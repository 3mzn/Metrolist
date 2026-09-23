/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.services

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.metrolist.music.App
import com.metrolist.music.BuildConfig
import com.metrolist.music.R
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.metrolist.music.social.PartnerResolver
import com.metrolist.music.social.SpotifyMirrorWorker
import com.metrolist.music.update.AppUpdateNotifier
import com.metrolist.music.utils.SongNotificationHelper
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Firebase Cloud Messaging service for handling "song listened" notifications.
 */
class SongListenedMessagingService : FirebaseMessagingService() {

    companion object {
        // Notification data keys
        const val KEY_TYPE = "type"
        const val KEY_FRIEND_NAME = "friendName"
        const val KEY_SONG_TITLE = "songTitle"
        const val KEY_SENT_SONG_ID = "sentSongId"

        const val TYPE_SONG_LISTENED = "song_listened"
        const val TYPE_APP_UPDATE = "app_update"
        const val TYPE_MIRROR_WAKE = "mirror_wake"
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        Timber.d("SongListenedMessaging", "Message received from: ${message.from}")

        val data = message.data
        val type = data[KEY_TYPE]

        when (type) {
            TYPE_SONG_LISTENED -> handleSongListenedNotification(data)
            TYPE_MIRROR_WAKE -> handleMirrorWake()
            TYPE_APP_UPDATE -> {
                if (!BuildConfig.UPDATER_AVAILABLE) return
                val app = applicationContext as? App ?: return
                app.appUpdateScope.launch {
                    AppUpdateNotifier.handle(app, data)
                }
            }
            else -> Timber.w("SongListenedMessaging", "Unknown notification type: $type")
        }
    }

    /**
     * Handle "song listened" notification.
     */
    private fun handleSongListenedNotification(data: Map<String, String>) {
        // FCM payloads omit the sender name when it's empty; fall back to the cached partner
        // name so the notification always names a person, never "A friend".
        val friendName =
            data[KEY_FRIEND_NAME]
                ?: PartnerResolver.cachedPartnerNameBlocking(this)
                ?: getString(R.string.song_listened_fallback_friend)
        val songTitle = data[KEY_SONG_TITLE] ?: "your song"

        Timber.d("SongListenedMessaging", "Song listened notification: $friendName listened to $songTitle")

        // Show notification
        SongNotificationHelper.showNotification(
            this,
            friendName,
            songTitle,
        )
    }

    /**
     * Mirror wake (SPEC_SPOTIFY_MIRROR D9): the server found new rows. Enqueue an
     * expedited one-time intake; the periodic worker and on-start reconcile cover a
     * missed or dropped push, so nothing can strand.
     */
    private fun handleMirrorWake() {
        Timber.d("SongListenedMessaging", "Mirror wake received, enqueuing expedited intake")
        val request = OneTimeWorkRequestBuilder<SpotifyMirrorWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        WorkManager.getInstance(applicationContext).enqueue(request)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Timber.d("SongListenedMessaging", "New FCM token: $token")
    }
}
