package xyz.zood.george;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import io.pijun.george.App;
import io.pijun.george.Hex;
import io.pijun.george.L;
import io.pijun.george.Prefs;
import io.pijun.george.api.OscarClient;
import io.pijun.george.api.UserComm;
import io.pijun.george.database.DB;
import io.pijun.george.database.FriendRecord;
import io.pijun.george.database.Snapshot;
import io.pijun.george.database.UserRecord;
import xyz.zood.george.worker.BackupDatabaseWorker;

/**
 * Display names never replace the immutable username used for identity and account lookup.
 * Preferences avoid a SQLite schema bump, which would stop older Zood clients restoring backups.
 * The same optional fields are included in the encrypted backup for cross-device restoration.
 */
public final class Nicknames {

    private static final CopyOnWriteArrayList<WeakReference<Runnable>> listeners = new CopyOnWriteArrayList<>();

    private Nicknames() {}

    private static SharedPreferences prefs() {
        return App.getApp().getSharedPreferences("nicknames", Context.MODE_PRIVATE);
    }

    // Separate each signed-in account so a nickname chosen by one person cannot leak into another's UI.
    private static String key(String suffix) {
        return Hex.toHexString(Prefs.get(App.getApp()).getUserId()) + ":" + suffix;
    }

    public static String normalized(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder();
        value.codePoints().filter(c -> !Character.isISOControl(c)).forEach(cleaned::appendCodePoint);
        String trimmed = cleaned.toString().trim();
        String result = trimmed.substring(0, trimmed.offsetByCodePoints(0, Math.min(64, trimmed.codePointCount(0, trimmed.length()))));
        return result.isEmpty() ? null : result;
    }

    public static String own() {
        return prefs().getString(key("own"), null);
    }

    public static String local(byte[] userId) {
        return prefs().getString(key("local:" + Hex.toHexString(userId)), null);
    }

    public static String shared(byte[] userId) {
        return prefs().getString(key("shared:" + Hex.toHexString(userId)), null);
    }

    /** The name to show for a friend: private nickname, linked contact, shared nickname, then @username. */
    public static String displayName(UserRecord user) {
        String name = chosenName(user);
        return name == null ? Usernames.display(user.username) : name;
    }

    /** Same order as {@link #displayName}, without the "@" so avatar initials use the first letter. */
    public static String avatarName(UserRecord user) {
        String name = chosenName(user);
        return name == null ? user.username : name;
    }

    private static String chosenName(UserRecord user) {
        // A name you picked yourself always wins over one the friend shared with you.
        String name = local(user.userId);
        if (name == null) {
            name = LinkedContacts.name(user.userId);
        }
        if (name == null) {
            name = shared(user.userId);
        }
        return name;
    }

    private static void save(String suffix, String value) {
        String normalized = normalized(value);
        if (Objects.equals(prefs().getString(key(suffix), null), normalized)) {
            return;
        }
        prefs().edit().putString(key(suffix), normalized).apply();
        BackupDatabaseWorker.scheduleBackup(App.getApp());
        notifyListeners();
    }

    /** Tells open screens that a friend's display name may have changed (nickname or linked contact). */
    static void notifyListeners() {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (WeakReference<Runnable> reference : listeners) {
                Runnable listener = reference.get();
                if (listener == null) {
                    listeners.remove(reference);
                } else {
                    listener.run();
                }
            }
        });
    }

    public static void observe(Runnable listener) {
        listeners.removeIf(reference -> reference.get() == null || reference.get() == listener);
        listeners.add(new WeakReference<>(listener));
    }

    public static void setLocal(byte[] userId, String value) {
        save("local:" + Hex.toHexString(userId), value);
    }

    public static void storeOwn(String value) {
        save("own", value);
    }

    public static void setOwn(String value) {
        storeOwn(value);
        App.runInBackground(() -> {
            try {
                for (FriendRecord friend : DB.get().getFriends()) {
                    sendTo(friend.user);
                }
            } catch (Exception error) {
                L.w("Nicknames.setOwn", error);
            }
        });
    }

    public static void sendTo(UserRecord user) {
        Prefs prefs = Prefs.get(App.getApp());
        OscarClient.queueSendMessage(OscarClient.getQueue(App.getApp()), user,
                prefs.getKeyPair(), prefs.getAccessToken(), UserComm.newNicknameUpdate().toJSON(), false, false);
    }

    static void recordShared(byte[] userId, String nickname) {
        save("shared:" + Hex.toHexString(userId), nickname);
    }

    public static void record(UserRecord user, UserComm comm) {
        // Missing means an older client; empty explicitly clears a previously shared nickname.
        if (comm.nickname != null && DB.get().getFriendByUserId(user.id) != null) {
            recordShared(user.userId, comm.nickname);
        }
    }

    static void deleteForAccount(byte[] account) {
        String prefix = Hex.toHexString(account) + ":";
        SharedPreferences.Editor editor = prefs().edit();
        for (String key : prefs().getAll().keySet()) {
            if (key.startsWith(prefix)) editor.remove(key);
        }
        editor.apply();
    }

    public static void restore(Snapshot snapshot) {
        SharedPreferences.Editor editor = prefs().edit().putString(key("own"), normalized(snapshot.nickname));
        for (Snapshot.Friend friend : snapshot.friends) {
            editor.putString(key("local:" + Hex.toHexString(friend.userId)), normalized(friend.nickname));
            editor.putString(key("shared:" + Hex.toHexString(friend.userId)), normalized(friend.sharedNickname));
        }
        editor.apply();
    }
}
