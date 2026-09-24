# SPEC_MIRROR_FIXES — audit fix batches (TEMPORARY; delete after merge)

> Status: DRAFT — awaiting owner approval per batch. Parent: SPEC_SPOTIFY_MIRROR.
> Owner decisions locked: FCM push built (F16), quiet backoff (F15), 2-min screen-off
> (F12), weekly poison retry (F10). Out of scope: F5, F9.

## Fix-A — server batch (one redeploy)

Goal: harden `poll-spotify` without changing its wire contract (responses identical).

Files: `supabase/functions/poll-spotify/index.ts` only. User re-pastes + Deploys once.

- **F10 poison backoff.** New table `mirror_skipped(source_id, spotify_id, failures, failed_at)`
  (migration SQL in the batch commit). Resolve loop: skip URIs parked (failures>=5 AND
  failed_at within 7 days); on resolve failure increment/create (failed_at=now); on
  success delete the skip row. Weekly retry falls out naturally.
- **F3 exact added-count.** Drop `ignoreDuplicates`; catch 409 per row (conflict =
  concurrent poll resolved it first → not counted). `added`/`remaining` then exact.
- **F15 quiet backoff.** New column `mirror_sources.error_count`. On any poll failure:
  error_count+1; wait `min(60, 2^count)` minutes before next attempt (skip early);
  on success reset to 0 + clear `error`. Auto-resumes, no UI.
- **F2-server timeouts.** `AbortSignal.timeout(15000)` on mint/read/embed fetches.
- **F16 Edge→FCM.** Firebase service-account stored via Management API secrets
  (`FIREBASE_SERVICE_ACCOUNT`, done with the owner's token — no user action). After
  new rows: best-effort data message `{type:mirror_wake, source_id, count}` to
  `metrolist_foss_updates` (Deno `crypto.subtle` RS256 JWT, 10s timeout, swallow all
  errors — WorkManager remains the guarantee).

Gate (numbers): existing gates re-run green (2-poll, revision, exclusion) + new:
poison URI resolves-fails 5× (simulated by pointing a test source at 5 bad URIs… in
practice verify skip-row creation on the 6 known-unresolvable Rock Classics URIs if
still failing, else code-review) then skipped on poll 6; dead source backs off
(verify via shortened test thresholds in a dry run, then ship production values);
FCM test message arrives on the debug device. Commit
`fix(mirror-server): backoff, exact counts, timeouts, FCM push`.

## Fix-B — phone core batch

Goal: correctness + scale. No UI changes (one strings-neutral batch).

Files: `social/SpotifyMirrorRepository.kt` (+ `utils/YoutubeMatcher.kt` untouched),
`app/build.gradle.kts` (no change expected).

- **F1 mutex around link tail.** `linkPlaylist` takes `intakeMutex` across
  save-link → reset → seed/backfill, so alive ticks can't interleave. (Link UI waits;
  rare action, bounded minutes for giant backfills — acceptable, note in code.)
- **F2-app timeouts.** Ktor `HttpTimeout` (30s request / 10s connect) on the mirror client.
- **F4 409→re-GET.** `getOrCreateSource`: catch conflict on insert → re-GET → return id.
- **F6 orphan sweep.** `intakeRows`: if `playlistBlocking` null → `untrack(localId)` +
  return (replaces silent skip).
- **F14 pullAll pagination.** Loop `limit/offset` (1000 pages) like backfill; drop the
  2000 cap.
- **F7 snapshot+incremental set (careful).** Load `(title, artist)` set once per
  `intakeRows`; `matchAll`/`insertMatched` consult it; every insert adds to it;
  in-transaction videoId guard stays the final backstop. Snapshot staleness is
  impossible by construction (single-flight + incremental add covers this pass).
- **F8 chunked inserts.** Collect matched pairs in order; insert in 50-chunks, each
  its own transaction, running max re-read per chunk (serialized → order preserved).
- **F11 alertOnlyOnce.** `setOnlyAlertOnce(true)` on the mirror notification.

Gate: compile + unit tests green; device: fresh backfill of the 130-playlist into a
fresh debug playlist (timed, order-checked); kill mid-backfill → converges, no dupes.
Commit `fix(mirror): intake hardening batch`.

## Fix-C — polish batch

Goal: battery, matching quality, navigation, tests, cleanup. No behavior redesign.

Files: repository, `SongNotificationHelper.kt`, `MainActivity.kt` (+ manifest if needed
for the extra — check first), `PlaylistMenu.kt` (one-line regex share), tests,
`metrolist_strings.xml` (only if deep-link needs new strings — prefer none).

- **F12 screen-off backoff.** Alive loop checks `PowerManager.isInteractive` each tick:
  off → 120s; on → existing 10s/30s. No receiver, no new permission.
- **F13 fuzzy compare.** New pure `matchTitleArtist(storedTitle, storedArtists, title,
  artist)`: lowercase, strip `[...]`/`(...)`/remaster tails for titles; split artists
  on `,;&` + feat/ft stripping, any-overlap wins. Comparison-only, storage untouched.
  Used by both pre- and post-match checks.
- **F17 deep link.** Notification tap carries the playlist id (`EXTRA_MIRROR_PLAYLIST_ID`,
  mirror the `EXTRA_LT_INVITE_TAP` pattern); MainActivity routes to the playlist
  screen if present, else opens the app as today.
- **F19 unit tests.** New `SpotifyMirrorLogicTest`: `parseSpotifyId` valid/invalid,
  link JSON round-trip, `matchTitleArtist` cases (exact/collab/remaster/negative).
  Follow `SharedPlaylistModelsTest` style.
- **F20 cleanup.** Rewrite the repository header KDoc (consumed-set truth); delete
  `debugBootstrapIfNeeded` + consts; menu uses repo `isSpotifyPlaylistUrl()` instead
  of its inline regex.

Gate: compile + unit tests green (new tests pass); device: screen-off tick spacing
(logcat), collab-dupe regression (previous 10-dupe scenario stays clean), tap
notification lands on the playlist. Commit `fix(mirror): polish batch`.

## Verification (all batches)

Re-run Phase-4 matrix deltas: backfill timing improved (report songs/min before/after),
kill-mid-backfill converges ordered, FCM closed-app wake measured end-to-end once,
no dupes on re-pull, untrack stops. Numbers, not impressions.
