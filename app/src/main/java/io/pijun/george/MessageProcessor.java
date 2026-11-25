package io.pijun.george;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Size;
import androidx.annotation.WorkerThread;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.pijun.george.api.DeviceInfo;
import io.pijun.george.api.LimitedUserInfoUnchecked;
import io.pijun.george.api.CommType;
import io.pijun.george.api.Message;
import io.pijun.george.api.OscarAPI;
import io.pijun.george.api.OscarClient;
import io.pijun.george.api.OscarError;
import io.pijun.george.api.OutboundMessage;
import io.pijun.george.api.PushNotification;
import io.pijun.george.api.UserComm;
import io.pijun.george.api.task.SendMessageTask;
import io.pijun.george.crypto.EncryptedData;
import io.pijun.george.crypto.KeyPair;
import io.pijun.george.database.DB;
import io.pijun.george.database.FriendRecord;
import io.pijun.george.database.UserRecord;
import retrofit2.Response;
import xyz.zood.george.AvatarManager;
import xyz.zood.george.Permissions;
import xyz.zood.george.DeviceStatusProbe;
import xyz.zood.george.FriendDevices;
import xyz.zood.george.FriendRequests;
import xyz.zood.george.service.LiveRequestService;
import xyz.zood.george.service.LocationService;
import xyz.zood.george.service.ScreamerService;
import xyz.zood.george.worker.BackupDatabaseWorker;

public class MessageProcessor {

    public enum Result {
        Success,
        ErrorDatabaseException,
        ErrorDatabaseInconsistency,
        ErrorDecryptionFailed,
        ErrorEncryptionFailed,
        ErrorInvalidCommunication,
        ErrorInvalidSenderId,
        ErrorMissingCipherText,
        ErrorMissingNonce,
        ErrorNoNetwork,
        ErrorNotAFriend,
        ErrorNotLoggedIn,
        ErrorRemoteInternal,
        ErrorUnknown,
        ErrorUnknownSender,
        RetryFriendRemoval
    }

    private static final ScheduledExecutorService inboxExecutor = Executors.newSingleThreadScheduledExecutor();
    private static String requestedInbox;
    private static boolean inboxScheduled;
    private static ScheduledFuture<?> inboxRetry;
    private static String acknowledgmentAccount;
    private static Message awaitingAcknowledgment;

    private MessageProcessor() {}

