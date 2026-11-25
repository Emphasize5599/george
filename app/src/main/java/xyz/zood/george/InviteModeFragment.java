package xyz.zood.george;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import java.io.IOException;

import io.pijun.george.Prefs;
import xyz.zood.george.databinding.FragmentAddFriendBinding;

/** The invite choices use the same layout and navigation as Add Friend. */
public class InviteModeFragment extends Fragment {
    public static void showFrom(@NonNull Fragment source) {
        source.getParentFragmentManager().beginTransaction()
                .setCustomAnimations(R.animator.new_enter_from_right, R.animator.new_exit_to_left,
                        R.animator.new_enter_from_left, R.animator.new_exit_to_right)
                .replace(R.id.fragment_host, new InviteModeFragment())
                .addToBackStack(null)
                .commit();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        FragmentAddFriendBinding binding = FragmentAddFriendBinding.inflate(inflater, container, false);
        Context context = requireContext();
        boolean sharing = NewFriendSharing.isEnabled(context);
        binding.screenTitle.setText(R.string.share_an_invite);
        binding.screenPurpose.setText(sharing ? R.string.invite_choices_sharing_on : R.string.invite_choices_sharing_off);
        binding.usernameContainer.setVisibility(View.GONE);
        binding.back.setOnClickListener(view -> getParentFragmentManager().popBackStack());
        binding.addFriend.setText(R.string.invite_with_link);
        binding.addFriend.setEnabled(true);
        binding.addFriend.setOnClickListener(view -> sharePersonalLink(sharing));
        binding.inviteFriend.setText(R.string.invite_without_link);
        binding.inviteFriend.setOnClickListener(view -> share(getString(R.string.invite_friend_msg)));
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, (view, insets) -> {
            var statusBar = (ConstraintLayout.LayoutParams) binding.statusBarPlaceholder.getLayoutParams();
            statusBar.height = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            binding.statusBarPlaceholder.setLayoutParams(statusBar);
            binding.root.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        return binding.getRoot();
    }

    private void sharePersonalLink(boolean sharing) {
        Context context = requireContext();
        String username = Prefs.get(context).getUsername();
        if (username != null) {
            try {
                share(getString(R.string.invite_friend_link_msg, InviteTokens.issueLink(username, sharing)));
                return;
            } catch (IOException error) {
                io.pijun.george.L.w("Unable to create invite link", error);
            }
        }
        Toast.makeText(context, R.string.invite_link_failed, Toast.LENGTH_LONG).show();
    }

    private void share(String text) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_TEXT, text);
        intent.setType("text/plain");
        startActivity(Intent.createChooser(intent, getString(R.string.send_to)));
    }
}
