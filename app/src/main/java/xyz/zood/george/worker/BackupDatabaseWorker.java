package xyz.zood.george.worker;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import io.pijun.george.AuthenticationManager;
import io.pijun.george.Hex;
import io.pijun.george.L;
import io.pijun.george.Prefs;
import io.pijun.george.Sodium;
import io.pijun.george.api.OscarClient;
import io.pijun.george.api.OscarError;
import io.pijun.george.crypto.EncryptedData;
import io.pijun.george.database.DB;
import io.pijun.george.database.Snapshot;
import retrofit2.Response;

public class BackupDatabaseWorker extends Worker {
    private static final String THUMBNAIL_BACKUPS = "avatar_thumbnail_backups";

    public BackupDatabaseWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        L.i("BackupDB.doWork");
        Context ctx = getApplicationContext();
        if (!AuthenticationManager.isLoggedIn(ctx)) {
            return Result.success();
        }

        synchronized (BackupDatabaseWorker.class) {
            return uploadSnapshot();
        }
    }

    @AnyThread
    public static void scheduleBackup(@NonNull Context ctx) {
        Constraints constraints = new Constraints.Builder().
                setRequiredNetworkType(NetworkType.CONNECTED).
                setRequiresCharging(false).
                setRequiresDeviceIdle(false).
                build();
        WorkRequest req = new OneTimeWorkRequest.Builder(BackupDatabaseWorker.class).
                setId(UUID.randomUUID()).
                setConstraints(constraints).
                setInitialDelay(Duration.ofSeconds(10)). // in case additional db operations will be performed after this request
                build();
        WorkManager.getInstance(ctx).enqueue(req);
    }

    /** Backfill previously cached avatars once, with a retry at the next launch if upload fails. */
    public static void scheduleThumbnailBackfill(Context context) {
        byte[] account = Prefs.get(context).getUserId();
        if (account != null && !context.getSharedPreferences(THUMBNAIL_BACKUPS, Context.MODE_PRIVATE)
                .getBoolean(Hex.toHexString(account), false)) {
            scheduleBackup(context);
        }
    }

    @NonNull
    @WorkerThread
    private Result uploadSnapshot() {
        Context ctx = getApplicationContext();
        Prefs prefs = Prefs.get(ctx);
        String token = prefs.getAccessToken();
        byte[] symmetricKey = prefs.getSymmetricKey();
        byte[] account = prefs.getUserId();
        if (symmetricKey == null || TextUtils.isEmpty(token) || account == null) return Result.success();

        // Never overwrite the backup of an account that's now active on another device; sign out instead.
        Snapshot remote = xyz.zood.george.DeviceSession.fetchRemote(ctx);
        if (!token.equals(prefs.getAccessToken())) return Result.success();
        if (xyz.zood.george.DeviceSession.isSupersededBy(remote)) {
            xyz.zood.george.DeviceSession.signOutBecauseSupersededElsewhere(ctx);
            return Result.success();
        }
        Snapshot snapshot = DB.get().getSnapshot();
        snapshot.activeDevice = xyz.zood.george.DeviceSession.recordFor(remote);

        if (!token.equals(prefs.getAccessToken())) return Result.success();

        byte[] snapshotBytes = snapshot.toJson();
        if (snapshotBytes == null) {
            throw new RuntimeException("Snapshot data is null");
        }
        EncryptedData encData = Sodium.symmetricKeyEncrypt(snapshotBytes, symmetricKey);
        try {
            Response<Void> response = OscarClient.newInstance(token).saveDatabaseBackup(encData).execute();
            if (!response.isSuccessful()) {
                OscarError err = OscarError.fromResponse(response);
                L.w("Encrypted db backup failed: " + err);
                L.w("\terror from server: " + err);
                return Result.failure();
            } else {
                ctx.getSharedPreferences(THUMBNAIL_BACKUPS, Context.MODE_PRIVATE)
                        .edit().putBoolean(Hex.toHexString(account), true).apply();
                L.i("BackupDB completed");
                return Result.success();
            }
        } catch (IOException ex) {
            // network error
            L.w("\tsnapshot upload failed");
            return Result.failure();
        }
    }
}
