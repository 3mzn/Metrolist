/*
 * Spotify Canvas support in Metrolist.
 *
 * Copyright (C) 2025 maxrave-dev (SimpMusic)
 * Copyright (C) 2025 The Metrolist Group
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * ---------------------------------------------------------------------------
 * Ported from SimpMusic's `LyricsCanvasRepositoryImpl.getCanvas`
 * (core/data/.../repository/LyricsCanvasRepositoryImpl.kt lines 114-245), GPL-3.0.
 *
 * DO NOT REWRITE THE LOGIC. Three things here are upstream's, not ours, and each is a
 * deliberate preservation rather than an oversight:
 *
 *  1. The query scrub regexes, which strip "(feat." / "&" / "&egrave;" / "." noise before
 *     searching. They are the reason the search matches at all.
 *  2. The candidate selection, which LOOKS like it matches on duration but compares
 *     `totalMilliseconds` (ms) against a `duration` argument in seconds. That branch is
 *     therefore unreachable and the first search hit is always used. Copying it verbatim
 *     keeps our behaviour identical to SimpMusic. **See SPEC_SPOTIFY_CANVAS.md 14.1e before
 *     changing it** - repairing the units is a behaviour change, not a bug fix.
 *  3. `?: (0 / 1000)`, integer division of zero, which is just a zero default.
 *
 * What is ours, and has no SimpMusic equivalent: the cache *index*. SimpMusic writes
 * `canvasUrl` into its `song` table, which we cannot do because this project must not
 * change the database schema (AGENTS.md, spec 11.4). The verdict therefore lives in
 * DataStore instead. The video bytes still use SimpMusic's `spotifyCanvas` SimpleCache.
 * ---------------------------------------------------------------------------
 */

package com.metrolist.music.utils.spotify

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.metrolist.music.constants.SpotifyCanvasCacheKey
import com.metrolist.music.constants.SpotifyCanvasFailureCountKey
import com.metrolist.music.di.CanvasCache
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import com.metrolist.spotify.Spotify
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * TEMPORARY. Phase 3 gate: the fetch runs on track change only when this is true, and it is
 * **false** in the committed state so the player behaves exactly as it did before this phase.
 * It was flipped to true only to gather the logcat evidence in spec 14.1f.
 * Phase 4 replaces this flag with the real rendering surface; Phase 5 replaces the flag with
 * the long-press gesture. Do not leave it on.
 */
const val SPOTIFY_CANVAS_FETCH_ENABLED = false

/** Spec 9.2: consecutive Spotify API failures that auto-disable the feature. */
const val SPOTIFY_CANVAS_FAILURE_LIMIT = 3

private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * One track's Canvas verdict.
 *
 * A null [canvasUrl] with [noCanvasAttempts] at or above the threshold is the cached
 * "confirmed no canvas" verdict. Below the threshold it is only a counter.
 */
@Serializable
private data class CanvasCacheEntry(
    val canvasUrl: String? = null,
    val canvasThumbUrl: String? = null,
    val isVideo: Boolean = true,
    val noCanvasAttempts: Int = 0,
) {
    val isConfirmedEmpty: Boolean get() = canvasUrl == null && noCanvasAttempts >= NEGATIVE_CACHE_THRESHOLD
}

/** Why a lookup ended the way it did. Drives what may be cached - see spec 9.2. */
private sealed interface Outcome {
    /** A Canvas was found and written to disk. */
    data class Success(val result: CanvasResult) : Outcome

    /** Spotify answered normally and there is genuinely no Canvas for this track. */
    data object CleanNegative : Outcome

    /** Spotify was unreachable, timed out, 5xx, or rejected the token. Never a negative. */
    data object TransportFailure : Outcome

    /** Maps to the shared enum so the tested policy is the one that runs. */
    val kind: OutcomeKind
        get() =
            when (this) {
                is Success -> OutcomeKind.SUCCESS
                is CleanNegative -> OutcomeKind.CLEAN_NEGATIVE
                is TransportFailure -> OutcomeKind.TRANSPORT_FAILURE
            }
}

