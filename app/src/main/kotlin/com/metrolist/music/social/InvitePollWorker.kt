/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.metrolist.music.utils.SongNotificationHelper
import com.metrolist.music.utils.dataStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Dead-process safety net for LT invites (SPEC_7 D13/D14).
 *
 * The LIVE delivery path is the Firestore listener in [InviteNotifier] — it delivers within
 * seconds whenever the app process is alive, which is the normal case (swiping the app away
 * does not kill the process). This worker only covers the rare case of a system-killed
 * process. 15 minutes is Android's hard floor for periodic WorkManager work — shorter
 * intervals are not possible, and force-stopped apps run no background work at all until
 * manually reopened.
 */
@HiltWorker
class InvitePollWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val inviteRepository: ListenTogetherInviteRepository,
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME = "lt_invite_poll_worker"

        private const val TAG = "InvitePoll"
    }

    override suspend fun doWork(): Result {
        if (auth.currentUser == null) {
            Timber.tag(TAG).d("Not logged in, skipping invite poll")
            return Result.success()
        }

        return try {
            val invite = fetchMyInvite()

            inviteRepository.cleanupExpiredInvites()

            val live = invite != null && invite.isPending() && !invite.isExpired()
            if (!live) {
                Timber.tag(TAG).d("No live invite present")
                return Result.success()
            }

            // The banner is showing in the foreground; it owns delivery. The
            // foreground->background transition in InviteNotifier handles later notification.
            if (AppForegroundTracker.isForeground) {
                Timber.tag(TAG).d("App in foreground, banner owns delivery")
                return Result.success()
            }

            // Notify once per invite (createdAt is the invite's identity).
            val notified = runCatching {
                context.dataStore.data.first()[ListenTogetherInviteRepository.LT_LAST_NOTIFIED_INVITE_CREATED_AT]
            }.getOrNull()
            if (notified == invite.createdAt) {
                Timber.tag(TAG).d("Already notified for this invite")
                return Result.success()
            }

            SongNotificationHelper.showInviteNotification(context, invite.fromName)
            runCatching {
                context.dataStore.edit { it[ListenTogetherInviteRepository.LT_LAST_NOTIFIED_INVITE_CREATED_AT] = invite.createdAt }
            }
            Timber.tag(TAG).d("Posted invite notification (poll)")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Invite poll failed")
            Result.retry()
        }
    }

    private suspend fun fetchMyInvite(): ListenTogetherInvite? {
        val myUid = auth.currentUser?.uid ?: return null
        val doc = withTimeoutOrNull(10_000) {
            firestore.collection("invites").document(myUid).get().await()
        } ?: return null
        return doc.takeIf { it.exists() }?.data?.let { ListenTogetherInvite.fromMap(it) }
    }
}
