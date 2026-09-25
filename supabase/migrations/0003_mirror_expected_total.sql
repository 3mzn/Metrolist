-- 0003_mirror_expected_total.sql - subset-read guard (Fix-D)
-- Applied via dashboard 2026-09-25; captured here for reproducibility.

-- Generation-scoped completeness: a repeated revision must yield at least this
-- many URIs or the read is a subset flake (spclient sometimes ends the page walk
-- early AND reports the subset length as total). Reset on revision change.
alter table public.mirror_sources
  add column if not exists expected_total integer;
