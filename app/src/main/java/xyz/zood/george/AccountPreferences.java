package xyz.zood.george;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import io.pijun.george.Hex;
import io.pijun.george.Prefs;
import io.pijun.george.api.OscarClient;
import xyz.zood.george.worker.BackupDatabaseWorker;

/** Editable account preferences, cached locally and carried in the encrypted snapshot. */
public final class AccountPreferences {
    @Nullable public Boolean shareWithNewFriends;
    @Nullable public Boolean liveRequestsEnabled;
    @Nullable public Integer folderColumns;
    @Nullable public Integer folderRows;
    @Nullable public Boolean completedOnboarding;

    private static SharedPreferences storage(Context context) {
        return context.getSharedPreferences("account_preferences", Context.MODE_PRIVATE);
    }

    public static AccountPreferences get(Context context) {
        byte[] account = Prefs.get(context).getUserId();
        return account == null ? new AccountPreferences() : load(context, account);
    }

    private static AccountPreferences load(Context context, byte[] account) {
        String key = Hex.toHexString(account);
        var saved = OscarClient.sGson.fromJson(storage(context).getString(key, null), AccountPreferences.class);
        if (saved != null) return saved;
        var defaults = new AccountPreferences();
        defaults.shareWithNewFriends = context.getSharedPreferences("new_friend_sharing", Context.MODE_PRIVATE)
                .getBoolean(key, true);
        defaults.liveRequestsEnabled = context.getSharedPreferences("family_locator", Context.MODE_PRIVATE)
                .getBoolean("live_requests_enabled", true);
        defaults.folderColumns = FriendBarFragment.legacyGridSize(context, 0);
        defaults.folderRows = FriendBarFragment.legacyGridSize(context, 1);
        defaults.completedOnboarding = Prefs.get(context).getShownOnboarding();
        return defaults;
    }

    public static synchronized void restore(Context context, @Nullable AccountPreferences remote) {
        byte[] account = Prefs.get(context).getUserId();
        if (account == null) return;
        AccountPreferences local = load(context, account);
        if (remote != null) {
            if (remote.shareWithNewFriends != null) local.shareWithNewFriends = remote.shareWithNewFriends;
            if (remote.liveRequestsEnabled != null) local.liveRequestsEnabled = remote.liveRequestsEnabled;
            if (remote.folderColumns != null) local.folderColumns = remote.folderColumns;
            if (remote.folderRows != null) local.folderRows = remote.folderRows;
            if (remote.completedOnboarding != null) local.completedOnboarding = remote.completedOnboarding;
        }
        save(context, account, local);
    }

    public interface Change {
        void apply(AccountPreferences preferences);
    }

    public static synchronized void update(Context context, Change change) {
        byte[] account = Prefs.get(context).getUserId();
        if (account == null) return;
        AccountPreferences preferences = load(context, account);
        change.apply(preferences);
        save(context, account, preferences);
        BackupDatabaseWorker.scheduleBackup(context.getApplicationContext());
    }

    private static void save(Context context, byte[] account, AccountPreferences preferences) {
        storage(context).edit().putString(Hex.toHexString(account), OscarClient.sGson.toJson(preferences)).apply();
    }

    static void deleteForAccount(Context context, byte[] account) {
        if (account != null) storage(context).edit().remove(Hex.toHexString(account)).apply();
    }
}
