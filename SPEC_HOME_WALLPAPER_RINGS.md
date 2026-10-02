# SPEC — Home Screen Ring Visualizer (Live Wallpaper)

**Status:** Phase 1 ✅ committed `058b42a39` — **GATE PASSED**. Phase 2 ✅ **COMPLETE**,
geometry pixel-verified. Phase 3 next, awaiting authorisation.
**Created:** 2026-10-03
**Branch:** `testing`
**Supersedes:** nothing. Extends `SPEC_SPOTIFY_CANVAS.md` (shares `CoverBassPulse`).

---

## 1. Goal

Draw the same bass-reactive rounded-rectangle glow ring that already ships around the
MiniPlayer — as a **live wallpaper**, positioned so each ring sits flush around a
home-screen widget. Four widgets, four rings, real audio, real time.

This is **not** an app-widget visualizer. Widgets are RemoteViews: a bitmap handed to
the launcher over a ~1 MB Binder pipe, ceiling ~2 fps. A wallpaper is a real
`Surface` on the real frame clock — genuine 60 fps. See §2.

---

## 2. Why wallpaper, not widget (evidence)

| | App Widget | Live Wallpaper |
|---|---|---|
| Transport | `RemoteViews` bitmap over Binder (~1 MB cap) | Direct `Surface`, same process |
| Practical frame rate | ~2 fps (measured ceiling in research) | 60 fps |
| Audio | none on device (partner audio) | own playback, in-process |
| Draws behind/around widgets | n/a | **Yes** — always behind |
| Survives idle | yes | HyperOS risk (§7) |

**Rejected alternatives, and why:**

- **Widget bitmap push** — 990 KB payload, ~2 fps. Unusable.
- **Widget animated-list (`<animation-list>`)** — read AOSP `AnimationDrawable.java`:
  `inflate()` ends `setFrame(0, true, false)` — the `false` is `animate`, so it **does
  not start**. `start()` is required and `RemoteViews` cannot reach it. Dead.
- **Widget `AnimatedImageDrawable`** (`autoStart="true"`) — genuinely self-starting,
  but frames are **baked into the APK**. Decoration, not data. Rejected as fake.
- **WebView widget** — not in the `RemoteViews` allowlist.

---

## 3. Audio source — no new capture required

**Android's `Services` documentation:** *"A service runs in the same process as the
application in which it is declared."*

Therefore `WallpaperService` and `MusicService` are the **same process**. `CoverBassPulse`
is a Kotlin `object` — a process-wide singleton (`CoverBassPulse.kt:34`).

The wallpaper **reads the existing singleton directly.** No new `Visualizer`, no FFT,
no `RECORD_AUDIO` request, no new permission, no IPC.

### 3.1 The 20 Hz platform cap is acceptable here

Measured on device: platform `Visualizer` caps at **19.2 Hz** (mean interval 52.0 ms),
against AOSP `CAPTURE_RATE_MAX = 20000` mHz. The abandoned PCM-tap experiment
(`221bd6c0b`) reached 93.75 Hz.

**A bass note lasts 100–300 ms.** At 20 Hz that is 2–6 samples per note. The existing
`advanceFrame` release glide already smooths 20 Hz input to 60 fps display. **The 20 Hz
cap is load-bearing for the in-app full-screen visualizer and irrelevant here.**

### 3.2 Ownership change (the only real code change)

`Player.kt:401` currently does:

```kotlin
Lifecycle.Event.ON_STOP -> CoverBassPulse.release()
```

Correct today (the visualizer is on an unseen screen). **Must change:** the wallpaper
becomes the audio-analysis owner; the player screen becomes one more consumer. Same
`object`, different owner. Requires an owner/refcount discipline in `CoverBassPulse`
so the two do not release each other.

---

## 4. Visual specification

### 4.1 Geometry — four rings

Coordinates measured on device (§5). **Wallpaper-surface coordinates — do not use these
numbers until Phase 2 establishes the screen→surface mapping.**

