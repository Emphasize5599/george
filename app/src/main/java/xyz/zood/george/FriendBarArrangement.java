package xyz.zood.george;

import android.util.Base64;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;

import io.pijun.george.database.FriendRecord;
import io.pijun.george.database.Snapshot;

final class FriendBarArrangement {
    private final ArrayList<Snapshot.Folder> folders;
    private final ArrayList<String> order;

    FriendBarArrangement() {
        synchronized (LinkedContacts.class) {
            var savedFolders = LinkedContacts.folders();
            var savedOrder = LinkedContacts.barOrder();
            folders = savedFolders == null ? new ArrayList<>() : savedFolders;
            order = savedOrder == null ? new ArrayList<>() : savedOrder;
        }
    }

    static String friendKey(byte[] id) {
        return "friend:" + Base64.encodeToString(id, Base64.NO_WRAP);
    }

    static String folderKey(Snapshot.Folder folder) {
        return "folder:" + folder.id.toUpperCase(Locale.ROOT);
    }

    static final class Item {
        final String key;
        final Snapshot.Folder folder;
        final ArrayList<FriendRecord> members;

        Item(FriendRecord friend) {
            key = friendKey(friend.user.userId);
            folder = null;
            members = new ArrayList<>();
            members.add(friend);
        }

        Item(Snapshot.Folder folder, ArrayList<FriendRecord> members) {
            this.key = folderKey(folder);
            this.folder = folder;
            this.members = members;
        }

        FriendRecord friend() {
            return folder == null ? members.get(0) : null;
        }
    }

    ArrayList<Item> items(ArrayList<FriendRecord> friends) {
        var sorted = new ArrayList<>(friends);
        sorted.sort(Comparator.comparing(friend -> Nicknames.displayName(friend.user), String.CASE_INSENSITIVE_ORDER));
        var ungrouped = new LinkedHashMap<String, FriendRecord>();
        for (FriendRecord friend : sorted) ungrouped.put(friendKey(friend.user.userId), friend);
        var available = new LinkedHashMap<String, Item>();
        for (Snapshot.Folder folder : folders) {
            if (folder.id == null || folder.members == null) continue;
            var members = new LinkedHashMap<String, FriendRecord>();
            for (byte[] id : folder.members) {
                if (id == null) continue;
                String key = friendKey(id);
                FriendRecord member = ungrouped.get(key);
                if (member != null) members.put(key, member);
            }
            if (members.size() < 2) continue;
            var item = new Item(folder, new ArrayList<>(members.values()));
            if (available.putIfAbsent(item.key, item) == null) {
                for (String key : members.keySet()) ungrouped.remove(key);
            }
        }
        for (FriendRecord friend : ungrouped.values()) {
            Item item = new Item(friend);
            available.put(item.key, item);
        }
        var result = new ArrayList<Item>();
        for (String key : order) {
            if (key == null) continue;
            String canonical = key.startsWith("folder:") ? "folder:" + key.substring(7).toUpperCase(Locale.ROOT) : key;
            Item item = available.remove(canonical);
            if (item != null) result.add(item);
        }
        result.addAll(available.values());
        return result;
    }

    void arrange(ArrayList<Item> items) {
        order.clear();
        for (Item item : items) order.add(item.key);
    }

    void group(Item moving, Item target) {
        FriendRecord friend = moving.friend();
        if (friend == null || moving.key.equals(target.key)) return;
        byte[] id = friend.user.userId;
        removeMembership(id);
        if (target.folder != null) {
            target.folder.members.add(id);
            order.remove(moving.key);
            return;
        }
        byte[] targetId = target.friend().user.userId;
        removeMembership(targetId);
        var folder = new Snapshot.Folder();
        folder.id = UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
        folder.members = new ArrayList<>(Arrays.asList(targetId, id));
        folders.add(folder);
        int position = order.indexOf(target.key);
        if (position >= 0) order.set(position, folderKey(folder));
        order.remove(moving.key);
    }

    void ungroup(Item item) {
        int index = order.indexOf(item.key);
        if (index >= 0) {
            order.remove(index);
            for (FriendRecord friend : item.members) order.add(index++, friendKey(friend.user.userId));
        }
        folders.remove(item.folder);
    }

    void moveOut(Item item, FriendRecord friend) {
        int index = order.indexOf(item.key);
        if (index >= 0) {
            if (item.members.size() == 2) {
                FriendRecord remaining = item.members.get(item.members.get(0).equals(friend) ? 1 : 0);
                order.set(index, friendKey(remaining.user.userId));
            }
            order.add(index + 1, friendKey(friend.user.userId));
        }
        removeMembership(friend.user.userId);
    }

    private void removeMembership(byte[] id) {
        for (Snapshot.Folder folder : folders) {
            if (folder.members != null) folder.members.removeIf(member -> Arrays.equals(member, id));
        }
        folders.removeIf(folder -> folder.members == null || folder.members.size() < 2);
    }

    void save() {
        LinkedContacts.saveBar(folders, order);
    }
}
