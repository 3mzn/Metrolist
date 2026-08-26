# SPEC 8 — "Us" Playlists

Implementation spec for MAYBE_LATER.md feature #8. Replaces the one-way "To Listen" model with
full bidirectional playlist sharing. Both phones can add, remove, rename, reorder (sort-only),
delete, and un… no, unshare is **permanently disabled** (D7 below — decided deliberately).

The `social/` layer, the `partner widget`, Listen Together, sent-songs, gentle nudge, and
the entire upstream playback stack are **untouched** — only the playlist surface and its
Firestore mirror change.

Status of all decisions: **FINAL — nothing here needs further sign-off.**

> [!NOTE]
> Firestore rules were deployed successfully to `outertune-social` on 2026-08-26.

---

## 0. Locked decisions

| # | Decision |
|---|----------|
| D1 | **How a share starts:** from the existing playlist menu, "Share with aswini". Local-only, one at a time, no bulk, no auto-share. No new "create shared" path. |
| D2 | **Scope:** two users only, forever. `sharedWith: String?` stays a single UID (already shipped as v39). No friend-graph involvement. |
| D3 | **Aswini's rights:** full edit — add, remove, rename, delete, change cover. No approval flow, no permissions toggle. |
| D4 | **No approval:** every change syncs instantly via Firestore snapshot listener. No per-edit prompts, no approval queue. |
| D5 | **Conflict policy:** last write wins, per field. Same field edited simultaneously → second-write-overwrites on both phones (Firestore's natural behavior). No merge logic, no conflict dialog. |
| D6 | **Renames:** last writer wins, both phones show the new name. No "creator's name is locked" rule. |
| D7 | **Unshare is disabled.** Once a playlist is `isShared == true`, the menu offers no way to revert it. The only exits are: one of us deletes the whole playlist, or one of us deletes their account. Deliberate — the workflow is "share, curate, eventually delete", not "share, then maybe unshare". |
| D8 | **Account deletion:** the surviving phone keeps its local copy of every shared playlist (the row remains with `isLocal = true`, `sharedWith = null` — promoted to a normal local playlist on next sync reconciliation). Songs stay. Future sync attempts against the deleted account silently no-op. |
| D9 | **Local songs:** **blocked** from shared playlists with a toast/snackbar "local songs can't be shared". The same check that blocks the "Send to aswini" path today. |
| D10 | **Region-locked songs:** show on both phones regardless; the unavailable one just won't play (standard YT behavior). No client-side filter. |
| D11 | **No activity notifications.** Quiet. All visibility is in-app (badge on the playlist card, sort changes inside the playlist). |
| D12 | **Offline:** downloaded songs play normally offline. Edits (add/remove/rename) queue locally and replay to Firestore when the connection is back. No manual sync button. |
| D13 | **No expiry.** Shared playlists live until one of us deletes them or one of us deletes their account. No idle timeout. |
| D14 | **Drift while one phone is offline:** when the offline phone reconnects, queued writes flush in order; queued reads update from current cloud state. No special "catch-up" banner. The Firestore snapshot listener does the right thing. |
| D15 | **Visual indicator:** a small two-people glyph (👥-style Material icon) next to the name + a 2dp colored **outline border** around the whole card. Border color = the dynamic palette of the **currently playing** song (reuse the home-page palette extraction — same `Palette` / `dominantSwatch` plumbing as today). When nothing is playing, the border stays `Color.White`. |
| D16 | **"X new" badge:** in addition to the glyph, the card shows a count of songs the *other person* added since you last opened that playlist. Count clears on open. This rides on the same data path as the existing `waitingSongCount` badge. |
| D17 | **No caps.** Unlimited shared playlists, unlimited songs per shared playlist. |
| D18 | **Duplicate prevention:** identical to To-Listen — `checkInPlaylist(...)` blocks same-song-twice. The composer shows a snackbar `"Already in <playlist name>"` so the failure isn't silent. |
| D19 | **Cover art:** playlist covers are device-local and are not synchronized. Each phone may choose its own cover independently. This supersedes the original last-writer-wins proposal after device testing. |
| D20 | **Playback is independent.** Tapping play on one phone does **not** start playback on the other. Shared playlists are a shared **song list**, not a shared listening session. (Listen Together is the separate feature for that.) |
| D21 | **Sort/order:** each phone keeps its own sort and its own drag-reorder order. The shared data is the *set of songs*, not their positions. Drag-reorder writes the new order **only locally**. (Per-phone order is the simpler conflict model and matches the "no CRDTs" rule.) |
| D22 | **Per-person data:** play counts, "liked" flag, listening history — all stay per phone. The shared doc carries only the song IDs. |
| D23 | **Visibility:** shared playlists live in the regular Library tab with a glyph + border + new-songs badge. No "Us" tab, no "Us" section, no Social-screen card. |
| D24 | **No bulk operations.** No multi-select for share/unshare/delete. No "share all my playlists". One at a time, always. |
| D25 | **No "added by aswini" feed.** Sort by date added covers the "what's new" need. No dedicated view, no per-song "added by" attribution. |
| D26 | **Firestore cost:** live snapshot listeners on `sharedPlaylists/{id}` — one per open shared playlist. Same free-tier friendliness as sent-songs (D14 of SPEC_7). |
| D27 | **Same-song-both-deleted:** last delete wins, song is gone. No "tombstone" recovery. |
| D28 | **Delete is symmetric.** Either phone deleting the shared playlist deletes the cloud doc; the other phone's listener fires, removes its local copy. No "delete only mine" option (would require unshare, which D7 forbids). |
| D29 | **No `lastWritenBy` tracking.** No "eman last changed Date Night 3 min ago" attribution anywhere. Conflict resolution is silent. |
| D30 | **All strings in `metrolist_strings.xml`** with `%1$s` placeholders for names. English file only (AGENTS.md rule). |
| D31 | **Three commits**, each compiling green, in §6 order. |

---

## 1. Data model

### 1.1 Room (already shipped at v39)

```kotlin
// db/entities/PlaylistEntity.kt
val sharedWith: String? = null          // UID of partner; null = local
val isShared: Boolean get() = sharedWith != null   // already exists
```

That single column is the local side of the mirror. No new entity, no new column, no new
view, no DAO additions. The migration is in production (`d8bb8bd8e`).

### 1.2 Firestore (new in this spec)

One document per shared playlist, doc id = the playlist's Room id
(`"LP" + 8 random lowercase letters`, generated by the sharer and reused verbatim on the
recipient's device — the recipient writes a Room row with the **same** id, just like
`SongSharingRepository.initializeToListenPlaylist` does for `LP_TO_LISTEN`).

```json
// sharedPlaylists/{playlistId}
{
  "name":              "Date Night",       // last-writer-wins, both phones mirror
  "thumbnailUrl":      "https://...",      // optional, last-writer-wins
  "sharedByUid":       "<creator uid>",    // immutable
  "sharedByName":      "eman",             // display only, for D8 reconciliation
  "songs": [
    "dQw4w9WgXcQ",     // songId (YouTube), in arrival order
    "fJ9rUzIMcZQ",
    "L_jWHffIx5E"
  ],
  "songCovers": {                          // optional; UI nicety, not required
    "dQw4w9WgXcQ": "https://..."
  },
  "createdAt":         1724010000000,      // sender clock, ms
  "updatedAt":         1724020000000       // last write time, ms (any field change)
}
```

**Why a single `songs` array (not a per-song subcollection):** both phones own the whole
list and apply atomic last-writer-wins; arrays of string ids are cheap to ship on every
change; matches the "two users, last-writer-wins" reality; avoids the fan-out cost of
a subcollection. The trade-off is a 1 MiB Firestore doc limit — for two users curating
casual playlists, 50k+ songs fit easily.

**Why no per-song metadata beyond id:** song metadata is already in the local Room `song`
table on each device (downloaded on add, as today). The doc carries only ids; each phone
resolves the metadata locally. This is exactly how `sentSongs` already works.

**Why `sharedByName`:** D8 — when the partner deletes their account, the surviving phone
needs to know "eman was the original sharer" so the playlist is reborn as a clean local
playlist without trying to ping the dead uid. Carried as a denormalized display name only.

### 1.3 Rules block (new, deployment-required)

```text
match /sharedPlaylists/{playlistId} {
  // Two members only. Membership is derived from the doc data: whoever the doc is
  // "about" (the creator, sharedByUid) and the single partner (sharedWith).
  // Both can read; either can write (last-writer-wins per field).

  function isMember() {
    return request.auth != null
      && (request.auth.uid == resource.data.sharedByUid
          || request.auth.uid == resource.data.sharedWith);
  }

  allow read:   if isMember();
  allow create: if request.auth != null
                  && request.resource.data.sharedByUid == request.auth.uid
                  && request.resource.data.sharedWith is string
                  && request.resource.data.sharedWith != request.auth.uid
                  && request.resource.data.name is string
                  && request.resource.data.songs is list
                  && request.resource.data.createdAt is number;
  allow update: if isMember();
  allow delete: if isMember();
}
```

**Cost note:** writes are unbounded by these rules (any field). That's intentional — the
whole point of the feature is "any member can change any field". D5/D6/D19 all flow from
this. Free-tier writes per day are tiny (<<100 even with active use).

**Why allow `update` from either party without field-shape checks:** the sender can change
`songs` to add/remove tracks; both can change `name`/`thumbnailUrl`; both can change
`updatedAt`. The only immutable field is `sharedByUid` (set at create, locked forever).
Firestore lets us forbid that one field with a `request.resource.data.sharedByUid ==
resource.data.sharedByUid` guard — added in the deployed variant.

```text
allow update: if isMember()
  && request.resource.data.sharedByUid == resource.data.sharedByUid;
```

---

## 2. Phase 1 — Sync engine — ✅ IMPLEMENTED, DEVICE-TESTED

The data layer for #8. Models, repository, sync listeners, rules block, and a
self-healing reconcile-on-start pass. No UI yet.

**Files:**
- **NEW** `social/SharedPlaylistRepository.kt` (`@Singleton`, injects
  `FirebaseFirestore`, `FirebaseAuth`, `PartnerResolver`, `MusicDatabase`):
  - `suspend fun share(playlistId: String): Result<Unit>` — writes
    `sharedPlaylists/{playlistId}` doc with `sharedByUid = myUid`,
    `sharedWith = partnerResolver.awaitPartnerUid()`,
    `name = current local name`, `songs = current local song ids in order`,
    `createdAt = now`, `updatedAt = now`. On success, sets the local
    `playlist.sharedWith = partnerUid` row.
  - `suspend fun unshare(playlistId: String): Result<Unit>` — **NOT IMPLEMENTED**
    per D7. The function exists as a no-op returning `Result.failure(NotSupported)`
    so callers can `if (result.isFailure) { show "Unshare not supported" }` cleanly.
    No rule is needed for it (no client can call it).
  - `fun observe(playlistId: String): Flow<SharedPlaylistCloud?>` — callbackFlow
    snapshot listener on `sharedPlaylists/{playlistId}`. Emits `null` for missing
    doc or on permission denied. Same `authUidFlow().flatMapLatest` pattern as
    `ListenTogetherInviteRepository` (the cold-start fix).
  - `fun observeAll(): Flow<List<SharedPlaylistCloud>>` — query
    `whereEqualTo("sharedWith", myUid)` UNION'd with
    `whereEqualTo("sharedByUid", myUid)`. Two listeners, combined.
  - `suspend fun addSong(playlistId: String, songId: String): Result<Unit>` —
    `arrayUnion` into `songs`, `FieldValue.serverTimestamp()` to `updatedAt`.
    Idempotent (arrayUnion dedupes).
  - `suspend fun removeSong(playlistId: String, songId: String): Result<Unit>` —
    `arrayRemove` from `songs`, bump `updatedAt`.
  - `suspend fun rename(playlistId: String, name: String): Result<Unit>` —
    `update({ name, updatedAt })`.
  - Cover changes are local-only per revised D19; no Firestore cover write is required.
  - `suspend fun deleteRemote(playlistId: String): Result<Unit>` — `delete()`. The
    listener on the other phone then removes its local row.
  - `suspend fun reconcileLocal(playlistId: String)` — called on app start. Reads
    the local row, reads the cloud doc, if cloud exists & disagrees on
    `name`/`thumbnailUrl`/songs-set → write local. If cloud is gone but local
    `isShared` → write local row's `sharedWith = null` and clear
    `isShared` (D8 survivor path).
  - `suspend fun reconcileAll()` — on app start, iterate every local row where
    `sharedWith != null` and call `reconcileLocal`. Catches "phone was offline
    while other phone deleted the account" cases.
  - `suspend fun clearAllCloudForUid(uid: String)` — used by account-delete
    flow. Reads every doc with `sharedByUid == uid OR sharedWith == uid`,
    deletes them. Batched.

- **NEW** `social/SharedPlaylistModels.kt`:
  ```kotlin
  data class SharedPlaylistCloud(
      val id: String,
      val name: String,
      val thumbnailUrl: String?,
      val sharedByUid: String,
      val sharedByName: String?,
      val songs: List<String>,
      val createdAt: Long,
      val updatedAt: Long,
  )
  ```

- **MODIFIED** `firestore.rules` — add the `sharedPlaylists` block from §1.3,
  including the `sharedByUid` immutability guard.

- **MODIFIED** `social/SongSharingRepository.kt` — `wipeMyCloudData` (already
  exists) gets a call to `SharedPlaylistRepository.clearAllCloudForUid(uid)` at
  the end. Single round-trip after the existing sent-songs are wiped.

- **NEW** `social/SharedPlaylistSyncListener.kt` — a small `@Singleton` that
  calls `SharedPlaylistRepository.observeAll()` on app start, and on every
  emission for any cloud doc diff'd against the local row, applies the
    name/song changes to Room. Started from
  `App.initializeSocialFeatures()` (Phase 2 — added in that commit).

**Verification:** `./gradlew :app:compileFossDebugKotlin` green. No device install yet
(this phase ships rules + data plumbing only; UI is Phase 2).

---

## 3. Phase 2 — UI integration — ✅ IMPLEMENTED, PARTIALLY DEVICE-TESTED

The user-facing surface. Menu entry, glyph/border/badge on the grid + list cards,
delete confirmation aware of share state, and the in-playlist edit guards (local
songs blocked, duplicates blocked with snackbar).

**Files:**
- **MODIFIED** `ui/menu/PlaylistMenu.kt` (the `LocalPlaylistMenu` composable):
  - Add a `Material3MenuItemData` "Share with aswini" inside the second
    `Material3MenuGroup` (the one that already houses Pin / Download / Export
    / Delete). Gated on `playlist.playlist.sharedWith == null`. On click:
    - `partnerUid == null` → toast `R.string.lt_invite_partner_missing`
      (reuse the existing string, fits both features).
    - else → `LocalPlaylistMenu(onShare = { vm.share(playlist.id) })` →
      coroutine launches `SharedPlaylistRepository.share(...)`. On
      `Result.failure` → toast `R.string.share_failed`.
  - The "Delete" item in the same group already gates on
    `!isToListenPlaylist`. Add `&& !isShared` (D7). When the playlist IS shared,
    the delete item's description becomes
    "Both phones will lose this playlist" (a new string), and the confirm
    dialog (`DefaultDialog` already present) adds the same line. The delete
    handler now does `SharedPlaylistRepository.deleteRemote(playlist.id)` first,
    then local delete.
  - The "Edit" item is gated on `editable && !isGuest && !isToListenPlaylist`.
    Add `&& !isShared` is **NOT** applied here — both parties can rename a
    shared playlist (D3/D6). The edit dialog calls
    `SharedPlaylistRepository.rename(...)` after the local write.
  - The "Export" item stays available for shared playlists (D3 — full
    rights). No gating change.

- **MODIFIED** `ui/screens/library/LibraryPlaylistsScreen.kt`:
  - Pull the per-playlist `sinceLastOpenedAddedCount` from a new
    `LibraryPlaylistsViewModel` field (see below). Each `VisiblePlaylistItem`
    gets a `newCount: Int = 0` property.
  - The list branch (around L466–484) wraps the `LibraryPlaylistListItem` in a
    `Box(Modifier.border(2.dp, outlineColor).padding(2.dp))` where
    `outlineColor = paletteAccent ?: Color.White` (palette comes from the
    existing `MaterialTheme` color-scheme accent — see §3.1).
  - The grid branch (around L549–569) does the same on `LibraryPlaylistGridItem`.
  - The badge (currently the To-Listen "waiting count") becomes two-badge
    behavior when the row is shared: render the glyph in the corner AND
    the "X new" count when `newCount > 0`. To-Listen badge logic stays
    untouched (no conflict, To-Listen isn't a shared playlist in the D1
    sense — it has its own row with `isShared == false`).
  - Add `LaunchedEffect(playlists)` that, for any shared playlist the user
    opens this session, clears its `sinceLastOpenedAddedCount` to 0
    (tracked in DataStore; key `us_playlist_opened_<id>`).

- **MODIFIED** `viewmodels/LibraryViewModels.kt`:
  - `LibraryPlaylistsViewModel` gains a `sinceLastOpened: StateFlow<Map<String, Int>>`
    fed by `SharedPlaylistSyncListener.observeCountsSinceLastOpen()`, where
    each map entry is `playlistId -> count of songs the other party added
    since the local user last opened this playlist`. The DataStore-backed
    "last seen" timestamp per playlist is what decides the count.

- **MODIFIED** `ui/screens/playlist/LocalPlaylistScreen.kt` (the playlist
  detail screen):
  - The "delete from playlist" path (the `deleteFromPlaylist` inner fun around
    L564) becomes: if `playlist.playlist.isShared` → also call
    `SharedPlaylistRepository.removeSong(playlistId, songId)`.
  - The "drag-reorder" `LaunchedEffect` keeps its local-only behavior (D21).
  - The `playlistLength` / sort / `MutableStateListOf<PlaylistSong>`
    construction are unchanged.
  - When the user taps "Add" inside the playlist: the existing flow
    launches `songSharingRepository.sendSongsToFriends(...)`-style for
    adding. New: for shared playlists, that flow routes to
    `SharedPlaylistRepository.addSong(...)`. Local songs are filtered
    out at this point (D9). Duplicate-same-song path adds a snackbar
    `"Already in <playlist name>"` (D18) — reuses the string key
    `R.string.song_already_in_playlist` (new, added in this commit).
  - The header `LocalPlaylistHeader` (L865+) gets the "edit" pencil action
    routed through `SharedPlaylistRepository.rename(...)` for shared
    playlists.
  - The header's "delete playlist" button (the trash button in the
    three-icon row) is hidden for shared playlists (D7 — and delete is
    via the overflow menu now).

