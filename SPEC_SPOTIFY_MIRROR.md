# SPEC_SPOTIFY_MIRROR — Spotify playlist auto-mirror

> Status: **DRAFT — awaiting owner approval. No code touched.**

## 0. Context for an agent with no prior history

This repo (`C:\musicapp\metrolist`) is a private two-person fork (eman + aswini) of the
Metrolist YouTube Music client (Kotlin, Compose, Room, Hilt, Firebase). Branch `testing`,
v13.10.2. The owner uses **Spotify purely for discovery** and Metrolist for listening;
today they manually re-find discovered songs.

**Feature:** long-press any existing playlist → "Track with Spotify" → paste a public
Spotify playlist link → choose backfill-missing or future-only → a server job picks up newly added Spotify songs
within about a minute and they land in the Metrolist playlist,
auto-downloaded, with a notification. Fully automatic after setup.

**Feasibility is PROVEN** (see `C:\musicapp\spotify-mirror-test\`): `probe-fullread.mjs`
reads a full playlist (1059/1059 URIs in ~4s); `probe-fullresolve.mjs` resolves every
track to `{title, artist, duration_ms}` (1059/1059, 0 failures); merged artifact
`full-1059.json`. Read path, all with plain `fetch`, no dev account, no secret, no
OAuth, no TLS impersonation:
1. `open.spotify.com/embed/playlist/{id}` → anonymous `accessToken` (~0.5s).
2. `spclient.wg.spotify.com/playlist/v2/playlist/{id}?from=&length=` → full URI list +
   `length` total + `revision` stamp + `truncated` flag (offsets byte-exact).
3. `open.spotify.com/embed/track/{id}` per NEW track only → title/artist/duration.
`/v1/*` is quota-dead for anonymous tokens and is never touched. Steady-state poll
cost with no changes: 2 small HTTP calls.

**Reuse, don't rebuild:**
- `viewmodels/JsonImportViewModel.kt` — title+artist → YouTube match + insert + failed
  list. The mirror feeds it the same row shape (see `drowning.json` in the test folder
  for the exact `[{title, artist}]` contract). NOTE: the matcher is currently private
  inside the ViewModel (UI-scoped) — Phase 2 must first extract it into an injectable
  class (e.g. `YoutubeMatcher`) with zero behavior change; the worker calls that.
- Precedents for the phone side: `social/InvitePollWorker.kt`, `social/GentleNudgeWorker.kt`,
  `social/SongListenedNotificationWorker.kt` (`@HiltWorker`, 15-min floor documented),
  `services/SongListenedMessagingService.kt` (FCM data path).
- `playback/DownloadUtil.kt` — downloads + lyrics warmer (existing WiFi/charging rules).
- Supabase project `teeafutbybbywitdahpr` (`push-update.js` precedent). Service key at
  `~/.config/supabase-key`; **management token at `~/.config/supabase-management-token`
  (verified working)**. Firebase service-account JSON at
  `~/.config/firebase-service-account.json` (for Edge→FCM signing).
- FCM topic `metrolist_foss_updates` (private fork installs only).

**House rules:** conventional commits (`type(scope): …`), `compileFossDebugKotlin` +
`testFossDebugUnitTest` green per phase, **stop for owner approval after each phase**,
no DB schema change without sign-off, English strings only in
`res/values/metrolist_strings.xml`, measured numbers never impressions.

**Phone prerequisites (build, not schema):** add the Supabase **anon (publishable) key**
as a `buildConfigField` next to the existing `SUPABASE_URL` (same value for all flavors;
public by design — RLS below does the protecting). HTTP uses the **existing Ktor CIO +
kotlinx.serialization** stack (no new dependency): PostgREST reads/writes and the
function invoke are plain JSON calls with `apikey`/`Authorization` headers.

## 1. Locked decisions

| # | Decision (owner-approved, final) |
|---|----------------------------------|
| D1 | **Setup:** long-press an EXISTING playlist → "Track with Spotify" → paste link → choose **backfill-missing** (add Spotify songs not already here) or **future-only** (ignore current, track new additions). Never offered during new-playlist creation. **Untrack** from the same long-press menu. |
| D2 | **Poll every 1 min** (server cron; ~2 tiny requests/min — trivial). Revision stamp short-circuits unchanged polls. |
| D3 | **Match on title+artist only**, same as JSON import. Everything that matches lands — **no confidence gate, no review queue** (owner: "just let the songs in"). A YouTube search with zero results is skipped (nothing to insert) and logged. |
| D4 | **Auto-download everything** mirrored, under the existing WiFi/charging rules. |
| D5 | **Lyrics same as normal downloads** (existing warmer covers it, no special path). |
| D6 | **Notify per batch** of new arrivals (local notification after intake). |
| D7 | **Add-only.** Spotify-side removals change nothing here. |
| D8 | **Local playlist, but shareable.** Mirrored adds go through the normal add path, so a shared playlist syncs to the partner exactly like a manual add (drives `removeSong`/`addSong` + listener, reuses SPEC_8 semantics). |
| D9 | **Phone pulls; FCM nudges; alive app goes direct.** App closed: WorkManager (15 min min interval) + FCM data message from Edge. **App alive: phone invokes the Edge Function directly** — on app start, on opening a tracked playlist, **every 10 s while a tracked playlist is open, every 30 s otherwise alive** — and the function **returns new rows in the response** (~2–4 s end to end). **Pull-to-refresh** on the playlist forces an immediate check. Worst cases: ~13 s foreground-open, ~33 s background-alive, ~1–6 min closed. Missed pushes never strand rows (next pull covers). Alive timers live in **application-process scope** (app-scope coroutine, cancelled with the process — explicitly not an alarm/PendingIntent, so dead process = closed path, no battery drain). |
| D10 | **Server never touches audio.** It stores track rows only; the phone downloads. |
| D11 | **Phone never talks to Spotify** (TLS-fingerprint lesson from the old OuterTune doc; also battery). All Spotify traffic is Edge-side. |
| D12 | One Spotify playlist may feed many Metrolist playlists (independent links); one Metrolist playlist tracks at most one Spotify playlist (re-track = replace link after confirm). Deleting a local playlist auto-untracks it. |

## 2. Architecture

```
Edge Function poll-spotify — DUAL MODE (same deployment):
  cron mode (pg_cron, every 1 min, service_role): poll all sources, store pending rows,
      best-effort FCM data message {source_id, count} to topic
  direct mode (phone → HTTPS invoke, anon key): poll the requested source(s) NOW,
      store pending rows AND return them in the response body (~2–4 s)
  both modes share one code path: mint → revision check → paginated read → diff →
      resolve new URIs → upsert mirror_tracks
phone:
  app closed: WorkManager 15 min + on-start reconcile + FCM-triggered expedited pull
  app alive: invoke direct mode on start, on tracked-playlist open, every 10 s while open / 30 s otherwise (process scope), pull-to-refresh
  intake (all paths, single-flight mutex — only one runs at a time): match bounded-parallel
      (Semaphore(6)) → insert strictly sequential (positions mirror row order) →
      per-device consumed-set update → local notification (D6). Consumption is tracked
      per phone (DataStore `consumed_{source}` set): global done-marking cannot work
      (two phones share rows — one phone's done would starve the other). Guards:
      videoId (in-tx) + title+artist pre/post-match, so overlapping pulls can neither
      duplicate nor scramble order.
  two devices pulling the same rows is safe by construction: inserts are idempotent
      (checkInPlaylist) and done-marking is idempotent — both phones
      converge, nothing duplicates, consumed rows never re-mirror
```

**Tables (new, Supabase Postgres):**
- `mirror_sources(id uuid pk, spotify_id text unique, name text, last_revision text, last_checked_at timestamptz, error text null)` — written by phone on track (anon insert), read/polled by Edge.
- `mirror_tracks(id uuid pk, source_id fk → mirror_sources, spotify_id text, title text, artist text, duration_ms int, created_at timestamptz, status text default 'pending')` — written by Edge (service_role), read + **marked done (update, never delete)** by phone. Unique `(source_id, spotify_id)` — re-polls never duplicate. Rows are never deleted: the poll diffs against all known URIs, so deleting a consumed row would re-mirror it on the next Spotify-side change.
- RLS: anon `select` on both; anon `insert` on `mirror_sources`; anon `update` on `mirror_tracks` (mark-done). Anon writes to `mirror_tracks` denied. Honest note: anon update is broad for a 2-user private project (worst case = rows wrongly marked done → re-mirror); tighten with per-source secrets later, not now.

**Phone link storage (DataStore, no schema change):** `localPlaylistId → {source_id, spotify_url, mode}`. Untrack removes the local link only; the server source row stays (one Spotify playlist may feed many playlists/phones — the phone cannot know if another link references it; an unreferenced source costs one 2-call revision-skip per minute). Songs already added stay — D7.

## 3. Phase plan — 4 phases, each shippable, each gated, approval between every one

Global gates per phase: `./gradlew :app:compileFossDebugKotlin` + `:app:testFossDebugUnitTest`
green, then the phase's device/numeric gate. One conventional commit per phase. Nothing
outside the phase's file list gets touched.

### Phase 1 — server: tables, poll function, schedule, deploy

Goal: a deployed Edge Function that any HTTP client can drive, proven with curl/Node
before the phone knows it exists.

Files (new, all under `supabase/` in this repo):
- `supabase/migrations/0001_mirror.sql` — `mirror_sources`, `mirror_tracks`, unique
  `(source_id, spotify_id)`, RLS exactly as §2.
- `supabase/functions/poll-spotify/index.ts` — dual cron+direct mode, one shared code
  path (mint → revision check → paginated read → diff → resolve → upsert). Cron ignores
  the response body; direct mode returns `{tracks: [{spotify_id, title, artist,
  duration_ms}]}`. Fail-closed: any Spotify error → `error` column + log, never delete.
- `supabase/functions/poll-spotify/deno.json` (if needed for imports).
- Cron: pg_cron 1-min job POSTing the function URL with the service-role key from the
  vault. Deploy via CLI with the management token (owner provides at build time).

NOT in this phase: any `app/` code, any phone dependency, FCM sending (stub the call
site with a logged TODO — Phase 2 wires the secret).

Gate (numbers): against the test playlist — poll twice, 2nd poll = 2 Spotify calls /
0 new rows; mark rows done, reset revision, re-poll → done rows excluded, only
truly-new returned; direct invoke returns rows in-response (small delta <10 s;
bulk capped at 100/invocation, measured 100 tracks ≈ 80 s). Commit `feat(mirror-server): supabase tables, poll function, schedule`.

### Phase 2 — phone intake: matcher, worker, pull paths, notify. No UI.

Goal: headless end-to-end (hardcoded test source id) — pull, match, insert, download,
mark done, notify — with the app closed, killed, and alive.

Files:
- Modify `app/build.gradle.kts` — Supabase anon key `buildConfigField` + supabase-kt
  `postgrest` + `functions` dependencies (ONLY additions).
- Modify `viewmodels/JsonImportViewModel.kt` — extract matching into new
  `utils/YoutubeMatcher.kt` (new, `@Singleton`/`@Inject`), zero behavior change;
  ViewModel delegates to it. Unit-test the extractor on fixed inputs.
- New `social/SpotifyMirrorRepository.kt` — Supabase client provider, link storage
  (DataStore `localPlaylistId → {source_id, spotify_url, mode}`), `pullPending()`,
  intake pipeline (match → `checkInPlaylist` → insert → `DownloadUtil` enqueue →
  mark rows done (`status='done'` update, never delete) → local notification D6), direct-invoke call, alive timers (10 s open /
  30 s alive, application-process scope).
- New `social/SpotifyMirrorWorker.kt` (`@HiltWorker`, mirrors `InvitePollWorker`
  pattern) — 15-min pull + expedited path.
- Modify `services/SongListenedMessagingService.kt` — handle `type=mirror_wake`
  data messages → expedited intake. Nothing else in that file changes.
- Notification channel + `POST_NOTIFICATIONS` handling for D6 (follow the existing
  notification code's pattern).

NOT in this phase: any menu/dialog/strings/UI, backfill UI, untrack UI, pull-to-refresh,
alive-timer wiring to screens (timers exist in the repository, screens hook them in
Phase 3). Test source id is a hardcoded constant.

Gate: unit tests green; device with airplane-mode kill mid-intake → restart converges,
zero dupes; FCM-triggered pull works from closed state (measure wake→insert seconds).
Commit `feat(mirror): phone intake worker`.

### Phase 3 — UI: track/untrack, choices, guardrails, alive hooks

Goal: the owner-designed setup flow, shippable to daily use.

Files:
- Modify `ui/menu/PlaylistMenu.kt` — "Track with Spotify" item (gated: existing
  playlists only, never during creation; shows "Untrack" when already linked) +
  untrack confirm.
- Modify `ui/screens/playlist/LocalPlaylistScreen.kt` — link dialog (validate
  `open.spotify.com/playlist/{id}`), mode-choice dialog (backfill-missing /
  future-only), backfill confirmation with counts (N Spotify / M already here,
  WiFi-only start gate), pull-to-refresh → immediate direct invoke.
- Alive-timer hooks: repository timers start/stop with tracked-playlist screen
  visibility (process scope preserved).
- `res/values/metrolist_strings.xml` — English strings only.
- Auto-untrack on local playlist delete (hook the existing delete path).

NOT in this phase: server changes, matcher changes, notification changes.

Gate: unit tests green; device on the real 1059 playlist future-only — add a song on
Spotify → lands in seconds alive / ~1–6 min closed, downloaded, notified; backfill
confirmation shows correct counts and respects the WiFi gate. Commit
`feat(mirror): track/untrack UI`.

### Phase 4 — verification pass (no planned code)

Goal: prove the matrix, fix only what fails (each fix its own `fix(mirror): …`
commit, re-gated).

- Timed 1059 backfill-missing: all missing arrive once, append order, zero dupes on
  re-pull (report minutes + songs/min).
- Kill app mid-intake AND mid-download: restart converges, no partial rows, no dupes.
- Untrack: source skipped on next poll; playlist keeps songs; re-track asks again.
- Spotify-side delete: no-op locally (D7), logged.
- Tracked playlist shared with partner: songs arrive on both phones via normal sync;
  mirrored adds indistinguishable from manual adds.
- End-to-end latencies reported as numbers: alive-foreground, alive-background,
  closed (each measured 3×).

## 4. Verification (device, both paths)

- Backfill-missing on a 1000+ playlist: every missing song arrives once, order = Spotify order of first appearance (append semantics), zero duplicates on re-pull.
- Future-only: pre-existing 1059 ignored; a song added on Spotify appears in **seconds alive (~13 s worst foreground-open, ~33 s background), ~1–6 min closed**, downloaded, notified.
- Kill app mid-intake and mid-download: restart converges, no partial rows, no dupes.
- Untrack: polls skip source; playlist keeps its songs.
- Shared playlist tracked: partner phone receives via normal sync.
- Timings reported as numbers (poll duration, intake duration, end-to-end latency).

## 5. Open defects / honest risks

- **Supabase egress untested** (residential proven; datacenter unknown). If Edge gets 403/429, fallback is the same code on any cheap host — the algorithm is portable.
- **First backfill of a 1000-track link ≈ 15 min** of per-track embeds (measured pace) — one-time, background, fine.
- **FCM data path is best-effort** (secret handling + Deno JWT); WorkManager 15-min poll is the guarantee. Worst case closed-app freshness ~6 min beyond the poll floor per D9.
- **Match quality is title+artist search** (owner-accepted, D3) — wrong-video mismatches possible, same as JSON import today; no review queue by decision.
- Spotify may change internal endpoints (no contract) — the poll function must fail closed (log + `error` column) and never delete phone data.
