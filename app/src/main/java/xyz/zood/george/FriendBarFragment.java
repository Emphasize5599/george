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
import android.util.TypedValue;

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
    @Nullable private View folderCard;
    @Nullable private String openFolderKey;
    @Nullable private String folderClosedKey;
    private long folderClosedByTapAt;
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

    /** A tap on a folder's tile in the bar: opens it, or closes it when it's the folder that's open. */
    void openFolder(View anchor, FriendBarArrangement.Item item) {
        if (folderTapShouldClose(item.key)) return;
        showFolder(anchor, item);
    }

    private void showFolder(View anchor, FriendBarArrangement.Item item) {
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

        // Header: the folder's name (wrapping onto more lines when it's long) and its ⋮ menu.
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(48));
        TextView title = new TextView(context);
        title.setText(folderTitle(item));
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        title.setTextColor(context.getColor(R.color.text_primary));
        title.setPadding(0, dp(8), dp(8), dp(8));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.ImageButton more = new android.widget.ImageButton(context);
        more.setImageResource(R.drawable.ic_sharp_more_vert_24dp);
        more.setColorFilter(context.getColor(R.color.text_secondary));
        TypedValue ripple = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        more.setBackgroundResource(ripple.resourceId);
        more.setContentDescription(getString(R.string.folder_menu, folderTitle(item)));
        more.setOnClickListener(view -> folderMenu(view, item));
        header.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
        body.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Same sizing rules as iOS: the card is only as wide as the people in it (at most the columns set in
        // Settings), with room for its header, and as many rows as it needs up to the set number; more scroll.
        int columns = gridSize(context, 0);
        int usedColumns = Math.max(1, Math.min(columns, item.members.size()));
        int visibleRows = Math.max(1, Math.min(gridSize(context, 1), (item.members.size() + columns - 1) / columns));
        int cellWidth = dp(64), cellHeight = dp(64), gap = dp(16);
        int gridWidth = usedColumns * cellWidth + (usedColumns - 1) * gap;
        int gridHeight = visibleRows * cellHeight + (visibleRows - 1) * gap + dp(8);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int margin = dp(12);
        body.setPadding(dp(16), dp(8), dp(16), dp(16));
        // By default the grid sets the width; a longer name widens the card to fit on one line, up to the
        // screen margins, and only then wraps (same rule as iOS).
        int titleWidth = (int) Math.ceil(title.getPaint().measureText(folderTitle(item))) + dp(8) + dp(48);
        int width = Math.min(Math.max(Math.max(gridWidth, titleWidth), dp(140)) + dp(32), screenWidth - 2 * margin);
        var scroll = new ScrollView(context);
        // Only a folder with more rows than fit can scroll, so only it shows a scroll bar.
        int rows = (item.members.size() + usedColumns - 1) / usedColumns;
        scroll.setVerticalScrollBarEnabled(rows > visibleRows);
        var grid = new GridLayout(context);
        grid.setColumnCount(usedColumns);
        grid.setPadding(0, dp(4), 0, dp(4));
        scroll.addView(grid, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, gridHeight);
        scrollParams.gravity = Gravity.START;
        body.addView(scroll, scrollParams);
        int index = 0;
        for (FriendRecord member : item.members) {
            View tile = LayoutInflater.from(context).inflate(R.layout.friends_bar_friend, grid, false);
            GridLayout.LayoutParams cellParams = new GridLayout.LayoutParams();
            cellParams.width = cellWidth;
            cellParams.height = cellHeight;
            boolean lastColumn = index % usedColumns == usedColumns - 1;
            boolean lastRow = index / usedColumns == (item.members.size() - 1) / usedColumns;
            // Gaps only between cells, so a folder that fits never has extra height to scroll.
            cellParams.setMargins(0, 0, lastColumn ? 0 : gap, lastRow ? 0 : gap);
            grid.addView(tile, cellParams);
            index++;
            FriendsBarAdapter.bindAvatar(tile.findViewById(R.id.avatar_image), member, R.dimen.forty);
            FriendsBarAdapter.setTileLabel(tile, Nicknames.displayName(member.user));
            tile.setOnClickListener(view -> {
                closeFolder();
                onFriendSelected(member);
            });
            makeMemberDraggable(tile, anchor, item, member);
        }
        // Measure the real height (the title may wrap onto several lines).
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int height = card.getMeasuredHeight();
        int[] barPosition = new int[2];
        int[] anchorPosition = new int[2];
        list.getLocationOnScreen(barPosition);
        anchor.getLocationOnScreen(anchorPosition);
        // Centered over the folder's tile, or pinned to the screen margin when the tile is near an edge,
        // floating just above the bar (over the map and any open friend card) without moving anything.
        int left = Math.max(margin, Math.min(anchorPosition[0] + (anchor.getWidth() - width) / 2, screenWidth - margin - width));
        int top = barPosition[1] - margin - height;
        // The window reaches down to the top of the bar and clips the card, so the card slides up out from
        // behind the bar when it opens and back down behind it when it closes. A little room on the sides and
        // top keeps the card's shadow from being clipped.
        int shadow = dp(8);
        card.setCardElevation(dp(6));
        FrameLayout stage = new FrameLayout(context);
        stage.setClipChildren(true);
        stage.setPadding(shadow, shadow, shadow, 0);
        stage.addView(card, new FrameLayout.LayoutParams(width, height, Gravity.TOP));
        int travel = height + margin;
        PopupWindow popup = new PopupWindow(stage, width + 2 * shadow, height + margin + shadow, true);
        folderPopup = popup;
        folderPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        folderPopup.setOutsideTouchable(true);
        folderPopup.setTouchModal(false);
        folderPopup.setAnimationStyle(0);
        folderPopup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        String key = item.key;
        // A tap outside slides the folder away. Tapping the folder's own tile closes it rather than reopening it.
        folderPopup.setTouchInterceptor((view, event) -> {
            // A folder that's already sliding away ignores touches (they reach the bar instead).
            if (popup != folderPopup) return false;
            if (event.getAction() == android.view.MotionEvent.ACTION_OUTSIDE) {
                folderClosedByTapAt = android.os.SystemClock.uptimeMillis();
                folderClosedKey = key;
                closeFolder();
                return true;
            }
            return false;
        });
        folderPopup.showAtLocation(anchor.getRootView(), Gravity.TOP | Gravity.START, left - shadow, top - shadow);
        folderCard = card;
        openFolderKey = key;
        card.setTranslationY(travel);
        card.animate().translationY(0).setDuration(250)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
    }

    /** True when this tap on a folder's tile should close that folder instead of opening it. */
    boolean folderTapShouldClose(String key) {
        if (key.equals(openFolderKey) && folderPopup != null) {
            closeFolder();
            return true;
        }
        // The tap that just closed this folder (by landing on its tile) is used up here, once, so the very next
        // tap reopens it however quickly it comes.
        boolean justClosed = key.equals(folderClosedKey) && android.os.SystemClock.uptimeMillis() - folderClosedByTapAt < 500;
        folderClosedKey = null;
        return justClosed;
    }

    private void closeFolder() {
        PopupWindow popup = folderPopup;
        View card = folderCard;
        folderPopup = null;
        folderCard = null;
        openFolderKey = null;
        if (popup == null) return;
        if (card == null || !card.isAttachedToWindow()) {
            popup.dismiss();
            return;
        }
        // Let taps pass straight through to the bar while it slides away, so a quick reopen always works.
        popup.setTouchable(false);
        popup.setFocusable(false);
        popup.update();
        card.animate().cancel();
        // Done the moment the card's top edge is behind the bar (the window ends at the bar's top edge).
        float hidden = ((View) card.getParent()).getHeight() - card.getTop();
        card.animate().translationY(hidden).setDuration(150)
                .setInterpolator(new androidx.interpolator.view.animation.FastOutLinearInInterpolator())
                .withEndAction(popup::dismiss).start();
    }

    /** The folder's ⋮ menu, opening above the button like the friend card's (below only if there's no room). */
    private void folderMenu(View anchor, FriendBarArrangement.Item item) {
        Context context = anchor.getContext();
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(6), 0, dp(6));
        PopupWindow popup = new PopupWindow(list, dp(240), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setCornerRadius(dp(16));
        background.setColor(context.getColor(R.color.surface_high));
        popup.setBackgroundDrawable(background);
        popup.setElevation(dp(8));
        addMenuRow(list, popup, R.string.ungroup_folder, R.drawable.ic_menu_folder_off, true, () -> {
            closeFolder();
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.confirm_ungroup_folder, folderTitle(item)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.ungroup_folder, (dialog, which) -> adapter.ungroup(item))
                    .show();
        });
        addMenuRow(list, popup, R.string.rename_folder, R.drawable.ic_menu_edit, false, () -> {
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
        });
        showMenuAbove(popup, list, anchor);
    }

    private void addMenuRow(LinearLayout list, PopupWindow popup, int label, int icon, boolean destructive, Runnable action) {
        Context context = list.getContext();
        TextView row = new TextView(context);
        row.setText(label);
        row.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        int color = context.getColor(destructive ? R.color.zood_red : R.color.text_primary);
        row.setTextColor(color);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinHeight(dp(48));
        row.setPaddingRelative(dp(16), dp(7), dp(16), dp(7));
        row.setCompoundDrawablePadding(dp(12));
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        row.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(
                destructive ? color : context.getColor(R.color.text_secondary)));
        TypedValue selectable = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, selectable, true);
        row.setBackgroundResource(selectable.resourceId);
        row.setOnClickListener(view -> {
            popup.dismiss();
            action.run();
        });
        list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void showMenuAbove(PopupWindow popup, LinearLayout list, View anchor) {
        int width = dp(240);
        list.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int height = list.getMeasuredHeight();
        // Attach to the activity's window: the anchor lives in the folder card's own popup window, which can't
        // host another popup. Positions are screen coordinates either way.
        View root = requireActivity().getWindow().getDecorView();
        androidx.core.view.WindowInsetsCompat insets = androidx.core.view.ViewCompat.getRootWindowInsets(root);
        int topInset = insets == null ? 0 : insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).top;
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int y = at[1] - dp(4) - height;
        if (y < topInset + dp(4)) y = at[1] + anchor.getHeight() + dp(4);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int x = Math.max(dp(8), Math.min(at[0] + anchor.getWidth() - width, screenWidth - dp(8) - width));
        popup.setHeight(height);
        popup.showAtLocation(root, Gravity.TOP | Gravity.START, x, y);
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
            if (item != null && holder != null && key.equals(item.key)) showFolder(holder.itemView, item);
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
