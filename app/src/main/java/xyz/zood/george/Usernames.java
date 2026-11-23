package xyz.zood.george;

import java.util.Locale;

/** The display prefix is not part of the server's account identifier. */
public final class Usernames {

    private Usernames() {}

    public static String display(String username) {
        return "@" + username;
    }

    static boolean isOwn(String username, String accountUsername) {
        return accountUsername != null && forLookup(username).equals(forLookup(accountUsername));
    }

    public static String forLookup(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
