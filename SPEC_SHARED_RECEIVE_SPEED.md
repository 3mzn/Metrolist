# SPEC_SHARED_RECEIVE_SPEED — parallelize shared-playlist receive

> Status: **COMPLETE — shipped in 13.10.1.** All phases implemented, audited, device-tested.

## 0. Context for an agent with no prior history

This repo (`C:\musicapp\metrolist`) is a private two-person fork (eman + aswini) of the
Metrolist YouTube Music client (Kotlin, Compose, Room, Hilt, Firebase). SPEC_8 ("Us"
playlists, see `SPEC_8.md`) syncs shared playlists through Firestore: one doc per
playlist at `sharedPlaylists/{playlistId}` (doc id = the local Room playlist id),
`songs` is a flat array of YouTube video IDs, last-writer-wins. Local side is Room v39:
`PlaylistEntity.sharedWith: String?` (null = local-only) plus `PlaylistSongMap`
rows. Positions are per-phone and never synced (SPEC_8 D21).

Key files (all paths under `app/src/main/kotlin/com/metrolist/music/`):
- `social/SharedPlaylistRepository.kt` (831 lines) — the whole sync engine.
- `social/SharedPlaylistSyncListener.kt` (87 lines) — app-lifetime collector.
- `social/SharedPlaylistModels.kt` (66 lines) — `SharedPlaylistCloud.fromMap`.
- `db/entities/PlaylistEntity.kt` — `sharedWith` column + `isShared` getter.
- `db/entities/PlaylistSongMap.kt` — map rows; FK CASCADE to playlist AND song
  (deleting a playlist cascades its map rows; inserting a map row for a missing
  song id throws `SQLiteConstraintException` — this crashed the 408-song test).
- `firestore.rules` — `sharedPlaylists` block (members read/write, `sharedByUid`
  immutable) and `partners_deleted` tombstones. Already deployed; no rule changes here.
- House precedent for bounded parallelism: `viewmodels/JsonImportViewModel.kt`
  (`Semaphore(4)` + `Mutex`, needs explicit `import kotlinx.coroutines.sync.withPermit`)
  and `playback/DownloadUtil.kt` (lyrics backfill, `Semaphore(6)`).
- Tests: `./gradlew :app:compileFossDebugKotlin -q` must print COMPILE OK;
  `./gradlew :app:testFossDebugUnitTest` stays green (`SharedPlaylistModelsTest`).

## 1. Problem

`reconcileLocal` (`SharedPlaylistRepository.kt:552`, song diff at `:626-644`) adds
missing songs strictly sequentially (`:632`: `toAdd.forEach { addSongLocally(...) }`).
Each song without local metadata costs a full `playerResponseForMetadata` network
resolve before the next starts (~1–3s); each song also gets its own Room transaction,
recomposing the playlist screen ~once per song. Measured shape: ~1 min for 1000
already-known songs, 10–20+ min for hundreds of cold ones (the 408-song test).
The Firestore doc itself arrives in milliseconds — all delay is local fan-out.

## 2. Requirements

1. **6-wide song parallelism.** Run each playlist's `toAdd` loop under a shared
   `Semaphore(6)`, same shape as the lyrics backfill.
2. **Concurrent shares must work perfectly.** Sharing playlist B while playlist A is
   still receiving must not corrupt, stall, or duplicate anything on either playlist.
3. **Batch the map inserts** into as few transactions as practical (arrival speed is
   parallelism; display smoothness is batching).
4. **No behavior change:** same diffing, same D21 local-only positions, same negative
   paths (NOT_FOUND handling, D8 survivor, D28 symmetric delete).

## 3. Design (locked)

- **One repository-level semaphore (6 permits total)** shared by all reconciles, not
  one per playlist — otherwise two concurrent shares would run 12-wide against
  rate-limited lyric/metadata endpoints.
- **One mutex per playlist ID** (`MutableMap<String, Mutex>`, entries removed when
  uncontended) held across that playlist's whole `reconcileLocal`. Different
  playlists proceed concurrently; overlapping emissions for the SAME playlist
  serialize. This is load-bearing: `playlist_song_map` has NO unique constraint on
  `(playlistId, songId)`, so two interleaved reconciles of one playlist could both
  pass the `checkInPlaylist == 0` guard and insert duplicate rows. Never hold a
  playlist mutex across a network fetch — acquire it only around the check+insert
  critical section (same pattern as JSON import's `dbMutex`), or hold it across the
  whole reconcile but release around fetches; simplest correct shape is per-song
  check+insert under the mutex with fetches outside it.
- Keep every insert inside `database.withTransaction` (the `f9d759929` FK fix):
  song row first (re-check `getSongByIdBlocking` inside), map row second.
- `SharedPlaylistSyncListener` needs NO structural change for concurrency if the
  per-doc loop stays sequential — but then playlist B waits for playlist A. To meet
  requirement 2 with real overlap, launch each doc's `reconcileLocal` as a child
  coroutine of the collect block (still `collect`, never `flatMapLatest` — cancelling
  an in-flight reconcile would strand half-applied state). The per-playlist mutex
  above is what keeps this safe.
- `recordIncomingAdditions` (badge accounting) stays sequential per emission, before
  the concurrent fan-out — badge math must see a stable previous snapshot.
- Optional, undecided: sync progress count on the playlist card. Receives are
  currently silent, so "stuck" and "working" look identical.

## 4. Verification

- Timed receive of a 400+ song share with cold metadata: expect ~6x wall-time
  improvement vs the sequential baseline.
- Overlap test: start receiving a 400-song share, then share a second playlist
  mid-receive. Both must complete with exact song sets (compare cloud `songs[]`
  against local `playlistSongIds` per playlist — zero missing, zero duplicates).
- Playlist screen stays smooth during bulk receive (no thousand-recomposition stutter).
- Re-run SPEC_8 checklist spot items (add/remove/rename/delete) — sync semantics
  untouched. KILL the app mid-receive once: restart must converge idempotently.

## 5. Follow-up: per-song PoToken mint was the real ceiling (fixed in 13.10.0-TEST+)

After phases 1–3 shipped, device testing showed 100 songs in 33–50s (2–5 songs/sec,
inconsistent) — well below what 6-wide parallelism should deliver.

Root cause: every `playerResponseForMetadata` call minted a fresh per-video PoToken via
`PoTokenGenerator.getWebClientPoToken(videoId, sessionId)`. All 6 lanes serialized through
the single shared PoToken WebView (one `evaluateJavascript` thread + Main dispatcher), and
worse, the per-video token was DISCARDED — metadata requests only ever send
`playerRequestPoToken`, which is the session-level streaming pot, identical for every song.
Each song paid a serialized WebView round-trip for nothing.

Fix (`64c63f503`): new `PoTokenGenerator.getSessionPoToken(sessionId)` returns the cached
session pot without per-video minting; `YTPlayerUtils.playerResponseForMetadata` uses it.
The per-video entry point was refactored into a shared guarded wrapper + extracted
`ensureSessionGenerator` with byte-identical timeout/cleanup/retry behavior — the playback
path cannot tell the difference. Wire behavior is unchanged (same token string sent).

Measured: 93 songs in 4s (~23/s); 493 songs in 43s (~11.5/s). Rolled into 13.10.1.