    @WorkerThread
    public static Result decryptAndProcess(@NonNull Context context, @NonNull byte[] senderId, @NonNull byte[] cipherText, @NonNull byte[] nonce) {
//        L.i("MessageUtils.decryptAndProcess");
        //noinspection ConstantConditions
        if (senderId == null || senderId.length != Constants.USER_ID_LENGTH) {
            L.i("senderId: " + Hex.toHexString(senderId));
            return Result.ErrorInvalidSenderId;
        }
        //noinspection ConstantConditions
        if (cipherText == null) {
            return Result.ErrorMissingCipherText;
        }
        //noinspection ConstantConditions
        if (nonce == null) {
            return Result.ErrorMissingNonce;
        }

        // check if this message is from ourself
        Prefs prefs = Prefs.get(context);
        byte[] ourId = prefs.getUserId();
        if (ourId == null) {
            return Result.ErrorNotLoggedIn;
        }
        if (Arrays.equals(senderId, ourId)) {
            return handleMessageFromOurself(context, cipherText, nonce, ourId);
        }


        DB db = DB.get();
        UserRecord user = db.getUser(senderId);
        if (user == null && FriendRequests.isBlocked(senderId)) return Result.Success;
        String token = prefs.getAccessToken();
        KeyPair keyPair = prefs.getKeyPair();
        if (!AuthenticationManager.isLoggedIn(context)) {
            return Result.ErrorNotLoggedIn;
        }
        if (token == null || keyPair == null) {
            return Result.ErrorNotLoggedIn;
        }

        if (user == null) {
            L.i("  need to download user");
            // we need to retrieve it from the server
            OscarAPI api = OscarClient.newInstance(token);
            try {
                Response<LimitedUserInfoUnchecked> response = api.getUser(Hex.toHexString(senderId)).execute();
                if (!response.isSuccessful()) {
                    OscarError apiErr = OscarError.fromResponse(response);
                    if (apiErr == null) {
                        return Result.ErrorUnknown;
                    }
                    switch (apiErr.code) {
                        case OscarError.ERROR_INVALID_ACCESS_TOKEN:
                            return Result.ErrorNotLoggedIn;
                        case OscarError.ERROR_USER_NOT_FOUND:
                            return Result.ErrorUnknownSender;
                        case OscarError.ERROR_INTERNAL:
                            return Result.ErrorRemoteInternal;
                        default:
                            return Result.ErrorUnknown;
                    }
                }
                LimitedUserInfoUnchecked luiu = response.body();
                if (luiu == null) {
                    L.w("Unable to decode user " + Hex.toHexString(senderId) + " from response");
                    return Result.ErrorUnknown;
                }
                var lui = luiu.toLimitedUserInfo();
                if (lui == null) {
                    L.w("Decoded user was missing a username or public key");
                    return Result.ErrorUnknown;
                }
                // now that we've encountered a new user, add them to the database (because of TOFU)
                user = db.addUser(senderId, lui.username, lui.publicKey);
                L.i("  added user: " + user);
                BackupDatabaseWorker.scheduleBackup(context);
            } catch (IOException ioe) {
                return Result.ErrorNoNetwork;
            } catch (DB.DBException dbe) {
                L.w("decryptAndProcess", dbe);
                return Result.ErrorDatabaseException;
            }
        }

        byte[] unwrappedBytes = Sodium.publicKeyDecrypt(cipherText, nonce, user.publicKey, keyPair.secretKey);
        if (unwrappedBytes == null) {
            return Result.ErrorDecryptionFailed;
        }
        UserComm comm = UserComm.fromJSON(unwrappedBytes);
        if (!comm.isValid()) {
            L.w("Rejected an invalid friend message");
            return Result.ErrorInvalidCommunication;
        }
//        L.i("  comm type: " + comm.type);
        if (FriendRequests.isBlocked(senderId)) {
            if (comm.type == CommType.LocationSharingRevocation) return handleLocationSharingRevocation(user, comm);
            return Result.Success;
        }
        xyz.zood.george.Nicknames.record(user, comm);
        switch (comm.type) {
            case NicknameUpdate:
                return Result.Success;
            case AvatarRequest:
                return handleAvatarRequest(context, user);
            case AvatarUpdate:
                return handleAvatarUpdate(context, user, comm);
            case BrowseDevices:
                L.i("We shouldn't be receiving browse_devices from other users");
                return Result.ErrorInvalidCommunication;
            case Debug:
                L.d("Received a friend debug message");
                return Result.Success;
            case DeviceInfo:
                L.i("We shouldn't be receiving device_info from other users");
                return Result.ErrorInvalidCommunication;
            case InviteRedemption:
                try {
                    xyz.zood.george.InviteRedemptions.receive(user, comm, token, keyPair);
                    return Result.Success;
                } catch (IOException | DB.DBException ex) {
                    L.w("Unable to process invite redemption", ex);
                    return Result.ErrorDatabaseException;
                }
            case FriendRequest:
            case LocationSharingGrant:
                Result result = handleLocationSharingGrant(user, comm);
                xyz.zood.george.Nicknames.record(user, comm);
                return result;
            case LocationSharingRevocation:
                return handleLocationSharingRevocation(user, comm);
            case LocationInfo:
                synchronized (FriendRequests.class) {
                    if (FriendRequests.isBlocked(user.userId)) return Result.Success;
                    return handleLocationInfo(user, comm);
                }
            case LocationUpdateRequest:
                return handleLocationUpdateRequest(context, user, token, keyPair);
            case LocationUpdateRequestReceived:
                return handleLocationUpdateRequestReceived(user, comm);
            case DeviceStatusRequest:
                return handleDeviceStatusRequest(user, token, keyPair);
            case DeviceStatus:
                FriendRecord reportingFriend = DB.get().getFriendByUserId(user.id);
                if (reportingFriend == null || reportingFriend.receivingBoxId == null) return Result.Success;
                FriendDevices.record(App.getApp(), user.userId, comm);
                DeviceStatusProbe.onStatusReceived(user.userId, comm.liveRequests);
                return Result.Success;
            case Scream:
            default:
                L.i("The invalid comm should have been caught during the isValid() check: " +
                        user.username + " - " + comm);
                return Result.ErrorInvalidCommunication;
        }
    }

