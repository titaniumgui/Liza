# Security and privacy

## What Liza stores

| Data | Where | Protection |
|---|---|---|
| EZVIZ Open Platform AppKey | `liza_secure` preferences | AES-256-GCM, key held in Android Keystore (non-exportable) |
| EZVIZ region | `liza_secure` preferences | Same |
| EZVIZ session access token | `liza_secure` preferences | Same |
| Camera verification codes (`verify_<serial>`) | `liza_secure` preferences | Same |
| Video quality and autoplay preferences | `liza_settings` preferences | Not encrypted (not sensitive) |
| SD clip thumbnails | App cache | App-private storage |
| Snapshots and recordings | `Pictures/Liza`, `Movies/Liza` | Shared media storage; visible to gallery apps |

## What Liza never handles

- **Your EZVIZ password.** You sign in on EZVIZ's own page through the SDK's `EzvizWebViewActivity`, and Liza only receives the resulting token.
- **Analytics or tracking.** Liza has no analytics, crash reporting or third-party services beyond the EZVIZ SDK.

## Network

- `usesCleartextTraffic="false"`: Liza makes no plain-HTTP requests.
- All API traffic goes to the EZVIZ regional servers listed in `ServerRegion.java`.
- Video goes through EZVIZ relay servers by default, falling back to a direct P2P connection when relay delivers no frames.

## Platform hardening

- `android:allowBackup="false"` keeps credentials out of device and cloud backups.
- All activities except the launcher are `exported="false"`.
- The sign-in broadcast receiver is registered with `RECEIVER_NOT_EXPORTED` on Android 13+.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | EZVIZ API and video streaming |
| `RECORD_AUDIO` | Two-way talk; requested only when you tap **Talk** |
| `MODIFY_AUDIO_SETTINGS` | Audio routing during talk |
| `WRITE_EXTERNAL_STORAGE` (Android ≤ 9 only) | Saving snapshots and recordings |
| `POST_NOTIFICATIONS` | The SD card reminder; requested only when you save a reminder (Android 13+) |
| `RECEIVE_BOOT_COMPLETED` | Schedules a saved SD card reminder again after the phone restarts |

## Publishing checklist

Before pushing the repository, confirm that none of these are committed (all are covered by `.gitignore`):

- `local.properties`, which contains your SDK path
- `*.jks` and `*.keystore`
- `.idea/` and `build/` folders

There is no AppKey or token anywhere in the source; each user enters their own at runtime.

## Liza web

| Data | Where | Protection |
|---|---|---|
| AppKey, AppSecret, region | `web/config.json` | Local file, git-ignored. The page never receives the AppSecret back. |
| EZVIZ access token | `web/session.json` | Local file, git-ignored. Deleted on sign-out. |
| SD clip thumbnails, UI preferences, SD card reminder | Browser local storage | Per browser, on this computer |
| Snapshots and recordings | Your browser's download folder | Saved only when you click Snapshot or Record |

- The server listens on `127.0.0.1` only. It refuses requests whose `Host` isn't `localhost`/`127.0.0.1` (DNS rebinding), and POSTs whose `Origin` is another website.
- It forwards only a fixed list of EZVIZ API calls (`ALLOWED` in `web/server.py`), and never logs the sign-in address, which carries the token.
- With the sign-in option, your password is typed only on EZVIZ's own page.
- `web/get_player.py` downloads EZVIZ's player from npm and installs it only if its SHA-512 checksum matches the pinned release.
