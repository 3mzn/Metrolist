/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import android.content.Context
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.media3.exoplayer.offline.DownloadService
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.BuildConfig
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.ExoDownloadService
import com.metrolist.music.sync.JsonTrack
import com.metrolist.music.utils.SongNotificationHelper
import com.metrolist.music.utils.YoutubeMatcher
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject
import timber.log.Timber

/**
 * Spotify auto-mirror intake (SPEC_SPOTIFY_MIRROR Phase 2).
 *
 * The server (Edge Function `poll-spotify`) owns all Spotify traffic. This repository:
 *  - stores per-playlist links (`localPlaylistId -> {source_id, spotify_url, mode}`),
 *  - pulls pending rows (closed path) or invokes the function directly and takes the
 *    rows from the response (alive path),
 *  - matches title+artist via [YoutubeMatcher] (D3: everything that matches lands),
 *  - inserts missing songs (idempotent via `checkInPlaylist`), enqueues downloads
 *    (existing rules, D4+D5), marks rows done (**update, never delete** — the server
 *    diffs against all known URIs, so deleting would re-mirror on the next change),
 *  - notifies per batch (D6).
 *
 * Two devices consuming the same rows converge: inserts and done-marks are idempotent.
 */
@Singleton
class SpotifyMirrorRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val youtubeMatcher: YoutubeMatcher,
) {
    @Serializable
    data class MirrorRow(
        val id: String? = null,
        val source_id: String = "",
        val spotify_id: String,
        val title: String,
        val artist: String,
        val duration_ms: Int? = null,
    )

    @Serializable
    private data class DirectResult(
        val added: List<MirrorRow> = emptyList(),
        val remaining: Int = 0,
    )

    @Serializable
    private data class DirectResponse(
        val ok: Boolean = false,
        val sources: Map<String, DirectResult> = emptyMap(),
    )

    @Serializable
    private data class ProbeResponse(
        val ok: Boolean = false,
        val total: Int? = null,
    )

    data class MirrorLink(
        val sourceId: String,
        val spotifyUrl: String,
        val mode: String, // "backfill" | "future"
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        // F2: hung sockets must fail fast, never stall the intake mutex forever.
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }

    private val linksKey = stringPreferencesKey("mirror_links")
    private val workManager by lazy { WorkManager.getInstance(context) }
    private val aliveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var aliveJob: Job? = null

    /**
     * Single-flight gate: worker, alive loop, and pull-to-refresh all funnel through
     * intake, and only one runs at a time. Without this, overlapping intakes insert
     * interleaved subsets and scramble playlist order — and redo each other's matches.
     */
    private val intakeMutex = Mutex()

    /** Bounded parallelism for YouTube matching (network-bound; inserts stay ordered). */
    private val matchSemaphore = Semaphore(6)

    /** True while a tracked playlist screen is open (Phase 3 drives this). */
    @Volatile
    var screenOpen: Boolean = false

    /** Live link map for UI (menu items, badges). */
    val linksFlow: StateFlow<Map<String, MirrorLink>> = context.dataStore.data
        .map { prefs -> parseLinks(prefs[linksKey].orEmpty()) }
        .stateIn(aliveScope, SharingStarted.Eagerly, emptyMap())

    private fun supabaseHeaders(): Map<String, String> =
        mapOf(
            "apikey" to BuildConfig.SUPABASE_ANON_KEY,
            "Authorization" to "Bearer ${BuildConfig.SUPABASE_ANON_KEY}",
        )

    // ── Links ─────────────────────────────────────────────────────────────────────────────

    suspend fun links(): Map<String, MirrorLink> {
        val raw = context.dataStore.data.map { it[linksKey].orEmpty() }.first()
        return parseLinks(raw)
    }

    private fun parseLinks(raw: String): Map<String, MirrorLink> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { id ->
                val l = obj.getJSONObject(id)
                MirrorLink(l.getString("source_id"), l.getString("spotify_url"), l.getString("mode"))
            }
        }.getOrDefault(emptyMap())
    }

    private suspend fun saveLinks(links: Map<String, MirrorLink>) {
        context.dataStore.edit { prefs ->
            prefs[linksKey] = JSONObject().apply {
                links.forEach { (id, l) ->
                    put(id, JSONObject().apply {
                        put("source_id", l.sourceId)
                        put("spotify_url", l.spotifyUrl)
                        put("mode", l.mode)
                    })
                }
            }.toString()
        }
    }

    /**
     * Link a local playlist to a public Spotify playlist URL. Creates the server source
     * row if needed. Future-only mode seeds current URIs as done (bounded loop) so only
     * later additions arrive; backfill mode needs no seeding (missing songs insert,
     * present ones skip via checkInPlaylist).
     */
    suspend fun linkPlaylist(localPlaylistId: String, spotifyUrl: String, mode: String): Result<Unit> =
        runCatching {
            val spotifyId = parseSpotifyId(spotifyUrl)
                ?: throw IllegalArgumentException("Not a Spotify playlist link")
            val sourceId = getOrCreateSource(spotifyId)
            // F1: hold the intake mutex across link+seed/backfill so alive ticks can't
            // interleave inserts (order) or slip pre-existing songs into future-only.
            intakeMutex.withLock {
                val updated = links().toMutableMap()
                updated[localPlaylistId] = MirrorLink(sourceId, spotifyUrl, mode)
                saveLinks(updated)
                // Consumption is tracked per-device (consumed set below): a relink must
                // re-evaluate from scratch, so reset first, then fill per mode.
                saveConsumed(sourceId, emptySet())
                if (mode == MODE_FUTURE) seedFutureOnly(sourceId)
                else backfillFromServer(sourceId, localPlaylistId)
            }
            Timber.tag(TAG).d("Linked $localPlaylistId -> $spotifyId ($mode)")
        }

    suspend fun untrack(localPlaylistId: String) {
        val updated = links().toMutableMap()
        val removed = updated.remove(localPlaylistId)
        saveLinks(updated)
        if (removed != null) {
            // Drop local consumption state; a later re-track re-seeds per its mode.
            saveConsumed(removed.sourceId, emptySet())
        }
    }

    private fun parseSpotifyId(url: String): String? =
        Regex("""open\.spotify\.com/playlist/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)

    private suspend fun getOrCreateSource(spotifyId: String): String {
        val existing: List<Map<String, String>> = http.get(
            "${BuildConfig.SUPABASE_URL}/rest/v1/mirror_sources?spotify_id=eq.$spotifyId&select=id",
        ) {
            supabaseHeaders().forEach { (k, v) -> header(k, v) }
        }.body()
        existing.firstOrNull()?.get("id")?.let { return it }
        // F4: two phones linking the same URL at once race here — a 409 means the
        // other side won; re-read instead of failing the whole link.
        try {
            http.post("${BuildConfig.SUPABASE_URL}/rest/v1/mirror_sources") {
                supabaseHeaders().forEach { (k, v) -> header(k, v) }
                contentType(ContentType.Application.Json)
                setBody(mapOf("spotify_id" to spotifyId))
            }
        } catch (e: io.ktor.client.plugins.ClientRequestException) {
            if (e.response.status != io.ktor.http.HttpStatusCode.Conflict) throw e
            Timber.tag(TAG).d("Source insert raced (409), re-reading")
        }
        return http.get(
            "${BuildConfig.SUPABASE_URL}/rest/v1/mirror_sources?spotify_id=eq.$spotifyId&select=id",
        ) {
            supabaseHeaders().forEach { (k, v) -> header(k, v) }
        }.body<List<Map<String, String>>>().firstOrNull()?.get("id")
            ?: throw IllegalStateException("Source row not visible after insert")
    }

    /**
     * Seed future-only: everything the server knows RIGHT NOW counts as "already there".
     * Base is a pull of all known rows (instant, exact for the normal fully-resolved case);
     * then a bounded direct loop catches anything resolving concurrently (mid-backfill
     * links). The set is ALWAYS saved, even if every poll comes back empty — an empty
     * save on failure would flood the playlist on the next intake.
     */
    private suspend fun seedFutureOnly(sourceId: String) {
        val seen = pullAll(listOf(sourceId))[sourceId].orEmpty().map { it.spotify_id }.toMutableSet()
        for (i in 0 until 20) {
            val added = directInvoke(listOf(sourceId))[sourceId].orEmpty()
            if (added.isEmpty()) break
            added.forEach { seen += it.spotify_id }
        }
        saveConsumed(sourceId, seen)
    }

    /** Backfill: pull every known row and insert what's missing locally, in order. */
    private suspend fun backfillFromServer(sourceId: String, localPlaylistId: String) {
        val rows = mutableListOf<MirrorRow>()
        var offset = 0
        while (true) {
            val page: List<MirrorRow> = try {
                http.get(
                    "${BuildConfig.SUPABASE_URL}/rest/v1/mirror_tracks" +
                        "?source_id=eq.$sourceId&select=id,source_id,spotify_id,title,artist,duration_ms" +
                        "&order=created_at&limit=1000&offset=$offset",
                ) {
                    supabaseHeaders().forEach { (k, v) -> header(k, v) }
                    accept(ContentType.Application.Json)
                }.body()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Backfill pull failed")
                return
            }
            rows += page
            if (page.size < 1000) break
            offset += page.size
        }
        intakeRows(localPlaylistId, sourceId, rows)
    }

    /**
     * Per-device consumption state: which Spotify ids THIS phone already handled.
     * Global done-marking cannot work (two phones share rows — one phone's done would
     * starve the other), so each device tracks its own set and filters pulls locally.
     */
    private fun consumedKey(sourceId: String) = stringPreferencesKey("mirror_consumed_$sourceId")

    private suspend fun consumed(sourceId: String): Set<String> {
        val raw = context.dataStore.data.map { it[consumedKey(sourceId)].orEmpty() }.first()
        if (raw.isBlank()) return emptySet()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            buildSet { repeat(arr.length()) { add(arr.getString(it)) } }
        }.getOrDefault(emptySet())
    }

    private suspend fun saveConsumed(sourceId: String, ids: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[consumedKey(sourceId)] = org.json.JSONArray().apply { ids.forEach(::put) }.toString()
        }
    }

    // ── Intake ────────────────────────────────────────────────────────────────────────────

    /** All known rows for these sources (consumption is filtered per-device in intakeRows). */
    private suspend fun pullAll(sourceIds: List<String>): Map<String, List<MirrorRow>> {
        if (sourceIds.isEmpty()) return emptyMap()
        return try {
            val rows: List<MirrorRow> = http.get(
                "${BuildConfig.SUPABASE_URL}/rest/v1/mirror_tracks" +
                    "?source_id=in.(${sourceIds.joinToString(",")})" +
                    "&select=id,source_id,spotify_id,title,artist,duration_ms" +
                    "&order=created_at&limit=2000",
            ) {
                supabaseHeaders().forEach { (k, v) -> header(k, v) }
                accept(ContentType.Application.Json)
            }.body()
            rows.groupBy { it.source_id }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "pullAll failed")
            emptyMap()
        }
    }

    /** Direct mode: ask the function to poll NOW; new rows come back in the response. */
    suspend fun directInvoke(sourceIds: List<String>): Map<String, List<MirrorRow>> {
        if (sourceIds.isEmpty()) return emptyMap()
        return try {
            val resp: DirectResponse = http.post(
                "${BuildConfig.SUPABASE_URL}/functions/v1/poll-spotify",
            ) {
                supabaseHeaders().forEach { (k, v) -> header(k, v) }
                contentType(ContentType.Application.Json)
                setBody(mapOf("source_ids" to sourceIds))
            }.body()
            resp.sources.mapValues { it.value.added }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "directInvoke failed")
            emptyMap()
        }
    }

    /** Closed path: consume server-stored pending rows for every linked playlist. */
    suspend fun intakeAll() = intakeMutex.withLock {
        val allLinks = links()
        if (allLinks.isEmpty()) return
        val pending = pullAll(allLinks.values.map { it.sourceId }.distinct())
        allLinks.forEach { (localId, link) ->
            intakeRows(localId, link.sourceId, pending[link.sourceId].orEmpty())
        }
    }

    /**
     * Alive path: consume already-pending rows first (incremental — first songs appear
     * without waiting for a full server resolve), then direct-invoke to trigger fresh
     * resolution and consume what comes back.
     */
    suspend fun intakeAllDirect() = intakeMutex.withLock {
        val allLinks = links()
        if (allLinks.isEmpty()) return
        val sourceIds = allLinks.values.map { it.sourceId }.distinct()
        val pending = pullAll(sourceIds)
        allLinks.forEach { (localId, link) ->
            intakeRows(localId, link.sourceId, pending[link.sourceId].orEmpty())
        }
        val fresh = directInvoke(sourceIds)
        allLinks.forEach { (localId, link) ->
            intakeRows(localId, link.sourceId, fresh[link.sourceId].orEmpty())
        }
    }

    /**
     * Consume rows in playlist order and return songs added. Matching runs bounded-parallel;
     * inserts run strictly sequentially so positions always mirror row order. Guards
     * (videoId + title+artist) make every path idempotent. Consumption is tracked
     * per-device (consumed set): rows handled here — inserted, skipped, or match-failed —
     * never come back; rows that throw stay unconsumed for retry.
     */
    private suspend fun intakeRows(localPlaylistId: String, sourceId: String, rows: List<MirrorRow>): Int {
        if (rows.isEmpty()) return 0
        val local = database.playlistBlocking(localPlaylistId) ?: run {
            Timber.tag(TAG).w("Mirror target $localPlaylistId gone, auto-untracking")
            untrack(localPlaylistId)
            return 0
        }
        val seen = consumed(sourceId).toMutableSet()
        val fresh = rows.filter { it.spotify_id !in seen }
        if (fresh.isEmpty()) return 0
        // F7: snapshot the playlist's title+artist set ONCE (not per row), then keep it
        // fresh incrementally as this pass inserts - O(N) instead of O(N^2).
        val knownTitles = loadTitleIndex(localPlaylistId)
        val matched = matchAll(knownTitles, fresh)
        // Null matches are clean skips (present or unmatchable): consume immediately.
        matched.forEachIndexed { index, item ->
            if (item == null) seen += fresh[index].spotify_id
        }
        val pairs = matched.mapIndexedNotNull { index, item ->
            if (item != null) fresh[index] to item else null
        }
        // F8: insert in 50-row transactions (playlist screen recomposes per chunk, not
        // per song). Order preserved: chunks commit strictly in sequence; a failed chunk
        // aborts the pass and its rows stay unconsumed for retry.
        val inserted = mutableListOf<SongItem>()
        try {
            pairs.chunked(INSERT_CHUNK).forEach { chunk ->
                database.withTransaction {
                    var currentMax = playlistSongMaps(localPlaylistId, 0).maxOfOrNull { it.position } ?: -1
                    chunk.forEach { (row, item) ->
                        if (isPresent(knownTitles, item.title, item.artists.firstOrNull()?.name)) {
                            seen += row.spotify_id
                            return@forEach
                        }
                        if (checkInPlaylist(localPlaylistId, item.id) == 0) {
                            if (getSongByIdBlocking(item.id) == null) {
                                insert(item.toMediaMetadata())
                            }
                            currentMax += 1
                            insert(
                                PlaylistSongMap(
                                    songId = item.id,
                                    playlistId = localPlaylistId,
                                    position = currentMax,
                                ),
                            )
                            knownTitles += item.title.lowercase() to
                                item.artists.map { it.name.lowercase() }.toSet()
                            inserted += item
                        }
                        seen += row.spotify_id
                    }
                    updatePlaylistLastUpdated(localPlaylistId)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Mirror chunk insert failed - remainder retried next pass")
        }
        saveConsumed(sourceId, seen)
        if (inserted.isNotEmpty()) {
            // Downloads enqueue after all inserts (same call as a manual add).
            inserted.forEach { enqueueDownload(it) }
            SongNotificationHelper.showMirrorNotification(context, inserted.size, local.playlist.name)
        }
        Timber.tag(TAG).d("Mirror intake for $localPlaylistId: ${inserted.size}/${rows.size} added")
        return inserted.size
    }

    /** Snapshot of (title, artists) in a playlist for O(1) duplicate checks. */
    private suspend fun loadTitleIndex(localPlaylistId: String): MutableList<Pair<String, Set<String>>> =
        try {
            database.playlistSongsBlocking(localPlaylistId).map { ps ->
                ps.song.song.title.lowercase() to ps.song.artists.map { it.name.lowercase() }.toSet()
            }.toMutableList()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Title index load failed, checks degrade to videoId-only")
            mutableListOf()
        }

    private fun isPresent(
        knownTitles: List<Pair<String, Set<String>>>,
        title: String,
        artist: String?,
    ): Boolean {
        if (artist.isNullOrBlank()) return false
        val t = title.lowercase()
        val a = artist.lowercase()
        return knownTitles.any { (kt, ka) -> kt == t && ka.any { it == a } }
    }

    /** Match all rows concurrently (bounded); results align by index (null = skip). */
    private suspend fun matchAll(
        knownTitles: List<Pair<String, Set<String>>>,
        rows: List<MirrorRow>,
    ): List<SongItem?> = coroutineScope {
        rows.map { row ->
            async {
                matchSemaphore.withPermit {
                    // Dedupe before searching: present songs skip without a network call.
                    if (isPresent(knownTitles, row.title, row.artist)) return@withPermit null
                    try {
                        youtubeMatcher.matchJsonTrackWithRetry(JsonTrack(row.title, row.artist))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(TAG).w("Match failed for ${row.title} — ${row.artist}")
                        null
                    }
                }
            }
        }.awaitAll()
    }

    private fun enqueueDownload(matched: SongItem) {
        // D4: same enqueue as a manual add — existing WiFi/charging rules + lyrics (D5) apply.
        val request = androidx.media3.exoplayer.offline.DownloadRequest.Builder(matched.id, matched.id.toUri())
            .setCustomCacheKey(matched.id)
            .setData(matched.title.toByteArray())
            .build()
        DownloadService.sendAddDownload(context, ExoDownloadService::class.java, request, false)
    }

    /** Alive path for one playlist (pull-to-refresh): direct-invoke now, consume. Returns songs added. */
    suspend fun intakeDirectFor(localPlaylistId: String): Int = intakeMutex.withLock {
        val link = links()[localPlaylistId] ?: return 0
        val rows = directInvoke(listOf(link.sourceId))[link.sourceId].orEmpty()
        if (rows.isEmpty()) return 0
        intakeRows(localPlaylistId, link.sourceId, rows)
    }

    /** Probe a Spotify link: returns total track count without resolving or storing. */
    suspend fun probeSpotifyTotal(spotifyUrl: String): Int? {
        val spotifyId = parseSpotifyId(spotifyUrl) ?: return null
        return try {
            val resp: ProbeResponse = http.post(
                "${BuildConfig.SUPABASE_URL}/functions/v1/poll-spotify",
            ) {
                supabaseHeaders().forEach { (k, v) -> header(k, v) }
                contentType(ContentType.Application.Json)
                setBody(mapOf("probe_spotify_id" to spotifyId))
            }.body()
            if (resp.ok) resp.total else null
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "probeSpotifyTotal failed")
            null
        }
    }

    /** Local song count for the backfill confirmation dialog. */
    suspend fun localSongCount(localPlaylistId: String): Int =
        runCatching { database.playlistSongsBlocking(localPlaylistId).size }.getOrDefault(0)

    // ── Scheduling + alive timers ─────────────────────────────────────────────────────────

    fun ensureScheduled() {
        val request = PeriodicWorkRequestBuilder<SpotifyMirrorWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(
            SpotifyMirrorWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun triggerNow() {
        val request = OneTimeWorkRequestBuilder<SpotifyMirrorWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueue(request)
    }

    fun triggerExpedited() = triggerNow()

    fun startAlivePolling() {
        if (aliveJob?.isActive == true) return
        aliveJob = aliveScope.launch {
            while (isActive) {
                delay(if (screenOpen) ALIVE_OPEN_MS else ALIVE_BG_MS)
                runCatching { intakeAllDirect() }
                    .onFailure { Timber.tag(TAG).e(it, "Alive mirror poll failed") }
            }
        }
    }

    fun stopAlivePolling() {
        aliveJob?.cancel()
        aliveJob = null
    }

    /**
     * Phase-2 gate helper (DEBUG only): with no UI yet, the worker needs a link. On a
     * debug build with zero links and a test source configured, this creates a
     * "Spotify Mirror Test" playlist and links it future-only. Production builds and
     * real links never touch this path.
     */
    suspend fun debugBootstrapIfNeeded(): Boolean {
        if (!BuildConfig.DEBUG || DEBUG_TEST_SOURCE_ID.isEmpty()) return links().isNotEmpty()
        if (links().isNotEmpty()) return true
        val existing = database.playlistEntitiesByNameAsc().firstOrNull { it.name == DEBUG_PLAYLIST_NAME }
        val playlistId = existing?.id ?: run {
            val entity = PlaylistEntity(
                name = DEBUG_PLAYLIST_NAME,
                browseId = null,
                isLocal = true,
                isEditable = true,
                bookmarkedAt = LocalDateTime.now(),
            )
            database.withTransaction { insert(entity) }
            entity.id
        }
        linkPlaylistBySource(playlistId, DEBUG_TEST_SOURCE_ID, MODE_FUTURE)
        return true
    }

    private suspend fun linkPlaylistBySource(localPlaylistId: String, sourceId: String, mode: String) {
        val updated = links().toMutableMap()
        updated[localPlaylistId] = MirrorLink(sourceId, "", mode)
        saveLinks(updated)
    }

    companion object {
        private const val TAG = "SpotifyMirror"
        const val MODE_BACKFILL = "backfill"
        const val MODE_FUTURE = "future"

        private const val ALIVE_OPEN_MS = 10_000L
        private const val ALIVE_BG_MS = 30_000L

        /** Songs per Room transaction during mirror intake (display smoothness). */
        private const val INSERT_CHUNK = 50

        private const val DEBUG_PLAYLIST_NAME = "Spotify Mirror Test"

        /**
         * Test source id for the Phase-2 device gate ONLY. Empty = bootstrap disabled.
         * Set to a real mirror_sources id for the gate run, verify, then revert.
         */
        private const val DEBUG_TEST_SOURCE_ID = ""
    }
}
