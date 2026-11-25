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
import androidx.lifecycle.ViewModelProvider;

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
import xyz.zood.george.viewmodels.MainViewModel;

/**
 * Debug-only fixture host for a dedicated tour emulator. No keys or access token are created.
 *
 * Scenes (adb shell am start -n <package>/xyz.zood.george.LayoutTourActivity --es scene <name>):
 * settings (default), friend, friend-long-name, friend-linked, friend-requesting, friend-unsupported, friend-keyboard, bar, avatar-crop.
 */
public class LayoutTourActivity extends AppCompatActivity {

    @Nullable private byte[] tourLinkFriend;
    private final androidx.activity.result.ActivityResultLauncher<Void> tourPicker = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.PickContact(), uri -> {
                byte[] friend = tourLinkFriend;
                android.util.Log.i("TourContactLink", "picker result uri=" + uri + " friend=" + (friend != null));
                if (uri == null || friend == null) return;
                new Thread(() -> {
                    boolean linked = LinkedContacts.linkPickedContact(friend, uri);
                    android.util.Log.i("TourContactLink", "picker uri=" + uri + " linked=" + linked
                            + " name=" + LinkedContacts.name(friend) + " photo=" + LinkedContacts.photoFile(friend).exists());
                }).start();
            });

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.enableEdgeToEdge(getWindow());
        // Never replace an account on an emulator/device that is already signed in.
        if (io.pijun.george.AuthenticationManager.isLoggedIn(this)) {
            finish();
            return;
        }
        String scene = getIntent().getStringExtra("scene");
        Prefs.get(this).setUsername("familydemo");
        byte[] fixtureAccount = new byte[16];
        // Keep this fixture's bar order independent of the folder tour's saved arrangement.
        if ("friend-keyboard".equals(scene)) java.util.Arrays.fill(fixtureAccount, (byte) 50);
        Prefs.get(this).setUserId(fixtureAccount);

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
        seedFriendBar(() -> getSupportFragmentManager().beginTransaction()
                .add(R.id.friends_bar_fragment, new FriendBarFragment()).commit());
    }

    private void seedFriendBar(Runnable onReady) {
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
                if (!isFinishing()) onReady.run();
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
        if ("friend-keyboard".equals(scene)) {
            MainFragment.applySystemUIInsets(root, root.findViewById(R.id.banners));
            seedFriendBar(() -> {
                FriendRecord last = DB.get().getFriends().stream()
                        .filter(friend -> "jamie".equals(friend.user.username)).findFirst().orElseThrow();
                getSupportFragmentManager().beginTransaction()
                        .add(R.id.friends_bar_fragment, new FriendBarFragment()).commit();
                populateFriendCard(scene, root, last);
            });
            return;
        }

        String username = scene.equals("friend-long-name") ? "alexander_golden_gate_family_demo" : "alexdemo";
        populateFriendCard(scene, root, TourFixtures.friend(username));
    }

    private void populateFriendCard(String scene, View root, FriendRecord friend) {
        FriendLocation location = TourFixtures.location();
        String realContact = getIntent().getStringExtra("contact");
        String photoRawContact = getIntent().getStringExtra("add-photo-to-raw-contact");
        if (photoRawContact != null) {
            // Debug: give a test contact a photo (a plain colored square) so the link can be checked with one.
            android.graphics.Bitmap square = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888);
            square.eraseColor(android.graphics.Color.rgb(0x2E, 0x7D, 0x32));
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            square.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, bytes);
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.ContactsContract.Data.RAW_CONTACT_ID, Long.parseLong(photoRawContact));
            values.put(android.provider.ContactsContract.Data.MIMETYPE, android.provider.ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE);
            values.put(android.provider.ContactsContract.CommonDataKinds.Photo.PHOTO, bytes.toByteArray());
            getContentResolver().insert(android.provider.ContactsContract.Data.CONTENT_URI, values);
        }
        if (realContact != null) {
            // Debug check of the real linking code: `--es contact <contact lookup URI>` (the kind of URI the
            // system contact picker returns) runs exactly what picking that contact does.
            boolean linked = LinkedContacts.linkPickedContact(friend.user.userId, android.net.Uri.parse(realContact));
            android.util.Log.i("TourContactLink", "linked=" + linked + " name=" + LinkedContacts.name(friend.user.userId)
                    + " nickname=" + Nicknames.local(friend.user.userId)
                    + " photo=" + LinkedContacts.photoFile(friend.user.userId).exists());
        } else if (scene.equals("friend-linked")) {
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
            UpdateStatusTracker.startRequest(friend.id);
        }

        ConstraintLayout panel = root.findViewById(R.id.info_panel);
        var viewModel = new ViewModelProvider(this).get(MainViewModel.class);
        InfoPanel infoPanel = new InfoPanel(panel, this, new InfoPanel.Listener() {
            @Override public void onInfoPanelCloseRequested() {}
            @Override public void onInfoPanelNicknameDraftChanged(long friendId, @Nullable String draft) {
                viewModel.setNicknameDraft(friendId, draft);
            }
            @Override public void onInfoPanelLocationRequested(@NonNull FriendRecord f) {
                UpdateStatusTracker.startRequest(f.id);
            }
            @Override public void onInfoPanelRemoveFriend(@NonNull FriendRecord f) {}
            @Override public void onInfoPanelOpenInMaps(@NonNull String displayName, @NonNull FriendLocation l) {}
            @Override public void onInfoPanelLinkContact(@NonNull FriendRecord f) {
                // Debug: the real system contact picker, then the real linking code.
                tourLinkFriend = f.user.userId;
                if (getIntent().getBooleanExtra("system-picker", false)) {
                    tourPicker.launch(null);
                } else {
                    ContactChooser.show(LayoutTourActivity.this, uri -> {
                        byte[] friend = f.user.userId;
                        new Thread(() -> {
                            boolean linked = LinkedContacts.linkPickedContact(friend, uri);
                            android.util.Log.i("TourContactLink", "chooser uri=" + uri + " linked=" + linked
                                    + " name=" + LinkedContacts.name(friend) + " photo=" + LinkedContacts.photoFile(friend).exists());
                        }).start();
                    });
                }
            }
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
        if ("friend-keyboard".equals(scene)) {
            viewModel.getFriendTileClick().observe(this, event -> {
                FriendRecord tapped = event.getEventIfNotHandled();
                if (tapped == null) return;
                if (tapped.id == infoPanel.getFriendId()) infoPanel.hide();
                else infoPanel.show(tapped, location);
            });
        }
    }
}
