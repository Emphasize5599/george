package io.pijun.george;

import android.content.Context;
import android.util.Log;

import java.io.IOException;

import androidx.annotation.NonNull;

public final class L {
    private static final String TAG = "Amidst";
    static final String LOG_FILENAME = "logs.txt";

    private L() {}

    public static void i(@NonNull String msg) {
        Log.i(TAG, msg);
    }

    public static void d(@NonNull String msg) {
        Log.d(TAG, msg);
    }

    public static void w(@NonNull String msg) {
        Log.w(TAG, msg);
    }

    public static void w(@NonNull String msg, @NonNull Throwable error) {
        Log.w(TAG, msg, error);
    }

    public static void e(@NonNull String msg, @NonNull Throwable error) {
        Log.e(TAG, msg, error);
    }

    /** Clear any file left by older versions that wrote diagnostic logs. */
    static void resetLog(@NonNull Context context) {
        try (var ignored = context.openFileOutput(LOG_FILENAME, Context.MODE_PRIVATE)) {
        } catch (IOException error) {
            Log.e(TAG, "Unable to reset the diagnostic log", error);
        }
    }
}
