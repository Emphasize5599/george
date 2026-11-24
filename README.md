# Amidst for Android

This branch is the independent Android companion to [Amidst for iPhone](https://github.com/Emphasize5599/family-locator-ios). Its application ID is `com.bensanzone.zood`; the upstream store links below describe the original Zood app.

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

### Editing names

Tap a nickname on a friend card or in Account, or tap an open folder’s title to edit it in place. The friend-card pencil and Rename Folder menu action do the same thing. Names wrap as you type, and the folder widens within the screen margins. Use the green checkmark to save or the yellow × to discard the draft.

Amidst uses the same light and dark action colors on iOS and Android. Navigation and discovery use blue, permissions and positive actions use green, and discarding an inline draft uses yellow. Destructive actions use red.