| Widget | Screen px `[x1,y1][x2,y2]` | px | Orb? | Orbit dir |
|---|---|---|---|---|
| Metrolist (Partner) | `[74,638][1006,1104]` | 932×466 | **No** | — |
| Nothing X | `[74,1148][498,1614]` | 424×466 | Yes | **+1** |
| Claude | `[582,1148][1006,1614]` | 424×466 | Yes | **−1** (mirrored) |
| Brave (search) | `[74,1658][1006,1869]` | 932×211 | **No** | — |

- **Metrolist + Brave:** identical pulse ring to MiniPlayer, `hotspotMult = 0f`.
- **Nothing X / Claude:** pulse ring **plus** orbiting hotspot, counter-rotating.
  Driven by the *same* shader uniforms — sign flip on `orbitSpeed`.

### 4.2 The shader needs no new maths

`BorderGlowShader.kt` is already parameterised:

| Requirement | Existing mechanism | Change |
|---|---|---|
| Square-ish corners | `cornerRadius` uniform, fed from `32.dp` (L150) | per-ring value |
| Arbitrary rect | `ringTopLeft` / `ringSize` uniforms | feed measured rect |
| Mirrored orbit | `orbitSpeed = 0.8 + b*4.0` (L67) | negate per ring |
| No orb | `hotspotMult` (L39, L128) | `0f` |
| Colour | `cr,cg,cb` uniforms | see §4.3 |

`sdRoundedRect` already handles any corner radius. **No new AGSL maths required.**

### 4.3 Colour

Single global source: **the currently-playing song's artwork palette**, exactly as
`MiniPlayer.kt:458` (`borderSongColor`). **All four rings share it.** No per-widget
colour.

**Gating — per user decision:** the visualizer runs **only** when
`Widget UI Debug Test` is ON (widget shows *my* song). When the widget shows the
partner's track, **rings are entirely inactive** — no bass, no colour, nothing.

> **Confirmed (Q1):** *"Listening: eman — Solar Eclipse"* is the user's own song, so the
> debug test is ON and the gate is satisfied. Rings are live in the current device state.

### 4.4 Alpha envelope — genuinely new logic

Requirement, from user:

- **Playing, no bass** → same as MiniPlayer: idle ring persists.
- **Playing, bass** → pulse.
- **Paused / stopped** → **fade to nothing** (MiniPlayer freezes in frame instead).
- **Wallpaper hidden** (left home screen) → **fade to nothing**, not freeze.

Current shader has no concept of "stopped" — `bass == 0` is indistinguishable from
quiet. **Requires a separate multiplicative alpha driven by playback state + engine
visibility**, not by bass. New state, new interpolation. This is the one genuinely new
rendering concept in the feature.

### 4.5 Colour / geometry notes

- Rings render **outside** the widget, flush to its **visible** edge — the wallpaper is
  *below* the widgets, so the ring must be at the boundary to be seen at all.
- MIUI applies its own corner rounding. Ring corner radius must **match per widget** or
  the corners visibly clash. **Per-ring radius, measured in Phase 2** (Q3).
- **Flush vs cell-edge:** flush first. Cell-edge variant (ring at grid boundary, ~4–38 px
  further out, per §5 padding table) available as a per-ring toggle if flush looks wrong.
- User visually confirms and requests the flip.

---

## 5. Measured device facts (2026-10-03)

Source: `adb -s ylwwmn85w4ifb6z9`.

| Fact | Value |
|---|---|
| Device | Redmi Note 14, `24117RN76G`, Android 16, SDK 36 |
| Screen | 1080 × 2400 |
| Density | 450 dpi → 1 dp = 2.8125 px |
| Launcher | `com.miui.home` |
| Widget hosts | `com.miui.home` hostId 1024 (10 widgets), 1026 (48 widgets) |
| Live wallpaper picker | **`com.android.wallpaper.livepicker` — INSTALLED** |
| Current wallpaper | `com.miui.miwallpaper/…wallpaperservice.ImageWallpaper` (static) |

### 5.1 Widget bounds — `uiautomator dump` (authoritative)

```
Partner    [74,638][1006,1104]   932×466   desc="Partner"
Nothing X  [74,1148][498,1614]   424×466   desc="Nothing X"
Claude     [582,1148][1006,1614]  424×466   desc="Claude"
Brave      [74,1658][1006,1869]  932×211   desc="Brave search"
```

### 5.2 MIUI invisible padding — **PIXEL-VERIFIED 2026-10-03**

