# PeppaNotifier — Android client

Native Kotlin app that polls `http://chat.kimiasoft.ir` every 15 seconds via a
persistent foreground service. No push, no internet required (intranet only).

## Prerequisites

* Android Studio Hedgehog 2023.1.1+ (any version with AGP 8.2 / Gradle 8.2 support).
* JDK 17 (bundled with recent Android Studio).
* Android SDK Platform 34 + build-tools 34.x (Android Studio offers to install).

## Build (Android Studio)

1. Open the `android/` directory as a project.
2. Let it sync Gradle (downloads on first run).
3. Replace `app/src/main/res/raw/notify.mp3` with a real notification sound (the
   committed file is a 0-byte placeholder so the project compiles). Keep it
   short: 0.5–1.5 s. The same file should also be copied to
   `web/assets/notify.mp3`.
4. Confirm `Api.BASE_URL` in `app/src/main/java/.../Api.kt` matches your
   server.
5. **Build → Build APK(s)** → debug APK at
   `app/build/outputs/apk/debug/app-debug.apk`.
6. For a release APK, **Build → Generate Signed Bundle / APK → APK → Release**.
   Use any keystore (self-signed is fine since this is a sideloaded intranet
   app). The `release` build type reuses the debug signing config, so once a
   keystore exists you can also just run `./gradlew assembleRelease`.

## Build (command line)

After opening once in Android Studio (so the Gradle wrapper jar is
materialized):

```
cd android
./gradlew assembleDebug         # debug APK
./gradlew assembleRelease       # release APK
```

If you've never opened the project in Android Studio, first run:

```
gradle wrapper --gradle-version=8.2
```

(needs a system-wide Gradle 8.x, just for the bootstrap step).

## Installing on devices

* Sideload via ADB: `adb install -r app-debug.apk`
* Or transfer the APK to the device and tap it; enable "Install unknown apps"
  for the source.

## Required per-device setup

On each phone, after first launch:

1. **Disable battery optimization** for PeppaNotifier (Settings → Apps →
   PeppaNotifier → Battery → Unrestricted).
2. **Allow notifications** when prompted (only required on Android 13+).
3. **Keep the persistent service notification visible** — it is what keeps the
   poller alive when the OS would otherwise kill the app.

Do Not Disturb is respected by design. If a user enables DND, sound is
suppressed.

## What it does

* On first launch shows a login screen. Username/password come from
  `web/data/users.json` on the server.
* On successful login the foreground service starts and a permanent
  notification appears.
* Every 15 s the service polls `/api.php?action=poll`. Any new messages produce
  a per-message notification (silent channel) and a `notify.mp3` playback. If
  `STREAM_MUSIC` is below 5 %, it's temporarily boosted to 30 % and restored
  after the clip ends.
* Tapping a message notification opens the main screen.
* In the main screen, tapping **Send** opens the user picker (with online
  status + PING shortcut). Tapping a user opens the send modal. Tapping the
  PING pill sends `ping!` directly.
* Tapping any history row opens the send modal pre-filled with the other
  party.
* In the send modal, the title shows the recipient's status (green dot online,
  gray dot + last-seen offline) and the action row is `[Send] [Ping] [Cancel]`.
* The Ping button sends `ping!` regardless of what's in the text box.
