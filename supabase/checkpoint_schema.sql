-- ############################################################################
-- WHICH FILE DO I RUN?
-- Open Dashboard, Table Editor, and look for a table named checkpoints.
--
--   no checkpoints table at all      run checkpoint_schema.sql only
--   it has a tour_id column          run checkpoint_schema.sql only
--   it has a property_id column      run migrate_site_checkpoints_to_tours.sql
--                                    first, then checkpoint_schema.sql
--
-- Most databases want checkpoint_schema.sql on its own. The migration file exists
-- only for a database left over from an earlier version of this schema, and it
-- fails with "relation public.checkpoints does not exist" if you run it on a
-- database that never had that older shape. That failure is harmless, nothing is
-- changed, and you can go straight to checkpoint_schema.sql.
-- ############################################################################

-- ============================================================================
-- Elite Guard Tours -- checkpoint and tour schema
-- ----------------------------------------------------------------------------
-- Target: the Elite Guard INCIDENT REPORTING Supabase project
--         https://fmfcfepwindmioiowvfe.supabase.co
--
-- Run this in that project: Dashboard, SQL Editor, New query, paste, Run.
-- Every statement is idempotent, so it is safe to run more than once.
--
-- It expects two tables that already exist in that project:
--   public.properties                 uuid id, name              the client sites
--   public.incident_portal_accounts   a role column, keyed to auth
--
-- It ADDS address and zone columns on properties, four new tables
-- (tours, checkpoints, tour_logs, tour_scans), their row level security
-- policies, and a reporting view. Nothing existing is dropped or altered
-- beyond the two new nullable columns on properties.
--
-- The hierarchy is site, then tour, then checkpoint. The NAME of a checkpoint
-- is what gets written onto its NFC tag and what a scan is matched against.
--
-- Every statement below is plain SQL. There are no DO blocks, no dynamic SQL,
-- and no semicolons or apostrophes inside comments, so any SQL client can run
-- the file whether or not it splits the script up before sending it.
-- ============================================================================

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------- 1. properties
-- The app shows the address and zone of a site under its name. Both are optional.
alter table public.properties add column if not exists address text;
alter table public.properties add column if not exists zone    text;

-- ---------------------------------------------------------------- 2. who may manage tours
-- True when the signed-in account may create tours and checkpoints and write NFC tags.
--
-- The accounts table is read through to_jsonb so this works no matter which column
-- links it to Supabase Auth. A missing key reads as null rather than failing, so all
-- three spellings can be checked at once and no column has to exist.
--
-- To change who may manage tours, edit the role list on the marked line and re-run.
create or replace function public.is_tour_manager()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1
    from public.incident_portal_accounts a
    where lower(coalesce(to_jsonb(a) ->> 'role', '')) = any (array['admin'])
      and auth.uid()::text = any (array[
        to_jsonb(a) ->> 'id',
        to_jsonb(a) ->> 'user_id',
        to_jsonb(a) ->> 'auth_user_id'
      ])
  )
$$;

-- ---------------------------------------------------------------- 3. tours
-- A named patrol route at a site. The admin portal creates these under a site,
-- then adds the checkpoints that belong to each one.
create table if not exists public.tours (
  id               uuid primary key default gen_random_uuid(),
  property_id      uuid not null references public.properties(id) on delete cascade,
  name             text not null,
  description      text,
  sort_order       integer not null default 0,
  expected_minutes integer,
  active           boolean not null default true,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now()
);

create index if not exists tours_property_idx on public.tours (property_id, sort_order);

-- ---------------------------------------------------------------- 4. checkpoints
-- A named checkpoint on one tour. The NAME is the identity. An admin writes it onto
-- an NFC tag from the phone, and a scan during a tour is matched by comparing the
-- name read off the tag against the checkpoints of that tour.
--
-- tag_uid and tag_written_at record the last tag a name was written to. They are
-- informational only, matching never depends on them, and they let the app show which
-- checkpoints still need a tag. They are deliberately NOT unique, because one physical
-- tag reading Front Gate can serve the Front Gate checkpoint on several tours.
create table if not exists public.checkpoints (
  id             uuid primary key default gen_random_uuid(),
  tour_id        uuid not null references public.tours(id) on delete cascade,
  name           text not null,
  description    text,
  sort_order     integer not null default 0,
  tag_uid        text,
  tag_written_at timestamptz,
  active         boolean not null default true,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now()
);

