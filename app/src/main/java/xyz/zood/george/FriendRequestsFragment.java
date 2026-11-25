package xyz.zood.george;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.util.Comparator;

import io.pijun.george.App;
import io.pijun.george.L;
import io.pijun.george.database.DB;
import io.pijun.george.database.Snapshot;

public class FriendRequestsFragment extends Fragment {
    private LinearLayout rows;
    private final Runnable refresh = this::render;

    public static FriendRequestsFragment newInstance(boolean blocked) {
        FriendRequestsFragment fragment = new FriendRequestsFragment();
        Bundle args = new Bundle();
        args.putBoolean("blocked", blocked);
        fragment.setArguments(args);
        return fragment;
    }

    private boolean showsBlocked() {
        return requireArguments().getBoolean("blocked");
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle state) {
        Context context = requireContext();
        ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(context.getColor(R.color.background));
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(page);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, insets) -> {
            var bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            page.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom);
            return insets;
        });
        MaterialButton back = button(showsBlocked() ? R.string.blocked_users : R.string.friend_requests);
        back.setIconResource(R.drawable.ic_chevron_left_24dp);
        back.setOnClickListener(view -> getParentFragmentManager().popBackStack());
        page.addView(back);
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        page.addView(rows);
        Nicknames.observe(refresh);
        render();
        return scroll;
    }

    @Override
    public void onDestroyView() {
        rows = null;
        super.onDestroyView();
    }

    private void render() {
        if (rows == null) return;
        rows.removeAllViews();
        if (showsBlocked()) {
            var users = FriendRequests.blocked();
            users.sort(Comparator.comparing(user -> user.username, String.CASE_INSENSITIVE_ORDER));
            for (Snapshot.User user : users) {
                String name = Usernames.display(user.username);
                addName(name);
                addAction(rows, R.string.unblock, () -> confirm(R.string.unblock, name, () -> FriendRequests.unblock(user)));
            }
            if (users.isEmpty()) addName(getString(R.string.no_blocked_users));
        } else {
            var requests = FriendRequests.pending();
            boolean shareBack = NewFriendSharing.isEnabled(requireContext());
            if (shareBack && !requests.isEmpty()) {
                var explanation = new TextView(requireContext());
                explanation.setText(R.string.approve_shares_location);
                explanation.setPadding(0, 0, 0, dp(12));
                rows.addView(explanation);
            }
            for (FriendRequests.Request request : requests) {
                String name = request.displayName();
                var card = new com.google.android.material.card.MaterialCardView(requireContext());
                var cardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cardParams.bottomMargin = dp(8);
                card.setRadius(dp(16));
                card.setCardElevation(0);
                card.setCardBackgroundColor(requireContext().getColor(R.color.surface));
                rows.addView(card, cardParams);
                var content = new LinearLayout(requireContext());
                content.setOrientation(LinearLayout.VERTICAL);
                content.setPadding(dp(12), dp(8), dp(12), dp(4));
                card.addView(content, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                TextView heading = new TextView(requireContext());
                heading.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
                heading.setText(name);
                if (request.avatar != null) {
                    var bitmap = android.graphics.BitmapFactory.decodeByteArray(request.avatar, 0, request.avatar.length);
                    if (bitmap != null) {
                        var avatar = new com.google.android.material.imageview.ShapeableImageView(requireContext());
                        avatar.setShapeAppearanceModel(com.google.android.material.shape.ShapeAppearanceModel.builder()
                                .setAllCornerSizes(com.google.android.material.shape.ShapeAppearanceModel.PILL).build());
                        avatar.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                        avatar.setImageBitmap(bitmap);
                        var identity = new LinearLayout(requireContext());
                        identity.setGravity(android.view.Gravity.CENTER_VERTICAL);
                        identity.addView(avatar, dp(40), dp(40));
                        heading.setPadding(dp(10), 0, 0, 0);
                        identity.addView(heading);
                        content.addView(identity);
                    } else content.addView(heading);
                } else content.addView(heading);
                if (request.nickname != null) {
                    TextView handle = new TextView(requireContext());
                    handle.setText(Usernames.display(request.user.username));
                    handle.setTextColor(requireContext().getColor(R.color.text_secondary));
                    content.addView(handle);
                }
                if (!request.includesLocation()) {
                    TextView note = new TextView(requireContext());
                    note.setText(R.string.friend_request_no_location);
                    note.setTextColor(requireContext().getColor(R.color.text_secondary));
                    content.addView(note);
                }
                LinearLayout actions = new LinearLayout(requireContext());
                content.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                boolean shareOnApproval = shareBack && !request.requiresFreshOffer;
                addRequestAction(actions, shareOnApproval ? R.string.approve_and_share : R.string.approve_request,
                        () -> runAction(() -> FriendRequests.approve(request, shareOnApproval)));
                addRequestAction(actions, R.string.deny_request, () -> confirm(R.string.deny_request, name, () -> FriendRequests.deny(request)));
                addRequestAction(actions, R.string.block_user, () -> confirm(R.string.block_user, name, () -> FriendRequests.block(request.user)));
            }
            if (requests.isEmpty()) addName(getString(R.string.no_friend_requests));
        }
    }

    private MaterialButton button(int label) {
        MaterialButton button = new MaterialButton(requireContext(), null, androidx.appcompat.R.attr.borderlessButtonStyle);
        button.setText(label);
        return button;
    }

    private void addName(String name) {
        TextView label = new TextView(requireContext());
        label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        label.setText(name);
        int gap = Math.round(12 * getResources().getDisplayMetrics().density);
        label.setPadding(0, gap, 0, 0);
        rows.addView(label);
    }

    private void addAction(LinearLayout parent, int label, Runnable action) {
        MaterialButton button = button(label);
        if (label == R.string.unblock) button.setTextColor(requireContext().getColor(R.color.action_positive));
        button.setOnClickListener(view -> action.run());
        parent.addView(button);
    }

    private void addRequestAction(LinearLayout parent, int label, Runnable action) {
        var button = button(label);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumHeight(dp(48));
        button.setPadding(0, 0, 0, 0);
        button.setTextColor(requireContext().getColor((label == R.string.approve_request || label == R.string.approve_and_share)
                ? R.color.action_positive : R.color.action_destructive));
        button.setOnClickListener(view -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void confirm(int action, String name, Decision decision) {
        var dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.confirm_friend_action, getString(action), name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(action, (confirmation, which) -> runAction(decision))
                .show();
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setTextColor(
                requireContext().getColor(action == R.string.unblock ? R.color.action_positive : R.color.action_destructive));
    }

    private void runAction(Decision decision) {
        Context context = requireContext().getApplicationContext();
        byte[] account = io.pijun.george.Prefs.get(context).getUserId();
        App.runInBackground(() -> {
            try {
                synchronized (FriendRequests.class) {
                    if (!java.util.Arrays.equals(account, io.pijun.george.Prefs.get(context).getUserId())) return;
                    decision.apply();
                }
            } catch (IOException | DB.DBException error) {
                L.w("Unable to update friend request settings", error);
                App.runOnUiThread(() -> Toast.makeText(context, R.string.friend_request_error, Toast.LENGTH_SHORT).show());
            }
            App.runOnUiThread(this::render);
        });
    }

    private interface Decision {
        void apply() throws IOException, DB.DBException;
    }
}
