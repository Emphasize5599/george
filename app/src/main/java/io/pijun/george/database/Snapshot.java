package io.pijun.george.database;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;

import io.pijun.george.CloudLogger;
import io.pijun.george.Constants;
import io.pijun.george.L;
import io.pijun.george.api.OscarClient;

public class Snapshot {

    public static class Friend {
        public byte[] userId;
        public byte[] sendingBoxId;
        public byte[] receivingBoxId;
        public Boolean establishedWithoutLocation;
        public String nickname;
        public String sharedNickname;
        @Nullable public byte[] avatarThumbnail;
        // Family Locator: the phone contact this friend is linked to (stock Zood ignores these keys).
        // The name restores anywhere; the id is an Android lookup URI or an iOS Contacts identifier,
        // so it's only reused on the platform named in linkedContactPlatform.
        public String linkedContactName;
        public String linkedContactId;
        public String linkedContactPlatform;
    }

    public static class User {
        public byte[] userId;
        public byte[] publicKey;
        public String username;
    }

    @NonNull
    public final ArrayList<Friend> friends = new ArrayList<>();
    @NonNull
    public final ArrayList<User> users = new ArrayList<>();
    @Nullable
    public byte[] avatar;

    // Family Locator: accounts blocked by the user.
    @Nullable
    public ArrayList<User> blocked;

    /** One-time invite links this account has issued. {@code usedBy} is set once someone redeems one. */
    public static class Invite {
        public byte[] token;
        @Nullable public byte[] usedBy;
    }

    @Nullable
    public ArrayList<Invite> invites;

    // Family Locator: friend bar folders and order.
    public static class Folder {
        public String id;
        public String name;
        public ArrayList<byte[]> members;
    }

    @Nullable
    public ArrayList<Folder> folders;

    // Family Locator: the device the account was most recently signed in on (see DeviceSession).
    public static class ActiveDevice {
        public String id;
        public String platform;
        public long since;
    }

    @Nullable
    public ActiveDevice activeDevice;
    @Nullable
    public ArrayList<String> barOrder;

    public String nickname;

    public int schemaVersion;
    public long timestamp;

    public byte[] toJson() {
        String s = OscarClient.sGson.toJson(this);
        return s.getBytes(Constants.utf8);
    }

    @Nullable
    public static Snapshot fromJson(@NonNull byte[] bytes) {
        InputStreamReader isr = new InputStreamReader(new ByteArrayInputStream(bytes));
        Snapshot snapshot;
        try {
            snapshot = OscarClient.sGson.fromJson(isr, Snapshot.class);
        } catch (Throwable t) {
            L.w("Snapshot.fromJson", t);
            return null;
        }
        return snapshot;
    }

}
