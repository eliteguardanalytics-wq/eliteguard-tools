# Elite Guard Tours (Android)

Android app for security officers to run NFC checkpoint tours. Each client site has
named checkpoints; the officer sees the list on the phone, taps each NFC tag as they
walk the tour, and the checkpoint is marked as scanned with a timestamp. Tours and every
tag tap are recorded as tour logs in the same Supabase project the Elite Guard web tools use.

## What the app does

- **Sign in** with the same username / password as the web tools (`username@eliteguard.internal`).
- **Sites** list (the `properties` table), each showing how many checkpoints are set up.
- **Tour screen** per site: the checkpoint list, an optional route picker, Start Tour / End Tour.
  Tapping a tag while a tour is running marks that checkpoint green with the time it was scanned.
  Unknown tags, tags from another site, and repeat taps give a distinct beep/vibration and message.
- **End Tour** shows what was missed before confirming, then records the log as
  `completed` or `incomplete`.
- **Works offline.** Sites and checkpoints are cached on the phone; tour logs and scans are
  saved locally first and uploaded automatically whenever the phone is online. The Sites
  screen shows how many records are still waiting to upload.
- **Resumes** an in-progress tour if the app is closed or the phone restarts.
- **Set Up Tags** (admins, supervisors and managers only, from the tour screen menu):
  add or rename checkpoints for a site and enrol NFC tags by selecting a checkpoint and tapping
  a tag. The tag serial is stored on the checkpoint, and the checkpoint id is also written onto
  the tag (NDEF) when the tag is writable.
- **Tour history** of tours recorded on this phone, with upload status.

## Backend setup (one time)

1. Open the Supabase project used by the web tools and run
   [`../supabase/checkpoint_schema.sql`](../supabase/checkpoint_schema.sql) in the SQL editor.
   It creates `checkpoints`, `tours`, `tour_checkpoints`, `tour_logs`, `tour_scans`, the row
   level security policies, and a `tour_log_summary` view for the future admin portal.
2. Make sure officers have a row in `profiles` (created by the web tool's user management).
   Roles `admin`, `supervisor` and `manager` can set up checkpoints from the phone.
3. Until the admin portal exists, checkpoints are created and tags are enrolled from the app's
   **Set Up Tags** screen, or by inserting rows into `checkpoints` directly.

## Building

Requirements: Android Studio (Narwhal or newer) or a JDK 17+ plus the Android SDK with
platform 36 installed.

```bash
cd checkpoint-app
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # needs a signing config; see below
```

Open the `checkpoint-app` folder in Android Studio and press Run to install on a connected phone.

For a release build add a `signingConfigs` block to `app/build.gradle.kts` pointing at your
keystore (do not commit the keystore), or upload the unsigned bundle to Play App Signing.

## Installing on officer phones

The debug APK can be side-loaded for testing: copy it to the phone, open it, and allow
installs from that source. The phone must have NFC (the app requires it) and NFC must be
turned on; the tour screen shows a red banner with a shortcut to settings if it is off.

## Using the app on site

1. Sign in, pick the site.
2. (Supervisors, first visit) Menu -> **Set up tags**: add a checkpoint for each tag location,
   select it, hold a blank NFC tag to the phone. Repeat for every checkpoint.
3. Officers press **Start Tour**, walk the tour and hold the phone to each tag. A short beep
   and vibration confirms the scan; the list turns green.
4. Press **End Tour**. If checkpoints were missed the app lists them before you confirm.

## Project layout

```
checkpoint-app/
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/eliteguard/checkpoint/
    App.kt, Config.kt           application singletons and backend settings
    data/Models.kt              Property, Checkpoint, Tour, TourLog, TourScan
    data/Db.kt                  SQLite cache + offline queue
    data/Session.kt             sign-in tokens and officer profile
    data/Repository.kt          sync, tour start/scan/end, checkpoint setup
    net/SupabaseClient.kt       tiny Supabase auth + PostgREST client
    nfc/NfcTags.kt              tag serial, NDEF read/write helpers
    ui/LoginActivity.kt         sign in
    ui/SitesActivity.kt         site list, refresh, sync status
    ui/TourActivity.kt          checkpoint list + NFC reader mode
    ui/SetupActivity.kt         add checkpoints, enrol tags
    ui/HistoryActivity.kt       tours recorded on this phone
  app/src/main/res/             layouts, strings, colours, icons
```

## Design notes

- **No third-party libraries.** The app uses only the Android framework and the Kotlin standard
  library. That keeps the build small and avoids dependency churn; it also means the code
  compiles against the platform alone.
- **NFC reader mode** (`NfcAdapter.enableReaderMode`) is used instead of intent dispatch, so
  the tour screen receives taps directly, the system "new tag" popup and sound are suppressed,
  and the phone does not need any special configuration.
- **Tag matching** is by the tag's serial number first, then by the checkpoint id written on
  the tag. Either one is enough, so tags that cannot be written still work.
- **Idempotent uploads.** Logs and scans get UUIDs on the phone and are sent with PostgREST
  upserts, so a retried upload never creates duplicates.
- The Supabase publishable (anon) key is embedded in `Config.kt`, exactly as it is in the web
  tools; the server's row level security policies decide what a signed-in officer can do.

## Next steps (not in this app)

- Web admin portal to manage sites, checkpoints, routes and to review tour logs
  (`tour_log_summary` view and `tour_scans` table are ready for it).
- Optional GPS capture per scan and photo/incident notes at a checkpoint.
- Scheduled tour compliance reporting (expected vs. actual tours per shift).