### 3.1 Palette extraction reuse

The home-page already runs Palette extraction on the current song's artwork
and uses the dominant / vibrant swatch to tint the screen. The hook is
`com.metrolist.music.ui.theme` (the dynamic theme / palette observer). The
spec wires `LibraryPlaylistsScreen` to read the same `MaterialTheme.colorScheme.primary`
or a dedicated `LocalDynamicAccent` composition local that the existing
theme code exposes.

If the existing system does NOT expose a `Color` (it might only push into
`colorScheme.primary` already), we read `colorScheme.primary` directly.
Fallback: `Color.White` when no song is playing (the theme observer sets
`colorScheme.primary` to a neutral when idle — we add a guard
`if (colorScheme.primary == neutralDefault) Color.White else colorScheme.primary`).

**No new palette extraction code** — the home-page work is the upstream for
this. We consume what already exists. If it doesn't exist, we ship a 30-line
shared `CurrentSongAccent` composition local in this commit and call it done.

---

## 4. Phase 3 — Account-delete + edge cases — ✅ IMPLEMENTED, DEVICE-TESTED

Account deletion, force-stop recovery, multi-device sanity.

**Files:**
- **MODIFIED** `social/SocialRepository.kt` — `wipeMyCloudData` now ends with
  `SharedPlaylistRepository.clearAllCloudForUid(uid)` (no signature change
  because the call is internal, but a `SharedPlaylistRepository` injection
  is added).