create index if not exists checkpoints_tour_idx on public.checkpoints (tour_id, sort_order);

-- Names must be unambiguous within a tour, because a scan is matched by name. The
-- comparison ignores case and collapsed whitespace, exactly as the app does it.
create unique index if not exists checkpoints_tour_name_idx
  on public.checkpoints (tour_id, lower(regexp_replace(btrim(name), '\s+', ' ', 'g')))
  where active;

-- ---------------------------------------------------------------- 5. tour logs
-- One row per tour walked by one officer. Ids are generated on the phone so a tour
-- walked offline can be uploaded later without duplicates.
--
-- officer_id holds auth.uid() and deliberately carries no foreign key, so this schema
-- does not depend on how incident_portal_accounts is keyed.
create table if not exists public.tour_logs (
  id                  uuid primary key,
  property_id         uuid not null references public.properties(id) on delete restrict,
  tour_id             uuid not null references public.tours(id) on delete restrict,
  officer_id          uuid,
  officer_name        text,
  started_at          timestamptz not null,
  completed_at        timestamptz,
  status              text not null default 'in_progress'
                      check (status in ('in_progress', 'completed', 'incomplete')),
  total_checkpoints   integer not null default 0,
  scanned_checkpoints integer not null default 0,
  device_id           text,
  notes               text,
  created_at          timestamptz not null default now()
);

create index if not exists tour_logs_property_started_idx on public.tour_logs (property_id, started_at desc);
create index if not exists tour_logs_officer_idx on public.tour_logs (officer_id, started_at desc);

-- One row per tag tap. A repeated tap of the same checkpoint is kept with
-- is_duplicate set to true, so the portal can show it without counting it.
create table if not exists public.tour_scans (
  id              uuid primary key,
  tour_log_id     uuid not null references public.tour_logs(id) on delete cascade,
  checkpoint_id   uuid references public.checkpoints(id) on delete set null,
  checkpoint_name text,
  scanned_at      timestamptz not null,
  tag_uid         text,
  is_duplicate    boolean not null default false,
  created_at      timestamptz not null default now()
);

create index if not exists tour_scans_log_idx on public.tour_scans (tour_log_id, scanned_at);

-- ---------------------------------------------------------------- 5b. reconcile columns
-- "create table if not exists" leaves an existing table exactly as it is, so a table
-- created by an older version of this file would never gain the columns added since, and
-- the reporting view at the end would fail to build. These statements add anything
-- missing and do nothing at all when the column is already there.
alter table public.tours add column if not exists description      text;
alter table public.tours add column if not exists sort_order       integer not null default 0;
alter table public.tours add column if not exists expected_minutes integer;
alter table public.tours add column if not exists active           boolean not null default true;
alter table public.tours add column if not exists created_at       timestamptz not null default now();
alter table public.tours add column if not exists updated_at       timestamptz not null default now();

alter table public.checkpoints add column if not exists description    text;
alter table public.checkpoints add column if not exists sort_order     integer not null default 0;
alter table public.checkpoints add column if not exists tag_uid        text;
alter table public.checkpoints add column if not exists tag_written_at timestamptz;
alter table public.checkpoints add column if not exists active         boolean not null default true;
alter table public.checkpoints add column if not exists created_at     timestamptz not null default now();
alter table public.checkpoints add column if not exists updated_at     timestamptz not null default now();

