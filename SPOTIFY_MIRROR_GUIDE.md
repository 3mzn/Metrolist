# Spotify Auto-Mirror — Feature, Build History, Issues & Runbook

Owner-built YouTube Music mirror: link any local playlist to a public Spotify
playlist URL and its songs arrive automatically (backfill existing + track future
additions), matched onto YouTube Music, with a review list for misses.

Status: shipped in **13.11.0 (178)**. Parent spec: `SPEC_SPOTIFY_MIRROR.md`
(decisions D1–D8, as amended). This file is the narrative companion: how it was
built, everything that broke, and how to operate it.

## 1. User flow

1. Long-press an existing playlist → **Track with Spotify** → paste link.
2. Choose **Add missing songs** (confirm dialog with Spotify-vs-local counts,
   WiFi required) or **Only new songs** (links instantly, seeds in background).
3. Songs arrive progressively with one notification per batch. Anything that
   can't be matched lands in the **review list**: banner in the playlist
   ("N Spotify songs couldn't be mirrored — Review") → per-row **Retry** /
   **Remove** (+ Retry all, Undo on remove, skipped-date per row).
4. Untrack from the same long-press menu. Retried songs land in Spotify order;
   removed review rows stay hidden on both phones.

## 2. Architecture

- **Client** (`social/SpotifyMirrorRepository.kt`, `utils/YoutubeMatcher.kt`):
  single-flight intake mutex, 6-wide match semaphore, 50-row insert
  transactions, per-device per-playlist consumption sets (DataStore),
  in-memory transient backoff (10 s → 10 min), `mirror_skip` Room table (v40).
- **Server** (`supabase/functions/poll-spotify/index.ts`): token mint →
  spclient page walk (200/page, ≤40) → per-new-track resolve (title/artist/
  duration, max 100/invocation, 300 ms spacing) → `mirror_tracks` upsert;
  revision stamp short-circuits unchanged polls; poison table parks
  repeatedly-unresolvable URIs; `expected_total` guards subset reads.
- **Tables**: `mirror_sources` (id, spotify_id, last_revision, expected_total,
  error/backoff columns), `mirror_tracks` (unique source+spotify, title/artist/
  duration_ms), `mirror_skipped` (poison), `mirror_dismissed` (cross-device
  review dismissals, union semantics).
- **Triggers**: alive loop (10 s open screen / 30 s bg / 2 min screen-off),
  15-min worker (CONNECTED), pull-to-refresh (full intake + result snackbar),
  FCM wake (best-effort only).

## 3. Matching pipeline (mirror path only; JSON import keeps legacy first-hit)

Per row: exact query → stripped fallback (`[...]`, `(...)`, `- tail` removed;
artist always kept) → **artist gate** (folded tokens: Topic/VEVO stripped,
collab/`and`/feat split, diacritics + leet folded; blank artist = gate off) →
**title gate** (folded equality both sides) → page 2 fetched when page 1 yields
nothing usable (or nothing in the duration window) → **duration pick** (±10 s,
audio over video, closest, studio over live last). 2 attempts for errors;
empties move on immediately (rate-limit hygiene). Manual Retry adds top-hit
leniency (same folded title + duration ≤10 s) for collab-credit splits.
Outcomes: Found → insert; NotFound → consumed + skip; NetworkError →
unconsumed + backoff, retried forever, never listed.

## 4. Build history

- **Phase 1 + Fix-A/B/C** (parent spec): worker, backoff timetables, poison
  table, exact counts, FCM wake, probe/confirm dialogs.
- **M1 match quality**: stripped fallback queries + duration preference
  (measured rescue of the hard tail on a 1059-track playlist).
- **M2 review list**: `mirror_skip` (v39→v40 auto-migration), banner, review
  screen, retry/remove/retry-all, auto-resolve on manual add, dismiss-silence.
- **Key saga (phantom)**: a regex splitting Kotlin's closing `\"` escape made
  me test a key *fragment* → phantom 401s → "fix" that netted to zero
  (working tree == HEAD hash proved it). Lesson: verify string boundaries
  byte-wise before concluding; the key was always fine.
- **Server strand (Fix-D)**: spclient can end the page walk early *and* report
  the subset length as total → revision advanced on a subset → tail stranded
  behind `skipped_unchanged` forever (59 rows). Fix: `expected_total`,
  reset on revision change, subset reads fail closed. Unstranded via
  revision reset; one poll resolved all 59.
- **pullAll pagination**: PostgREST caps single responses at 1000 rows
  (`max-rows`) regardless of requested limit. `pullAll` asked `limit=2000`
  and silently missed every row past 1000. Offset loop added (backfill
  already had one). Verified live: `55/1059 added` on the next tick.
- **Artist gate** (after measuring 165/1017 wrong-artist inserts — covers,
  karaoke, 8D/8-bit uploads; gate sim rejected 165/165, missed 0).
- **Title gate** (the "safety net" hole: a same-artist *different* song passed
  the artist gate, then the insert dedupe ate it silently — consumed, no song,
  no skip). Candidates now need folded-title equality too.
- **Audit batches → 13.11.0**: per-playlist consumption (H1), poison 25×/24 h
  (H2), pull runs full intake with feedback (H3), metered pause (M1), remove
  undo (M2), pull indicator + snackbars (M3), retry lands in Spotify order
  (M4), loading state (M5), plurals (M6), post-IO toasts (S1), empty-break
  (S2), in-window page-2 (S3), links fallback (S4), live downrank (S5), probe
  max (S6), 50 s resolve deadline (S7), skip dates (S8), cross-device
  dismissals (S9).

