package xyz.zood.george;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.concurrent.atomic.AtomicInteger;

import io.pijun.george.App;
import io.pijun.george.database.DB;
import io.pijun.george.database.FriendRecord;
import io.pijun.george.view.AvatarView;
import xyz.zood.george.viewmodels.MainViewModel;

public class FriendBarFragment extends Fragment implements DB.Listener, AvatarManager.Listener {
    private static final long GROUP_HOLD_MS = 550;
    private static final int[] GRID_MAX = {4, 5};
    private static final String[] GRID_KEYS = {"columns", "rows"};

    private final AtomicInteger friendRefreshGeneration = new AtomicInteger();
    private FriendsBarAdapter adapter;
    private MainViewModel mainViewModel;
    private RecyclerView list;
    private PopupWindow folderPopup;
    private final Runnable namesChanged = () -> {
        if (list != null) adapter.refresh();
    };


    @Override
    public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        mainViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        adapter = new FriendsBarAdapter(this);
        Nicknames.observe(namesChanged);
        DB.get().addListener(this);
        AvatarManager.addListener(this);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        list = (RecyclerView) inflater.inflate(R.layout.fragment_friends_bar, container, false);
        list.setLayoutManager(new LinearLayoutManager(inflater.getContext(), LinearLayoutManager.HORIZONTAL, false));
        list.setAdapter(adapter);
        new ItemTouchHelper(new BarDrag()).attachToRecyclerView(list);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                if (dx != 0) closeFolder();
            }
        });
        reloadFriends();
        return list;
    }

    @Override
    public void onDestroyView() {
        closeFolder();
        list = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        DB.get().removeListener(this);
        AvatarManager.removeListener(this);
        super.onDestroy();
    }

    private void reloadFriends() {
        int generation = friendRefreshGeneration.incrementAndGet();
        App.runInBackground(() -> {
            var friends = DB.get().getFriends();
            App.runOnUiThread(() -> {
                if (list != null && generation == friendRefreshGeneration.get()) adapter.setFriends(friends);
            });
        });
    }

    void onAddFriendAction() {
        mainViewModel.notifyAddFriendClicked();
    }

    void onRequestsAction() {
        var manager = requireActivity().getSupportFragmentManager();
        manager.beginTransaction().replace(R.id.fragment_host, FriendRequestsFragment.newInstance(false))
                .addToBackStack(null).commit();
    }

    void onFriendSelected(@NonNull FriendRecord friend) {
        mainViewModel.selectFriend(friend);
    }

    @Override public void onFriendRemoved(long friendId) { reloadFriends(); }
    @Override public void onLocationSharingGranted(long userId) { reloadFriends(); }
    @Override public void onLocationSharingRevoked(long userId) { reloadFriends(); }
    @Override public void onStartedSharingWithUser(long userId) { reloadFriends(); }
    @Override public void onStoppedSharingWithUser(long userId) { reloadFriends(); }
    @Override public void onAvatarUpdated(@Nullable String username) { namesChanged.run(); }

    void addMoveActions(View tile, RecyclerView.ViewHolder holder) {
        ViewCompat.addAccessibilityAction(tile, getString(R.string.move_earlier), (view, args) -> {
            adapter.nudge(holder.getBindingAdapterPosition(), -1);
            return true;
        });
        ViewCompat.addAccessibilityAction(tile, getString(R.string.move_later), (view, args) -> {
            adapter.nudge(holder.getBindingAdapterPosition(), 1);
            return true;
        });
    }

    String folderTitle(FriendBarArrangement.Item item) {
        return item.folder.name == null ? getString(R.string.friend_folder) : item.folder.name;
    }

    static int gridSize(Context context, int axis) {
        return context.getSharedPreferences("folder_grid", Context.MODE_PRIVATE).getInt(GRID_KEYS[axis], 3);
    }

    public static void chooseGridSize(Context context) {
        var preferences = context.getSharedPreferences("folder_grid", Context.MODE_PRIVATE);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.friend_folder_grid)
                .setItems(new String[]{
                        context.getString(R.string.friend_folder_columns, gridSize(context, 0)),
                        context.getString(R.string.friend_folder_rows, gridSize(context, 1))},
                        (dialog, axis) -> {
                            String[] values = new String[GRID_MAX[axis] - 1];
                            for (int i = 0; i < values.length; i++) values[i] = Integer.toString(i + 2);
                            new MaterialAlertDialogBuilder(context)
                                    .setTitle(axis == 0 ? R.string.folder_columns : R.string.folder_rows)
                                    .setSingleChoiceItems(values, gridSize(context, axis) - 2, (choice, selected) -> {
                                        preferences.edit().putInt(GRID_KEYS[axis], selected + 2).apply();
                                        choice.dismiss();
                                    })
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .show();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    void openFolder(View anchor, FriendBarArrangement.Item item) {
        closeFolder();
        Context context = anchor.getContext();
        MaterialCardView card = new MaterialCardView(context);
        card.setRadius(dp(20));
        card.setCardBackgroundColor(context.getColor(R.color.surface));
        card.setStrokeColor(context.getColor(R.color.text_secondary));
        card.setStrokeWidth(dp(1));
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(8), dp(12), dp(10));
        card.addView(body);

        MaterialButton title = new MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle);
        title.setText(getString(R.string.folder_menu_title, folderTitle(item)));
        title.setContentDescription(getString(R.string.folder_menu, folderTitle(item)));
        title.setOnClickListener(view -> folderMenu(view, item));
        body.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        int columns = gridSize(context, 0);
        int width = Math.min(dp(24 + columns * 64), list.getWidth() - dp(16));
        int cell = (width - dp(24)) / columns;
        var scroll = new ScrollView(context);
        var grid = new GridLayout(context);
        grid.setColumnCount(columns);
        scroll.addView(grid);
        int visibleRows = Math.min(gridSize(context, 1), (item.members.size() + columns - 1) / columns);
        body.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, visibleRows * cell));
        for (FriendRecord member : item.members) {
            View tile = LayoutInflater.from(context).inflate(R.layout.friends_bar_friend, grid, false);
            grid.addView(tile, new GridLayout.LayoutParams());
            tile.getLayoutParams().width = cell;
            tile.getLayoutParams().height = cell;
            FriendsBarAdapter.bindAvatar(tile.findViewById(R.id.avatar_image), member, R.dimen.forty);
            FriendsBarAdapter.setTileLabel(tile, Nicknames.displayName(member.user));
            tile.setOnClickListener(view -> {
                closeFolder();
                onFriendSelected(member);
            });
            makeMemberDraggable(tile, anchor, item, member);
        }
        int height = dp(66) + visibleRows * cell;
        int[] barPosition = new int[2];
        int[] anchorPosition = new int[2];
        list.getLocationOnScreen(barPosition);
        anchor.getLocationOnScreen(anchorPosition);
        int left = Math.max(barPosition[0] + dp(8), Math.min(
                anchorPosition[0] + (anchor.getWidth() - width) / 2,
                barPosition[0] + list.getWidth() - width - dp(8)));
        folderPopup = new PopupWindow(card, width, height, true);
        folderPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        folderPopup.setOutsideTouchable(true);
        folderPopup.setTouchModal(false);
        folderPopup.setElevation(dp(12));
        folderPopup.setOverlapAnchor(true);
        folderPopup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        folderPopup.showAsDropDown(anchor, left - anchorPosition[0], anchor.getHeight() - height, Gravity.LEFT);
    }

    private void closeFolder() {
        if (folderPopup != null) {
            folderPopup.dismiss();
            folderPopup = null;
        }
    }

    private void folderMenu(View anchor, FriendBarArrangement.Item item) {
        var menu = new PopupMenu(anchor.getContext(), anchor);
        menu.getMenu().add(R.string.ungroup_folder).setOnMenuItemClickListener(selection -> {
            closeFolder();
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.confirm_ungroup_folder, folderTitle(item)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.ungroup_folder, (dialog, which) -> adapter.ungroup(item))
                    .show();
            return true;
        });
        menu.getMenu().add(R.string.rename_folder).setOnMenuItemClickListener(selection -> {
            closeFolder();
            EditText input = new EditText(requireContext());
            input.setSingleLine();
            input.setText(item.folder.name);
            input.setHint(R.string.friend_folder);
            input.setContentDescription(getString(R.string.rename_folder));
            FrameLayout field = new FrameLayout(requireContext());
            field.setPadding(dp(24), 0, dp(24), 0);
            field.addView(input, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.rename_folder).setView(field)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.save, (dialog, which) -> adapter.rename(item, input.getText().toString().trim()))
                    .show();
            return true;
        });
        menu.show();
    }

    private void makeMemberDraggable(View tile, View anchor, FriendBarArrangement.Item item, FriendRecord member) {
        tile.setOnLongClickListener(view -> {
            var shadow = new View.DragShadowBuilder(view.findViewById(R.id.avatar_image));
            if (!view.startDragAndDrop(null, shadow, member, 0)) memberMenu(view, anchor, item, member);
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            return true;
        });
        ViewCompat.addAccessibilityAction(tile, getString(R.string.move_out_of_folder), (view, args) -> {
            closeFolder();
            adapter.moveOut(item, member);
            return true;
        });
        tile.setOnDragListener((view, event) -> {
            if (!(event.getLocalState() instanceof FriendRecord)) return false;
            FriendRecord moving = (FriendRecord) event.getLocalState();
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return item.members.contains(moving);
                case DragEvent.ACTION_DRAG_ENTERED:
                    if (!moving.equals(member)) view.setForeground(view.getContext().getDrawable(R.drawable.bg_folder_drop_target));
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                    view.setForeground(null);
                    return true;
                case DragEvent.ACTION_DROP:
                    int offset = item.members.indexOf(member) - item.members.indexOf(moving);
                    if (offset == 0) return false;
                    adapter.moveMember(item, moving, offset);
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    view.setForeground(null);
                    if (moving.equals(member)) {
                        if (event.getResult()) view.post(() -> reopenFolder(anchor, item.key));
                        else if (view.isAttachedToWindow()) memberMenu(view, anchor, item, member);
                    }
                    return true;
                default:
                    return true;
            }
        });
    }

    private void memberMenu(View tile, View anchor, FriendBarArrangement.Item item, FriendRecord member) {
        var menu = new PopupMenu(tile.getContext(), tile);
        int index = item.members.indexOf(member);
        if (index > 0) {
            menu.getMenu().add(R.string.move_earlier).setOnMenuItemClickListener(selection -> {
                adapter.moveMember(item, member, -1);
                reopenFolder(anchor, item.key);
                return true;
            });
        }
        if (index < item.members.size() - 1) {
            menu.getMenu().add(R.string.move_later).setOnMenuItemClickListener(selection -> {
                adapter.moveMember(item, member, 1);
                reopenFolder(anchor, item.key);
                return true;
            });
        }
        menu.getMenu().add(R.string.move_out_of_folder).setOnMenuItemClickListener(selection -> {
            closeFolder();
            adapter.moveOut(item, member);
            return true;
        });
        menu.show();
    }

    private void reopenFolder(View anchor, String key) {
        closeFolder();
        RecyclerView bar = list;
        if (bar == null) return;
        int position = bar.getChildAdapterPosition(anchor);
        bar.post(() -> {
            if (list != bar) return;
            var item = adapter.itemAt(position);
            var holder = bar.findViewHolderForAdapterPosition(position);
            if (item != null && holder != null && key.equals(item.key)) openFolder(holder.itemView, item);
        });
    }

    private int dp(float amount) {
        return Math.round(amount * getResources().getDisplayMetrics().density);
    }

    private final class BarDrag extends ItemTouchHelper.SimpleCallback {
        private FriendBarArrangement.Item moving;
        private FriendBarArrangement.Item target;
        private View hoverView;
        private long hoverSince;
        private boolean ready;
        private boolean changed;
        private final Runnable armGrouping = () -> {
            if (hoverView == null || moving == null || target == null) return;
            ready = true;
            hoverView.setForeground(hoverView.getContext().getDrawable(R.drawable.bg_folder_drop_target));
            hoverView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        };

        BarDrag() { super(ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0); }

        @Override public int getDragDirs(@NonNull RecyclerView view, @NonNull RecyclerView.ViewHolder holder) {
            return adapter.itemAt(holder.getBindingAdapterPosition()) == null ? 0 : super.getDragDirs(view, holder);
        }

        @Override public boolean onMove(@NonNull RecyclerView view, @NonNull RecyclerView.ViewHolder from,
                                        @NonNull RecyclerView.ViewHolder to) {
            var destination = adapter.itemAt(to.getBindingAdapterPosition());
            if (destination == null || destination == target) return false;
            boolean result = adapter.move(from.getBindingAdapterPosition(), to.getBindingAdapterPosition());
            if (result) {
                changed = true;
                from.itemView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
            return result;
        }

        @Override public boolean canDropOver(@NonNull RecyclerView view, @NonNull RecyclerView.ViewHolder movingHolder,
                                             @NonNull RecyclerView.ViewHolder targetHolder) {
            return adapter.itemAt(targetHolder.getBindingAdapterPosition()) != null;
        }

        @Override public float getMoveThreshold(@NonNull RecyclerView.ViewHolder holder) { return 0.6f; }

        @Override public void onSelectedChanged(@Nullable RecyclerView.ViewHolder holder, int state) {
            super.onSelectedChanged(holder, state);
            if (holder != null && state == ItemTouchHelper.ACTION_STATE_DRAG) {
                closeFolder();
                moving = adapter.itemAt(holder.getBindingAdapterPosition());
                changed = false;
                adapter.startDrag();
                holder.itemView.setScaleX(1.1f);
                holder.itemView.setScaleY(1.1f);
                holder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
        }

        @Override public void onChildDraw(@NonNull Canvas canvas, @NonNull RecyclerView view,
                                          @NonNull RecyclerView.ViewHolder holder, float dx, float dy,
                                          int state, boolean active) {
            if (active && state == ItemTouchHelper.ACTION_STATE_DRAG && moving != null && moving.folder == null) {
                float center = holder.itemView.getLeft() + holder.itemView.getWidth() / 2f + dx;
                View candidate = null;
                FriendBarArrangement.Item item = null;
                for (int i = 0; i < view.getChildCount(); i++) {
                    View child = view.getChildAt(i);
                    if (child == holder.itemView) continue;
                    var neighbor = adapter.itemAt(view.getChildAdapterPosition(child));
                    float delta = Math.abs(center - (child.getX() + child.getWidth() / 2f));
                    if (neighbor != null && delta < child.getWidth() * 0.27f) {
                        candidate = child;
                        item = neighbor;
                        break;
                    }
                }
                if (candidate != hoverView || item != target) {
                    clearHover();
                    hoverView = candidate;
                    target = item;
                    if (candidate != null) {
                        hoverSince = SystemClock.uptimeMillis();
                        if (item.folder == null) candidate.postDelayed(armGrouping, GROUP_HOLD_MS);
                        else armGrouping.run();
                    }
                }
            }
            super.onChildDraw(canvas, view, holder, dx, dy, state, active);
        }

        @Override public void clearView(@NonNull RecyclerView view, @NonNull RecyclerView.ViewHolder holder) {
            super.clearView(view, holder);
            holder.itemView.setScaleX(1);
            holder.itemView.setScaleY(1);
            var destination = ready || target != null && SystemClock.uptimeMillis() - hoverSince >= GROUP_HOLD_MS ? target : null;
            clearHover();
            var source = moving;
            moving = null;
            boolean reordered = changed;
            view.post(() -> adapter.finishDrag(source, destination, reordered));
        }

        private void clearHover() {
            if (hoverView != null) {
                hoverView.removeCallbacks(armGrouping);
                hoverView.setForeground(null);
            }
            hoverView = null;
            target = null;
            ready = false;
        }

        @Override public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {}
    }
}
