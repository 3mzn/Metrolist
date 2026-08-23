/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.metrolist.music.utils.SongNotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton service for real-time "friend listened" notifications.
 * This runs alongside the app and monitors Firestore for songs that have been listened to.
 */
@Singleton
class SongListenedRealTimeNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songSharingRepository: SongSharingRepository,
    private val auth: FirebaseAuth,
    private val notificationManager: SongListenedNotificationManager,
    private val partnerResolver: PartnerResolver,
) {
    private var listenerJob: Job? = null
    private var currentUserId: String? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null

    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        // Monitor Firebase Auth state changes
        authListener =
            FirebaseAuth.AuthStateListener { firebaseAuth ->
                val newUserId = firebaseAuth.currentUser?.uid

                if (newUserId != currentUserId) {
                    currentUserId = newUserId

                    if (newUserId != null) {
                        Timber.d("SongListenedRealTime", "User logged in: $newUserId - starting notification listener")
                        startListening()
                        notificationManager.startWorker()
                    } else {
                        Timber.d("SongListenedRealTime", "User logged out - stopping notification listener")
                        stopListening()
                        notificationManager.stopWorker()
                    }
                }
            }.also {
                auth.addAuthStateListener(it)
            }
    }

    /**
     * Start listening for songs that have been listened to.
     */
    private fun startListening() {
        // Cancel any existing listener
        listenerJob?.cancel()

        val currentUid = currentUserId ?: return
        val thirtyDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)

        listenerJob =
            scope.launch {
                try {
                    songSharingRepository.observeListenedSongsNeedingNotification(
                        fromUid = currentUid,
                        since = thirtyDaysAgo,
                    ).collect { songs ->
                        // Only notify for songs listened to in the last 24 hours
                        val oneDayAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)

                        // One notification per SONG, not per document: a song sent more than once
                        // lives in several sentSongs documents, and reaching 50% marks every one
                        // of them, which would otherwise stack identical notifications.
                        val recentSongs =
                            songs.filter { song ->
                                song.listenedAt != null && song.listenedAt > oneDayAgo
                            }.distinctBy { it.songId }

                        // Show notification for each recent song. The partner name doubles as a
                        // personalized fallback when the sender's username field is empty.
                        recentSongs.forEach { sentSong ->
                            SongNotificationHelper.showNotification(
                                context,
                                sentSong,
                                partnerFallback = partnerResolver.identity.value.partnerName,
                            )
                            // Mark EVERY document for this song as notified — an unmarked twin
                            // would re-notify on the very next snapshot.
                            songs.filter { it.songId == sentSong.songId }.forEach { twin ->
                                songSharingRepository.markNotificationSent(twin.id)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Throwable, not Exception: a missing Firestore dependency surfaces as
                    // NoClassDefFoundError, which an Exception guard lets through to the default
                    // handler. Friend notifications are ancillary, so a failure here must degrade
                    // the social feature rather than take down playback.
                    Timber.e(e, "Real-time listened-song notifications disabled after a failure")
                }
            }
    }

    /**
     * Stop listening for notifications.
     */
    private fun stopListening() {
        listenerJob?.cancel()
        listenerJob = null
    }

    /**
     * Clean up when destroyed.
     */
    fun destroy() {
        authListener?.let { auth.removeAuthStateListener(it) }
        stopListening()
    }
}
