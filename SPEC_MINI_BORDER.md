# SPEC_MINI_BORDER — Bass-reactive mini-player border (Idea 1)

## 1. Locked decisions
- New mini-player pill only, all background modes.
- Alpha 0.2 idle → sustain floor (subtle) + kick spikes to 1.0, 200ms decay;
  width 1→2dp jumps on kick onsets only.
- Color: white for all songs (palette washed out on gradient themes).
- Draw: alpha 0.2 idle → sustain + kick spikes; width 2.dp idle → 4.dp on
  kick onsets; static 1.dp/0.3 outline when toggled off.
- Freeze on pause; faint 0.2 idle on mute/cast.
- Settings (Appearance > Mini-player): own toggle default ON + Low/Med/High
  slider.
- Controller exposes `kickEnv` + sustain level + kick-onset events;
  one shared max-rate capture for pulse + border.
- Custom `drawBehind` border (no recomposition); always-fetch palette for
  every song in new mini-player; 20Hz capture → 60fps display interpolation.
- Strings in English `metrolist_strings.xml` only. No commit/push unrequested.

## 2. Phases (compile `:app:compileFossDebugKotlin` green after each)
### B1 — Controller signals (no UI)
- `CoverBassPulse.kt`: expose `kickEnv: Float` state, `sustainLevel: Float`
  state, and kick-onset events (threshold crossing on kickTarget, consumed
  once). No behavior change to pulse.
- Verify: compile green; pulse unchanged on device.

### B2 — Prefs + strings + settings UI (no behavior)
- `PreferenceKeys.kt`: `MiniPlayerBorderGlowKey` (bool, default ON) +
  `MiniPlayerBorderGlowIntensityKey` (string) + `BorderGlowIntensity`
  enum { LOW, MEDIUM, HIGH }.
- Strings: `mini_player_border_glow`, `_desc`, `_intensity`,
  `_low/_medium/_high`.
- `AppearanceSettings.kt` mini-player group: toggle + intensity slider
  dialog (pulse-dialog pattern).
- Verify: compile green; settings persist; player unchanged.

### B3 — Always-fetch palette (no visible change)
- `MiniPlayer.kt`: extend the `LaunchedEffect(mediaMetadata?.id, ...)` fetch
  to run in every background mode (not just GRADIENT); cache per song id.
- Verify: compile green; log one line per fetch; visuals unchanged.

### B4 — Border render + drive (feature live)
- `NewMiniPlayer` pill: replace static `border()` with custom `drawBehind`
  rounded border reading kick/sustain in draw scope (alpha + width);
  palette primary with outline fallback; freeze on pause, faint idle on
  mute/cast; 60fps loop shared with pulse.
- Verify on phone: idle 0.2 glow; sustain lifts floor; kicks flash + swell
  to 2dp with 200ms decay; toggle + slider work; legacy untouched.
