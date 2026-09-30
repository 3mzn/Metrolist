# SPEC_SPOTIFY_CANVAS — Spotify Canvas as the Player Background

Status: ACTIVE — Phases 0, 1, 1.5 and 2 complete. Phase 3 next.
Date: 2026-09-29 (Phase 2 results folded in 2026-09-30)
Branch: `testing`
Base: `7f168bf`

---

## 1. Goal

Port Spotify Canvas (animated looping artwork) from SimpMusic into Metrolist, and use it
as the full-screen background of the expanded main player.

A 2-second long-press on the cover art washes the player's entire background — gradient /
blur / theme, the bass-reactive particle visualizer, and the track-change colour wash — into
the track's Spotify Canvas, rendered full-screen behind the artwork and all controls.

A short swipe up on the cover art fades the artwork to full transparency to reveal the Canvas.
A second swipe up restores it. A second 2-second long-press dismisses the Canvas and
gracefully restores the normal background.

---

## 2. Licensing

Both projects are GPL-3.0.

- SimpMusic: `LICENSE` = GPL-3.0. `core/` submodule (`maxrave-dev/core`) = GPL-3.0.
- Metrolist: `LICENSE` = GPL-3.0.
- The ported Spotify sources carry **no per-file license headers** (only `package`/`import`
  lines), so there is no conflicting attribution clause to satisfy.

### 2.1 Obligations

- GPL-3.0 §5(a): every ported file must carry a header naming the original author, the source
  project, and GPL-3.0.
- Original author: **maxrave-dev** (SimpMusic). Portions of the TOTP logic derive from
  `spotify_monitor` (Python, referenced in SimpMusic's `SpotifyTotp.kt`).
- Metrolist's `README.md` gets one new row in the "Libraries & Integrations" table.
  **User has explicitly approved this README edit** (AGENTS.md normally forbids it).

---

## 3. Verified reference behaviour

Established by instrumenting SimpMusic v2.2.0 (code 59) on an emulator, with the user's
Spotify and YouTube Music accounts signed in.

### 3.1 What works

- Spotify login succeeds via a WebView; `spdc` cookie is captured.
- Client token obtained from `open.spotify.com/api/server-time` with spoofed
  `App-platform: WebPlayer` and `Spotify-App-Version: 1.2.61.20.g3b4cd5b2`.
- TOTP secret fetched at runtime from
  `raw.githubusercontent.com/xyloflake/spot-secrets-go/refs/heads/main/secrets/secretDict.json`.
- Canvas renders and loops in the player for tracks that have one.

### 3.2 The gate that blocked Canvas (and must not be reproduced)

Canvas is unreachable for items whose metadata description is `"Video"`, not `"Song"`:

```kotlin
// SimpMusic SharedViewModel.kt:286
if (nowPlaying.mediaItem.isSong() && nowPlayingScreenData.value.canvasData == null) {
    getCanvas(...)
}
// AllExt.kt:45
fun GenericMediaItem.isSong() = description?.contains(MERGING_DATA_TYPE.SONG) == true
```

Proven by log-line counts on device: `Timeline job: 3`, `Duration is: 0`, `Get lyrics: 4` —
the Canvas branch was never entered, while the sibling lyrics path ran normally.

**Metrolist has no equivalent gate.** It has no video/Canvas slot at all, so this constraint
does not exist here. The failure is that there is nothing to reproduce, not that we must
reproduce it.

### 3.3 Correction of a prior false claim

An earlier statement that Spotify Canvas requires Premium was wrong. Spotify community staff
state Canvas is "account bound, so it's not dependent on your current plan." Premium is **not**
a gate. The user confirmed Canvas works on their non-Premium account.

---

## 4. Architecture

### 4.1 New module: `:spotify`

Follows the existing pattern of `:lrclib`, `:lastfm`, `:shazamkit`, `:paxsenix`.
The module owns **networking only**; all rendering stays in `:app`.

Rationale for isolation: this is undocumented, spoofed, third-party API with a hard runtime
dependency on an external secrets repository. It will break eventually. A self-contained
module means removal is one directory plus a handful of call sites, not an audit of `:app`.

### 4.2 Porting rule — copy directly, do not rewrite

**SimpMusic's Spotify code is production, working code, verified on device. It is READY to be
taken directly.**

The implementation is expected to be a **faithful copy** of the SimpMusic source, not an
original reimplementation. The working code is the asset. Rewriting it would introduce new
bugs against an API that is already proven to work, for no benefit.

**Default action: copy the files as they are.** Only these changes are expected, and each is
mechanical rather than a redesign:

1. **Package/import rewriting** — `com.maxrave.spotify` → Metrolist's package convention.
2. **Replacing SimpMusic-internal helper imports** with Metrolist equivalents or local copies
   (see §4.2.1 — three such imports exist).
3. **Collapsing the KMP `expect`/`actual` pair** to a single Android implementation.
4. **Adding GPL-3.0 attribution headers** (§2.1).

Anything beyond those four — restructuring, "improving", renaming for taste, or writing fresh
logic to replace working logic — is **out of bounds** and requires explicit discussion first.
If the copied code looks wrong, the response is to **verify against SimpMusic's running
behaviour**, not to rewrite it.

### 4.2.1 Known dependency gaps (verified, not assumed)

Auditing every import in `core/service/spotify/src/commonMain` surfaced four things SimpMusic
supplies that Metrolist does not. These are the *only* adaptations required, and each has a
deterministic answer:

| SimpMusic import / dep | Status in Metrolist | Action |
|---|---|---|
| `com.maxrave.ktorext.curl.CurlLogger` | **Absent** — SimpMusic-internal Ktor logging plugin | **Copy it** from `core/service/ktorExt` into `:spotify`. It is small and self-contained; copying preserves the exact logging behaviour. |
| `com.maxrave.ktorext.getEngine` | **Absent** — KMP `expect`/`actual` engine selector | **Inline** as `HttpClient(OkHttp)`, matching Metrolist's existing engine choice (`:innertube` uses `HttpClient(OkHttp)`). One line, no behaviour change. |
| `com.maxrave.ktorext.encoding.brotli` | **Absent** — SimpMusic's brotli `ContentEncoding` helper | **Copy** the helper. Metrolist has `org.brotli:dec` (decode only) but no encoder; the Spotify client needs the encoder. |
| `io.ktor:ktor-serialization-kotlinx-protobuf` | **Absent** — Metrolist's version catalog has Ktor 3.5.2 with **no** protobuf serialization artifact | **Add the dependency.** Required — not optional. |
| `dev.turingcomplete:kotlinonetimepassword` | **Absent** | **Add.** Required by `SpotifyTotp`. |
| *(found at runtime, Phase 3)* `org.apache.http.legacy` `<uses-library>` | **Declared by Metrolist** | **Removed.** Its platform stub shadows the real `commons-codec` and breaks the TOTP. See §14.1f. |

**Ktor version difference: 3.6.0 (SimpMusic) vs 3.5.2 (Metrolist).** A minor-version skew, not a
major one. The APIs used here (`HttpCache`, `HttpSend`, `AcceptAllCookiesStorage`, `ProxyConfig`,
`ContentNegotiation`, `userAgent`) are all stable across 3.x. **Do not upgrade Metrolist's Ktor
to chase parity** — that would risk the whole app's networking to serve one new module. Accept
3.5.2 and fix only if the compiler proves something genuinely missing.

**Protobuf is load-bearing, not incidental.** `CanvasResponse` and `TokenResponse` use
`@ProtoNumber` annotations, and the client sets
`Accept: application/protobuf` / `Content-Type: application/protobuf` with the Ktor `protobuf()`
converter. Spotify is being spoken to in protobuf. The `@ProtoNumber` annotations **must** be
preserved verbatim — stripping them would silently corrupt field mapping rather than fail
loudly, which is the most dangerous possible kind of copy error here.

### 4.2.2 Ported files (from `core/service/spotify`, ~850 LOC)

| File | LOC | Notes |
|---|---|---|
| `SpotifyClient.kt` | 258 | Ktor client, spoofed UA, endpoints |
| `SpotifyAuth.kt` | 97 | Login orchestration |
| `Spotify.kt` | 88 | Public facade: `getCanvas`, `getSpotifyLyrics` |
| `SpotifyTotp.kt` | 62 | TOTP generation |
| `models/*` | ~240 | Canvas, client token, personal token, search, lyrics |
| `extensions/StringExt.kt` | 8 | |

**KMP collapse:** SimpMusic's `expect`/`actual` split must be collapsed. Files dropped entirely:
`SpotifyTotp.android.kt` (8), `SpotifyTotp.jvm.kt` (8), `SpotifyTotp.ios.kt` (4). The
`expect fun generateTotp` becomes a single Android implementation using
`kotlinonetimepassword`'s `GoogleAuthenticator`, matching Metrolist's existing pattern of
Android-only module actuals.

**Not ported:** the `spotify` module's search/`SearchResponse` types are only needed for the
lyrics path; they are retained because `SpotifyLyrics` shares the client, but the **lyrics
feature is out of scope for this spec** (see §12).

### 4.3 Metrolist-side additions

| Concern | Location |
|---|---|
Canvas fetch orchestration | `:app` `utils/spotify/` (thin wrapper over `:spotify`) |
Preference keys | `constants/PreferenceKeys.kt` |
Settings screen | `ui/screens/settings/SpotifySettings.kt` |
Canvas switch | `ui/screens/settings/PlayerSettings.kt` |
Login row | `ui/screens/settings/integrations/IntegrationScreen.kt` |
Video surface | `ui/player/CanvasBackgroundLayer.kt` (new) |
Artwork fade state | `ui/player/Player.kt` + `Thumbnail.kt` |

---

## 5. Visual states

Three states. **Particles return only when the Canvas itself is dismissed** — they are not a
fourth state, and never coexist with the Canvas.

| State | Background | Particles | Artwork | Entered by |
|---|---|---|---|---|
| **Normal** | gradient / blur / theme | **yes** | opaque | default, or 1s long-press to dismiss Canvas |
| **Canvas on** | Canvas video | **no** | opaque | 1s long-press |
| **Canvas revealed** | Canvas video | **no** | transparent | swipe up on artwork |

**Invariants**

- Artwork transparency is **only** ever present while a Canvas is rendering.
- Dismissing the Canvas returns the artwork to **fully opaque** (1000ms fade, §6.4).
- Particles are never drawn over a Canvas.
- The Canvas is never drawn over the controls.

### 5.1 All layouts

Canvas behaves identically in every layout — normal portrait, `isFullScreen` mode, landscape
(`isLandscape = true`), and tablet. One code path: "when Canvas is active, replace the
background layer." No layout-specific code.

### 5.2 Fill behaviour

Canvas fills the entire screen. **No letterboxing, no black bars** — centre-crop
(`ContentScale.Crop`). A Canvas is a 9:16 vertical video; in landscape this crops heavily,
which is accepted rather than letterboxed.

> **⚠ The crop is correct; the resampling is not.** Implemented with
> `VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING`, which is Media3's **nearest-neighbour** path —
> upscaling is visibly soft. Accepted for now; see **§14.2a**.

### 5.3 Controls

Controls (title, seekbar, transport row, bottom button row) remain **fully readable with no
scrim or dim overlay** for now. The Canvas sits behind them with nothing between. This is a
deliberate decision, not an oversight — revisit only if legibility proves to be a problem.

### 5.4 Lyrics

Canvas continues to render when inline lyrics are shown. Lyrics draw above the Canvas. The
long-press gesture is **unavailable** on the lyrics view, because the gesture requires the
artwork and `AnimatedContent` has replaced it.

---

## 6. Gestures

### 6.1 Long-press — engage Canvas

- **Duration: 1000ms**, **invisible**: no progress ring, no overlay, no visual affordance.
  - **Changed from 2000ms by user decision during Phase 5.** A shorter window makes it
    easier to trigger by accident, so the early-release path carries more of the real
    usage than the original design assumed. The single value is `CANVAS_HOLD_MS` in
    `Thumbnail.kt`; nothing else in the gesture depends on its magnitude.
- **Haptic: one short vibration at the moment the 1s threshold is met** — not on press.
  Rationale: the app is for the user and one other person; discoverability aids are not wanted.
  The vibration is the sole confirmation that the hold registered.
- **Cancel on early release.** Releasing before 1000ms does nothing.
- **No network fetch until 1000ms is actually held.** The fetch starts at the threshold, so an
  abandoned press costs nothing.
- Works in all layouts (portrait, landscape, fullscreen, tablet).
- Unavailable on the lyrics view.

### 6.2 Long-press — dismiss Canvas

A second 1s long-press dismisses. Background, particles, and artwork all return over **2000ms**
(symmetric with the wash-in). See §6.4 for the artwork's separate 1000ms fade.

### 6.3 Swipe up on artwork

- Hit zone: **the artwork square only.**
- **Vertical swipe up only.** Swipe down does nothing — the gesture must not be reversible in
  the down direction.
- **Threshold: 60dp** vertical.
- **Not finger-tracked.** Below-threshold movement is ignored; a qualifying swipe runs a
  **fixed 1000ms** fade of the artwork's alpha to 0 (out) or 1 (in).
- **Two-state toggle**, not a scrubbable value. One qualifying swipe fades out; the next fades
  back in. The two states are exclusive: revealing requires the artwork hidden, and vice versa.
- **Active only while a Canvas is rendering.** When no Canvas is active there is no vertical
  swipe on the artwork at all.
- **Independent of `swipeThumbnail`.** If horizontal song-swipe is disabled in settings,
  vertical swipe still works. The two are detected independently and do not interfere.
  This is also why vertical swipe is gated on Canvas being active: in the default state the
  artwork has no vertical gesture at all, so the horizontal song-swipe cannot be disturbed.

### 6.4 Artwork opacity animation

| Transition | Duration |
|---|---|
| Reveal / hide artwork (swipe) | **1000ms** fixed |
| Dismiss Canvas → artwork opaque | **1000ms** fixed |
| Dismiss Canvas → background + particles | **2000ms** crossfade |

> **Both directions of the wash are one value, `CANVAS_WASH_MS`, and both sides animate.**
>
> The spec originally said 800ms and the first implementation was reported as "a quick
> kinda-smooth 200ms-feeling snap". **The duration was never the problem.** Only the Canvas
> side was animated; the normal background was hard-cut the instant the first frame landed,
> so nothing was crossfading and the perceived speed was the cut. Both layers now ride the
> same curve via `legacyAlpha = 1f - canvasWashAlpha`.
>
> Raised 800ms → 1000ms → **2000ms** by user preference once the crossfade actually worked.
>
> One trap worth recording: particles are faded by a `Modifier.alpha` at the **call site**,
> not by passing a transparent `baseColor`. `PlayerParticles`' draw loop does
> `baseColor.copy(alpha = a * ...)`, which *overwrites* the incoming alpha rather than
> multiplying it — so a transparent `baseColor` would have silently done nothing.

### 6.5 Two independent fades, deliberately unequal

The artwork's 1000ms and the background's 2000ms are **separate values on purpose**, not an
oversight to be tidied up.

| Value | Lives in | Constant |
|---|---|---|
| Artwork alpha | `Thumbnail.kt` | `CANVAS_ARTWORK_FADE_MS = 1000` |
| Background wash | `CanvasBackgroundLayer.kt` | `CANVAS_WASH_MS = 2000` |

The artwork returns in half the time the background crossfade takes, so it is restored well before
that finishes. Tying them together would mean watching a semi-transparent artwork sit over the old
background for the whole two seconds.

Artwork duration history: spec said **400ms**, raised to **1000ms** by user decision during
Phase 6, on the same grounds as the wash — 400ms read as a snap against a 2000ms background.

The artwork fade is applied inside the `graphicsLayer` that already exists for the bass-pulse
scale (`alpha = artworkAlpha`), not as a second layer, and it fades the **whole artwork square**
including the cast button in its top-right corner. The cast button is positioned against that
square and is part of it; leaving it behind would strand a control floating over the Canvas.

---

## 7. Canvas playback

### 7.1 Surface

**TextureView.** Not SurfaceView, not manual frame decoding.

SurfaceView punches a separate hole in the window and is drawn above the UI layer by the
system. It would ignore the Canvas alpha wash (popping in at full opacity), would sit **above** the
controls rather than behind them, and would break the graceful restore. TextureView
obeys normal UI compositing, so fades and z-order work.

Cost accepted: slightly higher GPU memory and battery than SurfaceView, because frames are
composited through the normal UI pipeline rather than going straight to the display. For a
looping 3–8 second video on a music screen this is a bounded cost, and the user has stated
battery is not a concern.

### 7.2 Player

A **second ExoPlayer instance, created and owned by the UI layer** (`:app`), not by
`MusicService`. The Canvas is a video with no audio, so it needs no media session, no
notification, and no queue integration.

**Audio safety — the lesson from the previous failed attempt:**

- The Canvas ExoPlayer is **muted**, and configured with
  `setAudioAttributes(videoOnlyAudioAttributes, handleAudioFocus = false)`.
- It **can never take audio focus.** The main player's audio is untouchable, even if the Canvas
  misbehaves.
- The previous attempt merged audio and video into one `MergingMediaSource`, which failed
  because `ProgressiveMediaSource` publishes a placeholder timeline until its extractor reports
  source info, and `MergingMediaPeriod.selectTracks` then throws
  `IllegalStateException: Children enabled at different positions.` That architecture is
  explicitly rejected. A separate, isolated, muted video player avoids the entire class of
  problem.

### 7.3 Lifecycle

| Event | Behaviour |
|---|---|
Track change | Tear down and rebuild for the new track |
Player collapsed to mini player | Player stays **alive**; Canvas simply not rendered |
Player re-expanded | Canvas still present — no re-init, no black flash |
Song paused | **Canvas keeps looping** (user decision; battery explicitly not a concern) |
App backgrounded | **Keeps looping** (user decision) |
Feature disabled / Spotify logged out | Release the player |

Keeping the player alive across collapse is deliberate: re-initialising on every expand would
flash black.

### 7.4 First frame

The existing background is **kept until the Canvas's first frame is ready**, then the 2000ms
wash begins. There is **no black flash** at any point in the sequence.

---

## 8. Settings

### 8.1 Two separate placements (user decision)

**A. Canvas switch → `Settings → Appearance → Player`**
(i.e. `ui/screens/settings/PlayerSettings.kt`)

**B. Spotify login → `IntegrationScreen`**
(i.e. `ui/screens/settings/integrations/IntegrationScreen.kt`, alongside the existing
Last.fm and Discord rows)

The two are deliberately in different places: the switch is a player-behaviour preference, the
login is an integration.

### 8.2 The logged-out switch

When Spotify is not logged in, the Canvas switch is **visible but greyed out**, with a
subtitle explaining why (e.g. "Log in to Spotify to enable"). This is the Material 3 convention
for a control disabled by a prerequisite.

**Tapping it anyway still works:** it navigates to the Spotify login and shows a toast
("Log in to Spotify for Canvas to work").

This is a deliberate deviation: `PlayerSettings.kt` gains a navigation and a Spotify-state
dependency, so a settings switch can trigger navigation rather than only flipping a value.
Chosen because it keeps the feature discoverable while remaining visually honest.

**After successful login:** return to Player settings with the switch now enabled, and the
login row in IntegrationScreen reads "Log out from Spotify".

### 8.3 Default

**Canvas is ON by default**, consistent with the earlier D21 decision on the video background.

### 8.4 Keys

New keys in `PreferenceKeys.kt`:
- `SpotifyCanvasEnabledKey = booleanPreferencesKey("spotifyCanvasEnabled")`, default `true`
- Spotify token storage (cookie / client token / personal token + expiries)
- Consecutive-failure counter

All strings in `app/src/main/res/values/metrolist_strings.xml` (English only), per AGENTS.md.

---

## 9. Caching

### 9.1 Positive

Cache the Canvas video to disk, mirroring SimpMusic's `spotifyCanvas` `SimpleCache`. Avoids a
network round-trip on every play; Canvas URLs are stable per track.

### 9.2 Negative — and the user's clarification

Cache the "this track has no Canvas" verdict, so repeat plays do not re-request it.

**But only after multiple failed attempts, so the verdict is 100% certain.** A single failure
is not sufficient evidence.

**Only clean negatives are cached.** The failure taxonomy:

| Outcome | Cache as "no canvas"? | Reason |
|---|---|---|
| 404 / track has no canvas | **Yes** | Permanently true |
| Empty-but-successful response | **Yes** (only after 3 attempts) | Spotify confirms, no video attached |
| 401 / 403 | **No** | Token/account issue; needs re-auth, not a "no canvas" |
| Timeout / no network | **No** | Transient |
| 5xx | **No** | Spotify is degraded |

A transient failure must never permanently disable a Canvas the user actually wants. The
failure counter tracks consecutive hard failures and **resets on any successful load** —
otherwise three failures spread across a week would trip it.

**Notification threshold: N = 3 consecutive track-level failures.**
On trip: the feature turns itself off, the user gets **one** toast —
On trip: a **high-priority notification** saying why — "can't reach Spotify", "your session
expired, log in again", or "Spotify is having trouble". **The feature is NOT disabled** and
nothing re-arms automatically; the settings switch is the manual control.
**stays off** until manually re-enabled. No automatic retry/cooldown.

### 9.3 Login gating recap

There is **no manual token entry** anywhere — SimpMusic has no such field, and neither will
Metrolist. The WebView login is the only path. This was verified by searching for token text
inputs in SimpMusic's source (none found). The Discord integration in Metrolist *does* have a
manual token path (`saveDiscordToken`); Spotify deliberately will not.

---

## 10. Failure handling

| Situation | Behaviour |
|---|---|
Spotify not logged in, user long-presses | Toast only. Nothing else happens. |
Track has no Canvas | **Silent.** No toast, no visual change. (Canvas is sparse; a toast on every track would be maddening.) |
API failure on one track | Silent fallback to the normal background |
3 consecutive failures | High-priority notification naming the cause, once per outage. **Nothing is disabled** |
Video decode failure | Silent fallback; does not count toward the failure threshold |

The music must never be interrupted by any Canvas problem. Any error path that cannot be
handled locally resolves to: leave the player alone, restore the normal background.

---

## 11. Testing

### 11.1 Known-good references

- SimpMusic v2.2.0 on `emulator-5556`, verified working with the user's accounts.
- Test media with confirmed Canvas: the user's own session, `KlhuSz0HbcE` (Tiramisu) and
  others found during the session. **Canvas presence per track must be confirmed on device —
  most tracks have none, and a silent no-op is indistinguishable from a bug.**

### 11.2 Acceptance criteria

**All 13 pass.** #12 (landscape / `isFullScreen` / tablet) was closed by the user after Phase 7
(§14.1n), and #11's notification half was verified on device with a dead proxy (§14.1o).

Must all pass:

1. Long-press 1s on artwork, logged in, track **with** a Canvas → background washes to Canvas
over 2000ms, particles gone, no black flash.
2. Music plays perfectly throughout, uninterrupted, unaffected by Canvas.
3. One short swipe up on artwork → artwork fades to transparent over 1000ms fixed; Canvas
   visible, particles still absent.
4. Swipe up again → artwork fades back to fully opaque over 1000ms.
5. Second long-press 1s → Canvas dismissed, background + particles + opaque artwork all
restored over 2000ms / 1000ms.
6. Track with **no** Canvas → long-press does nothing, silently. Music unaffected.
7. Spotify not logged in → tap on the greyed switch navigates to login + shows a toast.
   Long-press shows the toast only.
8. Pause the song → Canvas keeps looping.
9. Collapse to mini player → Canvas not rendered; re-expand → Canvas present, no black flash.
10. Lyrics view → Canvas visible behind, lyrics readable, long-press unavailable.
11. Spotify API failing — silent fallback; after 3 consecutive failures, one high-priority
    notification naming the cause, and **the feature is NOT disabled**. ✅ **PASSED**
12. Landscape, `isFullScreen`, and tablet → identical behaviour, Canvas fills the screen with
    no black bars. ✅ **PASSED** — closed by user decision after Phase 7 (see §14.1n).
13. Early release at ~0.5s — half the hold window — nothing happens, no network request,
    no vibration. (This was 1.5s against the original 2s window; the ratio is what matters.)

### 11.3 Gates

- `./gradlew :app:compileFossDebugKotlin` clean.
- `./gradlew :app:testFossDebugUnitTest` — full suite green. The base is **132**; Phase 3
  **added 7** (`CanvasCachePolicyTest`), so the current expected total is **139**. Removing any
  of those 7 is a regression in the spec 9.2 guarantees.
  **`:spotify`'s instrumented suite is separate and additional** (4 tests via
  `:spotify:connectedDebugAndroidTest`); it does not change the `:app` count.
