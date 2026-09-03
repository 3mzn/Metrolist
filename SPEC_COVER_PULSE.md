# SPEC_COVER_PULSE — Bass-Pulse main cover (Idea 2)

> Idea 2 from `VIZ_IDEAS.md:26-38`, redirected to the expanded main player.
> Real-time `android.media.audiofx.Visualizer` FFT. No sidecars. Local playback only.

## 1. Locked decisions (from user Q&A)

| # | Decision |
|---|---|
| 1 | Surface: expanded main player large cover ONLY (not mini-pill, not blur/gradient backgrounds) |
| 2 | New player design only (`UseNewPlayerDesignKey`); old design stays static |
| 3 | Art image scales; overlays/icons (`CastButton`, play/replay) stay fixed |
| 4 | Overflow allowed (scale draws over neighbors, no re-layout); reduce below 1.12 if it touches UI on device |
| 5 | Peak: LOW 1.06 / MED 1.12 / HIGH 1.18; idle 1.0 |
| 6 | Detection: sustained bass + kick transient, smoothed (fast attack + ~200ms decay) |
| 7 | Motion: direct follow (speaker-cone, scale set every frame from smoothed bass) |
| 8 | Freeze held scale on pause/end/mute; song change = simplest (reset smoothed to 0) |
| 9 | Pause pulse under lyrics (free via unmount, no extra logic) |
| 10 | Local playback only; static 1.0 when casting |
| 11 | Release Visualizer on pause + app background; always runs otherwise (no power-saver gate) |
| 12 | Ignore reduced-motion setting |
| 13 | Settings: Appearance > Player toggle default ON + Low/Med/High slider |
| 14 | Permission: request `RECORD_AUDIO` at runtime when toggle flipped ON; denied = silent static, re-ask on next OFF->ON |
| 15 | Verify: play + watch on phone |

## 2. Target code (verified)

- `app/.../ui/player/Player.kt:220-224` `useNewPlayerDesign` gate.
- `Player.kt:1897` (landscape) / `:1960` (portrait) `Thumbnail(...)` call sites.
- `app/.../ui/player/Thumbnail.kt:566-593` inner art `Box(size(thumbnailSize).clip(...))` + `ThumbnailImage:623-649` (`AsyncImage fillMaxSize`) = pulse node. `CastButton:587-592` stays fixed.
- `Player.kt:1885-1905,1948-1967` `AnimatedContent(showInlineLyrics)` unmounts `Thumbnail` when lyrics show = lyrics pause.
- `Thumbnail.kt:377-410` carousel renders neighbors; scale ONLY current item (`item.mediaId == currentMediaId`).
- Session source: `playerConnection.player.audioSessionId`, valid if `!= C.AUDIO_SESSION_ID_UNSET && > 0` (`playback/MusicService.kt:2426-2427`, pattern `ui/menu/PlayerMenu.kt:725-726`).
- Casting pattern: `ui/player/MiniPlayer.kt:207-215` `castHandler` -> `isCasting`.
- Reference impl: `OuterTune/.../ui/utils/AudioVisualizerUtils.kt:42-121` (`Visualizer(session)`, `captureSize=1024`, max rate, FFT low-1/8 bass 0..1).
- Settings pattern: `ui/screens/settings/AppearanceSettings.kt:1090-1112` Switch item; prefs near `constants/PreferenceKeys.kt:61-62`.
- Permission patterns: `ui/screens/recognition/RecognitionScreen.kt:130-155` (composable launcher, preferred); `MainActivity.kt:315-320,362-369` (activity API).
- Manifest: `RECORD_AUDIO` already declared (`app/src/main/AndroidManifest.xml:17`); no manifest change.

## 3. Changes

### 3.1 NEW `app/.../ui/player/CoverBassPulse.kt`
Port OuterTune capture + transient-kick detector:
- `init(session, owner)`: `Visualizer(session)`, `captureSize = 1024`, FFT-only listener, `enabled = true`; try/catch -> static fallback.
- `release()`: `visualizer?.release()`, null out.
- Band: kick bins 1..2 only (~40-130Hz @44.1/48kHz, DC skipped) — mids/vocals excluded.
  Magnitudes use SIGNED bytes (-128..127); unsigned handling inflated silence.
