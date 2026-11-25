package xyz.zood.george.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.text.format.DateUtils;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.core.app.NotificationCompat;
import androidx.core.location.LocationListenerCompat;
import androidx.core.location.LocationManagerCompat;
import androidx.core.location.LocationRequestCompat;

import java.util.concurrent.Future;

import io.pijun.george.App;
import io.pijun.george.AuthenticationManager;
import io.pijun.george.L;
import io.pijun.george.LocationUtils;
import io.pijun.george.MessageProcessor;
import io.pijun.george.Prefs;
import io.pijun.george.UiRunnable;
import io.pijun.george.WorkerRunnable;
import io.pijun.george.api.OscarClient;
import io.pijun.george.api.OscarSocket;
import io.pijun.george.api.PushNotification;
import io.pijun.george.api.UserComm;
import io.pijun.george.crypto.EncryptedData;
import io.pijun.george.crypto.KeyPair;
import io.pijun.george.database.DB;
import io.pijun.george.database.UserRecord;
import xyz.zood.george.MainActivity;
import xyz.zood.george.Permissions;
import xyz.zood.george.R;

/**
 * Family Locator fork: this fork can't receive Zood's FCM pushes (they're tied to the upstream Firebase project),
 * so instead we keep the oscar websocket open from a low-priority foreground service. The server delivers every
 * message, including transient location update requests, over that socket. Requests are answered from in here,
 * because starting a separate location foreground service from the background isn't allowed on Android 12+.
 */
public class LiveRequestService extends Service implements OscarSocket.Listener {

    private static final String CHANNEL_ID = "live_requests_01";
    private static final int NOTIFICATION_ID = 45;
    private static final String PREFS_NAME = "family_locator";
    private static final String KEY_ENABLED = "live_requests_enabled";
    private static final long FIX_TIMEOUT_MS = 30 * DateUtils.SECOND_IN_MILLIS;

    @Nullable private static volatile LiveRequestService instance;

    @Nullable private OscarSocket socket;
    private boolean stopping = false;
    private long reconnectDelayMs = 2000;

    // State for an in-progress request fulfillment (main thread only)
    @Nullable private LocationManager locMgr;
    @Nullable private Future<?> fixTimeout;
    @Nullable private PowerManager.WakeLock fixWakeLock;
    private final java.util.HashSet<Long> pendingRequesters = new java.util.HashSet<>();

    //region Public API

    @AnyThread
    public static boolean isRunning() {
        return instance != null;
    }

