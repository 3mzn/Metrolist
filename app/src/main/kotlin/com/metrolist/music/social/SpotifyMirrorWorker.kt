/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Spotify mirror intake worker (SPEC_SPOTIFY_MIRROR Phase 2).
 *
 * Closed-path pull: consumes server-stored pending rows for every linked playlist.
 * Mirrors the InvitePollWorker pattern (network constraint, retry on failure).
 * The FCM wake path enqueues expedited one-time work of this same class.
 */
@HiltWorker
class SpotifyMirrorWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: SpotifyMirrorRepository,
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME = "spotify_mirror_worker"

        private const val TAG = "SpotifyMirrorWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            repository.debugBootstrapIfNeeded()
            repository.intakeAll()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Mirror intake failed")
            Result.retry()
        }
    }
}
