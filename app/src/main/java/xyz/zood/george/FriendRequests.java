package xyz.zood.george;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

import io.pijun.george.App;
import io.pijun.george.Hex;
import io.pijun.george.L;
import io.pijun.george.MessageProcessor;
import io.pijun.george.Prefs;
import io.pijun.george.api.OscarClient;
import io.pijun.george.api.UserComm;
import io.pijun.george.database.DB;
import io.pijun.george.database.FriendRecord;
import io.pijun.george.database.Snapshot;
import io.pijun.george.database.UserRecord;
import xyz.zood.george.worker.BackupDatabaseWorker;

/** Local consent for incoming Zood sharing grants. Only blocks are included in backups. */
public final class FriendRequests {
    private FriendRequests() {}

    public static final class Request {
        public Snapshot.User user;
        public byte[] boxId;
        public String nickname;

        public String displayName() {
            return nickname == null ? Usernames.display(user.username) : nickname;
        }
    }

    private static SharedPreferences preferences() {
        return App.getApp().getSharedPreferences("friend_requests", Context.MODE_PRIVATE);
    }

    private static String accountKey() {
        return Hex.toHexString(Prefs.get(App.getApp()).getUserId());
    }

    static void deleteForAccount(byte[] account) {
        preferences().edit().remove(Hex.toHexString(account)).apply();
    }

