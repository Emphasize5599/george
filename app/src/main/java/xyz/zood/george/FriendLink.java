package xyz.zood.george;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


import java.util.List;
import java.util.Locale;


/**
 * Friend links: a shareable link to someone's Amidst profile. Same format as the iPhone app.
 *
 * The link people share is a web address, because a web link is tappable in every messaging app and still works for
 * someone who doesn't have Amidst yet: the landing page (GitHub Pages, repository Emphasize5599/amidst) shows install
 * links and an "Open in Amidst" button that uses the app's own amidst:// scheme.
 *
 * A one-time link also carries {@code t}, a random token the inviter stored. Accepting it connects the two people
 * immediately. A link with only the public username still asks the inviter to approve a friend request.
 * Nothing is sent until the person who opens the link confirms in Add Friend.
 */
public final class FriendLink {

    private FriendLink() {}

    /** The landing page for invites that don't name anyone (what Amidst is, with download links). */
    public static final String HOME_URL = "https://emphasize5599.github.io/amidst/";
    private static final String WEB_HOST = "emphasize5599.github.io";
    private static final String WEB_PATH = "/amidst/add";
    private static final String SCHEME = "amidst";

    /** A friend link waiting for the map screen, including its one-time token when the link had a valid one. */
    public static final class Pending {
        public final String username;
        @Nullable public final byte[] inviteToken;

        Pending(@NonNull String username, @Nullable byte[] inviteToken) {
            this.username = username;
            this.inviteToken = inviteToken;
        }
    }

    /**
     * A link the app was opened with, waiting for the map screen. Kept outside any screen so a link opened while
     * signed out is still handled right after sign-in.
     */
    @Nullable private static Pending pending;

    /** The reusable link that opens Add Friend for {@code username}. */
    @NonNull
    public static String url(@NonNull String username) {
        return url(username, null);
    }

    /** A one-time link when {@code inviteToken} is 16 bytes; otherwise the reusable username link. */
    @NonNull
    public static String url(@NonNull String username, @Nullable byte[] inviteToken) {
        String link = HOME_URL + "add/?u=" + Uri.encode(username.toLowerCase(Locale.ROOT));
        String token = InviteLedger.encodeToken(inviteToken);
        return token == null ? link : link + "#t=" + token;
    }

    /**
     * The username a link points to, or null if it isn't a valid friend link. Accepts the web link and the app's own
     * forms (amidst://add/alex and amidst://add?u=alex), and only returns names the server could accept.
     */
    @Nullable
    static String usernameFrom(@Nullable Uri uri) {
        if (uri == null) return null;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String candidate = null;
        if (scheme.equals(SCHEME) && host.equals("add")) {
            List<String> segments = uri.getPathSegments();
            candidate = segments.isEmpty() ? uri.getQueryParameter("u") : segments.get(0);
        } else if (scheme.equals("https") && host.equals(WEB_HOST) && uri.getPath() != null
                && uri.getPath().startsWith(WEB_PATH)) {
            candidate = uri.getQueryParameter("u");
        }
        if (candidate == null) return null;
        String username = candidate.trim();
        if (username.startsWith("@")) username = username.substring(1);
        username = username.toLowerCase(Locale.ROOT);
        return username.matches("[a-z0-9]{5,32}") ? username : null;
    }

    /** The one-time token on a link, or null when it is missing or not 16 bytes (the link stays a normal request). */
    @Nullable
    static byte[] tokenFrom(@Nullable Uri uri) {
        if (uri == null) return null;
        String fragment = uri.getFragment();
        String rawToken = fragment != null && fragment.startsWith("t=")
                ? fragment.substring(2) : uri.getQueryParameter("t");
        return InviteLedger.decodeToken(rawToken);
    }

    /** Remembers the friend link in {@code intent}, if it has one. */
    static void receive(@Nullable Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        String username = usernameFrom(intent.getData());
        if (username != null) pending = new Pending(username, tokenFrom(intent.getData()));
    }

    /** The waiting friend link (once), or null. */
    @Nullable
    static Pending takePending() {
        Pending link = pending;
        pending = null;
        return link;
    }

}
