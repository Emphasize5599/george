package xyz.zood.george.widget;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.style.ImageSpan;
import android.text.style.UnderlineSpan;
import android.text.format.DateUtils;
import android.widget.PopupWindow;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.UiThread;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.SwitchCompat;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import io.pijun.george.App;
import io.pijun.george.L;
import io.pijun.george.UiRunnable;
import io.pijun.george.UpdateStatusTracker;
import io.pijun.george.database.FriendLocation;
import io.pijun.george.database.FriendRecord;
import xyz.zood.george.DeviceStatusProbe;
import xyz.zood.george.FriendDevices;
import xyz.zood.george.LinkedContacts;
import xyz.zood.george.R;
import xyz.zood.george.geocoding.RevGeocoding;
import xyz.zood.george.geocoding.ReverseGeocodingCache;

public class InfoPanel {

    private static final long REFRESH_INTERVAL = 15 * DateUtils.SECOND_IN_MILLIS;
    /** How long the green "Updated" check shows before fading into the regular update time. */
    private static final long UPDATED_CHECK_DURATION = 3 * DateUtils.SECOND_IN_MILLIS;

    private final Context context;
    private ImageView platformIcon;
    private final Runnable namesChanged = this::updateDisplayName;

    private void updateDisplayName() {
        if (currFriend != null) {
            username.setText(xyz.zood.george.Nicknames.displayName(currFriend.user));
        }
    }

    private void editNickname() {
        FriendRecord friend = getFriend();
        if (friend == null) {
            return;
        }
        com.google.android.material.textfield.TextInputLayout field = new com.google.android.material.textfield.TextInputLayout(context);
        field.setHint(context.getString(R.string.nickname_optional));
        int padding = Math.round(24 * context.getResources().getDisplayMetrics().density);
        field.setPadding(padding, padding / 2, padding, 0);
        field.setHelperText(context.getString(R.string.local_nickname_explanation));
        com.google.android.material.textfield.TextInputEditText input = new com.google.android.material.textfield.TextInputEditText(field.getContext());
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setText(xyz.zood.george.Nicknames.local(friend.user.userId));
        field.addView(input);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle(R.string.friend_nickname)
                .setView(field)
                .setPositiveButton(R.string.save_nickname, (dialog, which) -> {
                    xyz.zood.george.Nicknames.setLocal(friend.user.userId, input.getText() == null ? null : input.getText().toString());
                    updateDisplayName();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
    private final Listener listener;

    private FriendRecord currFriend;
    private FriendLocation currLoc;
    // Needs to be incremented in show() and hide() because both methods can cause a change of the
    // friend and its location object
    private long showId = 1;

    // Views
    @NonNull private final TextView address;
    @NonNull private final TextView battery;
    @NonNull private final ImageView batteryIcon;
    @NonNull private final ImageView bearing;
    @NonNull private final ImageView motion;
    @NonNull private final ImageButton overflowButton;
    @NonNull private final ConstraintLayout panel;
    @NonNull private final Button refreshButton;
    @NonNull private final ProgressBar refreshProgressBar;
    @NonNull private final SwitchCompat shareSwitch;
    @NonNull private final TextView shareSwitchLabel;
    @NonNull private final TextView username;
    // Family Locator: one status line under the address ("Requesting…", "Updated 5 min. ago",
    // "Unsupported (?)"), plus the anchored help opened by the info button.
    @NonNull private final View statusRow;
    @NonNull private final ImageView statusIcon;
    @NonNull private final TextView statusText;
    @NonNull private final View unsupportedInfo;
    @Nullable private PopupWindow refreshHelp;
    @Nullable private String refreshHelpBody;

    // Set by applyRequestCapability(): the friend's phone has said it can't answer Refresh requests,
    // and whether we're still re-checking that before saying so.
    private boolean requestsUnsupported;
    private boolean checkingSupport;
    // The last text shown in the status row, so a change can fade in instead of snapping.
    @Nullable private CharSequence lastStatus;

    @UiThread
    public InfoPanel(@NonNull ConstraintLayout root, @NonNull Context ctx, @NonNull Listener listener) {
        this.panel = root;
        this.listener = listener;
        this.context = ctx;
        panel.setClipToOutline(true);
        panel.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getContext().getResources().getDimension(R.dimen.eight));
            }
        });

