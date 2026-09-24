/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