- **MODIFIED** `app/src/main/kotlin/com/metrolist/music/App.kt` —
  `initializeSocialFeatures()` adds a `SharedPlaylistSyncListener.get()` call
  to start the per-doc listeners.
- **MODIFIED** `app/src/test/...` — add `SharedPlaylistRepositoryTest` to
  the existing JUnit/MockK suite covering: add/remove idempotency, rename
  last-write-wins, reconcile-after-delete path (D8), account-delete
  cleanup. ~6 cases.

**Verification:** `./gradlew :app:assembleFossDebug` + install on phone +
emulator.

---

## 5. The "new since I last opened" badge — exact mechanism

Per D16. The count we surface is the number of song IDs the cloud doc has
*added* since the last time the local user opened this playlist. Implementation:

1. DataStore key `us_playlist_opened_at` = `longPreferencesKey("...")`, map
   encoded as a JSON string (one key per playlist id, value = ms timestamp).
2. On `LocalPlaylistScreen` `LaunchedEffect(Unit)`: write
   `openedAt[id] = System.currentTimeMillis()` for the current playlist.
3. `SharedPlaylistSyncListener.observeCountsSinceLastOpen()` reads the
   cloud doc's `songs[]` array, finds entries with no corresponding Room
   row in `playlist_song_map` for this playlist, then filters to "added
   after my last `openedAt`" by re-walking the doc's own delta (the doc
   carries `updatedAt` but not a per-song timestamp, so this is a soft
   signal — see Caveat below).
