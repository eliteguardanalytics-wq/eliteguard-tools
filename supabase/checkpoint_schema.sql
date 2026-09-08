-- ============================================================================
-- Elite Guard Tours – checkpoint / tour schema
-- ----------------------------------------------------------------------------
-- Target: the Elite Guard INCIDENT REPORTING Supabase project
--         (https://fmfcfepwindmioiowvfe.supabase.co)
--
-- Run this once in that project: Dashboard -> SQL Editor -> New query -> Run.
-- Every statement is idempotent, so it is safe to re-run.
--
-- It expects two tables that already exist in that project:
--   public.properties                (uuid id, name)          -- the client sites
--   public.incident_portal_accounts  (role, keyed to auth)    -- the portal logins
--
-- It ADDS: address and zone columns on properties, plus five new tables
-- (checkpoints, tours, tour_checkpoints, tour_logs, tour_scans), their row level
-- security policies, and a reporting view. Nothing existing is dropped or altered
-- beyond the two new nullable columns on properties.
-- ============================================================================

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------- 1. properties
-- The app shows a site's address and zone under its name. Both are optional.
alter table public.properties add column if not exists address text;
alter table public.properties add column if not exists zone    text;

-- ---------------------------------------------------------------- 2. helpers
create or replace function public.set_updated_at()
returns trigger language plpgsql as $$
begin
  new.updated_at = now();
  return new;
end $$;

-- True when the signed-in account may manage checkpoints and enrol NFC tags.
--
-- The accounts table is keyed to Supabase Auth by one of id / user_id / auth_user_id.
-- This block finds which one and builds the function around it, so the function is
-- static (and fast) afterwards. To change who may enrol tags, edit the role list on
-- the marked line below and re-run this block.
do $do$
declare
  key_col text;
begin
  select c.column_name into key_col
  from information_schema.columns c
  where c.table_schema = 'public'
    and c.table_name   = 'incident_portal_accounts'
    and c.column_name in ('id', 'user_id', 'auth_user_id')
    and c.data_type    = 'uuid'
  order by array_position(array['id', 'user_id', 'auth_user_id'], c.column_name)
  limit 1;

  if key_col is null then
    raise exception
      'No uuid column named id, user_id or auth_user_id found on public.incident_portal_accounts. Create public.is_tour_manager() by hand.';
  end if;

  raise notice 'is_tour_manager() will match incident_portal_accounts.% against auth.uid()', key_col;

  execute format($fmt$
    create or replace function public.is_tour_manager()
    returns boolean language sql stable security definer
    set search_path = public
    as $body$
      select exists (
        select 1
        from public.incident_portal_accounts a
        where a.%I = auth.uid()
          and lower(a.role::text) = any (array['admin'])   -- <<< roles allowed to enrol tags
      );
    $body$;
  $fmt$, key_col);
end
$do$;

-- ---------------------------------------------------------------- 3. checkpoints
-- A named NFC checkpoint at a site. tag_uid is filled in when a tag is enrolled
-- from the phone (Set Up Tags) or from the future admin portal.
create table if not exists public.checkpoints (
  id          uuid primary key default gen_random_uuid(),
  property_id uuid not null references public.properties(id) on delete cascade,
  name        text not null,
  description text,
  sort_order  integer not null default 0,
  tag_uid     text unique,                      -- NFC tag serial, upper-case hex
  active      boolean not null default true,    -- deactivate instead of deleting
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create index if not exists checkpoints_property_idx on public.checkpoints (property_id, sort_order);

drop trigger if exists checkpoints_set_updated_at on public.checkpoints;
create trigger checkpoints_set_updated_at
  before update on public.checkpoints
  for each row execute function public.set_updated_at();

-- ---------------------------------------------------------------- 4. tours (routes)
-- Optional named routes: an ordered subset of a site's checkpoints. Sites with no
-- routes simply use "All checkpoints" in the app.
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

drop trigger if exists tours_set_updated_at on public.tours;
create trigger tours_set_updated_at
  before update on public.tours
  for each row execute function public.set_updated_at();

create table if not exists public.tour_checkpoints (
  tour_id       uuid not null references public.tours(id) on delete cascade,
  checkpoint_id uuid not null references public.checkpoints(id) on delete cascade,
  sort_order    integer not null default 0,
  primary key (tour_id, checkpoint_id)
);

-- ---------------------------------------------------------------- 5. tour logs
-- One row per tour an officer performs. Ids are generated on the phone so an
-- offline tour can be uploaded later without duplicates.
--
-- officer_id holds auth.uid() and deliberately carries no foreign key, so this
-- schema does not depend on how incident_portal_accounts is keyed. Join it to
-- that table on whichever column is_tour_manager() reported above.
create table if not exists public.tour_logs (
  id                  uuid primary key,
  property_id         uuid not null references public.properties(id) on delete restrict,
  tour_id             uuid references public.tours(id) on delete set null,
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

-- One row per tag tap. Repeated taps of the same checkpoint are kept with
-- is_duplicate = true so the portal can show them without counting them.
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

-- ---------------------------------------------------------------- 6. row level security
-- Applied only to the new tables. The existing properties and
-- incident_portal_accounts tables are left exactly as they are.
alter table public.checkpoints      enable row level security;
alter table public.tours            enable row level security;
alter table public.tour_checkpoints enable row level security;
alter table public.tour_logs        enable row level security;
alter table public.tour_scans       enable row level security;

-- Supabase grants these automatically to new tables in public, but granting explicitly
-- means the app works even if that project default was ever changed.
grant select                       on public.checkpoints, public.tours, public.tour_checkpoints to authenticated;
grant insert, update               on public.checkpoints, public.tours, public.tour_checkpoints to authenticated;
grant delete                       on public.checkpoints, public.tours, public.tour_checkpoints to authenticated;
grant select, insert, update       on public.tour_logs, public.tour_scans                       to authenticated;

-- Reference data: every signed-in officer can read; admins can change.
drop policy if exists "checkpoints read"   on public.checkpoints;
drop policy if exists "checkpoints manage" on public.checkpoints;
create policy "checkpoints read"   on public.checkpoints for select to authenticated using (true);
create policy "checkpoints manage" on public.checkpoints for all    to authenticated
  using (public.is_tour_manager()) with check (public.is_tour_manager());

drop policy if exists "tours read"   on public.tours;
drop policy if exists "tours manage" on public.tours;
create policy "tours read"   on public.tours for select to authenticated using (true);
create policy "tours manage" on public.tours for all    to authenticated
  using (public.is_tour_manager()) with check (public.is_tour_manager());

drop policy if exists "tour_checkpoints read"   on public.tour_checkpoints;
drop policy if exists "tour_checkpoints manage" on public.tour_checkpoints;
create policy "tour_checkpoints read"   on public.tour_checkpoints for select to authenticated using (true);
create policy "tour_checkpoints manage" on public.tour_checkpoints for all    to authenticated
  using (public.is_tour_manager()) with check (public.is_tour_manager());

-- Tour logs: officers see and write their own; admins see everything.
drop policy if exists "tour_logs read"   on public.tour_logs;
drop policy if exists "tour_logs insert" on public.tour_logs;
drop policy if exists "tour_logs update" on public.tour_logs;
create policy "tour_logs read"   on public.tour_logs for select to authenticated
  using (officer_id = auth.uid() or public.is_tour_manager());
create policy "tour_logs insert" on public.tour_logs for insert to authenticated
  with check (officer_id = auth.uid());
create policy "tour_logs update" on public.tour_logs for update to authenticated
  using (officer_id = auth.uid() or public.is_tour_manager())
  with check (officer_id = auth.uid() or public.is_tour_manager());

drop policy if exists "tour_scans read"   on public.tour_scans;
drop policy if exists "tour_scans insert" on public.tour_scans;
drop policy if exists "tour_scans update" on public.tour_scans;
create policy "tour_scans read" on public.tour_scans for select to authenticated
  using (exists (select 1 from public.tour_logs l
                 where l.id = tour_log_id and (l.officer_id = auth.uid() or public.is_tour_manager())));
create policy "tour_scans insert" on public.tour_scans for insert to authenticated
  with check (exists (select 1 from public.tour_logs l
                      where l.id = tour_log_id and l.officer_id = auth.uid()));
create policy "tour_scans update" on public.tour_scans for update to authenticated
  using (exists (select 1 from public.tour_logs l
                 where l.id = tour_log_id and (l.officer_id = auth.uid() or public.is_tour_manager())))
  with check (exists (select 1 from public.tour_logs l
                      where l.id = tour_log_id and (l.officer_id = auth.uid() or public.is_tour_manager())));

-- ---------------------------------------------------------------- 7. reporting view
-- One line per tour, for the future admin portal.
create or replace view public.tour_log_summary as
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

-- ============================================================================
-- IF THE APP SHOWS AN EMPTY SITE LIST
-- ----------------------------------------------------------------------------
-- The incident project's own policies decide whether a signed-in officer may read
-- public.properties. If row level security is on there with no read policy for
-- authenticated users, the app will sign in but list no sites. Check with:
--
--   select relrowsecurity from pg_class where oid = 'public.properties'::regclass;
--   select policyname, cmd, roles from pg_policies
--    where schemaname = 'public' and tablename = 'properties';
--
-- If a read policy is missing, this adds one. It is left commented out on purpose:
-- it widens who can read the incident project's site list, so enable it knowingly.
--
--   create policy "properties read" on public.properties
--     for select to authenticated using (true);
-- ============================================================================