- Delete `app/build/outputs/apk/foss/debug` before assembling (stale-APK rule).
- Manual on-device verification of §11.2, with logcat evidence for the Canvas fetch and for
  each failure path.

### 11.4 No DB schema change

Per AGENTS.md, the database schema is not touched. Canvas state is transient UI state plus
DataStore preferences.

---

## 12. Explicitly out of scope

- **Spotify lyrics** — the code ports, but the feature is not enabled or surfaced. The user
  asked for Canvas only. (SimpMusic's lyrics were verified working separately.)
- **Apple Music animated artwork** — user explicitly declined.
- **Video playback** (1080p, PiP, merged audio+video) — a separate feature entirely.
- **Manual token entry** — deliberately excluded, matching the reference implementation.
- **Player controls scrim / dimming** — deferred by decision (§5.3).
- **Any database schema change.**

---

## 13. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
Spotify bumps the client version; TOTP flow breaks | **High** - has happened to such clients before | Module isolation; user-visible notification; one place to fix |
Secrets repo `xyloflake/spot-secrets-go` goes down | Medium | Same; login degrades, music unaffected |
Canvas video fails to decode on some device | Low | Silent fallback to normal background |
TextureView memory cost | Low | Accepted by user; Canvas is small and short |
`:app` coupling from PlayerSettings gaining navigation | Low | Contained to one file |

---

## 14. Phase plan

Eight phases, strictly ordered. Each is independently compilable, independently verifiable on
device, and ends in a working state — no phase leaves the app broken or half-wired. If any
phase fails, the work stops there and everything before it is intact and shippable.

Each phase = **one commit**, on `testing`, with explicit user authorisation per commit. Nothing
is pushed.

### 14.0 Why this order

The two phases most likely to fail are placed **before any user-visible feature exists**, so
that when they do fail there is no UI to diagnose and no half-working feature to unpick:

- **Phase 3 (fetch + cache)** is where the Spotify integration actually lives or dies. It runs
  with **zero UI** — it fetches a Canvas to disk and logs the result. Verifiable in isolation.
- **Phase 4 (video surface)** is the only phase that can affect the player's visual
  correctness, and it runs behind a **temporary debug trigger**, not the real gesture, so
  rendering is proven before any gesture logic exists to confuse the picture.

Gestures come last, once the thing they control already works.

### 14.1 Dependency chain

```
P0 pre-flight
 └─> P1 :spotify module            (no UI, no device behaviour)
      └─> P2 login + token store   (IntegrationScreen row)
           └─> P3 fetch + cache     (no UI — the risky one, proven headless)
                └─> P4 video surface (debug trigger, no gesture)
                     └─> P5 long-press gesture
                          └─> P6 swipe + artwork fade
                               └─> P7 settings, failure handling, credits
```

Each phase's gate must pass before the next begins. A failed phase is a stopping point, not a
reason to continue and accumulate breakage.

---

### Phase 0 — Pre-flight

**Status:** ✅ **DONE - commit 97107e2ed**

**No code.** Establish ground truth before writing anything.

- ~~Confirm the emulator/device and build pipeline.~~ **SKIPPED by user** — compile + the full
  132-test suite were already verified green at base `7f168bf` during the earlier rollback, and
  the only commit since (`104db38a4`) adds a markdown file, which cannot break a build.
- **Identify at least 3 tracks that definitively have a Canvas.** **DONE** — see §14.0a below.
  The list was recovered from SimpMusic's own database, so nothing was re-tested.
- Record a logcat baseline for the Canvas fetch tag, to compare against.
- Delete `app/build/outputs/apk/foss/debug` before any assemble (stale-APK rule).

**Gate:** confirmed Canvas tracks recorded. **Risk:** none. **Rollback:** nothing to roll back.

#### 14.0a Confirmed Canvas tracks

**Eight tracks, verified to have a working Spotify Canvas.** Recovered from SimpMusic's
`Music Database` on `emulator-5556` — the app writes `canvasUrl` into the `song` table only
after a successful fetch, so this list is your own testing, read back off disk. **Nothing was
re-tested.**

| # | videoId | Title | Type |
|---|---|---|---|
| 1 | `zrW87-xUvt4` | Dat Bad | video |
| 2 | `LqaDwpR0KuM` | Itsy Bitsy | video |
| 3 | `3NVf0iAw5NY` | Producer Man | video |
| 4 | `4OncUpJmhtk` | Room For You | video |
| 5 | `9QrG7SUdbzs` | back from the dead | video |
| 6 | `pIZN1xCFUvQ` | buzzkill | video |
| 7 | `4y5B8A5DHwk` | do u really? (feat. Ruth B.) | video |
| 8 | `WXjWN08tnUU` | take me as I am | video |

All eight are **video** Canvas (`canvaz.scdn.co/.../video/...mp4`), not still images — so they
exercise the full playback path rather than a trivial image case.

**How this was obtained** (reproduce if the emulator is ever reset):

```
adb root
adb pull "/data/data/com.maxrave.simpmusic.dev/databases/Music Database"
adb pull "/data/data/com.maxrave.simpmusic.dev/databases/Music Database-wal"   # recent writes
adb pull "/data/data/com.maxrave.simpmusic.dev/databases/Music Database-shm"
```
```sql
SELECT videoId, title, canvasUrl FROM song WHERE canvasUrl IS NOT NULL AND canvasUrl != '';
```

> **The `-wal` file matters.** Recent Canvas writes live in the write-ahead log; pulling only
> the main DB can miss them. The main DB was 598 KB while the WAL held 4.1 MB.

**Usage:** any phase verifying Canvas behaviour (§11.2 criteria 1, 3, 4, 5) must use one of
these. A track **not** on this list serves as the negative control for criterion 6 — proving
that a no-Canvas track stays silent and does nothing.

**Still to confirm during Phase 3:** these IDs must resolve to Canvas via Metrolist's own fetch
path, and the URLs must still be live. The list proves they *existed*; Phase 3 proves our port
retrieves them.

#### 14.1a Phase 1 result — `:spotify` module

**16 files, 1469 lines.** 14 copied verbatim + 2 Ktor helpers.

| Check | Result |
|---|---|
`:spotify:compileDebugKotlin` | **BUILD SUCCESSFUL** |
`:app:compileFossDebugKotlin` | **BUILD SUCCESSFUL** |
`:app:testFossDebugUnitTest` | **132 tests, 0 failures** (unchanged from base) |
Ktor 3.5.2 compatibility | **Confirmed** — no missing API, no version bump needed |
`@ProtoNumber` preservation | **Verified 21 = 21**, and `CanvasResponse.kt` / `TokenResponse.kt` are **byte-identical** to source beyond the package line and header |

**Fidelity audit.** Every copied file's line delta was checked against its source:

| Delta | Files | Cause |
|---|---|---|
**+30** | 11 files | The 30-line GPL-3.0 header, nothing else |
**+32** | `SpotifyClient.kt` | header + 2 lines commenting the inlined OkHttp engine |
**+42** | `SpotifyTotp.kt` | header + the collapsed `expect`/`actual` (12 lines: imports, function body, doc) |

No logic was altered anywhere.

**The three adaptations from §4.2.1, as actually performed:**

1. `getEngine()` → **`HttpClient(OkHttp)`** at `SpotifyClient.kt:88`. Matches `:innertube`'s engine.
2. `CurlLoggerPlugin.kt` — copied verbatim, package rewritten only.
3. `BrotliEncoder.kt` — the `expect` declaration and the `androidMain` actual merged into one
   file. **Finding worth recording:** the encoder's `encode()` throws
   `UnsupportedOperationException`; only `decode()` is implemented, because `org.brotli:dec` is
   decode-only. Unreachable in practice — Ktor only advertises `br` when a request asks for it,
   and the Spotify client relies on gzip/deflate. Documented in the file so a future reader does
   not mistake it for a bug.

**New catalog entries:** `ktor-serialization-protobuf`, `kotlinx-serialization-protobuf`
(needs a new `kotlinx-serialization = "1.10.0"` version key), `ktor-client-logging`,
`kotlin-onetimepassword = "3.0.0"`.

**Not yet exercised:** the module compiles but nothing calls it. Its behaviour is unproven until
Phase 2 (login) and Phase 3 (fetch). Compilation proves the copy is *syntactically* faithful,
not that it *behaves* — which is exactly what those phases are for.

#### 14.1a-bis Phase 1.5 marker

Phase 1.5 is an **inserted** phase, not one of the original eight. It was added after Phase 1
because Phase 1 proved compilation but not behaviour, and Phases 2-4 all depend on the Spotify
chain actually working. It is deliberately UI-free and runs headlessly on a device.

**Status: DONE.** Results and the blocking finding are in 14.1c.

#### 14.1b Audit findings (Phase 1)

A line-by-line diff against SimpMusic, ignoring only the GPL header and package line:

| Result | Files |
|---|---|
**Identical** | 11 of 14 — all models, `Spotify.kt`, `SpotifyAuth.kt`, `StringExt.kt` |
**Differ, documented only** | `SpotifyClient.kt` (engine inlined), `SpotifyTotp.kt` (`expect`/`actual` collapsed) |

**Defect found and fixed:** my `HttpClient(OkHttp) {` edit landed at the wrong indentation,
leaving the function body visually misaligned with the source. Corrected to match.

**Pre-existing quirks in SimpMusic, deliberately preserved:**

1. **`getSpotifyAccessToken` accepts `sTime` and `cTime` and never sends them.** Present in
   SimpMusic's source identically. Documented with a comment at the call site so a future
   reader doesn't "fix" it into a behaviour change.
2. **`BrotliEncoder.encode()` throws.** `org.brotli:dec` is decode-only. Documented in the file.
3. **`HttpCache` installed with no explicit storage directory.** Identical upstream.

> **⚠ SECURITY FINDING — carry into Phase 2.** The Spotify client installs `Logging` at
> `LogLevel.ALL` plus `CurlLogger`, and **neither redacts anything**. Every request therefore
> prints in full, including the `sp_dc` cookie, the personal `Authorization: Bearer` token, and
> the `Client-Token`. SimpMusic gates nothing on build type, so this is upstream behaviour.
>
> Two aggravating factors specific to Metrolist:
> - Ktor's `Logger.DEFAULT` uses `println`, **not** `android.util.Log`. Metrolist's ProGuard
>   strips `Log.v`/`Log.d` in release but **cannot strip `println`**, so this logging survives
>   into release builds. It would not if it used Timber.
> - The CurlLogger plugin *supports* `redactHeaders` (it prints `<redacted>`) — SimpMusic simply
>   never sets it.
>
> **Not reachable yet:** `:app` does not depend on `:spotify`, so none of this is in an APK.
>
> **RESOLVED — fixed in Phase 1** (user delegated the call). Two changes, both additive, neither
> touching logic:
> 1. `redactHeaders = setOf("Cookie", "Authorization", "Client-Token")` on the CurlLogger plugin.
> 2. `level = if (BuildConfig.DEBUG) LogLevel.ALL else LogLevel.NONE`, plus
>    `buildFeatures { buildConfig = true }` in the module.
>
> Debug builds keep SimpMusic's full verbosity for diagnostics; release builds print nothing.
> `LogLevel.NONE` rather than `INFO` because these are third-party API calls whose only value is
> debugging, and the request bodies contain session material.

#### 14.1c Phase 1.5 — headless de-risking: ALL PASS

Phase 1 proved the copy compiles. It proved nothing about *behaviour*, and Phases 2-4 all assume
the Spotify chain works. So a headless instrumented test
(`spotify/src/androidTest/.../SpotifyChainSmokeTest.kt`) runs the whole risky path on a real
device with no UI, no DataStore, and no login screen.

**Final result: 4/4 passed against the live Spotify API.**

| Test | Needs cookie | Result |
|---|---|---|
`clientToken_isObtainableWithoutAnyCredentials` | no | **PASSED** |
`totpSecret_isFetchable` | no | **PASSED** — the external GitHub secrets repo is alive |
`personalToken_isObtainableAndAuthenticated` | yes | **PASSED** — `isAnonymous=false` |
`canvas_isFetchableForAKnownCanvasTrack` | yes | **PASSED** — real Canvas URL returned |

**The Canvas it retrieved:**

```
spotify:track:1fk5Jx5ytAcfc9o1tcrQ1d
https://canvaz.scdn.co/upload/licensor/3NAqPxQzicHuJO85ZYITtF/video/09b72ea8cb8f44128f02a26a5331d039.cnvs.mp4
```

**Independently cross-checked:** that URL is byte-identical to the one SimpMusic had already
cached in its Room database for the same track (`zrW87-xUvt4` = "Dat Bad"). Two separate
implementations, same answer.

### The trap this phase caught

**Phase 0's track list is YouTube video IDs. Spotify's canvas endpoint wants a Spotify track ID.**

Feeding one to the other returns a *perfectly well-formed* `HTTP 200` with
`Content-Type: application/protobuf` and a 3-byte body:

```
10 90 1c
```

which decodes to "no canvas for that id". It looks exactly like a broken protobuf mapping — the
failure the spec warned about twice — and is not one. Distinguishing the two required dumping
the raw response rather than inferring from a decoded model, because a decoded empty
`CanvasResponse` is indistinguishable from a successful one with no entries.

The real chain, copied from SimpMusic (`LyricsCanvasRepositoryImpl.getCanvas`, lines 114-200),
is:

```
YouTube videoId
  -> local SongEntity (title + artist)
  -> scrub the query of "(feat." / "&" / ")" / "." etc.
  -> searchSpotifyTrack()
  -> match candidates on DURATION (abs(trackDuration - localDuration) < 1s)
  -> Spotify track ID
  -> getSpotifyCanvas()
```

> **Requirement for Phase 3:** the **search** step must be ported, not just the canvas fetch.
> It is the part that maps our YouTube IDs onto Spotify's, and skipping it produces a silent,
> plausible-looking failure.
>
> **But do NOT try to "fix" the candidate selection — see §14.1e.** The user has decided that
> candidate selection is copied verbatim, so behaviour matches SimpMusic exactly.

### A second false signal, also caught here

SimpMusic's own auth code asserts the personal token is exactly **374** characters and retries
`init` if not. A live, fully authenticated token measured on 2026-09-30 was **395**. So that
constant is not a contract, and the first run's "expected 374 but was 140/395" was misleading —
140 was a genuinely anonymous token, 395 was a genuinely *valid* one. The ported logic keeps
SimpMusic's check (it is load-bearing for its retry), but **the test no longer asserts a fixed
length**, and asserts `isAnonymous == false` instead, which is the property that actually
matters.

### Test hygiene

Cookie-dependent tests use `Assume`, so they **skip** rather than fail when no cookie is
supplied, and the suite stays green for anyone without a Spotify session. The very first run
reported "2 passed, 2 skipped" and looked clean — which is precisely why the XML had to be
read rather than the console summary.

### How to run it

```
adb pull /data/data/com.maxrave.simpmusic.dev/files/datastore/settings.preferences_pb
# extract the sp_dc value, then:
./gradlew :spotify:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.sp_dc=<cookie>"
```

Quote the whole `-P...` argument: the cookie contains `&`, which PowerShell otherwise turns
into a `Selection failed` error.

**A stale cookie surfaces as `isAnonymous=true`, not as an exception.** Re-login in SimpMusic
and re-copy when that happens.

---

#### 14.1d Phase 2 result — login + token storage, and the WebView layout trap

**Status: DONE — commit `e65881a55`**

`:app` now depends on `:spotify`. The login WebView renders, the settings row, routes, token
repository and strings are in place. Gate green: `:app:compileFossDebugKotlin` +
`:app:testFossDebugUnitTest` — **132 tests, 0 failures**, unchanged from base.

##### The bug that cost the most: a WebView that loads perfectly and shows nothing

The login screen rendered **completely blank** for several build cycles. Recorded here because
the failure is invisible to every signal an engineer normally trusts.

**Root cause:** SimpMusic's `PlatformWebView` sets explicit layout params on the WebView. The
port omitted them:

```kotlin
layoutParams = ViewGroup.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    ViewGroup.LayoutParams.MATCH_PARENT,
)
```

**Measured A/B — one line toggled, same build cycle:**

| | `layoutParams` | WebView view size | `document.body` | Screenshot |
|---|---|---|---|---|
| without | absent | 900×1600 | `clientHeight = 0` | **blank** (54.9 KB PNG) |
| with | `MATCH_PARENT` | 900×1600 | `450 × 800` | **renders** (115.9 KB PNG) |

**Mechanism:** Compose's `AndroidView` sizes the WebView *view* to the full available bounds
either way — the view measured 900×1600 in **both** runs. But the WebView lays out its *document*
from its `LayoutParams`, so with those unset the page's `<body>` measured **zero height** and
painted nothing.

##### Why the diagnostics all said the WebView was innocent — because it was

The page was loading flawlessly the whole time, even while the screen was black:

| Signal | Value while blank |
|---|---|
| `document.readyState` | `complete` |
| `body.children.length` | 5 |
| `body.innerHTML.length` | 22 904 |
| subresources fetched | 22 (Next.js chunks, woff2 fonts, reCAPTCHA) |
| errors / HTTP failures | **none** |
| `onPageFinished` | fired, `title = "Spotify"` |
| `onPageCommitVisible` | fired |

**So the DOM probe is not a valid blank/loaded discriminator on its own.** Two earlier
experiments were actively misleading, and both are recorded so they are not repeated:

1. **`example.com` rendered**, which looked like proof the WebView worked.
2. **A `data:` URL with inline JavaScript "proved JS works."** The background turned green —
   but the `JS-EXECUTED` **text was never visible**. CSS propagates a root element's background
   to the canvas regardless of body height, so the background got through while the content was
   clipped by the very same zero-height bug. **The test was hitting the bug it was meant to rule
   out.**

The reliable discriminators are **`document.body.clientHeight`** and a **screenshot file size**
(a blank PNG is ~55 KB, a rendered one ~115 KB — immediate and free).

**Also ruled out, with evidence, so they are not re-investigated:** network, DNS, TLS,
`network_security_config` (byte-identical to SimpMusic's), User-Agent, JavaScript or DOM-storage
settings, cookie-clearing races, third-party cookies, app theme and hardware acceleration,
WebView version (110.0.5481.154.1 — identical in both apps), and any WebView provider or
`setDataDirectorySuffix` in Metrolist's manifest.

> **Lesson carried into the remaining phases:** a WebView can report complete success and paint
> nothing. Assert on **rendered geometry**, never on callbacks.

##### Deviation from SimpMusic, and why

SimpMusic does **not** clear cookies on entry to the login screen. The port originally did
(`LaunchedEffect { removeAllCookies }`, added to stop a stale session bouncing the page straight
to `/status`). It was **removed**: the porting rule (§4.2) permits only four kinds of change and
this was a fifth. SimpMusic's design already covers the case — it clears cookies after a
successful save, so the jar is empty on the next entry. The user was notified and did not object.

##### ✅ End-to-end verification — CLOSED

The user completed a real login on device. Both outstanding items are now resolved.

**1. The cookie is genuinely on disk.** `settings.preferences_pb` was pulled off the device and
decoded. `spotifySpdc` is present as a **211-character** URL-safe string. The other Spotify keys
are legitimately absent — `spotifyPersonalToken` / `spotifyClientToken` and their expiries are
minted **lazily** on the first `validTokens()` call, which is Phase 3 work. Their absence is the
designed behaviour, not a gap.

> **Note for anyone reading the DataStore directly.** Metrolist's `Preferences` protobuf uses
> field 2 for the value, and that field carries **its own tag+length wrapper** — a naive parse
> yields 214 bytes of non-UTF-8 and looks like corruption. The real string is the 211 bytes
> *inside* that wrapper. This cost three wrong readings before it was parsed correctly.

**2. The close-the-loop test passes 4/4 with our own cookie.** The `sp_dc` that **Metrolist's own
login** stored was fed back into `:spotify:connectedDebugAndroidTest`:

```
tests=4 failures=0 errors=0 skipped=0
  [PASSED] clientToken_isObtainableWithoutAnyCredentials
  [PASSED] personalToken_isObtainableAndAuthenticated
  [PASSED] canvas_isFetchableForAKnownCanvasTrack
  [PASSED] totpSecret_isFetchable
```

**`skipped=0` is the load-bearing number.** The cookie-dependent tests use `Assume`, so a missing
cookie makes them *skip* and the run still reports success (see §14.1c, test hygiene). Zero
skips proves they actually executed. And `personalToken_isObtainableAndAuthenticated` asserts
`isAnonymous == false`, so the cookie is genuinely authenticated rather than merely well-formed.

A login flow that stores a well-formed but useless token is now **ruled out**.

**Phase 2 is fully verified. Phase 3 may proceed.**

---

#### 14.1f Phase 3 finding — the fifth dependency gap, and why Phases 1 and 1.5 missed it

**A runtime class-loading conflict between Metrolist and the ported TOTP code.** Found the first
time the token mint ran inside `:app`, and it is the single best argument for this project's phase
ordering.

**Symptom.** With a valid `sp_dc` stored and the cookie proven good by the Phase 1.5 suite, every
Canvas fetch failed with:

```
java.lang.NoSuchMethodError: No static method isEmpty([B)Z in class
  Lorg/apache/commons/codec/binary/BinaryCodec;
  or its super classes (declaration of 'org.apache.commons.codec.binary.BinaryCodec'
  appears in /system/framework/org.apache.http.legacy.jar)
    at org.apache.commons.codec.binary.BaseNCodec.decode(BaseNCodec.java:631)
    at dev.turingcomplete.kotlinonetimepassword.GoogleAuthenticator.<init>(GoogleAuthenticator.kt:78)
    at com.metrolist.spotify.auth.SpotifyTotpKt.generateTotp(SpotifyTotp.kt:122)
    at com.metrolist.spotify.auth.SpotifyAuth.refreshToken(SpotifyAuth.kt:86)
```

**Cause.** Metrolist's manifest declared

```xml
<uses-library android:name="org.apache.http.legacy" android:required="false" />
```

`org.apache.http.legacy` ships a **stub** `org.apache.commons.codec.binary.BaseNCodec`, and a
`<uses-library>` is loaded by the **boot classloader** — which is consulted *before* the app's DEX.
The real `commons-codec:1.21.0` **is** in the APK, and it still loses. `kotlinonetimepassword`
needs `BaseNCodec.isEmpty([B)`, which the stub does not have.

**Why Phase 1 and Phase 1.5 both missed it.** Neither could have caught it:

| Phase | What it proved | Why the conflict was invisible |
|---|---|---|
| 1 | the copy **compiles** | a compile cannot see classloading order |
| 1.5 | the chain works **in `:spotify`'s own test APK** | that APK does **not** declare `org.apache.http.legacy`, so no stub is loaded and the real commons-codec is used |

The conflict exists **only in `:app`**. A test that never runs in the app module cannot find it.
This is precisely the risk that §14.0 ordered Phase 3 before any UI.

**Fix.** Remove the `<uses-library>` declaration. Evidence that this is safe:

- **No** Metrolist source references `org.apache.http` (verified across all of `app/src`).
- No dependency declares it — the single occurrence in the merged manifest is ours.
- It has been present since `1804fb079` *"first commit"*, with no commit ever explaining it.
- `android:required="false"` means it was defensive, not load-bearing.
- With it removed: `:app` compiles clean, the unit suite passes in full (132 at the time of this
  check; 139 now, after \u00a714.1g added 7), the `:spotify` chain suite is **4/4 with 0 skipped**,
  and playback is unaffected on device.

**Lesson worth keeping:** a transitive dependency can be present, correct, and still lose to a
platform stub. Presence on the classpath is not the same as being the class that loads. Only
running the code in the final app module reveals it.

##### Phase 3 evidence, gathered with the flag temporarily on

The whole chain, from a track change, on device, with logcat as evidence:

```
SpotifyCanvas: query for diGBleLYaGQ: 'Dragga Party Ymo G' (from 'Dragga Party' / 'Ymo G')
SpotifyCanvas: picked Spotify track 0PoHxWBZDbjBRSobhh06f6 ('Dragga Party')
               for diGBleLYaGQ 'Dragga Party' out of 3 candidate(s)
SpotifyCanvas: no canvas on Spotify for track 0PoHxWBZDbjBRSobhh06f6 (clean negative)
SpotifyCanvas: clean negative 1/3 for diGBleLYaGQ 'Dragga Party'
SpotifyCanvas: cache hit for zrW87-xUvt4 'Dat Bad'
               -> https://canvaz.scdn.co/upload/licensor/3NAqPxQzicHuJO85ZYITtF/video/09b72ea8cb8f44128f02a26a5331d039.cnvs.mp4
```

| Spec 9.2 / Phase 3 requirement | Evidence |
|---|---|
| The **search** step runs, and is mandatory (§14.1c) | `query for ...` then `picked Spotify track ...` — a Spotify track ID, not the YouTube one |
| Candidate selection is first-hit, per §14.1e | `out of 3 candidate(s)`, and the duration branch demonstrably did not fire |
| The Canvas URL for `zrW87-xUvt4` is correct | **byte-identical** to Phase 1.5's result *and* to the URL SimpMusic had cached |
| Positive cache holds the **video bytes** | `files/spotifyCanvas/5/*.exo` — two spans, 841 KB and 3.2 MB |
| Positive cache is consulted on replay | `cache hit for zrW87-xUvt4` |
| Clean negative is **not** cached on first sight | `clean negative 1/3` for `diGBleLYaGQ`, `url=None` |
| Tokens are minted and persisted | `spotifyPersonalToken` (395 chars), `spotifyClientToken`, both expiries, all in DataStore |
| Audio is untouched | playback continued throughout; the fetch runs on `Dispatchers.IO` |

**Adjudicated on-device:** with the legacy library removed, `GoogleAuthenticator` constructs, the
TOTP is produced, and Spotify returns `isAnonymous=false`. The fix is not theoretical.

#### 14.1h Phase 4 — the Canvas renders. Five bugs, four of them mine.

**Status: DONE.** The Canvas is on screen behind the player: full-screen, centre-cropped, looping,
behind the artwork and the controls. This is the thing the previous attempt never reached (§16.8).

```
SpotifyCanvas: cache hit for zrW87-xUvt4 'Dat Bad' -> .../09b72ea8cb8f44128f02a26a5331d039.cnvs.mp4
SpotifyCanvas: first canvas frame rendered
```

**Architecture, and why it cannot repeat the previous failure:**

| Decision | Reason |
|---|---|
| **TextureView**, not SurfaceView (§7.1) | A SurfaceView composites *above* the UI layer, so it would ignore the Canvas alpha wash and pop in at full opacity. Verified visually: the header and artwork sit on top. |
| **A separate, muted ExoPlayer** owned by the UI layer (§7.2) | Not a second output on the music player. No `MergingMediaSource`, so the `Children enabled at different positions` class of failure is structurally impossible. |
| `handleAudioFocus = false` | Verified: **zero audio-focus events in logcat** across a full run. The music is untouchable by construction. |
| Its own `CacheDataSource` over the Canvas `SimpleCache` | Shares no state with the music player's cache. |

**Verified on device:** renders; genuinely animating (3 distinct frames, MD5-compared); gradient,
blur, colour wash and particles all suppressed while it is up; silent fallback on a no-Canvas track;
audio uninterrupted. Gate: compile clean, **139/139**.

**Confirmed by the user, on the device, after the commit:**

| Check | Result |
|---|---|
| **Pause keeps the Canvas looping** (spec §7.3) | ✅ confirmed |
| **`isFullScreen` mode** | ✅ confirmed |
| Input across the whole app, collapsed and expanded | ✅ fixed and confirmed |
| Canvas present during expand and collapse animations | ✅ fixed and confirmed |

**⚠ Landscape is the one unverified item**, and the user deferred it. It is the heavy-crop case
(spec §5.2: a 9:16 Canvas cropped hard in landscape, deliberately not letterboxed) so it is a real
gap rather than a formality. **Phase 5 must verify it** before the gesture ships.

#### The five bugs — four were mine, and the user found every one

Worth recording in full, because the pattern is the lesson: **four of these were invisible to the
build, the tests, and my own on-device checks.** The user found all four by using the app.

**1. A full-screen view blocking every touch.** The player is a bottom sheet that stays composed when
collapsed, so the TextureView sat invisibly over the entire app and ate every press — while looking
perfectly normal. Three "fixes" failed before the right one:

| Attempt | Why it did not work |
|---|---|
| `dispatchTouchEvent`/`onTouchEvent` → false | The event is still *delivered* to the view first; the player beneath never receives it |
| `isEnabled = false` | `dumpsys activity top` showed the view still `V..D` — **Android hit-tests disabled views**; disabled stops *handling*, not *receiving* |
| `clearVideoTextureView()` | Removes the video surface, but the View stays full-size in the hierarchy |
| **zero size + `View.GONE`** | A zero-area view contains no point, so no touch can land on it. Confirmed: `G.ED..... 0,0-0,0` |

The decisive clue came from the user: *it blocks even for songs with no Canvas.* With no Canvas there
is no surface to clear, so the culprit had to be the View itself, not the video. That is what pointed
at the view dump instead of at the player.

**2. Dead after one collapse.** `stop()` on collapse, then a re-expand guard that compared only the
URL. The URL was unchanged, so `prepare()`/`play()` were skipped and the Canvas stayed dead until the
track changed. Fixed with `pause()` on collapse plus an explicit `STATE_IDLE` recovery.

**3. Written from inside `DisposableEffect`.** `firstFrameSeen = false` was a side effect Compose
does not reliably run, so the gate never re-armed.

**4. Not gated on the animation.** This was the user's diagnosis and the code proved it exactly:
`BottomSheetState.isExpanded` is `value == upperBound` — **exact equality against the anchor** — so
it is false for the whole of the expand and collapse animations. The normal background was visible
through the entire slide up, and again through the finger-tracked slide down. Now gated on
`state.progress`, which is continuous, with `CANVAS_ACTIVE_PROGRESS_THRESHOLD = 0.35f`.

**5. Media3 has no `videoTextureView` getter.** Assumed one existed, spent a build on it. `javap` on
`media3-exoplayer-1.10.1` confirms only `setVideoTextureView` / `clearVideoTextureView` are exposed,
so attachment is tracked locally.

#### ⚠ §7.4 is deliberately NOT done

`CanvasBackgroundLayer` reports its first frame; `Player.kt` assigns the flag and **never reads it**.
The background is swapped when the Canvas becomes active, not when its first frame is ready.

This is on purpose, not an oversight. The gate only matters once the Canvas appears **after a
deliberate gesture** — spec §6.1 issues no fetch before the 1s hold completes, so there is a real
window before the first decoded frame, and §7.4's crossfade is what hides it. Today the Canvas
appears on its own for any track that has one and the surface already holds a frame, so there is
nothing to hide. Wiring it now would gate a swap that already looks instant and could not be tested
until the gesture exists.

**Carried into Phase 5** as a requirement and a verification item.


**Committed state:** both `SPOTIFY_CANVAS_FETCH_ENABLED` and `SPOTIFY_CANVAS_RENDER_ENABLED` are
`false`, so the committed tree renders nothing and the player behaves exactly as it did before.

---

#### 14.1g Phase 3 — safety checks, and a second real bug they caught

**Status: DONE — commit `42eccd38c`**

Implemented and device-verified:

- `SpotifyCanvasRepository` — the ported `getCanvas`, the cache index, the failure taxonomy
- `CanvasCachePolicy.kt` — spec 9.2's taxonomy as pure, testable logic
- `CanvasResult` + `toCanvasResult()` — ported verbatim from `mapping/Mapping.kt`
- `@CanvasCache` + `provideCanvasCache` — SimpMusic's `spotifyCanvas` SimpleCache, same cache
  name and unbounded evictor, using Metrolist's existing `LazyCache` idiom
- `MusicService.onMediaItemTransition` hook, behind `SPOTIFY_CANVAS_FETCH_ENABLED`
- Failure logging in `SpotifySessionRepository` — the gap that made §14.1f invisible
- The `org.apache.http.legacy` removal above
- `CanvasCachePolicyTest` — 7 tests, which the spec §14 explicitly asked for

##### Check 1 — the negative cache reaches 3/3, and is then honoured

Played "Dragga Party" (`diGBleLYaGQ`) to completion of the threshold:

```
SpotifyCanvas: no canvas on Spotify for track 0PoHxWBZDbjBRSobhh06f6 (clean negative)
SpotifyCanvas: clean negative 3/3 for diGBleLYaGQ 'Dragga Party' - now cached as no-canvas
```

Replaying it afterwards:

```
SpotifyCanvas: no canvas (cached negative) for diGBleLYaGQ 'Dragga Party'
```

**No `query for` line and no `picked Spotify track` line** — the verdict was honoured without
contacting Spotify at all. That is the whole point of the threshold: after three confirmed empties
the app stops asking.

##### Check 2 — a transport failure can never poison the cache

Simulated offline by pointing the system proxy at a dead port (`settings put global http_proxy
127.0.0.1:1`), then skipping three tracks:

```
SpotifyCanvas: search failed for 20E0yY4LcLI   ->  consecutive Spotify failures: 1/3
SpotifyCanvas: search failed for uVfsRZI7XvM   ->  consecutive Spotify failures: 2/3
SpotifyCanvas: search failed for TsjAJJnkHOI   ->  consecutive Spotify failures: 3/3
SpotifyCanvas: failure limit reached - Phase 7 auto-disables here   # Phase 3 log; superseded by the Phase 7 notification
```

**Zero `clean negative` lines across three different tracks.** The index was then diffed
before/after:

| Check | Result |
|---|---|
| entries before → after | **13 → 13**, unchanged |
| the three failed tracks | **absent** from the index — no verdict was ever written |
| pre-existing attempt counts | **none changed** |

Then the proxy was removed and playback resumed:

```
SpotifyCanvas: canvas for wbG0rrSIC4k: https://canvaz.scdn.co/upload/licensor/1qUM3lGfyk2uc6PjDgGRa6/video/d06a771363fa4b12aa8be8264906d40d.cnvs.mp4
SpotifyCanvas: consecutive Spotify failure counter reset to 0 by a normal answer for wbG0rrSIC4k
```

##### The second bug: the failure counter did not reset on a clean negative

Found while setting up Check 2, and it was a real defect in the first draft of this phase.

The counter originally reset **only when a Canvas was actually found**. But most tracks have no
Canvas, so that is the *rare* case. Three transient network blips across a week — on tracks that
legitimately have no Canvas — would therefore never reset the counter, so the Phase 7 response
would fire on three blips spread across a week. Spec 9.2's own wording is *"resets on any
successful load"*, and a confirmed-empty answer **is** a successful load: Spotify answered.

Fixed, and pinned by `CanvasCachePolicyTest`:

```
tests=139 (132 base + 7 new), 0 failures

  a transport failure never confirms no-canvas, at any attempt count
  a clean negative is a successful load and so resets the failure counter
  clean negatives accumulate and only confirm on the third
  the failure counter trips after three consecutive transport failures
  a transport failure between clean negatives does not push a track to confirmed
  a canvas found clears the track's attempt count and resets failures
  a transport failure advances the consecutive-failure counter and does not reset it
```

> **Why the taxonomy became a separate file.** It was previously reachable only through a live
> network, which is exactly how the bug above survived review. Extracting it to
> `CanvasCachePolicy.kt` means the rule that stops a flaky network burying a real Canvas is now
> unit-tested, and the repository delegates to the tested function. This is Metrolist-side glue,
> not ported SimpMusic code, so it is ours to structure; SimpMusic has no caching policy at all.

**Phase 3 verification is now complete. Gate: compile clean, 139/139 tests.**

---

#### 14.1i Phase 5 - the long-press, and retiring both temporary flags

Commit `6e7f2df59`. The 1s hold replaced the 2s window by user decision, with `CANVAS_HOLD_MS`
in `Thumbnail.kt` left as the single dial. Both temporary flags introduced in Phase 3 were retired
here — nothing in the project is feature-gated any more except login state.

Deferred from Phase 4 and implemented here as §7.4: the normal background is held until
`onFirstFrameReady`, then crossfaded. This only matters once the Canvas appears after a deliberate
gesture, which is why it could not be proven in Phase 4.

User-verified on device: engage, dismiss, early release, silent behaviour on a track with no
Canvas, collapse mid-wash, and the logged-out toast.

#### 14.1j Phase 6 - the swipe, and what the gesture research changed

Commit `29e52c29d`. Two findings worth recording.

**The built-in detector was rejected.** Compose ships `detectVerticalDragGestures`, which does
exactly this job. It is built on `awaitVerticalTouchSlopOrCancellation` — the same helper recorded
in §16.9 as suspending indefinitely in this codebase without ever delivering a down, the fault that
broke the hold silently and permanently. The swipe is hand-rolled instead, in the same shape as
the hold.

**Separate `pointerInput` blocks are the documented pattern, not a shortcut.** The Compose
gesture detectors are top-level and the first blocks the coroutine forever, so a second detector
in the same block is unreachable; Google's docs say to use separate `pointerInput` instances for
multiple listeners. The cost is that neither gesture can cancel the other, since both consume
nothing — which is exactly what leaves the horizontal song-swipe working. `CanvasGestureLatch`
closes that in both directions, keyed on the pointer id rather than reset unconditionally so the
block that reaches `open()` second cannot discard a claim the other already made.

The latch was **two-way by user decision**: flick up, then pause without lifting, would otherwise
let the hold fire at 1s and bring the artwork back. The artwork fade was raised 400ms → 1000ms on
the same grounds as the wash; see §6.5 for why the two durations are deliberately unequal.

User-verified on device: reveal, restore, sub-threshold ignored, swipe down inert, no-Canvas
inert, `swipeThumbnail` off unaffected, horizontal song-swipe intact in every state, and both
hold-then-swipe orderings showing only one toggle.

#### 14.1k Post-Phase-6 fix - the wash was never actually crossfading

Commit `bf03160ef`. Reported as "a quick kinda-smooth 200ms-feeling snap" on both engage and
dismiss. The duration was never the cause: `canvasWashAlpha` animated only the Canvas in, while
the gradient, colour wash and particles were gated on the `!canvasRevealed` **boolean** and were
therefore removed in a single frame. A hard cut dominates perception regardless of the tween.

Second, independent cause: the Canvas layer started at alpha 0 and the player needs ~300ms to
produce its first frame, so most of the ramp played against nothing and only the steep tail was
visible.

Both sides now ride one value, `legacyAlpha = 1f - canvasWashAlpha`. Particles are faded by a
`Modifier.alpha` at the **call site**, not by passing a transparent `baseColor` — `PlayerParticles`'s
draw loop does `baseColor.copy(alpha = a * ...)`, which overwrites the incoming alpha instead of
multiplying it, so a transparent `baseColor` would have silently done nothing. `PlayerParticles.kt`
is unchanged.

Duration 800ms → 1000ms → **2000ms** by user preference once the crossfade actually worked.

#### 14.1e The duration match - a units bug upstream, copied deliberately

**User decision: copy it verbatim.** Behaviour in Metrolist must be identical to SimpMusic, so
the candidate-selection line is ported exactly as written, bug included.

**What upstream actually does** (`LyricsCanvasRepositoryImpl.getCanvas`, lines 186-213):

```kotlin
val track =
    if (duration != 0) {
        items.find { abs((it.item?.data?.duration?.totalMilliseconds ?: (0 / 1000)) - duration) < 1 }
            ?: items.firstOrNull()      // <-- this is what actually runs
    } else {
        items.firstOrNull()
    }
```

`totalMilliseconds` is **milliseconds**. The `duration` parameter is **seconds** — the caller
passes `(timeline.total / 1000).toInt()` (`SharedViewModel.kt:289`). The predicate is therefore
`abs(ms − seconds) < 1`, which is false for every real track. (`?: (0 / 1000)` is integer
division of zero, so it is also just `0`.)

**Consequence: the duration branch is unreachable and SimpMusic always takes the first search
hit.** Metrolist will do the same.

**Inherited risk, not introduced risk:** a search returning a same-titled remix or live version
first will yield that track's Canvas, or none. This is SimpMusic's behaviour, reproduced on
purpose. Anyone reading the code later must **not** "repair" the units without asking — that
would make Metrolist behave *differently* from the reference, which is exactly what the porting
rule exists to prevent.

**What is still required, and is not affected:** the search step itself. Going from a YouTube
videoId to a Spotify track ID requires `searchSpotifyTrack`; there is no other route. That is the
part §14.1c warns about, and it is fully preserved.

**Diagnostics stay mandatory** so a wrong pick is at least visible: Phase 3 already requires
logging the mapped Spotify track ID, its duration and the HTTP status under the `SpotifyCanvas`
tag. With first-hit selection those three lines are what makes a mismatch diagnosable after the
fact.

---

### Phase 1 — `:spotify` module

**Status:** ✅ **DONE - commit `42aaa9f8`** (see 14.1a)

> **⚑ COPY, DON'T REWRITE.** SimpMusic's Spotify code is working, production code verified on
> device. Take it **as it is**. The only permitted changes are the four in §4.2: package/import
> rewriting, replacing the three SimpMusic-internal imports per §4.2.1, collapsing
> `expect`/`actual`, and adding GPL-3.0 headers. **Preserve every `@ProtoNumber` annotation
> verbatim** — Spotify is spoken to in protobuf and dropping them corrupts field mapping
> silently. Do not add the missing dependencies to a newer Ktor; accept 3.5.2.

**Goal:** the Spotify code exists in Metrolist and compiles. Nothing uses it yet.

**In:** New `:spotify` module. Files per §4.2.2 with GPL-3.0 headers. The four dependencies
from §4.2.1: `ktor-serialization-kotlinx-protobuf`, `kotlinonetimepassword`, plus the copied
`CurlLogger` and brotli helpers. `settings.gradle.kts` entry. Lyrics-related response models
carried but **unreferenced** (out of scope per §12).

**Out:** any UI, any preference key, any call from `:app`.

**Gate:** `./gradlew :spotify:compileDebugKotlin` clean; app module still compiles and all 132
tests pass (proving the new module broke nothing).

**Verification:** unit tests for the pure, deterministic parts — TOTP generation against a
known timestamp, and model deserialisation from fixture JSON. These are worth writing now
because they are testable without a device or a network.

**Device verification:** none required. Nothing user-visible changed.

**Rollback:** delete the module directory + one `settings.gradle.kts` line.

### Phase 1.5 — Headless chain proof (inserted)

**Status:** ✅ **DONE - commit `b393250d`** (see 14.1c)

---

### Phase 2 — Login and token storage

**Status:** ✅ **DONE and fully verified — commit `e65881a55`** (results in §14.1d)

> **⚑ COPY, DON'T REWRITE.** Port `SpotifyAuth.kt`, `SpotifyClient`'s login calls, and
> `SpotifyTotp.kt` **as they are** from SimpMusic. The TOTP flow in particular is delicate —
> it depends on an external secrets repository, a spoofed client version string, and randomised
> User-Agents. Every one of those is load-bearing. Change nothing you have not read a reason for.
> Metrolist has no manual token-entry path for this, exactly as SimpMusic has none (§9.3).

**Goal:** sign in to Spotify from inside Metrolist. Proves the entire auth chain end to end.

**In:**
- **`app/build.gradle.kts`: add `implementation(projects.spotify)`.** This is the first link
  between `:app` and the ported module — until it exists, nothing in the app can call the
  Spotify client and this phase has nothing to wire up.
- DataStore keys for `spdc`, client token, personal token, and their expiries.
- `SpotifySettings.kt` in `ui/screens/settings/integrations/`, with a **Log in to Spotify** row
  routing to a WebView, and a **Log out from Spotify** row once signed in.
- Registration in `IntegrationScreen` alongside Last.fm and Discord.
- Strings in `metrolist_strings.xml`.
- Token-refresh handling on expiry.

> **Stale-cookie note.** An `sp_dc` cookie is not permanent — one measured 2026-09-30 had
> roughly 4 hours left. When it expires, Spotify returns a **valid-looking anonymous token**
> (`isAnonymous = true`), *not* an error, and that token carries no Canvas entitlement. So a
> login can appear to succeed and then fail silently at Canvas time. Re-login in SimpMusic and
> re-copy the cookie whenever a test fails this way.

**Out:** the Canvas switch (P7), Canvas fetching (P3), all player changes.

**Gate:** compile + 132 tests.

**Verification — the important bit:** log in for real on device. Confirm the row flips to
"Log out", and **confirm the tokens actually landed in DataStore** by reading the
`settings.preferences_pb` file. A green UI is not proof the token was stored; SimpMusic's
Canvas depends on the token, not the login label.

**Then close the loop:** copy the freshly-stored `sp_dc` out of the app and re-run
`:spotify:connectedDebugAndroidTest` with it, confirming all four Phase 1.5 tests still pass.
This catches a login UI that stores a well-formed but useless token — which the UI alone
cannot detect, and which only shows up later as silently-missing Canvas.

**Expected state after this phase:** `:app` depends on `:spotify`, the login works, tokens
persist, and the Phase 1.5 suite passes against a cookie produced by *our* login flow rather
than SimpMusic's.

**Rollback:** revert the commit. No player behaviour existed to depend on it.

---

### Phase 3 — Canvas fetch and caching

**Status:** ✅ **DONE — commit `42eccd38c`** (results in §14.1f, §14.1g)

> **⚑ COPY, DON'T REWRITE.** Port `Spotify.kt`'s `getCanvas` and SimpMusic's
> `LyricsCanvasRepositoryImpl.getCanvas` **as they are**. SimpMusic already has a working
> `spotifyCanvas` disk cache — copy its approach, do not invent a new one. The URL-derivation
> and cache-key logic was already solved; re-solving it is pure risk. Simplification is
> permitted only where SimpMusic carries code this feature does not use.

**Goal:** prove the Spotify integration actually retrieves Canvas data. **No UI at all.**

This is the highest-risk phase in the project, which is exactly why it runs before any UI
exists.

**In:**
- `utils/spotify/` orchestration in `:app`.
- **Map YouTube videoId → Spotify track ID.** Ported from
  `LyricsCanvasRepositoryImpl.getCanvas` (lines 114-245): build a scrubbed
  `"title artist"` query from the local song, call `searchSpotifyTrack()`, then select a
  candidate. The `:spotify` module already contains the search call and response model; this
  step is the `:app`-side glue and is **required** — see §14.1c. Fetching by YouTube ID returns
  HTTP 200 with an empty body, not an error.
  **Candidate selection is copied verbatim, including its units bug, so the first search hit is
  always used** — see §14.1e for the full reasoning. Do not "repair" it.
- Canvas fetch on track change, **gated behind a temporary internal flag** (default off).
- Positive disk cache of the Canvas video.
- Negative cache: only clean negatives, **only after 3 attempts** (§9.2).
- Consecutive-failure counter with reset-on-success.
- Diagnostic logging under a `SpotifyCanvas` tag.

**Metrolist-side mapping, confirmed by reading both codebases:**

| SimpMusic | Metrolist |
|---|---|
| `localDataSource.getSong(videoId)` → `SongEntity` with `artistName: List<String>` | `com.metrolist.music.db.entities.Song`, whose `orderedArtists: List<ArtistEntity>` supplies the artist — **`SongEntity` itself has no artist field** |
| `duration` = `(timeline.total / 1000).toInt()`, seconds | `SongEntity.duration`, already seconds |
| `artistName.firstOrNull()` | `orderedArtists.firstOrNull()?.name` |

**Out:** anything the user can see. The flag stays off, so the player is untouched.

**Gate:** compile + 132 tests + any new unit tests for the failure taxonomy (which outcomes
cache, which retry).

**Verification — this is the phase's whole purpose:**
- Enable the flag, play a track **from the §14.0a list** (e.g. Dat Bad) → the search matches
  it to a Spotify track ID → the Canvas file lands in the cache. Log the mapped Spotify track
  ID, the URL and the HTTP status. *The mapping step is part of the expected path, not a
  shortcut — a Canvas cannot be fetched from the YouTube ID alone.*
- Replay it → served from cache, no network hit.
- Play a **known non-Canvas** track → silent, and after 3 attempts the negative is cached.
- Simulate a timeout → confirm it is **not** cached as a negative, and the counter resets on
  the next success.
- Confirm playback of audio is entirely unaffected throughout.

**Rollback:** the flag is off, so the app behaves as if this phase never existed. Reverting
the commit is clean.

---

### Phase 4 — Canvas video surface

**Status:** ✅ **DONE** (results in §14.1h) — **⚠ spec §7.4 deliberately deferred to Phase 5**

**Goal:** a Canvas renders full-screen behind the player, correctly, in all layouts.

**In:**
- `CanvasBackgroundLayer.kt` — a **TextureView** host, centre-crop, fill, no letterboxing.
- The three visual states from §5 wired to the temporary flag, **not to a gesture**.
- Particle suppression while Canvas is active.
- Lifecycle per §7.3: alive across collapse, rebuilt on track change, kept on pause and on
  backgrounding, released on disable.
- Muted ExoPlayer with `setAudioAttributes(..., handleAudioFocus = false)`.
- First-frame gate: hold the existing background until the first Canvas frame is ready (§7.4).
- Controls legible with no scrim (§5.3).

**Out:** every gesture. No swipe, no long-press. Driven by the flag from P3.

> **⚠ §7.4 (first-frame gate) is NOT implemented and is deferred to Phase 5, deliberately.**
> `CanvasBackgroundLayer` reports its first frame via `onFirstFrameReady`, but `Player.kt` assigns
> the flag and never reads it, so the background is swapped when the Canvas becomes *active* rather
> than when its first frame is *ready*.
>
> It has not mattered yet: the Canvas currently appears on its own for any track that has one, and
> by then the surface already holds a decoded frame. The gate only becomes load-bearing once the
> Canvas appears **after a deliberate gesture**, because spec §6.1 issues no fetch before the 1s hold
> completes — that leaves a real window between the gesture and the first decoded frame, and
> §7.4's crossfade-from-the-existing-background is precisely what hides it. Wiring it now would
> gate a swap that already looks instant, and it could not be tested until the gesture exists.
>
> **Phase 5 must wire §7.4 as part of the wash**, and must add it to its own verification list.

**Gate:** compile + 132 tests.

**Verification:** with the flag on, in turn —
- Canvas fills the screen, **no black bars** top or bottom.
- Particles are gone; background, wash, and gradient are all gone.
- Controls readable above it.
- **Music plays uninterrupted** — check position keeps advancing and audio is clean.
- Collapsing to the mini player and re-expanding → no black flash.
- Pause → Canvas keeps looping.
- Portrait, landscape, `isFullScreen`, tablet all behave identically.

**Rollback:** flag off. Reverting removes all player changes.

---

### Phase 5 — Long-press gesture

**Status:** ✅ **DONE — commit `6e7f2df59`** (results in §14.1i)

**Goal:** the 1s long-press engages and dismisses the Canvas. First real user-facing phase.

**In:**
- Invisible 1s long-press on the artwork square (phase gate: was the flag).
- Haptic **only** at the 1000ms threshold.
- Cancel on early release; **no fetch issued before 1000ms**. Beware: a shorter window is 
  easier to hit by accident, so the early-release path carries more of the real usage.
- First engagement sets the phase gate to the real gesture and **retires the temporary flag**.
- 2000ms crossfade in and out, 1000ms artwork return to opaque.
- **⚠ Implement spec §7.4 here, not before** — Phase 4 deferred it. The Canvas only appears
  after the 1s hold completes and no fetch was issued before it, so there is a genuine window
  between the gesture and the first decoded frame. Hold the existing background until
  `onFirstFrameReady`, then crossfade over 2000ms. This is the one thing that makes the no-black-flash
  guarantee true rather than incidental.

**Out:** the swipe gesture.

**Gate:** compile + **139 tests**.

**Verification:**
- Hold 1s on a known-Canvas track → background washes, no black flash, particles gone.
- Release at ~0.5s (half the window) → nothing happens, **and no network request** (check the
  log). Verified at the 2s setting; the behaviour is threshold-relative, not time-relative.
- Haptic fires at the threshold, not on press.
- Second 1s hold → everything restored, Canvas gone.
- **Landscape** — the one check Phase 4 never got to. Canvas fills the screen, cropped hard, no
  letterboxing and no black bars (§5.2).
- **No black flash on a first-ever play of a Canvas track** — hold the normal background until the
  first frame, then crossfade. This verifies the deferred §7.4 work (§14.1h).
- Collapse mid-wash and re-expand: no partial-alpha Canvas left behind.
- On a no-Canvas track → silent no-op.
- Not logged in → toast only.
- **Audio is never interrupted** — the hard requirement from §7.2.

**Rollback:** revert the commit; the surface is inert without the gesture.

---

### Phase 6 — Swipe and artwork fade

**Status:** ✅ **DONE — commit `29e52c29d`** (results in §14.1j)

**Goal:** the reveal/hide interaction. Smallest, lowest-risk phase.

**In:**
- Vertical swipe **up** on the artwork square, 60dp threshold, **only while a Canvas is
  rendering**.
- Fixed 1000ms artwork alpha animation — not finger-tracked.
- Two-state exclusive toggle.
- Independent of `swipeThumbnail`; horizontal song-swipe untouched.
- Swipe **down** does nothing.

**Out:** nothing. This completes the interaction design of §6.

**Gate:** compile + **139 tests**.

**Verification:**
- Swipe up → artwork fades to fully transparent over 1000ms; Canvas visible, **particles still
  absent**.
- Swipe up again → artwork returns to opaque.
- Below 60dp → ignored.
- Swipe down → nothing.
- With **no** Canvas active → vertical swipe does nothing at all.
- With `swipeThumbnail` off → vertical swipe still works.
- Horizontal song-swipe still works in all states, including while a Canvas is showing.

**Rollback:** revert; the player keeps working with the long-press only.

---

### Phase 7 — Settings, failure handling, credits

**Status:** ✅ **DONE — commit `f39a66bdb`** (results in §14.1l, §14.1m)

**Goal:** the feature is complete, controllable, and correctly attributed.

**In:**
- **Canvas switch in `Settings → Appearance → Player`** (`PlayerSettings.kt`).
  - ON by default (§8.3).
  - When logged out: **visible, greyed, subtitle "Log in to Spotify to enable"** — tapping
    still navigates to login and toasts (§8.2).
  - After login: return to Player settings, switch now enabled.
- **High-priority notification on repeated Spotify failures** — outage, expired session, or
  5xx. Replaces the planned auto-disable by user decision: permanently disabling a setting on
  the user's behalf is the more surprising of the two behaviours, and the settings switch is
  already a manual kill-switch. Fires exactly once per run of failures (see §14.1l).
  manually re-enabled.
- Silent-fallback wiring for decode failures. These are NOT Spotify failures — the network and
  the session were both fine — so they never move the failure counter or raise the notification.
- **README credit row** for maxrave-dev and `spotify_monitor` (user-approved).
- Final string pass in `metrolist_strings.xml`.

**Out:** nothing. Feature complete.

**Gate:** compile + **full 144-test suite green** (139 + 5 added here), plus all of §11.2
**all 13 criteria** of §11.2 — #12 in §14.1n, #11's notification in §14.1o.

**Verification:** re-run the §11.2 acceptance list end to end, item by item, with logcat
evidence. This is the only phase that verifies the whole specification. Landscape (#12) is
excluded and stays open.
logcat evidence. This is the only phase that verifies the whole specification.

**Rollback:** the switch is off by default at this point, so reverting leaves a working app
with no Canvas and no orphaned UI.

---

### 14.2 Phase summary

| # | Phase | UI risk | Needs device? | Verifiable without later phases? |
|---|---|---|---|---|
| 0 | Pre-flight | none | baseline | **✅ DONE** `97107e2e` |
| 1 | `:spotify` module | none | no | **✅ DONE** `42aaa9f8` |
| **1.5** | **Headless chain proof (inserted)** | **none** | **yes** | **✅ DONE** `b393250d` |
| 2 | Login + tokens | low | **yes — real login** | **✅ DONE + verified** `e65881a55` |
| 3 | Fetch + cache | **none** | **yes — the risky one** | **✅ DONE** `42eccd38c` |
| 4 | Video surface | **medium** | **yes** | ✅ **DONE** `8cba7cc2d` — device results in §14.1h |
| 5 | Long-press | medium | yes | ✅ **DONE** `6e7f2df59` — results in §14.1i |
| 6 | Swipe | low | yes | ✅ **DONE** `29e52c29d` — results in §14.1j |
| 7 | Settings + credits | low | yes | ✅ **DONE** `f39a66bdb` — results in §14.1l, §14.1m |

**Post-Phase-6 fix, not its own phase:** the Canvas wash was animating only one side of the
crossfade — the normal background was hard-cut the instant the first frame landed, so nothing was
crossfading and the transition read as a snap. Fixed in `bf03160ef`; see §14.1k.

Phases 0–4 are complete without a single gesture existing. That is the point: the risky work
is finished and proven before the interaction design is layered on top.

**Phase 1.5 was inserted, not originally planned.** Phase 1 proved the copy compiled but not
that it *worked*, and Phases 2–4 all depend on it working. Adding it cost one test file and
immediately caught the YouTube-ID-vs-Spotify-ID trap described in §14.1c — which would
otherwise have been discovered only after the login screen and settings wiring were finished.
Nine phases in total, not eight.

### 14.3 Commit discipline

- One commit per phase, on `testing`, conventional-commit format (`feat(spotify):`, etc.).
- **Explicit user authorisation requested before each commit.** No batched pre-authorisation.
- Never push. `main` is never touched.
- The `rollback/pre-video` tag (`1e4bf4618`) stays intact.
- `SPEC_SPOTIFY_CANVAS.md` is updated as each phase completes, marking it done with its commit
  hash — this file is the running record.

### 14.2 Deferred improvements (not blocking)

#### 14.2a CLOSED — Canvas upscaling softness. Accepted as-is; no code change was made.

**Found by the user during Phase 4, on device. CLOSED after Phase 7 by user decision — accepted
as-is, will not fix.** No code change was made, so **the behaviour below is still exactly as
described.** This section is kept as the record of a known limitation, not as a fix.

`CanvasBackgroundLayer` sets `C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING` (2). Media3 1.10.1
offers **only two** modes:

| Mode | Behaviour |
|---|---|
| `VIDEO_SCALING_MODE_SCALE_TO_FIT` (1) | Letterboxes |
| `VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING` (2) | **Nearest-neighbour** resample |

There is no filtered-scaling option; Media3 dropped it. Mode 2 magnifies per texel, so a 9:16 Canvas
upscaled to a 900×1600 screen shows blocky, aliased edges. **Why some tracks look worse than others
is the source resolution**, not the code path: a lower-resolution Canvas is magnified harder.

Mode 1 was rejected because spec §5.2 requires full-screen with no black bars. **The crop itself was
right; only the resampling is wrong.**

**The fix, when it is picked up:** do the crop in Compose instead of in the player — render the view
at the video's native aspect, centre it, and let it overflow a `clipToBounds()` container. The
compositor then does the scaling with proper filtering, still with no letterbox. Self-contained in
`CanvasBackgroundLayer.kt`, no spec change, no gesture interaction.

**Not yet verified:** the actual native resolution of the cached Canvas files has never been read. If
they are already at or above screen resolution this is far less visible than assumed, and the cheap
first step is to measure before doing any work.

**How it got here, because it is a repeatable mistake:** mode 2 was chosen for its *geometry*
behaviour (no letterbox) and its *rendering* cost was not checked. Same shape as the §14.1d
`layoutParams` defect — taking a line at face value for what it appears to do rather than what it
actually does.

---

## 15. Implementation soundness audit

Verified against the actual source trees on 2026-09-29, not assumed. Findings and their
consequences.

### 15.1 Version compatibility

| Component | SimpMusic | Metrolist | Verdict |
|---|---|---|---|
Ktor | 3.6.0 | 3.5.2 | Minor skew. All APIs used are stable across 3.x. **Accept 3.5.2; do not upgrade Metrolist's Ktor** — that would put the entire app's networking at risk for one module. |
Kotlin | 2.4.20 | (per catalog) | Compile in Phase 1; the code is plain Kotlin with no 2.4-only features. |
kotlinx.serialization protobuf | present | **absent** | Must be **added** (§4.2.1). |
`kotlinonetimepassword` | present | **absent** | Must be **added**. |
`org.brotli:dec` | present (decoder) | present (decoder) | Spotify needs the **encoder**; copy SimpMusic's helper. |
Media3 | 1.11.1 | 1.10.1 | Canvas uses a bare `ExoPlayer` + `MediaItem`. No exotic API. |
Room | 2.8.5 | 2.8.4 | Untouched — no schema change (§4.3). |

### 15.2 SimpMusic-internal dependencies — all resolved, plus one found at runtime

Audited every `import` in `core/service/spotify/src/commonMain`. Four imports resolve to
SimpMusic-internal modules (`:ktorExt`) that do not exist in Metrolist. All four have a
deterministic answer, specified in §4.2.1: two are copied verbatim (`CurlLogger`, brotli
helper), one is inlined to match Metrolist's existing engine (`getEngine` → `OkHttp`), and the
fourth (`kotlinonetimepassword`) becomes a new dependency. **No unresolved references remain.**

**That audit was necessary but not sufficient.** A **fifth** gap existed that no import audit could
find: Metrolist's own `org.apache.http.legacy` `<uses-library>` broke the newly added
`kotlinonetimepassword` at runtime through boot-classloader shadowing. It is invisible to the
compiler and invisible to `:spotify`'s own test APK. See §14.1f.

### 15.3 Engine choice consistency

SimpMusic's `getEngine()` resolves to **OkHttp on Android**. Metrolist's `:innertube` also uses
`HttpClient(OkHttp)`. Inlining OkHttp in `:spotify` therefore matches existing behaviour
exactly — it is a one-line substitution, not a behavioural decision.

### 15.4 Structural fit in Metrolist

- **Module pattern is established** — `:lrclib`, `:lastfm`, `:shazamkit`, `:paxsenix` are
  single-object Ktor facades, exactly the shape `:spotify` will take.
- **Background layer has a clean insertion point** — `Player.kt:1022-1096` is a single
  `fillMaxSize()` block containing style, wash, and particles. A Canvas layer slots in as
  another child of that container, with no restructuring of the surrounding layout.
- **No video surface exists today** — zero `TextureView`/`SurfaceView`/`PlayerView`
  references outside the login WebView. This is new ground, so no existing convention to
  conflict with; §7.1 documents the choice and its cost.
- **No conflicts with existing gestures** — `Thumbnail.kt` already carries
  `detectTapGestures` (L567) for tap-to-seek and a horizontal carousel driven by
  `swipeThumbnail`. The Canvas gestures are gated behind "a Canvas is active" (§6.3), so the
  default experience is untouched.

### 15.5 Risks carried forward from the previous failed attempt

Recorded so the failure cannot silently repeat:

| Previous failure | How this spec prevents it |
|---|---|
Merged audio+video via `MergingMediaSource`; `MergingMediaPeriod.selectTracks` threw `IllegalStateException: Children enabled at different positions.` | Explicitly rejected (§7.2). A **separate, isolated, muted** video player. |
Video player touching the audio path | Hard requirement: `setAudioAttributes(..., handleAudioFocus = false)`. Audio is untouchable. |
Silent failure modes | Every failure path specified in §10 with explicit behaviour, plus a §9.2 failure taxonomy so a transient error can never be cached as permanent. |
Unverifiable "does it work" | Phase 0 establishes confirmed-Canvas tracks; §11.2 has 13 concrete criteria; §11.3 requires logcat evidence. |

### 15.6 Assumptions still open

Honest list of what is **not yet proven**, each with when it gets resolved:

| Assumption | Resolved in |
|---|---|
A confirmed-Canvas track list exists | ✅ **Phase 0 — done, 8 tracks (§14.0a)** |
Canvas URLs are still live and resolve via our fetch path | Phase 3 |
`@ProtoNumber` mapping survives the copy intact | ✅ **Phase 1 — verified 21 = 21, files byte-identical** |
Metrolist's Ktor 3.5.2 has no missing API for this code | **✅ Phase 1 — compiles clean**
TextureView composites correctly under the existing background stack | Phase 4, on device |
No DB schema change needed | Confirmed by design (§4.3, §11.4) |

None of these block starting Phase 1, which is a pure copy with no UI and no device dependency.

---

## 16. Context dump (survives session compaction)

> **If you are reading this after a compaction, you have ZERO prior context.** This section is
> self-sufficient. Read it fully before touching anything. Do not re-derive what is already
> established here — it was all verified against running code and real devices.
>
> **Phases 0, 1, 1.5 and 2 are committed.** The `:spotify` module exists and the Spotify chain is
> proven end to end against the live API. Phase 2 added the login UI, which renders correctly.
> Phase 3 (Canvas fetch + cache) is next and is the highest-risk phase in the project.

---

### 16.1 What this project is

Porting **Spotify Canvas** — a looping animated artwork feature — from
**SimpMusic** (GPL-3.0) into **Metrolist** (GPL-3.0), and using it as the full-screen
background of Metrolist's expanded main player, triggered by a 2-second long-press on the
cover art.

This is a *sequel to a failed attempt*. See §16.8. The previous attempt was rolled back
completely at the user's request after four days with no working result. **That failure is the
single most important thing to understand before writing code.**

---

### 16.2 Repository and environment facts

| Fact | Value |
|---|---|
Working repo | `C:\musicapp\metrolist` |
Branch | `testing` |
HEAD | `42eccd38c` - *"feat(spotify): fetch and cache Canvas behind a temporary flag"* |
Working tree | **Clean** |
Remotes | `origin` = MetrolistGroup/metrolist, `personal` = 3mzn/Metrolist. **Never push.** |
Protected tag | `rollback/pre-video` → `1e4bf4618` — **must stay intact** |
Reference repo | `C:\musicapp\SimpMusic` @ `b967fda`, v2.2.0 (code 59) |
SimpMusic `core/` | **git submodule** → `maxrave-dev/core`, @ `9950eb1`. Empty on clone; **must** `git submodule update --init` or 564 files / 65.8k LOC are invisible. |
Android SDK | `C:\Users\emanf\AppData\Local\Android\Sdk` |
adb | `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` |
Emulator | `emulator-5556`, **x86_64**, Android **15**, 900×1600, density 320 |
Installed pkgs | `com.metrolist.music.debug` (Metrolist), `com.maxrave.simpmusic.dev` (SimpMusic reference) |
Metrolist version | 13.11.0, versionCode 178 |
Metrolist modules | `:app :innertube :kugou :lrclib :lastfm :betterlyrics :shazamkit :paxsenix` |
Metrolist scale | 625 Kotlin files, ~145.1k LOC |
SimpMusic scale | 969 files, ~148.6k LOC (incl. submodule) |

### 16.3 Hard rules (from AGENTS.md + user instructions)

- **Never modify markdown** except spec files needed for a feature. **Exception granted by the
  user: one README credit row for this project** (§2.1).
- **Never bump the app version** without explicit approval.
- **Never touch the database schema.** This project does not (§11.4).
- All new strings go in `app/src/main/res/values/metrolist_strings.xml`, **English only**.
- **One commit per phase**, on `testing`, conventional-commit format.
  **Explicit user authorisation is required before each commit.** No batched pre-authorisation.
- **Never push. `main` is never touched.**
- Per-phase gates: `:app:compileFossDebugKotlin` clean, `:app:testFossDebugUnitTest` full suite
  green (**139 tests** — 132 base plus 7 added in Phase 3).
- **Delete `app/build/outputs/apk/foss/debug` before assembling** (stale-APK rule).
- If in doubt, ask. Do not assume.

### 16.4 The licensing position (already settled)

Both projects are **GPL-3.0**. SimpMusic's `core/` submodule is GPL-3.0. The ported Spotify
source files carry **no per-file license headers** — only `package`/`import` lines — so there is
no conflicting attribution clause.

Obligations: GPL-3.0 §5(a) headers on every ported file; a README credit naming
**maxrave-dev** and **`spotify_monitor`** (Python, which the TOTP logic derives from).

**This is settled. Do not re-litigate it.**

### 16.5 The porting rule (the user's explicit instruction)

**SimpMusic's code is READY TO DIRECTLY TAKE. Copy it. Do not rewrite it.**

The user was emphatic: *"we want perfection from what's already built, not original but
dysfunctional garbage."* SimpMusic's Spotify implementation is production, working,
device-verified code. It is the asset.

**Only four changes are permitted** (full text in §4.2):
1. Package/import rewriting (`com.maxrave.spotify` → Metrolist convention)
2. Replacing SimpMusic-internal imports (four of them, §4.2.1)
3. Collapsing KMP `expect`/`actual` to Android-only
4. Adding GPL-3.0 headers

Anything else is **out of bounds**. If copied code looks wrong, verify against SimpMusic's
running behaviour — do not rewrite.

> **⚠ `@ProtoNumber` annotations MUST be preserved verbatim.** `CanvasResponse.kt` and
> `TokenResponse.kt` use them, and the client sends `Accept: application/protobuf` with Ktor's
> `protobuf()` converter. Spotify is spoken to in **protobuf**. Dropping these annotations
> **corrupts field mapping silently** — no compile error, no crash, just wrong data. This is
> the most dangerous possible copy error in this project.

### 16.6 Verified findings about SimpMusic (do not re-derive)

Established by instrumenting SimpMusic v2.2.0 on the emulator with real Spotify and YouTube
Music accounts.

**Works:**
- WebView login → `spdc` cookie captured
- Client token from `open.spotify.com/api/server-time`, spoofing
  `App-platform: WebPlayer` and `Spotify-App-Version: 1.2.61.20.g3b4cd5b2`
- TOTP secret fetched at runtime from
  `raw.githubusercontent.com/xyloflake/spot-secrets-go/refs/heads/main/secrets/secretDict.json`
- Randomised fake User-Agents assembled from random Chrome/macOS version numbers
- Canvas renders and loops for tracks that have one
- **Spotify lyrics also work** (verified: "Word by word / Lyrics provided by SimpMusic Lyrics")

> **⚠ THE ID TRAP — the single most expensive mistake available in this project.**
>
> Metrolist deals in **YouTube video IDs** (`zrW87-xUvt4`). Spotify's canvas endpoint wants a
> **Spotify track ID** (`1fk5Jx5ytAcfc9o1tcrQ1d`). These spaces do not overlap, and passing one to
> the other returns **`HTTP 200`, `Content-Type: application/protobuf`, 3-byte body `10 90 1c`** —
> a well-formed success meaning "no canvas for that id".
>
> From a decoded model this is **indistinguishable from broken protobuf field mapping**, which
> is the failure this spec warns about twice. A reader who hits it may "fix" the `@ProtoNumber`
> annotations and break code that is working.
>
> The real chain (SimpMusic, `LyricsCanvasRepositoryImpl.getCanvas:114-245`):
> ```
> YouTube videoId → local SongEntity (title, artist, duration)
>   → scrub query of "(feat.", "&", ")", "." etc.
>   → searchSpotifyTrack()
>   → candidate selection  ← SEE §14.1e, this is NOT duration matching in practice
>   → Spotify track ID → getSpotifyCanvas()
> ```
> The `:spotify` module has the search call and response model. The mapping glue is Phase 3
> work, and the **search step is mandatory** — there is no other route from a YouTube ID to a
> Spotify track ID.
>
> ⚠ **The candidate-selection step is NOT duration matching, despite appearances.**
> Upstream *looks* like it matches on duration (`abs(totalMilliseconds - duration) < 1`) but
> compares **milliseconds against seconds**, so the branch is unreachable and **the first search
> hit is always used**. Metrolist copies this verbatim **by user decision**, so it behaves
> identically to SimpMusic. Do **not** "repair" the units — full reasoning in **§14.1e**.

**The gate that blocks Canvas in SimpMusic** (`SharedViewModel.kt:286`) — **must NOT be
reproduced:**
```kotlin
if (nowPlaying.mediaItem.isSong() && nowPlayingScreenData.value.canvasData == null) {
    getCanvas(...)
}
// AllExt.kt:45
fun GenericMediaItem.isSong() = description?.contains(MERGING_DATA_TYPE.SONG) == true
```
Proven on device by log-line counts: `Timeline job: 3`, `Duration is: 0`, `Get lyrics: 4` —
the Canvas branch was never entered while the sibling lyrics path ran normally.

Metrolist has no equivalent gate because it has no video/Canvas slot at all.

**Corrections made during the session — do not repeat these mistakes:**

- ❌ **"Spotify Canvas requires Premium."** WRONG. Spotify community staff state Canvas is
  "account bound, so it's not dependent on your current plan." The user confirmed Canvas works
  on their non-Premium account. **Premium is not a gate.**
- ❌ **"Canvas doesn't work in SimpMusic."** WRONG — it does. The earlier "it was just the cover
  art" reading of a screenshot was a misread. The cover art was confirmed as cover art, and
  the real blocker was the `"Song"`/`"Video"` type gate above.
- ✅ Canvas is a **vertical 9:16** video, 3–8 seconds, loops.
- ✅ Most tracks have **no** Canvas. Silence on a no-Canvas track is correct behaviour and is
  **indistinguishable from a bug** unless a known-Canvas track is used as a control.
- ✅ **Proven working via our own port** (Phase 1.5, commit `b393250d`): all four chain tests
  pass against the live API, and the Canvas URL returned is byte-identical to the one SimpMusic
  had cached for the same track. Two independent implementations agree.
- ✅ An `sp_dc` cookie is **not permanent** (~4 hours observed). A stale one yields a
  **valid-looking anonymous token** (`isAnonymous=true`), not an error — so a login can appear
  to succeed and then fail silently when no Canvas comes back. Re-login in SimpMusic to refresh.
- ⚠️ SimpMusic's auth code asserts the personal token is exactly **374** chars; a live
  authenticated token measured 2026-09-30 was **395**. That constant is not a contract. The
  ported logic keeps it (it only drives a retry — the real gate is `accessToken.isEmpty()`), but
  **our tests assert `isAnonymous == false`, never a fixed length.**

### 16.7 Metrolist code facts (the exact touchpoints)

**Background stack — the Canvas insertion point is `Player.kt:995-1096`:**
```
995:  Box(                                          <- outer container
998:      .fillMaxSize()
999:      .background(bottomSheetBackgroundColor)
1000: ) {
...
1022-1039:  BLUR style branch
1043-1074:  GRADIENT style branch (AnimatedContent, 800ms crossfade)
1083-1087:  Box(animatedWashColor.copy(alpha = 0.4f))   <- track-change colour wash
1089-1095:  if (useNewPlayerDesign) { PlayerParticles(fillMaxSize, baseColor) }
1096:  }
```
The Canvas layer slots in as **another child of this container**. No restructuring of the
surrounding layout is required.

Other facts:
- `PlayerBackgroundStyle` enum = `DEFAULT, GRADIENT, BLUR` (in `constants/PreferenceKeys.kt`)
- `UseNewPlayerDesignKey` defaults **true** (`Thumbnail.kt:542`)
- Artwork is `Thumbnail(...)` at **`Player.kt:2069`**, passed `isLandscape = true` (L2073)
- `AnimatedContent` swaps artwork ↔ `InlineLyricsView` at **L2057-2078** — so on the lyrics
  view there is no artwork, hence no long-press target (§5.4)
- `Thumbnail` already has `detectTapGestures` (L567) for tap-to-seek, and a horizontal
  carousel gated on `swipeThumbnail` (L233-234, default ON)
- **No video surface exists anywhere** in Metrolist (zero `TextureView`/`SurfaceView`/
  `PlayerView` refs outside the login WebView). New ground; no convention to conflict with.
- Integration settings dir: `ui/screens/settings/integrations/` containing
  `DiscordSettings.kt`, `IntegrationScreen.kt`, `LastFMSettings.kt`, `ListenTogetherSettings.kt`
- Player settings file: `ui/screens/settings/PlayerSettings.kt`
- 43 ViewModels, Room v40 with 19 entities, Hilt DI, DataStore preferences

### 16.8 ⚠ THE PREVIOUS FAILED ATTEMPT — read this before writing any playback code

A prior attempt implemented a music-video background in Metrolist. It was **rolled back
completely** (`git reset --hard 7f168bf`, 15 commits and ~1200 uncommitted lines destroyed, no
backup) after four days. The user chose a hard rollback over salvaging it.

**Root cause of the failure:** a `MergingMediaSource(audio, video)` design. Verified against
Media3 1.10.1 release source, `MergingMediaPeriod.selectTracks` throws
`IllegalStateException: Children enabled at different positions.` because `ProgressiveMediaSource`
publishes a placeholder timeline (`durationUs = TIME_UNSET`, `isSeekable = false`) until its
extractor reports source info, and `MediaItem.durationMs` is ignored by it. Sharing an item
also shared failure (a video fault ended audio playback) and required a re-prepare on
long-press.

**Secondary blocker, which is the real lesson:** the byte→timestamp mapping in the spooled SABR
video. Appended offsets only correspond to timestamps within a single continuous collection
run, and SABR re-points constantly. Four successive patches each exposed the next problem.
Diagnosed as **structural, not a local bug** — no global anchor table can be made reliable.

**How this spec prevents a repeat** (mapping in §15.5):

| Previous failure | Prevention here |
|---|---|
`MergingMediaSource` audio/video merge | **Explicitly rejected** (§7.2). A separate, isolated, **muted** video player. |
Video player touching the audio path | **Hard requirement**: `setAudioAttributes(videoOnlyAudioAttributes, handleAudioFocus = false)`. Music is untouchable even if Canvas misbehaves. |
Silent failure modes | Every failure path specified (§10) plus a failure taxonomy (§9.2) so a transient error can never be cached as permanent. |
Unverifiable "does it work" | Phase 0 establishes confirmed-Canvas tracks; §11.2 has 13 concrete criteria; §11.3 requires logcat evidence. |

**Also relevant:** the previous attempt's 2s long-press was verified to work as a gesture — the
gesture itself is not the problem. Playback architecture is.

### 16.9 🔧 Build and device gotchas (learned the hard way — do not repeat)

> **⚠ Compose gesture timeout — silent, permanent, and invisible to the compiler.**
>
> Inside a `pointerInput` block, `withTimeout` resolves to the **`AwaitPointerEventScope` member**,
> not the kotlinx import. The member shadows the import, and on timeout it throws
> **`PointerEventTimeoutCancellationException`**, which extends plain `CancellationException` — it is
> **not** a subclass of kotlinx's `TimeoutCancellationException`.
>
> Catching the kotlinx type therefore never matches. The exception escapes, the `pointerInput`
> handler dies **for good**, and every later press is ignored with **no crash and no log**. The
> symptom is indistinguishable from "the gesture was never wired up", which is why diagnosing it
> cost several build cycles. Confirmed by reading `SuspendingPointerInputFilter.kt` in
> compose-ui **1.11.4** (`awaitPointerEventScope`'s `withTimeout` / `withTimeoutOrNull`).
>
> **How this project avoids it:** the Canvas hold uses **`withTimeoutOrNull`** and tests for `null`,
> so there is no exception to catch and no type to get wrong. If you add a timed gesture (spec §6.3's
> swipe is the next one), use the same shape. Two further traps in the same area:
> - **Never broaden the catch** to `CancellationException`; a carousel stealing the gesture must
>   still propagate or the scroll breaks.
> - **`awaitTouchSlopOrCancellation` suspends indefinitely here** and never delivered a `down` at
>   all. The framework's own `waitForLongPress` uses a raw `awaitPointerEvent` loop instead; so
>   does this one.
>
> **`CanvasHold` log tag is kept on purpose.** Because the failure above is silent, one Timber line
> per terminal path is retained. Metrolist's ProGuard config strips Timber in release, so it costs
> nothing in the shipped build, but "nothing happened" is never ambiguous again.

**SimpMusic (reference build):**
- Task is **`:androidApp:assembleDebug`**, NOT `assembleFossDebug`. SimpMusic has **no product
  flavours**; FOSS vs full is chosen by the `isFullBuild` Gradle property (currently `true`).
- Debug `applicationIdSuffix = ".dev"` → package is **`com.maxrave.simpmusic.dev`**.
- `local.properties` was missing and had to be created with **only** `sdk.dir`. Use
  `C:/Users/emanf/AppData/Local/Android/Sdk` (forward slashes — over-escaping breaks it).
  `LASTFM_API_KEY`/`LASTFM_SECRET` are intentionally omitted; the build falls back to empty
  strings and just disables Last.fm.
- `:androidApp:packageDebug` **fails** after all modules compile. The APKs are still produced
  and valid — check `androidApp/build/outputs/apk/debug/`, verify with
  `aapt2 dump badging`, and install. Do not chase this failure; it is not fatal.
- Install the **x86_64** APK for this emulator.
- **First launch ANRs** (a real upstream bug, not our problem):
  `MainActivity.onCreate` → `putString` → `runBlocking` on the main thread against DataStore →
  `SimpleMediaService` ANR after 100s. The user got past it; it is **first-run only** (the
  DataStore file must exist). Leave it alone — it is SimpMusic's bug, and we are not patching
  their source.

**adb / verification (both repos):**
- **`adb logcat` wraps in seconds on this emulator.** Evidence was lost twice before switching
  to a **background `Start-Job` streaming `logcat -s <tag>`** while triggering the event. For
  anything short-lived, stream to a job — do not grep a buffer cleared a minute ago.
- **PowerShell `>` mangles binary.** `adb exec-out screencap -p > file.png` produced an
  unreadable file. Use instead:
  `adb shell screencap -p /sdcard/x.png` → `adb pull /sdcard/x.png <dest>` → `adb rm`.
  Verify magic bytes `89 50 4E 47`.
- `uiautomator dump` fails with **"null root node"** if the app has no UI tree (e.g. hung on a
  splash). This was a useful hang detector.
- To detect animation, take rapid successive screencaps and compare MD5 hashes — differing
  hashes means the image is moving.
- Reading a Compose switch state: parse the `uiautomator dump` XML for `checkable="true"` nodes
  and read their `checked=` attribute. Watch for PowerShell string-concatenating your
  arithmetic — use explicit `[int]` casts or output the raw `bounds` attribute.
- ANR traces: `adb root` then pull `/data/anr/anr_<timestamp>` (not `traces.txt`).

### 16.10 The Spotify code — exact location and shape

**Source:** `C:\musicapp\SimpMusic\core\service\spotify\src\commonMain\kotlin\com\maxrave\spotify\`

| File | LOC | Note |
|---|---|---|
`SpotifyClient.kt` | 258 | Ktor client, spoofed UA, all endpoints |
`SpotifyAuth.kt` | 97 | Login orchestration |
`Spotify.kt` | 88 | Facade: `getCanvas`, `getSpotifyLyrics` |
`SpotifyTotp.kt` | 62 | `expect fun generateTotp` |
`extensions/StringExt.kt` | 8 | |
`model/response/spotify/CanvasResponse.kt` | 62 | **`@ProtoNumber` — preserve** |
`model/response/spotify/TokenResponse.kt` | 53 | **`@ProtoNumber` — preserve** |
`model/response/spotify/SpotifySearchResponse.kt` | 67 | lyrics-adjacent |
`model/response/spotify/ClientTokenResponse.kt` | 25 | |
`model/response/spotify/PersonalTokenResponse.kt` | 14 | |
`model/response/spotify/SpotifyLyricsResponse.kt` | 19 | out of scope |
`model/body/CanvasBody.kt` | 17 | |
`model/body/SpotifyClientBody.kt` | 26 | |
`model/response/SearchResponse.kt` | 29 | out of scope |

**Platform actuals to DROP entirely** (Metrolist is Android-only):
- `androidMain/.../SpotifyTotp.android.kt` (8)
- `jvmMain/.../SpotifyTotp.jvm.kt` (8)
- `iosMain/.../SpotifyTotp.ios.kt` (4)

**Canvas fetch logic to copy:** `getCanvas` in
`core/data/src/commonMain/kotlin/com/maxrave/data/repository/LyricsCanvasRepositoryImpl.kt`
(a 1054-line file; `getCanvas` is ~130 lines of it, starting around L114). SimpMusic also has a
working `spotifyCanvas` disk `SimpleCache` (declared in `core/media/media3/.../di/Media3ServiceModule.kt:135`,
cache name `"spotifyCanvas"`) — **copy its approach, do not invent a new cache design.**

**Four imports that do not exist in Metrolist** (all resolved, §4.2.1):
- `com.maxrave.ktorext.curl.CurlLogger` → **copy** from `core/service/ktorExt`
- `com.maxrave.ktorext.getEngine` → **inline** as `HttpClient(OkHttp)` (matches `:innertube`)
- `com.maxrave.ktorext.encoding.brotli` → **copy** (Metrolist has brotli *decode* only)
- `dev.turingcomplete:kotlinonetimepassword` → **add dependency**

**Plus one missing artifact:** `io.ktor:ktor-serialization-kotlinx-protobuf` is **absent** from
Metrolist's version catalog. Must be added.

**Ktor versions:** SimpMusic 3.6.0, Metrolist 3.5.2. Minor skew; all APIs used are stable across
3.x. **Do not upgrade Metrolist's Ktor** to chase parity — that would risk the whole app's
networking for one module. Accept 3.5.2.

> **⚠ RUNTIME-ONLY DEPENDENCY CONFLICT (§14.1f).** Metrolist's manifest used to declare
> `<uses-library android:name="org.apache.http.legacy" android:required="false" />`. That library
> is boot-classpath, so its **stub** `org.apache.commons.codec.binary.BaseNCodec` shadows the real
> `commons-codec` inside the APK, and `kotlinonetimepassword` dies with `NoSuchMethodError` on
> `BaseNCodec.isEmpty([B)`. **This breaks only inside `:app`** — `:spotify`'s instrumented tests
> do not declare the library, so they pass while the app fails. Already removed; do not re-add.

### 16.11 The three visual states (locked, do not redesign)

| State | Background | Particles | Artwork | Entered by |
|---|---|---|---|---|
**Normal** | gradient / blur / theme | **yes** | opaque | default, or 1s long-press to dismiss Canvas |
**Canvas on** | Canvas video | **no** | opaque | 1s long-press |
**Canvas revealed** | Canvas video | **no** | transparent | swipe up on artwork |

**Invariants:**
- Particles are **never** drawn over a Canvas. They return **only** when the Canvas itself is
  dismissed. There is **no fourth state** — this was explicitly corrected by the user.
- Artwork transparency is **only** ever present while a Canvas is rendering.
- Dismissing the Canvas returns the artwork to **fully opaque**.
- Swipe **down** must never be used.
- Canvas fills the screen — **no black bars, no letterboxing**. Centre-crop.

### 16.12 Gesture parameters (all locked by the user)

| Gesture | Value |
|---|---|
Long-press duration | **1000ms**, **invisible** (no ring, no overlay) |
Long-press haptic | **One short vibration at the 1000ms threshold** — NOT on press |
Long-press cancel | Early release does nothing; **no network fetch before 1000ms** |
Swipe direction | **Up only**, 60dp threshold, artwork square hit zone only |
Swipe tracking | **NOT finger-tracked** — fixed animation |
Artwork fade duration | **1000ms** (both directions) |
Background wash duration | **2000ms** in, **2000ms** restore |
Swipe state | Two-state exclusive toggle, not scrubbable |
Vertical swipe active | **Only while a Canvas is rendering** |
Independence | Independent of `swipeThumbnail`; horizontal song-swipe never disturbed |
Discoverability aids | **None** — the app is for the user and one other person |

### 16.13 Settings placement (locked by the user, deviates from my recommendation)

- **Canvas switch → `Settings → Appearance → Player`** (`PlayerSettings.kt`)
- **Spotify login → `IntegrationScreen`**, alongside Last.fm and Discord
- **Default: ON**
- When logged out: switch is **visible, greyed, subtitle "Log in to Spotify to enable"**;
  tapping it **still navigates** to login and toasts. After login, return to Player settings
  with the switch enabled.
- This is a deliberate deviation: `PlayerSettings.kt` gains navigation and Spotify-state
  dependencies so a switch can trigger navigation.

### 16.14 Caching and failure policy (locked)

- Positive: Canvas video cached to disk; URLs stable per track.
- Negative: **cached only after 3 attempts** ("100% sure"), and **only clean negatives** —
  a 404 or a confirmed-empty response. **Never** cache a timeout, 5xx, or 401/403 as "no canvas".
- Consecutive-failure counter **resets on any success** (otherwise 3 failures across a week trip it).
- **N = 3** consecutive track-level failures → a **high-priority notification** naming the
  cause, **once per run of failures**. The feature is **NOT disabled** and nothing re-arms
  automatically; the settings switch is the manual control. *(Originally specified as
  auto-disable + one toast. Changed by user decision in Phase 7 — see §14.1m.)*

### 16.15 Phase plan (full text in §14)

```
0 pre-flight → 1 :spotify module → 2 login+tokens → 3 fetch+cache
              → 4 video surface → 5 long-press → 6 swipe → 7 settings+credits
```

**The ordering principle:** the two riskiest phases (3 and 4) run **before any user-visible
feature exists**. Phase 3 has **no UI at all**. Phase 4 is driven by a **temporary internal
flag**, not a gesture. **Phases 0–4 are complete without a single gesture existing.** Gestures
are layered on last.

Each phase: one commit, `testing`, explicit user authorisation required.
Current status: **Phases 0, 1, 1.5, 2 and 3 are committed** — `97107e2e`, `42aaa9f8`,
`b393250d`, `e65881a55`, `42eccd38c`.
**Phase 4 — the Canvas video surface — is next.** Phases 0-4 are the ones that run
before any gesture exists, and Phase 4 is the last of them: it makes the Canvas actually
visible behind the player, still behind the temporary flag rather than the long-press.
### 16.16 If you are resuming

1. Re-read §16.8 (the previous failure) before writing any playback code.
2. Re-read the **ID trap** in §16.6 before writing any Canvas-fetch code. It is the mistake
   most likely to be made and most likely to be misdiagnosed.
3. Read **§14.1d** in full. It records the WebView `layoutParams` trap and the two
   misleading experiments that preceded it, so neither is repeated.
4. Confirm `git status` — clean once Phase 2 is committed.
5. Confirm `adb devices` shows `emulator-5556`.
6. **Phases 2 and 3 are fully verified** — §14.1d and §14.1g. No outstanding items.
7. Read **§14.1f** before touching the manifest. It records why the
   `org.apache.http.legacy` `<uses-library>` had to go and must not be re-added.
   user to complete a real Spotify login, then a DataStore read, then a re-run of the
   Phase 1.5 suite against the cookie our own login stored.
7. **If the `sp_dc` cookie has gone stale**, the symptom is `isAnonymous=true` rather than an
   error — re-login and re-copy.

### 16.17 Facts deliberately NOT established here

| Open | Resolved in |
|---|---|
A confirmed list of tracks that definitively have a Canvas | ✅ **Phase 0 — done, 8 tracks (§14.0a)** |
Canvas URLs still live and resolvable through our own fetch | ✅ **Phase 1.5 — proven against the live API** |
`@ProtoNumber` mapping survives the copy | ✅ **Phase 1 — verified 21 = 21, files byte-identical** |
Metrolist's Ktor 3.5.2 has nothing missing for this code | ✅ **Phase 1 — compiles clean** |
The YouTube→Spotify ID mapping works | ✅ **Phase 1.5 — proven** (search + Canvas URL returned) |
TextureView composites correctly under the existing background stack | Phase 4, on device |
| **Our own login persists a token the API accepts** | **✅ Phase 2 — closed, 4/4 with our own cookie (§14.1d)** |
Whether a clean negative needs exactly 3 attempts or fewer in practice | Phase 3 |

**No database schema change is required** — confirmed by design.

### 16.18 Confirmed Canvas tracks (Phase 0 output)

**Eight tracks, verified, full table in §14.0a:**

`zrW87-xUvt4` (Dat Bad) · `LqaDwpR0KuM` (Itsy Bitsy) · `3NVf0iAw5NY` (Producer Man) ·
`4OncUpJmhtk` (Room For You) · `9QrG7SUdbzs` (back from the dead) · `pIZN1xCFUvQ` (buzzkill) ·
`4y5B8A5DHwk` (do u really?) · `WXjWN08tnUU` (take me as I am)

All eight are **video** Canvas. Use these to verify any Canvas behaviour. Use a track **not** on
this list as the negative control.

Recovered from SimpMusic's database, not re-tested. To regenerate if the emulator is reset:
pull `Music Database`, `Music Database-wal` **and** `Music Database-shm` (the WAL holds recent
writes — main DB was 598 KB, WAL 4.1 MB), then
`SELECT videoId,title,canvasUrl FROM song WHERE canvasUrl IS NOT NULL AND canvasUrl != ''`.

---

## 17. Open items

**None blocking.** Phase 2's two verification items are closed (§14.1d): a real login
persists a 211-char `sp_dc`, and `:spotify:connectedDebugAndroidTest` passes **4/4 with 0
skipped** against that cookie, including `isAnonymous == false`.

**Phase 5 is next** — the 1s long-press gesture. Everything the risky phases needed is proven:
the login works end to end, the fetch and both caches are correct, the Canvas renders, and the
Spotify chain has never once interrupted playback.

**Both Phase 4 flags are `false` in the committed state.** Phase 5 replaces the flag with the real
gesture, and is the first phase where the user can trigger a Canvas at all.

**Two deferred items are recorded and must not be quietly dropped:**
- **§7.4 first-frame gate** — wired but not gating. Required in Phase 5 as part of the wash.
- **§14.2a** — Canvas upscaling is nearest-neighbour, so low-resolution Canvases look soft.
  Deferred by user decision; the native resolution has not even been measured yet.

**The one thing most likely to bite again:** classloading order. A transitive dependency can be
present and correct and still lose to a platform stub (§14.1f). Anything verified only in a module's
own test APK is **not** verified.

**Phases 0, 1, 1.5, 2 and 3 are committed** — `97107e2e`, `42aaa9f8`, `b393250d`, `e65881a55`, `42eccd38c`. The Spotify chain is proven end to end against the live
API (§14.1c) and the login screen renders (§14.1d). **Phase 3 — Canvas fetch and cache —
is next**, the highest-risk phase, and it runs with no UI at all behind a temporary flag.

#### 14.1l Phase 7 - the settings switch, and the one deliberate port deviation

Commit `f39a66bdb`.

**The settings switch** is in `AppearanceSettings.kt`, first row of that screen's **Player**
group. Not `PlayerSettings.kt`: the two screens are separate files that both contain a group
titled "Player", and the wrong one was chosen first. The user found it - twice - before the
placement was right. Worth recording because the failure was not a coding mistake but a
lookup mistake: the route string `settings/player` was searched, only `PlayerSettings.kt` was
found, and the conclusion drawn was that no such row existed. `AppearanceSettings.kt` was never
opened. Read the file you are changing, not the string you expect to find in it.

The switch gates the **fetch**, not the display: a disabled Canvas issues no network request at
all, so §6.1's rule that nothing is fetched until a completed hold survives the switch being off.

**The port deviation.** `SpotifyClient.kt` sets `expectSuccess = false`, so Ktor does not throw
on a 401/403 or 5xx - it returns an ordinary response whose body is Spotify's protobuf *error*,
not a `CanvasResponse`. `getSpotifyCanvas` therefore did:

    runCatching { spotify.getSpotifyCanvas(...).body<CanvasResponse>() }

which cannot distinguish "this request failed" from "this track has no Canvas". The body was
decoded blind. Both possible outcomes are bad: either it throws (status lost, misreported as a
transport failure) or it decodes to an empty response, which becomes a `CleanNegative` and is
cached as a permanent "no Canvas" verdict after three attempts. **An outage could therefore
silently mark tracks as having no artwork, with no recovery.**

Fixed in `Spotify.kt` by rejecting the status before the body is touched:

    if (!response.status.isSuccess()) throw SpotifyHttpException(response.status.value)
    response.body<CanvasResponse>()

This is the **only** deliberate deviation from the as-ported networking, approved by user
decision, and it changes nothing on a successful fetch. It is what makes §9.2's stated
taxonomy enforceable rather than accidental: with a status in hand, 401/403, 5xx and a genuine
empty answer are three different things.

**The notification replaces the auto-disable**, also by user decision. §9.2 originally said
three consecutive failures would disable the feature until manually re-enabled. Permanently
disabling a setting on the user's behalf is the more surprising behaviour, and the Phase 7
settings switch is already a manual kill-switch - so the app now says what went wrong instead.
`IMPORTANCE_HIGH`, and **once per run of failures**: the trigger is `failures == LIMIT`, not
`>=`, because the counter increments one at a time and resets on any success. `>=` would notify
again on every further failure through a long outage. Pinned by `CanvasFailureCauseTest`.

Three distinct messages, from `FailureCause`: `UNREACHABLE`, `AUTH_EXPIRED` (401/403, or a stale
`sp_dc` cookie), `SERVER_ERROR`. `FailureCause` is deliberately **not** consulted by
`CanvasCachePolicy` - §9.2 groups all three together for caching, and only the user-facing message
distinguishes them. The policy and its 7 tests are untouched.

**Decode failure** falls back silently and explicitly does **not** move the failure counter: the
network and the session were both fine, so counting it would report a Spotify problem that does
not exist. It also clears `canvasArtworkRevealed` and `canvasFirstFrameReady`, because a
transparent artwork must never be left over a normal background.

Gates: :app:compileFossDebugKotlin clean, :app:testFossDebugUnitTest **144/144** (139 + 5 added
in `CanvasFailureCauseTest`).

Verified on device (emulator-5556): switch present and first in the Player group, toggling off
suppresses the Canvas entirely, logged-out greying and login navigation, and no Canvas-related
rows on the separate "Player and audio" screen. The failure notification was subsequently
verified separately with a dead proxy — see §14.1o.

README: credit row added to "Main Inspirations" for maxrave-dev and spotify_monitor, the latter
being the source of the TOTP logic credited in `SpotifyTotp.kt`.

Still open, and deliberately so: **§11.2 #12, landscape / `isFullScreen` / tablet**, deferred by
user decision. The Phase 7 gate is amended to exclude it, because otherwise it could never pass.


#### 14.1n Landscape closed as PASSED

Closed by user decision after Phase 7, following on-device verification. This is the last item
that had never been exercised — every other §11.2 criterion had at some point been run by hand.

Phase 5 had noted landscape as "the one check Phase 4 never got to", and it stayed unverified
through Phases 5 and 6 because it was deferred twice by user decision. Both `Thumbnail` call
sites in `Player.kt` carry the swipe plumbing, and `CanvasBackgroundLayer` fills `Modifier
.fillMaxSize()` with cropping, so the geometry was never at particular risk — but that is an
argument, not a test, and the deferral existed precisely because build-time reasoning had already
missed one real defect (§14.1d's `layoutParams`).

#### 14.1o — the failure notification, verified end to end

§11.2 #11 splits into two halves, and both have now been seen on a device:

| Half | Result |
|---|---|
| Silent fallback on a failed fetch | Exercised throughout Phases 3–7 |
| High-priority notification after 3 failures | **Verified** — user-tested on device |

Verified with the dead-proxy technique (offline without taking the emulator's own adb connection
down, unlike `cmd connectivity airplane-mode`):

    adb shell settings put global http_proxy 127.0.0.1:1

then three long-presses on a known-Canvas track. Two things confirmed at once:

1. **The notification fires** — once, not three times. That is the `failures == LIMIT` rather than
   `>=` distinction from §14.1l doing its job under real conditions, with the counter incrementing
   one failure at a time.
2. **Nothing is cached as a failure while Spotify is unreachable.** This is the more important
   result, and it is the §9.2 guarantee that the §14.1l status check exists to protect: an outage
   produces `UNREACHABLE`, which moves only the consecutive-failure counter and never
   `noCanvasAttempts`. A track's Canvas verdict is untouched by an outage, so the Canvas is still
   there when Spotify comes back.

Point 2 is worth stating plainly because it was the defect the status check was added to close.
Before it, a dead network could decode as an empty response and become a cached "no Canvas" after
three attempts — permanent, with no recovery short of clearing the cache. That is now demonstrably
not happening.

**Still reasoned about rather than observed:** `AUTH_EXPIRED` (a 401/403 or a stale `sp_dc` cookie)
and `SERVER_ERROR` (any other non-2xx). Only `UNREACHABLE` has met a real response. The other two
share the same status check and the same code path — the notification is the same, only the
message differs — so the untested part is the classification, not the delivery.
