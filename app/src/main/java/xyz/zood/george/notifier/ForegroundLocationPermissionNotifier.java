package xyz.zood.george.notifier;

import android.Manifest;
import android.content.Intent;
import android.content.Context;
import android.net.Uri;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import xyz.zood.george.MainFragment;
import xyz.zood.george.Permissions;
import xyz.zood.george.R;
import xyz.zood.george.widget.BannerView;
import xyz.zood.george.widget.ZoodDialog;

public class ForegroundLocationPermissionNotifier implements DefaultLifecycleObserver {

    @NonNull private final BannerView banner;
    @NonNull private final FragmentActivity activity;
    @NonNull private final MainFragment fragment;
    private static final int bannerItemId = 5811;
    private static final String LOCATION_PERMISSION_PREFS = "location_permission";
    private static final String FOREGROUND_REQUESTED = "foreground_requested";

    public ForegroundLocationPermissionNotifier(@NonNull FragmentActivity activity, @NonNull MainFragment fragment, @NonNull BannerView banner) {
        this.banner = banner;
        this.activity = activity;
        this.fragment = fragment;
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        if (Permissions.checkForegroundLocationPermission(activity)) {
            hide();
        }
        // not having the permission doesn't definitively mean we need to show the banner item.
        // There is no 'not asked' state for permissions, so we wait for MainFragment to explicitly
        // call show() if the user rejects the permission.
    }

    private void hide() {
        banner.removeItem(bannerItemId);
    }

    private void onBannerItemClicked() {
        var prefs = activity.getSharedPreferences(LOCATION_PERMISSION_PREFS, Context.MODE_PRIVATE);
        boolean openSettings = prefs.getBoolean(FOREGROUND_REQUESTED, false)
                && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION);
        @StringRes int message = openSettings ? R.string.grant_foreground_location_permission_via_settings_msg
                : R.string.grant_foreground_location_permission_msg;
        ZoodDialog dialog = ZoodDialog.newInstance(activity.getString(message));
        dialog.setTitle(activity.getString(R.string.location_permission));
        dialog.setButton1(activity.getString(openSettings ? R.string.settings : R.string.continue_label), view -> {
            if (openSettings) {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.fromParts("package", activity.getPackageName(), null));
                activity.startActivity(intent);
            } else {
                prefs.edit().putBoolean(FOREGROUND_REQUESTED, true).apply();
                fragment.fgLocationPermLauncher.launch(Permissions.getForegroundLocationPermissions());
            }
        });
        dialog.setButton2(activity.getString(R.string.no_thanks), null);
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    public void show() {
        banner.addItem(activity.getString(R.string.location_permission_needed), activity.getString(R.string.fix), bannerItemId, new BannerView.ItemClickListener() {
            @Override
            public void onBannerItemClick(int id) {
                onBannerItemClicked();
            }
        });
    }
}