        address = root.findViewById(R.id.address);
        if (address == null) {
            throw new IllegalArgumentException("'address' TextView is missing");
        }
        // The address is the "Open in Maps" link (it used to live in the ⋮ menu).
        address.setOnClickListener(v -> {
            FriendRecord friend = getFriend();
            if (friend != null && currLoc != null) {
                listener.onInfoPanelOpenInMaps(xyz.zood.george.Nicknames.displayName(friend.user), currLoc);
            }
        });

        battery = root.findViewById(R.id.battery);
        if (battery == null) {
            throw new IllegalArgumentException("'battery' TextView is missing");
        }
        battery.setCompoundDrawablePadding((int) ctx.getResources().getDimension(R.dimen.four));

        batteryIcon = root.findViewById(R.id.battery_icon);
        if (batteryIcon == null) {
            throw new IllegalArgumentException("'batteryIcon' is missing");
        }

        bearing = root.findViewById(R.id.bearing);
        if (bearing == null) {
            throw new IllegalArgumentException("'bearing' is missing");
        }

        motion = root.findViewById(R.id.motion);
        if (motion == null) {
            throw new IllegalArgumentException("'motion' is missing");
        }

        root.findViewById(R.id.info_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onInfoPanelCloseRequested();
            }
        });

        overflowButton = root.findViewById(R.id.info_overflow);
        if (overflowButton == null) {
            throw new IllegalArgumentException("'infoOverflow is missing");
        }
        overflowButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onInfoOverflowClicked();
            }
        });

        refreshButton = root.findViewById(R.id.refresh_button);
        if (refreshButton == null) {
            throw new IllegalArgumentException("'refreshButton is missing");
        }
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRefreshClicked();
            }
        });

        statusRow = root.findViewById(R.id.status_row);
        statusIcon = root.findViewById(R.id.status_icon);
        statusText = root.findViewById(R.id.status_text);
        unsupportedInfo = root.findViewById(R.id.unsupported_info);
        DeviceStatusProbe.addListener(deviceStatusListener);
        if (statusRow == null || statusIcon == null || statusText == null || unsupportedInfo == null) {
            throw new IllegalArgumentException("status views are missing");
        }
        unsupportedInfo.setOnClickListener(this::showRefreshHelp);
        panel.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            @Override public void onViewDetachedFromWindow(View view) { dismissRefreshHelp(); }
        });

        refreshProgressBar = root.findViewById(R.id.refresh_progress_bar);
        if (refreshProgressBar == null) {
            throw new IllegalArgumentException("'refreshProgressBar' is missing ");
        }

        shareSwitch = root.findViewById(R.id.share_switch);
        if (shareSwitch == null) {
            throw new IllegalArgumentException("'share_switch' SwitchCompat is missing");
        }

        shareSwitchLabel = root.findViewById(R.id.share_switch_label);
        if (shareSwitchLabel == null) {
            throw new IllegalArgumentException("'share_switch_label is missing");
        }

        platformIcon = root.findViewById(R.id.friend_platform);
        root.findViewById(R.id.edit_nickname).setOnClickListener(view -> editNickname());
        xyz.zood.george.Nicknames.observe(namesChanged);
        username = root.findViewById(R.id.username);
        if (username == null) {
            throw new IllegalArgumentException("'username' TextView is missing");
        }
    }

    @UiThread
    private void calculateAndUpdateRefreshButton(long currTime) {
        refreshButton.setVisibility(View.VISIBLE);
        if (applyRequestCapability()) {
            return;
        }
        // Zood's original rule: a location from the last 2 minutes is fresh enough, so Refresh rests.
        // The button is icon-only now; the status row already shows how recent the location is.
        boolean recent = (currTime - currLoc.time) < 2 * DateUtils.MINUTE_IN_MILLIS;
        refreshButton.setEnabled(!recent);
    }

    // region Status row

    /**
     * Picks the one line shown under the address. Request progress wins over the plain update time,
     * because it's what the person is waiting on right after tapping Refresh.
     */
    @UiThread
    private void updateStatusRow(long now) {
        FriendRecord friend = currFriend;
        if (friend == null || friend.receivingBoxId == null) {
            // Not sharing with us: the address line already says so.
            statusRow.setVisibility(View.GONE);
            lastStatus = null;
            return;
        }

        boolean showCheck = false;
        boolean showInfo = false;
        @ColorRes int color = R.color.text_tertiary;
        CharSequence text;

        UpdateStatusTracker.State state = UpdateStatusTracker.getFriendState(friend.id);
        Long answeredAt = UpdateStatusTracker.getResponseTime(friend.id);
        boolean justUpdated = state == UpdateStatusTracker.State.RequestFulfilled
                && answeredAt != null && now - answeredAt < UPDATED_CHECK_DURATION;

        if (requestsUnsupported) {
            // Keep the update time, with the note on its own line underneath.
            String note = context.getString(checkingSupport ? R.string.checking_ellipsis : R.string.on_demand_refresh_unsupported);
            text = currLoc != null ? updatedText(currLoc.time, now) + "\n" + note : note;
            showInfo = !checkingSupport;
            color = R.color.text_secondary;
        } else if (justUpdated) {
            text = context.getString(R.string.updated);
            showCheck = true;
            color = R.color.zood_green;
            // Come back when the check has had its moment and fade into the regular time.
            long id = showId;
            App.runOnUiThread(() -> {
                if (showId == id) {
                    updateStatusRow(System.currentTimeMillis());
                }
            }, UPDATED_CHECK_DURATION - (now - answeredAt) + 50);
        } else if (state == UpdateStatusTracker.State.Requested) {
            text = context.getString(R.string.requesting_ellipsis);
        } else if (state == UpdateStatusTracker.State.RequestAcknowledged) {
            text = context.getString(R.string.getting_their_location_ellipsis);
        } else if (state == UpdateStatusTracker.State.RequestedAndUnresponsive) {
            text = context.getString(R.string.no_response);
            color = R.color.zood_orange;
        } else if (state == UpdateStatusTracker.State.RequestDenied) {
            text = context.getString(R.string.updated_moments_ago);
        } else if (currLoc != null) {
            text = updatedText(currLoc.time, now);
        } else {
            // Waiting for their first location: the address line already says so.
            statusRow.setVisibility(View.GONE);
            lastStatus = null;
            return;
        }

        statusRow.setVisibility(View.VISIBLE);
        statusIcon.setVisibility(showCheck ? View.VISIBLE : View.GONE);
        unsupportedInfo.setVisibility(showInfo ? View.VISIBLE : View.GONE);
        statusText.setTextColor(ContextCompat.getColor(context, color));
        statusText.setText(text);
        if (lastStatus != null && !lastStatus.toString().contentEquals(text)) {
            statusRow.setAlpha(0f);
            statusRow.animate().alpha(1f).setDuration(250).start();
        }
        lastStatus = text;
    }

    /** "Updated 5 min. ago" today; an older update gets a date so it can't be mistaken for a recent one. */
    @NonNull
    private String updatedText(long time, long now) {
        if (time >= now - DateUtils.MINUTE_IN_MILLIS) {
            return context.getString(R.string.updated_just_now);
        }
        CharSequence when;
        if (DateUtils.isToday(time)) {
            when = DateUtils.getRelativeTimeSpanString(time, now,
                    DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE);
        } else {
            when = DateUtils.formatDateTime(context, time,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH);
        }
        return context.getString(R.string.updated_time_msg, when);
    }

    // endregion

    // region Address

    /**
     * Shows the address as a Maps link: underlined in its normal color, with a small map glyph that
     * takes space on the first line only, so wrapped lines still start at the card's left padding.
     */
    @UiThread
    private void setAddressLink(@NonNull CharSequence text) {
        SpannableStringBuilder link = new SpannableStringBuilder();
        Drawable glyph = AppCompatResources.getDrawable(context, R.drawable.ic_map_20dp);
        if (glyph != null) {
            glyph = glyph.mutate();
            // About one line of text tall, never bigger.
            int size = Math.round(address.getTextSize());
            glyph.setBounds(0, 0, size, size);
            glyph.setTint(address.getCurrentTextColor());
            link.append(" ", new ImageSpan(glyph, ImageSpan.ALIGN_CENTER), 0);
            link.append(' ');
        }
        int start = link.length();
        link.append(text);
        link.setSpan(new UnderlineSpan(), start, link.length(), 0);
        address.setText(link);
        address.setClickable(true);
        address.setContentDescription(text + ". " + context.getString(R.string.open_address_in_maps));
    }

    /** A plain message in the address slot ("Waiting for location…"), which isn't a link. */
    @UiThread
    private void setAddressMessage(@StringRes int message) {
        address.setText(message);
        address.setClickable(false);
        address.setContentDescription(null);
    }

    // endregion

    @Nullable
    @UiThread
    public FriendRecord getFriend() {
        return currFriend;
    }

    @UiThread
    public long getFriendId() {
        if (currFriend != null) {
            return currFriend.id;
        }

        return -1;
    }

    @UiThread
    public void hide() {
        dismissRefreshHelp();
        showId++;   // needs to be incremented here as well
        currFriend = null;
        currLoc = null;

        float xOffset = -panel.getRight();
        SpringForce xSpring = new SpringForce(xOffset)
                .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_MEDIUM);
        new SpringAnimation(panel, DynamicAnimation.TRANSLATION_X)
                .setSpring(xSpring)
                .setStartVelocity(-1000)
                .start();
    }

    @UiThread
    public boolean isHidden() {
        return panel.getTranslationX() != 0;
    }

    @UiThread
    private void onInfoOverflowClicked() {
        final FriendRecord friend = getFriend();
        if (friend == null) {
            return;
        }
        PopupMenu menu = new PopupMenu(context, overflowButton);
        MenuInflater inflater = new MenuInflater(context);
        inflater.inflate(R.menu.info_panel_overflow, menu.getMenu());
        menu.setForceShowIcon(true);
        menu.getMenu().findItem(R.id.copy_username).setTitle("@" + friend.user.username);
        menu.getMenu().findItem(R.id.copy_username).setIcon(R.drawable.ic_copy_username);
        boolean linked = LinkedContacts.name(friend.user.userId) != null;
        menu.getMenu().findItem(R.id.link_contact).setTitle(linked ? R.string.linked_contact : R.string.link_contact);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.copy_username) {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.copy_username), "@" + friend.user.username));
                } else if (item.getItemId() == R.id.link_contact) {
                    listener.onInfoPanelLinkContact(friend);
                } else if (item.getItemId() == R.id.remove_and_block) {
                    listener.onInfoPanelBlockFriend(friend);
                } else if (item.getItemId() == R.id.remove_friend) {
                    listener.onInfoPanelRemoveFriend(friend);
                } else if (item.getItemId() == R.id.view_safety_number) {
                    listener.onInfoPanelViewSafetyNumber(friend);
                }
                return true;
            }
        });
        menu.show();
    }

    /**
     * Grays out Refresh and shows "Unsupported (?)" when the friend's phone has told us it can't answer
     * location requests (an iPhone, or a fork user, with Live Requests off).
     * @return true if requests are unsupported for the current friend.
     */
    @UiThread
    private boolean applyRequestCapability() {
        FriendRecord friend = currFriend;
        FriendDevices.Device device = friend == null ? null : FriendDevices.get(context, friend.user.userId);
        platformIcon.setVisibility(device == null || device.platform == null ? View.GONE : View.VISIBLE);
        if (device != null && device.platform != null) {
            platformIcon.setImageResource(device.isIPhone() ? R.drawable.ic_platform_apple : R.drawable.ic_platform_android);
            platformIcon.setContentDescription(context.getString(device.isIPhone() ? R.string.apple_device : R.string.android_device));
        }
        boolean unsupported = friend != null && friend.receivingBoxId != null && device != null && device.cannotAnswerRequests();
        requestsUnsupported = unsupported;
        if (!unsupported) {
            checkingSupport = false;
            dismissRefreshHelp();
            return false;
        }
        refreshButton.setEnabled(false);
        // Hidden (not just disabled) until a later check finds their phone can answer again. Invisible rather
        // than gone, so the share switch beside it keeps its place.
        refreshButton.setVisibility(View.INVISIBLE);
        // Re-check a cached "can't answer" before declaring it; the status row shows "Checking…" meanwhile.
        DeviceStatusProbe.probeIfNeeded(context, friend);
        checkingSupport = DeviceStatusProbe.isProbing(friend.user.userId);
        if (checkingSupport) {
            dismissRefreshHelp();
        }
        @StringRes int body = device.isIPhone() ? R.string.unsupported_iphone_body : R.string.unsupported_android_body;
        refreshHelpBody = context.getString(body, xyz.zood.george.Nicknames.displayName(friend.user));
        return true;
    }

    /** A friend's device status changed or a check finished: redraw Refresh / Unsupported for the open friend. */
    private final DeviceStatusProbe.Listener deviceStatusListener = new DeviceStatusProbe.Listener() {
        @Override
        public void onDeviceStatusChanged(@NonNull byte[] userId) {
            FriendRecord friend = currFriend;
            if (friend == null || !java.util.Arrays.equals(friend.user.userId, userId)) {
                return;
            }
            if (currLoc != null) {
                calculateAndUpdateRefreshButton(System.currentTimeMillis());
            } else if (!applyRequestCapability()) {
                refreshButton.setEnabled(friend.receivingBoxId != null);
            }
            updateStatusRow(System.currentTimeMillis());
        }
    };

    @UiThread
    private void onRefreshClicked() {
        final FriendRecord friend = getFriend();
        if (friend == null) {
            return;
        }
        listener.onInfoPanelLocationRequested(friend);
    }

    @UiThread
    public void show(@NonNull FriendRecord friend, @Nullable FriendLocation loc) {
        // If the panel is not already visible, then slide it into view
        if (isHidden()) {
            SpringForce xSpring = new SpringForce(0)
                    .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY)
                    .setStiffness(SpringForce.STIFFNESS_MEDIUM);
            new SpringAnimation(panel, DynamicAnimation.TRANSLATION_X)
                    .setSpring(xSpring)
                    .setStartVelocity(1000)
                    .start();
        }

        if (currFriend == null || currFriend.id != friend.id) {
            dismissRefreshHelp();
            lastStatus = null;  // a different friend: don't fade from the previous friend's status
        }
        currFriend = friend;
        currLoc = loc;
        showId++;

        // common stuff
        username.setText(xyz.zood.george.Nicknames.displayName(friend.user));
        applySharingState(friend);

        // If they're not sharing with us
        if (friend.receivingBoxId == null) {
            // show the empty state and get out of here
            setAddressMessage(R.string.not_sharing_with_you);
            refreshProgressBar.setVisibility(View.INVISIBLE);
            refreshButton.setVisibility(View.INVISIBLE);
            dismissRefreshHelp();
            updateStatusRow(System.currentTimeMillis());
            bearing.setVisibility(View.GONE);
            motion.setVisibility(View.GONE);
            battery.setVisibility(View.INVISIBLE);
            batteryIcon.setVisibility(View.INVISIBLE);
            return;
        }

        // If we don't have location info yet (from a new friend)
        if (loc == null) {
            setAddressMessage(R.string.waiting_for_location_ellipsis);
            refreshButton.setVisibility(View.VISIBLE);
            refreshButton.setEnabled(!applyRequestCapability());
            updateStatusRow(System.currentTimeMillis());
            bearing.setVisibility(View.GONE);
            motion.setVisibility(View.GONE);
            battery.setVisibility(View.INVISIBLE);
            batteryIcon.setVisibility(View.INVISIBLE);
            return;
        }

        // == They are sharing with us ==
        long now = System.currentTimeMillis();
        calculateAndUpdateRefreshButton(now);
        updateStatusRow(now);

        // refresh status
        updateRefreshProgressBarState(loc.friendId);

        // friend activity info
        @DrawableRes int movement = 0;
        switch (loc.movement) {
            case Bicycle:
                movement = R.drawable.ic_sharp_bike_20dp;
                break;
            case OnFoot:
            case Running:
            case Walking:
                movement = R.drawable.ic_sharp_walk_20dp;
                break;
            case Vehicle:
                movement = R.drawable.ic_sharp_car_20dp;
                break;
            default:
                break;
        }
        if (movement != 0) {
            motion.setImageResource(movement);
            motion.setVisibility(View.VISIBLE);
        } else {
            motion.setImageBitmap(null);
            motion.setVisibility(View.GONE);
        }

        // friend bearing
        if (loc.bearing != null) {
            bearing.setVisibility(View.VISIBLE);
            bearing.setRotation(loc.bearing);
        } else {
            bearing.setVisibility(View.GONE);
        }

        // battery status
        if (loc.batteryLevel != null) {
            battery.setText(context.getString(R.string.battery_percent_msg, loc.batteryLevel));
            @DrawableRes int batteryImg;
            if (loc.batteryCharging != null && loc.batteryCharging) {
                if (loc.batteryLevel >= 95) {
                    batteryImg = R.drawable.ic_sharp_battery_charging_full_20dp;
                } else if (loc.batteryLevel >= 85) {
                    batteryImg = R.drawable.ic_sharp_battery_charging_90_20dp;
                } else if (loc.batteryLevel >= 70) {
                    batteryImg = R.drawable.ic_sharp_battery_charging_80_20dp;
                } else if (loc.batteryLevel >= 55) {
                    batteryImg = R.drawable.ic_sharp_battery_charging_60_20dp;
                } else if (loc.batteryLevel >= 40) {
                    batteryImg = R.drawable.ic_sharp_battery_charging_50_20dp;
                } else if (loc.batteryLevel >= 25) {
                    batteryImg = R.drawable.ic_sharp_battery_charging_30_20dp;
                } else {
                    batteryImg = R.drawable.ic_sharp_battery_charging_20_20dp;
                }
            } else {
                if (loc.batteryLevel >= 95) {
                    batteryImg = R.drawable.ic_sharp_battery_full_20dp;
                } else if (loc.batteryLevel >= 85) {
                    batteryImg = R.drawable.ic_sharp_battery_90_20dp;
                } else if (loc.batteryLevel >= 70) {
                    batteryImg = R.drawable.ic_sharp_battery_80_20dp;
                } else if (loc.batteryLevel >= 55) {
                    batteryImg = R.drawable.ic_sharp_battery_60_20dp;
                } else if (loc.batteryLevel >= 40) {
                    batteryImg = R.drawable.ic_sharp_battery_50_20dp;
                } else if (loc.batteryLevel >= 25) {
                    batteryImg = R.drawable.ic_sharp_battery_30_20dp;
                } else {
                    batteryImg = R.drawable.ic_sharp_battery_20_20dp;
                }
            }
            Drawable drawable = AppCompatResources.getDrawable(context, batteryImg);
            batteryIcon.setImageDrawable(drawable);
            battery.setVisibility(View.VISIBLE);
            batteryIcon.setVisibility(View.VISIBLE);
        } else {
            battery.setText(null);
            batteryIcon.setImageDrawable(null);
            battery.setVisibility(View.INVISIBLE);
            batteryIcon.setVisibility(View.INVISIBLE);
        }

        // the address
        RevGeocoding rg = ReverseGeocodingCache.get(loc.latitude, loc.longitude);
        if (rg != null) {
            setAddressLink(rg.getAddress());
        } else {
            setAddressMessage(R.string.loading_ellipsis);
            ReverseGeocodingCache.fetch(context, loc.latitude, loc.longitude, new ReverseGeocodingCache.OnCachedListener() {
                @Override
                public void onReverseGeocodingCached(@Nullable RevGeocoding rg) {
                    if (currLoc == null) {
                        return;
                    }
                    if (currLoc.latitude == loc.latitude && currLoc.longitude == loc.longitude) {
                        if (rg != null) {
                            setAddressLink(rg.getAddress());
                        } else {
                            setAddressLink(formatCoordinates(loc.latitude, loc.longitude));
                        }
                    }
                }
            });
        }

        App.runOnUiThread(new InfoPanelRefresher(showId), REFRESH_INTERVAL);
    }

    @NonNull
    private static String formatCoordinates(double lat, double lng) {
        // Always use a '.' decimal separator, so it can't be confused with the ',' between the values
        return String.format(Locale.US, "%.5f, %.5f", lat, lng);
    }

    @UiThread
    public void updateFriendRecord(@NonNull FriendRecord update) {
        if (currFriend == null) {
            return;
        }

        if (currFriend.id == update.id) {
            currFriend = update;
            // Refresh the switch and its label too; otherwise the label kept saying "Not sharing" after
            // sharing was turned on until the panel was closed and reopened.
            applySharingState(update);
        }
    }

    @UiThread
    private void applySharingState(@NonNull FriendRecord friend) {
        shareSwitch.setOnCheckedChangeListener(null);
        if (friend.sendingBoxId != null) {
            shareSwitch.setChecked(true);
            shareSwitchLabel.setText(R.string.sharing);
        } else {
            shareSwitch.setChecked(false);
            shareSwitchLabel.setText(R.string.not_sharing);
        }
        shareSwitch.setOnCheckedChangeListener(shareCheckedChangeListener);
    }

    @UiThread
    public void updateRefreshProgressBarState(long friendId) {
        UpdateStatusTracker.State status = UpdateStatusTracker.getFriendState(friendId);

        int vis;
        @ColorRes int colorId = 0;
        switch (status) {
            case NotRequested:
                vis = View.GONE;
                break;
            case Requested:
                vis = View.VISIBLE;
                colorId = R.color.zood_canary;
                break;
            case RequestedAndUnresponsive:
                vis = View.VISIBLE;
                colorId = R.color.inactive;
                break;
            case RequestDenied:
                vis = View.VISIBLE;
                colorId = R.color.zood_red;
                break;
            case RequestAcknowledged:
                vis = View.VISIBLE;
                colorId = R.color.zood_blue;
                break;
            case RequestFulfilled:
                vis = View.GONE;
                break;
            default:
                vis = View.GONE;
                break;
        }
        if (colorId != 0) {
            DrawableCompat.setTint(refreshProgressBar.getIndeterminateDrawable(),
                    ContextCompat.getColor(context, colorId));
        }
        refreshProgressBar.setVisibility(vis);
        if (currFriend != null && currFriend.id == friendId) {
            updateStatusRow(System.currentTimeMillis());
        }
    }

    private final CompoundButton.OnCheckedChangeListener shareCheckedChangeListener = new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (currFriend == null) {
                L.i("Share switch changed, but no FriendRecord found");
                return;
            }

            listener.onInfoPanelShareToggled(currFriend, isChecked);
        }
    };

    private void showRefreshHelp(View anchor) {
        if (refreshHelpBody == null) return;
        dismissRefreshHelp();
        View content = android.view.LayoutInflater.from(context).inflate(R.layout.refresh_help_popup, null);
        TextView message = content.findViewById(R.id.refresh_help_body);
        message.setText(refreshHelpBody);
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.min(Math.round(300 * density), panel.getWidth() - Math.round(16 * density));
        refreshHelp = new PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        refreshHelp.setBackgroundDrawable(context.getDrawable(R.drawable.bg_folder_preview));
        refreshHelp.setElevation(8 * density);
        refreshHelp.setOutsideTouchable(true);
        refreshHelp.showAsDropDown(anchor, 0, 0);
    }

    private void dismissRefreshHelp() {
        if (refreshHelp != null) {
            refreshHelp.dismiss();
            refreshHelp = null;
        }
    }

    public interface Listener {
        default void onInfoPanelBlockFriend(@NonNull FriendRecord friend) {}
        @UiThread
        void onInfoPanelCloseRequested();
        @UiThread
        void onInfoPanelLocationRequested(@NonNull FriendRecord friend);
        @UiThread
        void onInfoPanelRemoveFriend(@NonNull FriendRecord friend);
        /** Open the friend's last location in a maps app, labeled with the name shown on the card. */
        @UiThread
        void onInfoPanelOpenInMaps(@NonNull String displayName, @NonNull FriendLocation location);
        /** Link (or relink / unlink) the friend to one of this phone's contacts. */
        @UiThread
        void onInfoPanelLinkContact(@NonNull FriendRecord friend);
        @UiThread
        void onInfoPanelShareToggled(@NonNull FriendRecord friend, boolean shouldShare);
        @UiThread
        void onInfoPanelViewSafetyNumber(@NonNull FriendRecord friend);
    }

    private class InfoPanelRefresher implements UiRunnable {
        private final long id;

        private InfoPanelRefresher(long id) {
            this.id = id;
        }

        @Override
        public void run() {
            if (showId != id) {
                return;
            }

            // update the relative time, and refresh button
            long now = System.currentTimeMillis();
            calculateAndUpdateRefreshButton(now);
            updateStatusRow(now);

            App.runOnUiThread(this, REFRESH_INTERVAL);
        }
    }

}
