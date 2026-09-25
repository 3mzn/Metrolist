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
        assertEquals(
            setOf("the girl", "the dreamcatcher"),
            SpotifyMirrorRepository.splitArtists("The Girl and The Dreamcatcher"),
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

    private fun candidate(id: String, vararg artists: String) = SongItem(
        id = id,
        title = "T",
        artists = artists.map { Artist(it, null) },
        thumbnail = "t",
    )

    private fun titledCandidate(id: String, title: String, vararg artists: String) = SongItem(
        id = id,
        title = title,
        artists = artists.map { Artist(it, null) },
        thumbnail = "t",
    )

    @Test
    fun filterByArtist_exactAndVariants() {
        val ok = candidate("ok", "Taylor Swift")
        val topic = candidate("topic", "Taylor Swift - Topic")
        val vevo = candidate("vevo", "TaylorSwiftVEVO")
        val collab = candidate("collab", "Maanu, Annural Khalid")
        val junk = candidate("junk", "Wild Stylerz")
        assertEquals(
            listOf("ok", "topic", "vevo"),
            matcher.filterByArtist(listOf(ok, topic, vevo, junk), "Taylor Swift").map { it.id },
        )
        assertEquals(
            listOf("collab"),
            matcher.filterByArtist(listOf(collab, junk), "Maanu, Annural Khalid").map { it.id },
        )
        assertEquals(
            emptyList<SongItem>(),
            matcher.filterByArtist(listOf(junk), "Taylor Swift"),
        )
    }

    @Test
    fun filterByArtist_blankArtistDisablesGate() {
        val junk = candidate("junk", "Wild Stylerz")
        assertEquals(listOf(junk), matcher.filterByArtist(listOf(junk), ""))
    }

    @Test
    fun splitArtists_foldsVariants() {
        assertEquals(setOf("taylorswift"), matcher.splitArtists("Taylor Swift - Topic"))
        assertEquals(setOf("taylorswift"), matcher.splitArtists("TaylorSwiftVEVO"))
        assertEquals(setOf("arrahman"), matcher.splitArtists("A.R. Rahman"))
        assertEquals(setOf("beyonce"), matcher.splitArtists("Beyoncé"))
        assertEquals(setOf("pink"), matcher.splitArtists("P!nk"))
        assertEquals(
            setOf("arianagrande", "justinbieber"),
            matcher.splitArtists("Ariana Grande feat. Justin Bieber"),
        )
        assertEquals(
            setOf("thegirl", "thedreamcatcher"),
            matcher.splitArtists("The Girl and The Dreamcatcher"),
        )
    }

    @Test
    fun filterByArtist_andSplit() {
        val hit = candidate("hit", "The Girl", "the Dreamcatcher")
        val junk = candidate("junk", "Wild Stylerz")
        assertEquals(
            listOf("hit"),
            matcher.filterByArtist(listOf(hit, junk), "The Girl and The Dreamcatcher").map { it.id },
        )
    }

    @Test
    fun filterCandidates_rejectsSameArtistDifferentSong() {        // The "safety net" hole: same artist, different song must not pass.
        val wrongSong = titledCandidate("wrong", "positions", "Ariana Grande")
        val rightSong = titledCandidate("right", "safety net (Official Video)", "Ariana Grande")
        val cover = titledCandidate("cover", "safety net", "Wild Stylerz")
        val kept = matcher.filterCandidates(
            listOf(wrongSong, rightSong, cover),
            "safety net (feat. Ty Dolla \$ign)",
            "Ariana Grande",
        ).map { it.id }
        assertEquals(listOf("right"), kept)
    }

    @Test
    fun unescapeSpotifyText_decodesEscapes() {
        assertEquals(
            "Selena Gomez & The Scene",
            SpotifyMirrorRepository.unescapeSpotifyText("Selena Gomez \\u0026 The Scene"),
        )
        assertEquals(
            "From \"Fifty\"",
            SpotifyMirrorRepository.unescapeSpotifyText("From \\\"Fifty\\\""),
        )
        assertEquals("Dreams", SpotifyMirrorRepository.unescapeSpotifyText("Dreams\\"))
        assertEquals("Dreams", SpotifyMirrorRepository.unescapeSpotifyText("Dreams"))
    }

    @Test
    fun isLiveTitle_flagsLive() {
        assertTrue(matcher.isLiveTitle("Song - Live"))
        assertTrue(matcher.isLiveTitle("Song (Unplugged)"))
        assertFalse(matcher.isLiveTitle("Song (Official Video)"))
        assertFalse(matcher.isLiveTitle("Alive"))
    }

    @Test
    fun pickBest_prefersStudioOverLive() {
        val live = titledCandidate("live", "T (Live)", "A").copy(duration = 227)
        val studio = titledCandidate("studio", "T", "A").copy(duration = 231)
        assertEquals("studio", matcher.pickBest(listOf(live, studio), 229_000)?.id)
    }

    @Test
    fun needsMore_rules() {
        val good = titledCandidate("good", "T", "A").copy(duration = 229)
        val far = titledCandidate("far", "T", "A").copy(duration = 400)
        val junk = titledCandidate("junk", "Other", "Nobody")
        // Nothing survives: extend.
        assertTrue(matcher.needsMore(listOf(junk), "T", "A", 229_000))
        // Survivor in window: no need.
        assertFalse(matcher.needsMore(listOf(good, junk), "T", "A", 229_000))
        // Survivors exist but none in window: extend.
        assertTrue(matcher.needsMore(listOf(far, junk), "T", "A", 229_000))
        // No duration known: never extend on quality.
        assertFalse(matcher.needsMore(listOf(far), "T", "A", null))
    }
}
