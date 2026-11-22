package xyz.zood.george;

import android.content.Context;
import android.text.format.DateUtils;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.UiThread;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.CopyOnWriteArrayList;

import io.pijun.george.App;
import io.pijun.george.Hex;
import io.pijun.george.Prefs;
import io.pijun.george.MessageProcessor;
import io.pijun.george.crypto.EncryptedData;
import java.io.IOException;
import retrofit2.Response;
import io.pijun.george.UiRunnable;
import io.pijun.george.WorkerRunnable;
import io.pijun.george.api.OscarClient;
import io.pijun.george.api.UserComm;
import io.pijun.george.crypto.KeyPair;
import io.pijun.george.database.FriendRecord;

/**
 * Before we tell someone a friend's phone "can't answer location requests", make sure that's still true.
 * The cached report (FriendDevices) only changes when the friend sends a location, so a phone that became able
 * to answer again but hasn't moved keeps looking Unsupported. We ask it directly with a transient
 * device_status_request: a listening phone replies at once with device_status; silence means it really can't.
 */
public class DeviceStatusProbe {

    private static final long PROBE_TIMEOUT_MS = 6 * DateUtils.SECOND_IN_MILLIS;
    private static final long MIN_INTERVAL_MS = DateUtils.MINUTE_IN_MILLIS;

    private static final HashSet<String> probing = new HashSet<>();
    private static final HashMap<String, Long> lastProbe = new HashMap<>();
    private static final CopyOnWriteArrayList<WeakReference<Listener>> listeners = new CopyOnWriteArrayList<>();

    public interface Listener {
        @UiThread void onDeviceStatusChanged(@NonNull byte[] userId);
    }

    @AnyThread
    public static void addListener(@NonNull Listener l) {
        listeners.add(new WeakReference<>(l));
    }

    @AnyThread
    public static synchronized boolean isProbing(@NonNull byte[] userId) {
        return probing.contains(Hex.toHexString(userId));
    }

    /** Starts a probe if the cached report says this friend can't answer and we haven't asked recently. */
    @UiThread
    public static void probeIfNeeded(@NonNull Context ctx, @NonNull FriendRecord friend) {
        FriendDevices.Device device = FriendDevices.get(ctx, friend.user.userId);
        if (friend.receivingBoxId == null || device == null || !device.cannotAnswerRequests()) {
            return;
        }
        String key = Hex.toHexString(friend.user.userId);
        long now = System.currentTimeMillis();
        synchronized (DeviceStatusProbe.class) {
            Long last = lastProbe.get(key);
            if (probing.contains(key) || (last != null && now - last < MIN_INTERVAL_MS)) {
                return;
            }
            lastProbe.put(key, now);
            probing.add(key);
        }
        notifyChanged(friend.user.userId);
        Context app = ctx.getApplicationContext();
        App.runInBackground(new WorkerRunnable() {
            @Override
            public void run() {
                Prefs prefs = Prefs.get(app);
                String token = prefs.getAccessToken();
                KeyPair kp = prefs.getKeyPair();
                if (token != null && kp != null) {
                    // Refresh the package even if we have already seen this location: its device flags may differ.
                    try {
                        Response<EncryptedData> response = OscarClient.newInstance(token)
                                .pickUpPackage(Hex.toHexString(friend.receivingBoxId)).execute();
                        EncryptedData pkg = response.body();
                        if (response.isSuccessful() && pkg != null) {
                            MessageProcessor.decryptAndProcess(app, friend.user.userId, pkg.cipherText, pkg.nonce);
                        }
                    } catch (IOException ignored) {
                        // Still try the live status request if the package fetch fails.
                    }
                    FriendDevices.Device updated = FriendDevices.get(app, friend.user.userId);
                    if (updated != null && !updated.cannotAnswerRequests()) {
                        finish(friend.user.userId);
                        return;
                    }
                    OscarClient.immediatelySendMessage(friend.user, token, kp, UserComm.newDeviceStatusRequest(), true, true);
                }
            }
        });
        App.runOnUiThread(new UiRunnable() {
            @Override
            public void run() {
                finish(friend.user.userId);
            }
        }, PROBE_TIMEOUT_MS);
    }

    /** A device_status reply arrived (it's already been recorded in FriendDevices). */
    @AnyThread
    public static void onStatusReceived(@NonNull byte[] userId) {
        finish(userId);
    }

    @AnyThread
    private static void finish(@NonNull byte[] userId) {
        synchronized (DeviceStatusProbe.class) {
            probing.remove(Hex.toHexString(userId));
        }
        // Also notify on a reply that arrives without a probe, so the panel picks up the new status.
        notifyChanged(userId);
    }

    @AnyThread
    private static void notifyChanged(@NonNull byte[] userId) {
        App.runOnUiThread(new UiRunnable() {
            @Override
            public void run() {
                for (WeakReference<Listener> ref : listeners) {
                    Listener l = ref.get();
                    if (l != null) {
                        l.onDeviceStatusChanged(userId);
                    }
                }
            }
        });
    }
}
