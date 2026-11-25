package io.pijun.george;

import android.os.SystemClock;
import android.text.format.DateUtils;
import android.util.LongSparseArray;

import java.util.HashSet;

import androidx.annotation.AnyThread;
import androidx.annotation.CheckResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import io.pijun.george.api.UserComm;

public class UpdateStatusTracker {

    public enum State {
        NotRequested,
        Requested,
        RequestedAndUnresponsive,
        RequestAcknowledged,
        RequestDenied,
        RequestFulfilled,
    }

    private static final long REQUEST_TIMEOUT = 30 * DateUtils.SECOND_IN_MILLIS;
    private static final long STATUS_DURATION = DateUtils.MINUTE_IN_MILLIS;

    static class Response {
        final long responseTime;
        @UserComm.LocationUpdateRequestAction
        final String action;

        Response(long responseTime, @UserComm.LocationUpdateRequestAction String action) {
            this.responseTime = responseTime;
            this.action = action;
        }
    }

    private static final HashSet<Listener> listeners = new HashSet<>();
    private static final LongSparseArray<Long> requestTimes = new LongSparseArray<>();
    private static final LongSparseArray<Response> responses = new LongSparseArray<>();

    public static void addListener(@NonNull Listener l) {
        synchronized (UpdateStatusTracker.class) {
            listeners.add(l);
        }
    }

    @CheckResult
    @AnyThread
    public static synchronized State getFriendState(long friendId) {
        long now = SystemClock.elapsedRealtime();
        Long reqTime = requestTimes.get(friendId);
        if (reqTime == null) return State.NotRequested;

        long elapsed = now - reqTime;
        if (elapsed >= STATUS_DURATION) {
            requestTimes.remove(friendId);
            responses.remove(friendId);
            return State.NotRequested;
        }

        Response resp = responses.get(friendId);
        if (resp != null) {
            if (UserComm.LOCATION_UPDATE_REQUEST_ACTION_FINISHED.equals(resp.action)) return State.RequestFulfilled;
            if (UserComm.LOCATION_UPDATE_REQUEST_ACTION_TOO_SOON.equals(resp.action)) return State.RequestDenied;
        }
        if (elapsed >= REQUEST_TIMEOUT) return State.RequestedAndUnresponsive;
        return resp == null ? State.Requested : State.RequestAcknowledged;
    }

    @AnyThread
    public static boolean isPending(long friendId) {
        State state = getFriendState(friendId);
        return state == State.Requested || state == State.RequestAcknowledged;
    }

    /**
     * When the friend answered the current request, or null if they haven't.
     * The friend card uses it to show a brief "Updated" check before returning to the regular time.
     */
    @Nullable
    @AnyThread
    public static synchronized Long getResponseTime(long friendId) {
        Response resp = responses.get(friendId);
        return resp == null ? null : resp.responseTime;
    }

    @AnyThread
    private static void notifyListeners(long friendId) {
        // we don't need to synchronize here, because we're only called from already
        // synchronized methods
        for (Listener l : listeners) {
            App.runOnUiThread(new UiRunnable() {
                @Override
                public void run() {
                    l.onUpdateStatusChanged(friendId);
                }
            });
        }
    }

    @AnyThread
    public static void removeListener(@NonNull Listener l) {
        synchronized (UpdateStatusTracker.class) {
            listeners.remove(l);
        }
    }

    public static synchronized boolean startRequest(long friendId) {
        if (isPending(friendId)) return false;

        long reqTime = SystemClock.elapsedRealtime();
        requestTimes.put(friendId, reqTime);
        responses.remove(friendId);
        notifyListeners(friendId);
        scheduleStatusUpdate(friendId, reqTime, REQUEST_TIMEOUT);
        scheduleStatusUpdate(friendId, reqTime, STATUS_DURATION);
        return true;
    }

    private static void scheduleStatusUpdate(long friendId, long reqTime, long delay) {
        App.runOnUiThread(() -> {
            synchronized (UpdateStatusTracker.class) {
                Long current = requestTimes.get(friendId);
                if (current != null && current == reqTime) notifyListeners(friendId);
            }
        }, delay);
    }

    @AnyThread
    static synchronized void setUpdateRequestResponse(long friendId, String action) {
        if (!isPending(friendId)) return;
        if (UserComm.LOCATION_UPDATE_REQUEST_ACTION_STARTING.equals(action)
                || UserComm.LOCATION_UPDATE_REQUEST_ACTION_TOO_SOON.equals(action)) {
            recordResponse(friendId, action);
        }
    }

    private static void recordResponse(long friendId, String action) {
        responses.put(friendId, new Response(System.currentTimeMillis(), action));
        notifyListeners(friendId);
    }

    /** A newer location satisfies a pending refresh, regardless of what caused the phone to send it. */
    @AnyThread
    static synchronized void onLocationReceived(long friendId, long locationTime, Long previousTime) {
        if ((previousTime == null || locationTime > previousTime) && isPending(friendId)) {
            recordResponse(friendId, UserComm.LOCATION_UPDATE_REQUEST_ACTION_FINISHED);
        }
    }

    public interface Listener {
        @UiThread
        void onUpdateStatusChanged(long friendId);
    }

}