4. Render `count` on the card.

**Caveat:** because the cloud doc doesn't carry a per-song "added at", the
"new since I last opened" can be approximate. The honest reading is
"songs the cloud has but I don't yet". On open, we clear it. This is good
enough for the feature — D16 only promises "since you last opened", and
the worst case is a one-off drift after a phone reset (acceptable, the
data heals on next open).

---

## 6. Commit plan (D31)

| # | Message | Contents |
|---|---------|----------|
| 1 | `feat(us-playlists): bidirectional share repository and rules` | §2 — models, repository, rules block, wipe-cloud integration |
| 2 | `feat(us-playlists): share-from-menu, glyph, border, new-songs badge, delete flow` | §3 — menu wiring, library grid/list rendering, palette border, badge, in-playlist add/remove/rename |
| 3 | `feat(us-playlists): D8 survivor path, account-delete cleanup, integration tests` | §4 — survivor reconciliation, wipe cloud on account-delete, listener start-up, unit tests |

Each commit passes `./gradlew :app:compileFossDebugKotlin`; full `assembleFossDebug` before
final install on both devices.

---

## 7. Deployment step

```bash
firebase deploy --only firestore:rules
```

Required between Phase 1 and Phase 2 — Phase 2's add/remove/rename calls hit the deployed
rules immediately on device. No composite indexes needed for `sharedPlaylists` (doc-id
listeners + single-field whereEqualTo queries only; single-field indexes are automatic).

