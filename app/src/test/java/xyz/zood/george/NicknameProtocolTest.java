package xyz.zood.george;

import org.junit.Test;

import io.pijun.george.api.OscarClient;
import io.pijun.george.api.UserComm;
import io.pijun.george.database.Snapshot;

import static org.junit.Assert.*;

public class NicknameProtocolTest {

    @Test
    public void returnGrantMatchesOnlyTheOriginalOffer() {
        byte[] offer = new byte[16];
        byte[] recipientBox = offer.clone();
        recipientBox[0] = 1;
        UserComm grant = UserComm.newLocationSharingGrant(recipientBox, offer);
        String json = OscarClient.sGson.toJson(grant);
        assertTrue(json.contains("\"reply_to_box\""));
        UserComm restored = OscarClient.sGson.fromJson(json, UserComm.class);
        assertTrue(restored.repliesToSharingOffer(offer));
        assertFalse(restored.repliesToSharingOffer(null));
        assertFalse(restored.repliesToSharingOffer(recipientBox));
        assertFalse(UserComm.newLocationSharingGrant(recipientBox).repliesToSharingOffer(offer));
    }

    @Test
    public void distinguishesFriendRemovalFromOrdinaryRevocation() {
        UserComm ordinary = OscarClient.sGson.fromJson(OscarClient.sGson.toJson(UserComm.newLocationSharingRevocation()), UserComm.class);
        UserComm removed = OscarClient.sGson.fromJson(OscarClient.sGson.toJson(UserComm.newFriendRemoval()), UserComm.class);
        assertTrue(ordinary.isValid());
        assertTrue(removed.isValid());
        assertSame(ordinary.type, removed.type);
        assertNull(ordinary.friendRemoved);
        assertEquals(Boolean.TRUE, removed.friendRemoved);
        assertTrue(OscarClient.sGson.toJson(removed).contains("\"friend_removed\""));
    }

    @Test
    public void distinguishesLegacyProfilesFromExplicitResets() {
        UserComm legacy = OscarClient.sGson.fromJson("{\"type\":\"avatar_request\"}", UserComm.class);
        UserComm reset = OscarClient.sGson.fromJson("{\"type\":\"nickname_update\",\"nickname\":\"\"}", UserComm.class);
        UserComm malformed = OscarClient.sGson.fromJson("{\"type\":\"nickname_update\"}", UserComm.class);
        assertTrue(legacy.isValid());
        assertNull(legacy.nickname);
        assertTrue(reset.isValid());
        assertEquals("", reset.nickname);
        assertFalse(malformed.isValid());
    }

    @Test
    public void inviteTokenIsOptionalAndDoesNotChangeAFriendRequest() {
        byte[] token = new byte[16];
        java.util.Arrays.fill(token, (byte) 7);
        UserComm request = UserComm.newFriendRequest(null, token);
        String json = OscarClient.sGson.toJson(request);
        assertTrue(json.contains("\"invite_token\""));
        assertFalse(json.contains("drop_box"));
        UserComm back = OscarClient.sGson.fromJson(json, UserComm.class);
        assertArrayEquals(token, back.inviteToken);
        assertTrue(back.isValid());
        assertFalse(OscarClient.sGson.toJson(UserComm.newFriendRequest(null)).contains("invite_token"));

        UserComm grant = UserComm.newLocationSharingGrant(token, null, token);
        UserComm grantBack = OscarClient.sGson.fromJson(OscarClient.sGson.toJson(grant), UserComm.class);
        assertArrayEquals(token, grantBack.inviteToken);
        assertArrayEquals(token, grantBack.dropBox);
        assertTrue(grantBack.isValid());
    }

    @Test
    public void snapshotInvitesStayOnSchemaVersionOne() {
        Snapshot snapshot = new Snapshot();
        snapshot.schemaVersion = 1;
        Snapshot.Invite invite = new Snapshot.Invite();
        invite.token = new byte[16];
        java.util.Arrays.fill(invite.token, (byte) 4);
        snapshot.invites = new java.util.ArrayList<>(java.util.Collections.singletonList(invite));
        String json = new String(snapshot.toJson(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("\"invites\""));
        assertFalse(json.contains("used_by"));
        Snapshot back = Snapshot.fromJson(snapshot.toJson());
        assertNotNull(back);
        assertEquals(1, back.schemaVersion);
        assertArrayEquals(invite.token, back.invites.get(0).token);
        assertNull(back.invites.get(0).usedBy);
        Snapshot legacy = Snapshot.fromJson("{\"schema_version\":1,\"friends\":[],\"users\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertNotNull(legacy);
        assertNull(legacy.invites);
    }

    @Test
    public void restoresIosNicknameFieldsWithoutChangingSchemaVersion() {
        String json = "{\"schema_version\":1,\"timestamp\":0,\"nickname\":\"My name\",\"friends\":[{\"nickname\":\"Dad\",\"shared_nickname\":\"Alexander\"}],\"users\":[]}";
        Snapshot snapshot = OscarClient.sGson.fromJson(json, Snapshot.class);
        assertEquals(1, snapshot.schemaVersion);
        assertEquals("My name", snapshot.nickname);
        assertEquals("Dad", snapshot.friends.get(0).nickname);
        assertEquals("Alexander", snapshot.friends.get(0).sharedNickname);
        assertTrue(OscarClient.sGson.toJson(snapshot).contains("shared_nickname"));
        Snapshot legacy = OscarClient.sGson.fromJson("{\"schema_version\":1,\"friends\":[],\"users\":[]}", Snapshot.class);
        assertNull(legacy.nickname);
    }
}
