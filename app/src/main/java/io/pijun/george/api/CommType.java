package io.pijun.george.api;

public enum CommType {

    FriendRequest("friend_request"),
    InviteRedemption("invite_redemption"),
    NicknameUpdate("nickname_update"),
    AvatarRequest("avatar_request"),
    AvatarUpdate("avatar_update"),
    BrowseDevices("browse_devices"),
    Debug("debug"),
    DeviceInfo("device_info"),
    LocationInfo("location_info"),
    LocationSharingGrant("location_sharing_grant"),
    LocationUpdateRequest("location_update_request"),
    LocationUpdateRequestReceived("location_update_request_received"),
    LocationSharingRevocation("location_sharing_revocation"),
    Scream("scream"),
    ScreamBegan("scream_began"),
    // Family Locator fork: "can you answer location requests right now?" and the reply (no location inside).
    DeviceStatusRequest("device_status_request"),
    DeviceStatus("device_status"),
    Unknown("unknown");

    public final String val;

    CommType(String val) {
        this.val = val;
    }

    public static CommType get(String val) {
        if (val == null) {
            return Unknown;
        }

        for (CommType ct : values()) {
            if (ct.val.equals(val)) {
                return ct;
            }
        }

        return Unknown;
    }

}