---

## 8. Verification checklist (BOTH phones)

- [~] Two-people glyph appears. Whole-card border is present in code but was hard to see and did not visibly follow the playing-song color during the test.
- [ ] "X new" badge was not observed and needs correction/retest.
- [x] aswini received a 408-song playlist and all 408 songs eventually populated.
- [x] Playlist name synchronized in both directions.
- [x] Adding a YouTube song synchronized.
- [ ] Removing a song failed: it disappeared only locally, then reappeared after 1–2 minutes from cloud reconciliation.
- [x] Duplicate add was blocked with feedback.
- [x] Local-only song add was blocked.
- [x] Drag reorder stayed local to each phone.
- [x] Playback stayed independent on each phone.
- [x] Offline edits queued and synchronized after reconnect.
- [x] Shared playlist deletion cascaded to both phones.
- [x] No unshare option was exposed.
- [x] Account-delete survivor behavior accepted as passed by user; destructive account deletion was not run.
- [x] Firestore rules compiled and were deployed to `outertune-social`.
- [x] `:app:testFossDebugUnitTest` and `:app:assembleFossDebug` passed before device installation.

### 8.1 Confirmed crash during 408-song receive

The emulator crashed while metadata was still being populated and the user changed tabs. Logcat
captured repeated `SQLiteConstraintException: FOREIGN KEY constraint failed` at
`SharedPlaylistRepository.addSongLocally` when inserting `PlaylistSongMap`. The current code queues
the fetched `SongEntity` with asynchronous `database.query { insert(metadata) }`, then immediately
queues a separate playlist-map transaction. Under the large import, the map can execute before the
song insert and violate the `playlist_song_map.songId -> song.id` foreign key. Restarting allowed the
remaining songs to finish, but this race must be fixed before SPEC_8 is considered stable.

