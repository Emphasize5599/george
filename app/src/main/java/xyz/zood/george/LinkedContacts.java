package xyz.zood.george;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;

import io.pijun.george.App;
import io.pijun.george.Hex;
import io.pijun.george.Prefs;
import io.pijun.george.api.OscarClient;
import io.pijun.george.database.Snapshot;
import xyz.zood.george.worker.BackupDatabaseWorker;

/**
 * Remembers which phone contact a friend is linked to, so the friend can appear under a name the
 * person already knows.
 *
 * Privacy: only the picked contact's display name and lookup URI are kept, in this phone's preferences
 * and in the end-to-end encrypted account backup (so links come back after signing in again; the server
 * can't read them). The rest of the address book is never read, and links are never sent to the friend.
 *
 * Name priority (see {@link Nicknames#displayName}): your private nickname, then the linked contact,
 * then the friend's shared nickname, then their @username.
 *
 * This class also keeps the backup's blocked-user list, which the iPhone app writes. Android doesn't block
 * accounts yet, so it just carries that list through its own backups unchanged.
 */
public final class LinkedContacts {

    private LinkedContacts() {}

    private static SharedPreferences prefs() {
        return App.getApp().getSharedPreferences("linked_contacts", Context.MODE_PRIVATE);
    }

    // Keyed by the signed-in account as well, so one person's links never show up for another account.
    private static String key(@NonNull byte[] friendUserId, @NonNull String field) {
        String account = Hex.toHexString(Prefs.get(App.getApp()).getUserId());
        return account + ":" + Hex.toHexString(friendUserId) + ":" + field;
    }

    /** The linked contact's display name, or null if this friend isn't linked. */
    @Nullable
    public static String name(@NonNull byte[] friendUserId) {
        return prefs().getString(key(friendUserId, "name"), null);
    }

    /** The linked contact's lookup URI on this phone, or null (also null for a link restored from an iPhone). */
    @Nullable
    public static String uri(@NonNull byte[] friendUserId) {
        String uri = prefs().getString(key(friendUserId, "uri"), null);
        return uri == null || uri.isEmpty() ? null : uri;
    }

    public static void link(@NonNull byte[] friendUserId, @NonNull String name, @NonNull String contactUri) {
        prefs().edit()
                .putString(key(friendUserId, "name"), name)
                .putString(key(friendUserId, "uri"), contactUri)
                .apply();
        changed();
    }

    public static void unlink(@NonNull byte[] friendUserId) {
        prefs().edit()
                .remove(key(friendUserId, "name"))
                .remove(key(friendUserId, "uri"))
                .apply();
        changed();
    }

    private static void changed() {
        Nicknames.notifyListeners();
        BackupDatabaseWorker.scheduleBackup(App.getApp());
    }

    /**
     * After signing in: restore links and the carried blocked list from the backup. A link already made on
     * this phone wins. A link restored from an iPhone keeps only its name, since an iOS Contacts identifier
     * means nothing here.
     */
    public static void restore(@NonNull Snapshot snapshot) {
        SharedPreferences.Editor editor = prefs().edit();
        for (Snapshot.Friend friend : snapshot.friends) {
            if (friend.userId == null || friend.linkedContactName == null || friend.linkedContactName.isEmpty()) {
                continue;
            }
            if (name(friend.userId) != null) {
                continue;
            }
            editor.putString(key(friend.userId, "name"), friend.linkedContactName);
            boolean fromAndroid = "android".equals(friend.linkedContactPlatform) && friend.linkedContactId != null;
            editor.putString(key(friend.userId, "uri"), fromAndroid ? friend.linkedContactId : "");
        }
        if (snapshot.blocked != null) {
            editor.putString(blockedKey(), OscarClient.sGson.toJson(snapshot.blocked.toArray(new Snapshot.User[0])));
        }
        editor.apply();
        Nicknames.notifyListeners();
    }

    /** The blocked-user list to write back into the next backup (null if the backup never had one). */
    @Nullable
    public static ArrayList<Snapshot.User> carriedBlockedUsers() {
        String json = prefs().getString(blockedKey(), null);
        if (json == null) {
            return null;
        }
        try {
            Snapshot.User[] users = OscarClient.sGson.fromJson(json, Snapshot.User[].class);
            return users == null ? null : new ArrayList<>(Arrays.asList(users));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String blockedKey() {
        return Hex.toHexString(Prefs.get(App.getApp()).getUserId()) + ":blocked";
    }
}
