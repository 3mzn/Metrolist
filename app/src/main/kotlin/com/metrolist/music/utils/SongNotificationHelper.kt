/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.social.SentSong

object SongNotificationHelper {
    const val CHANNEL_ID = "song_listened_notifications"
    const val NOTIFICATION_ID_BASE = 3000

    fun showNotification(context: Context, sentSong: SentSong) {
        showNotification(
            context,
            sentSong.fromUsername.ifEmpty { context.getString(R.string.song_listened_fallback_friend) },
            sentSong.songTitle.ifEmpty { context.getString(R.string.song_listened_fallback_song) },
            sentSong.id.hashCode(),
        )
    }

    fun showNotification(
        context: Context,
        friendName: String,
        songTitle: String,
        uniqueId: Int = System.currentTimeMillis().toInt(),
    ) {
        createNotificationChannel(context)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create intent to open Social screen
        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("navigate_to", "social")
            }

        val pendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val title = context.getString(R.string.friend_listened_notification_title)
        val message = context.getString(R.string.friend_listened_to_song_message, friendName, songTitle)

        // Build notification
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.music_note)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

        // Show notification with unique ID
        notificationManager.notify(
            NOTIFICATION_ID_BASE + uniqueId,
            notification,
        )
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.song_listened_channel_name)
            val descriptionText = context.getString(R.string.song_listened_channel_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
