/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.social.AddSongResult
import com.metrolist.music.social.SentSong
import com.metrolist.music.social.SongSharingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SongSharingViewModel @Inject constructor(
    private val songSharingRepository: SongSharingRepository,
    private val database: MusicDatabase,
    private val auth: FirebaseAuth,
) : ViewModel() {
    private val TAG = "SongSharingViewModel"

    private val _incomingSongs = MutableStateFlow<List<SentSong>>(emptyList())
    val incomingSongs: StateFlow<List<SentSong>> = _incomingSongs.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private var listenerJob: Job? = null

    private var currentUserId: String? = null

    // Track already processed documents to prevent redundant processing in the same session
    private val processedDocumentIds = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            try {
                // Create "To Listen" playlist if it doesn't exist
                songSharingRepository.initializeToListenPlaylist()

                // Track user ID to handle account switches
                val userId = auth.currentUser?.uid
                if (userId != currentUserId) {
                    currentUserId = userId
                    processedDocumentIds.clear()
                }

                songSharingRepository.observeIncomingSongs().collect { songs ->
                    _incomingSongs.value = songs

                    songs.forEach { sentSong ->
                        // Only process songs not yet handled in this session
                        if (!processedDocumentIds.contains(sentSong.id)) {
                            processedDocumentIds.add(sentSong.id)

                            viewModelScope.launch {
                                try {
                                    processSentSong(sentSong)
                                } catch (e: Exception) {
                                    Timber.e(e, "Failed to process song ${sentSong.songTitle}")
                                }
                            }
                        }
                    }
                }

                _isInitialized.value = true
            } catch (e: Exception) {
                Timber.e(e, "Error initializing song sharing feature")
            }
        }
    }

    /**
     * Stop listening for incoming songs.
     */
    private fun stopListening() {
        listenerJob?.cancel()
        listenerJob = null
        _incomingSongs.value = emptyList()
        _isInitialized.value = false
    }

    /**
     * Process a sent song - fetch metadata and add to "To Listen" playlist.
     * Includes retry logic for transient failures.
     */
    private suspend fun processSentSong(sentSong: SentSong) {
        try {
            val metadata = fetchSongMetadata(sentSong)

            if (metadata != null) {
                // Add to "To Listen" playlist with retry logic
                var retryCount = 0
                val maxRetries = 3
                var success = false

                while (retryCount < maxRetries && !success) {
                    try {
                        val result = songSharingRepository.addSongToToListenPlaylist(sentSong, metadata)

                        when (result) {
                            AddSongResult.SUCCESS -> {
                                Timber.d(TAG, "Successfully added ${sentSong.songTitle} to To Listen playlist")
                                success = true
                            }
                            AddSongResult.DUPLICATE -> {
                                Timber.d(TAG, "${sentSong.songTitle} already in playlist. Skipping duplicate.")
                                success = true // Treat as success to stop retrying
                            }
                            AddSongResult.ERROR -> throw Exception("Repository returned ERROR")
                        }
                    } catch (e: Exception) {
                        retryCount++
                        if (retryCount < maxRetries) {
                            val delayMs = 1000L * retryCount // Exponential backoff: 1s, 2s, 3s
                            delay(delayMs)
                        } else {
                            Timber.e(e, "Failed to add ${sentSong.songTitle} after $maxRetries attempts")
                        }
                    }
                }
            } else {
                Timber.d(TAG, "Could not fetch metadata for ${sentSong.songTitle}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error processing sent song ${sentSong.songTitle}")
        }
    }

    /**
     * Fetch song metadata from YouTube or create from SentSong data.
     */
    private fun fetchSongMetadata(sentSong: SentSong): MediaMetadata? =
        try {
            MediaMetadata(
                id = sentSong.songId,
                title = sentSong.songTitle,
                artists =
                    listOf(
                        MediaMetadata.Artist(
                            id = null,
                            name = sentSong.songArtist,
                        ),
                    ),
                duration = sentSong.songDuration,
                thumbnailUrl = sentSong.thumbnailUrl,
                album =
                    if (sentSong.albumName != null) {
                        MediaMetadata.Album(
                            id = sentSong.albumId ?: "",
                            title = sentSong.albumName,
                        )
                    } else {
                        null
                    },
            )
        } catch (e: Exception) {
            Timber.e(e, "Error fetching metadata for ${sentSong.songId}")
            null
        }
}
