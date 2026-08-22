/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.social.SongSharingRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks playback progress for songs from the "To Listen" playlist.
 * Handles the 50% milestone notification and 100% auto-deletion.
 */
@Singleton
class PlaybackProgressTracker(
    private val songSharingRepository: SongSharingRepository,
    private val database: MusicDatabase,
    /**
     * Dispatcher the Room and Firestore lookups run on. Overridable so tests can drive the async
     * checks deterministically instead of racing a real background thread.
     */
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(
        songSharingRepository: SongSharingRepository,
        database: MusicDatabase,
    ) : this(songSharingRepository, database, Dispatchers.IO)

    private val TAG = "PlaybackProgressTracker"

    private var trackingJob: Job? = null
    var currentTrackingSongId: String? = null
    var currentSentSongId: String? = null
    private var lastFailedSongId: String? = null // Prevent hammering Firestore for non-shared songs
    private var has50PercentTriggered = false
    private var has100PercentTriggered = false
    private var maxProgressReached = 0f // Track maximum progress to handle seeking
    private var lastHeartbeatProgress = -1 // Track last 10% milestone for logging
    private val trackingInitialized = AtomicBoolean(false) // Atomic flag for initialization state

    /**
     * Start tracking when a media item transitions.
     */
    fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int, currentPlaylistId: String?) {
        // Cancel previous tracking
        stopTracking()

        // Always reset failure blacklist on a new song transition.
        // This ensures a fresh device (or role-switched device) gets a new chance to check Firestore.
        lastFailedSongId = null

        if (mediaItem == null) return

        val songId = mediaItem.mediaId
        Timber.tag(TAG).d("Media item transition: $songId from playlist: $currentPlaylistId")

        // Check if this song is from "To Listen" playlist
        checkAndStartTracking(songId, currentPlaylistId)
    }

    /**
     * Stop tracking when playback state changes to idle or ended.
     * Called manually from MusicService.
     */
    fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
            stopTracking()
            // Reset failure state when stopping to allow fresh start later
            lastFailedSongId = null
        }
    }

    /**
     * Check if a song is from the "To Listen" playlist and start tracking.
     */
    private fun checkAndStartTracking(songId: String, currentPlaylistId: String?) {
        Timber.tag(TAG).d("Context check: PlaylistId='$currentPlaylistId' | Expected='${PlaylistEntity.TO_LISTEN_PLAYLIST_ID}'")

        if (currentPlaylistId != PlaylistEntity.TO_LISTEN_PLAYLIST_ID) {
            Timber.tag(TAG).v("Not playing from To Listen playlist ($currentPlaylistId), skipping tracking.")
            return
        }

        if (songId == lastFailedSongId) {
            Timber.tag(TAG).v("Song $songId already failed check, skipping re-check.")
            return
        }

        Timber.tag(TAG).i("[checkAndStartTracking] Starting async check for song: $songId")

        trackingJob =
            CoroutineScope(ioDispatcher).launch {
                try {
                    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "NotLoggedIn"
                    Timber.tag(TAG).i("[User: $uid] Checking tracking for $songId in playlist $currentPlaylistId")

                    // Double-check: Verify song is actually in "To Listen" playlist locally
                    val isInToListenPlaylist =
                        database.checkInPlaylist(
                            PlaylistEntity.TO_LISTEN_PLAYLIST_ID,
                            songId,
                        ) > 0

                    Timber.tag(TAG).i("[Local DB Check] Song $songId in To Listen playlist: $isInToListenPlaylist")

                    if (!isInToListenPlaylist) {
                        Timber.tag(TAG).i("Song $songId not in local To Listen playlist, skipping track.")
                        lastFailedSongId = songId
                        return@launch
                    }

                    // Get the SentSong record from Firestore
                    val sentSong = songSharingRepository.getSentSongBySongId(songId)

                    if (sentSong == null) {
                        Timber.tag(TAG).i("No pending SentSong document found for $songId (User: $uid).")
                        lastFailedSongId = songId
                        return@launch
                    }

                    // Start tracking this song. Always start fresh for a new playback session.
                    currentTrackingSongId = songId
                    currentSentSongId = sentSong.id
                    lastFailedSongId = null
                    has50PercentTriggered = false
                    has100PercentTriggered = false
                    maxProgressReached = 0f
                    lastHeartbeatProgress = -1

                    Timber.tag(TAG).i("[Tracking Start] Song: '${sentSong.songTitle}' | Doc: ${sentSong.id} | Starting fresh tracking")
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error checking song for tracking")
                }
            }
    }

    /**
     * Track playback progress (called periodically by MusicService).
     */
    fun trackProgress(player: Player, scope: CoroutineScope, currentPlaylistId: String?) {
        val mediaId = player.currentMediaItem?.mediaId

        if (currentPlaylistId == PlaylistEntity.TO_LISTEN_PLAYLIST_ID) {
            Timber.tag(TAG).v("[trackProgress] Called | PlaylistId: $currentPlaylistId | MediaId: $mediaId | Tracking: ${currentTrackingSongId != null} | Playing: ${player.isPlaying}")
        }

        // If we ARE in the right playlist but tracking isn't active, try to (re)start it.
        if (currentTrackingSongId == null || currentSentSongId == null) {
            if (currentPlaylistId == PlaylistEntity.TO_LISTEN_PLAYLIST_ID && !trackingInitialized.get() && mediaId != null) {
                if (mediaId != lastFailedSongId) {
                    // Set flag BEFORE launching coroutine to prevent a race condition.
                    trackingInitialized.set(true)
                    Timber.tag(TAG).i("Tracking is inactive but we're in the right playlist. Attempting to start...")
                    scope.launch(ioDispatcher) {
                        try {
                            checkAndStartTracking(mediaId, currentPlaylistId)
                        } finally {
                            // Reset flag when done, but only if we didn't successfully initialize.
                            if (currentSentSongId == null) {
                                trackingInitialized.set(false)
                            }
                        }
                    }
                } else {
                    Timber.tag(TAG).v("[trackProgress] Skipping re-check for failed song: $mediaId")
                }
            } else if (currentPlaylistId == PlaylistEntity.TO_LISTEN_PLAYLIST_ID) {
                Timber.tag(TAG).v("[trackProgress] Not starting: trackingInitialized=${trackingInitialized.get()}, mediaId=$mediaId, lastFailed=$lastFailedSongId")
            }
            return
        }

        // Verify we are still on the same song.
        if (mediaId != currentTrackingSongId) {
            Timber.tag(TAG).i("Tracking mismatch: Player:$mediaId vs Tracker:$currentTrackingSongId. Stopping.")
            stopTracking()
            return
        }

        // Player MUST be accessed on the Main thread.
        try {
            val currentPosition = player.currentPosition
            val duration = player.duration

            if (duration <= 0) return // Wait for duration to load

            val progressPercent = (currentPosition.toFloat() / duration.toFloat()) * 100

            if (progressPercent > maxProgressReached) {
                maxProgressReached = progressPercent
            }

            // Heartbeat log every 10%.
            val intProgress = maxProgressReached.toInt()
            if (intProgress / 10 > lastHeartbeatProgress) {
                lastHeartbeatProgress = intProgress / 10
                Timber.tag(TAG).i("[Heartbeat] ${intProgress}% | Pos: ${currentPosition / 1000}s / ${duration / 1000}s | ID: $currentTrackingSongId | 50%Triggered: $has50PercentTriggered | 100%Triggered: $has100PercentTriggered")
            }

            // Milestone triggers (mutually exclusive).
            if (!has100PercentTriggered && maxProgressReached >= 95f) {
                has100PercentTriggered = true
                has50PercentTriggered = true // Ensure both are marked if 95% is reached quickly
                Timber.tag(TAG).i("95% Completion Triggered for $currentTrackingSongId")
                // Use an independent IO scope to avoid Main dispatcher issues.
                CoroutineScope(ioDispatcher).launch {
                    handle100PercentCompletion()
                }
            } else if (!has50PercentTriggered && maxProgressReached >= 50f) {
                has50PercentTriggered = true
                Timber.tag(TAG).i("50% Milestone Triggered for $currentTrackingSongId")
                CoroutineScope(ioDispatcher).launch {
                    handle50PercentMilestone()
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Tracking loop error")
        }
    }

    /**
     * Handle the 50% milestone - notify the sender.
     */
    private suspend fun handle50PercentMilestone() {
        var sentSongId = currentSentSongId
        val songId = currentTrackingSongId ?: return

        // If currentSentSongId is null, async initialization hasn't completed. Retry.
        if (sentSongId == null) {
            Timber.tag(TAG).w("[50% Handler] currentSentSongId is null, retrying initialization...")
            trackingInitialized.set(false)
            withContext(ioDispatcher) {
                checkAndStartTracking(songId, PlaylistEntity.TO_LISTEN_PLAYLIST_ID)
            }
            sentSongId = currentSentSongId
            if (sentSongId == null) {
                Timber.tag(TAG).e("[50% Handler] Retry failed, currentSentSongId still null")
                return
            }
            Timber.tag(TAG).i("[50% Handler] Retry succeeded, currentSentSongId: $sentSongId")
        }

        try {
            // Update Firestore directly using the document ID we have.
            songSharingRepository.markSongAsListened(sentSongId, songId)
            Timber.tag(TAG).i("[50% Handler] Firestore update completed successfully")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "[50% Handler] Error handling 50% milestone")
        }
    }

    /**
     * Handle the 100% completion - remove from the playlist.
     */
    private suspend fun handle100PercentCompletion() {
        var sentSongId = currentSentSongId
        val songId = currentTrackingSongId ?: return

        if (sentSongId == null) {
            Timber.tag(TAG).w("[100% Handler] currentSentSongId is null, retrying initialization...")
            trackingInitialized.set(false)
            withContext(ioDispatcher) {
                checkAndStartTracking(songId, PlaylistEntity.TO_LISTEN_PLAYLIST_ID)
            }
            sentSongId = currentSentSongId
            if (sentSongId == null) {
                Timber.tag(TAG).e("[100% Handler] Retry failed, currentSentSongId still null")
                stopTracking()
                return
            }
            Timber.tag(TAG).i("[100% Handler] Retry succeeded, currentSentSongId: $sentSongId")
        }

        try {
            // Always attempt local removal (idempotent - Room handles duplicates gracefully).
            songSharingRepository.markSongAsCompleted(sentSongId, songId)
            Timber.tag(TAG).i("[100% Handler] Firestore update and local removal completed successfully")

            stopTracking()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "[100% Handler] Error handling 100% completion")
            stopTracking()
        }
    }

    /**
     * Stop tracking the current song.
     */
    private fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        currentTrackingSongId = null
        currentSentSongId = null
        has50PercentTriggered = false
        has100PercentTriggered = false
        maxProgressReached = 0f
        lastHeartbeatProgress = -1
        trackingInitialized.set(false) // Reset initialization flag for next song
    }

    /**
     * Clean up resources.
     */
    fun cleanup() {
        stopTracking()
        lastFailedSongId = null
    }

    /**
     * Clear the blacklist when the user performs a seek operation.
     */
    fun onSeekPerformed() {
        Timber.tag(TAG).i("[Seek] User performed seek, clearing blacklist for song: $currentTrackingSongId")
        lastFailedSongId = null
        trackingInitialized.set(false)
    }

    /**
     * Clear the blacklist when the user restarts playback (replay).
     */
    fun onPlaybackRestarted() {
        Timber.tag(TAG).i("[Replay] User restarted playback, clearing blacklist for song: $currentTrackingSongId")
        lastFailedSongId = null
        trackingInitialized.set(false)
    }
}
