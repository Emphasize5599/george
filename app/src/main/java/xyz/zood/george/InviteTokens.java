package xyz.zood.george;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.pijun.george.App;
import io.pijun.george.Hex;
import io.pijun.george.Prefs;
import io.pijun.george.api.OscarClient;
import io.pijun.george.database.Snapshot;
import xyz.zood.george.worker.BackupDatabaseWorker;

/** Issued invite tokens for the signed-in account, mirrored into the encrypted backup. */
public final class InviteTokens {

    private InviteTokens() {}

    private static SharedPreferences preferences() {
        return App.getApp().getSharedPreferences("invite_tokens", Context.MODE_PRIVATE);
    }

    @Nullable
    private static String accountKey() {
        byte[] account = Prefs.get(App.getApp()).getUserId();
        return account == null ? null : Hex.toHexString(account);
    }

    /** A new one-time link for {@code username}, remembered before it is shown. */
    public static synchronized String issueLink(String username) throws IOException {
        byte[] token = new byte[InviteLedger.TOKEN_LENGTH];
        new SecureRandom().nextBytes(token);
        String key = accountKey();
        if (key == null) throw new IOException("Not signed in");
        List<InviteLedger.Record> records = InviteLedger.issued(token, load(key));
        save(key, records);
        BackupDatabaseWorker.scheduleBackup(App.getApp());
        return FriendLink.url(username, token);
    }

    /**
     * Consumes {@code token} for {@code sender} when it is unused.
     * Returns false for an unknown token or a replay. The decision is saved before returning.
     */
    public static synchronized boolean authorize(byte[] token, byte[] sender) throws IOException {
        String key = accountKey();
        if (key == null) return false;
        List<InviteLedger.Record> records = load(key);
        if (!InviteLedger.authorize(token, sender, records)) return false;
        save(key, records);
        return true;
    }

    static synchronized boolean wasUsedBy(byte[] token, byte[] sender) {
        String key = accountKey();
        if (key == null) return false;
        return load(key).stream().anyMatch(record ->
                Arrays.equals(record.token, token) && Arrays.equals(record.usedBy, sender)
                        && !InviteLedger.isInvalidatedFor(record, sender));
    }

    static synchronized void invalidateFor(byte[] user) throws IOException {
        String key = accountKey();
        if (key == null) return;
        List<InviteLedger.Record> records = load(key);
        if (InviteLedger.invalidate(user, records)) {
            save(key, records);
            BackupDatabaseWorker.scheduleBackup(App.getApp());
        }
    }

    /** Joins backup tokens with the ones already on this phone. A token stays used if either copy says so. */
    public static synchronized void restore(@Nullable Snapshot snapshot) {
        if (snapshot == null) return;
        String key = accountKey();
        if (key == null) return;
        try {
            save(key, InviteLedger.merge(load(key), fromSnapshot(snapshot.invites)));
        } catch (IOException ignored) {
            // The next backup from this phone still has its own copy; a failed merge must not drop local tokens.
        }
    }

    @Nullable
    public static synchronized ArrayList<Snapshot.Invite> forBackup() {
        String key = accountKey();
        if (key == null) return null;
        List<InviteLedger.Record> records = load(key);
        if (records.isEmpty()) return null;
        ArrayList<Snapshot.Invite> invites = new ArrayList<>();
        for (InviteLedger.Record record : records) {
            Snapshot.Invite invite = new Snapshot.Invite();
            invite.token = record.token;
            invite.usedBy = record.usedBy;
            invite.invalidatedFor = record.invalidatedFor;
            invites.add(invite);
        }
        return invites;
    }

    static synchronized void deleteForAccount(byte[] account) {
        if (account == null) return;
        preferences().edit().remove(Hex.toHexString(account)).apply();
    }

    private static List<InviteLedger.Record> load(String key) {
        String json = preferences().getString(key, null);
        InviteLedger.Record[] records = OscarClient.sGson.fromJson(json, InviteLedger.Record[].class);
        if (records == null) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(records));
    }

    private static void save(String key, List<InviteLedger.Record> records) throws IOException {
        if (!preferences().edit().putString(key, OscarClient.sGson.toJson(records)).commit()) {
            throw new IOException("Unable to save invite links");
        }
    }

    private static List<InviteLedger.Record> fromSnapshot(@Nullable ArrayList<Snapshot.Invite> invites) {
        List<InviteLedger.Record> records = new ArrayList<>();
        if (invites == null) return records;
        for (Snapshot.Invite invite : invites) {
            if (invite == null || invite.token == null) continue;
            InviteLedger.Record record = new InviteLedger.Record();
            record.token = invite.token;
            record.usedBy = invite.usedBy;
            record.invalidatedFor = invite.invalidatedFor;
            records.add(record);
        }
        return records;
    }
}
