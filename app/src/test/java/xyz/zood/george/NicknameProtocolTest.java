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