- Envelope: ~50ms-attack follower vs floor-tracking baseline (400ms fall,
  3000ms rise so kicks can't pump it); absolute 0.065 spike gate;
  `kick = clamp((follow - baseline - 0.065) / (0.39 + baseline * 0.5))` with
  snap attack; output = `kick * 0.9 +
  baseline * 0.1` (kicks dominate, sustained barely breathes).
- Display: captures (~20Hz) write `kickTarget` + snap attack; the owning
  `ThumbnailItem` `LaunchedEffect` runs a `withFrameNanos` loop (composition
  owns the frame clock — a standalone scope crashes) re-sampling the 250ms
  release glide every frame (60fps motion, same timing); the loop lives only
  inside the effect, `finally` releases.
- Expose `bass: MutableFloatState`, `smoothed` reset fn.
- Scale: `scale = 1f + smoothed * (peak - 1f)`; peak from intensity.
- Owner token: `init(session, owner)` stores the item media id; `releaseIf(owner)`
  no-ops for non-owners (carousel neighbors must not kill the live capture);
  `release()` force-releases (background/unmount).

### 3.2 `constants/PreferenceKeys.kt`
- `PlayerCoverPulseKey = booleanPreferencesKey("playerCoverPulse")` (default true at call site).
- `PlayerCoverPulseIntensityKey` + `enum CoverPulseIntensity { LOW, MEDIUM, HIGH }` (default MEDIUM).

### 3.3 `ui/screens/settings/AppearanceSettings.kt` (Player group)
- Toggle (Switch) + Low/Med/High slider, same `Material3SettingsItem` pattern as mini-player group.
- Toggle-ON with `RECORD_AUDIO` denied -> fire permission launcher (see 3.5).

### 3.4 `ui/player/Thumbnail.kt` (`ThumbnailItem`)
- Collect: `isPlaying`, `audioSessionId`, `isCasting`, toggle, intensity, `useNewPlayerDesign`, `mediaId`.
- `LaunchedEffect(isPlaying, sessionId, toggle, mediaId)`: init iff `playing && toggle && !casting && session valid && newDesign`; else `release()`. Reset smoothed on `mediaId` change.
- `DisposableEffect(LocalLifecycleOwner)`: `ON_STOP -> release()`; `ON_START -> re-init if playing`.
- `graphicsLayer { scaleX = scaleY = scale }` on image node only, reading bass state inside the layer block (no per-callback recomposition). Static path: frozen last scale or 1.0.

### 3.5 Permission
- `rememberLauncherForActivityResult(RequestPermission)` in settings toggle + player (RecognitionScreen pattern).
- Denied -> silent static; next OFF->ON re-fires request.

### 3.6 `metrolist_strings.xml` (English only, per AGENTS.md #3)
- `player_cover_pulse`, `player_cover_pulse_desc`, intensity labels.

## 4. Explicit non-goals
- No mini-player changes. No background blur/gradient pulse. No legacy player design. No manifest change. No DB schema change. No power-saver gate. No reduced-motion handling.

## 5. Phases (each ends with a green build; no commit/push without request)

### Phase 1 — Prefs + strings + settings UI (no behavior)
- `PreferenceKeys.kt`: add `PlayerCoverPulseKey` + `PlayerCoverPulseIntensityKey` + enum.
- `metrolist_strings.xml`: add toggle/desc/intensity strings.
- `AppearanceSettings.kt` Player group: toggle (default ON) + Low/Med/High slider, incl. permission-launcher stub on toggle-ON.
- Verify: `assembleFossDebug` green; toggle persists; slider persists; player unchanged.

### Phase 2 — `CoverBassPulse.kt` controller (no UI wiring)
- New file per §3.1 (port + envelope + init/release + scale math).
- Verify: compiles; no callers yet; no behavior change.

### Phase 3 — Thumbnail wiring + permission + lifecycle (feature live)
- `Thumbnail.kt` per §3.4 + permission per §3.5.
- Verify: full checklist in §6.

### Phase 4 — Tune + harden
- Overflow check (reduce peak if art touches controls); device-throw fallback confirmed; log noise check.
- Verify: play + watch pass on phone.

### Phase 5 — Sidechained kicks (planned, not implemented)
- Problem: during long sustained bass, the floor-tracker climbs toward the
  sustain, so `follow - baseline` shrinks and kicks landing mid-sustain score
  low (drowned out).
- Fix, `CoverBassPulse.kt` only (~25 lines, no `Thumbnail.kt` changes):
  - Kick path: spectral flux (`max(0, follow - prevFollow)`) normalized by a
    slow peak-hold (`fluxPeak`, seeded 0.3, 2s decay; 0.04 floor). Sustains
    have ~zero flux at any level, so kicks score the same mid-sustain as
    mid-silence. Existing 0.065 gate moves onto flux.
  - Sustain path: duck the drone under hits —
    `sustainOut = baseline * 0.1 * (1 - kickEnv * duck)`, `duck = 0.7`.
  - Attack/release/frame-loop untouched.
- Open: duck depth subtle (0.5) vs pumping (0.8); whether intensity slider
  also scales duck or peak-only as today.
- Verify: bass-roll track shows drone holding slightly with each kick punching
  through + dipping after; silence/verse/4-on-floor regression unchanged.

## 6. Build / verify
1. `.\gradlew :app:assembleFossDebug` green after each change.
2. Phone play + watch: on-beat pulse; freeze on pause; static when casting/old-design/lyrics/toggle-off; intensity changes peak.
3. `logcat -s CoverBassPulse` init/release only (no per-frame logs).
4. No commit/push without explicit request.
