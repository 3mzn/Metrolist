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
     * only runs when exact returned hits-but-empty. Every query's hits pass the
     * artist gate (title-only junk — covers, karaoke, 8D uploads — is rejected);
     * duration preference only reorders survivors and degrades to their first hit.
     * Gated-out-everything reads as NotFound (review list), never a wrong insert.
     */
    suspend fun matchWithOutcome(
        track: JsonTrack,
        durationMs: Int? = null,
        maxAttempts: Int = 2,
        lenientTopHit: Boolean = false,
    ): MatchOutcome {
        for (query in fallbackQueries(track.title, track.artist)) {
            // Errors retry within a query; empty results move to the next query
            // immediately (re-searching a dead query only feeds rate limits).
            for (attempt in 0 until maxAttempts) {
                try {
                    val page = searchSongs(query)
                    if (page == null) {
                        // Search itself failed: wait before retry, then next query.
                        if (attempt < maxAttempts - 1) {
                            kotlinx.coroutines.delay(500)
                        }
                        continue
                    }
                    var pool = page.first
                    val cont = page.second
                    if (cont != null && needsMore(pool, track.title, track.artist, durationMs)) {
                        // Page 1 can't yield a good pick: one more page before
                        // giving up (the right upload may sit just below junk, or
                        // nothing in range yet).
                        val more = YouTube.searchContinuation(cont)
                            .getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
                        pool = pool + more
                    }
                    val candidates = filterCandidates(pool, track.title, track.artist)
                    if (candidates.isNotEmpty()) {
                        return MatchOutcome.Found(pickBest(candidates, durationMs) ?: candidates.first())
                    }
                    break
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
        // Manual-retry-only leniency (collab-credit mismatches like Talk/Retronaut):
        // exact query's top hit, same folded title, duration within 10 s. Never used
        // by unattended intake — the user explicitly asked for THIS song and sees
        // what lands.
        if (lenientTopHit && durationMs != null) {
            val top = searchSongs(track.toSearchQuery())?.first?.firstOrNull()
            if (top != null && foldedTitle(top.title) == foldedTitle(track.title)) {
                val secs = durationMs / 1000
                val topSecs = top.duration
                if (topSecs != null && kotlin.math.abs(topSecs - secs) <= DURATION_WINDOW_SECS) {
                    return MatchOutcome.Found(top)
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
        return searchSongs(query)?.first?.firstOrNull()
    }

    /**
     * Raw candidate list for one query plus its continuation token (null when the
     * search itself failed). Page 2 is fetched by the caller only when needed.
     */
    private suspend fun searchSongs(query: String): Pair<List<SongItem>, String?>? =
        YouTube.search(query, filter = YouTube.SearchFilter.FILTER_SONG).map { page ->
            page.items.filterIsInstance<SongItem>() to page.continuation
        }.getOrNull()

    /**
     * Whether page 2 is worth fetching: nothing survived the filter, or survivors
     * exist but none is in the duration window (a better hit may sit below).
     * No duration known → never extend on quality grounds.
     */
    internal fun needsMore(
        pool: List<SongItem>,
        title: String,
        artist: String,
        durationMs: Int?,
    ): Boolean {
        val survivors = filterCandidates(pool, title, artist)
        if (survivors.isEmpty()) return true
        val expected = durationMs?.div(1000) ?: return false
        return survivors.none { c ->
            val secs = c.duration ?: return@none false
            kotlin.math.abs(secs - expected) <= DURATION_WINDOW_SECS
        }
    }

    /**
     * Candidate filter (mirror path): artist overlap AND folded-title equality.
     * Artist-only gating lets a same-artist different-song through, which the
     * insert dedupe then eats silently (consumed, no song, no skip — the
     * "safety net" hole). Both sides folded identically (brackets, parens,
     * dash-tails, case, punctuation), so legit variants ("Side To Side (feat.
     * Nicki Minaj)" == "Side To Side") still meet.
     */
    internal fun filterCandidates(candidates: List<SongItem>, title: String, artist: String): List<SongItem> {
        val want = foldedTitle(title)
        return filterByArtist(candidates, artist).filter { foldedTitle(it.title) == want }
    }

    /**
     * Artist gate: keep hits sharing at least one normalized artist token with the
     * incoming artist. Kills title-only junk (covers, karaoke, 8D/8-bit uploads)
     * that the query + duration pick can't distinguish. Blank incoming artist
     * can't gate — preserve old behavior rather than reject everything.
     */
    internal fun filterByArtist(candidates: List<SongItem>, artist: String): List<SongItem> {
        val incoming = splitArtists(artist)
        if (incoming.isEmpty()) return candidates
        return candidates.filter { item ->
            item.artists.flatMap { splitArtists(it.name) }.any { it in incoming }
        }
    }

    /**
     * Artist tokens: strip distributor tails ("- Topic", "VEVO" — official uploads
     * arrive under those), split collabs/lists on , ; & and feat/ft markers, then
     * fold (diacritics, case, punctuation: "A.R. Rahman" == "AR Rahman", "P!nk" ==
     * "Pink"). Both sides folded identically, so legit variants meet.
     */
    internal fun splitArtists(artist: String): Set<String> =
        artist.lowercase()
            .replace("\\s+-\\s+topic\\s*$".toRegex(), "")
            .replace("vevo\\s*$".toRegex(), "")
            .split(",", ";", "&", " and ", " feat. ", " ft. ", " feat ", " ft ", " featuring ")
            .map { foldToken(it) }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun foldToken(s: String): String {
        val ascii = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
        return ascii.lowercase()
            .replace("!", "i")
            .replace("$", "s")
            .replace("0", "o")
            .replace("[^a-z0-9]+".toRegex(), "")
    }

    /** Folded title for equality checks (same stripping as fallback queries). */
    internal fun foldedTitle(title: String): String = foldToken(stripForSearch(title))

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
     * audio over music-video among those, closest duration, studio over live
     * last. Degrades to the old first-hit behavior whenever duration is unknown
     * or nothing is in window — reorders only, never discards.
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
            compareBy(
                { if (it.isVideoSong) 1 else 0 },
                { kotlin.math.abs((it.duration ?: expectedSecs) - expectedSecs) },
                { if (isLiveTitle(it.title)) 1 else 0 },
            ),
        ) ?: candidates.first()
    }

    /** Live recordings land only as last resort (studio preferred, never rejected). */
    internal fun isLiveTitle(title: String): Boolean =
        "\\blive\\b|\\bunplugged\\b".toRegex(RegexOption.IGNORE_CASE).containsMatchIn(title)

    companion object {
        /** Duration preference window (secs) for pickBest. */
        private const val DURATION_WINDOW_SECS = 10
    }
}
