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

    const val NUDGE_CHANNEL_ID = "gentle_nudge_notifications"

    /**
     * Fixed ids: at most one nudge per direction per day by design, so repeats replace instead
     * of stacking. Sender and receiver nudges get distinct ids so a device with stale songs in
     * BOTH directions shows both notifications.
     */
    private const val NUDGE_SENDER_NOTIFICATION_ID = 2900
    private const val NUDGE_RECEIVER_NOTIFICATION_ID = 2901

    const val LT_INVITE_CHANNEL_ID = "lt_invites"

    /**
     * Fixed id: only one LT invite can be pending at a time, so repeats replace instead of
     * stacking.
     */
    private const val LT_INVITE_NOTIFICATION_ID = 2800

    /**
     * Heads-up channel for LT invites arriving while the app is not in the foreground.
     * Tapping routes straight into the join UI (no in-app banner) — see SPEC_7 D13.
     */
    fun showInviteNotification(
        context: Context,
        fromName: String,
    ) {
        createInviteChannel(context)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_LT_INVITE_TAP, true)
            }

        val pendingIntent =
            PendingIntent.getActivity(
                context,
                2,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val title = context.getString(R.string.lt_invite_notification_title, fromName)
        val message = context.getString(R.string.lt_invite_notification_body, fromName)

        val notification =
            NotificationCompat.Builder(context, LT_INVITE_CHANNEL_ID)
                .setSmallIcon(R.drawable.music_note)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

        notificationManager.notify(LT_INVITE_NOTIFICATION_ID, notification)
    }
    /**
     * Removes the LT-invite notification from the shade � called when the in-app banner
     * takes over delivery (app foregrounded) or the invite is consumed, so the two
     * channels never double-deliver the same invite.
     */
    fun cancelInviteNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(LT_INVITE_NOTIFICATION_ID)
    }

    private fun createInviteChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.lt_invites_channel_name)
            val descriptionText = context.getString(R.string.lt_invites_channel_description)
            val channel = NotificationChannel(
                LT_INVITE_CHANNEL_ID,
                name,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = descriptionText
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showNotification(
        context: Context,
        sentSong: SentSong,
        partnerFallback: String? = null,
    ) {
        showNotification(
            context,
            // "listened" events describe what MY PARTNER did — they are the listener of every
            // song I sent. Their name therefore leads; fromUsername only covers a session where
            // the resolver hasn't resolved yet.
            partnerFallback
                ?: sentSong.fromUsername.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.song_listened_fallback_friend),
            sentSong.songTitle.ifEmpty { context.getString(R.string.song_listened_fallback_song) },
            sentSong.id.hashCode(),
        )
    }

    fun showNotification(
        context: Context,
        friendName: String,
        songTitle: String,
        // Deterministic per (friend, song): a duplicate delivery for the same song replaces the
        // existing notification instead of stacking a second identical one.
        uniqueId: Int = "$friendName|$songTitle".hashCode(),
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

    /**
     * The gentle nudge is deliberately its own low-importance channel: it should feel soft and
     * be independently mutable, never sharing urgency with "friend listened" events.
     */
    fun showNudgeNotification(
        context: Context,
        title: String,
        message: String,
        isSenderNudge: Boolean,
    ) {
        createNudgeChannel(context)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("navigate_to", "social")
            }

        val pendingIntent =
            PendingIntent.getActivity(
                context,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat.Builder(context, NUDGE_CHANNEL_ID)
                .setSmallIcon(R.drawable.music_note)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

        notificationManager.notify(
            if (isSenderNudge) NUDGE_SENDER_NOTIFICATION_ID else NUDGE_RECEIVER_NOTIFICATION_ID,
            notification,
        )
    }

    private fun createNudgeChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.gentle_nudge_channel_name)
            val descriptionText = context.getString(R.string.gentle_nudge_channel_description)
            val channel = NotificationChannel(
                NUDGE_CHANNEL_ID,
                name,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = descriptionText
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
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
