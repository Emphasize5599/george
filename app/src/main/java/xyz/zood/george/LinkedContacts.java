package xyz.zood.george;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.pijun.george.App;
import io.pijun.george.Hex;
import io.pijun.george.Prefs;

/**
 * Remembers which phone contact a friend is linked to, so the friend can appear under a name the
 * person already knows.
 *
 * Privacy: links live only in this phone's preferences. They are never sent to the server or written
 * into the encrypted backup, because a contact's lookup key means nothing on another device and the
 * address book isn't ours to share. Only the picked contact's display name and lookup URI are kept.
 *
 * Name priority (see {@link Nicknames#displayName}): your private nickname, then the linked contact,
 * then the friend's shared nickname, then their @username.
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

    public static void link(@NonNull byte[] friendUserId, @NonNull String name, @NonNull String contactUri) {
        prefs().edit()
                .putString(key(friendUserId, "name"), name)
                .putString(key(friendUserId, "uri"), contactUri)
                .apply();
        Nicknames.notifyListeners();
    }

    public static void unlink(@NonNull byte[] friendUserId) {
        prefs().edit()
                .remove(key(friendUserId, "name"))
                .remove(key(friendUserId, "uri"))
                .apply();
        Nicknames.notifyListeners();
    }
}
