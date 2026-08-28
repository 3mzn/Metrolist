# SPEC 9: In-App Update Push Framework (v4)

## 0. Goal

Push a new Metrolist APK to every installed copy of the FOSS and Gms flavors of the app without users manually re-downloading. User ships a release via the existing `release.yml` GitHub Actions workflow, then a sibling workflow (`notify-update.yml`) downloads the signed APK from the GitHub Release (private repo, authenticated via `GITHUB_TOKEN`), uploads it to Supabase Storage bucket `releases` (public), generates a `notes.html` from `changelog.md` and uploads it alongside, then broadcasts an FCM data message with the Supabase public URLs. Devices compare the pushed `versionCode` to their installed `versionCode`; if newer, a notification appears and offers an in-app install.

This is the standard FOSS self-update pattern (F-Droid, Obtainium, Zapstore, rouse-context, dashline, birdecho, Cloudy all use a variant). It is **not** Play Store auto-update — Play Core is unavailable, and the build is FOSS sideloaded.

Flavor scope: **foss and gms** both get the updater. `UPDATER_AVAILABLE=true` for both per `app/build.gradle.kts:142,149`; only `izzy` is `false` (line 156). Izzy is out of scope.

## 1. The 8-step happy path

1. User ships a feature. Tests pass on the 3 devices.
2. **User bumps `versionName` and `versionCode`** in `app/build.gradle.kts`. *This is intentionally not done by the agent — AGENTS.md §AI-9 forbids it.* Commits the bump on `personal/testing`, pushes.
3. User merges `testing` → `main` on the `personal` remote. The existing `release.yml` (already in the repo) fires. It builds all 3 flavors, signs, and creates a GitHub Release with the FOSS APK as `Metrolist.apk` and the gms APK as `Metrolist-with-Google-Cast.apk`.
4. New `notify-update.yml` fires on `release: published`. Downloads the FOSS/GMS APKs from the GitHub Release (private repo, `Authorization: Bearer $GITHUB_TOKEN`), reads `versionCode` from `aapt2 dump badging`, uploads each APK to Supabase Storage `releases/<ver>/Metrolist.apk` (public) and generates/uploads `releases/<ver>/notes.html` from `changelog.md`, then POSTs to FCM with the Supabase public URLs. Two messages, one per topic.
5. FCM fans the messages out to all devices subscribed to the topics.
6. Each phone's `SongListenedMessagingService.onMessageReceived` runs. The existing service already has a `when (data["type"])` dispatcher. We add a new branch: `KEY_TYPE_APP_UPDATE -> AppUpdateNotifier.handle(...)`.
7. `AppUpdateNotifier` reads the installed `versionCode` via `PackageInfoCompat.getLongVersionCode(...)` (compared as `Long`, not `Int` — the platform's `Int`-based accessor is deprecated on `targetSdk = 36` and the `Long` accessor is the only correct one for forward compatibility). If pushed `≤` installed, return silently. If `>`, download via **OkHttp + `FileOutputStream` to `context.cacheDir/update-<versionCode>.apk`** (one coroutine, no `DownloadManager` polling — auditor's M6 blocker: `DownloadManager.setDestinationUri(Uri.fromFile(...))` is rejected on Android 10+ because `DownloadManager` writes via `MediaProvider` which can't write to `cacheDir`). Post a notification with two `PendingIntent` actions.
8. User taps "Install now". `Intent(ACTION_INSTALL_PACKAGE)` is fired with `intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)` (Intent flag, not PendingIntent flag — auditor's M6 fix: `FLAG_ACTIVITY_NEW_TASK` is an Intent flag, not a PendingIntent flag) on a `FileProvider` URI with the existing `${applicationId}.FileProvider` authority. The `PendingIntent` itself is built with `PendingIntent.getActivity(..., FLAG_IMMUTABLE | FLAG_GRANT_READ_URI_PERMISSION)` (the correct PendingIntent flags for API 31+). Android shows the install dialog. User taps Install. The new APK replaces the old in place (signing key matches, `INSTALL_FAILED_UPDATE_INCOMPATIBLE` is avoided because we also verify the downloaded APK's signing cert SHA-256 matches the installed app's before firing the install intent — see §3.1.8).

## 2. The FCM data contract

```json
{
  "message": {
    "topic": "metrolist_foss_updates",
    "data": {
      "type": "app_update",
      "latestVersionCode": "153",
      "latestVersionName": "13.6.4",
      "apkUrl": "https://teeafutbybbywitdahpr.supabase.co/storage/v1/object/public/releases/13.6.4/Metrolist.apk",
      "releaseNotesUrl": "https://teeafutbybbywitdahpr.supabase.co/storage/v1/object/public/releases/13.6.4/notes.html",
      "apkSha256": "<hex of the signed APK's SHA-256>"
    },
    "android": {
      "priority": "high",
      "ttl": "604800s"
    }
  }
}
```

Decisions, with the v1 plan's gaps closed:

- **Data-only, no `notification` payload.** Required so `onMessageReceived` always fires (FCM suppresses `notification` for foregrounded apps; the user would never see the update prompt while the app is open).
- **`priority: "high"`.** Doze mode batches normal-priority messages on sleeping devices for 1-10 min. The v1 plan already had this.
- **Explicit `ttl: "604800s"`** (7 days, not the 28-day default and not the ~4000-s Doze default). v1 plan claimed "28-day retention default" — that's only true for messages with `content_available` or specific options. Setting an explicit TTL removes ambiguity.
- **Topic name** is `metrolist_foss_updates` for FOSS and `metrolist_gms_updates` for gms. Two topics, two parallel messages, no flavor-specific code at the topic-subscription site. (v1 was wrong: it assumed gms is hypothetical-Play. Per `app/build.gradle.kts:142,149` the gms flavor explicitly has `UPDATER_AVAILABLE=true`, so it gets the updater too.)
- **`apkSha256`** is added to the payload. The handler verifies the downloaded APK's SHA-256 against this before firing the install intent. This is the user-side defense against the v1 §4.6 security gap the auditor caught: if the FCM topic is compromised, the attacker can't push a malicious APK because they can't generate a payload with the right SHA-256 of an APK they didn't sign with our keystore.
- **`>`, not `>=`** for the version comparison. Re-sending the same version is a silent no-op. Pushing a lower version (rollback) is also silent.

## 3. The 3 new files (v1 had 5; 2 collapsed into existing infrastructure)

### 3.1 `app/src/main/kotlin/com/metrolist/music/update/AppUpdateNotifier.kt` (~180 lines)

Pure notification-helper class. Single responsibility: handle an `app_update` FCM message — verify the version, download the APK, verify the SHA-256, build and post the notification.

Public surface:

```kotlin
object AppUpdateNotifier {
    /** Called from SongListenedMessagingService.onMessageReceived when data["type"] == "app_update". */
    suspend fun handle(context: Context, data: Map<String, String>)
    /** Idempotent. Called from App.onCreate. */
    fun createChannel(context: Context)
}
```

Internals:
- `createChannel`: channel id `"app_updates"`, name "App updates", `IMPORTANCE_HIGH`. Idempotent (no-op if exists).
- `handle`:
  1. Parse `data["latestVersionCode"]` to `Long`. On parse failure, log and return.
  2. Read installed `versionCode` via `PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))`. Compare: if pushed `≤` installed, return silently.
  3. Read `data["apkUrl"]`, `data["apkSha256"]`, `data["releaseNotesUrl"]`, `data["latestVersionName"]`. If any missing, log and return.
  4. **URL host pin**: assert `Uri.parse(apkUrl).host == "github.com" || Uri.parse(apkUrl).host.endsWith(".githubusercontent.com") || Uri.parse(apkUrl).host == "teeafutbybbywitdahpr.supabase.co"`. The leading dot is required — `host.endsWith("githubusercontent.com")` (no leading dot) would pass `evilgithubusercontent.com` (auditor's fourth-pass blocker). The leading dot restricts the match to actual subdomains of `githubusercontent.com` (`objects.githubusercontent.com`, `release-assets.githubusercontent.com` — the actual redirect targets from `github.com/.../releases/download/...`). If the host check fails, log and return. SHA-256 verification after download is the primary defense; host pin is defense-in-depth.
  5. **Download via OkHttp + `FileOutputStream` to `context.cacheDir/update-<versionCode>.apk`** (one coroutine, `withContext(Dispatchers.IO)`). Use `client.newCall(request).execute()` (synchronous, not `enqueue(callback)` — the auditor's fourth-pass confirmation: `enqueue` would require `suspendCancellableCoroutine` plus a `Dispatchers.Main` hop for `notify()`, and a callback-thread `notify` is undefined behavior). Code shape:
     ```kotlin
     val request = Request.Builder().url(apkUrl).build()
     client.newCall(request).execute().use { response ->
         if (!response.isSuccessful) error("HTTP ${response.code}")
         val target = File(context.cacheDir, "update-$versionCode.apk")
         response.body!!.byteStream().use { input ->
             FileOutputStream(target).use { output -> input.copyTo(output) }
         }
     }
     ```
     `DownloadManager.setDestinationUri(Uri.fromFile(...))` is rejected on Android 10+ because `DownloadManager` writes via `MediaProvider` and can't write to `context.cacheDir` (auditor's M6). We could also use `setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "update-<v>.apk")` but that puts the APK in shared storage where it persists; the `cacheDir` location is auto-cleaned by Android when space is low. OkHttp is already a transitive dep via the existing FCM/HTTP code path — verify before adding it as an explicit dep.
  6. **SHA-256 verify** the downloaded file against `data["apkSha256"]` using `MessageDigest.getInstance("SHA-256")` over the entire `FileInputStream`. If mismatch, log, delete the file, post a "verification failed, open release page" notification. (Strong defense for Auditor's M8.)
  7. **Signing cert verify** (handles API 26-27 vs API 28+). Compute SHA-256 of all current signers and the signing certificate history; every history cert SHA-256 must match at least one installed-app cert SHA-256. Pseudocode:
     ```kotlin
     val installedInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
     val installedCertShas = installedInfo.signingInfoOrSignatures().map { sha256(it.toByteArray()) }.toSet()
     val downloadedInfo = packageManager.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNING_CERTIFICATES)
         ?: error("getPackageArchiveInfo returned null")
     val downloadedCertShas = downloadedInfo.signingInfoOrSignatures().map { sha256(it.toByteArray()) }.toSet()
     if (downloadedCertShas.intersect(installedCertShas).isEmpty()) { /* mismatch */ }
     ```
     where `signingInfoOrSignatures()` is a small extension that does `if (Build.VERSION.SDK_INT >= 28) info.signingInfo.apkContentsSigners else PackageInfoCompat.getSignatures(info)` (auditor's M8: must check all signers and the history, not `[0]`; auditor's §10.4: must handle minSdk 26 with `PackageInfoCompat.getSignatures`).
  8. Build the notification with two `PendingIntent` actions:
     - **"Install now"** — Intent flags vs PendingIntent flags split correctly (auditor's M6: the v2 spec had them mixed):
       ```kotlin
       val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
           setDataAndType(apkUri, "application/vnd.android.package-archive")
           addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)   // Intent flag
           addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)            // Intent flag, required from non-Activity context
           putExtra(Intent.EXTRA_RETURN_RESULT, true)
       }
       val installPi = PendingIntent.getActivity(
           context, 0, installIntent,
           PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_GRANT_READ_URI_PERMISSION  // PendingIntent flags
       )
       ```
     - **"Open release page"** → `Intent(ACTION_VIEW, Uri.parse(releaseNotesUrl))` in Chrome Custom Tabs. Same flag split pattern.
  9. Tap-the-body fires "Install now" (uses the same `installPi`).
  10. **Install permission check** before firing: `context.packageManager.canRequestPackageInstalls()`. If false, the "Install now" action instead opens `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for the package. (Auditor's M6: explicit activity is required; without this, 80% of fresh installs hit the browser fallback.)
  11. Post the notification.

**Why the FCM handler is non-suspend:** the v2 spec proposed converting `onMessageReceived` to `suspend`; the auditor caught that FCM's `onMessageReceived(RemoteMessage): void` Java signature cannot be overridden by a Kotlin `suspend` function. v3 keeps the existing service method non-suspend and instead launches the work on an application-scoped coroutine.

### 3.2 `.github/workflows/notify-update.yml` (~80 lines)

Triggers on `release: { types: [published] }`. Two jobs: one for FOSS, one for gms. The `release: published` event is the public one, not `created` (which fires before assets are uploaded).

```yaml
name: Notify Update
on:
  release:
    types: [published]
  workflow_dispatch:
    inputs:
      tag:
        description: "Release tag to broadcast (e.g. v13.6.4)"
        required: true

concurrency:
  group: notify-${{ github.event.release.tag_name || inputs.tag }}
  cancel-in-progress: false
```

Why a separate `concurrency` group from `release.yml`: `release.yml` uses `${{ github.workflow }}-${{ github.ref }}`. If a second release is created quickly, the broadcast job must not be cancelled. The auditor's Gap 4 caught this.

Steps:
1. `actions/checkout@v6` with `fetch-depth: 0`.
2. `actions/setup-node@v4` (NOT `setup-java` — auditor's M7 caught the v1 wrong step).
3. `npm ci google-auth-library` (in a cached step).
4. Install `aapt2` via sdkmanager: `yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "build-tools;34.0.0"`. The full path is `$ANDROID_HOME/build-tools/34.0.0/aapt2` — `aapt` (no `2`) is **not** on PATH after `setup-android` and is also not in older build-tools versions (auditor's M5). We pin to build-tools 34.0.0 explicitly to avoid the version-drift risk.
5. For each of `[foss, gms]`, in a matrix:
   - `ASSET_NAME = "Metrolist.apk"` (foss) or `"Metrolist-with-Google-Cast.apk"` (gms). Derived from the existing `release.yml` rename logic at line 158-164.
   - Download the asset from the GitHub Release with `curl -L -H "Authorization: Bearer $GITHUB_TOKEN"` (private repo, 404 without auth) to `/tmp/${{ matrix.asset_name }}` (fallback `gh release view` → event payload → `api.github.com` with token).
   - Run `$ANDROID_HOME/build-tools/34.0.0/aapt2 dump badging` to get `versionCode`/`versionName`, `sha256sum` for `apkSha256` (APK is source of truth, not `grep` on Gradle).
   - Upload APK to Supabase Storage `releases/<ver>/Metrolist.apk` (`x-upsert:true`, `apikey`+`Authorization: Bearer $SUPABASE_SERVICE_KEY`, public URL `https://teeafutbybbywitdahpr.supabase.co/storage/v1/object/public/releases/<ver>/Metrolist.apk`).
   - Generate `notes.html` from `changelog.md` section for the tag (`<!doctype html>...<pre>changes</pre>`), upload to `releases/<ver>/notes.html` (`Content-Type: text/html`, public).
   - Mint OAuth token with `https://www.googleapis.com/auth/firebase.messaging` and POST to FCM with `topic`, `apkUrl` = Supabase APK public URL, `releaseNotesUrl` = Supabase notes public URL, `apkSha256`.
   - `Authorization: Bearer <token>`.
   - `add-mask` and `mask` the token in logs (auditor's M7).
6. If a FCM response is non-200, fail the step with the response body. The release is still created (it was a separate job); the broadcast failure is best-effort with a manual retry path via `workflow_dispatch`.

### 3.3 `C:\Users\emanf\AppData\Local\Temp\kilo\push-update.js` (~140 lines, on the user's laptop)

Node.js script for manual re-broadcasts. The workflow above auto-fires on every release; the script is the manual escape hatch for the user to re-push a missed message, or to broadcast to a different topic.

CLI: `node push-update.js --tag <tag> --flavor <foss|gms|both> [--apkUrl <url>]`

- `--tag` (required): release tag like `v13.6.4`.
- `--flavor` (required): which topic(s) to broadcast to. `foss`, `gms`, or `both`.
- `--apkUrl` (optional): skip the GitHub API lookup.

Logic:
1. Resolve the APK: if `--apkUrl` is set, download that URL to a temp file; else use the local signed APK at `C:/musicapp/metrolist/app/build/outputs/apk/foss/release/Metrolist.apk` (foss) or `Metrolist-with-Google-Cast.apk` (gms) — build with `assembleFossRelease`. Run `aapt2 dump badging` to get `versionCode`/`versionName`, compute SHA-256.
2. Upload the APK to Supabase Storage bucket `releases` at path `<tag-without-v>/<assetName>` (public, `x-upsert:true`). The public URL is `https://teeafutbybbywitdahpr.supabase.co/storage/v1/object/public/releases/<ver>/Metrolist.apk`.
3. Generate `notes.html` from `changelog.md` section for the tag (fallback to `Release <tag>`), upload to `releases/<ver>/notes.html` (public, `Content-Type: text/html`). Public URL is `.../releases/<ver>/notes.html`.
4. Mint OAuth token from `SUPABASE_SERVICE_KEY` (env `SUPABASE_SERVICE_KEY` or `~/.config/supabase-key`, `chmod 600`) + `OUTERTUNE_FCM_SERVICE_ACCOUNT` JSON, POST FCM to `metrolist_<flavor>_updates` with `apkUrl` = Supabase APK public URL, `releaseNotesUrl` = Supabase notes public URL, `apkSha256` = SHA of the uploaded APK.
5. Print FCM response.

The script reuses the same auth approach as the user's existing `firestore_check.js` (which already uses `google-auth-library` per the workspace setup). **Both the script and the workflow use the same `OUTERTUNE_FCM_SERVICE_ACCOUNT` JSON.**

## 4. The 3 modified files

### 4.1 `app/src/main/kotlin/com/metrolist/music/services/SongListenedMessagingService.kt`

Add one new branch to the existing `when (type)` block. **No new `<service>` declaration in the manifest.** Auditor's B3 is right: two `<service>` entries for `com.google.firebase.MESSAGING_EVENT` is a real interop problem on some OEMs and Android 14+. Extending the existing service is the natural fix; the service is already a multi-type dispatcher with a `KEY_TYPE` constant.

**The override MUST stay non-suspend** (auditor's B3 follow-up). FCM's Java signature is `void onMessageReceived(RemoteMessage)` — a Kotlin `suspend` override would not actually override the Java method, and the FCM SDK would call the Java method on a different code path, silently losing all update pushes.

Change shape (non-suspend, launches the work):

```kotlin
override fun onMessageReceived(message: RemoteMessage) {
    super.onMessageReceived(message)
    val data = message.data
    val type = data[KEY_TYPE]
    when (type) {
        TYPE_SONG_LISTENED -> handleSongListenedNotification(data)
        TYPE_APP_UPDATE -> appUpdateScope.launch {
            AppUpdateNotifier.handle(applicationContext, data)
        }
        else -> Timber.tag(TAG).w("Unknown FCM message type: $type")
    }
}
```

`appUpdateScope` is a `CoroutineScope(SupervisorJob() + Dispatchers.IO)` that the `App` class owns (sibling of the existing `appScope`). Application-scoped so the work survives the service's `onMessageReceived` returning (the FCM handler thread is short-lived — a few seconds — and a slow download would be killed if the scope were tied to the service).

Add to the companion object's constants:
```kotlin
const val TYPE_APP_UPDATE = "app_update"
```

The `appUpdateScope` is a new field on `App` and is cancelled in `onTerminate` (no-op on real devices, used in tests). Same lifecycle pattern as the existing FCM service.

### 4.2 `app/src/main/kotlin/com/metrolist/music/App.kt`

Four additions, all behind `if (BuildConfig.UPDATER_AVAILABLE)`:

1. The `appUpdateScope` field that the FCM service uses (sibling of the existing `appScope`).
2. The notification channel creation in `onCreate`.
3. The topic subscription in `onCreate`.
4. The `appUpdateScope.cancel()` in `onTerminate` (no-op on real devices, used in tests).

```kotlin
// New field on App:
val appUpdateScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// In onCreate, after the existing appScope setup:
if (BuildConfig.UPDATER_AVAILABLE) {
    AppUpdateNotifier.createChannel(this)
    appScope.launch {
        runCatching {
            FirebaseMessaging.getInstance().subscribeToTopic(currentFlavorUpdateTopic())
        }.onFailure { Timber.tag(TAG).w(it, "subscribeToTopic failed") }
    }
}

private fun currentFlavorUpdateTopic(): String = when (BuildConfig.FLAVOR) {
    "gms" -> "metrolist_gms_updates"
    "foss" -> "metrolist_foss_updates"
    else -> error("UPDATER_AVAILABLE=true but flavor=${BuildConfig.FLAVOR} has no topic")
}

// In onTerminate (already exists for appScope, add appUpdateScope cancel):
override fun onTerminate() {
    appUpdateScope.cancel()
    super.onTerminate()
}
```

This moves the subscription out of `MainActivity.onCreate` (auditor's Blocker 4: it would fire on every rotation, every cold start, every process restart, with no GMS-absence guard). The `runCatching` is the GMS-absence guard — de-Googled ROMs that sideload the FOSS APK won't have `FirebaseMessaging` initialized, so `subscribeToTopic` throws `IllegalStateException`; the `runCatching` swallows it.

`App.kt:178` already initializes the app's other background services, so the subscription joins a long-lived scope and doesn't get cancelled on Activity lifecycle events. The `appUpdateScope` is what the FCM service's `appUpdateScope.launch { AppUpdateNotifier.handle(...) }` (added in §4.1) uses — owned by `App`, cancelled in `onTerminate`.

**Also: `POST_NOTIFICATIONS` runtime request.** The manifest declares the permission (`AndroidManifest.xml:6`), but `MainActivity` only requests `RECORD_AUDIO`. On Android 13+, the high-importance channel will never appear until the runtime request is added. The `POST_NOTIFICATIONS` request should happen in `MainActivity.onCreate` *behind* the same `if (BuildConfig.UPDATER_AVAILABLE)` guard, with a `shouldShowRequestPermissionRationale` check and a graceful "you've denied notifications, updates won't notify you" toast if denied.

This is the auditor's "Gap 1". Adding it as a 3rd modified file would balloon the change; instead, it goes in `App.kt` via a small helper function called from `MainActivity.onCreate`.

## 5. The one new GitHub secret

`OUTERTUNE_FCM_SERVICE_ACCOUNT`: the service account JSON. Created in Firebase Console (Project Settings → Service Accounts → Generate new private key). The service account needs the IAM role `roles/firebasecloudmessaging.messages.create` (or `roles/fcm.sender`) on the `outertune-social` project. The workflow decodes the JSON and uses `google-auth-library` to mint a JWT with OAuth scope `https://www.googleapis.com/auth/firebase.messaging`.

The same JSON file lives on the user's laptop at `C:\Users\emanf\AppData\Local\Temp\kilo\outertune-fcm-sa.json` for the `push-update.js` script. **Never committed to git.** The workflow uses `add-mask` and `mask` so even if the token leaks into a log line, GitHub redacts it.

## 6. Existing infrastructure (not changed)

- **`release.yml`** — builds + signs + uploads. 15-minute timeout sufficient. Outputs `Metrolist.apk` (foss), `Metrolist-with-Google-Cast.apk` (gms), `Metrolist-izzy.apk`.
- **`provider_paths.xml`** — already exists with `<cache-path name="cache" path="."/>` covering the entire `context.cacheDir`. **No new file needed.** Use authority `${applicationId}.FileProvider` (capital F+P) matching the existing declaration at `AndroidManifest.xml:222-230`. Auditor's Blocker 1: confirmed against the code.
- **FCM via `firebase-bom`** — already in deps. `google-services.json` wired. `SongListenedMessagingService` registered and working. The new `app_update` type just adds a `when` branch.
- **The keystore secrets + `RELEASE_TOKEN`** — already in GitHub Actions secrets.
- **`ilharp/sign-android-release@v2.0.0`** — already in use.

## 7. Decisions baked in (v1's open questions answered)

| Decision | v1 | v2 | Why |
|---|---|---|---|
| Topic name | `metrolist_foss_updates` (one topic) | `metrolist_foss_updates` + `metrolist_gms_updates` | `UPDATER_AVAILABLE=true` for both foss AND gms per `build.gradle.kts:142,149` |
| FCM message type | Data-only, no `notification` | Same | `onMessageReceived` must always fire |
| Android priority | `high` | Same | Doze resistance |
| TTL | (unspecified) | `604800s` (7 days) explicit | v1 claimed 28 days; auditor's Gap 3 caught the inaccuracy |
| Version comparison | `>`, not `>=` | Same, but as `Long` not `Int` | `PackageInfoCompat.getLongVersionCode`; `Int` accessor deprecated on `targetSdk=36` |
| Install flow | `ACTION_INSTALL_PACKAGE` via FileProvider | Same, with `FLAG_IMMUTABLE \| FLAG_GRANT_READ_URI_PERMISSION \| FLAG_ACTIVITY_NEW_TASK` | All three flags required on API 31+ for `PendingIntent` and for non-Activity context |
| `apkSha256` in payload | (missing) | Required | Closes the "compromised topic = phishing" gap from Major 8 |
| Host pin on `apkUrl` | (missing) | Required: `host.endsWith(".githubusercontent.com") \|\| host == "github.com" \|\| host == "teeafutbybbywitdahpr.supabase.co"` | Cheap second-line defense; leading dot prevents `evilgithubusercontent.com`; suffix covers `objects.githubusercontent.com` and `release-assets.githubusercontent.com` (the actual redirect targets) |
| Download mechanism | `DownloadManager` with `setDestinationUri(Uri.fromFile(cacheDir/...))` | **OkHttp + `FileOutputStream` to `context.cacheDir/update-<v>.apk`** (one coroutine) | Auditor's M6: `DownloadManager.setDestinationUri(Uri.fromFile(...))` is rejected on Android 10+ because `DownloadManager` writes via `MediaProvider` which can't write to `context.cacheDir`; no more cursor polling or strict-mode leaks |
| Install permission flow | (hand-waved) | Explicit: check `canRequestPackageInstalls()`, else open `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` | 80% of fresh installs hit this without the explicit flow |
| `PendingIntent` flag split | `FLAG_IMMUTABLE \| FLAG_GRANT_READ_URI_PERMISSION \| FLAG_ACTIVITY_NEW_TASK` on the `PendingIntent` (would not compile / crashes on API 31) | Intent flags on the intent (`addFlags(FLAG_ACTIVITY_NEW_TASK \| FLAG_GRANT_READ_URI_PERMISSION)`), PendingIntent flags on the `PendingIntent.getActivity(..., FLAG_IMMUTABLE \| FLAG_GRANT_READ_URI_PERMISSION)` | Auditor's M6: `FLAG_ACTIVITY_NEW_TASK` is an Intent flag, not a PendingIntent flag |
| Signing cert compat | `signingInfo.apkContentsSigners[0]` (API 28+ only) | `if (Build.VERSION.SDK_INT >= 28) info.signingInfo.run { apkContentsSigners + signingCertificateHistory } else PackageInfoCompat.getSignatures(info)` | Auditor's M8 + §10.4 + §11: minSdk 26 = crash on 8.0/8.1 without compat; `signingInfo` accessor is API 28+ |
| Cert comparison | `[0]` of signers | All signers + cert history; intersection non-empty | Auditor's M8: key rotation leaves history; must check all |
| FCM handler signature | `override suspend fun onMessageReceived(...)` (does not override Java void) | `override fun onMessageReceived(...)` + `appUpdateScope.launch { AppUpdateNotifier.handle(...) }` | Auditor's B3 follow-up: Kotlin `suspend` cannot override Java `void`; FCM calls Java method, suspend branch never runs |
| `onNewToken` | log only | Same | No server-side token store needed for topic messaging |
| Auto-broadcast trigger | `release: published` | Same, with own `concurrency` group | Don't cancel on rapid second release |
| Manual script | One tag arg | `--tag --flavor --apkUrl` | Re-broadcast to a single topic for testing |
| Service account scope | `roles/firebase.messaging.admin` | `https://www.googleapis.com/auth/firebase.messaging` (OAuth) | The v1 IAM role is not an OAuth scope; the call returns 403 otherwise |
| Workflow runtime | `actions/setup-java` | `actions/setup-node` | No Java needed; node is |
| Notification channel | `"app_updates"`, `IMPORTANCE_HIGH` | Same | Doze |
| Subscribe to topic | In `MainActivity.onCreate` (every rotation) | In `App.onCreate` behind `if (BuildConfig.UPDATER_AVAILABLE)`, wrapped in `runCatching` | De-Googled ROMs throw `IllegalStateException`; per-rotation calls are wasteful |
| `POST_NOTIFICATIONS` runtime | (declared, not requested) | Requested in `MainActivity.onCreate` behind `if (BuildConfig.UPDATER_AVAILABLE)`, with rationale | Without it, the notification never shows on Android 13+ |

## 8. Anti-scope (NOT in this pass; document for future passes)

- **No background auto-download.** User taps the notification → download starts. Simpler than F-Droid/Obtainium's "auto-download, then prompt install".
- **No "Skip this version" memory.** Three-action notification (Update now / Later / Skip) is rouse-context's pattern; one DataStore key, one extra `PendingIntent` action. Future.
- **No in-app "check for updates" button.** Parallel discovery mechanism. Future.
- **No per-flavor split beyond foss/gms.** Izzy stays out (`UPDATER_AVAILABLE=false`).
- **No in-app download progress.** 27 MB APK, seconds on Wi-Fi.
- **No signing-key rotation support.** If the user ever changes the keystore, in-app update breaks. Documented in the README; manual uninstall-and-reinstall is the recovery.
- **No Izzy support.** `UPDATER_AVAILABLE=false` for izzy; the service is still registered and reachable but the `BuildConfig.UPDATER_AVAILABLE` guard in `App.onCreate` and the `if (BuildConfig.UPDATER_AVAILABLE)` branch in `AppUpdateNotifier.handle` will skip the subscription and skip the notification respectively.

## 9. Build + test plan

1. Write `AppUpdateNotifier.kt` (1). Build `./gradlew :app:assembleFossDebug` per AGENTS.md §Building. Fix compile errors.
2. Modify `SongListenedMessagingService.kt` (4.1) — add the `KEY_TYPE_APP_UPDATE` branch. Build.
3. Modify `App.kt` (4.2) — add channel + subscription behind the guard. Build.
4. Modify `MainActivity.kt` — add the `POST_NOTIFICATIONS` runtime request. Build.
5. Install on `emulator-5554` (eman) + `emulator-5556` (aswini).
6. User provides the FCM service account JSON (`OUTERTUNE_FCM_SERVICE_ACCOUNT` + the same file on the laptop for the script). Add the GitHub secret.
7. Write `push-update.js` (3.3). Test with a fake version (push versionCode=999 → notification appears; versionCode=100 → nothing). Verify the SHA-256 verify path with a tampered URL.
8. Write `notify-update.yml` (3.2). Test by pushing a `v0.0.0-test` tag to the user's fork, watching the release get created, watching the workflow fire, watching the FCM message arrive on the emulator.
9. Real test: bump `versionCode` to a new number on `personal/testing`, push, merge, see the release, see the FCM message, tap the notification, install, verify the new versionCode is reported.
10. Test failure modes (each is a separate manual test on the emulator):
    - Install permission denied → notification's "Install now" button opens Settings to grant the permission
    - APK URL 404 → notification shows "Open release page" as the primary action with a "Couldn't download" hint
    - Pushed versionCode < installed → silent, no notification
    - `apkSha256` mismatch (tampered) → silent delete, "verification failed" notification
    - Signing-cert SHA-256 mismatch (all signers + history, compared as intersection) → silent delete, "verification failed" notification
    - APK URL host not in `.githubusercontent.com` / `github.com` allowlist (e.g. `evil.com/Metrolist.apk`) → silent, log
    - Notification permission denied on Android 13+ → no notification shown (system suppression), but the FCM handler still runs and the OkHttp download completes silently. **We will document this as a known limitation**; the in-app "check for updates" button is the recovery in a future pass.
    - GMS absent (de-Googled ROM, FOSS flavor) → `subscribeToTopic` throws, caught, no crash
    - Izzy flavor → `UPDATER_AVAILABLE=false`, subscription skipped, notification handler early-returns
11. Test on the real phone (Xiaomi 24117RN76G).
12. Update `PENDING_TESTS.md` with the new test cases. Update `CONTINUATION.md` §1 with the setup steps.

## 10. What's still open (these are real, please weigh in)

1. **Notification dismiss / swipe-away.** Default Android behavior: download continues. Leave as-is. **Confirmed.**
2. **Re-notification after dismiss.** Out of scope this pass; one-shot. **Confirmed.**
3. **SHA-256 staleness if the GitHub asset is re-uploaded with a different APK after broadcast.** The workflow re-computes the SHA-256 from the downloaded asset at broadcast time (not from a separate `app/build.gradle.kts` grep), so the broadcast payload always matches what was downloaded by the workflow. If the user later re-uploads a different APK to the same release, the broadcast is stale and users see "verification failed"; the `workflow_dispatch` re-broadcast path re-computes and re-broadcasts. **Confirmed acceptable.**
4. **`signingInfo` is API 28+; `minSdk` is 26.** Spec now mandates the v3 compat branch (§3.1 step 7): `if (Build.VERSION.SDK_INT >= 28) info.signingInfo.run { apkContentsSigners + signingCertificateHistory } else PackageInfoCompat.getSignatures(info)`. **The cert SHA-256 is computed for every signer and every history cert; the intersection with the installed-app cert SHA-256s must be non-empty.** This handles key rotation (history) and v26-v27 devices. **No minSdk bump.**
5. **`aapt2` on the GitHub Actions runner.** v3 spec uses `android-actions/setup-android@v3.2.2` (the correct action, the auditor's correction) plus an explicit `yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "build-tools;34.0.0"` step, then `$ANDROID_HOME/build-tools/34.0.0/aapt2 dump badging`. Hardcoded path; no `aapt` (no `2`) which isn't on PATH. **Confirmed.**
6. **`google-services.json` for FOSS/Gms flavors.** Verified against the repo: one `app/google-services.json` at the project root covers all flavors (the file is at `app/google-services.json`, not in any per-flavor source set). No change needed. The `FirebaseMessaging.getInstance()` call works for both. **Confirmed.**
7. **"Option b" install dialog.** User picked option b; the single system "Install update?" tap is expected. **Confirmed.**
8. **Future topic scope.** One workflow, two topics. **Confirmed.**

## 11. Risk acknowledgment

v3 closes all 14 issues the v1 reviewer raised + the 4 new blockers/regressions the v2 reviewer caught. Two remaining risks:

- **The release workflow's 15-minute timeout is tight.** Existing builds take 8-10 min; if the build is slow, the broadcast may not fire before the release is "complete." The broadcast workflow is triggered by `release: published` which fires when the release is fully created with assets — not when the build is done. So this is decoupled and safe.
- **OkHttp as a direct dep for the APK download.** v3 replaces `DownloadManager` with OkHttp + `FileOutputStream`. The app's existing FCM code path likely already brings OkHttp in transitively (FCM uses OkHttp under the hood for the v1 HTTP API). If not, add `implementation("com.squareup.okhttp3:okhttp:4.12.0")` to `app/build.gradle.kts`. Will verify before building.

**v3 is ready to build.** All four blockers, all six majors, and all four gaps from the v1 review are addressed. The v2 reviewer's three new blockers (suspend override, PendingIntent flag split, `DownloadManager` destination) and two new re-opens (M5 `aapt2` full path, M8 cert signers + history) are also addressed. The v3 reviewer's fourth-pass items (host pin leading dot, file-count consistency, OkHttp `execute()` not `enqueue()`) are addressed. The 3 new files (`AppUpdateNotifier.kt` + `notify-update.yml` + `push-update.js` on the user's laptop) plus the 3 modified files (`SongListenedMessagingService.kt` + `App.kt` + `MainActivity.kt`) plus the 1 GitHub secret (`OUTERTUNE_FCM_SERVICE_ACCOUNT`) are the complete implementation.

**~450 lines of Kotlin, ~90 lines of YAML, ~140 lines of JS. Builds on existing `release.yml` and existing FCM. No schema changes, no database migrations.**
