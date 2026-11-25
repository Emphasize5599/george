package xyz.zood.george;

import android.content.Context;

import androidx.annotation.WorkerThread;

import java.io.IOException;

import io.pijun.george.Hex;
import io.pijun.george.Prefs;
import io.pijun.george.api.OscarClient;
import io.pijun.george.database.DB;
import io.pijun.george.database.UserRecord;

/** Optional account preference on this device. */
public final class NewFriendSharing {
    private NewFriendSharing() {}

    public static boolean isEnabled(Context context) {
        byte[] account = Prefs.get(context).getUserId();
        return account != null && context.getSharedPreferences("new_friend_sharing", Context.MODE_PRIVATE)
                .getBoolean(Hex.toHexString(account), false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        byte[] account = Prefs.get(context).getUserId();
        if (account != null) context.getSharedPreferences("new_friend_sharing", Context.MODE_PRIVATE)
                .edit().putBoolean(Hex.toHexString(account), enabled).apply();
    }

    static void deleteForAccount(Context context, byte[] account) {
        if (account != null) context.getSharedPreferences("new_friend_sharing", Context.MODE_PRIVATE)
                .edit().remove(Hex.toHexString(account)).apply();
    }

    @WorkerThread
    static void shareIfEnabled(Context context, UserRecord user, String accessToken) throws IOException {
        synchronized (FriendRequests.class) {
            Prefs prefs = Prefs.get(context);
            if (!isEnabled(context) || !accessToken.equals(prefs.getAccessToken())
                    || FriendRequests.isBlocked(user.userId)) return;
            var friend = DB.get().getFriendByUserId(user.id);
            var keys = prefs.getKeyPair();
            if (friend == null || friend.sendingBoxId != null || keys == null) return;
            var manager = new FriendshipManager(context, DB.get(), OscarClient.getQueue(context), accessToken, keys);
            if (!manager.startSharing(friend)) throw new IOException("Unable to start sharing with the new friend");
        }
    }
}