    private static Result handleAvatarRequest(@NonNull Context ctx, @NonNull UserRecord user) {
        L.d("Handling an avatar request");
        try {
            // make sure this is somebody that we're sharing our location with
            FriendRecord friend = DB.get().getFriendByUserId(user.id);
            L.i("\tfriendrecord: " + friend);
            if (friend == null) {
                L.i("\tnot a friend");
                return Result.Success;
            }
            // A name is friendship metadata; photos still require outgoing location-sharing consent.
            xyz.zood.george.Nicknames.sendTo(user);
            if (friend.sendingBoxId == null) {
                L.i("\tno sending box id");
                return Result.Success;
            }
            AvatarManager.sendAvatarToUser(ctx, user);
        } catch (IOException ex) {
            L.w("handleAvatarRequest", ex);
        }

        return Result.Success;
    }

    private static Result handleAvatarUpdate(@NonNull Context context, @NonNull UserRecord user, @NonNull UserComm comm) {
        L.d("Handling an avatar update");
        try {
            // make sure this person is at least a friend
            if (DB.get().getFriendByUserId(user.id) == null) {
                FriendRequests.recordPendingAvatar(user, comm.avatar);
                return Result.Success;
            }

            boolean success = AvatarManager.saveAvatar(context, user.username, comm.avatar);
            if (success) {
                xyz.zood.george.worker.BackupDatabaseWorker.scheduleBackup(context);
            } else {
                L.w("Failed to save a friend avatar");
            }
        } catch (IOException ex) {
            L.w("handleAvatarUpdate", ex);
        }
        return Result.Success;
    }

    private static Result handleBrowseDevices(@NonNull Context ctx, @NonNull String accessToken, @NonNull KeyPair keyPair, @NonNull @Size(Constants.USER_ID_LENGTH) byte[] ourId) {
        DeviceInfo di = new DeviceInfo(Prefs.get(ctx).getDeviceId(),
                Build.MANUFACTURER,
                Build.MODEL,
                "Android",
                Build.VERSION.RELEASE);
        UserComm c = UserComm.newDeviceInfo(di);
        // make sure we have everything we need
        EncryptedData encrypted = Sodium.publicKeyEncrypt(c.toJSON(), keyPair.publicKey, keyPair.secretKey);
        if (encrypted == null) {
            L.w("Failed to encrypt device info message to myself");
            return Result.ErrorEncryptionFailed;
        }
        OutboundMessage om = new OutboundMessage();
        om.cipherText = encrypted.cipherText;
        om.nonce = encrypted.nonce;
        om.urgent = true;
        om.isTransient = true;

        OscarAPI api = OscarClient.newInstance(accessToken);
        try {
            Response<Void> response = api.sendMessage(Hex.toHexString(ourId), om).execute();
            if (response.isSuccessful()) {
                return Result.Success;
            }

            OscarError err = OscarError.fromResponse(response);
            if (err != null) {
                L.w(err.toString());
                if (err.code == OscarError.ERROR_INVALID_ACCESS_TOKEN) {
                    return Result.ErrorNotLoggedIn;
                }
            } else {
                L.w("Unknown server error received while sending device_info");
            }
        } catch (IOException ex) {
            L.i("Network error while trying to send device_info");
        }

        // something happened, so we'll queue it to try again later.
        SendMessageTask smt = new SendMessageTask(accessToken);
        smt.hexUserId = Hex.toHexString(ourId);
        smt.message = encrypted;
        smt.urgent = true;
        smt.isTransient = true;
        OscarClient.getQueue(ctx).offer(smt);

        return Result.Success;
    }

