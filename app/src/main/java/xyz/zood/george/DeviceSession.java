package xyz.zood.george;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.IOException;
import java.util.UUID;

import io.pijun.george.App;
import io.pijun.george.AuthenticationManager;
import io.pijun.george.Hex;
import io.pijun.george.L;
import io.pijun.george.Prefs;
import io.pijun.george.Sodium;
import io.pijun.george.api.OscarClient;
import io.pijun.george.crypto.EncryptedData;
import io.pijun.george.database.Snapshot;
import retrofit2.Response;

/**
 * One device at a time per account, so two phones don't fight over the same backup.
 *
 * Zood's servers can't sign other devices out, so this uses the end-to-end encrypted backup both apps
 * already share: a fresh sign-in records itself as the active device there, and every device checks that
 * record when it opens and before it writes a backup. If a newer sign-in happened elsewhere, this device
 * signs itself out locally (nothing on the account is deleted).
 *
 * Only fresh sign-ins claim the account, so updating the app never signs anyone out by itself. Stock Zood
 * clients ignore the record. The iPhone app does the same with the same backup field.
 */
public final class DeviceSession {

    private DeviceSession() {}

    private static SharedPreferences prefs() {
        return App.getApp().getSharedPreferences("device_session", Context.MODE_PRIVATE);
    }

    /** A random ID for this install, kept across sign-ins. */
    @NonNull
    public static synchronized String deviceId() {
        String id = prefs().getString("device_id", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs().edit().putString("device_id", id).apply();
        }
        return id;
    }

    private static String claimKey() {
        return Hex.toHexString(Prefs.get(App.getApp()).getUserId()) + ":claimed_at";
    }

    /** When this device signed in (ms), or 0 if it was signed in before this feature existed. */
    private static long claimedAt() {
        return prefs().getLong(claimKey(), 0);
    }

    /** Called right after a fresh sign-in: this device becomes the active one. */
    public static void claim(@NonNull Context ctx) {
        prefs().edit().putLong(claimKey(), System.currentTimeMillis()).apply();
        xyz.zood.george.worker.BackupDatabaseWorker.scheduleBackup(ctx);
    }

    /** True if `remote` says a different device signed in after this one. */
    public static boolean isSupersededBy(@Nullable Snapshot remote) {
        if (remote == null || remote.activeDevice == null || remote.activeDevice.id == null) {
            return false;
        }
        return !remote.activeDevice.id.equals(deviceId()) && remote.activeDevice.since > claimedAt();
    }

    /** The active-device record to write into an outgoing backup: ours if we claimed, otherwise the one already there. */
    @Nullable
    public static Snapshot.ActiveDevice recordFor(@Nullable Snapshot remote) {
        long since = claimedAt();
        if (since > 0) {
            Snapshot.ActiveDevice mine = new Snapshot.ActiveDevice();
            mine.id = deviceId();
            mine.platform = "android";
            mine.since = since;
            return mine;
        }
        return remote == null ? null : remote.activeDevice;
    }

    /** Downloads and decrypts the account's backup. Null if there isn't one or it can't be read. */
    @Nullable
    @WorkerThread
    public static Snapshot fetchRemote(@NonNull Context ctx) {
        Prefs prefs = Prefs.get(ctx);
        String token = prefs.getAccessToken();
        byte[] key = prefs.getSymmetricKey();
        if (TextUtils.isEmpty(token) || key == null) {
            return null;
        }
        try {
            Response<EncryptedData> response = OscarClient.newInstance(token).getDatabaseBackup().execute();
            EncryptedData data = response.body();
            if (!response.isSuccessful() || data == null) {
                return null;
            }
            byte[] json = Sodium.symmetricKeyDecrypt(data.cipherText, data.nonce, key);
            return json == null ? null : Snapshot.fromJson(json);
        } catch (IOException e) {
            return null;
        }
    }

    /** Signs this device out locally because the account is now active on another device. */
    public static void signOutBecauseSupersededElsewhere(@NonNull Context ctx) {
        L.i("Signed in on another device; signing out here");
        App.runOnUiThread(() -> Toast.makeText(ctx, R.string.signed_in_elsewhere, Toast.LENGTH_LONG).show());
        AuthenticationManager.get().logOut(ctx, null);
    }

    /** Check when the app comes to the foreground. */
    public static void checkInBackground(@NonNull Context ctx) {
        if (!AuthenticationManager.isLoggedIn(ctx)) {
            return;
        }
        App.runInBackground(() -> {
            if (isSupersededBy(fetchRemote(ctx))) {
                signOutBecauseSupersededElsewhere(ctx);
            }
        });
    }
}
