package xyz.zood.george;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

import io.pijun.george.App;
import io.pijun.george.Hex;
import io.pijun.george.L;
import io.pijun.george.Prefs;
import io.pijun.george.api.OscarClient;
import io.pijun.george.database.DB;
import io.pijun.george.database.FriendRecord;
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
 * This class also stores account-scoped blocks, folders and friend bar order in the existing backup format.
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

    /** The identifier and platform used by the encrypted backup. */
    @Nullable
    public static String uri(@NonNull byte[] friendUserId) {
        String uri = prefs().getString(key(friendUserId, "uri"), null);
        return uri == null || uri.isEmpty() ? null : uri;
    }

    @Nullable
    public static String platform(@NonNull byte[] friendUserId) {
        return prefs().getString(key(friendUserId, "platform"), uri(friendUserId) == null ? null : "android");
    }

    static void deleteForAccount(byte[] account) {
        String prefix = Hex.toHexString(account) + ":";
        SharedPreferences.Editor editor = prefs().edit();
        for (String key : prefs().getAll().keySet()) {
            if (key.startsWith(prefix)) editor.remove(key);
        }
        editor.apply();
        File directory = new File(App.getApp().getFilesDir(), "contact_photos");
        File[] photos = directory.listFiles((dir, name) -> name.startsWith(prefix));
        if (photos != null) {
            for (File photo : photos) new AtomicFile(photo).delete();
        }
    }

    public static void link(@NonNull byte[] friendUserId, @NonNull String name, @NonNull String contactUri) {
        prefs().edit()
                .putString(key(friendUserId, "name"), name)
                .putString(key(friendUserId, "uri"), contactUri)
                .putString(key(friendUserId, "platform"), "android")
                .apply();
        Nicknames.setLocal(friendUserId, name);
        changed();
    }

    static synchronized boolean linkPickedContact(byte[] friendId, Uri contactUri) {
        var context = App.getApp();
        var resolver = context.getContentResolver();
        String name = null;
        boolean readContact = false;
        String given = ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME;
        String family = ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME;
        try (Cursor cursor = resolver.query(Uri.withAppendedPath(contactUri, ContactsContract.Contacts.Entity.CONTENT_DIRECTORY),
                new String[]{given, family}, ContactsContract.Data.MIMETYPE + "=?",
                new String[]{ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE}, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                readContact = true;
                String first = cursor.getString(0);
                String last = cursor.getString(1);
                name = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
            }
        } catch (IllegalArgumentException | SecurityException ignored) {
            // Some contacts providers only grant access to the selected contact row.
        }
        if (name == null || name.isEmpty()) {
            try (Cursor cursor = resolver.query(contactUri,
                    new String[]{ContactsContract.Contacts.DISPLAY_NAME_PRIMARY}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    readContact = true;
                    name = cursor.getString(0);
                }
            } catch (IllegalArgumentException | SecurityException error) {
                L.w("Unable to read the selected contact", error);
            }
        }
        if (!readContact) return false;
        if (name == null || name.trim().isEmpty()) name = context.getString(R.string.unnamed_contact);

        saveContactPhoto(contactUri, new AtomicFile(photoFile(friendId)), true);
        link(friendId, name.trim(), contactUri.toString());
        return true;
    }

    static ArrayList<FriendRecord> missingPhotos() {
        var friends = DB.get().getFriends();
        friends.removeIf(friend -> {
            byte[] id = friend.user.userId;
            return !"android".equals(platform(id)) || uri(id) == null || photoFile(id).exists();
        });
        return friends;
    }

    static synchronized void restorePhotos(byte[] account, ArrayList<FriendRecord> missing) {
        boolean changed = false;
        for (var friend : missing) {
            if (!Arrays.equals(account, Prefs.get(App.getApp()).getUserId())) break;
            byte[] id = friend.user.userId;
            String reference = uri(id);
            File file = photoFile(id);
            if (reference == null || file.exists() || !"android".equals(platform(id))) continue;
            Uri contactUri = Uri.parse(reference);
            if (!"content".equals(contactUri.getScheme()) || !ContactsContract.AUTHORITY.equals(contactUri.getAuthority())) continue;
            changed |= saveContactPhoto(contactUri, new AtomicFile(file), false);
        }
        if (changed) Nicknames.notifyListeners();
    }

    private static boolean saveContactPhoto(Uri contactUri, AtomicFile photo, boolean replaceExisting) {
        if (replaceExisting) photo.delete();
        var resolver = App.getApp().getContentResolver();
        try (var stream = ContactsContract.Contacts.openContactPhotoInputStream(resolver, contactUri)) {
            Bitmap bitmap = stream == null ? null : BitmapFactory.decodeStream(stream);
            if (bitmap != null) {
                FileOutputStream output = null;
                try {
                    output = photo.startWrite();
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) throw new IOException("Unable to encode contact photo");
                    photo.finishWrite(output);
                    return true;
                } catch (IOException error) {
                    if (output != null) photo.failWrite(output);
                    throw error;
                } finally {
                    bitmap.recycle();
                }
            }
        } catch (IOException | IllegalArgumentException | SecurityException error) {
            L.w("Unable to save selected contact photo", error);
        }
        return false;
    }

    public static File photoFile(byte[] friendId) {
        return new File(new File(App.getApp().getFilesDir(), "contact_photos"), key(friendId, "avatar") + ".jpg");
    }

    public static synchronized void unlink(@NonNull byte[] friendUserId) {
        String oldName = name(friendUserId);
        new AtomicFile(photoFile(friendUserId)).delete();
        prefs().edit()
                .remove(key(friendUserId, "name"))
                .remove(key(friendUserId, "uri"))
                .remove(key(friendUserId, "platform"))
                .apply();
        if (oldName != null && Objects.equals(Nicknames.normalized(oldName), Nicknames.local(friendUserId))) {
            Nicknames.setLocal(friendUserId, null);
        }
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
    public static synchronized void restore(@NonNull Snapshot snapshot) {
        SharedPreferences.Editor editor = prefs().edit();
        for (Snapshot.Friend friend : snapshot.friends) {
            if (friend.userId == null || friend.linkedContactName == null || friend.linkedContactName.isEmpty()) {
                continue;
            }
            if (name(friend.userId) != null) {
                continue;
            }
            editor.putString(key(friend.userId, "name"), friend.linkedContactName);
            editor.putString(key(friend.userId, "uri"), friend.linkedContactId);
            editor.putString(key(friend.userId, "platform"), friend.linkedContactPlatform);
        }
        if (snapshot.blocked != null) {
            editor.putString(blockedKey(), OscarClient.sGson.toJson(snapshot.blocked.toArray(new Snapshot.User[0])));
        }
        if (snapshot.folders != null) {
            editor.putString(accountKey("folders"), OscarClient.sGson.toJson(snapshot.folders.toArray(new Snapshot.Folder[0])));
        }
        if (snapshot.barOrder != null) {
            editor.putString(accountKey("bar_order"), OscarClient.sGson.toJson(snapshot.barOrder.toArray(new String[0])));
        }
        editor.apply();
        Nicknames.notifyListeners();
    }

    static void saveBlockedUsers(ArrayList<Snapshot.User> users) throws java.io.IOException {
        if (!prefs().edit().putString(blockedKey(), OscarClient.sGson.toJson(users)).commit()) {
            throw new java.io.IOException("Unable to save blocked users");
        }
        Nicknames.notifyListeners();
    }

    /**
     * Everything this phone remembers about a friend who was removed (or blocked): their folder membership
     * (a folder left with one person is dissolved), their place in the bar, the linked contact and nicknames.
     * Adding them again later starts fresh.
     */
    static synchronized void forgetFriend(@NonNull byte[] friendUserId) {
        ArrayList<Snapshot.Folder> folders = folders();
        ArrayList<String> order = barOrder();
        if (folders != null || order != null) {
            ArrayList<Snapshot.Folder> keptFolders = new ArrayList<>();
            if (folders != null) {
                for (Snapshot.Folder folder : folders) {
                    if (folder.members == null) continue;
                    folder.members.removeIf(member -> Arrays.equals(member, friendUserId));
                    if (folder.members.size() >= 2) keptFolders.add(folder);
                    else if (order != null && folder.id != null) order.remove(FriendBarArrangement.folderKey(folder));
                }
            }
            if (order != null) order.remove(FriendBarArrangement.friendKey(friendUserId));
            saveBar(keptFolders, order == null ? new ArrayList<>() : order);
        }
        new AtomicFile(photoFile(friendUserId)).delete();
        prefs().edit()
                .remove(key(friendUserId, "name"))
                .remove(key(friendUserId, "uri"))
                .remove(key(friendUserId, "platform"))
                .apply();
        Nicknames.setLocal(friendUserId, null);
        Nicknames.recordShared(friendUserId, null);
        changed();
    }

    static synchronized void saveBar(ArrayList<Snapshot.Folder> folders, ArrayList<String> order) {
        prefs().edit()
                .putString(accountKey("folders"), OscarClient.sGson.toJson(folders))
                .putString(accountKey("bar_order"), OscarClient.sGson.toJson(order))
                .apply();
        BackupDatabaseWorker.scheduleBackup(App.getApp());
    }

    public static synchronized void copyBarTo(Snapshot snapshot) {
        snapshot.folders = folders();
        snapshot.barOrder = barOrder();
    }

    /** The blocked list, or null if this account has no saved list. */
    @Nullable
    public static ArrayList<Snapshot.User> blockedUsers() {
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

    /** Friend bar folders (null if the backup had none). */
    @Nullable
    public static ArrayList<Snapshot.Folder> folders() {
        return carried(accountKey("folders"), Snapshot.Folder[].class);
    }

    /** Friend bar order (null if the backup had none). */
    @Nullable
    public static ArrayList<String> barOrder() {
        return carried(accountKey("bar_order"), String[].class);
    }

    @Nullable
    private static <T> ArrayList<T> carried(@NonNull String key, @NonNull Class<T[]> type) {
        String json = prefs().getString(key, null);
        if (json == null) {
            return null;
        }
        try {
            T[] values = OscarClient.sGson.fromJson(json, type);
            return values == null ? null : new ArrayList<>(Arrays.asList(values));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String blockedKey() {
        return accountKey("blocked");
    }

    private static String accountKey(@NonNull String name) {
        return Hex.toHexString(Prefs.get(App.getApp()).getUserId()) + ":" + name;
    }
}