    @AnyThread
    public static boolean isEnabled(@NonNull Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true);
    }

    @AnyThread
    public static void setEnabled(@NonNull Context ctx, boolean enabled) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (enabled) {
            startIfEnabled(ctx);
        } else {
            stop(ctx);
        }
    }

    /** Safe to call often; only starts the service if the user is logged in, has enabled it, and has location access. */
    @AnyThread
    public static void startIfEnabled(@NonNull Context ctx) {
        if (instance != null || !isEnabled(ctx) || !AuthenticationManager.isLoggedIn(ctx)) {
            return;
        }
        if (!Permissions.checkForegroundLocationPermission(ctx)) {
            return;
        }
        try {
            ctx.startForegroundService(new Intent(ctx, LiveRequestService.class));
        } catch (IllegalStateException ex) {
            // Not allowed from the background right now; we'll try again next time the app is opened.
            L.w("LiveRequestService unable to start", ex);
        }
    }

    @AnyThread
    public static void stop(@NonNull Context ctx) {
        ctx.stopService(new Intent(ctx, LiveRequestService.class));
    }

    /**
     * Gets a fresh fix and uploads it for a friend's request.
     * @return false if the service isn't running (caller should fall back to the stock behaviour).
     */
    @AnyThread
    public static boolean fulfillRequest(long requestingUserId) {
        LiveRequestService svc = instance;
        if (svc == null) {
            return false;
        }
        App.runOnUiThread(new UiRunnable() {
            @Override
            public void run() {
                svc.beginFix(requestingUserId);
            }
        });
        return true;
    }

    //endregion

    //region Service lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        L.i("LiveRequestService.onCreate");
        locMgr = getSystemService(LocationManager.class);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } catch (Exception ex) {
            // Typically a sticky restart while in the background; give up until the app is opened again.
            L.w("LiveRequestService.startForeground failed", ex);
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean wasRunning = instance != null;
        instance = this;
        if (!wasRunning) {
            connect();
            // Let friends know we can answer requests now.
            beginFix(-1);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        L.i("LiveRequestService.onDestroy");
        stopping = true;
        instance = null;
        OscarSocket s = socket;
        socket = null;
        if (s != null) {
            s.disconnect();
        }
        endFix();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.live_requests_channel_name),
                NotificationManager.IMPORTANCE_MIN);
        channel.setDescription(getString(R.string.live_requests_channel_description));
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.live_requests_notification_title))
                .setContentText(getString(R.string.live_requests_notification_text))
                .setSmallIcon(R.drawable.ic_position_service)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
                .build();
    }

    //endregion

    //region Socket

    @AnyThread
    private void connect() {
        if (stopping) {
            return;
        }
        String token = Prefs.get(this).getAccessToken();
        if (token == null) {
            stopSelf();
            return;
        }
        OscarSocket s = new OscarSocket(this);
        socket = s;
        s.connect(token);
    }

    @Override
    public void onConnect() {
        L.i("LiveRequestService socket connected");
        reconnectDelayMs = 2000;
        // Catch up on anything sent while we were disconnected.
        String token = Prefs.get(this).getAccessToken();
        if (token != null) {
            MessageProcessor.retrieveAndProcessNewMessages(token);
        }
    }

    @Override
    public void onDisconnect(int code, String reason) {
        L.i("LiveRequestService socket disconnected: " + code + " " + reason);
        if (stopping) {
            return;
        }
        if (code == OscarSocket.CLOSE_CODE_INVALID_TOKEN) {
            AuthenticationManager.get().logOut(this, null);
            return;
        }
        long delay = reconnectDelayMs;
        reconnectDelayMs = Math.min(reconnectDelayMs * 2, 60 * DateUtils.SECOND_IN_MILLIS);
        App.runInBackground(new WorkerRunnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ignore) {}
                connect();
            }
        });
    }

    @Override
    public void onPackageReceived(@NonNull byte[] boxId, @NonNull EncryptedData data) {
        // We don't watch boxes from the service; the map screen does that while it's visible.
    }

    @Override
    public void onPushNotificationReceived(@NonNull PushNotification notification) {
        MessageProcessor.receiveSocketNotification(notification);
    }

    //endregion

    //region Location fix for requests

    /** @param requestingUserId local user id to notify when done, or -1 for a self-initiated update. */
    @UiThread
    @SuppressLint("MissingPermission")
    private void beginFix(long requestingUserId) {
        if (requestingUserId != -1) {
            pendingRequesters.add(requestingUserId);
        }
        if (fixTimeout != null) {
            return; // already working on one; the result will cover this requester too
        }
        LocationManager lm = locMgr;
        String provider = lm == null ? null : LocationUtils.getBestProvider(lm);
        if (provider == null || !Permissions.checkForegroundLocationPermission(this)) {
            endFix();
            return;
        }
        PowerManager pm = getSystemService(PowerManager.class);
        fixWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FamilyLocator:LiveRequestFix");
        fixWakeLock.acquire(FIX_TIMEOUT_MS + 5000);
        LocationRequestCompat req = new LocationRequestCompat.Builder(500)
                .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
                .build();
        LocationManagerCompat.requestLocationUpdates(lm, provider, req, fixListener, Looper.getMainLooper());
        fixTimeout = App.runOnUiThreadCancellable(FIX_TIMEOUT_MS, new UiRunnable() {
            @Override
            public void run() {
                endFix();
            }
        });
    }

    @UiThread
    private void endFix() {
        Future<?> t = fixTimeout;
        fixTimeout = null;
        if (t != null) {
            t.cancel(false);
        }
        LocationManager lm = locMgr;
        if (lm != null) {
            LocationManagerCompat.removeUpdates(lm, fixListener);
        }
        PowerManager.WakeLock wl = fixWakeLock;
        fixWakeLock = null;
        if (wl != null && wl.isHeld()) {
            wl.release();
        }
        if (pendingRequesters.isEmpty()) {
            return;
        }
        java.util.ArrayList<Long> requesters = new java.util.ArrayList<>(pendingRequesters);
        pendingRequesters.clear();
        App.runInBackground(new WorkerRunnable() {
            @Override
            public void run() {
                Prefs prefs = Prefs.get(App.getApp());
                String token = prefs.getAccessToken();
                KeyPair kp = prefs.getKeyPair();
                if (token == null || kp == null) {
                    return;
                }
                UserComm finished = UserComm.newLocationUpdateRequestReceived(UserComm.LOCATION_UPDATE_REQUEST_ACTION_FINISHED);
                for (long id : requesters) {
                    UserRecord user = DB.get().getUser(id);
                    if (user != null) {
                        OscarClient.queueSendMessage(OscarClient.getQueue(App.getApp()), user, kp, token, finished.toJSON(), true, true);
                    }
                }
            }
        });
    }

    private final LocationListenerCompat fixListener = new LocationListenerCompat() {
        @Override
        public void onLocationChanged(@NonNull Location loc) {
            LocationUtils.upload(loc);
            boolean isAccurate = loc.hasAccuracy() && loc.getAccuracy() <= 10;
            boolean isRecent = (System.currentTimeMillis() - loc.getTime()) < 30 * DateUtils.SECOND_IN_MILLIS;
            if (isAccurate && isRecent) {
                endFix();
            }
        }
    };

    //endregion
}
