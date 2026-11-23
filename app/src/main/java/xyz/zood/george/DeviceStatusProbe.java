package xyz.zood.george;

import android.content.Context;
import android.text.format.DateUtils;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.CopyOnWriteArrayList;

import io.pijun.george.App;
import io.pijun.george.Hex;
import io.pijun.george.Prefs;
import io.pijun.george.UiRunnable;
import io.pijun.george.api.OscarClient;
import io.pijun.george.api.UserComm;
import io.pijun.george.crypto.KeyPair;
import io.pijun.george.database.FriendRecord;

/**
 * Before we tell someone a friend's phone "can't answer location requests", make sure that's still true.
 * The cached report (FriendDevices) only changes when the friend sends a location, so a phone that became able
 * to answer again but hasn't moved keeps looking Unsupported. We ask it directly with a transient
 * device_status_request: a listening phone can reply with device_status. Silence leaves manual refresh available.
 */
public class DeviceStatusProbe {

    private static final long PROBE_TIMEOUT_MS = 6 * DateUtils.SECOND_IN_MILLIS;
    private static final long MIN_INTERVAL_MS = DateUtils.MINUTE_IN_MILLIS;

    private static final HashSet<String> probing = new HashSet<>();
    private static final HashMap<String, Long> unavailableUntil = new HashMap<>();
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

    public static synchronized boolean isConfirmedUnavailable(@NonNull byte[] userId) {
        Long expiration = unavailableUntil.get(Hex.toHexString(userId));
        return expiration != null && expiration > android.os.SystemClock.elapsedRealtime();
    }

    /** Starts a probe if the cached report says this friend can't answer and we haven't asked recently. */
    @UiThread
    public static void probeIfNeeded(@NonNull Context ctx, @NonNull FriendRecord friend) {
        FriendDevices.Device device = FriendDevices.get(ctx, friend.user.userId);
        if (friend.receivingBoxId == null || device == null || !device.cannotAnswerRequests()) {
            return;
        }
        Prefs prefs = Prefs.get(ctx);
        String token = prefs.getAccessToken();
        KeyPair keyPair = prefs.getKeyPair();
        if (token == null || keyPair == null) return;
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
        App.runInBackground(() -> OscarClient.immediatelySendMessage(
                friend.user, token, keyPair, UserComm.newDeviceStatusRequest(), true, true));
        App.runOnUiThread(new UiRunnable() {
            @Override
            public void run() {
                finish(friend.user.userId);
            }
        }, PROBE_TIMEOUT_MS);
    }

    /** A device_status reply arrived (it's already been recorded in FriendDevices). */
    @AnyThread
    public static void onStatusReceived(@NonNull byte[] userId, @Nullable Boolean liveRequests) {
        if (liveRequests != null) {
            String id = Hex.toHexString(userId);
            long expiration = android.os.SystemClock.elapsedRealtime() + 30_000;
            synchronized (DeviceStatusProbe.class) {
                if (liveRequests) unavailableUntil.remove(id);
                else unavailableUntil.put(id, expiration);
            }
            if (!liveRequests) {
                App.runOnUiThread(() -> {
                    synchronized (DeviceStatusProbe.class) {
                        if (!unavailableUntil.remove(id, expiration)) return;
                    }
                    notifyChanged(userId);
                }, 30_000);
            }
        }
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
