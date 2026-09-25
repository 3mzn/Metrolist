/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.sync.JsonTrack
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YouTube matching for `{title, artist}` rows (SPEC_SPOTIFY_MIRROR).
 *
 * Extracted verbatim from `JsonImportViewModel` (which now delegates here) so the
 * headless mirror intake can share the exact same matching behavior. Owner rule (D3):
 * title+artist search, first song hit wins, no confidence gate.
 */
@Singleton
class YoutubeMatcher @Inject constructor() {
    /**
     * Match outcome: Found carries the hit; NotFound means "searched fine, no such
     * video" (safe to never retry); NetworkError means transient (caller should retry
     * later with backoff).
     */
    sealed interface MatchOutcome {
        data class Found(val item: SongItem) : MatchOutcome
        data object NotFound : MatchOutcome
        data object NetworkError : MatchOutcome
    }

    /**
     * Match a JSON track with YouTube Music with retry logic, distinguishing
     * definitive misses from transient failures (SPEC_MIRROR_MATCH).
     *
     * Query chain is strict improvement over the old exact-only behavior: the exact
     * query runs first with the same retry shape as before; the stripped fallback
     * only runs when exact returned hits-but-empty. Duration preference only
     * reorders hits and degrades to the old first-hit pick, so worst case ==
     * status quo (skip), never a worse insert.
     */
    suspend fun matchWithOutcome(
        track: JsonTrack,
        durationMs: Int? = null,
        maxAttempts: Int = 2,
    ): MatchOutcome {
        for (query in fallbackQueries(track.title, track.artist)) {
            repeat(maxAttempts) { attempt ->
                try {
                    val candidates = searchSongs(query)
                    if (!candidates.isNullOrEmpty()) {
                        return MatchOutcome.Found(pickBest(candidates, durationMs) ?: candidates.first())
                    }
                    // Empty hits, no error: wait before retry, then next query.
                    if (attempt < maxAttempts - 1) {
                        kotlinx.coroutines.delay(500)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // Don't retry on cancellation
                } catch (e: Exception) {
                    // Network or other error - retry if attempts remain
                    if (attempt == maxAttempts - 1) {
                        return MatchOutcome.NetworkError
                    }
                    kotlinx.coroutines.delay(500)
                }
            }
        }
        return MatchOutcome.NotFound
    }
    /**
     * Match a JSON track with YouTube Music with retry logic.
     */
    suspend fun matchJsonTrackWithRetry(track: JsonTrack, maxAttempts: Int = 2): SongItem? {
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
    suspend fun matchJsonTrack(track: JsonTrack): SongItem? {
        val query = track.toSearchQuery()
        return searchSongs(query)?.firstOrNull()
    }

    /** Raw candidate list for one query; null when the search itself failed. */
    private suspend fun searchSongs(query: String): List<SongItem>? =
        YouTube.search(query, filter = YouTube.SearchFilter.FILTER_SONG).map { page ->
            page.items.filterIsInstance<SongItem>()
        }.getOrNull()

    /**
     * Ordered search queries for a track: exact first, stripped fallback second
     * (omitted when stripping changes nothing). Artist is always kept — it anchors
     * against wrong-song matches when the title is broadened.
     */
    internal fun fallbackQueries(title: String, artist: String): List<String> {
        val exact = "$title $artist"
        val stripped = stripForSearch(title)
        return if (stripped.isBlank() || stripped.equals(title, ignoreCase = true)) {
            listOf(exact)
        } else {
            listOf(exact, "$stripped $artist")
        }
    }

    /**
     * Strip search-hostile annotations for the fallback query only (stored data and
     * the exact query are untouched): [...] segments, (...) segments, then any
     * dash-separated tail ("- Remix", "- Spider-Man: Into the Spider-Verse", ...).
     * Over-broadening is safe here — this query only runs after exact found nothing,
     * and the artist + duration pick below still guard the choice.
     */
    internal fun stripForSearch(title: String): String =
        title.replace("\\[.*?\\]".toRegex(), "")
            .replace("\\(.*?\\)".toRegex(), "")
            .replace("\\s+-\\s+[^\\s].*$".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()

    /**
     * Pick the best hit: prefer in-window durations (|yt - expected| <= 10s),
     * audio over music-video among those, closest duration last. Degrades to the
     * old first-hit behavior whenever duration is unknown or nothing is in window —
     * reorders only, never discards.
     */
    internal fun pickBest(candidates: List<SongItem>, durationMs: Int?): SongItem? {
        if (candidates.isEmpty()) return null
        val expectedSecs = durationMs?.div(1000) ?: return candidates.first()
        val inWindow = candidates.filter { c ->
            val secs = c.duration ?: return@filter false
            kotlin.math.abs(secs - expectedSecs) <= DURATION_WINDOW_SECS
        }
        if (inWindow.isEmpty()) return candidates.first()
        return inWindow.minWithOrNull(
            compareBy({ if (it.isVideoSong) 1 else 0 }, { kotlin.math.abs((it.duration ?: expectedSecs) - expectedSecs) }),
        ) ?: candidates.first()
    }

    companion object {
        /** Duration preference window (secs) for pickBest. */
        private const val DURATION_WINDOW_SECS = 10
    }
}
