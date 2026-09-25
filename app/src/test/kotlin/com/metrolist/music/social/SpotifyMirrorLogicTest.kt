/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.utils.YoutubeMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyMirrorLogicTest {
    @Test
    fun parseSpotifyId_validLink() {
        assertEquals(
            "3wWzDykWVgfFqJW0ziYcQu",
            SpotifyMirrorRepository.parseSpotifyId(
                "https://open.spotify.com/playlist/3wWzDykWVgfFqJW0ziYcQu?si=abc",
            ),
        )
    }

    @Test
    fun parseSpotifyId_rejectsNonPlaylist() {
        assertEquals(null, SpotifyMirrorRepository.parseSpotifyId("https://open.spotify.com/track/abc123"))
        assertEquals(null, SpotifyMirrorRepository.parseSpotifyId("not a link"))
        assertEquals(null, SpotifyMirrorRepository.parseSpotifyId(""))
    }

    @Test
    fun isSpotifyPlaylistUrl_matchesParser() {
        assertTrue(SpotifyMirrorRepository.isSpotifyPlaylistUrl("https://open.spotify.com/playlist/abc123XYZ"))
        assertFalse(SpotifyMirrorRepository.isSpotifyPlaylistUrl("https://open.spotify.com/album/abc123XYZ"))
    }

    @Test
    fun normalizeTitle_stripsEditions() {
        assertEquals("dreams", SpotifyMirrorRepository.normalizeTitle("Dreams - 2004 Remaster"))
        assertEquals("dreams", SpotifyMirrorRepository.normalizeTitle("Dreams (Remastered 2011)"))
        assertEquals("dreams", SpotifyMirrorRepository.normalizeTitle("Dreams [Explicit]"))
        assertEquals("hello world", SpotifyMirrorRepository.normalizeTitle("Hello   World"))
    }

    @Test
    fun splitArtists_handlesLists() {
        assertEquals(setOf("fleetwood mac"), SpotifyMirrorRepository.splitArtists("Fleetwood Mac"))
        assertEquals(
            setOf("maanu", "annural khalid"),
            SpotifyMirrorRepository.splitArtists("Maanu, Annural Khalid"),
        )
        assertEquals(
            setOf("a", "b"),
            SpotifyMirrorRepository.splitArtists("A feat. B"),
        )
    }

    @Test
    fun matchTitleArtist_exact() {
        assertTrue(
            SpotifyMirrorRepository.matchTitleArtist(
                "dreams", setOf("fleetwood mac"), "Dreams", "Fleetwood Mac",
            ),
        )
    }

    @Test
    fun matchTitleArtist_collabOverlap() {
        assertTrue(
            SpotifyMirrorRepository.matchTitleArtist(
                "jhol", setOf("maanu"), "Jhol", "Maanu, Annural Khalid",
            ),
        )
    }

    @Test
    fun matchTitleArtist_remasterVariant() {
        assertTrue(
            SpotifyMirrorRepository.matchTitleArtist(
                "dreams - 2004 remaster", setOf("fleetwood mac"), "Dreams", "Fleetwood Mac",
            ),
        )
    }

    @Test
    fun matchTitleArtist_negative() {
        assertFalse(
            SpotifyMirrorRepository.matchTitleArtist(
                "dreams", setOf("fleetwood mac"), "Dreams", "The Corrs",
            ),
        )
        assertFalse(
            SpotifyMirrorRepository.matchTitleArtist(
                "dreams", setOf("fleetwood mac"), "Landslide", "Fleetwood Mac",
            ),
        )
        assertFalse(
            SpotifyMirrorRepository.matchTitleArtist(
                "dreams", setOf("fleetwood mac"), "Dreams", null,
            ),
        )
    }

    private val matcher = YoutubeMatcher()

    private fun hit(id: String, durationSecs: Int?, video: Boolean) = SongItem(
        id = id,
        title = "T",
        artists = listOf(Artist("A", null)),
        duration = durationSecs,
        musicVideoType = if (video) "MUSIC_VIDEO_TYPE_UGC" else null,
        thumbnail = "t",
    )

    @Test
    fun stripForSearch_slots() {
        assertEquals("Dreams", matcher.stripForSearch("Dreams [Explicit]"))
        assertEquals("Dreams", matcher.stripForSearch("Dreams (Remastered 2011)"))
        assertEquals("Despacito", matcher.stripForSearch("Despacito - Remix"))
        assertEquals(
            "Sunflower",
            matcher.stripForSearch("Sunflower - Spider-Man: Into the Spider-Verse"),
        )
        assertEquals("Hello World", matcher.stripForSearch("Hello   World"))
        assertEquals("Dreams", matcher.stripForSearch("Dreams"))
    }

    @Test
    fun fallbackQueries_orderAndDedupe() {
        assertEquals(
            listOf("Despacito - Remix Luis Fonsi", "Despacito Luis Fonsi"),
            matcher.fallbackQueries("Despacito - Remix", "Luis Fonsi"),
        )
        assertEquals(
            listOf("Dreams Fleetwood Mac"),
            matcher.fallbackQueries("Dreams", "Fleetwood Mac"),
        )
    }

    @Test
    fun pickBest_prefersAudioInWindow() {
        val video = hit("v", 231, true) // |231-229| = 2, in window but video
        val audio = hit("a", 237, false) // |237-229| = 8, in window, audio
        assertEquals("a", matcher.pickBest(listOf(video, audio), 229_000)?.id)
    }

    @Test
    fun pickBest_windowBeatsAudio() {
        val farAudio = hit("a", 400, false)
        val nearVideo = hit("v", 231, true)
        assertEquals("v", matcher.pickBest(listOf(farAudio, nearVideo), 229_000)?.id)
    }

    @Test
    fun pickBest_degradesToFirstHit() {
        val first = hit("first", 900, true)
        val second = hit("second", 901, false)
        // All wild durations -> first (today's behavior).
        assertEquals("first", matcher.pickBest(listOf(first, second), 229_000)?.id)
        // Unknown duration either side -> first.
        assertEquals("first", matcher.pickBest(listOf(first, second), null)?.id)
        assertEquals(
            "first",
            matcher.pickBest(listOf(hit("first", null, false), second), 229_000)?.id,
        )
    }

    @Test
    fun pickBest_emptyIsNull() {
        assertNull(matcher.pickBest(emptyList(), 229_000))
    }
}
