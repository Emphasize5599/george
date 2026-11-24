package io.pijun.george;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.squareup.picasso.Picasso;

import java.io.File;

import xyz.zood.george.AvatarManager;
import xyz.zood.george.BatterySettings;
import xyz.zood.george.FriendshipManager;
import xyz.zood.george.FriendBarFragment;
import xyz.zood.george.NoticesFragment;
import xyz.zood.george.R;
import xyz.zood.george.ThemeDialog;
import xyz.zood.george.databinding.FragmentSettingsBinding;
import xyz.zood.george.service.LiveRequestService;
import xyz.zood.george.widget.ZoodDialog;

public class SettingsFragment extends Fragment implements SettingsAdapter.Listener, AvatarManager.Listener {

    private FragmentSettingsBinding binding;
    private SettingsAdapter adapter;

    public static SettingsFragment newInstance() {
        return new SettingsFragment();
    }

    // applySystemUIInsets applies the system view insets. It should only be called once after the fragment's view is created.
    private void applySystemUIInsets(FragmentSettingsBinding binding) {
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsView, (v, insets) -> {
            int statusBarHeight;
            statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;

            ConstraintLayout.LayoutParams sbLP = (ConstraintLayout.LayoutParams) binding.statusBarPlaceholder.getLayoutParams();
            sbLP.height = statusBarHeight;
            binding.statusBarPlaceholder.setLayoutParams(sbLP);

            return WindowInsetsCompat.CONSUMED;
        });
    }

    //region Lifecycle
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        adapter = new SettingsAdapter(this);
        AvatarManager.addListener(this);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        binding.list.setAdapter(adapter);
        binding.back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getParentFragmentManager().popBackStack();
            }
        });

        Prefs prefs = Prefs.get(requireContext());
        String username = prefs.getUsername();
        binding.avatar.setUsername(ownNickname(username));
        Context ctx = requireContext();
        File myImg = AvatarManager.getMyAvatar(ctx);
        Picasso.with(ctx).load(myImg).into(binding.avatar);
        String ownNickname = xyz.zood.george.Nicknames.own();
        String handle = xyz.zood.george.Usernames.display(username);
        binding.username.setText(ownNickname != null ? ownNickname : handle);
        binding.detail.setText(handle);
        binding.detail.setVisibility(ownNickname != null ? View.VISIBLE : View.GONE);
        binding.profileItem.setOnClickListener(view -> showSettingsPage(new xyz.zood.george.AccountFragment()));

        applySystemUIInsets(binding);

        return binding.getRoot();
    }

    @Override
    public void onDestroy() {
        AvatarManager.removeListener(this);

        super.onDestroy();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        binding = null;
    }

    //endregion

    //region SettingsAdapter.Listener methods

    @Override
    public void onAboutAction() {
        showSettingsPage(new NoticesFragment());
    }

    public void onInviteFriendAction() {
        FriendshipManager.inviteFriend(requireContext());
    }

    @Override
    public void onLogOutAction() {
        ZoodDialog dialog = ZoodDialog.newInstance(getString(R.string.confirm_log_out_msg));
        dialog.setButton1(getString(R.string.log_out), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AuthenticationManager.get().logOut(requireContext(), new AuthenticationManager.LogoutWatcher() {
                    @Override
                    public void onUserLoggedOut() {
                        requireActivity().finish();
                    }
                });
            }
        });
        dialog.setButton2(getString(R.string.cancel), null);
        FragmentManager fm = getParentFragmentManager();
        dialog.show(fm, null);
    }

    public void onNotificationsClicked() {

    }

    public void onPrivacyPolicyClicked() {
        String url = "https://github.com/Emphasize5599/family-locator-ios/blob/main/Docs/PRIVACY.md?view=policy";
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setData(Uri.parse(url));
        startActivity(i);
    }

    @Override
    public void onFolderGridAction() {
        FriendBarFragment.chooseGridSize(requireContext());
    }

    private void showSettingsPage(Fragment fragment) {
        FragmentManager mgr = getParentFragmentManager();
        mgr.beginTransaction()
                .setCustomAnimations(R.animator.new_enter_from_right,
                        R.animator.new_exit_to_left,
                        R.animator.new_enter_from_left,
                        R.animator.new_exit_to_right)
                .replace(R.id.fragment_host, fragment)
                .addToBackStack(null)
                .commit();
    }

    @Override
    public void onLiveRequestsAction() {
        Context ctx = requireContext();
        boolean on = LiveRequestService.isEnabled(ctx);
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.live_requests)
                .setMessage(R.string.live_requests_dialog_message)
                .setPositiveButton(on ? R.string.turn_off : R.string.turn_on, (dialog, which) -> {
                    LiveRequestService.setEnabled(ctx, !on);
                    adapter.refreshLiveRequestsItem();
                    if (!on) {
                        BatterySettings.requestUnrestrictedUsage(ctx);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onThemeAction() {
        ThemeDialog.newInstance().show(getParentFragmentManager(), null);
    }

    //endregion

    //region AvatarManager.Listener

    @Override
    public void onAvatarUpdated(@Nullable String username) {
        if (username != null) {
            // we're only interested in updates to our personal avatar
            return;
        }

        Context ctx = getContext();
        if (ctx == null || binding == null) {
            return;
        }

        File myImg = AvatarManager.getMyAvatar(ctx);
        Picasso.with(ctx).load(myImg).into(binding.avatar);
    }

    //endregion

    private static String ownNickname(String username) {
        String nickname = xyz.zood.george.Nicknames.own();
        return nickname != null ? nickname : username;
    }
}
