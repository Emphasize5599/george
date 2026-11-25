package xyz.zood.george;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.squareup.picasso.Picasso;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import androidx.annotation.AnyThread;
import androidx.annotation.CheckResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;

import io.pijun.george.App;
import io.pijun.george.L;
import io.pijun.george.Prefs;
import io.pijun.george.UiRunnable;
import io.pijun.george.WorkerRunnable;
import io.pijun.george.api.OscarClient;
import io.pijun.george.api.UserComm;
import io.pijun.george.crypto.KeyPair;
import io.pijun.george.database.DB;
import io.pijun.george.database.FriendRecord;
import io.pijun.george.database.UserRecord;
import xyz.zood.george.worker.BackupDatabaseWorker;

public class AvatarManager {

    private static final String AVATAR_DIR = "avatars";
    public static final String MY_AVATAR = "me";
    private static final CopyOnWriteArrayList<WeakReference<Listener>> listeners = new CopyOnWriteArrayList<>();

    public static final int BACKUP_THUMBNAIL_TOTAL_BYTES = 128 * 1024;
    private static final int BACKUP_THUMBNAIL_MAX_BYTES = 12 * 1024;
    private static final int BACKUP_THUMBNAIL_SIZE = 128;

    /** Reads the shared profile photo cache. Linked contact photos are stored separately. */
    @Nullable @WorkerThread
    public static byte[] backupThumbnail(Context context, String username) {
        if (!canCacheFriendThumbnail(username)) return null;
        File photo = getAvatar(context, username);
        long length = photo.length();
        if (length <= 0 || length > 1024 * 1024) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(photo.getAbsolutePath(), options);
        int longest = Math.max(options.outWidth, options.outHeight);
        if (options.outWidth <= 0 || options.outHeight <= 0 || longest > 4096) return null;
        if (longest <= BACKUP_THUMBNAIL_SIZE && length <= BACKUP_THUMBNAIL_MAX_BYTES) {
            try {
                byte[] cached = Files.readAllBytes(photo.toPath());
                if (isBackupThumbnail(cached)) return cached;
            } catch (IOException ignored) {
                return null;
            }
        }

        options.inJustDecodeBounds = false;
        options.inSampleSize = Math.max(1, Integer.highestOneBit(longest / BACKUP_THUMBNAIL_SIZE));
        Bitmap bitmap = BitmapFactory.decodeFile(photo.getAbsolutePath(), options);
        if (bitmap == null) return null;
        float ratio = Math.min(1f, (float) BACKUP_THUMBNAIL_SIZE / Math.max(bitmap.getWidth(), bitmap.getHeight()));
        Bitmap thumbnail = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * ratio)),
                Math.max(1, Math.round(bitmap.getHeight() * ratio)), true);
        try {
            var bytes = new ByteArrayOutputStream();
            for (int quality : new int[]{72, 50}) {
                if (thumbnail.compress(Bitmap.CompressFormat.JPEG, quality, bytes)
                        && bytes.size() <= BACKUP_THUMBNAIL_MAX_BYTES) return bytes.toByteArray();
                bytes.reset();
            }
            return null;
        } finally {
            if (thumbnail != bitmap) thumbnail.recycle();
            bitmap.recycle();
        }
    }

    @WorkerThread
    public static boolean restoreBackupThumbnail(Context context, String username, byte[] data) {
        if (!canCacheFriendThumbnail(username) || !isBackupThumbnail(data)) return false;
        try {
            return saveAvatar(context, username, data);
        } catch (IOException error) {
            L.w("Unable to restore a profile thumbnail", error);
            return false;
        }
    }

    private static boolean canCacheFriendThumbnail(String username) {
        // The reserved filename for our own avatar predates the per-friend backup field.
        return !MY_AVATAR.equalsIgnoreCase(username) && !username.isEmpty()
                && username.indexOf('/') < 0 && username.indexOf((char) 92) < 0;
    }

    private static boolean isBackupThumbnail(byte[] data) {
        if (data == null || data.length == 0 || data.length > BACKUP_THUMBNAIL_MAX_BYTES) return false;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
        return options.outWidth > 0 && options.outWidth <= BACKUP_THUMBNAIL_SIZE
                && options.outHeight > 0 && options.outHeight <= BACKUP_THUMBNAIL_SIZE;
    }

    //region Listener management

    @AnyThread
    public static void addListener(@NonNull Listener listener) {
        WeakReference<Listener> ref = new WeakReference<>(listener);
        listeners.add(ref);
    }

    @AnyThread
    private static void notifyListeners(@Nullable String username) {
        // Copy the items into another list, so we don't block any add/remove operations while
        // notifying listeners.
        LinkedList<WeakReference<Listener>> copy = new LinkedList<>(listeners);
        App.runOnUiThread(new UiRunnable() {
            @Override
            public void run() {
                for (WeakReference<Listener> ref : copy) {
                    Listener l = ref.get();
                    if (l == null) {
                        continue;
                    }
                    l.onAvatarUpdated(username);
                }
            }
        });
    }

    @AnyThread
    public static void removeListener(@NonNull Listener listener) {
        int i=0;
        while (i < listeners.size()) {
            WeakReference<Listener> ref = listeners.get(i);
            Listener l = ref.get();
            if (l == null || l == listener) {
                listeners.remove(i);
                continue;
            }
            i++;
        }
    }

    //endregion

    @WorkerThread
    public static void deleteAll(@NonNull Context ctx) {
        File filesDir = ctx.getFilesDir();
        File avatarsDir = new File(filesDir, AVATAR_DIR);
        File[] avatars = avatarsDir.listFiles();
        if (avatars == null) {
            return;
        }

        for (File a : avatars) {
            //noinspection ResultOfMethodCallIgnored
            a.delete();
        }
    }

    @CheckResult
    @NonNull @AnyThread
    public static File getAvatar(@NonNull Context ctx, @NonNull String username) {
        File filesDir = ctx.getFilesDir();
        File avatarsDir = new File(filesDir, AVATAR_DIR);
        return new File(avatarsDir, username.toLowerCase(Locale.US) + ".jpg");
    }

    @CheckResult @NonNull @AnyThread
    public static File getMyAvatar(@NonNull Context ctx) {
        return getAvatar(ctx, MY_AVATAR);
    }

    @WorkerThread @CheckResult
    public static boolean saveAvatar(@NonNull Context ctx, @NonNull String username, @NonNull byte[] imgData) throws IOException {
        L.d("Saving a friend avatar");
        username = username.toLowerCase(Locale.US);
        File filesDir = ctx.getFilesDir();
        File avatarsDir = new File(filesDir, AVATAR_DIR);
        if (!avatarsDir.exists()) {
            boolean success = avatarsDir.mkdir();
            if (!success) {
                L.w("Unable to create the avatars directory");
                return false;
            }
        }
        // make sure this avatar can be decoded
        if (BitmapFactory.decodeByteArray(imgData, 0, imgData.length) == null) {
            L.w("Unable to decode a friend avatar");
            return false;
        }

        File imgFile = new File(avatarsDir, username + ".jpg");
        FileOutputStream fos;
        try {
            fos = new FileOutputStream(imgFile);
        } catch (FileNotFoundException ex) {
            L.w("opening user avatar file", ex);
            return false;
        }

        fos.write(imgData);
        fos.close();

        Picasso.with(ctx).invalidate(getAvatar(ctx, username));
        notifyListeners(username);
        return true;
    }

    @WorkerThread
    @CheckResult
    static boolean setMyAvatar(@NonNull Context ctx, @NonNull Bitmap img) throws IOException {
        File filesDir = ctx.getFilesDir();
        File avatarsDir = new File(filesDir, AVATAR_DIR);
        if (!avatarsDir.exists()) {
            boolean success = avatarsDir.mkdir();
            if (!success) {
                L.w("Unable to create the avatars directory to save personal avatar");
                return false;
            }
        }
        File imgFile = new File(avatarsDir, MY_AVATAR + ".jpg");
        FileOutputStream fos;
        try {
            fos = new FileOutputStream(imgFile);
        } catch (FileNotFoundException ex) {
            L.w("opening my avatar file", ex);
            return false;
        }
        boolean success = img.compress(Bitmap.CompressFormat.JPEG, 80, fos);
        if (!success) {
            L.w("Failed to compress avatar avatar to file");
            return false;
        }
        fos.flush();
        fos.close();
        Picasso.with(ctx).invalidate(getMyAvatar(ctx));

        notifyListeners(null);

        App.runInBackground(new WorkerRunnable() {
            @Override
            public void run() {
                try {
                    ArrayList<FriendRecord> friends = DB.get().getFriendsToShareWith();
                    LinkedList<UserRecord> users = new LinkedList<>();
                    for (FriendRecord f : friends) {
                        users.add(f.user);
                    }
                    sendAvatarToUsers(ctx, users);
                    BackupDatabaseWorker.scheduleBackup(ctx);
                } catch (IOException ex) {
                    L.w("sendAvatarToUsers",ex);
                }
            }
        });

        return true;
    }

    @Nullable @WorkerThread
    static byte[] requestAvatar(Context context) {
        File file = getMyAvatar(context);
        if (file.length() == 0 || file.length() > 128 * 1024) return null;
        try {
            return java.nio.file.Files.readAllBytes(file.toPath());
        } catch (IOException error) {
            L.w("Could not attach profile photo", error);
            return null;
        }
    }

    @WorkerThread
    public static void sendAvatarToUser(@NonNull Context ctx, @NonNull UserRecord user) throws IOException {
        sendAvatarToUsers(ctx, Collections.singletonList(user));
    }

    @WorkerThread
    private static void sendAvatarToUsers(@NonNull Context ctx, @NonNull List<UserRecord> users) throws IOException {
        Prefs prefs = Prefs.get(ctx);
        String token = prefs.getAccessToken();
        KeyPair keyPair = prefs.getKeyPair();
        if (token == null || keyPair == null) {
            L.i("AvatarManager.sendAvatarToUsers missing token or keypair");
            return;
        }

        sendAvatarToUsers(ctx, users, keyPair, token);
    }

    static void sendAvatarToUsers(@NonNull Context ctx, @NonNull List<UserRecord> users, @NonNull KeyPair keyPair, @NonNull String accessToken) throws IOException {
        StringBuilder sb = new StringBuilder("sendAvatarToUsers: ");
        for (UserRecord u : users) {
            sb.append(u.username);
            sb.append(",");
        }
        L.i(sb.toString());

        File avatarFile = getMyAvatar(ctx);
        if (!avatarFile.exists()) {
            return;
        }

        byte[] buffer;
        int read;
        try (FileInputStream fis = new FileInputStream(avatarFile)) {
            buffer = new byte[(int) avatarFile.length()];
            read = fis.read(buffer);
        }
        if (read < avatarFile.length()) {
            L.w("Did not read entire avatar");
            return;
        }
        UserComm comm = UserComm.newAvatarUpdate(buffer);
        for (UserRecord u : users) {
            String errMsg = OscarClient.queueSendMessage(OscarClient.getQueue(ctx), u, keyPair, accessToken, comm.toJSON(), false, false);
            if (errMsg != null) {
                L.w(errMsg);
            }
        }
    }

    public interface Listener {
        @UiThread
        void onAvatarUpdated(@Nullable String username);
    }

}