    private static Result handleLocationInfo(@NonNull UserRecord user, @NonNull UserComm comm) {
        DB db = DB.get();
        FriendRecord fr = db.getFriendByUserId(user.id);
        if (fr == null || fr.receivingBoxId == null) {
            // there should be an approved friend record for any locations that we receive
            return Result.ErrorNotAFriend;
        }
        FriendDevices.record(App.getApp(), user.userId, comm);
        try {
            io.pijun.george.database.FriendLocation previous = db.getFriendLocation(fr.id);
            if (previous != null && previous.time > comm.time) return Result.Success;
            db.setFriendLocation(fr.id, comm.latitude, comm.longitude, comm.time, comm.accuracy, comm.speed, comm.bearing, comm.movement, comm.batteryLevel, comm.batteryCharging);
            UpdateStatusTracker.onLocationReceived(fr.id, comm.time, previous == null ? null : previous.time);
        } catch (DB.DBException ex) {
            L.w("handleLocationInfo", ex);
            return Result.ErrorDatabaseException;
        }

        return Result.Success;
    }

    private static Result handleLocationSharingGrant(@NonNull UserRecord user, @NonNull UserComm comm) {
        L.i("LocationSharingGrant");
        try {
            FriendRequests.receive(user, comm);
        } catch (IOException | DB.DBException ex) {
            L.w("handleLocationSharingGrant", ex);
            return Result.ErrorDatabaseException;
        }

        return Result.Success;
    }

    private static Result handleLocationSharingRevocation(@NonNull UserRecord user, @NonNull UserComm comm) {
        L.i("LocationSharingRevocation");
        boolean removingFriend = Boolean.TRUE.equals(comm.friendRemoved);
        try {
            if (removingFriend) FriendRequests.forget(user);
            else FriendRequests.revoke(user);
            return Result.Success;
        } catch (IOException | DB.DBException ex) {
            L.w("handleLocationSharingRevocation", ex);
            return removingFriend ? Result.RetryFriendRemoval : Result.ErrorDatabaseException;
        }
    }

    private static Result handleLocationUpdateRequest(@NonNull Context context, @NonNull UserRecord userRecord, @NonNull String accessToken, @NonNull KeyPair keyPair) {
        L.d("Handling a location request");
        // make sure this is actually a friend
        FriendRecord f = DB.get().getFriendByUserId(userRecord.id);
        if (f == null || f.sendingBoxId == null) {
            return Result.Success;
        }

        boolean canUseForegroundLocation = App.isInForeground || LiveRequestService.isRunning();
        if (!(canUseForegroundLocation ? Permissions.checkForegroundLocationPermission(context)
                : Permissions.checkBackgroundLocationPermission(context))) {
            // we don't, so just inform the requester that we're done providing updates
            UserComm done = UserComm.newLocationUpdateRequestReceived(UserComm.LOCATION_UPDATE_REQUEST_ACTION_FINISHED);
            String errMsg = OscarClient.immediatelySendMessage(userRecord, accessToken, keyPair, done, true, true);
            if (errMsg != null) {
                L.w(errMsg);
            }
            return Result.Success;
        }

        L.i("MP attempting to start LocationService");
        if (!LiveRequestService.fulfillRequest(userRecord.id)) {
            Intent serviceIntent = LocationService.newIntent(context, userRecord.id);
            try {
                context.startForegroundService(serviceIntent);
            } catch (IllegalStateException ex) {
                // ForegroundServiceStartNotAllowedException: we're in the background with no exemption.
                L.w("MessageProcessor unable to start LocationService from background", ex);
                UserComm done = UserComm.newLocationUpdateRequestReceived(UserComm.LOCATION_UPDATE_REQUEST_ACTION_FINISHED);
                OscarClient.immediatelySendMessage(userRecord, accessToken, keyPair, done, true, true);
                return Result.Success;
            }
        }

        // let them know we've started
        L.i("queueing update starting");
        UserComm started = UserComm.newLocationUpdateRequestReceived(UserComm.LOCATION_UPDATE_REQUEST_ACTION_STARTING);
        String errMsg = OscarClient.immediatelySendMessage(userRecord, accessToken, keyPair, started, true, true);
        if (errMsg != null) {
            L.w(errMsg);
        }

        return Result.Success;
    }

