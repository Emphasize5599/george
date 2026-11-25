package xyz.zood.george;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * One-time invite tokens. The server never sees them in the clear: each client stores the tokens it issued,
 * and a link works only when the inviter still has that exact token unused.
 */
public final class InviteLedger {

    public static final int TOKEN_LENGTH = 16;
    /** Keep recent redemptions as well as unused links when trimming the backup. */
    public static final int CAP = 40;

    private InviteLedger() {}

    public static final class Record {
        public byte[] token;
        @Nullable public byte[] usedBy;
    }

    public static List<Record> issued(byte[] token, List<Record> records) {
        List<Record> next = new ArrayList<>(records);
        Record record = new Record();
        record.token = token.clone();
        next.add(record);
        return trim(next);
    }

    /** Consumes an unused token for the sender. Replays and unrecognized tokens are rejected. */
    public static boolean authorize(byte[] token, byte[] sender, List<Record> records) {
        if (token == null || token.length != TOKEN_LENGTH || sender == null || sender.length != TOKEN_LENGTH) return false;
        for (Record record : records) {
            if (!Arrays.equals(record.token, token)) continue;
            if (record.usedBy == null) {
                record.usedBy = sender.clone();
                return true;
            }
            return false;
        }
        return false;
    }

    /** A token is used if either side says so. Local wins when the two sides name different people. */
    public static List<Record> merge(List<Record> local, List<Record> remote) {
        List<Record> ordered = new ArrayList<>();
        addAll(ordered, local);
        addAll(ordered, remote);
        return trim(ordered);
    }

    public static List<Record> trim(List<Record> records) {
        if (records.size() <= CAP) return records;
        List<Integer> unused = new ArrayList<>();
        List<Integer> used = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).usedBy == null) unused.add(i);
            else used.add(i);
        }
        int usedTarget = Math.min(used.size(), CAP / 2);
        int unusedTarget = Math.min(unused.size(), CAP - usedTarget);
        int usedFinal = Math.min(used.size(), CAP - unusedTarget);
        boolean[] keep = new boolean[records.size()];
        markTail(keep, unused, unusedTarget);
        markTail(keep, used, usedFinal);
        List<Record> trimmed = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) if (keep[i]) trimmed.add(records.get(i));
        return trimmed;
    }

    /** Base64url without padding, the form that goes in a link's {@code t} parameter. Null when {@code token} is not 16 bytes. */
    @Nullable
    public static String encodeToken(byte[] token) {
        if (token == null || token.length != TOKEN_LENGTH) return null;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    /** Decodes a link token. Invalid or wrong-length values are null so the link falls back to a normal request. */
    @Nullable
    public static byte[] decodeToken(@Nullable String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        byte[] decoded = decode(Base64.getUrlDecoder(), trimmed);
        if (decoded == null) decoded = decode(Base64.getDecoder(), trimmed);
        return decoded != null && decoded.length == TOKEN_LENGTH ? decoded : null;
    }

    private static void addAll(List<Record> ordered, List<Record> incoming) {
        if (incoming == null) return;
        for (Record record : incoming) {
            if (record == null || record.token == null || record.token.length != TOKEN_LENGTH) continue;
            byte[] usedBy = record.usedBy != null && record.usedBy.length == TOKEN_LENGTH ? record.usedBy : null;
            int index = indexOf(ordered, record.token);
            if (index < 0) {
                Record copy = new Record();
                copy.token = record.token.clone();
                copy.usedBy = usedBy == null ? null : usedBy.clone();
                ordered.add(copy);
            } else if (ordered.get(index).usedBy == null && usedBy != null) {
                ordered.get(index).usedBy = usedBy.clone();
            }
        }
    }

    private static int indexOf(List<Record> records, byte[] token) {
        for (int i = 0; i < records.size(); i++) {
            if (Arrays.equals(records.get(i).token, token)) return i;
        }
        return -1;
    }

    private static void markTail(boolean[] keep, List<Integer> indexes, int count) {
        for (int i = Math.max(0, indexes.size() - count); i < indexes.size(); i++) keep[indexes.get(i)] = true;
    }

    @Nullable
    private static byte[] decode(Base64.Decoder decoder, String value) {
        try {
            return decoder.decode(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