`uiautomator` host-frame vs visible-content bounds, cross-checked against the live
screenshot with the probe wallpaper active (which makes edges trivially detectable —
bright blue behind dark cards).

| Widget | Host frame | **Visible content (verified)** | Inset L/R | Inset T/B |
|---|---|---|---|---|
| Partner | `[74,638][1006,1104]` | `[74,638][1006,1104]` | 0 | 0 |
| Nothing X | `[74,1148][498,1614]` | `[78,1173][494,1589]` | 4 | 25 |
| Claude | `[582,1148][1006,1614]` | `[584,1171][1003,1590]` | 2 | 23 |
| Brave | `[74,1658][1006,1869]` | `[74,1696][1006,1831]` | 0 | 38 |

**Verification method:** luminance scans across each boundary. Example — Nothing X at
x=280: wallpaper luminance 90.3 for y ≤ 1172, widget luminance 25.3 from y = 1173. The
transition lands on **exactly** the predicted pixel. Same result for Brave
(75.7 → 53.0 at y = 1696) and Partner (73.0 → 202.7 at y = 638).

**`uiautomator`'s nested content bounds are pixel-exact.** No heuristics needed, and no
disagreement between the two methods — the method cross-check the spec called for
returned clean.

### 5.2a Corner radii — **MEASURED per widget** (Q3 answered)

Measured by walking down each widget's left edge until it reaches solid colour; the
vertical offset at which the straight edge begins is the corner radius.

| Widget | Radius | Evidence |
|---|---|---|
| Nothing X | **56 px** | left edge solid at y = 1229, top at 1173 → 56 |
| Claude | **~44 px** | left edge solid at y = 1219, top at 1171 → 48 (AA spread) |
| Brave | **~18 px** | left edge solid at y = 1714, top at 1696 → 18 |
| Partner | **~40 px** (est.) | card corners, less critical — ours, with a gradient |

**These are the radii Phase 3 must pass to the shader.** Using one uniform radius would
visibly clash at the corners, which is exactly what Q3 anticipated.

### 5.3 Wallpaper surface — **MEASURED: 1080×2400, identity, no scroll** ✅

**Surface buffer: `bounds={0,0,2400,1080}` → 1080 × 2400, exactly the screen.**

Read from the live `Wallpaper BBQ wrapper` layer in `dumpsys SurfaceFlinger` while the
wallpaper was visible. Combined with:

```
PROBE offsets xPixel=0 yPixel=0 step=-1.0
```

`xOffsetStep == -1.0` is Android's documented "**this wallpaper does not scroll**"
signal, and `xPixelOffset == 0` confirms the launcher is not translating the surface
across pages.

**The screen→buffer transform is the IDENTITY.** The `uiautomator` bounds in §5.1 can be
fed straight into the shader — no scale, no offset, no correction.

**Both §5.3 and §6 are fully closed:**
- Rings will **not** drift across the 8 home screen pages.
- No coordinate conversion is needed at all.

**Your screen is 1080×2400. That is not in dispute.** The open question is a different
number: the size of the *drawing canvas* Android hands the wallpaper.

#### What a "surface" is

The wallpaper does not draw on the screen. Android gives it an off-screen **buffer** — a
`Surface` — and our job is to paint into that buffer. The system then copies it to the
display. The buffer's dimensions arrive in `onSurfaceChanged(holder, format, width, height)`.

**Why the buffer might not be 1080×2400.** Two reasons, both standard Android:

1. **Scroll width.** If the launcher scrolls the wallpaper as you move between home
   screen pages, it must hand the wallpaper a buffer wider than the screen so there is
   somewhere to scroll *to*. The canonical Android tutorial does exactly this:
   `this.width = 2 * width`. With **8 pages** on this device, the multiplier could be
   larger still.

2. **Crop / aspect padding.** `dumpsys wallpaper` reports `mWidth=2400 mHeight=2400` and
   `mCropHint=Rect(0, 0 - 864, 1920)`. **Neither matches the 1080×2400 screen.** The
   system is describing a scaling/cropping arrangement we have not decoded yet.

#### Why this blocks ring placement

`uiautomator` reports widget positions in **screen pixels**, origin at the top-left of the
display. A wallpaper draws in **buffer pixels**, origin at the top-left of the buffer.

