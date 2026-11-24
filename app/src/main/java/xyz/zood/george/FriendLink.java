package xyz.zood.george;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.Locale;

import io.pijun.george.Prefs;

/**
 * Friend links: a shareable link to someone's Amidst profile that lets the recipient send them a friend request.
 * Same format as the iPhone app.
 *
 * The link people share is a web address, because a web link is tappable in every messaging app and still works for
 * someone who doesn't have Amidst yet: the landing page (GitHub Pages, repository Emphasize5599/amidst) shows install
 * links and an "Open in Amidst" button that uses the app's own amidst:// scheme.
 *
 * A link carries only the public username. Nothing is sent or shared until the person who opens it confirms in
 * Add Friend, so a link can never add someone or share a location by itself.
 */
public final class FriendLink {

    private FriendLink() {}

    /** The landing page for invites that don't name anyone (what Amidst is, with download links). */
    public static final String HOME_URL = "https://emphasize5599.github.io/amidst/";
    private static final String WEB_HOST = "emphasize5599.github.io";
    private static final String WEB_PATH = "/amidst/add";
    private static final String SCHEME = "amidst";

    /**
     * A username from a link the app was opened with, waiting for the map screen. Kept outside any screen so a link
     * opened while signed out is still handled right after sign-in.
     */
    @Nullable private static String pending;

    /** The link that opens Add Friend for {@code username}. */
    @NonNull
    public static String url(@NonNull String username) {
        return HOME_URL + "add/?u=" + Uri.encode(username.toLowerCase(Locale.ROOT));
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

    /** Remembers the friend link in {@code intent}, if it has one. */
    static void receive(@Nullable Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        String username = usernameFrom(intent.getData());
        if (username != null) pending = username;
    }

    /** The waiting friend link's username (once), or null. */
    @Nullable
    static String takePending() {
        String username = pending;
        pending = null;
        return username;
    }

    /**
     * Asks whether the invite should include the user's own friend link. Some people want to hand out a link straight
     * to their profile; others would rather not share their username with everyone who sees the message (for example
     * in a group chat), so it's their choice.
     */
    @UiThread
    public static void shareInvite(@NonNull Context context) {
        String username = Prefs.get(context).getUsername();
        String[] choices = {context.getString(R.string.invite_with_link), context.getString(R.string.invite_without_link)};
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.share_an_invite)
                .setItems(choices, (dialog, which) -> {
                    String text = which == 0 && username != null
                            ? context.getString(R.string.invite_friend_link_msg, url(username))
                            : context.getString(R.string.invite_friend_msg);
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.putExtra(Intent.EXTRA_TEXT, text);
                    send.setType("text/plain");
                    context.startActivity(Intent.createChooser(send, context.getString(R.string.send_to)));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
