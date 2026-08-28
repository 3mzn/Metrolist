/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.sync.ImportResult
import com.metrolist.music.sync.JsonTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@HiltViewModel
class JsonImportViewModel @Inject constructor(
    private val database: MusicDatabase,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _failedImports = MutableStateFlow<List<ImportResult.Failed>>(emptyList())
    val failedImports: StateFlow<List<ImportResult.Failed>> = _failedImports.asStateFlow()

    // Job tracking for cancellation
    private var currentImportJob: kotlinx.coroutines.Job? = null

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /**
     * Import playlist from JSON file.
     */
    fun startJsonImport(uri: Uri, playlistName: String) {
        // Cancel any existing import
        currentImportJob?.cancel()

        currentImportJob =
            viewModelScope.launch {
                try {
                    _syncState.value = SyncState.Syncing()
                    _statusText.value = "Reading JSON file..."
                    _failedImports.value = emptyList()

                    // Read and parse JSON
                    val tracks = parseJsonFile(uri)
                    if (tracks.isEmpty()) {
                        _syncState.value = SyncState.Error("No tracks found in JSON file")
                        return@launch
                    }

                    _statusText.value = "Found ${tracks.size} tracks in JSON"

                    // Find or create playlist
                    val playlistId = withContext(Dispatchers.IO) {
                        findOrCreatePlaylist(playlistName)
                    }

                    // Import tracks
                    performJsonImport(tracks, playlistId, playlistName)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    _syncState.value = SyncState.Cancelled
                    _statusText.value = "Import cancelled"
                    throw e // Re-throw to properly cancel coroutine
                } catch (e: SerializationException) {
                    _syncState.value = SyncState.Error("Invalid JSON format: ${e.message}")
                } catch (e: Exception) {
                    _syncState.value = SyncState.Error("Import failed: ${e.message}")
                }
            }
    }

    /**
     * Cancel an ongoing JSON import.
     */
    fun cancelJsonImport() {
        currentImportJob?.cancel()
        currentImportJob = null
        _syncState.value = SyncState.Cancelled
        _statusText.value = "Import cancelled"
    }

    /**
     * Parse JSON file from URI.
     */
    private suspend fun parseJsonFile(uri: Uri): List<JsonTrack> = withContext(Dispatchers.IO) {
        val inputStream: java.io.InputStream =
            context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Cannot open file")

        val jsonString = inputStream.bufferedReader().use { it.readText() }

        // Parse as array of tracks
        json.decodeFromString<List<JsonTrack>>(jsonString)
    }

    /**
     * Find an existing playlist by name or create a new one.
     */
    private suspend fun findOrCreatePlaylist(playlistName: String): String =
        withContext(Dispatchers.IO) {
            // Query all playlists and find by name
            val existingPlaylist = database.playlistsByNameAsc().firstOrNull()
                ?.find { it.playlist.name == playlistName }

            if (existingPlaylist != null) {
                _statusText.value = "Found existing playlist: $playlistName"
                existingPlaylist.id
            } else {
                _statusText.value = "Creating new playlist: $playlistName"
                val newPlaylist =
                    PlaylistEntity(
                        name = playlistName,
                        browseId = null,
                        bookmarkedAt = LocalDateTime.now(),
                        isEditable = true,
                        isLocal = true,
                    )
                database.query {
                    insert(newPlaylist)
                }
                newPlaylist.id
            }
        }

    /**
     * Import tracks and add to playlist with retry logic, duplicate detection, and error handling.
     * Parallelized: YouTube searches run concurrently with bounded concurrency (4) to cut wall-time
     * ~3-4x vs serial. DB check/insert is serialized via Mutex to avoid duplicate races.
     */
    private suspend fun performJsonImport(
        tracks: List<JsonTrack>,
        playlistId: String,
        playlistName: String,
    ) {
        withContext(Dispatchers.IO) {
            _statusText.value = "Matching songs with YouTube Music..."
            val totalSongs = tracks.size
            val failed = Collections.synchronizedList(mutableListOf<ImportResult.Failed>())
            val songsImported = AtomicInteger(0)
            val songsSkipped = AtomicInteger(0)
            val completed = AtomicInteger(0)
            val semaphore = Semaphore(4)
            val dbMutex = Mutex()

            coroutineScope {
                tracks.mapIndexed { index, track ->
                    async {
                        // Bounded concurrency — network is the bottleneck
                        semaphore.withPermit {
                            ensureActive()
                            // Cooperative cancel check for ViewModel job
                            if (currentImportJob?.isActive == false) {
                                ensureActive() // throws CancellationException
                            }
                            try {
                                // Progress/status (thread-safe; StateFlow is atomic)
                                _statusText.value = "Matching [${index + 1}/$totalSongs]: ${track.displayName()}"

                                // Parallel search (retains retry+500ms delay)
                                val matchedSong = matchJsonTrackWithRetry(track, maxAttempts = 2)

                                if (matchedSong != null) {
                                    // Serialize DB critical section to prevent duplicate race
                                    dbMutex.withLock {
                                        ensureActive()
                                        val isDuplicate = database.checkInPlaylist(playlistId, matchedSong.id) > 0
                                        if (isDuplicate) {
                                            songsSkipped.incrementAndGet()
                                        } else {
                                            val metadata = matchedSong.toMediaMetadata()
                                            val existing = database.song(matchedSong.id).firstOrNull()
                                            if (existing == null) {
                                                try {
                                                    database.insert(mediaMetadata = metadata)
                                                } catch (e: Exception) {
                                                    failed.add(ImportResult.Failed(track, "Database error: ${e.message}"))
                                                    return@withLock
                                                }
                                            }
                                            try {
                                                val playlist = database.playlist(playlistId).first()
                                                if (playlist != null) {
                                                    database.query {
                                                        addSongToPlaylist(playlist, listOf(matchedSong.id))
                                                    }
                                                    val imported = songsImported.incrementAndGet()
                                                    _syncState.value =
                                                        SyncState.Syncing(imported, totalSongs, matchedSong.title)
                                                } else {
                                                    failed.add(ImportResult.Failed(track, "Playlist not found"))
                                                }
                                            } catch (e: Exception) {
                                                failed.add(ImportResult.Failed(track, "Failed to add to playlist: ${e.message}"))
                                            }
                                        }
                                    }
                                } else {
                                    failed.add(ImportResult.Failed(track, "No match found on YouTube Music after retries"))
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failed.add(ImportResult.Failed(track, "Unexpected error: ${e.message}"))
                            } finally {
                                val done = completed.incrementAndGet()
                                _progress.value = done.toFloat() / totalSongs
                            }
                        }
                    }
                }.awaitAll()
            }

            _failedImports.value = failed.toList()
            _syncState.value = SyncState.Success

            // Build status message
            val parts = mutableListOf<String>()
            val imported = songsImported.get()
            val skipped = songsSkipped.get()
            if (imported > 0) parts.add("Added $imported songs")
            if (skipped > 0) parts.add("skipped $skipped duplicates")
            if (failed.size > 0) parts.add("${failed.size} failed")

            _statusText.value = "Import complete! ${parts.joinToString(", ")} to \"$playlistName\"."
        }
    }

    /**
     * Match a JSON track with YouTube Music with retry logic.
     */
    private suspend fun matchJsonTrackWithRetry(track: JsonTrack, maxAttempts: Int = 2): SongItem? {
        repeat(maxAttempts) { attempt ->
            try {
                val result = matchJsonTrack(track)
                if (result != null) {
                    return result
                }
                // If no result but no error, wait before retry
                if (attempt < maxAttempts - 1) {
                    kotlinx.coroutines.delay(500)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // Don't retry on cancellation
            } catch (e: Exception) {
                // Network or other error - retry if attempts remain
                if (attempt == maxAttempts - 1) {
                    return null
                }
                kotlinx.coroutines.delay(500)
            }
        }
        return null
    }

    /**
     * Match a JSON track with YouTube Music (single attempt).
     */
    private suspend fun matchJsonTrack(track: JsonTrack): SongItem? {
        val query = track.toSearchQuery()
        return YouTube.search(query, filter = YouTube.SearchFilter.FILTER_SONG).map { page ->
            page.items.filterIsInstance<SongItem>().firstOrNull()
        }.getOrNull()
    }

    /**
     * Clear failed imports list.
     */
    fun clearFailedImports() {
        _failedImports.value = emptyList()
    }

    sealed class SyncState {
        data object Idle : SyncState()
        data class Syncing(val current: Int = 0, val total: Int = 0, val lastSong: String = "") : SyncState()
        data object Success : SyncState()
        data object Cancelled : SyncState()
        data class Error(val message: String) : SyncState()
    }
}
