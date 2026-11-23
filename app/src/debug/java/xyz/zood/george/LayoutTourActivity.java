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

import io.pijun.george.App;
import io.pijun.george.Constants;
import io.pijun.george.Prefs;
import io.pijun.george.api.UserComm;
import io.pijun.george.database.DB;
import io.pijun.george.SettingsFragment;
import io.pijun.george.UpdateStatusTracker;
import io.pijun.george.database.FriendLocation;
import io.pijun.george.database.FriendRecord;
import io.pijun.george.database.TourFixtures;
import io.pijun.george.database.UserRecord;
import xyz.zood.george.widget.InfoPanel;

/**
 * Debug-only fixture host for a dedicated tour emulator. No keys or access token are created.
 *
 * Scenes (adb shell am start -n <package>/xyz.zood.george.LayoutTourActivity --es scene <name>):
 * settings (default), friend, friend-long-name, friend-linked, friend-requesting, friend-unsupported, bar, avatar-crop.
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
        host.setBackgroundColor(getColor(R.color.background));
        setContentView(host);
        if ("bar".equals(scene)) {
            showFriendBar(host, state);
            return;
        }
        if ("avatar-crop".equals(scene)) {
            if (state == null) showAvatarCrop();
            return;
        }
        if (state == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_host, new SettingsFragment())
                    .commit();
        }
    }

    private void showAvatarCrop() {
        var image = android.graphics.Bitmap.createBitmap(960, 640, android.graphics.Bitmap.Config.ARGB_8888);
        var canvas = new android.graphics.Canvas(image);
        var paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        canvas.drawColor(Color.rgb(43, 110, 126));
        paint.setColor(Color.rgb(226, 170, 92));
        canvas.drawCircle(370, 320, 225, paint);
        paint.setColor(Color.rgb(112, 79, 146));
        canvas.drawRect(520, 110, 890, 560, paint);
        var file = new java.io.File(getCacheDir(), "tour-avatar.png");
        try (var output = new java.io.FileOutputStream(file)) {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Unable to write synthetic avatar", error);
        } finally {
            image.recycle();
        }
        startActivity(AvatarCropperActivity.newIntent(this, android.net.Uri.fromFile(file)));
    }

    private void showFriendBar(FrameLayout host, Bundle state) {
        FrameLayout bar = new FrameLayout(this);
        bar.setId(R.id.friends_bar_fragment);
        int height = getResources().getDimensionPixelSize(R.dimen.sixtyFour);
        host.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height, android.view.Gravity.CENTER));
        if (state != null) return;
        App.runInBackground(() -> {
            try {
                String[] names = {"alex", "blair", "casey", "devon", "eli", "frankie", "gray", "harper", "jamie"};
                for (int i = 0; i < names.length; i++) {
                    UserRecord user = tourUser(i + 20, names[i]);
                    if (DB.get().getFriendByUserId(user.id) == null) {
                        DB.get().startSharingWith(user, new byte[Constants.DROP_BOX_ID_LENGTH]);
                    }
                }
                for (int i = 0; i < 2; i++) {
                    UserRecord sender = tourUser(40 + i, i == 0 ? "taylor" : "quin");
                    if (DB.get().getFriendByUserId(sender.id) != null) continue;
                    UserComm grant = UserComm.newLocationSharingGrant(new byte[Constants.DROP_BOX_ID_LENGTH]);
                    grant.nickname = i == 0 ? "Taylor" : null;
                    FriendRequests.receive(sender, grant);
                    if (DB.get().getFriendByUserId(sender.id) != null) {
                        throw new IllegalStateException("An unapproved sender appeared in the friends list");
                    }
                }
            } catch (java.io.IOException | DB.DBException error) {
                throw new IllegalStateException("Unable to seed synthetic friend bar", error);
            }
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    getSupportFragmentManager().beginTransaction()
                            .add(R.id.friends_bar_fragment, new FriendBarFragment()).commit();
                }
            });
        });
    }

    private UserRecord tourUser(int number, String name) throws DB.DBException {
        byte[] id = new byte[Constants.USER_ID_LENGTH];
        java.util.Arrays.fill(id, (byte) number);
        UserRecord existing = DB.get().getUser(id);
        return existing == null ? DB.get().addUser(id, name, new byte[Constants.PUBLIC_KEY_LENGTH]) : existing;
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
        UserComm capability = UserComm.newDeviceStatus();
        capability.platform = UserComm.PLATFORM_IOS;
        capability.liveRequests = !scene.equals("friend-unsupported");
        FriendDevices.record(this, friend.user.userId, capability);
        if (scene.equals("friend-unsupported")) DeviceStatusProbe.onStatusReceived(friend.user.userId, false);
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
            @Override public void onInfoPanelViewSafetyNumber(@NonNull FriendRecord f) {
                SafetyNumberFragment.newInstance("familydemo", new byte[32], f.user.username, f.user.publicKey,
                        Nicknames.displayName(f.user)).show(getSupportFragmentManager(), "tour-safety-number");
            }
        });
        UpdateStatusTracker.addListener(infoPanel::updateRefreshProgressBarState);
        // The real screen hides the timed-share button while a friend's card is open.
        root.findViewById(R.id.timed_share_fab).setVisibility(View.GONE);
        panel.setTranslationX(0);
        infoPanel.show(friend, location);
    }
}
