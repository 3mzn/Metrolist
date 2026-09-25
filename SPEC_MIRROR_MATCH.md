# SPEC_MIRROR_MATCH — smarter YouTube matching (TEMPORARY; delete after merge)

> **STATUS: IMPLEMENTED + GATED (compile ✓, unit tests ✓) together with SPEC_MIRROR_REVIEW. UNCOMMITTED — awaiting approval.**

> ## RESUME BLOCK (read first after context compaction)
> - **Repo:** `C:\musicapp\metrolist`, branch `testing`. Remote `personal` = `https://github.com/3mzn/Metrolist.git`.
> - **Feature:** Spotify auto-mirror. Parent spec: `SPEC_SPOTIFY_MIRROR.md` (COMMITTED). Fix batches: `SPEC_MIRROR_FIXES.md` (COMMITTED; Fix-A/B/C all done + pushed).
> - **This spec:** improves MATCH QUALITY only. Nothing committed for it yet. Next action: implement Phase M1 below, gate, commit `fix(mirror): smarter match queries`, await approval. Do NOT build releases without explicit approval.
> - **Server:** Edge Function `poll-spotify` deployed via Dashboard paste (Management API deploy is broken upstream — never use it). Source of truth file: `supabase/functions/poll-spotify/index.ts`. Supabase project `teeafutbybbywitdahpr`. Tokens (never print): `~/.config/supabase-management-token`, `~/.config/supabase-key`, `~/.config/supabase-anon-key`, Firebase SA `~/.config/firebase-service-account.json`.
> - **Test assets:** `C:\musicapp\spotify-mirror-test\` (probes, `full-1059.json`). User's playlist: `3Eiye9G3kmubbFiqgExMyV` (1059 entries). NEVER hand-type IDs — extract by regex from the URL.
> - **Devices:** phone `ylwwmn85w4ifb6z9` (adb), debug app `com.metrolist.music.debug`. Debug APK: `app\build\outputs\apk\foss\debug\app-foss-debug.apk`. ALWAYS delete `app\build\outputs\apk\foss\debug` before `assembleFossDebug` (stale-APK incident). Install: `adb -s <id> install -r <apk>`.
> - **House rules:** conventional commits, `compileFossDebugKotlin` + `testFossDebugUnitTest` green per change, stop for approval after each phase, push only when told, English strings only, no DB schema change without sign-off, measured numbers.
> - **User context:** owner (eman) + partner (aswini), two phones. Mirror: long-press playlist → Track with Spotify → backfill/future. Owner decisions: title+artist matching, everything in, no auto-download (streaming only), notify per batch, add-only.
> - **Known open threads:** ~30 hard feat/remix titles skip matching; FCM closed-app arrival unproven on-device (Doze nights); `status` column vestigial; anon key committed (private repo only).

## 0. Problem

~30–60 tracks per 1000 skip with clean server titles. Two distinguishable causes:

1. **Query too specific.** `"<title> <artist>"` verbatim (e.g. `"Despacito - Remix Luis Fonsi"`,
   `"Sunflower - Spider-Man: Into the Spider-Verse Post Malone"`) misses when YouTube
   indexes the base recording. Fallback with stripped queries rescues these.
2. **Blind first-hit pick.** `matchJsonTrack` takes the first `FILTER_SONG` hit with no
   quality check — live versions, hour-long compilations, lyric videos, or wrong songs
   when the top hit is junk. We HAVE Spotify `duration_ms` and never use it.

How the code knows what to strip (deterministic, no ML): the junk lives in
predictable slots — bracketed segments `[...]`, parentheticals `(...)`, and
dash-separated tails (`- Remix`, `- Radio Edit`, `- Sped Up…`, `- Single Version`,
`- Theme Song Version`, `- Music From…`, `- From…`, `- End Title`, `- Love Theme…`).
Strip in that order for the fallback query; artist is always kept (it anchors against
wrong-song matches).

How wrong-video risk stays minimal:
- `FILTER_SONG` stays (music only, no videos-from-search).
- Duration preference: among hits, prefer `|ytSecs - spotSecs| ≤ 10`; ties → prefer
  audio (`isVideoSong == false`, field exists on `SongItem`) → else first hit.
- The fallback chain is strict improvement: exact query first (today's behavior,
  unchanged); stripped query only when exact finds nothing; duration pick only
  reorders, never discards (fallback = today's first hit). Worst case == status quo.

## 1. Design (locked)

In `utils/YoutubeMatcher.kt`, mirror-only path (`matchWithOutcome`; the JSON import
method `matchJsonTrackWithRetry` is untouched):

```
matchWithOutcome(track, durationMs?, maxAttempts=2):
  1. exact: "title artist" → collect hits (up to 2 attempts as today)
  2. candidates = hits; if empty → stripped fallback queries (base title + artist),
     same retry shape
  3. pick = pickBest(candidates, durationMs) → Found / NotFound
  NetworkError only on exception (unchanged semantics for the caller)
```

New pure internals (unit-tested, `internal` like `matchTitleArtist`):
- `fallbackQueries(title, artist): List<String>` — `[exact, stripped]` (stripped omitted
  when identical to exact).
- `pickBest(candidates: List<SongItem>, durationMs: Int?): SongItem?` — duration window
  ±10s (either side null → skip preference), ATV tiebreak, else first. Empty → null.
- `stripForSearch(title): String` — bracket/paren/dash-tail stripping (comparison AND
  query use; stored data untouched).

`SongItem` fields used: `id`, `title`, `artists: List<Artist{name}>`, `duration: Int?`
(seconds), `isVideoSong` (val, derived). All constructible in tests (required:
`id, title, artists, thumbnail`; rest defaulted).

Edge cases: duration null either side → first hit (today). All hits wild durations
(>2× expected) → still picks closest (never discards; D3 "let everything in").
Empty candidate list after both queries → NotFound (consumed, as today).

## 2. Phase M1 (single commit)

Files: `utils/YoutubeMatcher.kt`, `app/src/test/.../social/SpotifyMirrorLogicTest.kt`
(append cases: strip rules, pick (duration tie/ATV/first-fallback/empty), fallback
chain order via fake track rows — matcher hits themselves are NOT unit-tested, only
the pure query/pick logic).

Gate: compile + full unit suite green. Commit `fix(mirror): smarter match queries`.
NO device gate possible without a relink (owner relinks backfill when convenient;
present-skips make it cheap: ~60 searches, expect the easy half of the hard tail
to land). Then delete this file.