    /** A friend is checking whether we can answer location requests. Only answer people we share with. */
    private static Result handleDeviceStatusRequest(@NonNull UserRecord user, @NonNull String accessToken, @NonNull KeyPair keyPair) {
        FriendRecord f = DB.get().getFriendByUserId(user.id);
        if (f == null || f.sendingBoxId == null) {
            return Result.Success;
        }
        OscarClient.immediatelySendMessage(user, accessToken, keyPair, UserComm.newDeviceStatus(), true, true);
        return Result.Success;
    }

    private static Result handleLocationUpdateRequestReceived(@NonNull UserRecord user, @NonNull UserComm comm) {
        String logMsg = String.format("handleLocationUpdateRequestReceived - %s - %s", user.username, comm.locationUpdateRequestAction);
        L.i(logMsg);
        FriendRecord friend = DB.get().getFriendByUserId(user.id);
        if (friend == null || friend.receivingBoxId == null) {
            return Result.Success;
        }
        FriendDevices.record(App.getApp(), user.userId, comm);
        if (UserComm.LOCATION_UPDATE_REQUEST_ACTION_FINISHED.equals(comm.locationUpdateRequestAction)) {
            // Stock Zood can report completion without a fresh fix. Verify against the receiving box.
            if (UpdateStatusTracker.isPending(friend.id)) {
                FriendRequests.fetchLatestIfSignedIn(user, friend.receivingBoxId);
            }
        } else {
            UpdateStatusTracker.setUpdateRequestResponse(friend.id, comm.locationUpdateRequestAction);
        }

        return Result.Success;
    }

    private static Result handleMessageFromOurself(@NonNull Context context, @NonNull byte[] cipherText, @NonNull byte[] nonce, @NonNull @Size(Constants.USER_ID_LENGTH) byte[] ourId) {
        if (!AuthenticationManager.isLoggedIn(context)) {
            return Result.ErrorNotLoggedIn;
        }

        Prefs prefs = Prefs.get(context);
        String token = prefs.getAccessToken();
        KeyPair keyPair = prefs.getKeyPair();
        if (token == null || keyPair == null) {
            return Result.ErrorNotLoggedIn;
        }

        byte[] userCommBytes = Sodium.publicKeyDecrypt(cipherText, nonce, keyPair.publicKey, keyPair.secretKey);
        if (userCommBytes == null) {
            return Result.ErrorDecryptionFailed;
        }
        UserComm comm = UserComm.fromJSON(userCommBytes);
        if (!comm.isValid()) {
            L.w("Rejected an invalid account message");
            return Result.ErrorInvalidCommunication;
        }

        // we only support a few messages from ourself
        switch (comm.type) {
            case BrowseDevices:
                return handleBrowseDevices(context, token, keyPair, ourId);
            case DeviceInfo:
                // We don't care. Only useful for the web client
                return Result.Success;
            case Scream:
                return handleScreamRequest(context, token, keyPair, ourId);
            default:
                return Result.ErrorInvalidCommunication;
        }
    }

    private static Result handleScreamRequest(@NonNull Context context, @NonNull String accessToken, @NonNull KeyPair keyPair, @NonNull @Size(Constants.USER_ID_LENGTH) byte[] ourId) {
        Intent i = ScreamerService.newStartIntent(context);
        ContextCompat.startForegroundService(context, i);

        UserComm c = UserComm.newScreamBegan();
        // make sure we have everything we need
        EncryptedData encrypted = Sodium.publicKeyEncrypt(c.toJSON(), keyPair.publicKey, keyPair.secretKey);
        if (encrypted == null) {
            L.w("Failed to encrypt device info message to myself");
            return Result.ErrorEncryptionFailed;
        }
        OutboundMessage om = new OutboundMessage();
        om.cipherText = encrypted.cipherText;
        om.nonce = encrypted.nonce;
        om.urgent = true;
        om.isTransient = true;

        OscarAPI api = OscarClient.newInstance(accessToken);
        try {
            Response<Void> response = api.sendMessage(Hex.toHexString(ourId), om).execute();
            if (response.isSuccessful()) {
                return Result.Success;
            }

            OscarError err = OscarError.fromResponse(response);
            if (err != null) {
                L.w(err.toString());
                if (err.code == OscarError.ERROR_INVALID_ACCESS_TOKEN) {
                    return Result.ErrorNotLoggedIn;
                }
            } else {
                L.w("Unknown server error received while sending scream_began");
            }
        } catch (IOException ex) {
            L.i("Network error while trying to send scream_began");
        }

        // something happened, so we'll queue it to try again later.
        SendMessageTask smt = new SendMessageTask(accessToken);
        smt.hexUserId = Hex.toHexString(ourId);
        smt.message = encrypted;
        smt.urgent = true;
        smt.isTransient = true;
        OscarClient.getQueue(context).offer(smt);

        return Result.Success;
    }

