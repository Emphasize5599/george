# Amidst on Google Play

Not ready to submit. This file is the script for Play Console. It is not a Play approval.

The package is `com.bensanzone.zood`. Reviewer usernames and passwords stay in the iOS repo's `.review-private/APP_REVIEW_CREDENTIALS.md`. That file is Git-ignored. Do not paste it here.

The store name is Amidst: Where Everyone Is. The home-screen name is Amidst.

## What's left to do

Do these before submitting. The form answers and the listing text below are ready to use.

1. Re-check both demo accounts. Delete one new disposable account through the app and confirm it can no longer sign in. Also confirm deletion and retention on https://www.zood.xyz/location/delete-account.
2. On a physical Android device, test denied location, foreground-only location, and background location. Record a video of the in-app disclosure and the location permission flow. Play requires that video for background location.
3. Build a signed Play App Bundle from the Play flavor and review it in the console. GitHub APKs are sideloads. The Play flavor does not request a direct battery-optimization exemption. It sends the user to Android Settings instead.
4. Create the Play listing and paste the answers below. Enter the publisher's contact details. Finish Data safety, the content questionnaire, app access, and the declarations for background location and the location foreground services.
5. Upload current screenshots, an icon, and feature art of this app. Do not reuse upstream Zood art.
6. Confirm someone monitors https://github.com/Emphasize5599/family-locator-ios/issues and can escalate service abuse to the Zood operator.
7. Keep evidence that Amidst may use the Zood service, and confirm what that service retains.

Do not join Designed for Families. Do not put a price in the title or the short description.

## Answers for the submission form

Play calculates the content rating from the questionnaire. Do not type a rating in by hand.

### Store settings

| Field | Answer |
| --- | --- |
| App name | Amidst: Where Everyone Is |
| Default language | English (United States) |
| App or game | App |
| Free or paid | Free |
| Ads | No |
| In-app purchases | No |

### App content

| Question | Answer |
| --- | --- |
| Privacy policy | https://github.com/Emphasize5599/family-locator-ios/blob/main/Docs/PRIVACY.md?view=policy |
| Ads | No |
| App access | All of the main features need a sign-in. Provide both demo accounts from the private credentials file. No two-factor prompt and no purchase. |
| Target audience | General audience. Not primarily directed at children. |
| Designed for Families | Do not opt in. |
| News app | No |
| Government app | No |
| Data deletion | The app deletes the account at Settings → Account → Delete Account, after the server confirms. Web deletion: https://www.zood.xyz/location/delete-account. The same steps are in the privacy policy: https://github.com/Emphasize5599/family-locator-ios/blob/main/Docs/PRIVACY.md?view=policy#delete-your-amidst-account |

Content questionnaire: answer None or No for violence, sexual content, language, controlled substances, and gambling. Answer Yes for users interacting and for sharing real-world location. There is no chat thread and no public feed.

### Data safety

Data is collected and is not sold. It is encrypted in transit. Users can request account deletion. There is no advertising SDK.

Location packages, messages, photos, and the account backup are encrypted on the device before upload. The service can still see account identifiers and connection metadata such as IP address. Say that. Do not select "data is not collected."

| Data | Answer |
| --- | --- |
| Precise location | Collected. App functionality. Optional in the sense that viewing friends does not require sharing. Required for the user to share their own location. |
| Name | Optional nicknames and a chosen contact name, inside the encrypted backup. |
| Email address | Optional, at registration. |
| User IDs | Username and public account identifier. |
| Photos | Optional profile photo, encrypted for friends. |
| Contacts | Only the one contact the user picks. Not the address book. The contact photo stays on the device. |
| App activity and diagnostics | Do not declare analytics or crash-reporting you did not add. |
| Device or other IDs | Battery, platform, motion category, accuracy, speed, and direction travel inside the encrypted location package. |

The Zood service is an independent operator, not an SDK the Amidst maintainers run. When the form asks whether data is shared with a third party, read the service-provider exception against https://www.zood.xyz/privacy/mobile-apps and answer for what that operator actually receives. Do not describe the operator as unable to see account identifiers.

### Location declaration

Background location is used for two things the user turns on: keeping a share updated while the app is closed, and answering a Live Request from a friend who was already granted access. The in-app disclosure has to say that before the permission prompt. The Play Console declaration and the required video should show the same thing.

Foreground services of type `location` back that sharing, Live Requests, and an active timed share. Declare those services. Do not declare a battery-optimization exemption for the Play flavor. That permission is not in the Play build.

Policies to read while answering:

- [User data](https://support.google.com/googleplay/android-developer/answer/10144311)
- [Background location](https://support.google.com/googleplay/android-developer/answer/9799150)
- [Account deletion](https://support.google.com/googleplay/android-developer/answer/13327111)
- [Prepare for review](https://support.google.com/googleplay/android-developer/answer/9859455)

## Suggested Play listing

The files in `fastlane/metadata/android/en-US/` are what Fastlane uploads.

| Field | Text | Characters |
| --- | --- | --- |
| Title | Amidst: Where Everyone Is | 25 |
| Short description | Open Source, Always Encrypted location sharing for family and friends | 69 |

Full description:

```
Amidst lets you share your location with friends you choose. Add friends by username, choose whether to share with each friend, and stop sharing at any time. You can organize the map's friend bar into folders. For a time-limited share, create a link and send it to someone you trust.

Location shared through Amidst is always encrypted on the device before it is sent. With your permission, Amidst can update a share in the background and respond to Live Requests from friends you already share with. You can use the app to view friends' shared locations and send friend requests without sharing your location.

Amidst is an independent, open-source client for the Zood Location network.
```

Play has no separate keyword field. The title, short description, and full description are the searchable text. Repeating a phrase naturally in the full description is appropriate here. The App Store keyword list is a different field and lives in the iOS `Docs/APP_REVIEW.md`.

Screenshots, the icon, and the feature graphic still need to be exported from this build. Use fictional people. Do not show a price or the Zood icon.

## For AI agents

Humans can submit from the sections above. This section is the engineering record.

Reviewed November 24, 2026 for the independent Android application `com.bensanzone.zood`.

- Target SDK 36 matches the current Play target API requirement. The Play flavor omits `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and opens system Settings for battery controls.
- Add Friend opens with location denied. Viewing friends and sending a request do not require location. The location banner explains sharing, Live Requests, and active timed shares while the app is closed. The user can decline.
- Settings links to the Amidst privacy policy. About links to support and shows the installed version. Release diagnostics on the reviewed paths do not log decoded friend messages or usernames.
- Account deletion waits for the upstream API before clearing local state. The public Zood deletion page loaded when it was checked. A live web-deletion test with a disposable account is still open.
- Main-manifest location foreground services: `TimedShareService`, `LiveRequestService`, `LocationService`, and `ScreamerService`.
- Store metadata no longer uses upstream Zood branding. The title and descriptions were updated for the chosen name. Screenshot, icon, and feature-art files were not part of that change.