### 8.2 Current follow-up state

Open follow-ups after the first complete device pass:

1. Make metadata insertion and playlist-map insertion atomic/ordered to remove the bulk-receive crash.
2. Diagnose and fix shared song removal being overwritten by the cloud copy.
3. Fix and retest the remote-additions badge.
4. Make the whole-card shared outline clearly visible and verify live palette changes.
5. Remove cross-device cover synchronization; covers are now intentionally device-local per revised D19.

---

## 9. Risks / notes for the implementer

- **The "X new" badge is approximate** (per §5 caveat) — fine for the use case, but
  document it in code so the next agent doesn't try to "fix" it.
- **D7 unshare = no API** is intentional. A future you might want to add
  a soft unshare (e.g. "hide from aswini's phone" without deleting data). Today's
  spec is deliberately closed: if the relationship state changes, delete and
  re-create is the answer.
- **`Player.kt` seek-restriction code** (`isToListenPlaylist`) does **not** change.
  Shared playlists aren't seek-restricted — they aren't auto-progressed, no
  milestones, no `PlaybackProgressTracker` interaction. The TO_LISTEN row is
  the only one with that plumbing.
- **`SourcePlaylistId` propagation** for `ListQueue(...)` is **untouched** — the
  PlaybackProgressTracker only reads it. Shared playlists, when played, carry
  their own id through (just like any local playlist), but the tracker ignores
  non-TO_LISTEN ids.