alter table public.tour_logs add column if not exists officer_id          uuid;
alter table public.tour_logs add column if not exists officer_name        text;
alter table public.tour_logs add column if not exists completed_at        timestamptz;
alter table public.tour_logs add column if not exists status              text not null default 'in_progress';
alter table public.tour_logs add column if not exists total_checkpoints   integer not null default 0;
alter table public.tour_logs add column if not exists scanned_checkpoints integer not null default 0;
alter table public.tour_logs add column if not exists device_id           text;
alter table public.tour_logs add column if not exists notes               text;
alter table public.tour_logs add column if not exists created_at          timestamptz not null default now();

alter table public.tour_scans add column if not exists checkpoint_id   uuid;
alter table public.tour_scans add column if not exists checkpoint_name text;
alter table public.tour_scans add column if not exists tag_uid         text;
alter table public.tour_scans add column if not exists is_duplicate    boolean not null default false;
alter table public.tour_scans add column if not exists created_at      timestamptz not null default now();

-- ---------------------------------------------------------------- 5c. device enrolment
-- A phone is tied to one site before anyone signs in. The portal issues a license key per
-- site, and the installer types the portal host and that key once. From then on the phone
-- only ever sees that site.
create table if not exists public.site_licenses (
  id          uuid primary key default gen_random_uuid(),
  property_id uuid not null references public.properties(id) on delete cascade,
  -- Printed for a human to type. Stored however you like, since the check below ignores
  -- case and any dashes or spaces. 16 hex characters is 64 bits, which is far too much to
  -- guess, and the default generates one for you.
  license_key text not null unique default upper(encode(gen_random_bytes(8), 'hex')),
  -- The host the installer types, for example eliteguard.siloam.one. Checked together with
  -- the key, so a key issued for one tenant cannot enrol a phone pointed at another.
  portal_host text not null,
  label       text,
  active      boolean not null default true,
  created_at  timestamptz not null default now()
);

create index if not exists site_licenses_property_idx on public.site_licenses (property_id);

-- One row per enrolled phone, so the portal can see which devices belong to which site.
-- Written by the app after a successful sign-in, not during enrolment.
create table if not exists public.site_devices (
  device_id    text primary key,
  property_id  uuid not null references public.properties(id) on delete cascade,
  portal_host  text,
  app_version  text,
  enrolled_at  timestamptz not null default now(),
  last_seen_at timestamptz not null default now()
);

create index if not exists site_devices_property_idx on public.site_devices (property_id);

-- How a phone records itself. Done through a function rather than a direct insert for two
-- reasons. An upsert is ON CONFLICT DO UPDATE underneath, which needs to read the conflicting
-- row, so a caller allowed to write but not read would be refused. And this way the table needs
-- no insert or update grant at all: the only write path is this one function, and reading the
-- fleet stays an admin matter.
create or replace function public.register_device(
  p_device_id   text,
  p_property_id uuid,
  p_portal_host text,
  p_app_version text
)
returns void
language sql
security definer
set search_path = public
as $$
  insert into public.site_devices (device_id, property_id, portal_host, app_version, last_seen_at)
  values (p_device_id, p_property_id, p_portal_host, p_app_version, now())
  on conflict (device_id) do update
    set property_id  = excluded.property_id,
        portal_host  = excluded.portal_host,
        app_version  = excluded.app_version,
        last_seen_at = now()
$$;

-- The one thing an unauthenticated app may ask: does this license key belong to this host,
-- and if so which site is it. Nothing else about the license is exposed, and the table
-- itself is never readable without a login, so the worst a guessed key reveals is a site
-- name. Signing in is still required to see or write anything.
--
-- Comparison ignores case and any dashes or spaces, so the key can be printed in groups
-- of four and typed however is convenient.
create or replace function public.verify_site_license(p_license_key text, p_portal_host text)
returns table (property_id uuid, property_name text)
language sql
stable
security definer
set search_path = public
as $$
  select l.property_id, p.name
  from public.site_licenses l
  join public.properties p on p.id = l.property_id
  where l.active
    and upper(regexp_replace(coalesce(p_license_key, ''), '[^0-9A-Za-z]', '', 'g'))
      = upper(regexp_replace(l.license_key, '[^0-9A-Za-z]', '', 'g'))
    and lower(btrim(coalesce(p_portal_host, ''))) = lower(btrim(l.portal_host))
  limit 1
