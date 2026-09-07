-- ============================================================================
-- Elite Guard Tours – checkpoint / tour schema for Supabase
-- ----------------------------------------------------------------------------
-- Run this once in the Supabase SQL editor (Dashboard -> SQL Editor -> New query).
-- It is safe to re-run: every statement is idempotent.
--
-- Assumes the tables already used by the web tools exist:
--   public.properties (id uuid primary key, name text, ...)   -- client sites
--   public.profiles   (id uuid primary key = auth.users.id, username, display_name, role)
-- If your properties.id column is bigint instead of uuid, change the type of
-- every property_id column below to match.
-- ============================================================================

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------- helpers
create or replace function public.set_updated_at()
returns trigger language plpgsql as $$
begin
  new.updated_at = now();
  return new;
end $$;

-- True when the signed-in user may manage checkpoints and routes.
create or replace function public.is_tour_manager()
returns boolean
language sql stable security definer
set search_path = public
as $$
  select exists (
    select 1 from public.profiles
    where id = auth.uid() and role in ('admin', 'supervisor', 'manager')
  );
$$;

-- ---------------------------------------------------------------- checkpoints
-- A named NFC checkpoint at a site. tag_uid is filled in when a tag is enrolled
-- from the phone (Set Up Tags) or from the admin portal.
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

-- ---------------------------------------------------------------- tours (routes)
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

-- ---------------------------------------------------------------- tour logs
-- One row per tour an officer performs. Ids are generated on the phone so an
-- offline tour can be uploaded later without duplicates.
create table if not exists public.tour_logs (
  id                  uuid primary key,
  property_id         uuid not null references public.properties(id) on delete restrict,
  tour_id             uuid references public.tours(id) on delete set null,
  officer_id          uuid references public.profiles(id) on delete set null,
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

-- ---------------------------------------------------------------- row level security
alter table public.checkpoints      enable row level security;
alter table public.tours            enable row level security;
alter table public.tour_checkpoints enable row level security;
alter table public.tour_logs        enable row level security;
alter table public.tour_scans       enable row level security;

-- Reference data: every signed-in officer can read; managers can change.
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

-- Tour logs: officers see and write their own; managers see everything.
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

-- ---------------------------------------------------------------- portal helper view
-- Handy for the future admin portal: one line per tour with the site name.
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

-- ---------------------------------------------------------------- example seed (optional)
-- insert into public.checkpoints (property_id, name, sort_order)
-- select id, 'Front Gate', 1 from public.properties where name = 'Example Plaza';