## 5. Issue log (every one hit during build/test)

| # | Symptom | Root cause | Fix | Verified |
|---|---|---|---|---|
| 1 | 97/1059 missing, no review | Rows never resolved server-side (strand) | Fix-D + revision reset | `added=59, remaining=0` |
| 2 | Intake frozen, no logs | Phone WiFi outage (ConnectTimeout) + stale pre-install process serving old code | Force-stop; documented | Ticks resumed |
| 3 | 165 wrong-artist songs | First-hit pick, no gates (D3) | Artist + title gates | Sim 165/165 rejected |
| 4 | safety net vanishes (consumed, no song, no skip) | Same-artist wrong hit → insert-dedupe silence | Title gate | Landed on relink |
| 5 | Review Retry/X crash | DB on main thread | Dispatchers.IO | Installed, tapped |
| 6 | `\u0026` in review rows | Old-parser server rows | Repaired 6 rows server-side + client unescape | Clean display |
| 7 | Dance Monkey retry looped | Crashed before searching (see 5), then matched | — | Landed |
| 8 | Talk/Retronaut unmatchable | Collab credited per-side; absent from 2 pages (40 hits) | Lenient retry (title+duration) | Landed |
| 9 | Ace/Motörhead held | Same title, different song (13 s apart) | Correctly rejected — review is right | By design |
| 10 | thank u, next wrongly in | Pre-gate insert for the 34+35 row | Gate (relink landed correct 34+35); stale row hand-removed | Positions verified |
| 11 | Rock That Body skip vanished, no song | Unexplained (diags rotated before capture) | Insert diagnostics kept until seen again | Open |
| 12 | Glowing/Better Stereo rejected | Splitter lacked " and " | Added (both splitters) | Better landed |
| 13 | Pull-to-refresh "does nothing" | No indicator + direct-only path (new-rows-only) | Indicator + snackbar + full-intake path | Installed |
| 14 | Empty-review flash on open | `initialValue = emptyList()` | Null-initial + loader | Installed |
| 15 | Same URL on 2 playlists cross-talk | Consumption keyed per-source | Per-playlist keys + one-time adoption | Installed |
| 16 | Untrack wipes sibling progress | Same shared key | Scoped by playlist (with above) | Installed |
| 17 | Outage parks songs 7 days | Poison 5× on any failure incl. transient | 25× / 24 h | Deployed |
| 18 | 59 tail invisible to phone | pullAll 1000-row cap | Offset pagination | `55/1059 added` live |
| 19 | Temp-spec + CLI junk hygiene | — | Deleted; `.gitignore` covers `supabase/.temp/` | Committed |

Open / accepted: Rock That Body hole (11); JSON import keeps legacy first-hit (owner deferred); FCM closed-app arrival unproven (worker is the guarantee); transliterated credits stay in review (no safe signal); listen-preview in review deferred (dead route); "1 songs"-class strings in other locales fall back to default (fine).

## 6. Decisions (amendments to parent D3/D4)

- D3 amended: artist + title gates; review list with retry/remove; manual-retry top-hit leniency.
- D4 (no auto-download, streaming only) held throughout.
- Dismissals: union across phones (dismiss shared, undismiss per-device).
- Matching pauses on metered connections (backfill keeps its WiFi gate).
- Transients never enter review (automatic backoff owns them).

## 7. Operations runbook

- **Deploy function** (Management-API deploy is broken only if run from the
  wrong dir): from repo root,
  `supabase functions deploy poll-spotify --project-ref teeafutbybbywitdahpr`
  with `SUPABASE_ACCESS_TOKEN` = `~/.config/supabase-management-token`.
  Verify with a direct invoke (`added_total`).
- **SQL without dashboard**: Management API query endpoint
  `POST /v1/projects/<ref>/database/query` with the same token, **one
  statement per call**. Migrations live in `supabase/migrations/` (0001–0004).
- **Schema note**: `expected_total` (0003) and `mirror_dismissed` (0004) were
  applied live statement-by-statement; files capture them for fresh setups.
- **Release**: `assembleFossRelease` → verify versionCode via `aapt2 dump
  badging` → sign with `apksigner.jar` (`--ks-pass pass:...`, space form —
  the `.bat` mangles colons) → `gh release create vX --repo 3mzn/Metrolist`
  → `node push-update.js <RELEASE apk>` (never the 54 MB debug build —
  bucket 413s over ~27 MB…and debug is bigger).
- **Device**: `ylwwmn85w4ifb6z9`; debug APK reinstall kills the process, but a
  stale process serves old code — force-stop after install when behavior
  looks old. USB drops often; wait/replug rather than debugging ghosts.
- **DB forensics**: `run-as … cat databases/song.db` + `-wal` via `cmd /c`
  redirect (PowerShell `>` corrupts binary via UTF-16); DataStore
  `files/datastore/settings.preferences_pb` parses as proto map.
- **Tokens** (never print): `~/.config/supabase-management-token`,
  `~/.config/supabase-key` (service_role — repairs), `~/.config/supabase-anon-key`,
  `~/.config/firebase-service-account.json`.
- **Surgical unstrand**: `UPDATE mirror_sources SET last_revision = NULL
  WHERE id = '<uuid>'` → next poll re-diffs fully.
- **Key lesson**: PostgREST single-response cap (1000) — always page counts;
  single requests lie by omission.
