# Elite Guard Tours (Android)

Android app for security officers to run NFC checkpoint tours. Each client site has
named checkpoints; the officer sees the list on the phone, taps each NFC tag as they
walk the tour, and the checkpoint is marked as scanned with a timestamp.

The app runs against the Elite Guard **incident reporting** Supabase project
(`fmfcfepwindmioiowvfe`). It shares that project's logins and its `properties` table, and
adds its own checkpoint and tour-log tables alongside the incident data.

## How it fits together

The hierarchy is **site → tour → checkpoint**. A checkpoint is a name, and that name is what
gets written onto its NFC tag.

1. In the admin portal an administrator creates a site, adds tours to it, and adds checkpoint
   names to each tour (one at a time or in bulk). Names only — no tags involved.
2. An administrator signs into this app, picks a tour, selects a checkpoint name, and holds a
   blank tag to the phone. The name is written onto the tag and stays there until an
   administrator writes something else onto it.
3. An officer signs in, presses **Start Tour**, picks a tour, and walks it. Each tag tap turns
   that checkpoint green and drops it to the bottom of the list, so what is still outstanding
   stays at the top. **End Tour** finishes the tour whether or not everything was scanned.

## What the app does

- **Sign in** with the same username and password as the incident reporting portal.
- **Home**: one big **Start Tour** button, plus **Set Up Tags** for administrators. An unfinished
  tour turns the button into **Resume Tour**.
- **Tour picker**: every tour, grouped under its site, with its checkpoint count.
- **Tour screen**: the tour's checkpoint names. A tag tap matches the name written on the tag
  against this tour's checkpoints; a match turns green, shows the time, and moves to the bottom.
  A repeat tap, a name that is not on this tour, and a tag with nothing written on it each get a
  distinct beep and vibration with an explanation.
- **End Tour** lists anything not scanned before confirming, then records the tour as
  `completed` or `incomplete`.
- **Works offline.** Sites, tours and checkpoints are cached on the phone; tour logs and scans are
  saved locally first and uploaded automatically whenever the phone is online. The home screen
  shows how many records are still waiting to upload.
- **Resumes** an in-progress tour if the app is closed or the phone restarts.
- **Set Up Tags** (accounts with the `admin` role only): pick a tour, select a checkpoint, hold a
  tag to the phone. The screen shows how many of the tour's checkpoints already have a tag. If the
  tag already carried a different name, the app says what it replaced. Checkpoints can also be
  added in bulk or renamed here, so a tour can be set up from the field before the portal exists.
- **Tour history** of tours recorded on this phone, with upload status.

## Backend setup (one time)

1. Open the **incident reporting** Supabase project and run
   [`../supabase/checkpoint_schema.sql`](../supabase/checkpoint_schema.sql) in the SQL editor.
   It adds `address` and `zone` columns to `properties`, creates `checkpoints`, `tours`,
   `tour_checkpoints`, `tour_logs` and `tour_scans` with their row level security policies,
   and creates a `tour_log_summary` view for the admin portal. Re-running it is safe. If you ran
   an earlier version of this file, where checkpoints hung off a site, it migrates them under one
   "Main Tour" per site and clears the old tag serials, since those tags now need the name
   written onto them.
2. Watch for the notice it prints: `is_tour_manager() will match incident_portal_accounts.<col>
   against auth.uid()`. That confirms it found how your accounts table links to Supabase Auth.
3. Officers need a row in `incident_portal_accounts`. Accounts whose `role` is `admin` can set up
   checkpoints and enrol tags from the phone; everyone else can run tours.
4. If the app signs in but lists no sites, the incident project's own policies are blocking reads
   of `properties`. The bottom of the SQL file has the check and the one-line fix.
5. Create tours and checkpoints in the admin portal, or until it exists from the app's
   **Set Up Tags** screen, or by inserting rows into `tours` and `checkpoints` directly.

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
    data/Models.kt              Property, Tour, Checkpoint, TourLog, TourScan,
                                plus the checklist ordering and name-matching rules
    data/Db.kt                  SQLite cache + offline queue
    data/Session.kt             sign-in tokens and officer profile
    data/Repository.kt          sync, tour start/scan/end, checkpoint setup
    net/SupabaseClient.kt       tiny Supabase auth + PostgREST client
    nfc/NfcTags.kt              tag serial, NDEF read/write helpers
    ui/LoginActivity.kt         sign in
    ui/HomeActivity.kt          Start Tour, sync status, refresh
    ui/TourPickerActivity.kt    tours grouped by site
    ui/TourActivity.kt          the officer's checklist + NFC reader mode
    ui/SetupActivity.kt         write checkpoint names onto tags
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
- **Tags carry the checkpoint name, and matching is by name.** Nothing has to be pre-registered,
  and one physical tag reading "Front Gate" serves the "Front Gate" checkpoint on every tour that
  has one. Matching ignores case and stray whitespace. Because a name is the identity, the
  database enforces that names are unique within a tour.
- **Each tag is written twice**: an app-specific NDEF external record, and a plain text record so
  a generic NFC reader shows a human the checkpoint name too. Tags are never write-locked, so an
  administrator can always re-point one.
- **`tag_uid` is audit only.** The tag serial is recorded on the checkpoint and on each scan so you
  can see which physical tag was used, but matching never depends on it.
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

- **Web admin portal** at `eliteguard.siloam.one`: a site list, add/edit tours per site, and
  add checkpoints per tour with a bulk-add box (names only). Plus tour log review, for which the
  `tour_log_summary` view and `tour_scans` table are ready.
- Optional GPS capture per scan and photo/incident notes at a checkpoint.
- Scheduled tour compliance reporting (expected vs. actual tours per shift).
