package xyz.zood.george;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;

import io.pijun.george.CloudLogger;
import io.pijun.george.Constants;
import io.pijun.george.L;
import io.pijun.george.Prefs;
import io.pijun.george.api.OscarClient;
import io.pijun.george.api.UserComm;
import io.pijun.george.api.task.OscarTask;
import io.pijun.george.crypto.KeyPair;
import io.pijun.george.database.DB;
import io.pijun.george.database.FriendRecord;
import io.pijun.george.database.UserRecord;
import io.pijun.george.queue.PersistentQueue;
import xyz.zood.george.worker.BackupDatabaseWorker;

public class FriendshipManager {

    @NonNull private final String accessToken;
    @NonNull private final Context ctx;
    @NonNull private final DB db;
    @NonNull private final KeyPair keyPair;
    @NonNull private final PersistentQueue<OscarTask> queue;

    @AnyThread
    FriendshipManager(@NonNull Context ctx, @NonNull DB db, @NonNull PersistentQueue<OscarTask> queue, @NonNull String accessToken, @NonNull KeyPair keyPair) {
        this.ctx = ctx;
        this.db = db;
        this.accessToken = accessToken;
        this.keyPair = keyPair;
        this.queue = queue;
    }

    @WorkerThread
    void addFriend(@NonNull String username, boolean confirmedSharing, @NonNull OnAddFriendFinishedListener listener) {
        Prefs prefs = Prefs.get(ctx);
        if (Usernames.isOwn(username, prefs.getUsername())) {
            listener.onAddFriendFinished(AddFriendResult.CantAddSelf, null);
            return;
        }
        UserRecord user = db.getUser(Usernames.forLookup(username));
        if (user == null) {
            // the user should already have been added by the 'search as you type' feature
            listener.onAddFriendFinished(AddFriendResult.UserNotFound, null);
            return;
        }

        synchronized (FriendRequests.class) {
            if (Arrays.equals(user.userId, prefs.getUserId())) {
                listener.onAddFriendFinished(AddFriendResult.CantAddSelf, null);
                return;
            }
            if (FriendRequests.isBlocked(user.userId)) {
                listener.onAddFriendFinished(AddFriendResult.SharingGrantFailed, ctx.getString(R.string.unblock_before_sharing));
                return;
            }
            for (FriendRequests.Request request : FriendRequests.pending()) {
                if (!Arrays.equals(request.user.userId, user.userId)) continue;
                try {
                    FriendRequests.approve(request);
                    listener.onAddFriendFinished(AddFriendResult.Success, null);
                } catch (DB.DBException | IOException error) {
                    listener.onAddFriendFinished(AddFriendResult.DatabaseError, error.getLocalizedMessage());
                }
                return;
            }
            FriendRecord friend = db.getFriendByUserId(user.id);
            if (friend != null && friend.receivingBoxId != null) {
                listener.onAddFriendFinished(AddFriendResult.Success, null);
                return;
            }
            if (!confirmedSharing) {
                listener.onAddFriendFinished(AddFriendResult.ConfirmSharing, user.username);
                return;
            }
            addFriend(user, listener);
        }
    }

    private void addFriend(UserRecord user, OnAddFriendFinishedListener listener) {
        // check if we already have this user as a friend, and if we're already sharing with them
        final FriendRecord friend = db.getFriendByUserId(user.id);
        if (friend != null) {
            if (friend.sendingBoxId != null) {
                // send the sending box id to this person one more time, just in case
                UserComm comm = UserComm.newLocationSharingGrant(friend.sendingBoxId, friend.receivingBoxId);
                String errMsg = OscarClient.queueSendMessage(queue, user, keyPair, accessToken, comm.toJSON(), false, false);
                if (errMsg != null) {
                    CloudLogger.log(errMsg);
                    listener.onAddFriendFinished(AddFriendResult.SharingGrantFailed, errMsg);
                } else {
                    listener.onAddFriendFinished(AddFriendResult.Success, null);
                }
                return;
            }
        }

        byte[] sendingBoxId = new byte[Constants.DROP_BOX_ID_LENGTH];
        new SecureRandom().nextBytes(sendingBoxId);
        UserComm comm = UserComm.newLocationSharingGrant(sendingBoxId);
        String errMsg = OscarClient.queueSendMessage(queue, user, keyPair, accessToken, comm.toJSON(), false, false);
        if (errMsg != null) {
            listener.onAddFriendFinished(AddFriendResult.SharingGrantFailed, errMsg);
            return;
        }

        try {
            db.startSharingWith(user, sendingBoxId);
        } catch (DB.DBException dbe) {
            listener.onAddFriendFinished(AddFriendResult.DatabaseError, dbe.getLocalizedMessage());
            L.w("addFriend - startSharingWith", dbe);
            return;
        }

        try {
            AvatarManager.sendAvatarToUsers(ctx, Collections.singletonList(user), keyPair, accessToken);
        } catch (IOException ex) {
            L.w("addFriend - sendAvatarTouser", ex);
            // We're purposely not returning early here. This isn't a critical error.
        }

        listener.onAddFriendFinished(AddFriendResult.Success, null);
        BackupDatabaseWorker.scheduleBackup(ctx);
    }