    public static ArrayList<Request> pending() {
        ArrayList<Request> result = storedRequests();
        result.removeIf(request -> isBlocked(request.user.userId));
        result.sort(Comparator.comparing(request -> request.user.username, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private static ArrayList<Request> storedRequests() {
        String json = preferences().getString(accountKey(), null);
        Request[] requests = OscarClient.sGson.fromJson(json, Request[].class);
        return requests == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(requests));
    }

    public static ArrayList<Snapshot.User> blocked() {
        ArrayList<Snapshot.User> users = LinkedContacts.blockedUsers();
        return users == null ? new ArrayList<>() : users;
    }

    public static boolean isBlocked(byte[] userId) {
        return contains(LinkedContacts.blockedUsers(), userId);
    }

    public static boolean contains(@Nullable ArrayList<Snapshot.User> users, byte[] userId) {
        if (users != null) {
            for (Snapshot.User user : users) {
                if (Arrays.equals(user.userId, userId)) return true;
            }
        }
        return false;
    }

    public static synchronized void receive(UserRecord user, UserComm comm) throws IOException, DB.DBException {
        if (isBlocked(user.userId)) return;
        FriendRecord friend = DB.get().getFriendByUserId(user.id);
        ArrayList<Request> requests = pending();
        boolean removedPending = requests.removeIf(request -> Arrays.equals(request.user.userId, user.userId));
        if (friend != null && (friend.receivingBoxId != null || comm.repliesToSharingOffer(friend.sendingBoxId))) {
            DB.get().sharingGrantedBy(user, comm.dropBox);
            if (removedPending) save(requests);
            BackupDatabaseWorker.scheduleBackup(App.getApp());
            fetchLatestIfSignedIn(user, comm.dropBox);
            return;
        }
        Request request = new Request();
        request.user = new Snapshot.User();
        request.user.userId = user.userId;
        request.user.publicKey = user.publicKey;
        request.user.username = user.username;
        request.boxId = comm.dropBox;
        request.nickname = Nicknames.normalized(comm.nickname);
        requests.add(request);
        save(requests);
    }

    public static synchronized void revoke(UserRecord user) throws IOException {
        ArrayList<Request> requests = pending();
        if (requests.removeIf(request -> Arrays.equals(request.user.userId, user.userId))) save(requests);
        DB.get().sharingRevokedBy(user);
        BackupDatabaseWorker.scheduleBackup(App.getApp());
    }

    public static synchronized void forget(UserRecord user) throws IOException, DB.DBException {
        forget(user.userId, user);
    }

    private static void forget(byte[] userId, @Nullable UserRecord user) throws IOException, DB.DBException {
        ArrayList<Request> requests = storedRequests();
        if (requests.removeIf(request -> Arrays.equals(request.user.userId, userId))) save(requests);
        if (user != null) {
            FriendRecord friend = DB.get().getFriendByUserId(user.id);
            if (friend != null) DB.get().removeFriend(friend);  // also deletes their last location
        }
        LinkedContacts.forgetFriend(userId);
        BackupDatabaseWorker.scheduleBackup(App.getApp());
    }

    static synchronized void deny(Request request) throws IOException {
        ArrayList<Request> requests = pending();
        if (requests.removeIf(current -> matches(current, request))) save(requests);
    }

    static synchronized void approve(Request request) throws IOException, DB.DBException {
        if (pending().stream().noneMatch(current -> matches(current, request))) return;
        UserRecord user = DB.get().getUser(request.user.userId);
        if (user == null) {
            user = DB.get().addUser(request.user.userId, request.user.username, request.user.publicKey);
        }
        DB.get().sharingGrantedBy(user, request.boxId);
        Nicknames.recordShared(user.userId, request.nickname);
        BackupDatabaseWorker.scheduleBackup(App.getApp());
        deny(request);
        var prefs = Prefs.get(App.getApp());
        var key = prefs.getKeyPair();
        String token = prefs.getAccessToken();
        if (key != null && token != null) {
            OscarClient.queueSendMessage(OscarClient.getQueue(App.getApp()), user, key, token,
                    UserComm.newAvatarRequest().toJSON(), false, false);
        }
        fetchLatestIfSignedIn(user, request.boxId);
    }

    private static void fetchLatestIfSignedIn(UserRecord user, byte[] boxId) {
        String token = Prefs.get(App.getApp()).getAccessToken();
        if (token != null) App.runInBackground(() -> fetchLatest(user, boxId, token));
    }

    private static void fetchLatest(UserRecord user, byte[] boxId, String token) {
        try {
            var response = OscarClient.newInstance(token).pickUpPackage(Hex.toHexString(boxId)).execute();
            var packet = response.body();
            if (packet == null || !response.isSuccessful()) return;
            synchronized (FriendRequests.class) {
                if (!token.equals(Prefs.get(App.getApp()).getAccessToken()) || isBlocked(user.userId)) return;
                FriendRecord friend = DB.get().getFriendByUserId(user.id);
                if (friend != null && Arrays.equals(friend.receivingBoxId, boxId)) {
                    MessageProcessor.decryptAndProcess(App.getApp(), user.userId, packet.cipherText, packet.nonce);
                }
            }
        } catch (IOException error) {
            L.w("Unable to fetch the first approved location", error);
        }
    }

    static synchronized void block(Snapshot.User user) throws IOException, DB.DBException {
        ArrayList<Snapshot.User> users = blocked();
        if (!contains(users, user.userId)) users.add(user);
        LinkedContacts.saveBlockedUsers(users);
        BackupDatabaseWorker.scheduleBackup(App.getApp());
        forget(user.userId, DB.get().getUser(user.userId));
        queueRemoval(user);
    }

    static synchronized void unblock(Snapshot.User user) throws IOException, DB.DBException {
        ArrayList<Snapshot.User> users = blocked();
        if (!contains(users, user.userId)) return;
        forget(user.userId, DB.get().getUser(user.userId));
        // Repair older blocks even when their local friend row is already gone.
        queueRemoval(user);
        users.removeIf(blockedUser -> Arrays.equals(blockedUser.userId, user.userId));
        LinkedContacts.saveBlockedUsers(users);
        BackupDatabaseWorker.scheduleBackup(App.getApp());
    }

    public static synchronized void enforceBlocks() throws IOException, DB.DBException {
        for (Snapshot.User user : blocked()) forget(user.userId, DB.get().getUser(user.userId));
    }

    private static void queueRemoval(Snapshot.User user) throws IOException {
        Context context = App.getApp();
        Prefs prefs = Prefs.get(context);
        String token = prefs.getAccessToken();
        var key = prefs.getKeyPair();
        if (token == null || key == null) {
            if (BuildConfig.DEBUG && token == null && key == null) return;
            throw new IOException("Unable to send friendship removal while signed out");
        }
        UserRecord recipient = new UserRecord();
        recipient.userId = user.userId;
        recipient.username = user.username;
        recipient.publicKey = user.publicKey;
        String error = OscarClient.queueSendMessage(OscarClient.getQueue(context), recipient, key, token,
                UserComm.newFriendRemoval().toJSON(), false, false);
        if (error != null) throw new IOException(error);
    }

    private static boolean matches(Request first, Request second) {
        return Arrays.equals(first.user.userId, second.user.userId) && Arrays.equals(first.boxId, second.boxId);
    }

    private static void save(ArrayList<Request> requests) throws IOException {
        if (!preferences().edit().putString(accountKey(), OscarClient.sGson.toJson(requests)).commit()) {
            throw new IOException("Unable to save friend requests");
        }
        Nicknames.notifyListeners();
    }
}
