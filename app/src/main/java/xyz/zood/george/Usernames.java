package xyz.zood.george;

import java.util.Locale;

/** The display prefix is not part of the server's account identifier. */
public final class Usernames {

    private Usernames() {}

    public static String forLookup(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
