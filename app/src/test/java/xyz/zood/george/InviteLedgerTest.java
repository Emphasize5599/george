package xyz.zood.george;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class InviteLedgerTest {

    @Test
    public void sharingConsentStaysWithTheLinkItWasIssuedFor() {
        List<InviteLedger.Record> records = InviteLedger.issued(filled(11), new ArrayList<>());
        records = InviteLedger.issued(filled(12), records, true);
        List<InviteLedger.Record> restored = InviteLedger.merge(new ArrayList<>(), records);
        assertNull(restored.get(0).shareOnRedemption);
        assertEquals(Boolean.TRUE, restored.get(1).shareOnRedemption);
    }

    @Test
    public void removingAFriendOnlyInvalidatesTheirAccessToExistingLinks() {
        byte[] oldToken = filled(11);
        byte[] newToken = filled(12);
        byte[] removed = filled(13);
        byte[] other = filled(14);
        List<InviteLedger.Record> oldBackup = InviteLedger.issued(oldToken, new ArrayList<>());
        List<InviteLedger.Record> revoked = InviteLedger.issued(oldToken, new ArrayList<>());
        assertTrue(InviteLedger.invalidate(removed, revoked));
        assertFalse(InviteLedger.invalidate(removed, revoked));
        List<InviteLedger.Record> restored = InviteLedger.merge(oldBackup, revoked);
        assertFalse(InviteLedger.authorize(oldToken, removed, restored));
        assertFalse(InviteLedger.authorize(oldToken, removed, InviteLedger.merge(revoked, oldBackup)));
        assertTrue(InviteLedger.authorize(oldToken, other, restored));
        assertTrue(InviteLedger.authorize(newToken, removed, InviteLedger.issued(newToken, restored)));
    }

    @Test
    public void unusedTokenRejectsReplayByAnySender() {
        byte[] token = filled(7);
        byte[] sender = filled(8);
        byte[] other = filled(9);
        List<InviteLedger.Record> records = InviteLedger.issued(token, new ArrayList<>());
        assertTrue(InviteLedger.authorize(token, sender, records));
        assertArrayEquals(sender, records.get(0).usedBy);
        assertFalse(InviteLedger.authorize(token, sender, records));
        assertFalse(InviteLedger.authorize(token, other, records));
        assertFalse(InviteLedger.authorize(filled(1), sender, records));
        assertEquals(1, records.size());
    }

    @Test
    public void mergeKeepsATokenUsedWhenEitherSideUsedIt() {
        byte[] token = filled(7);
        byte[] sender = filled(8);
        byte[] other = filled(9);
        List<InviteLedger.Record> merged = InviteLedger.merge(unused(token), used(token, sender));
        assertEquals(1, merged.size());
        assertArrayEquals(sender, merged.get(0).usedBy);

        List<InviteLedger.Record> conflict = InviteLedger.merge(used(token, sender), used(token, other));
        assertArrayEquals(sender, conflict.get(0).usedBy);

        byte[] extra = filled(3);
        List<InviteLedger.Record> union = InviteLedger.merge(unused(token), used(extra, sender));
        assertEquals(2, union.size());
        assertNull(union.get(0).usedBy);
        assertArrayEquals(sender, union.get(1).usedBy);
    }

    @Test
    public void trimKeepsRecentUnusedAndRecentRedemptions() {
        List<InviteLedger.Record> records = new ArrayList<>();
        for (int i = 0; i < 30; i++) records.add(record(filled(i), null));
        for (int i = 0; i < 30; i++) records.add(record(filled(100 + i), filled(8)));
        List<InviteLedger.Record> trimmed = InviteLedger.trim(records);
        assertEquals(InviteLedger.CAP, trimmed.size());
        int unused = 0;
        int used = 0;
        for (InviteLedger.Record record : trimmed) {
            if (record.usedBy == null) unused++;
            else used++;
        }
        assertEquals(20, unused);
        assertEquals(20, used);
        assertArrayEquals(filled(29), trimmed.get(19).token);
        assertArrayEquals(filled(129), trimmed.get(trimmed.size() - 1).token);
    }

    @Test
    public void linkTokenRoundTripsAndRejectsAnythingElse() {
        byte[] token = filled(7);
        String encoded = InviteLedger.encodeToken(token);
        assertNotNull(encoded);
        assertEquals(22, encoded.length());
        assertFalse(encoded.contains("+"));
        assertFalse(encoded.contains("/"));
        assertFalse(encoded.contains("="));
        assertArrayEquals(token, InviteLedger.decodeToken(encoded));
        assertArrayEquals(token, InviteLedger.decodeToken(java.util.Base64.getEncoder().encodeToString(token)));
        assertNull(InviteLedger.decodeToken("not-a-token"));
        assertNull(InviteLedger.decodeToken(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[15])));
        assertNull(InviteLedger.encodeToken(new byte[15]));
    }

    private static List<InviteLedger.Record> unused(byte[] token) {
        return new ArrayList<>(java.util.Collections.singletonList(record(token, null)));
    }

    private static List<InviteLedger.Record> used(byte[] token, byte[] sender) {
        return new ArrayList<>(java.util.Collections.singletonList(record(token, sender)));
    }

    private static InviteLedger.Record record(byte[] token, byte[] usedBy) {
        InviteLedger.Record record = new InviteLedger.Record();
        record.token = token;
        record.usedBy = usedBy;
        return record;
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[InviteLedger.TOKEN_LENGTH];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
