package io.pijun.george.database;

import android.text.format.DateUtils;

import androidx.annotation.NonNull;

import java.util.Arrays;

/**
 * Debug-only, fictional friend data for the layout tour. Nothing here touches the database,
 * the server or real contacts. Lives in this package because FriendLocation's constructor is
 * package-private in production code.
 */
public final class TourFixtures {

    private TourFixtures() {}

    @NonNull
    public static FriendRecord friend(@NonNull String username) {
        UserRecord user = new UserRecord();
        user.id = 1;
        user.userId = new byte[16];
        Arrays.fill(user.userId, (byte) 7);
        user.username = username;
        user.publicKey = new byte[32];

        FriendRecord friend = new FriendRecord();
        friend.id = 1;
        friend.userId = 1;
        friend.user = user;
        friend.sendingBoxId = new byte[32];
        friend.receivingBoxId = new byte[32];
        return friend;
    }

    /** A walking friend on the Golden Gate Bridge, last seen 25 minutes ago. */
    @NonNull
    public static FriendLocation location() {
        long time = System.currentTimeMillis() - 25 * DateUtils.MINUTE_IN_MILLIS;
        return new FriendLocation(1, 37.8199, -122.4783, time, 12f, 1.2f, 30f,
                MovementType.Walking, 82, false);
    }
}