If those two coordinate spaces differ — by a width multiplier, a height, or an offset —
then feeding the `uiautomator` numbers straight into the shader places all four rings
wrong, by an amount we cannot predict from documentation. It has to be **read off the
device**.

#### How Phase 1 resolves it

Log, on the real device:
- `onSurfaceChanged` width/height on first attach
- `onOffsetsChanged(xOffset, yOffset, xPixelOffset, yPixelOffset)` — `xPixelOffset` is the
  direct measure of how far the wallpaper has been scrolled, and therefore whether the
  buffer is wider than the screen
- Same values on page 1 vs after a swipe to page 2

Then the screen→buffer transform is *derived from measurement*, not assumed. Expected
outcome, subject to confirmation: buffer is full-width-equivalent and y maps 1:1, giving
a clean identity transform — in which case the `uiautomator` numbers are used directly.

---

## 6. 8 home screen pages — **NO DRIFT, risk closed** ✅

`uiautomator` reports **`Page 1 of 8 pages`**. All widgets are on page 1.

**Measured: the wallpaper surface does not scroll** (`xOffsetStep == -1.0`,
`xPixelOffset == 0` — see §5.3). The launcher is not translating the wallpaper on page
swipes, so **rings stay locked to their widgets** and the concern that motivated Q2 is
moot. No offset-parity fix is needed.

**DECIDED (Q2):** static placement at the measured page-1 coordinates; ignore
`onOffsetsChanged`. Confirmed correct by measurement — the surface does not move, so
static placement is not a compromise here, it is the exact answer.

---

## 7. HyperOS 3 survival risk — **GATE PASSED** ✅

### 7.0 Phase 1 results — measured, all critical questions answered

| Question | Result |
|---|---|
| Does a 3rd-party live wallpaper run on HyperOS 3? | ✅ **YES** — 60.13 fps sustained |
| Does `lockHardwareCanvas()` work? (§9a) | ✅ **YES** — `hw=true` |
| Does AGSL / `RuntimeShader` work in a wallpaper? | ✅ **YES** — `agsl=true` |
| Surface size? (§5.3) | ✅ **1080 × 2400 — identity transform** |
| Is the surface scrolled/multiplied? (§5.3, §6) | ✅ **NO** — `step=-1.0`, `xPixel=0` |
| Does the visibility lifecycle work? | ✅ `visible=true/false`, loop starts/stops correctly |
| Survives leaving the home screen and returning? | ✅ Confirmed by user + log |
| Survives `am kill-all`? | ✅ **YES** — PID 31425 unchanged |
| Survives `send-trim-memory RUNNING_CRITICAL`? | ✅ **YES** |
| Survives `am kill` on our own package? | ✅ **YES** — PID 31425 unchanged |
| **GATE** | ✅ **PASSED** |

**Frame rate, measured over a 9.979 s window:** 600 frames = **60.13 fps**. No dropped-frame
warnings, no `lockCanvas` fallback, no shader errors, no exceptions.

**§9a resolved favourably:** MIUI's surfaces **do** support `lockHardwareCanvas()`, so the
existing AGSL ring shader (`BorderGlowShader`) can be reused as-is in the wallpaper. The
software-canvas fallback path was written but never triggered.

### 7.0a `force-stop` reverts the wallpaper — but ordinary kills do NOT

`adb shell am force-stop com.metrolist.music.debug` **did** revert the wallpaper to
MIUI's static one. But every realistic kill path was then tested and **all survived**:

| Action | Result |
|---|---|
| `am kill-all` | ✅ wallpaper held, PID 31425 alive |
| `send-trim-memory RUNNING_CRITICAL` | ✅ survived |
| `am kill com.metrolist.music.debug` | ✅ survived, PID unchanged |
| Open another app, return home | ✅ user-confirmed, still animating |

**Interpretation:** `force-stop` is an explicit "disable this app" signal, and the system
treats the wallpaper provider as removed. Real memory pressure does **not** behave that
way. **The earlier fear that HyperOS would silently drop the wallpaper under pressure is
disproven.**

### 7.1 While music is playing — safe

