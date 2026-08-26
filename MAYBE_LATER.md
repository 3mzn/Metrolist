# MAYBE_LATER.md

Couple-oriented feature ideas for our private modded fork.
Two users only: **eman** (sender) & **aswini** (receiver). All personalization uses full lowercase names.

Build order: 13 → 3 → 5 → 6 → 4 → 7 → 8
(#6 hard-depends on #5; #4 soft-depends on #5 for presence-based skip conditions)

Progress: 13 ✅ · 3 ✅ · 5+6 ✅ (shipped as one Partner widget feature) · W-SCALE ✅ (shipped at 65% floor) · 4 ✅ (gentle nudge shipped + tested) · 7 ✅ (listen together invites — one-tap `invites/{recipientUid}`, 30 min expiry) · 8 🧪 (implemented; first two-device pass found 4 follow-ups) · next: finish **8** → **9 VIZ**

---

## W-SCALE. Widget text auto-scale — ✅ SHIPPED

Shipped in `b9299de37` + `d71b86bc0`. One shared multiplier shrinks BOTH lines together,
preserving the title:artist ratio exactly; floor set to **65%** (user-tuned, up from the
planned 50%), past it the existing ellipsize takes over. Baseline gap scales with the text.
Implemented in `PartnerWidgetManager.composeUnifiedWidget` (live-text block only;
idle/compact untouched). Verified on-device.

When the song title and/or artist are too long for the panel width, scale BOTH down
proportionally instead of truncating with "…". Chosen over scrolling-marquee + edge-fade
(rejected as over-engineered: zero battery cost, zero launcher quirks).

1. Measure title at default size (`height * 0.13f`) against available panel width
2. Measure artist at its default (`height * 0.085f`)
3. Overflow in either → compute ONE shared scale factor so both fit; apply to both sizes,
   preserving the title:artist ratio exactly
4. Scale floor at ~50% — beyond that, ellipsis ("…") takes over (unreadable otherwise)
5. Baseline gap between title and artist scales with them so the stack stays visually tight
6. Header ("Listening: eman") untouched — short by nature

Where: `PartnerWidgetManager.composeUnifiedWidget` (live-text block only; idle/compact untouched).
Zero battery cost, works identically on every launcher.

---

## 9. VIZ — Native visualizer for downloaded songs (LAST in build order)

Real frequency-band visualizer for **downloaded songs only**, built from the local audio file
instead of live-audio capture — no RECORD_AUDIO permission, works fully offline.

1. On download completion: background job decodes the audio file (MediaCodec) → PCM → FFT over
   ~50ms windows → compact per-song frequency-band array cached as a sidecar file next to the
   download (~50–200KB; follows the existing download-sidecar pattern, avoids DB schema change)
2. Playback: `player.currentPosition` → index into cached bands → draw bars. Pure array lookup
   + Canvas — trivially cheap at 60fps
3. Seeks just work — jump anywhere and the bars match that exact moment instantly
4. Deterministic: same song always renders identically; sample-accurate sync beats the live-tap API
5. Streamed songs: fall back to simulated bars (or none) — no local file exists to analyze
6. Effort ≈ 1 day: decode pipeline ~200 lines, textbook FFT ~50 lines, renderer with a few styles

Why LAST: beautiful polish, but everything above it ships more value per hour.

---

## 3. Waiting-for-you badge

A small count bubble on the Library tab (and optionally on "From aswini") showing how many songs aswini has sent eman that he hasn't started listening yet.

- **Data source:** zero new infrastructure. `SongSharingViewModel.incomingSongs` already exposes every doc in `sentSongs` where `toUid = me && completedAt == null`. The badge is literally `incomingSongs.size`.
- **Behavior:** badge shows on the Library nav item; tapping through shows the To Listen playlist with pending items sorted newest-first. Count clears item-by-item as songs complete.
- **Edge case:** songs 50%-ed but not finished still count — they're unfinished. Optionally exclude the currently playing song.
- **Effort:** UI-only, an afternoon.

---

## 4. Gentle nudge (notifies BOTH people) — ✅ SHIPPED

Shipped as `37a425c84` → `5c792e400`. Daily `GentleNudgeWorker`: one batched notification
pair per day for songs unstarted ≥3 days; per-song cap of 2 rounds (`nudgeCount`/`lastNudgedAt`
on the sentSongs doc); skips while the partner is actively listening (status/{uid} freshness)
or while the stale song plays locally; foreground suppression applies ONLY to the receiver
nudge — the sender nudge is the only surface showing "partner hasn't listened", so it always
fires. Tested on both phones (PENDING_TESTS.md). Original design below.

### Original design

If a sent song sits unstarted for N days (default 3), both sides get a soft daily notification — warm, never guilt-trippy.

- **Sender notification:** `aswini hasn't gotten to 'Blonde Redhead' yet 🎧`
- **Receiver notification:**
  - one stale song: `You haven't listened to 'Blonde Redhead' (sent by eman)`
  - multiple stale songs: `You haven't listened to 5 songs sent by eman`
- **Trigger:** WorkManager daily check. Query outgoing `sentSongs`: `fromUid = me`, `completedAt == null`, `listenedAt == null`, `sentAt < now - 3 days`, no previous nudge today. Mirror query for the receiving side (same fields, roles swapped).
- **Anti-nag rules:** max ONE notification pair per day, batching all stale songs into single messages per side. Per-song cap of two nudge rounds total ever (store `nudgeCount` / `lastNudgedAt` on the doc), then stop.
- **Skip conditions:** don't nudge either side if the song is currently playing, or if presence data from feature #5 shows the other person actively listening right now.
- **Strings:** both message templates go in `metrolist_strings.xml` only.

---

## 5. Now-listening heartbeat

Live "she's listening to X right now" awareness between the two phones.

- **Write path:** when playback starts/transitions, MusicService writes `status/{uid} = {songId, title, artist, thumbnailUrl, updatedAt}` to Firestore; deletes (or marks stale) on stop. Throttle writes to once per song change, not per second.
- **Read path:** snapshot listener on partner's `status/{uid}` doc, same `callbackFlow` pattern as `observeIncomingSongs`.
- **Display:** small line under the Library header or on Home: `🎧 aswini · Radiohead — Weird Fishes · 2m ago`. Tap it to play the same song yourself.
- **Staleness rule:** hide if `updatedAt > 2 min old` or app killed without cleanup — timestamp comparison handles crashes for free; never show ghost status.
- **Privacy toggle:** settings switch to pause the heartbeat (writes nothing) without affecting anything else.

---

## 6. Couple widget

Home-screen widget mirroring feature #5 outside the app: partner's current track, album art, tap-to-play.

- **Layout:** single 4×1 widget — art thumbnail, scrolling title, artist, small pulsing dot when the track is <2 min old (live indicator).
- **Mechanics:** reuse Metrolist's existing widget architecture (`MusicWidgetReceiver` pattern). Updates via Firestore snapshot listener while the app is open, plus periodic fallback updates when closed.
- **Tap action:** deep link into the app and immediately queue partner's current track (same flow as `MainActivity.handleDeepLinkIntent`).
- **Empty state:** `aswini isn't listening 🌙` centered text.

---

## 7. Listen Together, trivially (redesigned menu) — ✅ SHIPPED

Shipped `fa109d87c` → `02bdbe873` (+30 min expiry fix `b60583334` era). Single primary action `Invite aswini to listen together` replacing the username/room-code form. Covers `D1–D16` in `SPEC_7.md:0` + `SPEC_7.md:1` `invites/{recipientUid}` `{roomCode, fromUid, fromName, createdAt, status}`, **30 min reader-clock expiry** `SPEC_7.md:21`, foreground `InviteBanner.kt` / background `SongNotificationHelper.kt:lt_invites#2800` / dead-process `InvitePollWorker.kt` tiers `SPEC_7.md:32`, invite-waiting card + `InviteSection` + `AdvancedJoinSection` on `ui/screens/ListenTogetherScreen.kt` `SPEC_7.md:200`, `firestore.rules` `invites` block, verified on phone + emulator. Fixes in the testing war documented in `CONTINUATION.md:15`.

- **Current UI:** the Listen Together menu has username + room-code input fields and a connect button. All of that goes away.
- **New UI:** the same button/menu becomes a single primary action: `Invite aswini to listen together`. Shows `Invite sent · waiting… [Cancel]` while pending, inline `Join / Reject` when incoming, `InviteSection` inside the session view while waiting for the partner, and `Manual join options` (`AdvancedJoinSection`) remains.
- **Invite flow:**
  1. eman taps invite → write `invites/{aswiniUid} = {roomCode, fromUid: emanUid, fromName: eman, createdAt, status: pending}` to Firestore (room created locally via existing LT client first).
  2. aswini's phone (snapshot listener / poll) shows: foreground → banner, backgrounded (alive) → heads-up notification `lt_invites`, fully closed → poll. `eman wants to listen together — join?`
  3. One tap joins the existing room with playback synced. Decline → doc deleted after `status declined` signal, sender gets toast; invite expires silently after **30 min** `SPEC_7.md:21` (was 15, fixed to give the poll two cycles of slack).
- **What stayed untouched:** `ListenTogetherClient`, sync protocol, play/pause/seek/queue propagation. Only automating room discovery — no protocol changes.
- **Edge cases:** simultaneous invites → last write wins, other side gets a toast; expired invite → banner/notification vanishes, no "expired" toast (deviation, `CONTINUATION.md:13`).

---

## 8. "Us" playlists

Playlists both of us can edit, kept in sync through Firestore.

Implementation exists in the current SPEC_8 checkpoint: Room v39 `sharedWith`, Firestore
`sharedPlaylists`, app-lifetime reconciliation, share/add/rename/delete, Library glyph/border/badge,
local-song and duplicate guards, independent reorder/playback, account-delete survivor path, and
deployed rules. The first phone + emulator test passed receive (408 songs), rename, add, duplicate,
local-song block, independent reorder/playback, offline recovery, symmetric delete, and no-unshare.
Open defects are documented in `SPEC_8.md:8`: a bulk-receive foreign-key race, remove-song cloud
reappearance, unseen badge not rendering, and weak/non-reactive card outline. Covers were deliberately
changed to device-local after testing; cross-device cover sync is no longer required.

- **Model:** new playlist flag `sharedWith: [partnerUid]` on local `PlaylistEntity` (schema addition — AGENTS.md forbids schema edits, so needs explicit sign-off), OR mirror shared playlists entirely in Firestore and render them as a special section without Room rows.
- **Sync strategy (simple version):** Firestore doc per shared playlist holding ordered song-ID list + metadata. Both phones listen to their shared playlists' docs; incoming changes merge into the local Room copy, outgoing adds/deletes write to Firestore first then apply locally on ack. Last-writer-wins per song operation is fine at two users.
- **UI:** shared playlists appear in the normal playlists grid with a 👥/"Us" glyph; long-press menu hides destructive options ("delete" requires confirmation naming both of us).
- **Conflict reality check:** different songs added within the same minute just merge (array union). Same-song simultaneous edits basically never happen — do NOT over-engineer CRDTs.

---

## 13. Rename the surfaces / hardcode the partner ✅ DECIDED

Declutter everything the friend-system built for many people down to exactly one person. Names are final: **eman** and **aswini**, full lowercase, no capitalization.

- **Config:** hardcode/store `partnerUid` (aswini) + current user identity (eman) — ideally a first-run dialog storing `partnerUid` + display name in DataStore so it survives UID quirks, with `eman`/`aswini` as defaults.
- **Renames:**
  - "To Listen" → `From aswini` everywhere (playlist ID stays fixed `LP_TO_LISTEN`, only the display label maps)
  - "Send to friends" → `Send to aswini`
  - Notification strings swap "a friend" → `aswini`; FCM fallback strings personalized
- **Removals:** `SendToFriendsDialog`'s multi-select collapses to a confirm button (`Send 3 songs to aswini?`); `SocialRepository`'s request/accept/reject flows become dead code paths we simply stop calling — leave them compiled but unreferenced, zero risk.
- **Bonus polish:** received-songs playlist icon and accent tint from aswini's chosen color (hook for future idea #14).

---

## Dropped

- ~~Couple Wrapped~~ (former idea #10) — decided against, not needed.
