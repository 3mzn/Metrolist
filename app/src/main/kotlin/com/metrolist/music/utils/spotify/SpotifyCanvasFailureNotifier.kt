/*
 * Spotify Canvas support in Metrolist.
 *
 * Copyright (C) 2026 The Metrolist Group
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * ---------------------------------------------------------------------------
 * Spec 9.2's user-facing failure report: a high-priority notification.
 *
 * This replaced a planned auto-disable, by user decision. The spec originally said three
 * consecutive failures would switch the feature off until manually re-enabled. That was
 * replaced because it is the more surprising of the two behaviours, and because the Canvas
 * preference switch (Phase 7) already gives the user a manual kill-switch. Telling them
 * what went wrong is the smaller claim to make on their behalf.
 *
 * **One notification per outage, not one per attempt.** The trigger is
 * `failures == SPOTIFY_CANVAS_FAILURE_LIMIT` exactly, in SpotifyCanvasRepository: the
 * counter increments once per failure and resets on any successful answer, so the threshold
 * is crossed once per run of failures and re-armed afterwards.
 * ---------------------------------------------------------------------------
 */

package com.metrolist.music.utils.spotify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import timber.log.Timber

private const val TAG = "SpotifyCanvas"

/** Stable, so a repeat run of the same failure updates the existing notification. */
private const val CHANNEL_ID = "spotify_canvas_failure"
private const val NOTIFICATION_ID = 7331

/**
 * Posts the failure notification. Never throws and never blocks the caller.
 *
 * A notification is not guaranteed to be seen: the user can deny `POST_NOTIFICATIONS`, or
 * silence the channel, and in both cases posting is at best a no-op. So every call is wrapped —
 * a failure to report a failure must not become a crash on the gesture path.
 */
internal object SpotifyCanvasFailureNotifier {

    fun notify(context: Context, cause: FailureCause) {
        runCatching {
            ensureChannel(context)

            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // Nothing can be shown. Logging is the only honest option — and it means the
                // symptom ("Canvas never appears") has no explanation attached to it, which is
                // exactly what this notification exists to prevent.
                Timber.tag(TAG).w("no POST_NOTIFICATIONS permission; cannot report $cause")
                return
            }

            val messageRes =
                when (cause) {
                    FailureCause.AUTH_EXPIRED -> R.string.canvas_error_auth_expired
                    FailureCause.UNREACHABLE -> R.string.canvas_error_unreachable
                    FailureCause.SERVER_ERROR -> R.string.canvas_error_server
                }

            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.small_icon)
                    .setContentTitle(context.getString(R.string.canvas_feature_name))
                    .setContentText(context.getString(messageRes))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(messageRes)))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .setAutoCancel(true)
                    .setContentIntent(openAppIntent(context))
                    .build()

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }.onFailure {
            Timber.tag(TAG).w(it, "could not post the Spotify Canvas failure notification")
        }
    }

    /**
     * IMPORTANCE_HIGH so it heads-up and is not silently dropped, which is the point: this
     * reports a feature that has stopped working.
     *
     * `createNotificationChannel` is a no-op for an existing channel, so it is safe to call on
     * every post. It is *not* safe to change IMPORTANCE afterwards — Android keeps the user's
     * choice — so the value here is the first one that takes effect.
     */
    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.canvas_failure_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.canvas_failure_channel_description)
            },
        )
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
