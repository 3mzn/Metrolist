# MAYBE_LATER.md

Couple-oriented feature ideas for our private modded fork.
Two users only: **eman** (sender) & **aswini** (receiver). All personalization uses full lowercase names.

Build order: 13 → 3 → 5 → 6 → 4 → 7 → 8
(#6 hard-depends on #5; #4 soft-depends on #5 for presence-based skip conditions)

Progress: 13 ✅ · 3 ✅ · 5+6 ✅ (shipped as one Partner widget feature) · W-SCALE ✅ (shipped at 65% floor) · next: **4** → 7 → 8 → **9 VIZ**

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

## 4. Gentle nudge (notifies BOTH people)

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

## 7. Listen Together, trivially (redesigned menu)

Keep Metrolist's entire Listen Together backend untouched. Replace only the join UI on the existing LT entry point.

- **Current UI:** the Listen Together menu has username + room-code input fields and a connect button. All of that goes away.
- **New UI:** the same button/menu becomes a single primary action: `Invite aswini to listen together`. Optionally a secondary line showing session state ("listening together now · end session").
- **Invite flow:**
  1. eman taps invite → write `invite/{aswiniUid} = {roomCode, from: emanUid, createdAt, expiresAt}` to Firestore (room created locally via existing LT client first).
  2. aswini's phone (snapshot listener on her invite doc) shows a non-intrusive banner or notification: `eman wants to listen together — join?`
  3. One tap joins the existing room with playback synced. Decline → invite expires silently after 10 min.
- **What stays untouched:** `ListenTogetherClient`, sync protocol, play/pause/seek/queue propagation. We're only automating room discovery — no protocol changes.
- **Edge cases:** simultaneous invites → last write wins, other side gets a toast; expired invite → tapping join shows a gentle "invite expired" toast.

---

## 8. "Us" playlists

Playlists both of us can edit, kept in sync through Firestore.

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
