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
    /** How long an explicit "Live Requests is off" report is trusted without re-checking. */
    static final long RECENT_REPORT_MS = 6 * 60 * 60 * 1000L;

    public static class Device {
        @Nullable public final String platform;
        @Nullable public final Boolean liveRequests;
        /** When this report arrived (System.currentTimeMillis), or 0 for reports saved by older versions. */
        public final long reportedAt;

        Device(@Nullable String platform, @Nullable Boolean liveRequests, long reportedAt) {
            this.platform = platform;
            this.liveRequests = liveRequests;
            this.reportedAt = reportedAt;
        }

        /**
         * The friend's phone recently said outright that Live Requests is off (it sends this with every
         * location update, and again as soon as the setting changes). That's trusted right away, without
         * waiting for a reply: a phone with Live Requests off, especially an iPhone, usually can't reply.
         */
        public boolean recentlyReportedOff() {
            return cannotAnswerRequests() && reportedAt > 0
                    && System.currentTimeMillis() - reportedAt < RECENT_REPORT_MS;
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
        String value = (comm.platform == null ? "" : comm.platform) + "|" + live + "|" + System.currentTimeMillis();
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
        long reportedAt = 0;
        if (parts.length > 2) {
            try { reportedAt = Long.parseLong(parts[2]); } catch (NumberFormatException ignored) { }
        }
        return new Device(platform, live, reportedAt);
    }

    @AnyThread
    public static void clearAll(@NonNull Context ctx) {
        prefs(ctx).edit().clear().apply();
    }
}
