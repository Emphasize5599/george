package xyz.zood.george;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.pijun.george.Hex;

import io.pijun.george.api.UserComm;

/**
 * Remembers what platform each friend's device reported and whether it can answer location update requests.
 * Kept in SharedPreferences rather than the DB on purpose: bumping the DB version would change the backup
 * snapshot's schema_version and make stock Zood refuse to restore the backup.
 */
public class FriendDevices {

    private static final String PREFS_NAME = "friend_devices";

    public static class Device {
        @Nullable public final String platform;
        @Nullable public final Boolean liveRequests;

        Device(@Nullable String platform, @Nullable Boolean liveRequests) {
            this.platform = platform;
            this.liveRequests = liveRequests;
        }

        public boolean isIPhone() {
            return UserComm.PLATFORM_IOS.equals(platform);
        }

        /** Only devices that explicitly said they can't answer are unsupported. Stock Zood says nothing. */
        public boolean cannotAnswerRequests() {
            return Boolean.FALSE.equals(liveRequests);
        }
    }

    private static SharedPreferences prefs(@NonNull Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @AnyThread
    public static void record(@NonNull Context ctx, @NonNull byte[] userId, @NonNull UserComm comm) {
        if (comm.platform == null && comm.liveRequests == null) {
            return;
        }
        String key = Hex.toHexString(userId);
        String live = comm.liveRequests == null ? "" : (comm.liveRequests ? "1" : "0");
        String value = (comm.platform == null ? "" : comm.platform) + "|" + live;
        prefs(ctx).edit().putString(key, value).apply();
    }

    @AnyThread @Nullable
    public static Device get(@NonNull Context ctx, @NonNull byte[] userId) {
        String value = prefs(ctx).getString(Hex.toHexString(userId), null);
        if (value == null) {
            return null;
        }
        String[] parts = value.split("\\|", -1);
        String platform = parts.length > 0 && !parts[0].isEmpty() ? parts[0] : null;
        Boolean live = null;
        if (parts.length > 1 && !parts[1].isEmpty()) {
            live = parts[1].equals("1");
        }
        return new Device(platform, live);
    }

    @AnyThread
    public static void clearAll(@NonNull Context ctx) {
        prefs(ctx).edit().clear().apply();
    }
}
