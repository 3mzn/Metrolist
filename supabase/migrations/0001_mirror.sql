-- 0001_mirror.sql - Spotify mirror tables + RLS (SPEC_SPOTIFY_MIRROR Phase 1)

create table if not exists public.mirror_sources (
  id uuid primary key default gen_random_uuid(),
  spotify_id text not null unique,
  name text not null default '',
  last_revision text,
  last_checked_at timestamptz,
  error text
);

create table if not exists public.mirror_tracks (
  id uuid primary key default gen_random_uuid(),
  source_id uuid not null references public.mirror_sources (id) on delete cascade,
  spotify_id text not null,
  title text not null,
  artist text not null,
  duration_ms integer,
  created_at timestamptz not null default now(),
  status text not null default 'pending',
  unique (source_id, spotify_id)
);
create index if not exists mirror_tracks_source_idx on public.mirror_tracks (source_id);

alter table public.mirror_sources enable row level security;
alter table public.mirror_tracks enable row level security;

-- Phone (anon): read both, insert sources (track action), mark consumed rows done.
-- Rows are NEVER deleted: the poll diffs against all known URIs, so deleting would
-- re-mirror consumed tracks on the next Spotify-side change. Edge (service_role)
-- bypasses RLS entirely.
drop policy if exists "anon read sources" on public.mirror_sources;
create policy "anon read sources" on public.mirror_sources
  for select to anon using (true);

drop policy if exists "anon insert sources" on public.mirror_sources;
create policy "anon insert sources" on public.mirror_sources
  for insert to anon with check (true);

drop policy if exists "anon read tracks" on public.mirror_tracks;
create policy "anon read tracks" on public.mirror_tracks
  for select to anon using (true);

drop policy if exists "anon mark done" on public.mirror_tracks;
create policy "anon mark done" on public.mirror_tracks
  for update to anon using (true) with check (true);