    @UiThread
    public static void inviteFriend(@NonNull Context ctx) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.putExtra(Intent.EXTRA_TEXT, ctx.getString(R.string.invite_friend_msg));
        i.setType("text/plain");
        ctx.startActivity(Intent.createChooser(i, ctx.getString(R.string.send_to)));
    }

    @WorkerThread
    void removeFriend(@NonNull FriendRecord friend, @NonNull OnRemoveFriendFinishedListener listener) {
        synchronized (FriendRequests.class) {
            String error = OscarClient.queueSendMessage(queue, friend.user, keyPair, accessToken,
                    UserComm.newFriendRemoval().toJSON(), false, false);
            if (error != null) {
                L.w("Unable to queue friendship removal");
                listener.onRemoveFriendFinished(false);
                return;
            }
            try {
                FriendRequests.forget(friend.user);
                listener.onRemoveFriendFinished(true);
            } catch (DB.DBException | IOException ex) {
                L.w("Unable to remove friend locally", ex);
                listener.onRemoveFriendFinished(false);
            }
        }
    }

    @WorkerThread
    void startSharingWith(@NonNull FriendRecord friend, @NonNull OnShareOpFinishedListener listener) {
        synchronized (FriendRequests.class) {
            if (Arrays.equals(friend.user.userId, Prefs.get(ctx).getUserId())
                    || FriendRequests.isBlocked(friend.user.userId)) {
                listener.onShareOpFinished(false);
                return;
            }
            startSharing(friend, listener);
        }
    }

    private void startSharing(FriendRecord friend, OnShareOpFinishedListener listener) {
        byte[] sendingBoxId = new byte[Constants.DROP_BOX_ID_LENGTH];
        new SecureRandom().nextBytes(sendingBoxId);
        // send the sending box id to the friend
        FriendRecord current = db.getFriendByUserId(friend.user.id);
        if (current == null) {
            listener.onShareOpFinished(false);
            return;
        }
        UserComm comm = UserComm.newLocationSharingGrant(sendingBoxId, current.receivingBoxId);
        String errMsg = OscarClient.queueSendMessage(queue, friend.user, keyPair, accessToken, comm.toJSON(), false, false);
        if (errMsg != null) {
            L.w("startSharingWith - queueSendMessage", new RuntimeException(errMsg));
            listener.onShareOpFinished(false);
            return;
        }

        // add this to our database
        try {
            db.startSharingWith(friend.user, sendingBoxId);
        } catch (DB.DBException ex) {
            L.w("startSharingWith - startSharingWith", ex);
            listener.onShareOpFinished(false);
        }

        // notify the listener before doing any low priority work
        listener.onShareOpFinished(true);

        // send the friend our avatar
        try {
            AvatarManager.sendAvatarToUser(ctx, friend.user);
        } catch (IOException ex) {
            L.w("startSharingWith - sendAvatarToUser", ex);
            listener.onShareOpFinished(false);
        }
        BackupDatabaseWorker.scheduleBackup(ctx);
    }

    @WorkerThread
    void stopSharingWith(@NonNull FriendRecord friend, @NonNull OnShareOpFinishedListener listener) {
        // remove the sending box id from the database
        try {
            db.stopSharingWith(friend.user);
        } catch (DB.DBException ex) {
            L.w("stopSharingWith - db.stopSharingWith", ex);
            listener.onShareOpFinished(false);
        }
        BackupDatabaseWorker.scheduleBackup(ctx);

        UserComm comm = UserComm.newLocationSharingRevocation();
        String errMsg = OscarClient.queueSendMessage(queue, friend.user, keyPair, accessToken, comm.toJSON(), false, false);
        if (errMsg != null) {
            L.w("stopSharingWith - queueSendMessage", new RuntimeException(errMsg));
            listener.onShareOpFinished(false);
        }
        listener.onShareOpFinished(true);
    }

    enum AddFriendResult {
        Success,
        ConfirmSharing,
        CantAddSelf,
        UserNotFound,
        SharingGrantFailed,
        DatabaseError,
    }

    interface OnAddFriendFinishedListener {
        @WorkerThread
        void onAddFriendFinished(@NonNull AddFriendResult result, @Nullable String extraInfo);
    }

    interface OnShareOpFinishedListener {
        @WorkerThread
        void onShareOpFinished(boolean success);
    }

    interface OnRemoveFriendFinishedListener {
        @WorkerThread
        void onRemoveFriendFinished(boolean success);
    }

}
