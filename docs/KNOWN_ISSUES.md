# Known issues and limitations

These findings come from a review of the source at version 0.3.0. They are listed roughly by impact.

## Missing features

### No push notifications
An earlier README described detection notifications with background polling. The current code has none of that:
- `AlarmNotificationJobService.java` contains only its `package` line.
- The manifest declares no job service and no `POST_NOTIFICATIONS` permission.

Alerts are visible only by opening **Recent motion alerts**. The public EZVIZ SDK has no push-registration API, so adding notifications would mean polling `getAlarmList` from a `JobService` or `WorkManager` job.

## Fixed

| Issue | Fix |
|---|---|
| `minSdk` 21, but `SecureVault` needs Android Keystore AES (API 23), so setup could never finish on Android 5.x | `minSdk` raised to 23 |
| `gradle.properties` pointed `org.gradle.java.home` at a JDK on the developer's PC, which broke other people's builds | Line removed from the project |

## Limitations

| Area | Limitation |
|---|---|
| Devices | Only the first 20 devices are loaded (no paging). Only the first camera channel of each device is used. |
| Alerts | The last 24 hours only, up to 50 events, all devices combined. |
| SD card | One day at a time. Thumbnails are made by playing every clip, which can take a while and wakes battery cameras. |
| Settings | No battery level, working mode, chime, volume, screen or light controls: the public SDK doesn't expose them for the DP2C. |
| Sign-out | Clears the session token but keeps the AppKey and per-camera verification codes. |
| Device settings → Video quality | Changes the camera setting but doesn't update the per-camera choice that live view remembers. |
| Platform | ARM devices only (`armeabi-v7a`, `arm64-v8a`). x86 emulators work only where ARM translation is available. |
| UI | Dark theme only, portrait only (except live full screen), English only. |
| Testing | No unit or instrumented tests. Alert-to-recording matching hasn't been validated against a real DP2C account. |

## Housekeeping

- `LiveActivity.loadImage()` is never called.
- The colour constants and view helpers (`text`, `button`, `rounded`, `dp`) are copied into every activity.
- `okhttp 3.12.1` and `gson 2.8.5` are old versions pinned to match the EZVIZ SDK. Check before upgrading.
- There is no release build type, signing config or R8 shrinking. The published APK is a debug build.
- There is a `gradlew.bat` but no `gradlew` shell script for macOS and Linux.

## Liza web

| Area | Issue or limitation |
|---|---|
| Sign-in without AppSecret | EZVIZ rejects any return address but its own (error 1016), so you paste back the success-page address by hand. The session lasts about a week. |
| AppSecret | Reaches only cameras on the EZVIZ account that created the AppKey. |
| Times | EZVIZ's web API reports SD and alert times as camera wall-clock time written as UTC. Liza converts with the computer's time zone, which is assumed to match the camera's. |
| SD playback | EZVIZ's player asks for a whole local day of clips, which its server rejects outside UTC. Liza narrows that request (in `web/public/app.js`), which depends on the player's internal request path and may need updating with a new EZUIKit version. |
| Thumbnails | Frames grabbed from clips without an alert snapshot wake battery cameras, take a few seconds each, and are cached per browser. |
| Testing | Tested in a Chromium-based browser on Windows with a DP2C (Europe). Two-way talk, arm/disarm, rename and flip haven't been exercised against a camera yet. |
