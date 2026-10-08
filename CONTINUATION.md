# CONTINUATION.md — current state

> Rewritten 2026-10-08. The previous version of this file described v13.6.9 (158) and its
> "next steps" were all completed months ago. It is now a **current-state** document that points
> at the individual spec files rather than duplicating them.
>
> **Read this, then read the spec file for whatever you are touching.** Feature detail lives in
> the specs; feature *history* and hard-won lessons live in those same specs.

---

## 1. Identity

A private two-person fork of the Metrolist YouTube Music client.

| | |
|---|---|
| **Users** | **eman** (this fork's owner) and **aswini** (partner). All user-facing names are lowercase. |
| **App** | `com.metrolist.music` — debug builds are `com.metrolist.music.debug` |
| **Version** | **15.0.3 (185)**, compileSdk 37, minSdk 26, targetSdk 36, JDK 21 |
| **Branch** | `testing` is the working branch; `main` is fast-forwarded to it on merge |
| **Rollback tag** | `rollback/pre-video` → `1e4bf4618` — **must stay intact** |

## 2. Remotes

| Remote | Target | Push policy |
|---|---|---|
| `origin` | `MetrolistGroup/Metrolist` | **Read-only. Never push.** |
| `personal` | `3mzn/Metrolist` | **The only push target.** |

The fork is **permanently diverged** and must never sync with upstream. Upstream migrated to
InnerTubeX and deleted the entire cipher/stream layer plus all social features; syncing would
require porting ~85 commits. Monitor upstream only for YTM breakage indicators.

---

## 3. Paths

| What | Path |
|---|---|
| Working project | `C:\musicapp\metrolist` |
| Upstream original clone | `C:\musicapp\Metrolist_original` (fork point = `732dd13db`) |
| OuterTune reference | `C:\musicapp\OuterTune\`, `C:\musicapp\OuterTune_original\` |
| Android SDK | `C:\Users\emanf\Android\Sdk` → actually `C:\Users\emanf\AppData\Local\Android\Sdk` |
| adb | `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` (on USER PATH) |
| Release keystore | `app/keystore/release.keystore`, alias `metrolist`, pass `metrolist123` |
| Release script | `C:\Users\emanf\tools\adb-tailscale.ps1` (ADB-over-Tailscale reconnect) |
| Temp scratch | `C:\Users\emanf\AppData\Local\Temp\opencode\` |

**Never print these tokens:** `~/.config/supabase-management-token`, `~/.config/supabase-key`,
`~/.config/supabase-anon-key`, `~/.config/firebase-service-account.json`.

## 4. Devices

| Device | ADB serial | Notes |
|---|---|---|
| **Phone** | `ylwwmn85w4ifb6z9` | Redmi Note 14 (`24117RN76G`), 1080×2400, density 450, HyperOS 3, Android 16 (SDK 36). Runs as **eman**. Has the release build installed. |
| Emulator | `emulator-5556` (varies) | Runs as **aswini**. No GMS → cannot receive FCM. |

**Wireless ADB is unreliable on HyperOS** — it auto-disables the toggle. Use Tailscale:
`adb connect 100.121.44.102:5555`, or plug USB once then `adb tcpip 5555`.
When devices vanish: `adb kill-server; adb start-server`, then `adb devices`.

## 5. Remote access (working)

- **Tailscale** on both devices, account `emanfunnyalt2`.
  - Laptop `100.73.117.90` (`laptop-mgn4vndv`), phone `100.121.44.102` (`redmi-note-14`).
- **OpenCode server**, always-on via Windows scheduled task `OpenCode Server (always-on)`:
  `opencode.exe serve --hostname 0.0.0.0 --port 49374`, at logon, restart on failure.
- Web UI: `http://100.73.117.90:49374`
- Tailscale is whitelisted from doze on the phone.
- **Open item:** the server password is unconfirmed, and there is no Tailscale ACL. The user
  deferred both on 2026-10-08.

---

## 6. Specs — the running record

Every feature has a spec file. **Never leave a spec outdated; never work without one.**

| Spec | Feature | State |
|---|---|---|
| `SPEC_HOME_WALLPAPER_RINGS.md` | Live wallpaper bass-reactive rings | ✅ Phases 0–4 done, verified on device |
| `SPEC_SPOTIFY_CANVAS.md` | Spotify Canvas as player background | ✅ All 9 phases done |
| `SPEC_SPOTIFY_MIRROR.md` + `SPOTIFY_MIRROR_GUIDE.md` | Spotify playlist auto-mirror | ✅ Shipped 13.11.0 |
| `SPEC_7.md` | Listen Together invites | ✅ 16 locked decisions, device-verified |
| `SPEC_8.md` | "Us" shared playlists | ✅ 3 open defects (§10) |
| `SPEC_13.md` | Partner mode (eman/aswini) | ✅ Final |
| `SPEC_9*.md` (4 files) | In-app update push | ✅ Shipped; 1 open workflow bug (§10) |
| `SPEC_LT_CHAT.md` + `LTCHAT_*.md` (3) | Listen Together chat | ✅ Shipped, **audit fixes applied** (§10) |
| `SPEC_COVER_PULSE.md` | Bass-pulse main cover | ✅ Shipped |
| `SPEC_MINI_BORDER.md` | Bass-reactive mini-player border | ✅ Shipped |
| `SPEC_SHARED_RECEIVE_SPEED.md` | Parallel shared-playlist receive | ✅ Shipped 13.10.1 |
| `SPEC_MIRROR_FIXES.md` | Mirror audit batches | ✅ Shipped |

**Roadmap:** `MAYBE_LATER.md`. **Test status:** `PENDING_TESTS.md`. **Known bugs:**
`PROBLEMS.md` (both entries fixed in 13.9.10). **Parked:** `PARKED_MINIPLAYER_GLOW.md`.

## 7. Architecture

Kotlin + Compose + Material 3, Media3/ExoPlayer, Room (v40), Hilt, DataStore, Firebase, Coil,
AGSL/RuntimeShader.

**Modules:** `:app :innertube :lrclib :kugou :lastfm :betterlyrics :paxsenix :shazamkit :spotify`

**Key facts:**

- `MusicService.kt` (~5,100 lines) is a Media3 `MediaLibraryService` with triple ExoPlayer
  (main/secondary/fading for crossfade), custom DSP, downloads, Discord RPC, Cast, Android Auto.
  It owns the social/widget/heartbeat wiring.
- `CoverBassPulse.kt` is a **process-wide singleton** `object` holding one `Visualizer`. It is
  shared by the player screen and the wallpaper. Read §10 for the ownership discipline.
- `App.kt` configures the Coil singleton with a disk cache at **`cacheDir/coil`**. Anything that
  builds its own `ImageLoader.Builder(...)` gets **no disk cache** — this has caused two bugs.
- Room v40, 19 entities, auto-migrations with pre-migration backups. **No schema changes without
  explicit sign-off.**
- Firebase project `outertune-social`, region `me-central1`. Collections: `users`, `friends`,
  `friendRequests`, `sentSongs`, `status`, `invites`, `sharedPlaylists`, `lt_chat_messages`,
  `lt_chat_presence`, `partners_deleted`.
- Supabase `teeafutbybbywitdahpr`, bucket `releases` (public). FCM topics:
  `metrolist_foss_updates`, `metrolist_gms_updates`.

## 8. Release process

```bash
# 1. bump version in app/build.gradle.kts (needs user approval)
# 2. commit + push to BOTH testing and main
git push personal testing
git push personal HEAD:main

# 3. build (delete stale dir first)
rm -rf app/build/outputs/apk/foss/release
./gradlew :app:assembleFossRelease

# 4. sign
apksigner sign --ks app/keystore/release.keystore --ks-pass pass:metrolist123 \
  --key-pass pass:metrolist123 --ks-key-alias metrolist \
  --out Metrolist.apk app/build/outputs/apk/foss/release/app-foss-release-unsigned.apk

# 5. GitHub release + tag
gh api -X POST repos/3mzn/Metrolist/releases -f tag_name=vX.Y.Z ...
gh api --method POST "https://uploads.github.com/repos/3mzn/Metrolist/releases/<id>/assets?name=Metrolist.apk" \
  -H "Content-Type: application/octet-stream" --input Metrolist.apk

# 6. FCM push (uploads to Supabase + writes latest-foss.json + broadcasts)
node push-update.js Metrolist.apk
```

**Always verify after publishing** — do not trust the success messages:
`latest-foss.json` serves the right version, the public APK returns 200 at the expected size,
the SHA in the JSON matches the local file, the asset state is `uploaded`, and the tag points at
the same commit as `main`.

Current release: **v15.0.3**, APK SHA-256 `016311ef9f0bcb5c773fce4865d2eed62c10b916f805829c662e8fb134f13c20`,
signing cert SHA-256 `215ab5eb74e2f1ca4938680185324fa801dc1566a53a15ae498ceae1764df3a0`.

---

## 9. Rules that must not be broken

1. **Never push to `origin`.** Only to `personal`.
2. **Never bump the version** without explicit approval.
3. **Never change the Room schema** without explicit sign-off.
4. **One commit per phase, with explicit user authorisation.** No batched pre-authorisation.
5. **No force-push, no history rewriting**, no branch deletion without explicit instruction.
6. **Strings only in `app/src/main/res/values/metrolist_strings.xml`** (English). Never
   `strings.xml`, never another language.
7. **Per-phase gates:** `:app:compileFossDebugKotlin` clean and
   `:app:testFossDebugUnitTest` **144 green**. Full release build before shipping.
8. **Delete `app/build/outputs/apk/foss/release` before assembling** (stale-APK rule).
9. **Never edit `README.md` or `AGENTS.md`.** Spec files for a feature you are implementing are
   the exception, and only with the user's approval.
10. **Use the Read tool for reading code — never shell.** Shell is for executing commands.
11. **Never guess at device behaviour.** Read logcat and screenshots. The user tests on hardware.
12. **Don't commit, push, install, or start fixing a bug** without being asked.
13. If in doubt, ask. Do not assume.

## 10. Open items

### Bugs

- **Same private-loader defect in two other widgets.** `MetrolistWidgetManager.kt:41` and
  `MusicRecognizerWidgetService.kt:71` both build private `ImageLoader.Builder(...)` instances
  with no disk cache, so they will fail offline exactly as the partner widget did. **Not fixed**
  — the user only authorised the partner widget. Same one-line fix as
  `PartnerWidgetManager.kt:95` (use `context.imageLoader`).
- **`release.yml:58-70`** compares `HEAD` to `HEAD^`, so a multi-commit push containing a
  version bump silently skips the release. Should compare `github.event.before`.
  Not urgent — releases are done manually now.
- **LT chat rules/indexes deployed?** `firestore.rules` and `firestore.indexes.json` contain the
  correct, audit-fixed rules for `lt_chat_messages` / `lt_chat_presence`. Unconfirmed whether
  Firebase actually has them deployed.

### SPEC_8 "Us" playlists — 3 open defects

1. Bulk receive can crash: `SQLiteConstraintException` FK race at
   `SharedPlaylistRepository.addSongLocally`.
2. Remove-song is reverted by cloud reconciliation (reappears after 1–2 min).
3. The "X new" badge never appeared on device.
4. Shared-card outline is hard to see and doesn't react to the palette.

### Unverified

- **Partner-song-on-debug-test-off path** — audited by reading, never confirmed on hardware.
  Needs aswini playing a track while the debug test is off.

### Parked / deferred

- `PARKED_MINIPLAYER_GLOW.md` — MiniPlayer glow uses unpremultiplied alpha. Deferred by the user
  on purpose; fixing blind risks making the approved look worse.
- Server password + Tailscale ACL (§5).
- Debug build `com.metrolist.music.debug` is still installed on the phone as a separate app.
- Wallpaper feature ideas (touch ripples, constellation, metaballs) — unspecced.
- Canvas bulk download feasibility — no spec written.

---

## 11. Hard-won lessons

**These cost real time. Read them before writing code in these areas.**

### Diagnosing on-device

- **Read logcat before concluding anything.** Twice a "convincing" diagnosis was wrong and logcat
  held the real answer immediately.
- **`android.util.Log.i` survives R8; `Timber.d` does not.** Use it for release diagnostics.
- **Measure, don't infer.** Pixel luminance scans, `uiautomator dump`, MD5-comparing successive
  screenshots, `dumpsys SurfaceFlinger`. A confident diagnosis from plausibility was wrong twice.
- **Two independent methods agreeing on a wrong answer is how a bad measurement earns false
  confidence.** The device test is what catches it.
- Streaming logcat to a background job — a buffer cleared a minute ago is useless for
  short-lived events.

### Bugs that were invisible to tests

- **A `return` outside a `try` skips the `finally`** and latches a flag permanently
  (`widgetUpdateInFlight`). 144/144 tests stayed green through the whole bug.
- **A comment can describe intent rather than behaviour.** `// the rings stop drawing` was false,
  and `// "pixel-verified"` overclaimed a measurement. Both were mine.
- **Sharing a process-wide singleton between two independent lifecycles** is where real risk
  lives (`CoverBassPulse`). Three ownership bugs, all invisible to the test suite:
  a consumer that never re-asks, a leaked `Visualizer`, and an ordering mistake
  (`lastInitSession` recorded before `hardRelease()` nulled it — the wallpaper re-created the
  analyser 60×/second).
- **A private `ImageLoader.Builder(...)` gets no disk cache.** The app's singleton attaches one
  at `cacheDir/coil`. Cost two bugs.
- **Coil keys its disk cache by request URL *and* size/precision.** Asking for `w1080` when the
  app warmed `w544` is a guaranteed miss even with the art on disk.
- **A WebView can report complete success and paint nothing.** Assert on rendered geometry
  (`document.body.clientHeight`), never on callbacks.
- **On Android 13+ (API 33), a WebView may need explicit layout params** or its document
  measures zero height while the view measures full size.
- **A Compose `pointerInput` `withTimeout` resolves to the `AwaitPointerEventScope` member**, not
  the kotlinx import, and throws a `CancellationException` subclass that catching the kotlinx
  type never matches. Use `withTimeoutOrNull` and test for `null`.
- **`awaitTouchSlopOrCancellation` suspends indefinitely** in this codebase and never delivers a
  `down`. Use a raw `awaitPointerEvent` loop.

### Android platform traps

- **`EXTRA_LIVE_WALLPAPER_COMPONENT` must be a `ComponentName` Parcelable, not a String.**
  A String throws inside `LiveWallpaperChange.init()` and the activity finishes instantly —
  indistinguishable from "nothing happened".
- **AGSL needs `lockHardwareCanvas()`.** `lockCanvas()` returns a software canvas and
  `RuntimeShader` throws.
- **AGSL output is premultiplied alpha.** Returning `(cr, cg, cb, a)` with unpremultiplied colour
  means alpha is effectively ignored and you get a flat fill.
- **Shader time must be wrapped (~60 s)** or `cos()` loses float32 precision and the orbit
  freezes.
- **Android has no API to *unset* a live wallpaper** — only to change it. `clear()` + the
  `SET_WALLPAPER` permission is the only programmatic route.
- **`<uses-library>` is loaded by the boot classloader**, so a platform stub can shadow a
  correct dependency already in the APK. `org.apache.http.legacy`'s stub `BaseNCodec` broke
  `kotlinonetimepassword` — invisible to the compiler and to the module's own test APK.
- **`WallpaperService` and `MusicService` are the same process.** A `WallpaperService` can read
  the player's state directly — no IPC needed.
- A live wallpaper renders **behind** widgets, so rings must be drawn **just outside** the
  widget rect, not the rect interior.

### Debug builds

- **The debug package is `com.metrolist.music.debug`** — a *separate app* with separate data.
  Installing it over the release build does **not** replace it; `adb install -r` "succeeds" while
  `lastUpdateTime` never changes. Verify, don't trust.
- A stale pre-install process serves old code after reinstall. Force-stop.
- HyperOS blocks ADB installs until the phone prompt is tapped (`INSTALL_FAILED_USER_RESTRICTED`).

### Tooling

- `Set-Content` / `Out-File` mangle UTF-8 and corrupt binary. Use
  `[System.IO.File]::WriteAllText` with `UTF8Encoding($false)`.
- Apostrophes in Android XML strings must be escaped (`Couldn\'t`) — an unescaped one fails
  resource flattening and breaks the release build *after* signing.
- PowerShell `>` corrupts binary via UTF-16. Use `cmd /c` redirect.
- Coil 3 AAR internals aren't readable via bytecode strings; don't reverse-engineer cache-key
  semantics — verify empirically.

---

## 12. User preferences

1. **Short responses.** No emojis.
2. **Never start fixing a bug without first learning the feature's code fully** — all of it,
   including the UI.
3. Ask before big implementations. Round-based questions were well received.
4. **The user does all device verification.** Read the logs yourself; don't guess.
5. Pushes go to `personal`. Merge = fast-forward `main` to `testing`.
6. Explain git simply — the user is learning.
7. When the user says "stop" or "stand by", comply immediately and exactly.
8. Report findings honestly, including when a diagnosis turns out to be wrong.
9. Measure and report numbers, not impressions.

---

## 13. Historical reference

Preserved because it is genuinely useful, not because it describes current state.

**The Listen Together testing war (Aug 2026)** — the most instructive debugging episode in the
project. An invite feature shipped "successfully", then failed completely on device. The
diagnosis sequence: room creation actually *succeeded* (a global toast proved it) → Firestore
`PERMISSION_DENIED` on the sender's listener → rules deny reads of *nonexistent* docs, so all
sender-side reads had to become queries → listeners attached with a `null` uid because auth
restores asynchronously, requiring an `authUidFlow` + `flatMapLatest` → and finally the actual
cause: `inviteNotifier.start()` was **never called**.

Two rules learned, both still in `firestore.rules`:
- `resource.data.x` errors on a nonexistent doc → any rule touching it denies reads for missing
  docs. Short-circuit on the doc id.
- **Owner-token writes bypass rules**, so a test invite can be planted via the Firestore REST
  API when the client path can't be exercised.

**Guest playback control** — investigated and **dropped** by the user: suggest + auto-approve
already covers adding songs, and the metroserver relay behaviour for guest actions is unknown and
external. Do not resurrect without being asked.

**Partner widget mojibake** — pre-existing double-encoded em-dashes in
`PartnerWidgetManager.kt` from commit `0201913c7`. Documented, deliberately untouched.

**Upstream sync** — verdict is **NEVER SYNC**. See §2.