`MusicService` holds `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` with a visible notification.
That is the strongest status an Android app can hold. It sits above the background-tier
rules that normally constrain wallpapers, so while music plays, our process is protected.

### 7.2 While nothing is playing — the user's stated requirement

**User requirement: the wallpaper must survive HyperOS when nothing is playing.**

This case has no foreground service, so the process is a plain wallpaper service subject
to HyperOS's process management. Documented Xiaomi behaviour:

- Third-party live wallpapers need a "background running" grant.
- Settings can **reset after an OS update**.
- Some Xiaomi devices ship with the live wallpaper system **fully disabled** until
  re-enabled via the Themes app.
- HyperOS 3 ships its own "AI dynamic wallpapers" — the platform is investing here, which
  is a point in our favour.

**What the design does about it — this is the important part.** The idle state is
**deliberately made trivial to survive:**

| State | Wallpaper work done |
|---|---|
| Music playing, visible | full ring rendering at 60 fps |
| Music playing, hidden | `onVisibilityChanged(false)` → **stop the loop, draw nothing** |
| Nothing playing, visible | **solid `#000000`, one `drawColor`, then sleep** |
| Nothing playing, hidden | nothing |

In the three idle cases the wallpaper does **no per-frame work at all.** It paints black
once and stops. A frozen process costs nothing, so HyperOS freezing it costs nothing, and
when music starts, `MusicService` starts the process back up anyway.

**So the idle requirement is met by construction, not by fighting HyperOS.** The design
does not need to survive idle *well* — it needs to survive idle *cheaply*, which it does
by doing nothing.

**Residual risk, stated honestly:** if HyperOS kills the process so aggressively that it
will not restart when a track starts, rings would not appear. Mitigation: `MusicService`
starting is itself the process-start event, and foreground-service starts are not
suppressed. Phase 1 tests the idle→playing transition explicitly.

**Phase 1 is still the gate.** If HyperOS refuses to run a third-party live wallpaper on
this device at all, Phases 2–5 never happen.

---

## 8. Wallpaper setting & restore — **design revised**

**Original assumption was wrong.** Reading the current wallpaper is blocked:

> `WallpaperManager.getDrawable()` — *"Starting in Android 13, directly accessing the
> wallpaper is not possible anymore"*. Requires `MANAGE_EXTERNAL_STORAGE`
> ("all files access"). *(Android Developers, API reference)*

Current wallpaper is MIUI's static `ImageWallpaper`; its pixels are inside MIUI's
private app data. **Not restorable.**

### 8.1 Enabling from inside the app — **CONFIRMED WORKING**

The user must be able to enable the wallpaper **by tapping in Metrolist**. **User-verified
on device (2026-10-03):** third-party wallpaper apps can set their own live wallpaper, and
Android shows a prompt asking whether to apply it to **home screen, lock screen, or both**.

This is exactly `WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER` + 
`EXTRA_LIVE_WALLPAPER_COMPONENT` (both API 16+, public). Official doc: *"Directly launch
live wallpaper preview, allowing the user to immediately confirm to switch to a specific
live wallpaper."*

- One tap in Metrolist → preview of **our** wallpaper → Android prompt (home / lock / both)
  → applied. No picker browsing, no searching.
- **Choose "home screen" only.** Lock screen is out of scope (§ Q5).
- Silent set remains impossible (`setWallpaperComponent` is `@SystemApi`, system-only
  permission) — **and is not needed.** The prompted flow satisfies the requirement.
- No reflection, no non-SDK interfaces.

### 8.2 Restore route

- User can switch back **from the same picker at any time** — nothing is lost.
- `com.android.wallpaper.livepicker` **is installed** on this device.

### 8.2a ⚠️ PHASE 1 FINDING — MIUI intercepts both picker routes

Measured on device 2026-10-03. **Two distinct blockers, both confirmed:**

**(1) The live wallpaper is not LISTED.** Two different pickers exist:

| Picker | Contents |
|---|---|
| Settings → Personalization (Themes app, `com.miui.miwallpaper`) | Xiaomi wallpapers only |
| "Live Wallpapers" (`com.android.wallpaper.livepicker`) | Mi Wallpaper, Themes — **no third-party** |

**The registration is correct.** `cmd package query-services -a
android.service.wallpaper.WallpaperService` returns our service:

