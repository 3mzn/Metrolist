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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
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

    // ---------------------------------------------------------------- concurrency machinery

    /** Upper bound on simultaneous YouTube Music searches. Kept modest to stay a polite client. */
    private companion object {
        const val MAX_CONCURRENT_SEARCHES = 5
        const val MATCH_RETRY_DELAY_MS = 750L
        const val MAX_ADAPTIVE_DELAY_MS = 4_000L
        const val INSERT_CHUNK_SIZE = 25
    }

    /** Next track index to be claimed by a worker. */
    private val nextIndex = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Adaptive pause applied before each search. Rises when the backend pushes back (exceptions)
     * and decays after clean rounds, degrading gracefully toward sequential pacing instead of
     * tripping YouTube's bot detection.
     */
    private val adaptiveDelayMs = java.util.concurrent.atomic.AtomicLong(0)

    /** Results slot per track index — keeps output ordered regardless of completion order. */
    private lateinit var matchResults: Array<SongItem?>

    /** In-run memoization: identical search queries inside one file share a single lookup. */
    private val searchCache =
        java.util.concurrent.ConcurrentHashMap<String, SongItem?>()

    /** Thread-safe failure collection, re-sorted by original position when the import ends. */
    private val failedQueue =
        java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, ImportResult.Failed>>()

    /** Tracks claimed/completed work for progress reporting. */
    private val processedCount = java.util.concurrent.atomic.AtomicInteger(0)

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
     * Import tracks: bounded-concurrent YouTube matching first (results land in JSON order via
     * indexed slots), then a single batched Room commit that inserts library rows, appends to the
     * playlist and skips duplicates — one disk sync per chunk instead of one per track.
     *
     * The adaptive delay rises whenever the backend pushes back and decays on clean rounds,
     * degrading gracefully toward sequential pacing instead of tripping bot detection.
     */
    private suspend fun performJsonImport(
        tracks: List<JsonTrack>,
        playlistId: String,
        playlistName: String,
    ) {
        withContext(Dispatchers.IO) {
            _statusText.value = "Matching songs with YouTube Music..."
            val total = tracks.size
            matchResults = arrayOfNulls(total)
            nextIndex.set(0)
            adaptiveDelayMs.set(0)
            failedQueue.clear()
            searchCache.clear()

            val workerCount = minOf(MAX_CONCURRENT_SEARCHES, total)

            // ---- Phase A: bounded-concurrent matching ----
            repeat(workerCount) {
                launch {
                    while (true) {
                        currentCoroutineContext().ensureActive()

                        val index = nextIndex.getAndIncrement()
                        if (index >= total) return@launch
                        val track = tracks[index]

                        try {
                            val query = track.toSearchQuery()

                            // Adaptive pause before hitting the network.
                            val pauseMs = adaptiveDelayMs.get()
                            if (pauseMs > 0) {
                                kotlinx.coroutines.delay(kotlin.random.Random.nextLong(0, pauseMs))
                                currentCoroutineContext().ensureActive()
                            }

                            // In-run memoization: identical queries share one lookup.
                            // (Only successful matches are cached — ConcurrentHashMap
                            // cannot hold null values.)
                            val queryCached = searchCache.containsKey(query)
                            val matched =
                                if (queryCached) {
                                    searchCache[query]
                                } else {
                                    val fresh = matchJsonTrackWithRetry(track)
                                    if (fresh != null) searchCache[query] = fresh
                                    fresh
                                }

                            if (matched != null) {
                                matchResults[index] = matched
                            } else {
                                failedQueue.add(
                                    index to
                                        ImportResult.Failed(
                                            track,
                                            "No match found on YouTube Music after retries",
                                        ),
                                )
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            adaptiveDelayMs.updateAndGet { current ->
                                minOf(current + 500, MAX_ADAPTIVE_DELAY_MS)
                            }
                            failedQueue.add(
                                index to ImportResult.Failed(track, "Search error: ${e.message}"),
                            )
                        } finally {
                            val processed = processedCount.incrementAndGet()
                            _progress.value = processed.toFloat() / total * 0.8f
                            _statusText.value = "Matching [${processed + 1}/$total]: ${track.displayName()}"
                        }
                    }
                }
            }

            // ---- Phase B: batched insertion in original JSON order ----
            val matchedEntries =
                buildList {
                    for (index in 0 until total) {
                        matchResults[index]?.let { song -> add(index to song) }
                    }
                }

            var songsImported = 0
            var songsSkipped = 0

            val playlist = database.playlistBlocking(playlistId)
            if (playlist == null) {
                _failedImports.value = failedQueue.toList().sortedBy { it.first }.map { it.second }
                _syncState.value = SyncState.Error("Playlist vanished during import")
                return@withContext
            }

            database.transaction {
                matchedEntries.forEachIndexed { position, (_, songItem) ->
                    if (database.checkInPlaylist(playlistId, songItem.id) > 0) {
                        songsSkipped++
                    } else {
                        if (database.getSongByIdBlocking(songItem.id) == null) {
                            database.insert(songItem.toMediaMetadata())
                        }
                        database.addSongToPlaylist(playlist, listOf(songItem.id))
                        songsImported++
                    }

                    if ((position + 1) % INSERT_CHUNK_SIZE == 0) {
                        _progress.value = 0.8f + 0.2f * ((position + 1).toFloat() / matchedEntries.size)
                        _statusText.value = "Adding [${position + 1}/${matchedEntries.size}]..."
                    }
                }
            }

            val failed =
                failedQueue.toList().sortedBy { it.first }.map { it.second }
            _failedImports.value = failed
            _progress.value = 1f
            _syncState.value = SyncState.Success

            val parts = mutableListOf<String>()
            if (songsImported > 0) parts.add("Added $songsImported songs")
            if (songsSkipped > 0) parts.add("skipped $songsSkipped duplicates")
            if (failed.isNotEmpty()) parts.add("${failed.size} failed")

            _statusText.value = "Import complete! ${parts.joinToString(", ")} to \"$playlistName\"."
        }
    }

    /**
     * Match a JSON track with YouTube Music with retry logic.
     *
     * Exceptions propagate to the caller (feeding the adaptive delay and failure list) — only a
     * clean "no results" response returns null, so throttling never masquerades as a bad match.
     */
    private suspend fun matchJsonTrackWithRetry(track: JsonTrack, maxAttempts: Int = 2): SongItem? {
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                val result = matchJsonTrack(track)
                // Clean round: let the adaptive pause decay.
                adaptiveDelayMs.updateAndGet { current -> current / 2 }
                return result
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // Don't retry on cancellation
            } catch (e: Exception) {
                lastError = e
                // Network or other error - push back before retrying if attempts remain
                adaptiveDelayMs.updateAndGet { current ->
                    minOf(current + 500, MAX_ADAPTIVE_DELAY_MS)
                }
                if (attempt < maxAttempts - 1) {
                    kotlinx.coroutines.delay(MATCH_RETRY_DELAY_MS)
                }
            }
        }
        throw lastError ?: Exception("Search failed")
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