$$;

-- ---------------------------------------------------------------- 6. access control
-- Applied only to the new tables. The existing properties and
-- incident_portal_accounts tables are left exactly as they are.
alter table public.tours       enable row level security;
alter table public.checkpoints enable row level security;
alter table public.tour_logs     enable row level security;
alter table public.tour_scans    enable row level security;
alter table public.site_licenses enable row level security;
alter table public.site_devices  enable row level security;

-- Supabase grants these to new tables in public automatically. Granting explicitly
-- means the app still works if that project default was ever changed.
grant select, insert, update, delete on public.tours       to authenticated;
grant select, insert, update, delete on public.checkpoints to authenticated;
grant select, insert, update         on public.tour_logs   to authenticated;
grant select, insert, update         on public.tour_scans  to authenticated;
grant select, insert, update, delete on public.site_licenses to authenticated;
-- Read only. The one write path is register_device below, which runs as owner.
grant select                         on public.site_devices  to authenticated;

-- Function privileges have to be revoked before they are granted. PostgreSQL gives EXECUTE on
-- a new function to PUBLIC by default, and on Supabase anon is part of PUBLIC, so granting to
-- authenticated alone would leave the function open to anyone holding the publishable key.
revoke all on function public.is_tour_manager() from public;
revoke all on function public.verify_site_license(text, text) from public;
revoke all on function public.register_device(text, uuid, text, text) from public;

-- Enrolment happens before anyone signs in, so this one is deliberately callable by anon. It
-- returns only a site id and name for a key that matches its host, and the tables behind it
-- stay unreadable without a login.
grant execute on function public.verify_site_license(text, text) to anon, authenticated;

-- These two need a signed-in account. Policies call is_tour_manager as the querying user, so
-- authenticated must be able to execute it.
grant execute on function public.is_tour_manager() to authenticated;
grant execute on function public.register_device(text, uuid, text, text) to authenticated;

-- Tours and checkpoints: every signed-in officer may read, admins may change.
--
-- auth.uid() and is_tour_manager() are wrapped in a sub-select on purpose. Postgres then
-- evaluates each once per statement instead of once per row, which is what the Supabase
-- advisor means by an auth RLS initplan warning. It does not change who sees what.
drop policy if exists "tours read"   on public.tours;
drop policy if exists "tours manage" on public.tours;
create policy "tours read"   on public.tours for select to authenticated using (true);
create policy "tours manage" on public.tours for all    to authenticated
  using ((select public.is_tour_manager())) with check ((select public.is_tour_manager()));

drop policy if exists "checkpoints read"   on public.checkpoints;
drop policy if exists "checkpoints manage" on public.checkpoints;
create policy "checkpoints read"   on public.checkpoints for select to authenticated using (true);
create policy "checkpoints manage" on public.checkpoints for all    to authenticated
  using ((select public.is_tour_manager())) with check ((select public.is_tour_manager()));

-- Licenses and devices: only an admin may read or change them. Enrolment does not read
-- these tables directly, it goes through verify_site_license above.
drop policy if exists "site_licenses manage" on public.site_licenses;
create policy "site_licenses manage" on public.site_licenses for all to authenticated
  using ((select public.is_tour_manager())) with check ((select public.is_tour_manager()));

-- Only an admin may read the fleet. There is no write policy because there is no direct write
-- path: register_device above runs as owner and is the only way a row gets there.
drop policy if exists "site_devices read"   on public.site_devices;
drop policy if exists "site_devices write"  on public.site_devices;
drop policy if exists "site_devices update" on public.site_devices;
create policy "site_devices read" on public.site_devices for select to authenticated
  using ((select public.is_tour_manager()));