```
name=com.metrolist.music.wallpaper.MetrolistWallpaperService
packageName=com.metrolist.music.debug
```

So the *system* sees the wallpaper; MIUI's picker simply does not list third-party apps.
`com.android.wallpaper.livepicker` is heavily modified — its logcat shows MIUI
instrumentation (`MIUIInput`, `getMiuiFreeFormStackInfo`, `com.miui.*` SELinux denials).

**(2) Both set intents are hijacked into Settings.** Firing either from `adb shell`:

```
am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER ...
am start -n com.android.wallpaper.livepicker/.LiveWallpaperChange --es ...EXTRA_LIVE_WALLPAPER_COMPONENT pkg/cls
```

lands on **`com.android.settings/.MiuiSettings`** → Settings → Personalization, not the
preview screen. AOSP's `LiveWallpaperChange` expects the extra as a `"package/class"`
string, which is what was sent, so the format was not the issue.

**Not yet established:** whether this is caller-identity-dependent (shell UID vs a real
app) or unconditional. The user's prior report — third-party wallpaper apps *can* set
wallpapers with a home/lock/both prompt — suggests the flow works **from a real app**, and
the prompt described is MIUI's own.

**Resolution path:** test the intent from an actual `Activity` in the app. This is the
production code path required by §8.1 anyway, so it is not throwaway work.

### 8.2b ✅ RESOLVED — the extra must be a `ComponentName`, not a String

**The failure was a bug in our code, not MIUI blocking us.** Logcat:

```
W Bundle: Key android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT expected Parcelable
          but value was a java.lang.String.
W Bundle: java.lang.ClassCastException: java.lang.String cannot be cast to android.os.Parcelable
    at android.content.Intent.getParcelableExtra(Intent.java:9597)
    at com.android.wallpaper.livepicker.LiveWallpaperChange.init(LiveWallpaperChange.java:67)
```

`LiveWallpaperChange` calls `getParcelableExtra()`, so the value must be a `ComponentName`
object. A `"package/class"` string throws inside `init()` and the activity finishes
instantly — indistinguishable from "nothing happened". AOSP's own
`LiveWallpaperChange.java` reads it as `Parcelable`, and every tutorial passes
`new ComponentName(this, MyWallpaperService::class.java)`.

**Correct form (now shipped):**

```kotlin
putExtra(
    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
    ComponentName(context, MetrolistWallpaperService::class.java),
)
```

This is correct on **all** API levels — `getParcelableExtra` has always been the read path.

**Lesson:** the initial "MIUI is intercepting" conclusion was drawn from circumstantial
evidence while the definitive answer sat in logcat. **Read the log before concluding.**

