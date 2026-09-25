# SPEC_MIRROR_REVIEW — "couldn't mirror" review list (TEMPORARY; delete after merge)

> **STATUS: IMPLEMENTED + GATED (compile ✓, unit tests ✓, assembleFossDebug ✓). UNCOMMITTED — awaiting approval.**
> Device gate pending: install debug APK, then verify banner → review → retry/remove.

> ## RESUME BLOCK
> - Repo `C:\musicapp\metrolist`, branch `testing`, remote `personal`.
> - Parent: `SPEC_SPOTIFY_MIRROR.md` (committed). Sibling temp spec: `SPEC_MIRROR_MATCH.md`
>   (M1 implemented in tree, UNCOMMITTED — gate together with this).
> - Owner overrides: D3 no-queue decision REOPENED — review list approved. Schema change
>   v39→v40 APPROVED (Room entity). Entry point approved: banner in playlist screen.
> - Next: implement below, gate compile+tests, commit (await approval), then device verify.

## Design (locked)

- New `db/entities/MirrorSkipEntity.kt`, table `mirror_skip`,
  `@Entity(primaryKeys = ["localPlaylistId", "spotifyId"])`:
  `title, artist, durationMs Int?, skippedAt Long, dismissed Boolean = false`.
- `MusicDatabase`: +entity, version 39→40, `AutoMigration(39, 40)` (pure CREATE TABLE).
- `DatabaseDao`: `mirrorSkips(playlistId): Flow<List<...>>` (dismissed = 0, newest first),
  `mirrorSkipCount(playlistId): Flow<Int>`, `upsertSkip` (REPLACE), `dismissSkip`,
  `deleteSkip`, `deleteResolvedSkips` handled in Kotlin (matchTitleArtist vs knownTitles).
- `SpotifyMirrorRepository`: NotFound branch of intake upserts a skip unless a dismissed
  row exists; intake also deletes skips now present (manual adds resolve silently).
  New APIs: `skipsFlow`, `skipCountFlow`, `retrySkip` (present→resolve; Found→insert+delete;
  NotFound→touch timestamp; NetworkError→return false), `retryAllSkips`, `dismissSkip`.
- UI: banner atop `LocalPlaylistScreen` for tracked playlists with count > 0
  ("N songs couldn't be mirrored — Review"); new `MirrorReviewScreen(playlistId)` route
  in `NavigationBuilder` with per-row Retry/Remove + Retry-all + empty state.
  Strings → `res/values/metrolist_strings.xml` (English only).
- No server changes. No auto-retry of skips (battery); user-driven only.