    /** Durable socket messages signal the inbox. Only transient messages are handled inline. */
    public static void receiveSocketNotification(@NonNull PushNotification notification) {
        if (notification.id != null && !notification.id.equals("0")) {
            String token = Prefs.get(App.getApp()).getAccessToken();
            if (token != null) retrieveAndProcessNewMessages(token);
            return;
        }
        Result result = decryptAndProcess(App.getApp(), notification.senderId, notification.cipherText, notification.nonce);
        if (result != Result.Success) L.w("Unable to process transient message: " + result);
    }

    public static synchronized void retrieveAndProcessNewMessages(@NonNull String accessToken) {
        if (!accessToken.equals(Prefs.get(App.getApp()).getAccessToken())) return;
        requestedInbox = accessToken;
        if (!inboxScheduled) {
            inboxScheduled = true;
            inboxExecutor.execute(MessageProcessor::syncInbox);
        }
    }

    private static void syncInbox() {
        while (true) {
            String token;
            synchronized (MessageProcessor.class) {
                token = requestedInbox;
                requestedInbox = null;
                if (token == null) {
                    inboxScheduled = false;
                    return;
                }
            }
            if (!token.equals(Prefs.get(App.getApp()).getAccessToken())) continue;
            if (inboxRetry != null) inboxRetry.cancel(false);
            try {
                if (drainInbox(token)) continue;
            } catch (IOException | RuntimeException error) {
                L.w("Unable to sync the message inbox", error);
            }
            inboxRetry = inboxExecutor.schedule(() -> retrieveAndProcessNewMessages(token), 30, TimeUnit.SECONDS);
        }
    }

    private static boolean drainInbox(String token) throws IOException {
        if (!token.equals(acknowledgmentAccount)) {
            awaitingAcknowledgment = null;
            acknowledgmentAccount = token;
        }
        OscarAPI api = OscarClient.newInstance(token);
        Response<Message[]> response = api.getMessages().execute();
        if (!response.isSuccessful()) {
            L.w("Unable to read the message inbox: " + OscarError.fromResponse(response));
            return response.code() < 500 && response.code() != 429;
        }
        Message[] messages = response.body();
        if (messages == null) return true;

        // The server's socket callbacks and inbox response can both arrive out of order.
        Arrays.sort(messages, Comparator.comparingLong(message -> message.id));
        for (Message message : messages) {
            if (!token.equals(Prefs.get(App.getApp()).getAccessToken())) return true;

            boolean alreadyApplied = awaitingAcknowledgment != null && awaitingAcknowledgment.id == message.id
                    && Arrays.equals(awaitingAcknowledgment.nonce, message.nonce);
            if (!alreadyApplied) {
                Result result = decryptAndProcess(App.getApp(), message.senderId, message.cipherText, message.nonce);
                if (result == Result.ErrorNoNetwork || result == Result.ErrorRemoteInternal
                        || result == Result.RetryFriendRemoval) return false;
                if (result != Result.Success) L.w("Discarding invalid inbox message: " + result);
            }
            awaitingAcknowledgment = message;
            Response<Void> deleted = api.deleteMessage(message.id).execute();
            if (!deleted.isSuccessful() && deleted.code() != 404) return false;
            awaitingAcknowledgment = null;
        }

        if (token.equals(Prefs.get(App.getApp()).getAccessToken())) {
            try {
                FriendRequests.repairLocationGrants();
            } catch (IOException | DB.DBException error) {
                L.w("Could not repair a previously queued location grant", error);
            }
        }
        return true;
    }
}
