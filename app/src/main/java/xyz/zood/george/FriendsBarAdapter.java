package xyz.zood.george;

import android.graphics.Outline;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import io.pijun.george.App;
import io.pijun.george.database.FriendRecord;
import io.pijun.george.view.AvatarRenderer;
import io.pijun.george.view.AvatarView;
import xyz.zood.george.viewmodels.MainViewModel.NicknameDraft;

final class FriendsBarAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int APP_ITEMS = 3;
    private static final Object NICKNAME_PAYLOAD = new Object();
    private static final Object FOLDER_TITLE_PAYLOAD = new Object();
    private final FriendBarArrangement arrangement = new FriendBarArrangement();
    private final FriendBarFragment listener;
    private ArrayList<FriendRecord> friends = new ArrayList<>();
    private ArrayList<FriendBarArrangement.Item> people = new ArrayList<>();
    private boolean dragging;
    @Nullable private NicknameDraft draft;

    FriendsBarAdapter(FriendBarFragment listener) {
        this.listener = listener;
    }

    void setFriends(ArrayList<FriendRecord> updated) {
        friends = updated;
        refresh();
    }

    void refresh() {
        if (dragging) return;
        people = arrangement.items(friends);
        notifyDataSetChanged();
    }

    void startDrag() {
        dragging = true;
    }

    void finishDrag(@Nullable FriendBarArrangement.Item moving, @Nullable FriendBarArrangement.Item target, boolean changed) {
        dragging = false;
        if (changed || target != null) {
            arrangement.arrange(people);
            if (target != null && moving != null) arrangement.group(moving, target);
            arrangement.save();
        }
        refresh();
    }

    boolean move(int from, int to) {
        if (from == to || itemAt(from) == null || itemAt(to) == null) return false;
        people.add(to - 1, people.remove(from - 1));
        notifyItemMoved(from, to);
        return true;
    }

    void nudge(int position, int offset) {
        if (move(position, position + offset)) finishDrag(null, null, true);
    }

    void rename(FriendBarArrangement.Item item, String name) {
        item.folder.name = name.isEmpty() ? null : name;
        arrangement.save();
        refresh();
    }

    void ungroup(FriendBarArrangement.Item item) {
        arrangement.arrange(people);
        arrangement.ungroup(item);
        arrangement.save();
        refresh();
    }

    void moveOut(FriendBarArrangement.Item item, FriendRecord friend) {
        arrangement.arrange(people);
        arrangement.moveOut(item, friend);
        arrangement.save();
        refresh();
    }

    void moveMember(FriendBarArrangement.Item item, FriendRecord friend, int offset) {
        int from = item.members.indexOf(friend);
        int to = from + offset;
        if (from < 0 || to < 0 || to >= item.members.size()) return;
        byte[] id = friend.user.userId;
        byte[] target = item.members.get(to).user.userId;
        item.folder.members.removeIf(member -> java.util.Arrays.equals(id, member));
        for (int index = 0; index < item.folder.members.size(); index++) {
            if (java.util.Arrays.equals(target, item.folder.members.get(index))) {
                item.folder.members.add(index + (offset > 0 ? 1 : 0), id);
                break;
            }
        }
        arrangement.save();
        refresh();
    }

    @Nullable FriendBarArrangement.Item itemAt(int position) {
        return position > 0 && position <= people.size() ? people.get(position - 1) : null;
    }

    int positionOfFriend(long id) {
        for (int index = 0; index < people.size(); index++) {
            var item = people.get(index);
            if (item.folder == null && item.friend().id == id) return index + 1;
        }
        return RecyclerView.NO_POSITION;
    }

    @Nullable NicknameDraft getNicknameDraft() {
        return draft;
    }

    void setNicknameDraft(@Nullable NicknameDraft next) {
        int previousPosition = draft == null ? RecyclerView.NO_POSITION : positionOfFriend(draft.friendId);
        int nextPosition = next == null ? RecyclerView.NO_POSITION : positionOfFriend(next.friendId);
        draft = next;
        if (previousPosition != RecyclerView.NO_POSITION && previousPosition != nextPosition) {
            notifyItemChanged(previousPosition, NICKNAME_PAYLOAD);
        }
        if (nextPosition != RecyclerView.NO_POSITION) notifyItemChanged(nextPosition, NICKNAME_PAYLOAD);
    }

    void updateFolderTitle(@Nullable String key) {
        for (int index = 0; index < people.size(); index++) {
            if (people.get(index).key.equals(key)) {
                notifyItemChanged(index + 1, FOLDER_TITLE_PAYLOAD);
                return;
            }
        }
    }

    @Override public int getItemCount() {
        return 1 + people.size() + APP_ITEMS;
    }

    @Override public int getItemViewType(int position) {
        if (position == 0) return R.layout.friends_bar_margin;
        FriendBarArrangement.Item item = itemAt(position);
        if (item != null) return item.folder == null ? R.layout.friends_bar_friend : R.layout.friends_bar_folder;
        if (position == people.size() + 1) return R.layout.friends_bar_divider;
        if (position == people.size() + 2) return R.layout.friends_bar_requests;
        return R.layout.friends_bar_add_friend;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        FriendBarArrangement.Item item = itemAt(position);
        View tile = holder.itemView;
        if (item != null && item.folder == null) {
            bindAvatar(tile.findViewById(R.id.avatar_image), item.friend(), R.dimen.forty);
            bindFriendName(tile, item.friend());
        } else if (item != null) {
            GridLayout grid = tile.findViewById(R.id.folder_preview);
            grid.removeAllViews();
            for (FriendRecord member : item.members.subList(0, Math.min(4, item.members.size()))) {
                AvatarView avatar = new AvatarView(grid.getContext());
                GridLayout.LayoutParams params = new GridLayout.LayoutParams();
                int size = grid.getResources().getDimensionPixelSize(R.dimen.folder_mini_avatar);
                params.width = size;
                params.height = size;
                params.setMargins(1, 1, 1, 1);
                grid.addView(avatar, params);
                bindAvatar(avatar, member, R.dimen.folder_mini_avatar);
            }
            setTileLabel(tile, listener.folderTileTitle(item));
        } else if (holder.getItemViewType() == R.layout.friends_bar_requests) {
            TextView badge = tile.findViewById(R.id.requests_badge);
            int count = FriendRequests.pending().size();
            badge.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
            badge.setText(count > 99 ? tile.getContext().getString(R.string.requests_badge_overflow) : Integer.toString(count));
            tile.setContentDescription(count == 0 ? tile.getContext().getString(R.string.friend_requests)
                    : tile.getResources().getQuantityString(R.plurals.friend_requests_count, count, count));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
        FriendBarArrangement.Item item = itemAt(position);
        if (payloads.contains(NICKNAME_PAYLOAD) && item != null && item.folder == null) {
            bindFriendName(holder.itemView, item.friend());
        } else if (payloads.contains(FOLDER_TITLE_PAYLOAD) && item != null && item.folder != null) {
            setTileLabel(holder.itemView, listener.folderTileTitle(item));
        } else {
            super.onBindViewHolder(holder, position, payloads);
        }
    }

    private void bindFriendName(View tile, FriendRecord friend) {
        String displayName = Nicknames.displayName(friend.user);
        String avatarName = Nicknames.avatarName(friend.user);
        if (draft != null && draft.friendId == friend.id && !draft.text.equals(displayName)) {
            String nickname = Nicknames.normalized(draft.text);
            displayName = Nicknames.displayName(friend.user, nickname);
            avatarName = Nicknames.avatarName(friend.user, nickname);
        }
        AvatarView avatar = tile.findViewById(R.id.avatar_image);
        avatar.setDisplayName(avatarName);
        setTileLabel(tile, displayName);
    }

    static void setTileLabel(View tile, String name) {
        TextView label = tile.findViewById(R.id.bar_tile_label);
        label.setText(name);
        tile.setContentDescription(name);
    }

    static void bindAvatar(AvatarView avatar, FriendRecord friend, int sizeRes) {
        avatar.setUsername(Nicknames.avatarName(friend.user));
        Object request = new Object();
        avatar.setTag(request);
        var context = avatar.getContext().getApplicationContext();
        int size = avatar.getResources().getDimensionPixelSize(sizeRes);
        App.runInBackground(() -> {
            var photo = AvatarRenderer.loadFriendPhoto(context, friend.user, size);
            if (photo != null) {
                App.runOnUiThread(() -> {
                    if (avatar.getTag() == request) avatar.setImage(photo);
                });
            }
        });
    }

    @NonNull
    @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(viewType, parent, false);
        RecyclerView.ViewHolder holder = new RecyclerView.ViewHolder(view) {};
        if (viewType == R.layout.friends_bar_add_friend || viewType == R.layout.friends_bar_requests) {
            ImageView image = view.findViewById(R.id.image);
            image.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline outline) {
                    outline.setOval(0, 0, v.getWidth(), v.getHeight());
                }
            });
            image.setClipToOutline(true);
            view.setOnClickListener(v -> {
                if (viewType == R.layout.friends_bar_add_friend) listener.onAddFriendAction();
                else listener.onRequestsAction();
            });
        } else if (viewType == R.layout.friends_bar_folder || viewType == R.layout.friends_bar_friend) {
            view.setOnClickListener(v -> {
                FriendBarArrangement.Item item = itemAt(holder.getBindingAdapterPosition());
                if (item == null) return;
                if (item.folder == null) listener.onFriendSelected(item.friend());
                else listener.openFolder(view, item);
            });
            listener.addMoveActions((FrameLayout) view, holder);
        }
        return holder;
    }
}
