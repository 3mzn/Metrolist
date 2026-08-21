/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.metrolist.music.utils.SongNotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
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
                        val recentSongs =
                            songs.filter { song ->
                                song.listenedAt != null && song.listenedAt > oneDayAgo
                            }

                        // Show notification for each recent song
                        recentSongs.forEach { sentSong ->
                            SongNotificationHelper.showNotification(context, sentSong)
                            // Mark as notified
                            songSharingRepository.markNotificationSent(sentSong.id)
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error in notification listener")
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
