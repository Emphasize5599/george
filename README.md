# Amidst for Android

This branch is the independent Android companion to [Amidst for iPhone](https://github.com/Emphasize5599/family-locator-ios). Its application ID is `com.bensanzone.amidst`; the upstream store links below describe the original Zood app.

See the [shared privacy policy](https://github.com/Emphasize5599/family-locator-ios/blob/main/Docs/PRIVACY.md?view=policy) and [Google Play review notes](Docs/PLAY_REVIEW.md).

Names follow this priority: your private nickname for a friend, their shared nickname, then `@username`. The immutable username remains available in View Username and is always used for account lookup and safety numbers. Shared profile names travel inside encrypted communications; private overrides only enter your encrypted backup. Optional Snapshot fields preserve schema version1 for compatibility; older clients may omit these fields when saving a backup.

The profile tile opens Account, where the shared nickname can be edited. Change Photo remains a separate text action. The About dialog reads the installed package identifier rather than assuming the upstream identifier.

For isolated UI checks, Debug builds include `xyz.zood.george.LayoutTourActivity`. Use it only on a dedicated tour emulator: it refuses signed-in devices and supplies a fictional username without keys or an access token. Release builds exclude this activity. Run one emulator/simulator at a time and always cold boot; do not overlap release optimization with simulator verification on the8GB development Mac.

# Zood Location Android

Zood Location is an easy to use location sharing app leveraging end-to-end encryption.

Features
* Easy to use interface for sharing your location with your family or close friends.
* End to end encryption
* Username based accounts
* Convenient 'timed shares' for temporarily sharing your location with someone that is not a friend via a link.

Currently available on the Play Store, and F-Droid via the [IzzyOnDroid repo](https://apt.izzysoft.de/fdroid/).

<a href="https://play.google.com/store/apps/details?id=xyz.zood.george"><img alt="Get it on Google Play" src="https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png" height="80px" /></a>
<a href="https://apt.izzysoft.de/fdroid/index/apk/xyz.zood.george"><img alt="Get it on IzzyOnDroid" src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png" height="80px" /></a>

## License

Copyright 2020 Arash Payan

Licensed under the AGPLv3: https://www.gnu.org/licenses/agpl-3.0.en.html

Zood is a trademark of Tangled Wires, LLC<br>
Google Play and the Google Play logo are trademarks of Google Inc.

### Friend requests

Continue after the username check to choose **Share My Location** or **Not Now**. Both send a friend request to a current Amidst client. **Not Now** sends your optional profile photo and display name without a location grant, and the recipient sees that you are not sharing. Older Zood clients only understand requests that include a grant. Approving a request leaves your location sharing off. If either person chooses to share later, their location updates the existing friend card.

A one-time link connects the first recipient whose confirmation is processed by a current Amidst client. The inviter’s sharing stays off unless they turn it on. A username-only link uses the ordinary request flow.

After location setup, Amidst can ask for optional Contacts access if an already-linked Android contact needs its photo restored. Photos are read from the linked cards on the device and stay there.

### Live Requests with Zood and Amidst

Refresh remains available when the other client omits device status, as original Zood does, or last reported Live Requests off. A legacy completion reply triggers a check for a newer encrypted location. The friend card waits up to 30 seconds and retains the previous location and timestamp if no update arrives.

### Editing names

Tap a nickname on a friend card or in Account, or tap an open folder’s title to edit it in place. The friend-card pencil and Rename Folder menu action do the same thing. Names wrap as you type, and the folder widens within the screen margins. Use the green checkmark to save or the yellow × to discard the draft. Tapping outside the editor or pressing Back with the keyboard closed also discards the draft. A friend’s editor starts with the name on their card. The card and the bottom bar move above the keyboard, and an offscreen friend tile scrolls into view to show the draft name and any initials. Tap a friend’s tile again to close their card. Tap a folder name or choose Rename Folder to open the keyboard. The folder title is vertically centered with its menu button, and its bottom-bar label previews the draft. In Settings, About opens Notices & Trademarks and displays the installed version at the top.

Amidst uses the same light and dark action colors on iOS and Android. Navigation and discovery use blue, permissions and positive actions use green, and discarding an inline draft uses yellow. Destructive actions use red.

Encrypted account backups include compact thumbnails of the friend profile photos already cached on your device. Signing back in restores them without waiting for friends to come online. Photos from linked address-book contacts stay on the device.
