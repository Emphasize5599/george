package xyz.zood.george;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

import io.pijun.george.App;
import io.pijun.george.Hex;
import io.pijun.george.Prefs;
import io.pijun.george.api.OscarClient;
import io.pijun.george.api.UserComm;
import io.pijun.george.crypto.KeyPair;
import io.pijun.george.database.DB;
import io.pijun.george.database.UserRecord;

/** Tracks the result of an invite confirmation while Add Friend is open. */
public final class InviteRedemptions {
    private static final long REPLY_TIMEOUT_MS = 20_000;
    private static final class Attempt {
        final byte[] inviter;
        final String accessToken;
        final Listener listener;

        Attempt(byte[] inviter, String accessToken, Listener listener) {
            this.inviter = inviter;
            this.accessToken = accessToken;
            this.listener = listener;
        }
    }
    private static final ConcurrentHashMap<String, Attempt> pending = new ConcurrentHashMap<>();

    private InviteRedemptions() {}

    interface Listener {
        void finished(@Nullable Boolean accepted);
    }

    static boolean send(UserRecord user, byte[] token, byte[] avatar, String accessToken,
                        KeyPair keys, Listener listener) {
        String id = Hex.toHexString(token);
        Attempt attempt = new Attempt(user.userId, accessToken, listener);
        if (pending.putIfAbsent(id, attempt) != null) return false;
        UserComm request = UserComm.newInviteRedemption(token, null);
        request.avatar = avatar;
        String error = OscarClient.queueSendMessage(OscarClient.getQueue(App.getApp()), user, keys,
                accessToken, request.toJSON(), true, false);
        if (error != null) {
            pending.remove(id, attempt);
            return false;
        }
        App.runOnUiThread(() -> {
            if (pending.remove(id, attempt)) App.runInBackground(() -> listener.finished(null));
        }, REPLY_TIMEOUT_MS);
        return true;
    }

    public static void receive(UserRecord user, UserComm message, String accessToken, KeyPair keys)
            throws IOException, DB.DBException {
        if (!accessToken.equals(Prefs.get(App.getApp()).getAccessToken())) return;
        String id = Hex.toHexString(message.inviteToken);
        if (message.inviteAccepted != null) {
            Attempt attempt = pending.get(id);
            if (attempt != null && attempt.accessToken.equals(accessToken)
                    && Arrays.equals(attempt.inviter, user.userId) && pending.remove(id, attempt)) {
                attempt.listener.finished(message.inviteAccepted);
            }
            return;
        }

        boolean accepted;
        synchronized (FriendRequests.class) {
            if (FriendRequests.isBlocked(user.userId)) return;
            accepted = InviteTokens.wasUsedBy(message.inviteToken, user.userId)
                    ? DB.get().getFriendByUserId(user.id) != null
                    : FriendRequests.acceptInvite(user, message);
        }
        UserComm reply = UserComm.newInviteRedemption(message.inviteToken, accepted);
        if (OscarClient.queueSendMessage(OscarClient.getQueue(App.getApp()), user, keys,
                accessToken, reply.toJSON(), true, false) != null) {
            throw new IOException("Unable to queue invite confirmation");
        }
    }
}