-- Tour logs: an officer sees and writes their own, an admin sees all of them.
drop policy if exists "tour_logs read"   on public.tour_logs;
drop policy if exists "tour_logs insert" on public.tour_logs;
drop policy if exists "tour_logs update" on public.tour_logs;
create policy "tour_logs read"   on public.tour_logs for select to authenticated
  using (officer_id = (select auth.uid()) or (select public.is_tour_manager()));
create policy "tour_logs insert" on public.tour_logs for insert to authenticated
  with check (officer_id = (select auth.uid()));
create policy "tour_logs update" on public.tour_logs for update to authenticated
  using (officer_id = (select auth.uid()) or (select public.is_tour_manager()))
  with check (officer_id = (select auth.uid()) or (select public.is_tour_manager()));

drop policy if exists "tour_scans read"   on public.tour_scans;
drop policy if exists "tour_scans insert" on public.tour_scans;
drop policy if exists "tour_scans update" on public.tour_scans;
create policy "tour_scans read" on public.tour_scans for select to authenticated
  using (exists (select 1 from public.tour_logs l
                 where l.id = tour_log_id and (l.officer_id = (select auth.uid()) or (select public.is_tour_manager()))));
create policy "tour_scans insert" on public.tour_scans for insert to authenticated
  with check (exists (select 1 from public.tour_logs l
                      where l.id = tour_log_id and l.officer_id = (select auth.uid())));
create policy "tour_scans update" on public.tour_scans for update to authenticated
  using (exists (select 1 from public.tour_logs l
                 where l.id = tour_log_id and (l.officer_id = (select auth.uid()) or (select public.is_tour_manager()))))
  with check (exists (select 1 from public.tour_logs l
                      where l.id = tour_log_id and (l.officer_id = (select auth.uid()) or (select public.is_tour_manager()))));

-- ---------------------------------------------------------------- 7. reporting view
-- One line per walked tour, for the admin portal.
--
-- security_invoker is essential here, not optional. A Postgres view runs with the
-- privileges of the account that owns it, which for a view created in the SQL editor is
-- a superuser, and superusers are exempt from row level security. Without this setting
-- the view would hand every officer every other officer tour log, straight past the
-- tour_logs policies below. With it, the view is evaluated as whoever is querying, so
-- the same policies apply and an officer sees only their own tours while an admin sees
-- all of them. Requires PostgreSQL 15 or newer, which Supabase has.
create or replace view public.tour_log_summary
with (security_invoker = on) as
select
  l.id,
  l.property_id,
  p.name              as property_name,
  l.tour_id,
  t.name              as tour_name,
  l.officer_id,
  l.officer_name,
  l.started_at,
  l.completed_at,
  l.status,
  l.total_checkpoints,
  l.scanned_checkpoints,
  extract(epoch from (l.completed_at - l.started_at)) / 60 as duration_minutes
from public.tour_logs l
join public.properties p on p.id = l.property_id
left join public.tours t on t.id = l.tour_id;

grant select on public.tour_log_summary to authenticated;

-- ---------------------------------------------------------------- 8. clean-up
-- An older version of this file installed a trigger to maintain updated_at. The
-- column remains and is set when a row is created. Nothing in the app reads it, so
-- the trigger and its function are removed rather than kept as plpgsql, which some
-- SQL clients mis-parse. A portal that wants the column current can set
-- updated_at = now() in its own update statements.
drop trigger if exists tours_set_updated_at       on public.tours;
drop trigger if exists checkpoints_set_updated_at on public.checkpoints;
drop function if exists public.set_updated_at();

-- ============================================================================
-- IF THE APP SHOWS AN EMPTY SITE LIST
-- ----------------------------------------------------------------------------
-- The policies of the incident project decide whether a signed-in officer may read
-- public.properties. If row level security is on there with no read policy for
-- authenticated users, the app signs in but lists no tours. Check the policies on
-- public.properties in Dashboard, Authentication, Policies.
--
-- If a read policy is missing, the line below adds one. It is commented out on
-- purpose, because it widens who can read the site list of the incident project.
-- Remove the two dashes to enable it.
--
-- create policy "properties read" on public.properties for select to authenticated using (true)
-- ============================================================================
