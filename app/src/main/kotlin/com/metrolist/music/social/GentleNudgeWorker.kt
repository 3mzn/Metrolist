/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.metrolist.music.R
import com.metrolist.music.utils.SongNotificationHelper
import com.metrolist.music.utils.dataStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * The gentle nudge: once a day, softly remind BOTH sides about shared songs that have been
 * sitting unstarted for [STALE_AFTER_DAYS] days.
 *
 * Anti-nag rules (MAYBE_LATER #4), all enforced here:
 *  - at most ONE notification pair per day (DataStore epoch-day gate)
 *  - at most [SongSharingRepository.MAX_NUDGE_ROUNDS] rounds per song, ever (nudgeCount on the doc)
 *  - never while the partner is actively listening (status/{partnerUid} freshness)
 *  - never about a song that is playing on THIS device right now
 */
@HiltWorker
class GentleNudgeWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val songSharingRepository: SongSharingRepository,
    private val partnerResolver: PartnerResolver,
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME = "gentle_nudge_worker"
        const val STALE_AFTER_DAYS = 3L

        /** A status doc fresher than this means its owner is listening right now. */
        private const val PRESENCE_FRESH_MS = 2 * 60 * 1000L

        private val LAST_NUDGE_EPOCH_DAY = longPreferencesKey("last_nudge_epoch_day")
        private const val TAG = "GentleNudge"

        private fun todayEpochDay(): Long = System.currentTimeMillis() / TimeUnit.DAYS.toMillis(1)
    }

    override suspend fun doWork(): Result {
        if (auth.currentUser == null) {
            Timber.tag(TAG).d("Not logged in, skipping nudge check")
            return Result.success()
        }

        return try {
            // Hard daily gate: periodic WorkManager work can fire more than once a day after
            // reboots or constraint churn. Only set when a nudge actually went out, so a song
            // that crosses the staleness line later today can still be nudged.
            val today = todayEpochDay()
            val lastDay = runCatching {
                context.dataStore.data.first()[LAST_NUDGE_EPOCH_DAY]
            }.getOrNull()
            if (lastDay == today) {
                Timber.tag(TAG).d("Already nudged today, skipping")
                return Result.success()
            }

            if (isPartnerListeningNow()) {
                Timber.tag(TAG).d("Partner is actively listening, skipping nudge today")
                return Result.success()
            }

            val staleCutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(STALE_AFTER_DAYS)
            val playingSongId = currentlyPlayingSongId()

            // Full document lists (twins included) for marking; deduplicated per-song views for
            // display, with the currently-playing song excluded from both.
            val outgoingAll = songSharingRepository.getStaleSongs(fromMe = true, staleCutoff = staleCutoff)
            val incomingAll = songSharingRepository.getStaleSongs(fromMe = false, staleCutoff = staleCutoff)
            val outgoing = outgoingAll.distinctBy { it.songId }.filter { it.songId != playingSongId }
            val incoming = incomingAll.distinctBy { it.songId }.filter { it.songId != playingSongId }

            val partnerName = partnerResolver.identity.value.partnerName
                ?: context.getString(R.string.song_listened_fallback_friend)

            var nudged = false

            if (outgoing.isNotEmpty()) {
                val message =
                    if (outgoing.size == 1) {
                        context.getString(R.string.nudge_sender_single, partnerName, outgoing[0].songTitle)
                    } else {
                        context.getString(
                            R.string.nudge_sender_multi,
                            partnerName,
                            outgoing[0].songTitle,
                            outgoing.size - 1,
                        )
                    }
                SongNotificationHelper.showNudgeNotification(
                    context,
                    context.getString(R.string.nudge_sender_title, partnerName),
                    message,
                    isSenderNudge = true,
                )
                nudged = true
            }

            if (incoming.isNotEmpty()) {
                val message =
                    if (incoming.size == 1) {
                        context.getString(R.string.nudge_receiver_single, incoming[0].songTitle, partnerName)
                    } else {
                        context.getString(R.string.nudge_receiver_multi, incoming.size, partnerName)
                    }
                SongNotificationHelper.showNudgeNotification(
                    context,
                    context.getString(R.string.nudge_receiver_title),
                    message,
                    isSenderNudge = false,
                )
                nudged = true
            }

            if (nudged) {
                // Mark every document of every nudged song (twins included) so the per-song cap
                // is authoritative on the docs, not on this device. Songs excluded by the
                // playing check keep their budget untouched.
                val nudgedSongIds = (outgoing + incoming).map { it.songId }.toSet()
                songSharingRepository.markSongsNudged(
                    (outgoingAll + incomingAll).filter { it.songId in nudgedSongIds },
                )
                runCatching {
                    context.dataStore.edit { it[LAST_NUDGE_EPOCH_DAY] = today }
                }
                Timber.tag(TAG).d("Nudged: %d outgoing, %d incoming", outgoing.size, incoming.size)
            } else {
                Timber.tag(TAG).d("Nothing stale to nudge about")
            }

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Gentle nudge check failed")
            Result.retry()
        } catch (e: Throwable) {
            // Throwable, not just Exception: a missing Firestore dependency surfaces as
            // NoClassDefFoundError (e.g. in the :crash process), which an Exception guard lets
            // through to crash the process on every scheduled run. Fail permanently instead.
            Timber.tag(TAG).e(e, "Gentle nudge worker disabled after an unrecoverable failure")
            Result.failure()
        }
    }

    /** True when the partner's heartbeat status doc was updated within [PRESENCE_FRESH_MS]. */
    private suspend fun isPartnerListeningNow(): Boolean {
        val partnerUid = partnerResolver.awaitPartnerUid() ?: return false
        return try {
            val doc = withTimeoutOrNull(10_000) {
                firestore.collection("status").document(partnerUid).get().await()
            } ?: return false
            val updatedAt = doc.getLong("updatedAt") ?: return false
            System.currentTimeMillis() - updatedAt < PRESENCE_FRESH_MS
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Presence check failed, assuming partner is not listening")
            false
        }
    }

    /**
     * The song THIS device is broadcasting right now, if fresh. Depends on the heartbeat
     * privacy toggle being on; when off, the currently-playing exclusion silently doesn't
     * apply (rare, and only ever costs one extra nudge).
     */
    private suspend fun currentlyPlayingSongId(): String? {
        val myUid = auth.currentUser?.uid ?: return null
        return try {
            val doc = withTimeoutOrNull(10_000) {
                firestore.collection("status").document(myUid).get().await()
            } ?: return null
            val updatedAt = doc.getLong("updatedAt") ?: return null
            if (System.currentTimeMillis() - updatedAt >= PRESENCE_FRESH_MS) return null
            doc.getString("songId")?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Own-status check failed")
            null
        }
    }
}
