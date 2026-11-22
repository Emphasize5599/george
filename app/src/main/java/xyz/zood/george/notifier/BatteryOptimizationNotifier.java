package xyz.zood.george.notifier;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import xyz.zood.george.BatterySettings;
import xyz.zood.george.Permissions;
import xyz.zood.george.R;
import xyz.zood.george.widget.BannerView;

/** Keeps battery setup visible and rechecks it when the owner returns from Android settings. */
public final class BatteryOptimizationNotifier implements DefaultLifecycleObserver {
    private static final int BANNER_ID = 2135;
    private static final String PREFS_NAME = "family_locator";
    private static final String KEY_EXPLAINED = "unrestricted_battery_explained";
    private final FragmentActivity activity;
    private final BannerView banner;

    public BatteryOptimizationNotifier(@NonNull FragmentActivity activity, @NonNull BannerView banner) {
        this.activity = activity;
        this.banner = banner;
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        if (!BatterySettings.needsUnrestrictedUsage(activity)) {
            banner.removeItem(BANNER_ID);
            return;
        }
        banner.addItem(activity.getString(R.string.unrestricted_battery_needed),
                activity.getString(R.string.fix), BANNER_ID, id -> explain());
        // Wait until location setup is complete so permission prompts do not overlap.
        if (Permissions.checkForegroundLocationPermission(activity)
                && Permissions.checkBackgroundLocationPermission(activity)
                && !activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_EXPLAINED, false)
                && !activity.getSupportFragmentManager().isStateSaved()) {
            explain();
        }
    }

    private void explain() {
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_EXPLAINED, true).apply();
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.unrestricted_battery_title)
                .setMessage(R.string.unrestricted_battery_message)
                .setPositiveButton(R.string.system_settings,
                        (dialog, which) -> BatterySettings.requestUnrestrictedUsage(activity))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
