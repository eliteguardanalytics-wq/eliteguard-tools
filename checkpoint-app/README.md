# Elite Guard Tours (Android)

Android app for security officers to run NFC checkpoint tours. Each client site has
named checkpoints; the officer sees the list on the phone, taps each NFC tag as they
walk the tour, and the checkpoint is marked as scanned with a timestamp.

The app runs against the Elite Guard **incident reporting** Supabase project
(`fmfcfepwindmioiowvfe`). It shares that project's logins and its `properties` table, and
adds its own checkpoint and tour-log tables alongside the incident data.

## What the app does

- **Sign in** with the same username and password as the incident reporting portal.
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
- **Set Up Tags** (accounts with the `admin` role only, from the tour screen menu):
  add or rename checkpoints for a site and enrol NFC tags by selecting a checkpoint and tapping
  a tag. The tag serial is stored on the checkpoint, and the checkpoint id is also written onto
  the tag (NDEF) when the tag is writable.
- **Tour history** of tours recorded on this phone, with upload status.

## Backend setup (one time)

1. Open the **incident reporting** Supabase project and run
   [`../supabase/checkpoint_schema.sql`](../supabase/checkpoint_schema.sql) in the SQL editor.
   It adds `address` and `zone` columns to `properties`, creates `checkpoints`, `tours`,
   `tour_checkpoints`, `tour_logs` and `tour_scans` with their row level security policies,
   and creates a `tour_log_summary` view for the future admin portal. Re-running it is safe.
2. Watch for the notice it prints: `is_tour_manager() will match incident_portal_accounts.<col>
   against auth.uid()`. That confirms it found how your accounts table links to Supabase Auth.
3. Officers need a row in `incident_portal_accounts`. Accounts whose `role` is `admin` can set up
   checkpoints and enrol tags from the phone; everyone else can run tours.
4. If the app signs in but lists no sites, the incident project's own policies are blocking reads
   of `properties`. The bottom of the SQL file has the check and the one-line fix.
5. Until the admin portal exists, checkpoints are created and tags enrolled from the app's
   **Set Up Tags** screen, or by inserting rows into `checkpoints` directly.

## Building

Requirements: Android Studio (Narwhal or newer) or a JDK 17+ plus the Android SDK with
platform 36 installed.

```bash
cd checkpoint-app
./gradlew test                 # unit tests (no device needed)
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
    App.kt, Config.kt           application singletons and all backend settings
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
- **All backend settings live in `Config.kt`**: project URL, publishable key, the accounts and
  sites table names, the login domain, and which roles may enrol tags. Pointing the app at a
  different project is a one-file change plus a run of the SQL.
- **The login domain is discovered, not hard-coded.** Officers sign in with a username, but
  Supabase Auth authenticates on an e-mail address, so the portal appends a domain. Which domain
  the incident project uses is not recorded in this repo, so on the very first sign-in the app
  tries each candidate in `Config.LOGIN_DOMAINS` and permanently remembers the one that works.
  Every later sign-in is a single request. Once the real domain is known, put it first in that
  list, or make it the only entry. `app/src/test/java/.../LoginDomainTest.kt` covers this.
- **The accounts table is read defensively.** The app selects the whole row and picks the officer's
  name from the first of `display_name`, `full_name`, `name`, `username` or `email` that is
  present, and finds the row by `id`, `user_id` or `auth_user_id`. It therefore does not depend on
  `incident_portal_accounts` having any particular shape beyond a `role` column.
- The publishable (anon) key is embedded in `Config.kt` and is safe to ship; the project's row
  level security policies decide what a signed-in officer can actually do.

## Next steps (not in this app)

- Web admin portal to manage sites, checkpoints, routes and to review tour logs
  (`tour_log_summary` view and `tour_scans` table are ready for it).
- Optional GPS capture per scan and photo/incident notes at a checkpoint.
- Scheduled tour compliance reporting (expected vs. actual tours per shift).
