# GUEST_CONTROL_EXPERIMENT.md

Minimal experiment to answer ONE question before building the full "guest has full
playback control" feature (user request, Aug 25 2026):

> **Does the Listen Together server relay a GUEST's playback action to the host — or does
> it drop/reject non-host actions?**

Nobody knows, because the client blocks guest actions before they ever leave the phone
(`ListenTogetherClient.sendPlaybackAction`, line ~1771: `if (_role.value != RoomRole.HOST)
return`). The server's behavior has therefore never been observed.

## The three edits (PAUSE only, no polish)

1. **`ListenTogetherClient.kt` (~line 1771)** — lift the host-only gate in
   `sendPlaybackAction` so a guest's PAUSE is actually sent to the server.

2. **`ListenTogetherManager.kt` (~line 508)** — the host currently discards received
   transport actions:
   ```kotlin
   if (!isHost || isQueueOp) { handlePlaybackSync(event.action) }
   ```
   Widen it so a received PAUSE is applied on the host too. The apply-logic in
   `handlePlaybackSync` (PLAY/PAUSE/SEEK/SKIP_NEXT/SKIP_PREV/CHANGE_TRACK, with drift
   correction and revision tracking) already exists and is role-agnostic — zero changes
   needed there.

3. **Player UI (guest side)** — make the guest's play/pause button *send* PAUSE/PLAY via
   the client instead of doing nothing. One button, one branch.

## How to run it

1. Build, install on both devices (phone = eman/host, emulator = aswinitest/guest)
2. Start a session (invite → join → play a song on the phone)
3. On the emulator, tap **pause** — once
4. Watch logcat on both devices (`adb logcat -s ListenTogetherManager ListenTogetherClient LTInvite`)

## Outcomes

| Observation | Meaning | Next step |
|---|---|---|
| Host's music pauses | Server relays guest actions | Build the full guest-control feature (~6 files, all mapped: Client gate, Manager host-apply, Player.kt seek gate, MiniPlayer 4 gates, Queue.kt, Thumbnail.kt; keep the To-Listen seek restriction) |
| Nothing / server error / kick | Server blocks non-host actions | Client work alone can never fix it — needs metroserver modification or self-hosting |

Either outcome is a definitive answer for ~30 minutes of work. Do NOT build the full
feature before this experiment passes.

## Context (from the full investigation, Aug 25 2026)

- Protocol already defines `SKIP_NEXT`/`SKIP_PREV` (Protocol.kt:63-64) and
  `handlePlaybackSync` implements every needed action — the receiving logic is complete
  and role-agnostic.
- UI gates to rewire for the full feature later: `Player.kt:341`
  `seekRestricted = isListenTogetherGuest || isToListenPlaylist` (KEEP the To-Listen part),
  `MiniPlayer.kt` lines 223/535/733/926, `Queue.kt:173`, `Thumbnail.kt:446`, plus the
  `isGuest` suggestion-routing in menus (guest song selection already flows through
  suggestions with auto-approve forced ON for invite sessions).
- The metroserver is an external Go repo (not in this codebase). If it must be changed,
  that happens outside this repo.