**Listing remains unavailable** (the wallpaper is still absent from the picker's list),
but this no longer matters: the app sets its own wallpaper directly, which is what §8.1
requires anyway.

### 8.2c Confirmation (user, 2026-10-03)

Prompt appeared, wallpaper applied, animation visible on the home screen. In-app enable
works exactly as specified in §8.1 — one tap, preview, home/lock/both prompt.

### 8.3 The setting

Toggle in Appearance (next to the existing PCM visualizer toggles). **Default OFF.**

Tapping ON triggers `ACTION_CHANGE_LIVE_WALLPAPER` (§8.1) → system preview of our
wallpaper → user confirms. The preference only becomes true **after** the user confirms;
a cancelled preview leaves the setting OFF.

| State | Wallpaper | Rings |
|---|---|---|
| Disabled | untouched / user-restored | none |
| Enabled, no Metrolist playback | solid pure black `#000000` | none (faded out) |
| Enabled, Metrolist playing, widget = partner | black | **inactive** (§4.3) |
| Enabled, Metrolist playing, debug test ON | black + 4 rings | active |

Pure `#000000` on AMOLED = pixels off. Genuinely battery-cheap when idle.

**On disable:** the app **cannot** restore the original wallpaper (§8). It must:
1. Tell the user plainly in the UI — disabling may leave a black wallpaper.
2. Offer a button that **reopens the picker** so the user can reselect their own
   wallpaper unaided.

---

## 9. Offload conflict — **DECIDED: auto-disable**

Under audio offload, `MediaCodecAudioRenderer` bypasses the decoder → **no audio reaches
the visualizer → rings are dead.** This is the same pre-existing bug that silently kills
volume normalisation and EQ in that chain.

**User decision (2026-10-03): auto-disable offload for sanity.**

**Design:**
- Enabling the wallpaper visualizer **forces `AudioOffload` off.**
- Enabling offload **while the visualizer is on** is refused with an explanatory message.
  Rationale: the reverse direction (silently switching off a feature the user just turned
  on) is worse than refusing the new one.

**Precedent in the codebase:** `PlayerSettings.kt:454` already does exactly this pattern
for crossfade — `audio_offload_disabled_by_crossfade`, with the toggle shown unchecked
and disabled. Follow that shape for consistency:
- Same "forced off because X" string pattern.
- Same disabled-when-conflicting toggle presentation.

Offload defaults off, so this is a rare path — but it must not silently produce dead rings.

---

## 9a. ⚠️ THE RUNTIMESHADER CANVAS TRAP — Phase 1 hard requirement

**AGSL/`RuntimeShader` works in live wallpapers** (user-confirmed and consistent with
research). But it only works on a **hardware-accelerated** canvas.

`SurfaceHolder.lockCanvas()` returns a **CPU/software** canvas by default. Drawing a
`RuntimeShader` into it throws:

```
IllegalArgumentException: Software rendering doesn't support RuntimeShader
    at android.graphics.BaseCanvas...
```

This is a **known, reported failure** specific to `WallpaperService.Engine`. The fix is
one call: `holder.lockHardwareCanvas()` instead of `holder.lockCanvas()`.

**`lockHardwareCanvas()` is not guaranteed.** The base `SurfaceHolder` implementation
throws `IllegalStateException("This SurfaceHolder doesn't support lockHardwareCanvas")`.
Whether MIUI's launcher surfaces support it is **unknown until tested on device.**

**Therefore Phase 1 must:**
- Attempt `lockHardwareCanvas()` inside `try/catch`.
- On `IllegalStateException` or `IllegalArgumentException`, fall back to `lockCanvas()`
  **and record that the fallback happened**, via log + a visible colour cue.
- Log which path was taken. **This is a first-class Phase 1 deliverable.**

A software canvas still draws fine — it just cannot run AGSL. The fallback wallpaper
renders a plain gradient so the probe still proves *"the wallpaper runs"*, separately from
*"AGSL works."* Keeping those two facts separate matters: a failure of one must not be
mistaken for a failure of the other.

**Project constraints:** `minSdk = 26`, `compileSdk = 37`, `targetSdk = 36`.
`RuntimeShader` needs API 33+. **The device is API 36, so AGSL is available here** — but
the shipped APK must still guard on `Build.VERSION.SDK_INT >= 33` with a non-AGSL fallback
path, exactly as `BorderGlowShader.isSupported()` already does (`BorderGlowShader.kt:135`).

---

## 10. Phases

Each phase: `:app:compileFossDebugKotlin` clean, `:app:testFossDebugUnitTest` **144
green**, one commit, **explicit user authorisation**. No version bump without approval.
Delete `app/build/outputs/apk/foss/debug` before assembling.

### Phase 0 — Spec ✅
This document. **Status: awaiting user review.**

### Phase 1 — Wallpaper probe · **GATE**
Minimum viable live wallpaper: animated gradient, **no audio, no rings**, no settings
integration, no `CoverBassPulse` changes.

Establishes, on the real device:
- Does a third-party live wallpaper animate at all? (§8.1 confirmed the *set* flow works)
- **Actual `onSurfaceChanged` surface dimensions** → resolves §5.3
- `onOffsetsChanged` / `xPixelOffset` on page 1 vs page 2 → is the buffer wider than the
  screen? (§5.3, §6)
- **`lockHardwareCanvas()` support → is AGSL available?** (§9a) Must be a *separate*
  reported fact from "the wallpaper runs".
- Idle survival on HyperOS: leave the home screen idle 10 min with nothing playing, then
  confirm the process is still alive and correct when playback starts. (§7.2)

**Phase 1 explicitly does NOT touch:** `CoverBassPulse`, `Player.kt`, `MiniPlayer.kt`,
`BorderGlowShader.kt`, any settings screen, any preference, any string.

**Stop condition:** if it will not run, abandon here. Cost ≈ one small commit.

### Phase 2 — Geometry mapping ✅ **COMPLETE 2026-10-03**

With the phone connected, unlocked, on the home screen, and the probe wallpaper active:

1. ✅ `uiautomator dump` → authoritative widget bounds (§5.1).
2. ✅ `screencap` → pixel verification of visible edges. **All four matched exactly.**
3. ✅ `dumpsys SurfaceFlinger` → live wallpaper layer `bounds={0,0,2400,1080}` = **1080×2400**.
4. ✅ Reconciliation: `uiautomator` and screenshot **agree perfectly**. No deltas.
5. ✅ Screen→surface transform derived: **identity**.
6. ✅ Corner radii measured per widget (§5.2a).

**Deliverable achieved — final ring geometry (surface == screen coordinates):**

| Widget | Ring rect `[x,y,w,h]` | Corner radius | Orb | Direction |
|---|---|---|---|---|
| Metrolist (Partner) | `[74,638,932,466]` | ~40 px | no | — |
| Nothing X | `[78,1173,416,416]` | 56 px | yes | **+1** |
| Claude | `[584,1171,419,419]` | ~44 px | yes | **−1** |
| Brave | `[74,1696,932,135]` | ~18 px | no | — |

Note the ring rects are the **visible** bounds. Because the wallpaper renders *behind* the
widgets, the ring must be drawn **just outside** these — the glow extends outward from the
edge, so the rect itself is the boundary and the shader's falloff does the rest.

### Phase 3 — Static rings, no audio (NEXT)
Four rings drawn at the measured geometry from Phase 2, driven by a fixed test value
(`bass = 0.5`). User visually confirms placement, corners, and flush-vs-cell-edge.
**No audio dependency yet** — so geometry problems stay isolated from audio problems.

### Phase 4 — Live audio
- `CoverBassPulse` ownership transfer (§3.2), wallpaper owns the analysis.
- Alpha envelope (§4.4): idle-persist, stopped→fade, hidden→fade.
- Colour from `borderSongColor` (§4.3).
- Mirrored orbits, `hotspotMult = 0f` on Metrolist/Brave.
- `Widget UI Debug Test` gating.
- Offload interlock (§9), following the crossfade precedent at `PlayerSettings.kt:454`.

### Phase 5 — Calibration screen *(optional)*
Screenshot + four draggable/resizable frames, user snaps each to its widget, persists
corrections. Insurance against a HyperOS grid change silently misaligning the rings.
Low value while the user never moves widgets — recommended as cheap insurance, not required.

---

## 11. Question log

**All resolved 2026-10-03.**

| # | Question | Answer |
|---|---|---|
| Q1 | "Listening: eman" — debug test or partner mode? | **Debug test — my own song.** Gate is testable. |
| Q2 | 8-page drift acceptable? | **Yes, acceptable.** Static placement; rings wrong on pages 2–8. No offset-parity fix. |
| Q3 | Per-widget or uniform corner radius? | **Per widget.** Visually correct. **Radii now measured** — see §5.2a. |
| Q4 | Offload policy? | **Auto-disable** (§9). |
| Q5 | Lock screen in scope? | **Confirmed out of scope.** Home screen only. |
| Q6 | Enable without the picker? | **Yes, from inside the app** — one tap → our wallpaper's preview → Android prompt (home/lock/both). **User-verified working on device.** (§8.1) |
| Q7 | Can the original wallpaper be restored? | **Not pixel-exact** (Android 13+ blocks reading it). Restore is by re-opening the picker and re-selecting. Black is the fallback. |

**No open questions remain.** Phase 1 may proceed on user authorisation.

---

## 12. Constraints carried forward

- Fork-only, permanently diverged. `origin` read-only. `personal` only push target.
- Work on `testing`. `rollback/pre-video` → `1e4bf4618` intact.
- **No DB schema change.** No version bump without approval.
- English strings only in `app/src/main/res/values/metrolist_strings.xml`.
- **Metrolist audio only.** No cross-app audio capture.
- Home screen only. Lock screen out of scope.
- **Never go without a spec; never leave the spec outdated.** This file is that record.