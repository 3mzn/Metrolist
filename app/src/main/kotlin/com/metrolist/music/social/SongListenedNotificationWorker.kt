/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.metrolist.music.utils.SongNotificationHelper
import com.metrolist.music.social.PartnerResolver
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Background worker that checks for songs listened to by friends.
 * Runs every 15 minutes when the app is closed.
 */
@HiltWorker
class SongListenedNotificationWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val songSharingRepository: SongSharingRepository,
    private val auth: FirebaseAuth,
    private val partnerResolver: PartnerResolver,
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME = "song_listened_notification_worker"
    }

    override suspend fun doWork(): Result {
        Timber.d("SongListenedWorker", "Worker started - checking for listened songs")

        // Check if user is logged in
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Timber.d("SongListenedWorker", "User not logged in, skipping check")
            return Result.success()
        }

        return try {
            // Get songs that have been listened to but not notified
            val thirtyDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
            val listenedSongs =
                songSharingRepository.getListenedSongsNeedingNotification(
                    fromUid = currentUser.uid,
                    since = thirtyDaysAgo,
                )

            // Only notify for songs listened to in the last 24 hours
            val oneDayAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
            val recentSongs =
                listenedSongs.filter { song ->
                    song.listenedAt != null && song.listenedAt > oneDayAgo
                }

            Timber.d("SongListenedWorker", "Showing ${recentSongs.size} recent songs (last 24h)")

            // One notification per SONG: repeated sends create multiple sentSongs documents for
            // the same track, and 50%-listened marks them all.
            recentSongs.distinctBy { it.songId }.forEach { sentSong ->
                SongNotificationHelper.showNotification(
                    context,
                    sentSong,
                    partnerFallback = partnerResolver.identity.value.partnerName,
                )
                // Mark every document for this song so unmarked twins don't re-notify later.
                listenedSongs.filter { it.songId == sentSong.songId }.forEach { twin ->
                    songSharingRepository.markNotificationSent(twin.id)
                }
            }

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Error checking for listened songs")
            // Transient (network, Firestore unavailable) - retry on the next run.
            Result.retry()
        } catch (e: Throwable) {
            // A linkage failure such as NoClassDefFoundError is not transient and would otherwise
            // escape doWork and crash the process on every scheduled run. Fail permanently instead.
            Timber.e(e, "Listened-song notification worker disabled after an unrecoverable failure")
            Result.failure()
        }
    }
}
