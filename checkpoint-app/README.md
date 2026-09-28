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

### One-time device activation

A phone belongs to one site, decided before anyone signs in.

1. The portal issues a **site licence** for a site — a row in `site_licenses` carrying the key and
   the portal host it is valid for. The key defaults to 16 random hex characters; print it in
   groups of four if that is easier to type.
2. On first launch the app asks for the **portal address** (`eliteguard.siloam.one`, or
   `xyzsecurity.siloam.one` for another tenant) and that licence.
3. The app resolves the address to a backend, checks the licence against it, and stores the site.
   From then on the phone only ever sees that one site. Signing out does not undo it; an admin can
   reassign the phone from the home menu.

The address decides which Supabase project the app talks to. A host may publish
`https://<host>/app-config.json` holding `supabase_url` and `supabase_anon_key`, which is what
lets a second tenant work without a new build. When that file is absent, as it is for Elite Guard
today, the app falls back to the project compiled into `Config.kt`. The licence is still checked
against whichever backend was resolved, and a key is only accepted for the host it was issued
for, so a licence cannot be used against another tenant.

### Then, per person

A guard signs in and gets Start Tour. An admin signs in and additionally gets **Set Up Tags**,
scoped to the phone's site, with three things on it:

- **Create New Tour** — names a patrol route at this site.
- **Program Existing Tag** — pick the tour, pick the checkpoint, confirm, hold the tag.
- **Add New Tag** — pick the tour, type the name, press Program Tag, hold the tag. The checkpoint
  is created when the button is pressed, so a failed write leaves a named checkpoint to retry
  rather than losing the name.

An officer then presses **Start Tour**, picks a tour, and walks it. Each tag tap turns that
checkpoint green and drops it to the bottom of the list. **End Tour** finishes whether or not
everything was scanned.

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

Run this in the **incident reporting** Supabase project: Dashboard, SQL Editor, New query,
paste, Run.

### Which file to run

Open the Table Editor and look for a table named `checkpoints`.

| What you see | What to run |
| --- | --- |
| No `checkpoints` table at all | [`checkpoint_schema.sql`](../supabase/checkpoint_schema.sql) only |
| It has a `tour_id` column | [`checkpoint_schema.sql`](../supabase/checkpoint_schema.sql) only |
| It has a `property_id` column | [`migrate_site_checkpoints_to_tours.sql`](../supabase/migrate_site_checkpoints_to_tours.sql) first, then [`checkpoint_schema.sql`](../supabase/checkpoint_schema.sql) |

**Almost certainly the first row.** The migration file exists only for a database left over
from an earlier version of this schema. Running it on a database that never had that older
shape fails with `relation "public.checkpoints" does not exist`, which is harmless — nothing
is changed, and you can go straight to `checkpoint_schema.sql`.

`checkpoint_schema.sql` adds `address` and `zone` to `properties`, creates `tours`,
`checkpoints`, `tour_logs` and `tour_scans` with their row level security policies, and creates
the `tour_log_summary` view for the admin portal. Re-running it is safe. Both files are plain
SQL with no procedural blocks, so any client can run them.

### Then

1. Row level security is turned on by the script itself, with policies, on all four tables it
   creates. You do not need to enable it by hand, and nothing should be left disabled. The
   `tour_log_summary` view is created with `security_invoker = on`, without which a Postgres view
   runs as its owner and would show every officer every other officer's tour logs regardless of
   those policies. Your own `properties` and `incident_portal_accounts` tables are left untouched.
2. Officers need a row in `incident_portal_accounts`. Accounts whose `role` is `admin` can create
   tours and checkpoints and write tags from the phone; everyone else can walk tours. The
   `is_tour_manager()` function reads that table through `to_jsonb`, so it works whether the row
   is keyed by `id`, `user_id` or `auth_user_id`, and whether `role` is text or an enum.
3. If the app signs in but lists no tours, the incident project's own policies are blocking reads
   of `properties`. The bottom of the schema file has the check and a one-line fix.
4. Issue a licence for each site so its phones can be activated:

   ```sql
   insert into public.site_licenses (property_id, portal_host, label)
   select id, 'eliteguard.siloam.one', 'Ocean Place phones'
   from public.properties where name = 'Ocean Place'
   returning license_key;
   ```

   The returned key is what the installer types. `site_licenses` is readable only by an admin,
   and enrolment goes through `verify_site_license()`, which is the one function an
   unauthenticated app may call. It answers with a site name only when the key matches the host
   it was issued for, so a guessed key reveals nothing more than that, and signing in is still
   required to see or change anything.
5. Enrolled phones appear in `site_devices`, written by `register_device()` after a successful
   sign-in. Only an admin can read that table.
6. Create tours and checkpoints in the admin portal, or from the app's **Set Up Tags** screen, or
   by inserting rows into `tours` and `checkpoints` directly.

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
    data/Device.kt              which site this phone belongs to, set once
    net/TenantConfig.kt         resolves a portal host to a backend
    data/Repository.kt          sync, tour start/scan/end, checkpoint setup
    net/SupabaseClient.kt       tiny Supabase auth + PostgREST client
    nfc/NfcTags.kt              tag serial, NDEF read/write helpers
    ui/EnrolActivity.kt         one-time activation against a site licence
    ui/LoginActivity.kt         sign in
    ui/HomeActivity.kt          Start Tour, sync status, refresh
    ui/SetupMenuActivity.kt     the three admin options
    ui/AddTagActivity.kt        name a checkpoint and program its tag
    ui/TourPickerActivity.kt    this site tours
    ui/TourActivity.kt          the officer's checklist + NFC reader mode
    ui/SetupActivity.kt         write checkpoint names onto tags
    ui/HistoryActivity.kt       tours recorded on this phone
  app/src/main/res/             layouts, strings, colours, icons
```

## Colour

The app uses the Siloam One brand palette: Ink `#0F1A2A`, Slate `#46586E`, Bone `#F6F4F0`,
Brass `#D08E2C`, White `#FFFFFF`. Those five are in `res/values/colors.xml` exactly as supplied,
and everything else is derived from them rather than invented — tints are washes of a hue over
Bone, and text colours are darkened until they clear WCAG AA on whatever they sit on. The
measured ratios are recorded beside each token so a future change can be checked instead of
guessed at. All twenty text pairings in the app pass AA.

Brass is the accent. It carries the header accent, the progress fill, the selected checkpoint and
the informational banners, but it is a mid-tone, so it is never body text on a light surface
(2.77:1 on White) and never sits behind white text. Banner text uses a darkened Brass instead.

Two colours sit outside the brand because they carry meaning the palette has no room for: a
muted forest green for a scanned checkpoint, and a brick red for a failure. Both are desaturated
to sit with the warm neutrals. Colour is never the only signal — a scanned checkpoint also gains
a tick in place of its number, its detail line changes, and it moves to the bottom of the list —
which matters because green and red are close in luminance and hard to separate for a
red-green colourblind officer.

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
  add checkpoints per tour with a bulk-add box (names only). Issuing and revoking site licences,
  and a view of enrolled phones from `site_devices`. Plus tour log review, for which the
  `tour_log_summary` view and `tour_scans` table are ready.
- **`app-config.json` on each tenant host**, once a second tenant exists, so a single build can
  serve all of them.
- Optional GPS capture per scan and photo/incident notes at a checkpoint.
- Scheduled tour compliance reporting (expected vs. actual tours per shift).