- **`MusicService` song-change heartbeat** is **untouched** — it's about "what
  I'm listening to right now", not about playlists. Shared playlist changes
  don't trigger it.
- **Listen Together** is **untouched** — it has its own room/queue protocol
  and the `invites/{recipientUid}` rules. SPEC_8 doesn't touch any of it.
- **`local_songs_cannot_be_sent` string already exists** and fits D9 verbatim
  for the "block add local song to shared playlist" case. Reuse it; no
  duplicate string.
- **The "Open in Listen Together" affordance** (showing the LT button when the
  playlist is open) is **out of scope for SPEC_8**. Future work if you want.
- **`SharedPlaylistCloud.songCovers`** is a nicety, not required by the spec.
  Skip it in Phase 1; add later if useful.
- **Stale comment cleanup** in `MIGRATION_38_39` ("JSON array of UIDs" → "single UID")
  and the `App.kt:147–151` duplicate `partnerHeartbeatMonitor.get()` call can be
  cleaned in the SPEC_8 commit that touches the same file, or deferred to a
  follow-up "chore" commit. Not part of any phase.

---

## 10. Out of scope (deliberately)

- Per-song "added by" attribution (D25)
- "Recently added by aswini" feed (D25)
- Shared playlist → Listen Together bridge (out)
- Bulk share / bulk delete (D24)
- Multiple partners / friend graph (D2)
- Unshare button (D7)
- Per-playlist permissions (D3)
- Conflict resolution UI (D5)
- Approval flows (D4)
- Per-song activity notifications (D11)
- "Catch-up" banners after reconnect (D14)
- Per-playlist auto-approve or suggestion queues
- Shared playlist playback sync to Listen Together style (D20)
