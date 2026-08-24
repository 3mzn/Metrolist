/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the background worker for song listened notifications.
 */
@Singleton
class SongListenedNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
) {
    private val workManager = WorkManager.getInstance(context)

    /**
     * Start the background worker. Called when the user logs in.
     */
    fun startWorker() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Timber.d("SongListenedNotifMgr", "User not logged in, not starting worker")
            return
        }

        Timber.d("SongListenedNotifMgr", "Starting background notification worker")

        // Only run when connected to the internet
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        // Periodic work request - runs every 15 minutes
        val workRequest =
            PeriodicWorkRequestBuilder<SongListenedNotificationWorker>(
                15,
                TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .build()

        // Enqueue work - keep existing if already scheduled
        workManager.enqueueUniquePeriodicWork(
            SongListenedNotificationWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest,
        )

        Timber.d("SongListenedNotifMgr", "Background worker scheduled")
    }

    /**
     * Stop the background worker. Called when the user logs out.
     */
    fun stopWorker() {
        Timber.d("SongListenedNotifMgr", "Stopping background notification worker")
        workManager.cancelUniqueWork(SongListenedNotificationWorker.WORK_NAME)
    }

    /**
     * Start the daily gentle-nudge worker. Same lifecycle as the listened worker: started on
     * login / app open, stopped on logout.
     */
    fun startNudgeWorker() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Timber.d("SongListenedNotifMgr", "User not logged in, not starting nudge worker")
            return
        }

        Timber.d("SongListenedNotifMgr", "Starting gentle nudge worker")

        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        val workRequest =
            PeriodicWorkRequestBuilder<GentleNudgeWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

        workManager.enqueueUniquePeriodicWork(
            GentleNudgeWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest,
        )

        Timber.d("SongListenedNotifMgr", "Gentle nudge worker scheduled")
    }

    /**
     * Stop the daily gentle-nudge worker. Called when the user logs out.
     */
    fun stopNudgeWorker() {
        Timber.d("SongListenedNotifMgr", "Stopping gentle nudge worker")
        workManager.cancelUniqueWork(GentleNudgeWorker.WORK_NAME)
    }

    /**
     * Start the 15-min LT-invite poll (dead-process safety net; live delivery is the
     * Firestore listener in InviteNotifier). Same lifecycle: started on login, stopped on
     * logout.
     */
    fun startInvitePollWorker() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Timber.d("SongListenedNotifMgr", "User not logged in, not starting invite poll worker")
            return
        }

        Timber.d("SongListenedNotifMgr", "Starting LT invite poll worker")

        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        val workRequest =
            PeriodicWorkRequestBuilder<InvitePollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

        workManager.enqueueUniquePeriodicWork(
            InvitePollWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest,
        )
    }

    /**
     * Stop the LT-invite poll. Called when the user logs out.
     */
    fun stopInvitePollWorker() {
        Timber.d("SongListenedNotifMgr", "Stopping LT invite poll worker")
        workManager.cancelUniqueWork(InvitePollWorker.WORK_NAME)
    }

    /**
     * Check if the worker is running.
     */
    fun isWorkerRunning(): Boolean {
        val workInfos =
            workManager.getWorkInfosForUniqueWork(
                SongListenedNotificationWorker.WORK_NAME,
            ).get()
        return workInfos.any { !it.state.isFinished }
    }

    /**
     * Manually trigger the worker to run immediately (for testing).
     */
    fun triggerWorkerNow() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Timber.d("SongListenedNotifMgr", "User not logged in, cannot trigger worker")
            return
        }

        Timber.d("SongListenedNotifMgr", "Manually triggering notification worker")

        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        val workRequest =
            androidx.work.OneTimeWorkRequestBuilder<SongListenedNotificationWorker>()
                .setConstraints(constraints)
                .build()

        workManager.enqueue(workRequest)
    }
}
