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
        return YouTube.search(query, filter = YouTube.SearchFilter.FILTER_SONG).map { page ->
            page.items.filterIsInstance<SongItem>().firstOrNull()
        }.getOrNull()
    }
}
