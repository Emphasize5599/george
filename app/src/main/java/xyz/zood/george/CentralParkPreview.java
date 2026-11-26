package xyz.zood.george;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.lifecycle.LifecycleOwner;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapLibreMapOptions;
import org.maplibre.android.maps.MapView;

import io.pijun.george.Utils;

/** Noninteractive map with the same tile styles used on the signed-in map. */
final class CentralParkPreview extends FrameLayout {
    private final ImageView settings;
    private final ImageView locate;
    private MapLibreMap map;
    private String style;
    private boolean dark;

    CentralParkPreview(Context context, LifecycleOwner lifecycle) {
        super(context);
        setContentDescription(context.getString(R.string.central_park_preview));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        MapLibre.getInstance(context);
        var options = MapLibreMapOptions.createFromAttributes(context)
                .textureMode(true)
                .camera(new CameraPosition.Builder().target(new LatLng(40.778, -73.969)).zoom(13.7).build());
        var mapView = new MapView(context, options);
        mapView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        addView(mapView, new LayoutParams(-1, -1));
        mapView.onCreate(null);
        lifecycle.getLifecycle().addObserver(new MapLibreMapLifecycleObserver(mapView));
        mapView.getMapAsync(ready -> {
            map = ready;
            var ui = map.getUiSettings();
            ui.setAllGesturesEnabled(false);
            ui.setCompassEnabled(false);
            applyStyle();
        });

        var controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        var position = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        position.setMargins(dp(10), dp(10), dp(10), dp(10));
        addView(controls, position);
        settings = addControl(controls, R.drawable.ic_settings_24dp);
        locate = addControl(controls, R.drawable.ic_my_location_black_24dp);
    }

    void setAppearance(int appearance) {
        dark = appearance == AppCompatDelegate.MODE_NIGHT_YES
                || (appearance == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM && Utils.isNightMode(getContext()));
        applyStyle();
        tintControl(settings);
        tintControl(locate);
    }

    private void applyStyle() {
        if (map == null) return;
        String next = dark ? MainFragment.DARK_MAP_STYLE : MainFragment.LIGHT_MAP_STYLE;
        if (!next.equals(style)) {
            style = next;
            map.setStyle(style);
        }
    }

    private ImageView addControl(LinearLayout controls, int icon) {
        var view = new ImageView(getContext());
        view.setImageResource(icon);
        view.setPadding(dp(9), dp(9), dp(9), dp(9));
        var size = new LinearLayout.LayoutParams(dp(40), dp(40));
        size.bottomMargin = dp(8);
        controls.addView(view, size);
        return view;
    }

    private void tintControl(ImageView view) {
        var surface = new GradientDrawable();
        surface.setCornerRadius(dp(12));
        surface.setColor(dark ? Color.rgb(34, 42, 52) : Color.WHITE);
        view.setBackground(surface);
        view.setColorFilter(dark ? Color.rgb(136, 198, 240) : Color.rgb(24, 86, 130));
    }

    private int dp(int size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }
}
