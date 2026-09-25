-- 0004_mirror_dismissed.sql - cross-device review dismissals (S9)
-- Union semantics: dismiss on either phone hides the row everywhere.
-- Undismiss is per-device (the other phone keeps its local dismissal).

create table if not exists public.mirror_dismissed (
  source_id uuid not null references public.mirror_sources (id) on delete cascade,
  spotify_id text not null,
  dismissed_at timestamptz not null default now(),
  primary key (source_id, spotify_id)
);

alter table public.mirror_dismissed enable row level security;

-- Consistent with the existing mirror tables: anon clients sync their own links.
drop policy if exists "anon read dismissed" on public.mirror_dismissed;
create policy "anon read dismissed" on public.mirror_dismissed
for select to anon using (true);
drop policy if exists "anon write dismissed" on public.mirror_dismissed;
create policy "anon write dismissed" on public.mirror_dismissed
for insert to anon with check (true);
drop policy if exists "anon undismiss" on public.mirror_dismissed;
create policy "anon undismiss" on public.mirror_dismissed
for delete to anon using (true);
