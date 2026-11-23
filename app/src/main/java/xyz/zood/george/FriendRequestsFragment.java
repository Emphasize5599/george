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
            for (FriendRequests.Request request : requests) {
                String name = request.displayName();
                addName(name);
                if (request.nickname != null) {
                    TextView handle = new TextView(requireContext());
                    handle.setText(Usernames.display(request.user.username));
                    handle.setTextColor(requireContext().getColor(R.color.text_secondary));
                    rows.addView(handle);
                }
                LinearLayout actions = new LinearLayout(requireContext());
                rows.addView(actions);
                addAction(actions, R.string.approve_request, () -> runAction(() -> FriendRequests.approve(request)));
                addAction(actions, R.string.deny_request, () -> confirm(R.string.deny_request, name, () -> FriendRequests.deny(request)));
                addAction(actions, R.string.block_user, () -> confirm(R.string.block_user, name, () -> FriendRequests.block(request.user)));
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
        button.setOnClickListener(view -> action.run());
        parent.addView(button);
    }

    private void confirm(int action, String name, Decision decision) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.confirm_friend_action, getString(action), name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(action, (dialog, which) -> runAction(decision))
                .show();
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
