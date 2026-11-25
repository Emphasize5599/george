package xyz.zood.george;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import io.pijun.george.api.OscarClient;
import io.pijun.george.database.Snapshot;

import static org.junit.Assert.*;

/**
 * The iPhone and Android apps share one end-to-end encrypted backup per account, so signing out of one and
 * into the other must bring everything across. These check the shared JSON both ways:
 * - `ios-snapshot.json` was written by the iPhone app's real encoder (Tools/NicknameCompatibilityCheck.swift
 *   in the iOS repo); every field must read correctly here.
 * - Android writes its own full backup to build/cross-platform/android-snapshot.json, which the iOS check
 *   reads back the same way.
 */
public class CrossPlatformBackupTest {

    @Test
    public void readsEveryFieldOfAnIphoneBackup() throws Exception {
        String json;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("ios-snapshot.json")) {
            assertNotNull("ios-snapshot.json fixture missing", in);
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Snapshot s = Snapshot.fromJson(json.getBytes(StandardCharsets.UTF_8));
        assertNotNull(s);
        byte[] friendId = filled(2, 16);

        assertEquals(1, s.schemaVersion);
        assertEquals("Ben", s.nickname);
        assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, s.avatar);

        assertEquals("alexdemo", s.users.get(0).username);
        assertArrayEquals(friendId, s.users.get(0).userId);
        assertArrayEquals(filled(3, 32), s.users.get(0).publicKey);

        Snapshot.Friend f = s.friends.get(0);
        assertArrayEquals(friendId, f.userId);
        assertArrayEquals(filled(4, 32), f.sendingBoxId);
        assertArrayEquals(filled(5, 32), f.receivingBoxId);
        assertEquals("Dad", f.nickname);
        assertEquals("Alex", f.sharedNickname);
        assertEquals("Alex Rivera", f.linkedContactName);
        assertEquals("ABC-123", f.linkedContactId);
        assertEquals("ios", f.linkedContactPlatform);

        assertEquals("blockeddemo", s.blocked.get(0).username);
        assertEquals("Family", s.folders.get(0).name);
        assertArrayEquals(friendId, s.folders.get(0).members.get(0));
        assertEquals(Collections.singletonList("folder:11111111-2222-3333-4444-555555555555"), s.barOrder);
        assertEquals("ios-device", s.activeDevice.id);
        assertEquals("ios", s.activeDevice.platform);
        assertEquals(1_700_000_000_000L, s.activeDevice.since);
        assertNotNull(s.invites);
        assertArrayEquals(filled(4, 16), s.invites.get(0).token);
        assertNull(s.invites.get(0).usedBy);
    }

    @Test
    public void writesAFullBackupForTheIphoneCheck() throws Exception {
        Snapshot s = new Snapshot();
        s.schemaVersion = 1;
        s.timestamp = 1_800_000_000_000L;
        s.nickname = "Ana";
        s.avatar = new byte[]{(byte) 0x89, 0x50};
        byte[] friendId = filled(7, 16);

        Snapshot.User u = new Snapshot.User();
        u.userId = friendId;
        u.publicKey = filled(1, 32);
        u.username = "momdemo";
        s.users.add(u);

        Snapshot.Friend f = new Snapshot.Friend();
        f.userId = friendId;
        f.sendingBoxId = filled(6, 32);
        f.receivingBoxId = filled(5, 32);
        f.nickname = "Mom";
        f.sharedNickname = "Maria";
        f.linkedContactName = "Maria Lopez";
        f.linkedContactId = "content://com.android.contacts/contacts/lookup/abc/1";
        f.linkedContactPlatform = "android";
        s.friends.add(f);

        Snapshot.User blocked = new Snapshot.User();
        blocked.userId = filled(9, 16);
        blocked.publicKey = filled(8, 32);
        blocked.username = "blockedandroid";
        s.blocked = new ArrayList<>(Collections.singletonList(blocked));

        Snapshot.Folder folder = new Snapshot.Folder();
        folder.id = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE";
        folder.name = "Kids";
        folder.members = new ArrayList<>(Collections.singletonList(friendId));
        s.folders = new ArrayList<>(Collections.singletonList(folder));
        s.barOrder = new ArrayList<>(Arrays.asList("folder:" + folder.id, "friend:" + java.util.Base64.getEncoder().encodeToString(friendId)));

        s.activeDevice = new Snapshot.ActiveDevice();
        s.activeDevice.id = "android-device";
        s.activeDevice.platform = "android";
        s.activeDevice.since = 1_800_000_000_000L;

        Snapshot.Invite invite = new Snapshot.Invite();
        invite.token = filled(9, 16);
        invite.usedBy = filled(2, 16);
        s.invites = new ArrayList<>(Collections.singletonList(invite));

        byte[] json = s.toJson();
        String text = new String(json, StandardCharsets.UTF_8);
        for (String key : new String[]{"\"linked_contact_name\"", "\"bar_order\"", "\"active_device\"", "\"blocked\"", "\"folders\"", "\"shared_nickname\"", "\"invites\"", "\"used_by\""}) {
            assertTrue("missing " + key, text.contains(key));
        }

        // Round trip on Android too.
        Snapshot back = Snapshot.fromJson(json);
        assertNotNull(back);
        assertEquals("Mom", back.friends.get(0).nickname);
        assertEquals(1_800_000_000_000L, back.activeDevice.since);

        File out = new File("build/cross-platform/android-snapshot.json");
        //noinspection ResultOfMethodCallIgnored
        out.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(json);
        }
    }

    private static byte[] filled(int value, int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
