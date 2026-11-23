package xyz.zood.george;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.WindowCompat;

import io.pijun.george.Prefs;
import io.pijun.george.SettingsFragment;
import io.pijun.george.UpdateStatusTracker;
import io.pijun.george.database.FriendLocation;
import io.pijun.george.database.FriendRecord;
import io.pijun.george.database.TourFixtures;
import xyz.zood.george.widget.InfoPanel;

/**
 * Debug-only fixture host for a dedicated tour emulator. No keys or access token are created.
 *
 * Scenes (adb shell am start -n <package>/xyz.zood.george.LayoutTourActivity --es scene <name>):
 * settings (default), friend, friend-long-name, friend-linked, friend-requesting.
 */
public class LayoutTourActivity extends AppCompatActivity {

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.enableEdgeToEdge(getWindow());
        // Never replace an account on an emulator/device that is already signed in.
        if (io.pijun.george.AuthenticationManager.isLoggedIn(this)) {
            finish();
            return;
        }
        Prefs.get(this).setUsername("familydemo");
        Prefs.get(this).setUserId(new byte[16]);

        String scene = getIntent().getStringExtra("scene");
        if (scene != null && scene.startsWith("friend")) {
            showFriendCard(scene);
            return;
        }

        FrameLayout host = new FrameLayout(this);
        host.setId(R.id.fragment_host);
        setContentView(host);
        if (state == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_host, new SettingsFragment())
                    .commit();
        }
    }

    /**
     * Inflates the real main-screen layout, but swaps the map and the embedded fragments for empty
     * placeholders, so the friend card can be shown without an account, keys or network.
     */
    private void showFriendCard(@NonNull String scene) {
        LayoutInflater inflater = LayoutInflater.from(getApplicationContext()).cloneInContext(this);
        inflater.setFactory2(new LayoutInflater.Factory2() {
            @Nullable
            @Override
            public View onCreateView(@Nullable View parent, @NonNull String name, @NonNull Context context, @NonNull AttributeSet attrs) {
                if (name.equals("fragment") || name.endsWith("MapView")) {
                    FrameLayout placeholder = new FrameLayout(context, attrs);
                    placeholder.setBackgroundColor(Color.rgb(170, 211, 223));
                    return placeholder;
                }
                // Everything else goes through AppCompat, so tints and Material widgets match the app.
                return getDelegate().createView(parent, name, context, attrs);
            }

            @Nullable
            @Override
            public View onCreateView(@NonNull String name, @NonNull Context context, @NonNull AttributeSet attrs) {
                return onCreateView(null, name, context, attrs);
            }
        });
        View root = inflater.inflate(R.layout.fragment_main, (ViewGroup) null, false);
        setContentView(root);

        String username = scene.equals("friend-long-name") ? "alexander_golden_gate_family_demo" : "alexdemo";
        FriendRecord friend = TourFixtures.friend(username);
        FriendLocation location = TourFixtures.location();
        if (scene.equals("friend-linked")) {
            LinkedContacts.link(friend.user.userId, "Alex Rivera", "content://fixture/contact");
        } else {
            LinkedContacts.unlink(friend.user.userId);
        }
        if (scene.equals("friend-requesting")) {
            UpdateStatusTracker.setLastRequestTime(friend.id, System.currentTimeMillis());
        }

        ConstraintLayout panel = root.findViewById(R.id.info_panel);
        InfoPanel infoPanel = new InfoPanel(panel, this, new InfoPanel.Listener() {
            @Override public void onInfoPanelCloseRequested() {}
            @Override public void onInfoPanelLocationRequested(@NonNull FriendRecord f) {
                UpdateStatusTracker.setLastRequestTime(f.id, System.currentTimeMillis());
            }
            @Override public void onInfoPanelRemoveFriend(@NonNull FriendRecord f) {}
            @Override public void onInfoPanelOpenInMaps(@NonNull String displayName, @NonNull FriendLocation l) {}
            @Override public void onInfoPanelLinkContact(@NonNull FriendRecord f) {}
            @Override public void onInfoPanelShareToggled(@NonNull FriendRecord f, boolean share) {}
            @Override public void onInfoPanelViewSafetyNumber(@NonNull FriendRecord f) {}
        });
        UpdateStatusTracker.addListener(infoPanel::updateRefreshProgressBarState);
        // The real screen hides the timed-share button while a friend's card is open.
        root.findViewById(R.id.timed_share_fab).setVisibility(View.GONE);
        panel.setTranslationX(0);
        infoPanel.show(friend, location);
    }
}
