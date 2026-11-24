package xyz.zood.george;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.annotation.NonNull;

public final class BatterySettings {
    private BatterySettings() {}

    public static boolean needsUnrestrictedUsage(@NonNull Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        ActivityManager activity = context.getSystemService(ActivityManager.class);
        return power == null || !power.isIgnoringBatteryOptimizations(context.getPackageName())
                || (activity != null && activity.isBackgroundRestricted());
    }

    /** Android requires the owner to approve the exemption; fall back to settings on other devices. */
    @SuppressLint("BatteryLife")
    public static void requestUnrestrictedUsage(@NonNull Context context) {
        if (!needsUnrestrictedUsage(context)) {
            return;
        }
        Uri packageUri = Uri.fromParts("package", context.getPackageName(), null);
        PowerManager power = context.getSystemService(PowerManager.class);
        if ("github".equals(BuildConfig.FLAVOR) && power != null
                && !power.isIgnoringBatteryOptimizations(context.getPackageName())) {
            if (open(context, new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri))) {
                return;
            }
        }
        if (!open(context, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))) {
            open(context, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private static boolean open(Context context, Intent intent) {
        try {
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException ignored) {
            return false;
        }
    }
}