@Singleton
class SpotifyCanvasRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionRepository: SpotifySessionRepository,
    @param:CanvasCache private val canvasCache: Cache,
) {
    private val TAG = "SpotifyCanvas"
    private val spotify = Spotify()

    /**
     * Fetches, caches and returns the Canvas for one track, or null when there is none.
     *
     * Never throws and never blocks playback: every failure path resolves to null.
     */
    suspend fun getCanvas(
        videoId: String,
        title: String,
        artist: String,
        duration: Int,
    ): CanvasResult? =
        withContext(Dispatchers.IO) {
            if (!SPOTIFY_CANVAS_FETCH_ENABLED) return@withContext null

            cachedEntry(videoId)?.let { entry ->
                if (entry.isConfirmedEmpty) {
                    Timber.tag(TAG).d("no canvas (cached negative) for $videoId '$title'")
                    return@withContext null
                }
                entry.canvasUrl?.let { url ->
                    Timber.tag(TAG).d("cache hit for $videoId '$title' -> $url")
                    return@withContext CanvasResult(entry.isVideo, url, entry.canvasThumbUrl)
                }
            }

            when (val outcome = resolve(videoId, title, artist, duration)) {
                is Outcome.Success -> {
                    applyOutcome(videoId, outcome)
                    outcome.result
                }

                is Outcome.CleanNegative -> {
                    applyOutcome(videoId, outcome)
                    null
                }

                is Outcome.TransportFailure -> {
                    applyOutcome(videoId, outcome)
                    null
                }
            }
        }

    /** The whole network path, ported from SimpMusic's `getCanvas` body. */
    private suspend fun resolve(
        videoId: String,
        title: String,
        artist: String,
        duration: Int,
    ): Outcome {
        val tokens =
            sessionRepository.validTokens()
                ?: run {
                    Timber.tag(TAG).w("no valid Spotify tokens for $videoId - not logged in, or cookie is stale")
                    return Outcome.TransportFailure
                }

        // Ported verbatim from SimpMusic. The scrub is what makes the search match at all.
        val q =
            "$title $artist"
                .replace(
                    Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
                    " ",
                ).replace(
                    Regex("( và | & | и | e | und |, |和| dan)"),
                    " ",
                ).replace("  ", " ")
                .replace(Regex("([()])"), "")
                .replace(".", " ")
                .replace("  ", " ")
        Timber.tag(TAG).d("query for $videoId: '$q' (from '$title' / '$artist')")

        val search =
            spotify
                .searchSpotifyTrack(q, tokens.personal, tokens.client)
                .getOrElse {
                    Timber.tag(TAG).w(it, "search failed for $videoId")
                    return Outcome.TransportFailure
                }

        val items = search.data?.searchV2?.tracksV2?.items
        if (items.isNullOrEmpty()) {
            Timber.tag(TAG).d("search returned nothing for $videoId '$title'")
            return Outcome.CleanNegative
        }

        // Ported verbatim, INCLUDING the unreachable duration branch - see the file header
        // and spec 14.1e. `totalMilliseconds` is ms and `duration` is seconds, so the
        // predicate is never true and `firstOrNull()` is what actually runs. The logging
        // below is the only reason a wrong pick stays diagnosable, so keep it.
        val track =
            if (duration != 0) {
                items.find {
                    abs(
                        (
                            (
                                (
                                    it.item
                                        ?.data
                                        ?.duration
                                        ?.totalMilliseconds ?: (0 / 1000)
                                ) - duration
                            )
                        ),
                    ) < 1
                }
                    ?: items.firstOrNull()
            } else {
                items.firstOrNull()
            }

        val spotifyTrackId = track?.item?.data?.id
        if (spotifyTrackId.isNullOrBlank()) {
            Timber.tag(TAG).d("no usable Spotify track id for $videoId '$title'")
            return Outcome.CleanNegative
        }
        Timber.tag(TAG).d(
            "picked Spotify track $spotifyTrackId ('${track?.item?.data?.name}') for $videoId " +
                "'$title' out of ${items.size} candidate(s)",
        )

        val canvas =
            spotify
                .getSpotifyCanvas(spotifyTrackId, tokens.personal, tokens.client)
                .getOrElse {
                    Timber.tag(TAG).w(it, "canvas fetch failed for $spotifyTrackId")
                    return Outcome.TransportFailure
                }

        val result =
            canvas.toCanvasResult()
                ?: run {
                    Timber.tag(TAG).d("no canvas on Spotify for track $spotifyTrackId (clean negative)")
                    return Outcome.CleanNegative
                }

        Timber.tag(TAG).d("canvas for $videoId: ${result.canvasUrl}")
        writeVideoToCache(result)
        return Outcome.Success(result)
    }

    /**
     * Downloads the Canvas into the `spotifyCanvas` SimpleCache so Phase 4's player reads it warm.
     *
     * SimpMusic reaches the same cache by streaming through a `CacheDataSource` during playback;
     * with no player in this phase, `CacheWriter` is the supported way to populate it without one.
     * The cache key is the URL, which is why the URL is remembered separately.
     */
    private fun writeVideoToCache(result: CanvasResult) {
        if (!result.isVideo) return
        val dataSource =
            CacheDataSource
                .Factory()
                .setCache(canvasCache)
                .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(OkHttpClient()))
                .createDataSource()
        runCatching {
            CacheWriter(dataSource, DataSpec(Uri.parse(result.canvasUrl)), null, null).cache()
            Timber.tag(TAG).d("pre-cached ${result.canvasUrl} (cache now ${canvasCache.cacheSpace} bytes)")
        }.onFailure {
            // A cache miss is not a Canvas failure: the URL is still stored and Phase 4 can
            // stream it. Log only.
            Timber.tag(TAG).w(it, "could not pre-cache canvas video; it will stream instead")
        }
    }

    // ---------------------------------------------------------------- cache index

    private suspend fun cachedEntry(videoId: String): CanvasCacheEntry? = readIndex()[videoId]

    private suspend fun readIndex(): Map<String, CanvasCacheEntry> {
        val raw = context.dataStore.data.first()[SpotifyCanvasCacheKey].orEmpty()
        if (raw.isEmpty()) return emptyMap()
        return runCatching { JSON.decodeFromString<Map<String, CanvasCacheEntry>>(raw) }
            .getOrElse {
                Timber.tag(TAG).w(it, "canvas index unreadable; starting a fresh one")
                emptyMap()
            }
    }

    private suspend fun updateIndex(videoId: String, transform: (CanvasCacheEntry) -> CanvasCacheEntry) {
        val updated = readIndex().toMutableMap()
        updated[videoId] = transform(updated[videoId] ?: CanvasCacheEntry())
        context.safeDataStoreEdit { it[SpotifyCanvasCacheKey] = JSON.encodeToString(updated) }
    }

    private suspend fun applyOutcome(
        videoId: String,
        outcome: Outcome,
    ) {
        val kind = outcome.kind
        val previous = readIndex()[videoId] ?: CanvasCacheEntry()
        val decision = decideCacheUpdate(kind, previous.noCanvasAttempts)

        when (kind) {
            OutcomeKind.SUCCESS -> {
                val result = (outcome as Outcome.Success).result
                updateIndex(videoId) {
                    CanvasCacheEntry(result.canvasUrl, result.canvasThumbUrl, result.isVideo, decision.newAttempts)
                }
            }

            // A transport failure deliberately writes nothing about the track. See the policy.
            OutcomeKind.TRANSPORT_FAILURE -> Unit

            OutcomeKind.CLEAN_NEGATIVE -> {
                val attempts = decision.newAttempts
                Timber.tag(TAG).d(
                    "clean negative $attempts/$NEGATIVE_CACHE_THRESHOLD for $videoId" +
                        if (decision.confirmedNoCanvas) " - now cached as no-canvas" else "",
                )
                updateIndex(videoId) { it.copy(noCanvasAttempts = attempts) }
            }
        }

        if (decision.failuresDelta > 0) {
            val failures = (context.dataStore.data.first()[SpotifyCanvasFailureCountKey] ?: 0) + decision.failuresDelta
            context.safeDataStoreEdit { it[SpotifyCanvasFailureCountKey] = failures }
            Timber.tag(TAG).w("consecutive Spotify failures: $failures/$SPOTIFY_CANVAS_FAILURE_LIMIT")
            if (failures >= SPOTIFY_CANVAS_FAILURE_LIMIT) {
                // Phase 3 records the trip only. The auto-disable and its one-shot toast are Phase 7,
                // so this log line is the trigger point that phase hooks into.
                Timber.tag(TAG).w("failure limit reached - Phase 7 auto-disables here")
            }
        } else if (decision.resetFailures) {
            val failures = context.dataStore.data.first()[SpotifyCanvasFailureCountKey] ?: 0
            if (failures != 0) {
                context.safeDataStoreEdit { it[SpotifyCanvasFailureCountKey] = 0 }
                Timber.tag(TAG).d("consecutive Spotify failure counter reset to 0 by a normal answer for $videoId")
            }
        }
    }
}
