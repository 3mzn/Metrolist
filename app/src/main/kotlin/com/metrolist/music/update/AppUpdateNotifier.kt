package com.metrolist.music.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.metrolist.music.BuildConfig
import com.metrolist.music.R
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

object AppUpdateNotifier {
    private const val CHANNEL_ID = "app_updates"
    private const val NOTIFICATION_ID_UPDATE = 4000
    private const val NOTIFICATION_ID_VERIFY_FAILED = 4001
    private const val TAG = "AppUpdateNotifier"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when a new version of Metrolist is available"
            }
            nm.createNotificationChannel(channel)
        }
    }

    suspend fun handle(context: Context, data: Map<String, String>) {
        if (!BuildConfig.UPDATER_AVAILABLE) return

        val latestCodeStr = data["latestVersionCode"] ?: run {
            Timber.tag(TAG).w("Missing latestVersionCode")
            return
        }
        val latestCode = latestCodeStr.toLongOrNull() ?: run {
            Timber.tag(TAG).w("Invalid latestVersionCode: $latestCodeStr")
            return
        }
        val installedCode = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            PackageInfoCompat.getLongVersionCode(info)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to read installed versionCode")
            return
        }
        if (latestCode <= installedCode) return

        val apkUrl = data["apkUrl"] ?: run {
            Timber.tag(TAG).w("Missing apkUrl")
            return
        }
        val apkSha256 = data["apkSha256"] ?: run {
            Timber.tag(TAG).w("Missing apkSha256")
            return
        }
        val releaseNotesUrl = data["releaseNotesUrl"] ?: ""
        val latestName = data["latestVersionName"] ?: latestCodeStr

        val host = try { Uri.parse(apkUrl).host } catch (_: Exception) { null }
        if (host == null || !(host == "github.com" || host.endsWith(".githubusercontent.com") || host == "teeafutbybbywitdahpr.supabase.co")) {
            Timber.tag(TAG).w("Host pin failed for $apkUrl host=$host")
            return
        }

        val targetFile = File(context.cacheDir, "update-$latestCode.apk")
        try {
            targetFile.parentFile?.mkdirs()
            downloadToFile(apkUrl, targetFile)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Download failed for $apkUrl")
            postDownloadFailed(context, releaseNotesUrl)
            return
        }

        val actualSha = try { sha256OfFile(targetFile) } catch (e: Exception) {
            Timber.tag(TAG).w(e, "SHA-256 compute failed")
            targetFile.delete()
            postVerifyFailed(context, releaseNotesUrl)
            return
        }
        if (!actualSha.equals(apkSha256, ignoreCase = true)) {
            Timber.tag(TAG).w("SHA-256 mismatch expected=$apkSha256 actual=$actualSha")
            targetFile.delete()
            postVerifyFailed(context, releaseNotesUrl)
            return
        }

        if (!verifySigningCert(context, targetFile.absolutePath)) {
            Timber.tag(TAG).w("Signing cert mismatch")
            targetFile.delete()
            postVerifyFailed(context, releaseNotesUrl)
            return
        }

        postUpdateAvailable(context, latestName, latestCode, targetFile, releaseNotesUrl)
    }

    private suspend fun downloadToFile(url: String, dest: File) = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
        val request = Request.Builder().url(url).get().header("User-Agent", "Metrolist-Updater").build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code} for $url")
            val body = resp.body ?: throw java.io.IOException("Empty body for $url")
            body.byteStream().use { input ->
                FileOutputStream(dest).use { out ->
                    input.copyTo(out)
                }
            }
        }
    }

    private fun sha256OfFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256")
        return d.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun verifySigningCert(context: Context, apkPath: String): Boolean {
        return try {
            val pm = context.packageManager
            val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val installedShas = signingCertShas(installed).toSet()
            if (installedShas.isEmpty()) return false
            val downloaded = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
            val downloadedShas = signingCertShas(downloaded).toSet()
            if (downloadedShas.isEmpty()) return false
            downloadedShas.intersect(installedShas).isNotEmpty()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Signing cert verify failed")
            false
        }
    }

    private fun signingCertShas(info: android.content.pm.PackageInfo): List<String> {
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                val si = info.signingInfo ?: return emptyList()
                val list = mutableListOf<String>()
                si.apkContentsSigners?.forEach { sig -> list.add(sha256(sig.toByteArray())) }
                si.signingCertificateHistory?.forEach { sig -> list.add(sha256(sig.toByteArray())) }
                list
            } else {
                @Suppress("DEPRECATION")
                val sigs = info.signatures ?: return emptyList()
                sigs.map { sha256(it.toByteArray()) }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun postUpdateAvailable(
        context: Context,
        versionName: String,
        versionCode: Long,
        apkFile: File,
        releaseNotesUrl: String
    ) {
        createChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val apkUri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", apkFile)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "FileProvider failed, falling back to browser")
            postFallbackBrowser(context, releaseNotesUrl)
            return
        }

        val canInstall = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.packageManager.canRequestPackageInstalls()
            } else true
        } catch (_: Exception) { true }

        val installIntent = if (canInstall) {
            Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
        } else {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        val installPi = try {
            // Android 14+ BAL hardening: the creator of a PendingIntent must opt in to
            // allow the start to proceed. The recommended mode is ALLOW_IF_VISIBLE (API 36+),
            // which requires the sender (us, since the PI is fired in our own process from
            // a notification) to be visible at the moment of the start. Since the user just
            // tapped the notification, we are visible.
            val piFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 36) {
                val opts = android.app.ActivityOptions.makeBasic().apply {
                    setPendingIntentCreatorBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                    )
                }
                opts.toBundle()?.let { bundle ->
                    PendingIntent.getActivity(context, 0, installIntent, piFlags, bundle)
                } ?: PendingIntent.getActivity(context, 0, installIntent, piFlags)
            } else if (Build.VERSION.SDK_INT >= 34) {
                val opts = android.app.ActivityOptions.makeBasic().apply {
                    setPendingIntentCreatorBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }
                opts.toBundle()?.let { bundle ->
                    PendingIntent.getActivity(context, 0, installIntent, piFlags, bundle)
                } ?: PendingIntent.getActivity(context, 0, installIntent, piFlags)
            } else {
                PendingIntent.getActivity(context, 0, installIntent, piFlags)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "PendingIntent failed")
            return
        }

        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseNotesUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val browserPi = try {
            val browserFlags = PendingIntent.FLAG_IMMUTABLE
            if (Build.VERSION.SDK_INT >= 36) {
                val opts = android.app.ActivityOptions.makeBasic().apply {
                    setPendingIntentCreatorBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                    )
                }
                opts.toBundle()?.let { bundle ->
                    PendingIntent.getActivity(context, 1, browserIntent, browserFlags, bundle)
                } ?: PendingIntent.getActivity(context, 1, browserIntent, browserFlags)
            } else if (Build.VERSION.SDK_INT >= 34) {
                val opts = android.app.ActivityOptions.makeBasic().apply {
                    setPendingIntentCreatorBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }
                opts.toBundle()?.let { bundle ->
                    PendingIntent.getActivity(context, 1, browserIntent, browserFlags, bundle)
                } ?: PendingIntent.getActivity(context, 1, browserIntent, browserFlags)
            } else {
                PendingIntent.getActivity(context, 1, browserIntent, browserFlags)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Browser PendingIntent failed")
            return
        }

        val title = context.getString(R.string.app_update_available_title, versionName)
        val text = context.getString(R.string.app_update_available_text)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.music_note)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SYSTEM)
            .setAutoCancel(true)
            .setContentIntent(installPi)
            .addAction(R.drawable.download, context.getString(R.string.app_update_install_now), installPi)
            .addAction(R.drawable.share, context.getString(R.string.app_update_open_release), browserPi)
            .build()

        nm.notify(NOTIFICATION_ID_UPDATE, notification)
    }

    private fun postVerifyFailed(context: Context, releaseNotesUrl: String) {
        createChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseNotesUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(context, 2, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.music_note)
            .setContentTitle(context.getString(R.string.app_update_verify_failed_title))
            .setContentText(context.getString(R.string.app_update_verify_failed_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(NOTIFICATION_ID_VERIFY_FAILED, notification)
    }

    private fun postDownloadFailed(context: Context, releaseNotesUrl: String) {
        postVerifyFailed(context, releaseNotesUrl)
    }

    private fun postFallbackBrowser(context: Context, releaseNotesUrl: String) {
        if (releaseNotesUrl.isBlank()) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseNotesUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(context, 3, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.music_note)
            .setContentTitle(context.getString(R.string.app_update_download_failed_title))
            .setContentText(context.getString(R.string.app_update_open_release))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(NOTIFICATION_ID_VERIFY_FAILED, notification)
    }
}
