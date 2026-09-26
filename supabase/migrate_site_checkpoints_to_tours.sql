-- ############################################################################
-- STOP. MOST DATABASES DO NOT NEED THIS FILE.
--
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
-- Elite Guard Tours -- move checkpoints from site-owned to tour-owned
-- ----------------------------------------------------------------------------
-- This file only moves an existing site-owned checkpoints table to being
-- tour-owned. It is not part of a normal install.
--
-- What it does. The hierarchy became site, then tour, then checkpoint. Each site
-- that has checkpoints gets one tour named Main Tour to hold them, so nothing is
-- lost. Rename those tours afterwards in the Table Editor or the admin portal.
--
-- It also clears the stored tag serials. Under the old design a tag was matched by
-- its serial. A tag is now matched by the checkpoint name written onto it, so every
-- tag has to be re-written from the app once, via Set Up Tags.
--
-- Run this file FIRST, then run checkpoint_schema.sql.
--
-- Every statement is plain SQL. There are no DO blocks and no dynamic SQL, so any
-- SQL client can run the file whether or not it splits the script before sending.
-- ============================================================================

-- 1. The tours table may not exist yet, depending on how far the old file got.
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

-- 2. One holding tour per site that has checkpoints, unless that site already has one.
insert into public.tours (property_id, name, sort_order)
select distinct c.property_id, 'Main Tour', 0
from public.checkpoints c
where not exists (
  select 1 from public.tours t where t.property_id = c.property_id
);

-- 3. The new columns.
alter table public.checkpoints add column if not exists tour_id uuid references public.tours(id) on delete cascade;
alter table public.checkpoints add column if not exists tag_written_at timestamptz;

-- 4. Point every checkpoint at the holding tour of its site.
update public.checkpoints c
set tour_id = t.id
from public.tours t
where c.tour_id is null
  and t.property_id = c.property_id
  and t.sort_order = 0;

-- 5. A tour log now has to name its tour, so any old log without one cannot be kept.
delete from public.tour_logs where tour_id is null;

-- 6. Lock in the new shape and drop what the old one needed.
alter table public.checkpoints alter column tour_id set not null;
alter table public.checkpoints drop column property_id;
drop table if exists public.tour_checkpoints;

-- 7. The old design made tag_uid unique, because a serial identified one checkpoint.
--    Names identify checkpoints now, and one physical tag may serve the same-named
--    checkpoint on several tours, so that constraint has to go.
alter table public.checkpoints drop constraint if exists checkpoints_tag_uid_key;

-- 8. Clear the old serials. Those tags carry no name yet, so they need re-writing
--    from the app before a scan can match them.
update public.checkpoints set tag_uid = null where tag_written_at is null;

-- 9. Lock the tours table down until checkpoint_schema.sql adds its policies. Turning
--    row level security on with no policy yet denies everyone, which is the safe
--    direction to be in between the two files.
alter table public.tours enable row level security;

-- ============================================================================
-- Now run checkpoint_schema.sql, which adds the policies that open these tables
-- back up to the right people.
-- ============================================================================
