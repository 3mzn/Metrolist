/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import com.google.firebase.Timestamp

class SharedPlaylistModelsTest {
    private val validMap =
        mapOf<String, Any?>(
            "name" to "Date Night",
            "thumbnailUrl" to "https://example.com/cover.jpg",
            "sharedByUid" to "uid-eman",
            "sharedByName" to "eman",
            "sharedWith" to "uid-aswini",
            "songs" to listOf("songA", "songB"),
            "createdAt" to 1_000L,
            "updatedAt" to 2_000L,
        )

    @Test
    fun fromMap_parsesValidDoc() {
        val cloud = SharedPlaylistCloud.fromMap("LPabcdefgh", validMap)
        assertNotNull(cloud)
        assertEquals("LPabcdefgh", cloud!!.id)
        assertEquals("Date Night", cloud.name)
        assertEquals("https://example.com/cover.jpg", cloud.thumbnailUrl)
        assertEquals("uid-eman", cloud.sharedByUid)
        assertEquals("eman", cloud.sharedByName)
        assertEquals("uid-aswini", cloud.sharedWith)
        assertEquals(listOf("songA", "songB"), cloud.songs)
        assertEquals(1_000L, cloud.createdAt)
        assertEquals(2_000L, cloud.updatedAt)
    }

    @Test
    fun fromMap_defaultsUpdatedAtToCreatedAt() {
        val map = validMap - "updatedAt"
        val cloud = SharedPlaylistCloud.fromMap("LPabcdefgh", map)
        assertNotNull(cloud)
        assertEquals(1_000L, cloud!!.updatedAt)
    }

    @Test
    fun fromMap_readsFirestoreTimestamps() {
        val cloud = SharedPlaylistCloud.fromMap(
            "LPabcdefgh",
            validMap + (
                "createdAt" to Timestamp(3, 500_000_000)
            ) + (
                "updatedAt" to Timestamp(4, 250_000_000)
            ),
        )

        assertNotNull(cloud)
        assertEquals(3_500L, cloud!!.createdAt)
        assertEquals(4_250L, cloud.updatedAt)
    }

    @Test
    fun fromMap_defaultsMissingSongsToEmpty() {
        val map = validMap - "songs"
        val cloud = SharedPlaylistCloud.fromMap("LPabcdefgh", map)
        assertNotNull(cloud)
        assertEquals(emptyList<String>(), cloud!!.songs)
    }

    @Test
    fun fromMap_rejectsMissingRequiredFields() {
        assertNull(SharedPlaylistCloud.fromMap("id", validMap - "name"))
        assertNull(SharedPlaylistCloud.fromMap("id", validMap - "sharedByUid"))
        assertNull(SharedPlaylistCloud.fromMap("id", validMap - "sharedWith"))
        assertNull(SharedPlaylistCloud.fromMap("id", validMap - "createdAt"))
    }

    @Test
    fun remoteAdditions_excludesExistingAndLocallyKnownSongs() {
        assertEquals(
            setOf("partnerSong"),
            SharedPlaylistRepository.remoteAdditions(
                previousCloudSongs = setOf("oldSong"),
                cloudSongs = listOf("oldSong", "partnerSong", "localSong"),
                localSongs = setOf("oldSong", "localSong"),
            ),
        )
    }

    @Test
    fun remoteAdditions_initialSnapshotUsesLocalSongsAsBaseline() {
        assertEquals(
            setOf("partnerSong"),
            SharedPlaylistRepository.remoteAdditions(
                previousCloudSongs = null,
                cloudSongs = listOf("localSong", "partnerSong"),
                localSongs = setOf("localSong"),
            ),
        )
    }

    @Test
    fun arrayOperations_areIdempotent() {
        val songs = listOf("a", "b")
        assertEquals(songs, SharedPlaylistRepository.applyArrayUnion(songs, "b"))
        assertEquals(listOf("a", "b"), SharedPlaylistRepository.applyArrayUnion(songs, "b"))
        assertEquals(listOf("a"), SharedPlaylistRepository.applyArrayRemove(songs, "b"))
        assertEquals(songs, SharedPlaylistRepository.applyArrayRemove(songs, "missing"))
    }
}
