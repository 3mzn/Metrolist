-- 0002_mirror_hardening.sql - poison backoff + error backoff (SPEC_MIRROR_FIXES Fix-A)

-- Per-URI resolve-failure tracking: park after 5 fails, weekly retry.
create table if not exists public.mirror_skipped (
  source_id uuid not null references public.mirror_sources (id) on delete cascade,
  spotify_id text not null,
  failures integer not null default 1,
  failed_at timestamptz not null default now(),
  primary key (source_id, spotify_id)
);

-- Consecutive poll-failure counter for quiet backoff (wait min(60, 2^count) minutes).
alter table public.mirror_sources
  add column if not exists error_count integer not null default 0;

-- Service role bypasses RLS; no policies needed for either. Anon gets nothing new.
