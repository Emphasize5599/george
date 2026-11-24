# Amidst Android review preparation

Reviewed November 24, 2026 for Amidst’s independent Android application, com.bensanzone.zood.

## Client checks

- Target SDK 36 matches the current [Play target API requirement](https://support.google.com/googleplay/android-developer/answer/11926878). The Play flavor leaves out the permission for a direct battery-optimization exemption and opens Android Settings for battery controls.
- Add Friend opens with location denied. Viewing friends and sending a request without sharing don’t depend on the permission. The location banner explains what the app uses location for, including sharing with selected friends, Live Requests and active timed shares while the app is closed. Users can decline the dialog. The separate background settings prompt is available when sharing with friends.
- Settings links to the [Amidst privacy policy](https://github.com/Emphasize5599/family-locator-ios/blob/main/Docs/PRIVACY.md?view=policy). About has a support/report link and the installed version. Release diagnostics avoid logging decoded friend messages and the usernames in the reviewed paths.
- Account deletion is in Settings → Account. The client waits for confirmation from the upstream API before clearing local state. The [Amidst deletion section](https://github.com/Emphasize5599/family-locator-ios/blob/main/Docs/PRIVACY.md?view=policy#delete-your-amidst-account) links to the [Zood deletion page](https://www.zood.xyz/location/delete-account) for deletion outside the app. The public page was checked for access. Web deletion and associated data retention still need a live verification with a disposable account.
- The Play title is Amidst: Where Everyone Is. The short description is Open Source, Always Encrypted location sharing for family and friends. Upstream branding and screenshots were removed from the active metadata. Supply current Amidst screenshots, icon and feature art for the listing.

## Publisher checks

- Complete Play Console Data safety, account deletion URL, app access, the content and audience questionnaires, and the background location and foreground-service declarations. Provide a video showing the disclosure and location use. Supply working test credentials with a fictional sample friend and the upstream service available. Test denied, foreground-only, and background location on physical devices.
- Confirm service authorization and retention with the Zood operator. Maintain a monitored way to receive abuse reports and a response and escalation process. The app supports blocking and reports, and the operator controls actions on the service.
- Verify the current signed Play bundle in the console before submission. GitHub APKs are sideloading packages. Play submission and approval are separate.

See [Play User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311), [background location](https://support.google.com/googleplay/android-developer/answer/9799150), [account deletion](https://support.google.com/googleplay/android-developer/answer/13327111), and [preparing for review](https://support.google.com/googleplay/android-developer/answer/9859455).
