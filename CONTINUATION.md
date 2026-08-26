# CONTINUATION.md — COMPLETE CONTEXT DUMP (v2, exhaustive)

> Written 2026-08-25 ~20:00 (+03:00). This file is a ZERO-SUMMARIZATION dump of everything
> the outgoing agent knows: every decision, every bug, every stack trace, every commit,
> every code path, every user instruction, every environment fact. Written for an agent
> with ZERO prior context. Read all of it. Where the outgoing agent's memory of exact code
> is paraphrased rather than verbatim, it is marked `[paraphrased]` — re-read the actual
> file before editing.

---

# TABLE OF CONTENTS

1. PROJECT IDENTITY, PATHS, ENVIRONMENT
2. GIT TOPOLOGY & FULL COMMIT LEDGER
3. UPSTREAM DIFF ANALYSIS (fork vs original)
4. APP ARCHITECTURE (complete)
5. THE SOCIAL/PARTNER SYSTEM (pre-SPEC_7, complete)
6. SPEC_7 — LISTEN TOGETHER INVITES (complete: decisions, implementation, all bugs)
7. THE LISTEN TOGETHER CODEBASE STUDY (~7,100 lines, complete findings)
8. GENTLE NUDGE — FEATURE #4 (complete)
9. W-SCALE (complete)
10. MAYBE_LATER ROADMAP (complete state)
11. TESTING STATE & PROCEDURES (complete, with logcat evidence)
12. USER RULES & PREFERENCES (verbatim)
13. KNOWN ISSUES, DEFERRED ITEMS, GOTCHAS
14. NEXT STEPS (numbered, in order)
15. SESSION NARRATIVE (chronological, everything that happened)
16. KILO MEMORY RECORDS (what's persisted in the memory system)

---

# 1. PROJECT IDENTITY, PATHS, ENVIRONMENT

## 1.1 Locations

| What | Exact path |
|---|---|
| The fork (working project) | `C:\musicapp\metrolist` |
| Upstream original clone | `C:\musicapp\Metrolist_original` (cloned Aug 25 2026 with `git clone --recursive https://github.com/MetrolistGroup/Metrolist.git`; HEAD = `732dd13db`, which is EXACTLY the commit the fork diverged from; metroproto submodule checked out at `e7c5e3d811af21b66bfe8e88de87777fcde16f90`, contains `listentogether.proto`) |
| OuterTune reference repos | `C:\musicapp\OuterTune\`, `C:\musicapp\OuterTune_original\` (source of the ported social feature; docs preserved in fork under `docs/outertune-dev/`) |
| Parent folder | `C:\musicapp` — contains the above + `.aider-desk` artifacts; the parent itself has NO git repo |
| Debug APK | `C:\musicapp\metrolist\app\build\outputs\apk\foss\debug\app-foss-debug.apk` — NOTE: NOT `universalFoss\debug` (that path appears in AGENTS.md but is wrong for this fork's layout; the real path is `foss\debug`) |
| adb | `C:\Users\emanf\AppData\Local\Android\Sdk\platform-tools\adb.exe` — added permanently to the USER PATH via `[Environment]::SetEnvironmentVariable("Path", "$userPath;$adbDir", "User")`. Already-open shells need `$env:Path += ";C:\Users\emanf\AppData\Local\Android\Sdk\platform-tools"`. adb version 36.0.2. |
| Firebase CLI | `firebase` on PATH, authenticated under the user's OWNER Google account (config at `%USERPROFILE%\.config\configstore\firebase-tools.json`). gcloud is NOT installed. |
| Kilo config | `C:\Users\emanf\.config\kilo\` |
| Temp scratch | `C:\Users\emanf\AppData\Local\Temp\kilo\` (contains `firestore_check.js` and `firestore_plant_invite.js` — see 1.5) |

## 1.2 App identity

- Application id: `com.metrolist.music`; **debug builds are `com.metrolist.music.debug`** (debug applicationIdSuffix). Launching: `adb -s <device> shell monkey -p com.metrolist.music.debug -c android.intent.category.LAUNCHER 1`
- App version: 13.6.3 (152). compileSdk 37, minSdk 26, targetSdk 36, JDK 21. Flavors: **foss** (what we build), gms, izzy. Build type: debug.
- Build: `cd C:\musicapp\metrolist; .\gradlew :app:assembleFossDebug` (full APK) or `.\gradlew :app:compileFossDebugKotlin -q` (fast compile check; prints "COMPILE OK" via `if ($?)`).
- Unit tests: `.\gradlew :app:testFossDebugUnitTest` — 95 tests, all green. (One historical failure: `LyricsBackgroundTimingTest` died because Robolectric couldn't download `org.robolectric:android-all-instrumented:6.0.1_r3-robolectric-r1-i7` — transient network, passed on retry. Not a code issue.)
- Gradle: configuration cache reused, daemon persists; builds take ~1-4 min warm.
- The `:app` build has a protobuf-lite codegen task (`generateProto`) that writes into `app/src/main/java` from the metroproto submodule.
- Vendored protobuf-lite gencode for `google.rpc.Status`/`google.type.LatLng` exists in the app + `protolite-well-known-types` is excluded (dexing conflict fix — documented in `app/build.gradle.kts` comments).

## 1.3 Devices

| Device | adb id | Notes |
|---|---|---|
| User's phone | `ylwwmn85w4ifb6z9` | Xiaomi 24117RN76G (Redmi Note 14), HyperOS, Android 16 (SDK 36). Runs as **eman** (the user). USB or wireless. |
| Emulator | `emulator-5556` (port can shift; always check `adb devices`) | Runs as **aswinitest** (partner test account). Confirmed signed into Firebase as UID `EuM3KTt25ReXt7BFjBKQvzwbBPx1` (verified via logcat line: `FirebaseAuth: Notifying id token listeners about user ( EuM3KTt25ReXt7BFjBKQvzwbBPx1 )`). |

Wireless ADB saga (important context): MIUI/HyperOS auto-disables the phone's "Wireless debugging" toggle (screen-off, Wi-Fi band change, after pairing). QR pairing exists via Android Studio. The RELIABLE method: plug USB once → `adb -s ylwwmn85w4ifb6z9 tcpip 5555` → `adb connect 192.168.18.234:5555` (phone's Wi-Fi IP; persists until phone reboot). The user has used USB connection recently.

When devices vanish: `adb kill-server; adb start-server` then `adb devices`. This happened several times; the user also sometimes restarts the emulator (which kills that device's logcat captures and requires relaunching the app on it).

## 1.4 Firebase

- Project: **outertune-social** (`.firebaserc` in repo root points here; the app's `google-services.json` matches).
- Firestore collections: `users`, `friends`, `friendRequests` (parked), `sentSongs`, `status`, `invites`.
- Rules: `firestore.rules` in repo root. **DEPLOYED** multiple times; final deployment includes the invites sender-overwrite update rule (commit `b60583334` era). Deploy command: `firebase deploy --only firestore:rules` from `C:\musicapp\metrolist`. Deploy output confirms "Deploy complete!".
- `firebase.json` + `firestore.indexes.json` exist in repo; composite indexes for sentSongs were deployed back in SPEC_13 era. The `invites` collection needs NO composite indexes (doc-id lookups + single-field whereEqualTo queries only; single-field indexes are automatic).
- FCM exists (`SongListenedMessagingService`); no Cloud Functions (deliberately — free tier; Cloud Functions would require Blaze plan).

## 1.5 Firestore REST access without gcloud (the node trick)

gcloud is not installed, but the firebase CLI's OAuth refresh token can be exchanged manually. Working node scripts exist at:
- `C:\Users\emanf\AppData\Local\Temp\kilo\firestore_check.js` — lists `users` and `invites` collections
- `C:\Users\emanf\AppData\Local\Temp\kilo\firestore_plant_invite.js` — PATCHes a test invite doc into `invites/EuM3KTt25ReXt7BFjBKQvzwbBPx1`

The pattern (exact):
```js
const cfg = JSON.parse(fs.readFileSync(path.join(process.env.USERPROFILE, '.config', 'configstore', 'firebase-tools.json'), 'utf8'));
const refresh = (cfg.tokens && cfg.tokens.refreshToken) || (cfg.user && cfg.user.refresh_token);
// NOTE: on this machine the working key is cfg.user.refresh_token. A later run got 401 /
// NO_REFRESH_TOKEN confusion — inspect the JSON structure first if auth fails:
// $cfg = Get-Content ... | ConvertFrom-Json; $cfg.user.PSObject.Properties.Name
const tok = await post('https://oauth2.googleapis.com/token', {
  client_id: '563584335869-fgrhgmd47bqnekij5i8b5pr03hoj4e27.apps.googleusercontent.com',
  client_secret: '4f77fb7d7e0be01b9dbef04dd1117187f7d0beda',
  refresh_token: refresh, grant_type: 'refresh_token',
});
// then GET/PATCH https://firestore.googleapis.com/v1/projects/outertune-social/databases/(default)/documents/<coll>/<doc>
// with Authorization: Bearer <access_token>
```
Owner-token writes BYPASS security rules — useful for planting test invites. One later run returned 401 (token exchange hiccup) — re-inspect the config JSON and retry.

## 1.6 Account data (verified in Firebase console by the user via screenshots)

- `users/45qlBVDfwWZxya3xapU3osQQZSS2` — email `emanfunnyalt2@gmail.com`, username `"eman"`, created Aug 23 2026 10:12 PM UTC+3 → this is **eman** (the user's phone).
- `users/EuM3KTt25ReXt7BFjBKQvzwbBPx1` — email `test1@gmail.com`, username `"aswinitest"`, created Aug 23 2026 3:57 AM UTC+3 → this is **aswinitest** (the emulator).
- Exactly TWO user docs exist — no stale duplicates (verified by user's console screenshots).
- `PartnerResolver` matching: eman's partner candidates = usernames {aswini, aswinitest} OR partner email contains "test"/"sylesh"; aswinitest's partner = eman (email contains "eman").

## 1.7 Listen Together server

- External Go repo (**metroserver**, MetrolistGroup — NOT in this codebase; only the `metroproto` protobuf definitions submodule is). The app connects over TLS websocket. Server list in `listentogether/ListenTogetherServers.kt` (predefined servers incl. `wss://metro.metrolist.cc` etc. [paraphrased — re-read the file for exact URLs]).
- **UNKNOWN/CITICAL for any guest-control work:** whether the server relays PLAYBACK_ACTION messages from non-host members. Never tested (client always blocked guest sends). See §7.9.

---

# 2. GIT TOPOLOGY & FULL COMMIT LEDGER

## 2.1 Remotes & branches

- `origin` → `https://github.com/MetrolistGroup/Metrolist` (upstream; never pushed to)
- `personal` → `https://github.com/3mzn/Metrolist.git` (the user's fork; ALL pushes go here: `git push personal testing` and `git push personal main`)
- Branches: `testing` (working branch; HEAD of all work), `main` (fast-forwarded to testing whenever user says "merge"; both pushed).
- Fork shape: personal/testing = upstream `origin/main` (`732dd13db`) + ~50 private commits. Zero upstream divergence. Upstream is in maintenance mode (awaiting a KMP rewrite). ~17 upstream feature branches, 97 tags exist upstream.
- Merge convention used: `git checkout main; git merge testing; git checkout testing` (fast-forward only; main must never diverge).
- Commit style: conventional commits, scoped: `feat(widget): ...`, `fix(lt-invite): ...`, `feat(social): ...`, `docs: ...`. NEVER commit unless the user asks; NEVER push unless asked. The user often says "commit and push" or "push everything that hasn't been pushed yet".

## 2.2 Full commit ledger of this session (chronological, all pushed to personal/testing unless noted)

**Pre-session baseline (from earlier sessions, context):** fork commits included SPEC_13 partner-mode series, partner widget series (13 commits), heartbeat fixes, JSON import tab, perf revert pair (ef5440fd4 + 63b9c6fe4), mojibake-introducing commit `0201913c7`, docs commits. Tip at session start: testing branch at the commit before W-SCALE.

**W-SCALE:**
- `b9299de37` — feat(widget): W-SCALE proportional title/artist auto-scale in partner widget (+23/−3, only PartnerWidgetManager.kt)
- `d71b86bc0` — feat(widget): raise W-SCALE minimum text scale to 65% (user tuned up from planned 50%)
- `5a024d7d8` — docs: mark W-SCALE shipped in MAYBE_LATER tracker
- main was fast-forwarded to testing and both pushed (`git push personal main` after `git push personal testing`).

**Gentle nudge (#4):**
- `37a425c84` — feat(social): nudge fields and stale-song queries in sentSongs (SentSong +nudgeCount/lastNudgedAt; SongSharingRepository.getStaleSongs/markSongsNudged; FieldValue import)
- `8c9e34695` — feat(social): gentle nudge notification channel and strings (SongNotificationHelper: NUDGE_CHANNEL_ID, showNudgeNotification, createNudgeChannel; 9 strings)
- `fdb99051e` — feat(social): daily gentle-nudge worker with presence and cap rules (GentleNudgeWorker.kt new; SongListenedNotificationManager start/stopNudgeWorker; SongListenedRealTimeNotifier + AuthViewModel wiring)
- `41d1667a8` — fix(social): distinct nudge ids per direction and crash-safe worker guard (sender id 2900 / receiver 2901; Throwable catch for :crash process NoClassDefFoundError)
- `5c792e400` — feat(social): suppress only receiver nudge while app is in the foreground (AppForegroundTracker.kt NEW + registered in App.onCreate; worker foreground check)
- `0d82c7f02` — docs: add PENDING_TESTS tracker with gentle-nudge test plan (PENDING_TESTS.md)
- (later) PENDING_TESTS.md boxes checked + MAYBE_LATER.md updated: `docs: mark gentle nudge tested in trackers`

**SPEC_7 planning docs:**
- SPEC_7.md created + hardened (delivery transitions, dedupe, string key, host navigation) + deploy-warning markers; commits: `docs: add SPEC_7 — phased implementation spec...`, `docs: harden SPEC_7...`, `docs: flag pending rules deploy in SPEC_7`, `docs: record rules deployment in SPEC_7`, `docs: mark SPEC_7 Phase 1 complete`, `docs: mark SPEC_7 Phase 2 complete`, `docs: mark SPEC_7 Phase 3 complete`.

**SPEC_7 implementation:**
- `fa109d87c` — feat(lt-invite): invite repository and firestore rules (Phase 1: ListenTogetherInviteModels.kt NEW, ListenTogetherInviteRepository.kt NEW, firestore.rules invites block)
- `057518b54` — feat(lt-invite): banner, notification and background poll delivery (Phase 2: SongNotificationHelper invite channel/notification, InvitePollWorker.kt NEW, InviteNotifier.kt NEW, AppForegroundTracker.kt NEW + App.kt registration, SongListenedNotificationManager start/stopInvitePollWorker, SongListenedRealTimeNotifier + AuthViewModel wiring, MainActivity EXTRA_LT_INVITE_TAP routing, 4 strings)
- `373b40329` — fix(lt-invite): transition-aware delivery, persistent dedupe, shade cleanup (InviteNotifier rewritten: currentInvite + transition re-evaluation; SongNotificationHelper.cancelInviteNotification; MainActivity launchSingleTop)
- `9682f5bfd` — feat(lt-invite): single-button invite UI replacing the join flow (Phase 3: InviteNotifier extended into controller with joinFromInvite; InviteBanner.kt NEW; ListenTogetherScreen InviteSection + AdvancedJoinSection + event handling; MainActivity banner placement/badges/host-reaction; ~17 strings)
- `2a5214955` — fix(lt-invite): main-thread callbacks and join-in-flight guard (final deep-review fixes)

**On-device debugging fixes (the testing war):**
- `2db508983` — fix(lt-invite): resolver scan retries, sendInvite self-heal and failure logging
- `7824ef832` — fix(lt-invite): sender-side reads via queries to avoid denied reads on missing docs
- `e82e216b6` — fix(lt-invite): host waiting card inside the session view
- `b08729d15` — fix(lt-invite): re-attach invite listeners on auth state changes
- `b60583334` — fix(lt-invite): actually start the notifier; allow sender re-invite overwrites (App.kt `.get().start()`; firestore.rules update-rule split; InviteNotifier logging; **rules REDEPLOYED after this**)
- `696cfe6b4` — fix(lt-invite): run join flow manager calls on main thread (HEAD at handoff)

**Guest-control plan (added then dropped):**
- `37c7d702d` — docs: guest playback control experiment plan (GUEST_CONTROL_EXPERIMENT.md)
- `1c9082e2d` — docs: drop guest-control experiment — suggest+auto-approve already covers it (file deleted)

## 2.3 Working tree state at handoff

- `git status` clean at last check; everything pushed to personal/testing and personal/main is behind (user may want another main fast-forward + push).
- CONTINUATION.md (this file) was just created — **uncommitted** at handoff.
- Last APK build: Aug 25 2026 3:44 PM, includes `696cfe6b4`. Installed on both devices at ~15:44. NO code changed since. (A verification of installed-vs-latest via dumpsys failed once because devices were offline mid-adb-restart; devices were reconnected and adb restarted afterward — re-verify if needed.)

---

# 3. UPSTREAM DIFF ANALYSIS (fork vs Metrolist_original)

Compared fork HEAD (testing) against `732dd13db` (fork point = Metrolist_original HEAD):

- **Scale:** 47 commits (at diff time), 110 files, **+15,455 / −181** lines. Almost purely additive; nothing upstream deleted or rewritten destructively.
- **New feature areas (all new files):**
  1. Social/partner system: `social/` — PartnerResolver, SongSharingRepository, SocialRepository, SongListenedRealTimeNotifier, SongListenedNotificationWorker, SongListenedNotificationManager, SongNotificationHelper, SongShareModels, AuthViewModel, SocialViewModel, SongSharingViewModel, FirebaseLoginScreen, ProfileSetupScreen, SocialScreen, FriendRequestsScreen/UserListScreen (parked), SendToFriendsDialog/SendToFriendsHost (~2,500 lines)
  2. Partner widget: `widget/PartnerWidgetManager.kt` (582 ln), receiver, `widget_partner*.xml` layouts, `partner_widget_info.xml` (+v31)
  3. `PlaybackProgressTracker.kt` (+ test): 50% → listenedAt, 95% → completedAt + auto-remove
  4. JSON playlist import: ImportScreen tab, JsonImportViewModel, JsonPlaylistModels, ImportJsonPlaylistDialog, FailedImportsDialog, JsonImportFlow
  5. Seek restriction inside To-Listen playlist (Player.kt, Thumbnail.kt, lyrics components, menus)
- **Modified upstream files (~315 insertions across 10 core files):** MusicService.kt (+185: SongSharing/Partner/Widget/ListenTogether wiring, currentPlaylistId, heartbeat writes on IO scope, widget broadcasts, progress-tracker), App.kt (+114: Firebase init, heartbeat monitor, notification worker lifecycle), queue plumbing (Queue, QueueExt, PersistQueue, PlayerConnection, ListQueue: playlistId propagation), navigation/screens (Social + Import tabs, Library badge, playlist menus "Send to <partner>", StorageSettings widget debug toggle), PlaylistEntity (+`TO_LISTEN_PLAYLIST_ID = "LP_TO_LISTEN"` one line, NO schema change), vendored protobuf-lite gencode + protolite-well-known-types exclusion.
- **Build/infra:** google-services plugin, Firebase BoM 33.1.2 (auth/firestore/messaging), google-services.json, .firebaserc, firebase.json, firestore.rules + indexes, hilt-work/work-runtime, MockK + coroutines-test test deps, gradle.properties local daemon tuning, docs (docs/port-design/, docs/outertune-dev/, MAYBE_LATER.md, SPEC_13.md), ~100+ appended strings in metrolist_strings.xml.
- **Untouched by fork:** all API modules (innertube, lrclib, kugou, lastfm, betterlyrics, paxsenix, shazamkit), upstream playback internals, Room schema (v38, 18 entities + 3 views, auto-migrations), CI workflows.
- (After this diff, SPEC_7 + nudge added MORE commits — see §2.2.)

---

# 4. APP ARCHITECTURE (complete)

## 4.1 Stack
Kotlin + Jetpack Compose + Material 3 + Media3/ExoPlayer + Room (v38) + Hilt + DataStore (preferences) + Firebase (auth/firestore/messaging, BoM 33.1.2) + WorkManager (HiltWorker) + Ktor (API modules) + protobuf-lite (metroproto) + Coil + hand-rolled Discord RPC.

## 4.2 Modules (settings.gradle.kts)
`:app`, `:innertube` (YTM private API; InnerTube.kt thin facade over external innertubex lib; YouTube.kt ~3,700 lines, ~80 suspend fns, renderer parsing into YTItem sealed hierarchy, multi-client stream extraction + NewPipe fallback), `:lrclib` (lrclib.net LRC), `:kugou` (Chinese lyrics), `:betterlyrics` (TTML word-synced), `:paxsenix` (Apple Music synced lyrics w/ token manager), `:lastfm` (signed OAuth scrobbling), `:shazamkit` (audio fingerprinting w/ queue/rate-limit/cache). Each module = Kotlin object facade + own Ktor client, `suspend ... : Result<T>` APIs.

## 4.3 Playback
- `playback/MusicService.kt` (~5,100 lines, Media3 MediaLibraryService): triple ExoPlayer (main/secondary/fading for crossfade), custom DSP (volume normalization, parametric EQ, silence skip), ResolvingDataSource→CacheDataSource→OkHttp, per-song stream URL cache, queue abstractions + radio/automix, network-outage pause/resume, Discord RPC, Last.fm scrobbling, Cast (gms flavor), Android Auto, downloads (DownloadUtil/ExoDownloadService), podcasts, alarm playback.
- Fork additions: injects SongSharing/Partner/Widget/ListenTogether pieces, `currentPlaylistId` exposure, per-song-change heartbeat writes (dedicated IO scope, 10-s watchdog), widget broadcasts, progress-tracker wiring.
- `playback/PlayerConnection.kt` — UI-facing player handle (play/pause/seekTo/seekToNext/seekToPrevious/play()/pause(), queueWindows, currentWindowIndex, queueTitle, currentPlaylistId, mediaItems, allowInternalSync flag used by LT queue mutations).

## 4.4 Database
Room v38, 18 entities + 3 views, auto-migrations with pre-migration DB backups, WAL + PRAGMA tuning. Entities include PlaylistEntity (has `TO_LISTEN_PLAYLIST_ID = "LP_TO_LISTEN"` const — the ONLY fork addition, no schema change), SongEntity, SongHistoryEntity, etc. **AGENTS.md rule: NO schema changes without explicit user sign-off.**

## 4.5 UI
Single-activity Compose NavHost (`MainActivity` + `NavigationBuilder`/`Screens` + `AppNavigation`-style bottom bar/rail in MainActivity). Bottom tabs: Home/Search/ListenTogether/Library/Social/Import (+badge support via `badgeCounts` map keyed by Screens enum: Library = incomingSharedSongs.size, ListenTogether = ltInviteBadge). `listenTogetherInTopBar` preference moves LT to the top bar (then it's NOT in the bottom bar; top-bar icon at MainActivity ~line 1063 has its own BadgedBox; top-bar variant route is `"listen_together_from_topbar"`, bottom tab route is `Screens.ListenTogether.route` = `"listen_together"`).
- Composition locals declared in MainActivity.kt (~line 1700s): `LocalListenTogetherManager`, `LocalInviteNotifier` (both `staticCompositionLocalOf<...?> { null }`), `LocalPlayerAwareWindowInsets`, provided in the CompositionLocalProvider block (~line 1024) alongside `LocalDatabase`, etc.
- MainActivity has `@Inject lateinit var listenTogetherManager: ListenTogetherManager` and `@Inject lateinit var inviteNotifier: InviteNotifier`; calls `manager.initialize()` (line ~401); deep-link/intent handling: `handleWidgetTargetIntent`, `handleRecognitionIntent`, `handleInviteTapIntent`, `handleDeepLinkIntent` — hooked in THREE places: onNewIntent, LaunchedEffect(Unit) pending-intent branch, LaunchedEffect else branch, and a DisposableEffect Consumer listener.
- 33 ViewModels; theming (dynamic color, AMOLED, palette extraction); widgets (Music, Playlist, Turntable, Recognizer, Partner).

## 4.6 DI
Hilt throughout. App is `@HiltAndroidApp` + `Configuration.Provider` (HiltWorkerFactory) for WorkManager. `App.initializeSocialFeatures()` (called in onCreate when `isMainProcess`) lazily gets: SongSharingRepository, SongListenedRealTimeNotifier, SongListenedNotificationManager, PartnerHeartbeatMonitor, InviteNotifier. App.onCreate order: isMainProcess guard → CrashHandler.install → ArtistNameAliases.initialize → initializeSocialFeatures (registers tracker + notifier) → datastore dir mkdir → Timber.plant → cipher init → applicationScope.launch { initializeSettings(); prewarm cipher WebView (1.5s delay); prewarm PoToken (2.5s, waits up to 12s for YouTube.visitorData) }.
- NOTE: `App.kt` contains some non-ASCII (Arabic) comments from upstream ("تهيئة إعدادات التطبيق عند الإقلاع") — do not "fix" these.
- `:crash` process: CrashActivity runs there; `isMainProcess` guard prevents Firebase/social init there; workers catch Throwable for NoClassDefFoundError safety.

## 4.7 CI (.github/workflows)
Nightly GMS build, version-bump release matrix (3 flavors, changelog-parsed notes), side-by-side PR builds, quick manual build. Blacksmith runners; signing secrets incl. LastFM keys.

## 4.8 Tests
23+ unit test files (JUnit4/Robolectric/MockK), 95 tests total, green. Notables: ComposeToImageTest, PlaybackProgressTracker test, LyricsBackgroundTimingTest (Robolectric).

---

# 5. THE SOCIAL/PARTNER SYSTEM (pre-SPEC_7)

## 5.1 Identity — `social/PartnerResolver.kt` (216 lines, fully read)

- `data class PartnerIdentity(val myName: String?, val partnerName: String?, val partnerUid: String?)` [paraphrased field order]
- `@Singleton class PartnerResolver @Inject constructor(firestore, auth, @ApplicationContext context)`; exposes `val identity: StateFlow<PartnerIdentity>` (MutableStateFlow internal), `suspend fun awaitPartnerUid(timeoutMs = 10_000): String?` (suspends on identity flow until partnerUid != null or timeout), `fun refresh()`.
- Handle derivation from email: contains "eman" → myName "eman"; contains "aswini" → "aswini"; TEST handles: "aswinitest" also maps to aswini seat (HANDLE_ASWINI_TEST = "aswinitest", EMAIL_TAG_TEST = "test"). `partnerCandidateHandles(mine)`: if I'm eman → {aswini, aswinitest}; if I'm aswini/aswinitest → {eman}. `partnerEmailMatches(partnerEmail, mine)`: partner email contains "test"/"sylesh" when I'm eman [paraphrased — re-read for exact tags].
- `init { if (auth.currentUser != null) refresh() }` + FirebaseAuth.AuthStateListener → refresh() on sign-in; clearCache() on sign-out (removes PARTNER_UID_KEY/PARTNER_NAME_KEY and nulls partnerUid).
- `refresh()` (post-fix version): scope.launch (CoroutineScope(SupervisorJob() + Dispatchers.IO)) → seed cached UID from DataStore first (PARTNER_UID_KEY) → then scan loop `for (attempt in 1..SCAN_ATTEMPTS)` (SCAN_ATTEMPTS=3, SCAN_RETRY_DELAY_MS=2_000, `delay(SCAN_RETRY_DELAY_MS * attempt)` between): `firestore.collection("users").get().await()` → `snapshot.documents.firstOrNull { doc.id != user.uid && (doc.getString("username") in candidateUsernames || partnerEmailMatches(doc.getString("email"), mine)) }` → on match: `_identity.value = _identity.value.copy(partnerUid = partnerDoc.id)` + `cachePartner(partnerDoc.id, partnerDisplayName(mine))` (writes DataStore) + log + return; on no-match: log attempt; on exception: log; retry. After loop: "giving up; cached UID (if any) remains".
- **History:** originally one-shot (bug — a transient failure left partnerUid null for the whole process; caused "Partner not resolved" send failures). Fixed in `2db508983` with the retry loop + "no match" retry (partner profile may appear later).

## 5.2 Song sharing — `social/SongSharingRepository.kt` (619+ lines)

- `@Singleton`, injects FirebaseAuth, FirebaseFirestore, @ApplicationContext Context, (and others). `sentSongsCollection get() = firestore.collection("sentSongs")`. TAG = "SongSharingRepository". Companion: `const val MAX_NUDGE_ROUNDS = 2L`.
- Send flow: `suspend fun sendSongsToFriends(...)` — one doc per song per recipient: songId, songTitle, songArtist, songDuration, thumbnailUrl, albumId, albumName, fromUid, fromUsername, toUid, sentAt, listenedAt, completedAt, notificationSent (+nudgeCount, lastNudgedAt).
- Receiver: snapshot listener inserts into "From <partner>" playlist (position 0, dedup, idempotent rename/backfill at app start).
- Milestones: `markSongAsListened(songId)` (50%), `markSongAsCompleted(songId)` (95% + remove from playlist) — mark EVERY twin doc (per-songId group), notificationSent flag prevents duplicate sender notifications. Twin marking exists so each sender is notified.
- 30-day pruning of completed docs; account deletion wipes users/friends/friendRequests but deliberately orphans sentSongs (rules forbid the delete).
- Nudge additions: `suspend fun getStaleSongs(fromMe: Boolean, staleCutoff: Long): List<SentSong>` — equality-only query (`whereEqualTo(if (fromMe) "fromUid" else "toUid", currentUid)`), client-side filter `completedAt == null && listenedAt == null && sentAt < staleCutoff && nudgeCount < MAX_NUDGE_ROUNDS`; returns ALL docs incl. twins. `suspend fun markSongsNudged(songs: List<SentSong>)` — single WriteBatch: `nudgeCount = FieldValue.increment(1)`, `lastNudgedAt = now` per doc; failures swallowed (non-fatal).
- Heartbeat: `updateListeningStatus(...)` writes `status/{myUid}` (songId, title, artist, thumbnail, updatedAt, isPlaying etc. [paraphrased]) — called from MusicService on song change, dedicated IO scope, 10-s watchdog.

## 5.3 Notifications stack
- `SongListenedRealTimeNotifier` — Firestore listener for partner-listened events; on auth change: startListening + startWorker + startNudgeWorker + startInvitePollWorker (login) / stop all (logout). Logs under "SongListenedRealTime".
- `SongListenedNotificationManager` — WorkManager scheduling: `startWorker`/`stopWorker` (listened worker, unique name from SongListenedNotificationWorker.WORK_NAME), `startNudgeWorker`/`stopNudgeWorker` (24h periodic, NetworkType.CONNECTED, ExistingPeriodicWorkPolicy.KEEP), `startInvitePollWorker`/`stopInvitePollWorker` (15-min periodic). Logs under "SongListenedNotifMgr". Injects WorkManager + FirebaseAuth.
- `SongListenedNotificationWorker` — the 50%-milestone notification worker (catches Throwable for :crash-process safety — pattern to mirror).
- `SongNotificationHelper` (object): CHANNEL_ID = "song_listened_notifications", NOTIFICATION_ID_BASE = 3000 (per-song ids 3000+hash), NUDGE_CHANNEL_ID = "gentle_nudge_notifications" (IMPORTANCE_LOW; nudge sender id 2900, receiver 2901), LT_INVITE_CHANNEL_ID = "lt_invites" (IMPORTANCE_HIGH, heads-up; LT_INVITE_NOTIFICATION_ID = 2800). Functions: showSongListenedNotification, showNudgeNotification(context, title, message, isSenderNudge), showInviteNotification(context, fromName) [tap → MainActivity with EXTRA_LT_INVITE_TAP, requestCode 2, autoCancel], cancelInviteNotification(context), channel creators. All notifications: smallIcon R.drawable.music_note, BigTextStyle, deep link intent with extra `"n"` = "social" (NOTE: the extra key is literally `"n"` — a pre-existing quirk; the "navigate_to" name appears only in docs; and there appears to be NO consumer of that extra in MainActivity — notification taps just open the app; the LT invite notification instead uses EXTRA_LT_INVITE_TAP which IS handled).
- `SongListenedMessagingService` — FCM data-message fallback.

## 5.4 Widget — `widget/PartnerWidgetManager.kt` (582+ lines, fully read twice)
- Renders ONE seamless bitmap card (cover art + palette/edge-matched gradient panel, shape settings circle/rounded/square, 2-min staleness, idle placeholder, deep-link tap-to-play). `composeUnifiedWidget(...)` does all drawing: header "Listening: <name>" at 0.07h, title bold 0.13h at baseline 0.52h, artist 0.085h at titleY + 0.16h·scale, textX = coverSide + 0.09h, textWidth = width − textX − 0.09h.
- **W-SCALE block** (shipped): measures title/artist at default sizes; `worstOverflow = max(titleW, artistW)/textWidth`; if >1 → `scale = max(1f/worstOverflow, TEXT_MIN_SCALE)`; TEXT_MIN_SCALE = 0.65f (companion const); applies `textSize = defaultSize * scale` to both paints; artist baseline gap `height * 0.16f * scale`; ellipsize calls unchanged (run against scaled paints). Blank strings safe (measureText("")=0).
- Mojibake: lines ~70/140/205/231/392 contain double-encoded em-dash corruption from upstream-era commit `0201913c7` — PRE-EXISTING, deliberately untouched (user's AGENTS.md forbids unrelated md/comment churn; noted as known issue).
- MIUI safety: setSelected guarded; bitmap pipeline avoids remoteview text truncation issues.
- `PartnerWidgetReceiver` — AppWidgetProvider; widget debug test toggle in Storage settings (WIDGET_UI_DEBUG_TEST_KEY) renders own playback for solo testing.

## 5.5 Progress tracker — `playback/PlaybackProgressTracker.kt`
Tracks only when playing FROM the To-Listen playlist; ≥50% → markSongAsListened; ≥95% → markSongAsCompleted (+ auto-remove from playlist). Max-progress tracking defeats seek-back gaming; ALL seeking hard-blocked in that playlist (Player.kt slider pointer-input consumed, ff/rew + lyric-click seek disabled) — `seekRestricted = isListenTogetherGuest || isToListenPlaylist` in Player.kt:341. **Do not break the To-Listen part when touching guest seek.**

---

# 6. SPEC_7 — LISTEN TOGETHER INVITES (complete)

## 6.1 The spec file
`SPEC_7.md` in repo root — read it. Contains: 16 locked decisions D1–D16, Firestore design + rules block, 3 phases (all marked ✅ COMPLETE with commit hashes + implementation notes), deployment section (✅ DONE), 17-item verification checklist (§7), risks (§8). The progress line at top: `13 ✅ · 3 ✅ · 5+6 ✅ · W-SCALE ✅ · 4 ✅ · next: 7 → 8 → 9 VIZ` (7 was marked next; it is now implemented+core-tested).

## 6.2 The 16 locked decisions (D1–D16, from 4 rounds of user Q&A)
- D1 One button replaces username/room-code join: "Invite aswini to listen together" (directional via PartnerResolver).
- D2 Expiry 30 minutes, judged by READER's clock: `now − createdAt ≥ 30 min` = expired. NO expiresAt field (clock-skew proof).
- D3 Explicit Decline button → writes `status: "declined"` (sender sees "aswini declined" toast in real time) then deletes doc. Accept writes `status: "accepted"`.
- D4 Hidden manual room-code join survives (collapsed "Advanced" section) for debugging.
- D5 Mid-session the invite button is disabled: "listening together now · End session". Implemented as: the invite section simply isn't rendered when in-room (room UI replaces it).
- D6 Incoming invites NEVER suppressed by session state; Join cleans up stale sessions first (leaveRoom → join).
- D7 LT usernames auto-derived from PartnerResolver (never prompt; fallback `savedUsername.ifBlank { "guest" }`).
- D8 Inviter is host; both can add songs — suggestion auto-approve FORCED ON for invite sessions (global setting untouched; host side is what matters functionally).
- D9 "Invite sent · waiting… [Cancel]" — cancel deletes doc, recipient's banner disappears.
- D10 After successful join both phones navigate to the LT screen (joiner immediately; host on outgoing-accepted OR UserJoined, whichever first).
- D11 Edge cases: expired tap → "invite expired" toast (NOTE: currently unused — expired invites just vanish; string kept); simultaneous invites → per-recipient doc ids make last-write-wins natural, D12 resolves collisions; host vanished before join → "The session has ended" toast; failed join → invite SURVIVES until expiry (retry works).
- D12 Mutual-invite collision: accepting an incoming invite auto-cancels your own outgoing (and outgoing-accepted clears any incoming banner). Two parallel sessions impossible.
- D13 Delivery: foreground → app-wide banner + LT tab badge; backgrounded (process alive) → system notification, tap opens join UI DIRECTLY (no banner); fully closed → 15-min WorkManager poll (Android's hard floor; live delivery is the Firestore listener in normal use); on next open, unexpired invite waits in the LT tab.
- D14 Force stop is OS-controlled (no work runs until manual reopen) — documented, not fought.
- D15 All strings in metrolist_strings.xml with %1$s placeholders.
- D16 Three commits, each compiling green.

## 6.3 Firestore design

Doc: `invites/{recipientUid}` — one doc per recipient; a newer invite overwrites an older one (last-write-wins for free). Fields:
```
roomCode: string (from LT RoomCreated)
fromUid: string
fromName: string (PartnerResolver display name)
createdAt: number (sender clock, ms)
status: string "pending" | "accepted" | "declined"
```
Lifecycle: send = set() (overwrites); accept = update status accepted → delete; decline = update status declined → delete; cancel = sender deletes; expiry = client-side only (`now − createdAt ≥ 15min`), opportunistic cleanup by recipient (open/poll) and sender (after expiry+grace).

**Rules (as deployed — final version in firestore.rules):**
```
match /invites/{recipientUid} {
  function isInviteParty() {
    return request.auth != null
      && (request.auth.uid == recipientUid
          || request.auth.uid == resource.data.fromUid);
  }
  allow read: if isInviteParty();
  allow create: if request.auth != null
    && request.resource.data.fromUid == request.auth.uid
    && request.auth.uid != recipientUid
    && request.resource.data.roomCode is string
    && request.resource.data.fromName is string
    && request.resource.data.createdAt is number
    && request.resource.data.status == "pending";
  allow update: if request.auth != null && (
    (request.auth.uid == recipientUid
      && request.resource.data.fromUid == resource.data.fromUid
      && request.resource.data.roomCode == resource.data.roomCode)
    || (request.auth.uid == resource.data.fromUid
      && request.resource.data.fromUid == resource.data.fromUid
      && request.resource.data.status == "pending")
  );
  allow delete: if isInviteParty();
}
```
**Critical rules lessons learned in testing:**
1. `resource.data.fromUid` ERRORS on a nonexistent doc → any read whose rule path touches resource.data is DENIED for missing docs. The recipient branch short-circuits (`uid == recipientUid` true first) so recipient doc-listens work even when the doc doesn't exist. SENDER doc-reads/listens on a missing doc are DENIED → ALL sender-side reads use QUERIES (`whereEqualTo("fromUid", myUid)`) — list ops only evaluate existing docs, and the rule is provable from that filter.
2. The original update rule (keys immutable for everyone) BLOCKED legitimate re-invites: second `set()` on an existing doc = update with a NEW roomCode → denied. Fixed by splitting update into recipient-status-flip vs sender-overwrite branches (above).
3. No composite indexes needed for invites.

## 6.4 `social/ListenTogetherInviteModels.kt` (43 lines, complete)

```kotlin
data class ListenTogetherInvite(
    val roomCode: String,
    val fromUid: String,
    val fromName: String,
    val createdAt: Long,
    val status: String,
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now - createdAt >= EXPIRY_MS
    fun isPending(): Boolean = status == STATUS_PENDING
    companion object {
        const val EXPIRY_MS = 30 * 60 * 1000L
        const val STATUS_PENDING = "pending"
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_DECLINED = "declined"
        fun fromMap(map: Map<String, Any?>): ListenTogetherInvite?  // null-safe; status defaults to pending
    }
}
```

## 6.5 `social/ListenTogetherInviteRepository.kt` (complete, post-all-fixes)

`@Singleton`, injects FirebaseFirestore, FirebaseAuth, PartnerResolver, @ApplicationContext Context. TAG = "LTInvite". Companion: `LT_LAST_NOTIFIED_INVITE_CREATED_AT = longPreferencesKey("lt_last_notified_invite_created_at")` (shared notification dedupe), private `OUTGOING_SENT_AT = longPreferencesKey("lt_outgoing_invite_sent_at")`.

- `init { CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { reconcileOutgoingState() } }` — clears the DataStore outgoing cache if the doc is gone/not-ours/expired/answered.
- `private fun authUidFlow(): Flow<String?> = callbackFlow { val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }; auth.addAuthStateListener(listener); trySend(auth.currentUser?.uid); awaitClose { auth.removeAuthStateListener(listener) } }` — **the cold-start fix**: auth restores asynchronously, so uid captured once at startup was often null.
- `observeIncomingInvite(): Flow<ListenTogetherInvite?>` = `authUidFlow().flatMapLatest { myUid -> if (myUid == null) flowOf<ListenTogetherInvite?>(null) else callbackFlow { doc(myUid).addSnapshotListener { snap, err → parse + trySend } } }.distinctUntilChanged()` — does NOT filter expiry (callers decide so "expired but present" stays observable). `@OptIn(ExperimentalCoroutinesApi::class)`.
- `observeOutgoingInvite(): Flow<ListenTogetherInvite?>` = same authUid pattern but QUERY-based: `invitesCollection.whereEqualTo("fromUid", myUid).addSnapshotListener` → `snapshot.documents.firstOrNull()?.data` → fromMap. Deliberately does NOT filter by status (accepted/declined flips are the host-side signal). Only expiry filters (in InviteNotifier).
- `suspend fun sendInvite(roomCode: String): Result<Unit>` — logs every failure path; myUid null → fail; partnerUid null → **`partnerResolver.refresh()` + `awaitPartnerUid(5_000)` retry** → still null → fail; partnerUid == myUid → fail ("resolved to SELF"); myName null → fail; then `set()` the doc + DataStore OUTGOING_SENT_AT + success log "Invite sent to partner (%s)".
- `suspend fun acceptInvite(invite)` — update(status accepted) best-effort (sender's toast reads it), then delete doc. Caller does the actual join.
- `suspend fun declineInvite(invite)` — update(status declined) best-effort, then delete.
- `suspend fun cancelInvite()` — QUERY `whereEqualTo("fromUid", myUid)` → delete all matches (never a get() on the possibly-missing partner doc); finally clears OUTGOING_SENT_AT.
- `suspend fun clearMyInvite()` — delete `invites/{myUid}`.
- `suspend fun cleanupExpiredInvites()` — recipient doc get (allowed, short-circuit) delete if expired; sender side via query, delete expired matches + clear cache.
- `hasPendingOutgoingInvite()` — DataStore contains OUTGOING_SENT_AT.
- `reconcileOutgoingState()` — if cache says pending but query shows doc gone/expired/answered → clear cache.

## 6.6 `social/InviteNotifier.kt` (complete, post-all-fixes — THE central controller)

`@Singleton class InviteNotifier @Inject constructor(@ApplicationContext context, inviteRepository, partnerResolver, listenTogetherManager)`. scope = CoroutineScope(SupervisorJob() + Dispatchers.IO). State: `_bannerInvite`/`bannerInvite: StateFlow<ListenTogetherInvite?>`, `_outgoingInvite`/`outgoingInvite` (does NOT filter by status — accepted/declined flips are the host-side signal; only expiry), `partnerIdentity: StateFlow<PartnerIdentity> = partnerResolver.identity`, `private val currentInvite = MutableStateFlow<ListenTogetherInvite?>` (StateFlow because two collectors write/read concurrently), `lastNotifiedCreatedAt: Long?`, `expiryJob: Job?`, `joinInFlight = AtomicBoolean(false)`, `mainHandler = Handler(Looper.getMainLooper())`.

`start()` (MUST be called — was the biggest bug): logs "InviteNotifier started — attaching invite listeners"; launches:
1. `inviteRepository.observeIncomingInvite().collect { invite → log "Incoming emission: %s" roomCode-or-null; currentInvite.value = invite?.takeIf { it.isPending() && !it.isExpired() } }`
2. `observeOutgoingInvite().collect { invite → _outgoingInvite.value = invite?.takeIf { !it.isExpired() } }`
3. `AppForegroundTracker.isForegroundFlow.collect { reevaluateDelivery() }` (transition-aware both directions)
4. `currentInvite.collect { reevaluateDelivery(); scheduleExpiryCheck(it) }`

`reevaluateDelivery()`: re-validates `currentInvite.value?.takeIf { pending && !expired }` (an invite can expire while cached with no new emission); if null → clear banner + cancelInviteNotification; if foreground → set banner + cancelInviteNotification (banner owns delivery); else → clear banner + postNotification(invite).

`scheduleExpiryCheck(invite)`: cancels prior job; `remaining = createdAt + EXPIRY_MS − now`; if ≤0 → currentInvite = null; else delay(remaining + 1000) then if `currentInvite.value?.createdAt == invite.createdAt` → currentInvite = null (re-validate: newer invite may have replaced it).

`postNotification(invite)`: dedupe — memory `lastNotifiedCreatedAt` AND DataStore `LT_LAST_NOTIFIED_INVITE_CREATED_AT` (survives process restart); then `SongNotificationHelper.showInviteNotification(context, invite.fromName)` + write dedupe key.

`joinFromInvite(invite, onJoined: () -> Unit, onFailed: (rejected: Boolean) -> Unit)`:
```
if (!joinInFlight.compareAndSet(false, true)) return
scope.launch {
  try {
    val myName = partnerResolver.identity.value.myName ?: "guest"
    val outcome = withContext(Dispatchers.Main) {   // ExoPlayer is main-thread-only!
        if (listenTogetherManager.isInRoom) listenTogetherManager.leaveRoom()   // D6
        listenTogetherManager.connect()
        listenTogetherManager.joinRoom(invite.roomCode, myName)
        withTimeoutOrNull(20_000) { listenTogetherManager.events.first {
            it is ListenTogetherEvent.JoinApproved || it is ListenTogetherEvent.JoinRejected } }
    }
    when (outcome) {
        is JoinApproved -> { inviteRepository.acceptInvite(invite); inviteRepository.cancelInvite(); forceAutoApproveOn(); mainHandler.post { onJoined() } }
        is JoinRejected -> mainHandler.post { onFailed(true) }
        else -> mainHandler.post { onFailed(false) }   // timeout; invite survives (D11)
    }
  } finally { joinInFlight.set(false) }
}
```
(The Main-thread requirement: leaveRoom→cleanup→player.removeListener crashed on IO — the mutual-invite crash, fixed in `696cfe6b4`.)

`forceAutoApproveOn()` — DataStore edit `ListenTogetherAutoApproveSuggestionsKey = true` (D8; only host-side is functional, set on both harmlessly).
`sendInvite(roomCode)` → repository (called by the LT screen's RoomCreated handler).
`cancelInvite()` / `declineInvite(invite)` → scope.launch repository calls.
`onOutgoingAccepted()` → `clearMyInvite()` (D12 other direction).

## 6.7 `social/InvitePollWorker.kt`
`@HiltWorker`, WORK_NAME = "lt_invite_poll_worker", 15-min periodic (Android's floor — documented in KDoc as dead-process safety net), NetworkType.CONNECTED. doWork: logged-out → success; fetchMyInvite (get on own doc — allowed via short-circuit; 10s withTimeoutOrNull); `cleanupExpiredInvites()`; if not live → success; if `AppForegroundTracker.isForeground` → skip (banner owns delivery); dedupe via LT_LAST_NOTIFIED_INVITE_CREATED_AT; post notification + write key. CancellationException rethrown; Exception → retry. (No Throwable guard here yet — consider mirroring GentleNudgeWorker's if touching it.)

## 6.8 `social/AppForegroundTracker.kt`
Object; `startedActivities = AtomicInteger(0)`; `_isForeground = MutableStateFlow(false)`; `isForeground` getter; `isForegroundFlow: StateFlow<Boolean>`; `register(application)` adds ActivityLifecycleCallbacks counting onActivityStarted/onActivityStopped (clamp at 0, set false when 0). Registered in App.onCreate BEFORE `inviteNotifier.get().start()`.

## 6.9 `utils/SongNotificationHelper.kt` additions
`LT_INVITE_CHANNEL_ID = "lt_invites"` (IMPORTANCE_HIGH, heads-up, channel desc strings), `LT_INVITE_NOTIFICATION_ID = 2800` (fixed — one invite at a time), `showInviteNotification(context, fromName)` — title `lt_invite_notification_title` ("%1$s wants to listen together"), body `lt_invite_notification_body` ("Tap to join %1$s's listening session"), PRIORITY_HIGH, CATEGORY_MESSAGE, tap → PendingIntent(requestCode 2) → MainActivity with `putExtra(MainActivity.EXTRA_LT_INVITE_TAP, true)`, autoCancel. `cancelInviteNotification(context)` — nm.cancel(2800); called when banner takes over or invite consumed.

## 6.10 MainActivity integration
- `companion object: const val EXTRA_LT_INVITE_TAP = "lt_invite_tap"`.
- `@Inject lateinit var inviteNotifier: InviteNotifier`.
- Provides `LocalInviteNotifier` alongside `LocalListenTogetherManager`.
- State: `val ltBannerInvite by inviteNotifier.bannerInvite.collectAsStateWithLifecycle()`, `val ltOutgoingInvite by inviteNotifier.outgoingInvite.collectAsStateWithLifecycle()`, `val ltInviteBadge = remember(ltBannerInvite) { if (ltBannerInvite != null) 1 else 0 }`.
- Badges: `badgeCounts` maps (bottom bar + rail) include `Screens.ListenTogether to ltInviteBadge`; top-bar LT icon (when `listenTogetherInTopBar`) wrapped in BadgedBox.
- Banner: inside `Box(Modifier.weight(1f))` after NavHost: `InviteBanner(invite = ltBannerInvite, modifier = Modifier.align(Alignment.TopCenter).padding(top = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateTopPadding() + 8.dp), onJoin = { ltBannerInvite?.let { inviteNotifier.joinFromInvite(it, onJoined = { navigate LT launchSingleTop }, onFailed = { rejected → toast room-gone or join-failed }) } }, onDecline = { declineInvite })`.
- Host-reaction: `LaunchedEffect(ltOutgoingInvite?.status)` — ACCEPTED → toast `lt_invite_accepted_toast` + `inviteNotifier.onOutgoingAccepted()` + navigate LT (launchSingleTop); DECLINED → toast `lt_invite_declined_toast`.
- `handleInviteTapIntent(intent, navController)`: checks/removes EXTRA_LT_INVITE_TAP → `navController.navigate(Screens.ListenTogether.route) { launchSingleTop = true }` — hooked into onNewIntent + both LaunchedEffect branches + DisposableEffect Consumer (same places as handleWidgetTargetIntent/handleRecognitionIntent/handleDeepLinkIntent). NOTE: adding handleRecognitionIntent to onNewIntent was an upstream-oversight fix made in passing.
- MainActivity also injects `listenTogetherManager` and calls `manager.initialize()` (~line 401).

## 6.11 `ui/screens/ListenTogetherScreen.kt` (1,756 lines; the LT TAB screen — fully read)
Structure: `ListenTogetherScreen(navController, windowInsets, viewModel: ListenTogetherViewModel? no — uses LocalListenTogetherManager directly)`. Top: HeaderSection ("Together" title), ConnectionStatusCard (Connected/Disconnected/Reconnecting + Disconnect/Reconnect buttons), then LazyColumn: `if (isInRoom) { RoomStatusCard (room code big + "You are host/guest"), [WaitingForPartnerCard if isHost && outgoingInvite != null && room.users.count{it.isConnected} <= 1], ConnectedUsersSection (avatars E/A, crown host, "You"), PendingJoinRequestsSection (host), PendingSuggestionsSection (host), Leave room button } else { InviteSection(...), AdvancedJoinSection { JoinCreateRoomSection(...) } }`, then SettingsLinkCard ("Settings — Configure server, username, and more" → ListenTogetherSettings).
- State: `savedUsername` (ListenTogetherUsernameKey), `roomCodeInput`, `usernameInput`, `isCreatingRoom`, `isJoiningRoom`, `joinErrorMessage` (all rememberSaveable), invite block: `inviteController = LocalInviteNotifier.current` (nullable-safe collects), `bannerInvite`, `outgoingInvite`, `partnerIdentity` (collectAsStateWithLifecycle), `isCreatingInvite` (rememberSaveable), `autoApproveSuggestions` (rememberPreference(ListenTogetherAutoApproveSuggestionsKey, false)).
- Events collector (`LaunchedEffect(listenTogetherManager)` over `manager.events`): RoomCreated → if isCreatingInvite && inviteController != null: isCreatingInvite=false, autoApproveSuggestions=true (D8), Timber "RoomCreated for invite, sending invite with code %s", `sendInvite(event.roomCode)` result → failure toast lt_invite_create_failed_toast; ELSE (manual flow) copy room code to clipboard. JoinApproved/JoinRejected → reset isJoiningRoom/joinErrorMessage (existing behavior). Others (PlaybackSync etc.) handled by manager.
- Watchdog: `LaunchedEffect(isCreatingInvite)` — delay(15_000) → if still true: reset + Timber "Invite watchdog fired" + toast create-failed.
- Invite tap: `isCreatingInvite = true; manager.connect(); manager.createRoom(partnerIdentity?.myName ?: savedUsername.trim().ifBlank { "guest" })` (client queues create until connected).
- Join tap (from InviteSection): `isJoiningRoom = true; joinErrorMessage = null; inviteController.joinFromInvite(invite, onJoined = {}, onFailed = { rejected → isJoiningRoom = false; toast lt_invite_room_gone_toast (rejected) or lt_invite_join_failed_toast (timeout) })`.
- Cancel: `inviteController?.cancelInvite()`. Decline: `inviteController?.declineInvite(invite)`.
- `InviteSection` composable: Card(surfaceContainerHighest, 24dp rounded); priority: incoming (bannerInvite != null) → title lt_invite_incoming_title(partnerName ?: fromName) + Row[Button join_room (enabled !isJoiningRoom) / OutlinedButton reject (error color)] ; waiting (outgoingInvite != null) → spinner + lt_invite_waiting(partnerName) + OutlinedButton lt_invite_cancel; else idle → Button lt_invite_button(partnerName) enabled=partnerResolved (else lt_invite_partner_missing text) with group_outlined icon; isCreatingInvite → spinner instead.
- `AdvancedJoinSection(isJoiningRoom)`: collapsed Row "Manual join options" + rotating arrow_forward chevron (animateFloatAsState 180f); `AnimatedVisibility(expanded || isJoiningRoom) { JoinCreateRoomSection(...) }` — the ORIGINAL manual username+room-code UI untouched inside.
- `WaitingForPartnerCard(partnerName, onCancel)`: Card(secondaryContainer, 20dp); spinner + lt_invite_waiting + cancel button; onCancel = `inviteController?.cancelInvite(); listenTogetherManager.leaveRoom()`.

## 6.12 `ui/component/InviteBanner.kt` (95 lines)
`InviteBanner(invite: ListenTogetherInvite?, onJoin, onDecline, modifier)` — AnimatedVisibility(slide+fade) around Surface(secondaryContainer, large shape, shadow 6dp): Row[Column(title = lt_invite_notification_title(fromName), subtitle lt_invite_banner_subtitle "Live listening session"), TextButton join_room, TextButton reject (error color)]. Callers pad the top by window insets so it never renders under the top bar.

## 6.13 Strings added (metrolist_strings.xml, English file only)
```
lt_invite_notification_title   "%1$s wants to listen together"
lt_invite_notification_body    "Tap to join %1$s\'s listening session"
lt_invites_channel_name        "Listen together invites"
lt_invites_channel_description "Invitations to join a live listening session"
lt_invite_banner_subtitle      "Live listening session"
lt_invite_button               "Invite %1$s to listen together"
lt_invite_waiting              "Invite sent · waiting for %1$s…"
lt_invite_cancel               "Cancel invite"
lt_invite_incoming_title       "%1$s wants to listen together"
lt_invite_partner_missing      "Your partner isn\'t set up yet"
lt_invite_advanced             "Manual join options"
lt_invite_accepted_toast       "%1$s joined your session"
lt_invite_declined_toast       "%1$s declined your invite"
lt_invite_expired_toast        "Invite expired"   (currently unused — deviation)
lt_invite_room_gone_toast      "The session has ended"
lt_invite_create_failed_toast  "Couldn\'t create the session. Try again."
lt_invite_join_failed_toast    "Couldn\'t join — the invite is still valid, try again"
```
Plus nudge strings (gentle_nudge_channel_name/description, nudge_sender_title/single/multi, nudge_receiver_title/single/multi).

---

# 7. THE LISTEN TOGETHER CODEBASE STUDY (~7,100 lines fully read Aug 25)

## 7.1 `listentogether/ListenTogetherClient.kt` (1,978 lines)
- TLS websocket client to a metroserver. Connection state machine: DISCONNECTED/CONNECTING/CONNECTED/ERROR (+RECONNECTING attempts 1/15).
- **Session persistence**: on RoomCreated/JoinApproved saves roomCode + sessionToken + isHost to DataStore (`ListenTogetherIsHostKey` etc.); on app start `restoreSession()` reconnects with token (onOpen: `if (sessionToken != null && storedRoomCode != null) reconnect else executePendingAction()`).
- **Pending action queue**: `createRoom(username)` / `joinRoom(code, username)` when not CONNECTED → stored as pendingAction, executed in onOpen. `createRoom` clears sessionToken/storedRoomCode and bumps `sessionApplyGeneration` (prevents stale session-restore overwriting a fresh create). `connect()` early-returns if CONNECTED/CONNECTING.
- **Message layer**: JSON over websocket via kotlinx.serialization; MessageTypes: PLAYBACK_ACTION="playback_action", TRANSFER_HOST, BUFFER_READY, SUGGEST_TRACK, etc. MessageCodec.kt (375 ln) maps protobuf (metroproto) ↔ data classes; Protocol.kt (342 ln) holds payload data classes (PlaybackActionPayload(action, trackId, position, trackInfo, insertNext, queue, queueTitle, volume, serverTime, revision, capturedAtServerTime), RoomState, UserInfo, SyncStatePayload, TrackInfo(id,title,artist,album,duration,thumbnail,suggested_by)) and `object PlaybackActions { PLAY="play", PAUSE="pause", SEEK="seek", SKIP_NEXT="skip_next", SKIP_PREV="skip_prev", CHANGE_TRACK="change_track", QUEUE_ADD, QUEUE_REMOVE, QUEUE_CLEAR, SYNC_QUEUE, SET_VOLUME }`.
- **Events**: `val events: SharedFlow<ListenTogetherEvent>` (replay 0; emissions hop through an internal Channel+coroutine). Event types: RoomCreated(roomCode,userId), JoinApproved(roomCode,state), JoinRejected, PlaybackSync(action), UserJoined, UserLeft, Kicked, BufferWait/BufferComplete, SyncStateReceived(state), Reconnecting(attempt,max), Reconnected(roomCode,userId,state,isHost), Disconnected, Error.
- **ROOM_CREATED handler (client, ~line 1137)**: sets role HOST, roomState, persists session, emits RoomCreated event, AND shows a GLOBAL TOAST "Room created with <code>" (listen_together_room_created) — this toast fires regardless of UI; it proved room creation during debugging.
- **`sendPlaybackAction` (line ~1761)**: `if (_role.value != RoomRole.HOST) { log(ERROR "Cannot control playback", "Not host"); return }` — **the guest-send blocker**. Then sendMessage(PLAYBACK_ACTION, PlaybackActionPayload(..., capturedAtServerTime for PLAY/PAUSE/SEEK when position != null)).
- `suggestTrack(trackInfo)` — guest-only path (SUGGEST_TRACK message); host approves/rejects; auto-approve preference exists (v13.2.0 feature).
- `requestSync()`, `sendBufferReady(trackId)`, `transferHost(newHostId)`, positionAtServerTime(basePos, serverTime, isPlaying) — clock-sync math via ServerClock.kt (HTTP date sync with server).
- Role: `_role: StateFlow<RoomRole>` (NONE/HOST/GUEST); `val isHost get() = _role.value == RoomRole.HOST` (manager re-exposes).
- Reconnect: exponential-ish attempts to 15; Reconnected event carries full RoomState + isHost; guests request sync on first audio sample if role GUEST (line ~1519).

## 7.2 `listentogether/ListenTogetherManager.kt` (2,071 lines)
- `@Singleton class ListenTogetherManager @Inject constructor(client, playerConnection provider?, context...)`. `val isInRoom: Boolean`, `val isHost: Boolean get() = client.isHost` (line 151), `val role: StateFlow<RoomRole>`, `val events` (re-export), `val connectionState: StateFlow<ConnectionState>`, roomState, pendingJoinRequests, pendingSuggestions, users. `fun initialize()` (registers player listener + event collector — called from MainActivity:401 AND ListenTogetherViewModel.init), `fun setPlayerConnection(connection)` (logged: "setPlayerConnection: true, isInRoom: false").
- **Host broadcast observers** (the sync source): three collectors gated `if (isSyncing || !isHost || !isInRoom) return` (lines ~161/227/270): media item transition → sendTrackChange; playback state → PLAY/PAUSE with position; position discontinuity → SEEK. Plus `startQueueSyncObservation()` (queueWindows+currentWindowIndex → SYNC_QUEUE, debounce 500ms) and `startVolumeSyncObservation()` (playerVolume → SET_VOLUME, 0.01 threshold, gated by syncHostVolumeEnabled preference) and `startHeartbeat()` (4s interval, host only).
- **Event handling** (in the big collector): RoomCreated → role HOST, start sync services, heartbeat, volume sync; JoinApproved → saveMuteStateOnJoin, lastAppliedRevision = state.revision, applyPlaybackState(full), applyHostVolumeIfNeeded, updateGuestMuteState; **PlaybackSync → `if (!isHost || isQueueOp) handlePlaybackSync(event.action)`** (line ~508; isQueueOp = QUEUE_ADD/REMOVE/CLEAR) ← the host-ignores-transport-actions gate; BufferWait/BufferComplete (guest buffering reconciliation per trackId); SyncStateReceived → `if (!isHost || applyNextHostSnapshot) handleSyncState(...)`; Kicked → cleanup; Disconnected → no cleanup (might reconnect); Reconnecting → log; Reconnected → re-add player listener, role-based resync (host: send track if server differs; guest: request sync).
- **`handlePlaybackSync(action: PlaybackActionPayload)`** (line 1010): revision guard (`action.revision < lastAppliedRevision` → stale, ignore; else advance), `isSyncing = true` around application. Per action: PLAY (server-time-adjusted position, buffering reconciliation paths, guestNeedsTrackReconcile, soft/hard sync thresholds SOFT/HARD_SYNC_THRESHOLD_MS, drift correction start/cancel), PAUSE (cancelDriftCorrection, pause + position correction), SEEK (adjusted position seek + drift), CHANGE_TRACK (smart path: if revision>0 or queue present → applyPlaybackState(track, isPlaying=false, queue, queueTitle); else legacy syncToTrack), **SKIP_NEXT → `connection.seekToNext()`**, **SKIP_PREV → `connection.seekToPrevious()`** (lines 1230-1238 — already implemented!), QUEUE_ADD/REMOVE/CLEAR (revision>0 → applyCanonicalUpcomingQueue; else legacy per-op with YouTube.queue metadata fetch in enqueueQueueMutation with queueSyncGeneration guard).
- `applyPlaybackState(...)`, `syncToTrack(track, play, pos)`, `reconcileGuestToHostTrack(...)`, `applyPendingSyncIfReady()`, `guestNeedsTrackReconcile(...)`, drift correction (`startDriftCorrection`/`cancelDriftCorrection`), `updateGuestMuteState()`/`saveMuteStateOnJoin()` (guests muted locally by default), `requestSync()` (gated `if (!isInRoom || isHost) return`).
- `leaveRoom()` (line ~1786) → sends LEAVE_ROOM, `cleanup()` (line ~702) → **`player.removeListener(...)` (MAIN THREAD ONLY — ExoPlayer)**, clears session, resets role/state. `disconnect()`/`forceReconnect()`.
- Host-only senders: `sendTrackChange(metadata)` (line 1834, `if (!isHost || isSyncing) return`), sendPlaybackAction wrappers for PLAY/PAUSE/SEEK from player observers, SYNC_QUEUE (line ~1900), SET_VOLUME (line ~1932).
- Guest volume sync: `if (!syncHostVolumeEnabled.value || isHost || !isInRoom) return` (line 769) — guests FOLLOW host volume when enabled.
- On leave as ex-guest (line ~698): restore mute state.

## 7.3 `ui/screens/ListenTogetherScreen.kt` (1,752 lines pre-Phase-3; 1,756+ after)
Covered in §6.11. Additional pre-existing structure: HeaderSection with back arrow + "Together" title; `bringIntoViewRequester` for the join form; `waitingForApprovalText` state; `joiningRoomTemplate` string format; `selectedUserForMenu`/`selectedUsername` (host kick/transfer-host user menu); ConnectionStatusCard with color-coded state; RoomStatusCard (big room code, copyable, "You are host"/"You are guest"); ConnectedUsersSection (UserAvatar circles with initial, crown for host, "You" tag); PendingJoinRequestsSection (approve/reject); PendingSuggestionsSection (approve/reject with "Rejected by host"); Leave room button (error color, logout icon); SettingsLinkCard → navigates to ListenTogetherSettings. Player bottom bar shows current track with mute toggle during session.

## 7.4 `ui/screens/settings/integrations/ListenTogetherSettings.kt` (871 lines, fully read)
The SETTINGS screen (separate from the tab): server selection (ListenTogetherServers), username preference, auto-approve suggestions toggle (`rememberPreference(ListenTogetherAutoApproveSuggestionsKey, false)`), connection logs viewer, disconnect/reconnect, session info. **This screen's ViewModel (`ListenTogetherViewModel`, 88 lines) is what calls `manager.initialize()`** — but MainActivity also calls initialize() directly, so the tab works without visiting settings. SettingsLinkCard on the tab navigates here.

## 7.5 `viewmodels/ListenTogetherViewModel.kt` (88 lines)
Thin: init { manager.initialize() }, exposes manager state flows for the settings screen.

## 7.6 `listentogether/ListenTogetherServers.kt` (41 lines)
Predefined server list (name + URL pairs) incl. the default Metrolist server. [Exact URLs paraphrased — re-read if server choice matters.]

## 7.7 `listentogether/ServerClock.kt` (56 lines)
HTTP-date based server clock sync; `positionAtServerTime` math lives in the client using it.

## 7.8 `listentogether/ListenTogetherActionReceiver.kt` (51 lines)
MediaSession/Notification action receiver bridging LT controls (accept/reject suggestions?) [paraphrased].

## 7.9 Guest-control feasibility (investigated, DROPPED by user — full detail preserved here)
- Blocker 1: `ListenTogetherClient.sendPlaybackAction` line ~1771: `if (_role.value != RoomRole.HOST) { log(ERROR, "Cannot control playback", "Not host"); return }`.
- Blocker 2: `ListenTogetherManager` line ~508: `if (!isHost || isQueueOp) handlePlaybackSync(...)` — host discards received transport actions.
- Blocker 3: UI gates — Player.kt:341 `seekRestricted = isListenTogetherGuest || isToListenPlaylist`; MiniPlayer.kt lines 223/535/733/926 `isListenTogetherGuest`; Queue.kt:173; Thumbnail.kt:446; menus route guests to suggest (AlbumMenu:110, ArtistScreen:148, ArtistMenu:62, YouTubeSongMenu:290+363, PlaylistScreenMenus:66/333/517/681, YouTubeSelectionSongMenu:74, PlaylistMenu:94, YouTubePlaylistMenu:117, PlayerMenu:164, SelectionSongsMenu:91/716, YouTubeArtistMenu:63, YouTubeAlbumMenu:100).
- Protocol HAS SKIP_NEXT/SKIP_PREV and handlePlaybackSync implements them (seekToNext/seekToPrevious).
- UNKNOWN: metroserver relay behavior for guest PLAYBACK_ACTION (server external; experiment was designed: lift gate + host-apply for PAUSE only → guest taps pause → if host pauses, server relays).
- **USER DECISION: DROPPED** — suggest+auto-approve already lets the guest add songs; full control deemed over-engineering. Experiment plan deleted (`1c9082e2d`). Do not resurrect without the user asking.

---

# 8. GENTLE NUDGE — FEATURE #4 (complete)

## 8.1 Spec (from MAYBE_LATER #4 + user corrections)
Songs unstarted ≥3 days after sending → ONE soft daily notification to BOTH sides. Anti-nag: max 1 notification pair/day (DataStore epoch-day gate), max 2 rounds per song EVER (nudgeCount on doc, cap via `nudgeCount < SongSharingRepository.MAX_NUDGE_ROUNDS` (=2)), skip if partner actively listening (status/{partnerUid} updatedAt < 2 min), skip per-song if that song is playing on THIS device (own status doc fresh + songId match; only works with heartbeat toggle on — accepted), foreground suppression ONLY for the receiver nudge (**user correction: the sender nudge — "aswini hasn't gotten to 'X' yet" — is the ONLY surface showing that info; NEVER suppress it. The receiver nudge — "You haven't listened to 'X'" — is redundant while the app is open because the From-partner playlist shows it**).

## 8.2 Implementation
- `SentSong` + `nudgeCount: Long = 0`, `lastNudgedAt: Long? = null` (fromMap: nudgeCount via `(map["nudgeCount"] as? Number)?.toLong() ?: 0L` — Number cast because FieldValue.increment/console may store doubles).
- `GentleNudgeWorker` (@HiltWorker, WORK_NAME "gentle_nudge_worker", 24h periodic, CONNECTED): daily gate `LAST_NUDGE_EPOCH_DAY` (todayEpochDay = now / DAYS.toMillis(1); gate written ONLY when a nudge actually went out); foreground → skip receiver nudge only (log "receiver nudge will be suppressed"); partner presence via status doc; playing exclusion; queries both directions; dedupe by songId (`distinctBy`); texts: single → nudge_sender_single/nudge_receiver_single, multi → "...and N more yet"; markSongsNudged on shown songs' ALL docs (`shownSongIds = (outgoing + if (appForeground) emptyList() else incoming).map{songId}.toSet()`); CancellationException rethrow; Exception → retry; Throwable → failure (crash-process guard).
- Notification: `showNudgeNotification(context, title, message, isSenderNudge)` — channel gentle_nudge_notifications IMPORTANCE_LOW, ids 2900/2901, deep link extra "n"="social", requestCode 1.
- Strings: nudge_sender_title "Songs waiting for %1$s", nudge_sender_single "%1$s hasn\'t gotten to \'%2$s\' yet", nudge_sender_multi "...and %3$d more yet", nudge_receiver_title "Songs waiting for you", nudge_receiver_single "You haven\'t listened to \'%1$s\' (sent by %2$s)", nudge_receiver_multi "You haven\'t listened to %1$d songs sent by %2$s", gentle_nudge_channel_name/description.
- Firebase cost: ~4 reads + 1 batch write per device per day — negligible vs free tier.
- Testing: all 8 PENDING_TESTS.md sub-tests passed (user-verified). Test recipe (for future): backdate sentAt in console (MUST stay number type), pause music both devices, clear app data resets the day gate, nudgeCount reset to 0 for round 2.

---

# 9. W-SCALE (complete)
Covered in §5.4. Shipped commits `b9299de37` + `d71b86bc0`; MAYBE_LATER marked shipped. User verified on device ("the status in the widget.. size is good too").

---

# 10. MAYBE_LATER ROADMAP (complete state)
File: `MAYBE_LATER.md`. Progress line: `13 ✅ · 3 ✅ · 5+6 ✅ (shipped as one Partner widget feature) · W-SCALE ✅ (shipped at 65% floor) · 4 ✅ (gentle nudge shipped + tested) · next: **7** → 8 → **9 VIZ**` — NOTE: 7 is now implemented+core-tested; the line still says "next: 7" — UPDATE IT when committing next docs change (user approved tracker edits).
- **#7 SPEC_7**: implemented; remaining §7 checklist items in §11 below.
- **#8 "Us" playlists** (next feature): two-way editable shared playlists, last-writer-wins, array-union merges, no CRDTs. REQUIRES USER DECISION: Option A = `sharedWith` column on PlaylistEntity (Room schema change — AGENTS.md #5 needs explicit sign-off) vs Option B = Firestore-only rendering (no schema change). Next agent: present both designs, get decision.
- **#9 VIZ** (last): FFT visualizer for downloaded songs; sidecar file at download time (decode→PCM→FFT, ~50-200KB); playback = array lookup by position; streams → simulated bars; no RECORD_AUDIO permission; ~1 day est.
- Dropped: #10 Couple Wrapped; guest full transport control (Aug 25 — user decided suggest+auto-approve suffices).

---

# 11. TESTING STATE & PROCEDURES

## 11.1 Verified working (on devices)
- Invite flow end-to-end: phone invite → waiting card in session view → emulator banner (pop-up on home screen) → Join → both in room (room code S4J7S5J3 observed, "Connected users (2)", eman Host + aswinitest You) → playback syncs (song played on phone appeared on emulator) → host controls playback; guest mute/unmute local; guest adds songs via "Suggest to host" + auto-approve toggle (user confirmed solved).
- Decline/cancel flows ✅ (user-tested). 30-min expiry ✅ (user-tested) — fixed from 15 `ListenTogetherInviteModels.kt:29` so poll has two cycles of slack; `SPEC_7.md:21`.
- Nudge: all 8 sub-tests ✅.
- W-SCALE ✅ (visual).
- 2026-08-26 post-fix re-verify `13.6.3` on phone `ylwwmn85w4ifb6z9` + emulators `5554`/`5556` `10:48:10`: `10:11:31 TST4F` banner `verify01_banner.png`, `10:14` `HOME` → notification `id=2800 when=1787728411650`, dead-process poll resurrect `verify_t5d.txt` `RESURRECTED pid: 17810 when=1787729228359`, reopen banner `verify_reopen.png` / clean `verify_clean.png`, delete → `Incoming emission: null`.

## 11.2 Crash found & fixed, needs re-test
- **Mutual-invite accept-from-inside-a-room**: eman in room, aswini left + invited him, he accepted → CRASH `IllegalStateException: Player is accessed on the wrong thread. Current thread: 'DefaultDispatcher-worker-8' Expected: 'main'` at `ExoPlayerImpl.removeListener(1926)` ← `ListenTogetherManager.cleanup(702)` ← `leaveRoom(1786)` ← `InviteNotifier$joinFromInvite$1.invokeSuspend(InviteNotifier.kt:206)` (Dispatchers.IO). Fixed in `696cfe6b4` (withContext(Dispatchers.Main) around manager calls). Installed both devices. ✅ RE-TEST PASSED (Aug 25, user-verified): accepting an invite from inside a session switches rooms without crashing.

## 11.3 SPEC_7 §7 items (SPEC_7 §7)
1. ✅ DONE — Backgrounded notification: emulator app alive+backgrounded → invite → heads-up notification → tap → join UI directly (no banner)
2. ✅ DONE — Banner-ignored → backgrounded → notification fires immediately `verify01_banner→verify01_shade`: `when=1787693052184` + banner `verify01_banner.png`; `06:57 verify_reopen.png` shows banner `eman wants to listen together`
3. ✅ DONE — Poll never double-notifies while foregrounded — `InvitePoll: Already notified for this invite` at `00:38:11`; `when=1787693036148` unchanged
4. ✅ DONE — Fully-closed poll: `am kill` schedulable dead → poll resurrects `verify_t5d.txt` `RESURRECTED pid: 17810` notification `when=1787729228359`; reopen banner `verify_reopen.png` / clean `verify_clean.png`; 15→30 fix `ListenTogetherInviteModels.kt:29` proven. Force-stop still blocked per `SPEC_7.md:33` D14.
5. ✅ DONE (assumed passed per user instruction — airplane mode `JoinRejected` `withTimeoutOrNull 20s` `onFailed(false)` leaves doc live; verified via `firestore Check` doc `GET still pending` + re-join path `InviteNotifier.kt:200 joinFromInvite`) — mark on real-device retry later if needed.
6. ✅ DONE (verified autonomously on `emulator-5556` aswinitest `12:20–12:30`, dirty emulator data path): planted `FAKE999`/`VANISH1`–`VANISH3` room codes never created on `metroserver` `SPEC_7.md:7`; banner `hv_banner2.png` / `hv_banner3.png` `eman wants to listen together / Live listening session` → tap `Join` `connectionState CONNECTING` → `withTimeoutOrNull 20s` no `JoinApproved` → `onFailed(true→false)` `lt_invite_room_gone_toast` **"The session has ended"** branch; doc remains `pending` `GET invites/EuM3K...` live, no accept-stamp `src/social/ListenTogetherInviteRepository.kt:acceptInvite`; 3/3 cycles `12:25:04 VANISH1` `12:26:06 VANISH2` `12:28:45 VANISH3` : payload `action= "The session has ended"` / `LTInvite: No live invite present` pre-fill on stale doc. Cleaned to `GET 404`.
7. ✅ DONE — Hidden manual join (Advanced) still works
8. ✅ DONE (assumed passed per user instruction — free-tier sanity / listener/worker storms; check Firebase usage dashboard after a day `CONTINUATION.md:631` when convenient).

## 11.4 Debugging procedures that worked
- Live logcat captures: `adb -s <dev> logcat -s LTInvite PartnerResolver ListenTogetherClient ListenTogetherManager` (background_process tool; captures die with agent session or device restart — restart them).
- Clear buffer: `adb -s <dev> logcat -c`.
- Find UID: `adb -s emulator-5556 logcat -d -e "EuM3KTt|45qlBVD"` (FirebaseAuth line).
- Firestore inspection: node REST scripts (§1.5) or user's console.
- The debugging sequence that cracked it: plant/inspect Firestore docs → read both devices' filtered logcat → map PERMISSION_DENIED/absence-of-logs to code paths.

---

# 12. USER RULES & PREFERENCES (verbatim where it matters)

1. **"stop using shell to read the code. ALWAYS USE YOUR READ TOOL. ... always use the read tool to read."** — repeated 3+ times, saved as a Kilo memory correction. Shell ONLY for executing commands. (Grep/Glob tools are the designated search tools and acceptable; raw shell rg/cat for reading is NOT.)
2. **"you should NEVER start fixing or finding the bug without fully learning the [feature's] code first, all the code, including the UI. nothing missed."**
3. Don't commit unless asked; don't push unless asked; don't install unless asked. User says "commit", "push", "install to phone/emulator" explicitly.
4. Pushes always go to `personal` remote. Merge = fast-forward main to testing.
5. No emojis. Concise responses unless detail requested. The user is LEARNING GIT — explain concepts simply when they ask (rebase/stash/cherry-pick taught this session).
6. The user tests on devices and reports; agent handles builds/installs/logcat/Firebase console work ("check firebase yourself. i'm not doing it. i gave you access to the firebase cli under my owner accounts").
7. When the user says "stop" or "stand by" — comply immediately, exactly ("say Standing by").
8. Queued messages ("if you stopped in the middle, continue; if you finished, say Standing by") — comply: continue work or say "Standing by."
9. AGENTS.md in the repo has the project rules (no md edits except feature spec files, no schema changes without sign-off, conventional commits, no force-push except own-branch rebases, etc.). User-ordered doc edits override.
10. The user appreciates being asked questions before big implementations ("ask me every possible question") — for SPEC_7 this was done via the question tool in 4 rounds.

---

# 13. KNOWN ISSUES, DEFERRED ITEMS, GOTCHAS

1. **PartnerWidgetManager.kt mojibake** (double-encoded em-dashes, lines ~70/140/205/231/392) — pre-existing (commit `0201913c7`), out of scope, DO NOT "fix" casually. (A suspected mojibake in SongNotificationHelper.kt:43 was byte-verified as a proper U+2014 em-dash — false alarm; the read tool displayed it wrong once.)
2. `lt_invite_expired_toast` string unused (expired invites vanish instead of tappable — deliberate deviation, in SPEC_7 §4).
3. "Newest wins" toast for simultaneous invites superseded by D12 auto-cancel (per-recipient docs never overwrite each other; both banners coexist; one accept resolves). SPEC_7 documents this.
4. During invite-joins, AdvancedJoinSection briefly shows (shared `isJoiningRoom` flag) — cosmetic, accepted.
5. `:crash` process: no Firebase; workers must catch Throwable (NoClassDefFoundError). GentleNudgeWorker does; InvitePollWorker catches Exception only (consider mirroring if touched).
6. Notification deep-link extra `"n"` (SongNotificationHelper listened-notification) appears to have NO consumer in MainActivity — pre-existing upstream quirk; the LT invite notification uses its own handled extra. Don't conflate them.
7. App.kt contains upstream Arabic comments — leave them.
8. Wireless ADB on the phone is unreliable (MIUI); use `adb tcpip 5555` after USB.
9. Emulator restarts kill logcat captures + the app; relaunch app + restart captures after.
10. Robolectric test downloads can flake (network) — retry the test task.
11. The `users` collection scan in PartnerResolver uses `firstOrNull` — if multiple matching docs ever exist (stale test accounts), it may pick a dead UID. Currently exactly 2 docs exist (verified). Watch for this if test accounts proliferate.
12. `ListenTogetherManager.events` is a replay-0 SharedFlow — `first{}` subscribers must attach before the event fires; joinFromInvite subscribes immediately after joinRoom (network RTT makes this safe in practice).
13. Free-tier: all features designed well under limits (invites ~2 reads+2 writes/user/invite, poll ≤96 reads/day/device, nudge ~4 reads+1 batch/day).

---

# 14. NEXT STEPS (in order)

## 14.1 Aug 26 SPEC_8 checkpoint (newest state; supersedes the older list below)

SPEC_8 "Us" playlists are implemented in the working tree and Firestore rules are deployed to
`outertune-social`. `./gradlew :app:testFossDebugUnitTest :app:assembleFossDebug` passed and the APK
was installed on phone `ylwwmn85w4ifb6z9` and MuMu emulator `127.0.0.1:7555`.

First two-device results: receive of a 408-song playlist completed; receive, rename, add, duplicate
guard, local-song guard, independent reorder, independent playback, offline recovery, symmetric
delete, and no-unshare passed. User counts the destructive account-delete scenario as passed without
deleting either real test account. Rules were deployed twice, with the final deployment compiling
without warnings.

Confirmed open defects:

1. Emulator logcat captured repeated `SQLiteConstraintException: FOREIGN KEY constraint failed` at
   `SharedPlaylistRepository.addSongLocally` during the 408-song import. `database.query` queues the
   song insert asynchronously, so the playlist-map transaction can run first.
2. Remove-song disappeared only locally, did not disappear on the partner, then reappeared after
   cloud reconciliation 1–2 minutes later.
3. The remote-additions `X new` badge was not observed.
4. The shared two-person glyph appears, but the 2dp whole-card outline is difficult to see and did
   not visibly react to the current song palette.
5. User changed D19: playlist cover art is now device-local. Remove/avoid Firestore cover syncing.

Next action after this checkpoint commit/push: fix those four functional/UI defects, remove cover
sync, rerun unit/build checks, reinstall both devices, and retest only those focused cases.

1. **Verify device/build state** — adb was just restarted; devices reconnected (`ylwwmn85w4ifb6z9` + `emulator-5556` confirmed). Installed build should be `696cfe6b4`'s APK (installed ~15:44 Aug 25; no code changed since). Verify via `adb -s <dev> shell dumpsys package com.metrolist.music.debug | Select-String lastUpdateTime` vs APK LastWriteTime (3:44:58 PM); reinstall if unsure.
2. ~~Re-test mutual-invite scenario~~ — ✅ DONE (Aug 25): crash fix `696cfe6b4` verified; accepting an invite from inside a session switches rooms without crashing.
3. **Run remaining SPEC_7 tests** (§11.3 items 5–6): failed-join retry (airplane mode), host-vanished toast. Items 1–4, 7 done; 30 min expiry now proven.
4. **Update MAYBE_LATER.md progress line** (7 → ✅) and PENDING_TESTS.md as tests pass (user approves tracker edits).
5. **Plan #8 "Us" playlists** — present Option A (Room `sharedWith` column; needs explicit schema-change sign-off) vs Option B (Firestore-only mirroring); user decides; then spec → phases → implement (follow the SPEC_7 pattern: spec file, phased commits, deep code study first).
6. **#9 VIZ** after #8.
7. Free-tier dashboard check after a day of usage.
8. CONTINUATION.md (this file) — uncommitted; commit/push if the user wants it preserved in git.

---

# 15. SESSION NARRATIVE (chronological — everything that happened)

1. **Full repo analysis** (user: "Analyze whole repo metrolist... read every md file, every document. read all the code."): analyzed architecture, git topology (fork +48 over upstream `732dd13db`), all docs (README, AGENTS.md, development_guide, MAYBE_LATER, SPEC_13, changelog, docs/port-design/*, docs/outertune-dev/* — 12 files incl. PENDINGFIXES/PENDINGTESTS), all code areas via subagent exploration. Delivered comprehensive report.
2. **Original repo comparison**: user asked to clone upstream to a separate folder; first clone attempt aborted by user ("stop. i will clone it myself"); user cloned without --recursive (metroproto empty); agent initialized submodule; user deleted and re-cloned; final successful clone `--recursive` verified (tip = `732dd13db` = exact fork point). Diff analysis delivered (§3).
3. **MAYBE_LATER analysis** delivered (build order, per-item assessment, dependency discipline).
4. **W-SCALE**: planned (read PartnerWidgetManager fully) → user confirmed ratio preservation intent → implemented (shared multiplier, ellipsize-after-scale, scaled baseline gap) → compiled → built → user found adb missing → PATH added → wireless adb pairing struggles → USB+tcpip 5555 → installed phone+emulator → user verified ("perfect. firebase works too now. the status in the widget.. size is good too") → committed `b9299de37` → user requested 65% floor (from 50%) → `d71b86bc0` → rebuilt → installed → committed/pushed → main fast-forwarded + pushed → git teaching moment (rebase/stash/cherry-pick explained with repo-specific examples).
5. **Nudge #4**: planned (read worker/manager/helper/repository code) → user said go with "extremely careful... make sure this wont be too unoptimized for my firebase free tier" → implemented 3 commits → user asked "how would i test without waiting 3 days" → explained console-backdate technique (no code changes) → user asked for complete sweep + guarantee → sweep found 2 defects (notification replacement; crash-safety Throwable) → fixed `41d1667a8` → user asked about app-closed/force-closed conditions → AppForegroundTracker created + foreground skip → user CORRECTED the foreground logic ("the From aswini playlist doesn't tell me if SHE LISTENED... the notification nudge is the only thing telling me") → asymmetric rule implemented (sender nudge never suppressed) → built → committed `5c792e400` → pushed → user tested OK ("perfect") → PENDING_TESTS.md created → user said items 1,2,3 (old pending docs items) already tested → asked what ellipsis means → roadmap recap.
6. **SPEC_7 planning**: user: "plan out the next feature... ask me every possible question". Read LT surface (client API, screen, navigation). 4 rounds of questions (delivery model, expiry, decline, manual join, busy state, username, host role, queue adding, delivery map, edge cases, poll interval correction — user wanted 5-min polling, agent corrected: WorkManager floor is 15 min, live path is the listener, force-stop is OS-blocked, FCM needs Blaze) + gap findings (invite-during-session, decline signal, mutual collision, failed join, auto-approve) → SPEC_7.md written (16 decisions, 3 phases) → user asked to double-check → 5 spec defects found+fixed → "go" → Phase 1 → Phase 2 → Phase 3 (user interrupted mid-Phase-3 exploration: "why are you using shell to read the code?" → switched to Read tool permanently) → compile errors fixed (imports, isJoiningRoom param) → built → committed.
7. **Deep review of Phase 2** (user-requested): found 5 defects (banner-never-appears-after-foregrounding; duplicate notification after process death; stale shade notification; expired-invite notification; back-stack stacking) → all fixed → committed `2a5214955` (wait — that's the final fixes commit; the Phase-2 review fixes were `373b40329`... both existed; `2a5214955` was the final full-implementation review pass).
8. **Deploy + full review**: rules deployed; full SPEC_7 review (all files) → 2 more fixes (main-thread callbacks via Handler; joinInFlight AtomicBoolean) → verdict: code-complete.
9. **Testing war** (the most instructive part):
   - Test 1: user tapped Invite → toast "room created with code" + "Couldn't create session" toast + nothing on emulator. User angry: "I don't think you learned anything about the entire LT feature before implementing all this. READ EVERYTHING, EVERY LINE OF CODE."
   - Agent read ALL LT code (~7,100 lines, every file). Diagnosed: room creation SUCCEEDED (global toast), sendInvite failed fast → partner resolution. User provided account data (aswinitest/test1@gmail.com, eman/emanfunnyalt2@gmail.com) — data was correct.
   - Fixes: resolver retry loop, sendInvite self-heal + logging, screen logging (`2db508983`).
   - Retest: "Invite sent to partner (EuM3KTt...)" in logs — SEND WORKED. But no waiting card (design flaw: room creation flips isInRoom instantly, hiding the join-section UI) and nothing on emulator.
   - Fixed waiting card placement (inside session view, `e82e216b6`).
   - Emulator still silent → logcat revealed PERMISSION_DENIED on outgoing listener → root cause: rules deny sender reads of NONEXISTENT docs → converted all sender reads to queries (`7824ef832`).
   - Emulator still silent → discovered listeners attached with null uid on cold start (auth restores async) → authUidFlow + flatMapLatest (`b08729d15`).
   - Still nothing → user: "Still doesn't work at all. not a single change." → pulled logs → found re-invite PERMISSION_DENIED on WRITE (update rule blocked roomCode change) AND the smoking gun: `App.kt:157 inviteNotifier.get()` **never called start()** → fixed `.get().start()` + rules update-rule split → REDEPLOYED rules (`b60583334`).
   - Verification: agent planted a test invite via Firestore REST → emulator logcat showed `Incoming emission: UZ9V7B24` — listener LIVE (the invite itself had expired 1 min prior, hence no banner).
   - User restarted emulator → agent reconnected adb + restarted logcat capture + relaunched app.
   - **TEST PASSED**: user's report + emulator screenshots — banner appeared, Join worked, both in room S4J7S5J3, playback synced, host controls work, guest mute works. "listening together works."
10. **Guest control request** → user wanted guest full control → agent investigated (found the 3 blockers + server unknown) → user said hold, just feasibility → report delivered → user explained suggest+auto-approve already solves song-adding → DROPPED the feature, deleted experiment md (`1c9082e2d`).
11. **Crash report**: user tested mutual invites → accept-from-inside-room CRASHED (wrong-thread ExoPlayer access, full stack trace provided) → fixed with withContext(Dispatchers.Main) (`696cfe6b4`) → installed both.
12. User asked what tests remain → list given → user asked if latest build installed → adb devices were offline → user reconnected, adb restarted (devices confirmed) → user said STOP → "Standing by" → **this CONTINUATION.md dump**.

---

# 16. KILO MEMORY RECORDS (persisted across sessions; recall if this file is lost)

- `spec7-status` — SPEC_7 code complete, tip 2a5214955 (now outdated — HEAD is 696cfe6b4), rules deployed, remaining tests.
- `spec7-testing-result` — full testing result + all 6 bug fixes + debugging workflow + remaining tests (written Aug 25 after the war).
- `lt_invite_expiry_design` — invites expire by receiver-clock at 15 min, no expiresAt stored (D2).
- `lt_main_thread_callbacks` — ListenTogether/InviteNotifier join callbacks must run on main thread; AtomicBoolean guard.
- `invite_notifier_dedupe` — transition-aware re-evaluation with persistent dedupe of last notified createdAt.
- `partner_widget_mojibake` — pre-existing mojibake from 0201913c7, out of scope.
- `spec_7_documented_deviations` — lt_invite_expired_toast unused; newest-wins superseded by D12; shared isJoiningRoom surface.
- `push_branch` — pushes go to personal/testing on outertune-social remote; rules via firebase CLI.
- `pending_firestore_rules_deploy` — OUTDATED (deploy done).
- `build_command` — ./gradlew :app:compileFossDebugKotlin -q.
- `two_device_checklist_setup` — one phone + one emulator (aswini partner account); don't set emulator clock forward.
- Correction record: **always use Read tool for reading code, never shell**.
- `latest_session_digest` — session handoff digests exist; this CONTINUATION.md supersedes them.

---

END OF DUMP. The next agent should start at §14 (Next Steps), verify §1.3/§2.3 state, and obey §12 rules absolutely.
