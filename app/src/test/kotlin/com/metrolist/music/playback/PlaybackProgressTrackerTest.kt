/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.firebase.auth.FirebaseAuth
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.social.SentSong
import com.metrolist.music.social.SongSharingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Behaviour of the "To Listen" progress tracker: which songs get tracked, when the 50% and 95%
 * milestones fire, and that neither can be replayed by scrubbing.
 *
 * The tracker's Room/Firestore lookups run on an injected dispatcher, so every test drives them with
 * [advanceUntilIdle] rather than racing a real background thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackProgressTrackerTest {
    private val songId = "testSong123"
    private val sentSongId = "sentSong456"
    private val toListen = PlaylistEntity.TO_LISTEN_PLAYLIST_ID

    private lateinit var repository: SongSharingRepository
    private lateinit var database: MusicDatabase
    private lateinit var player: Player

    private val sentSong = SentSong(
        id = sentSongId,
        songId = songId,
        songTitle = "Test Song",
        songArtist = "Artist",
        songDuration = 100,
        fromUid = "sender",
        fromUsername = "Sender",
        toUid = "recipient",
        sentAt = 0,
    )

    @Before
    fun setUp() {
        mockkStatic(FirebaseAuth::class)
        every { FirebaseAuth.getInstance() } returns mockk(relaxed = true)

        repository = mockk(relaxed = true)
        database = mockk(relaxed = true)
        player = mockk(relaxed = true)

        every { player.currentMediaItem } returns mediaItem(songId)
        every { player.currentPosition } returns 0L
        every { player.duration } returns DURATION_MS
        every { player.isPlaying } returns true
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // region tracking scope

    @Test
    fun `songs from another playlist are not tracked`() = trackerTest { tracker, scope ->
        tracker.trackProgress(player, scope, "user_playlist_123")
        advanceUntilIdle()

        assertNull(tracker.currentTrackingSongId)
        assertNull(tracker.currentSentSongId)
        coVerify(exactly = 0) { repository.getSentSongBySongId(any()) }
    }

    @Test
    fun `songs with no playlist context are not tracked`() = trackerTest { tracker, scope ->
        tracker.trackProgress(player, scope, null)
        advanceUntilIdle()

        assertNull(tracker.currentTrackingSongId)
        assertNull(tracker.currentSentSongId)
        coVerify(exactly = 0) { repository.getSentSongBySongId(any()) }
    }

    @Test
    fun `transition into another playlist does not start tracking`() = trackerTest { tracker, _ ->
        tracker.onMediaItemTransition(
            mediaItem(songId),
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            "regular_playlist",
        )
        advanceUntilIdle()

        assertNull(tracker.currentTrackingSongId)
        coVerify(exactly = 0) { repository.getSentSongBySongId(any()) }
    }

    @Test
    fun `song absent from the local playlist is not looked up in Firestore`() = trackerTest { tracker, scope ->
        every { database.checkInPlaylist(toListen, songId) } returns 0

        tracker.trackProgress(player, scope, toListen)
        advanceUntilIdle()

        assertNull(tracker.currentTrackingSongId)
        coVerify(exactly = 0) { repository.getSentSongBySongId(any()) }
    }

    @Test
    fun `shared song from the To Listen playlist starts tracking`() = trackerTest { tracker, scope ->
        stubSharedSong()

        tracker.trackProgress(player, scope, toListen)
        advanceUntilIdle()

        assertEquals(songId, tracker.currentTrackingSongId)
        assertEquals(sentSongId, tracker.currentSentSongId)
    }

    // endregion

    // region milestones

    @Test
    fun `reaching half way marks the song as listened`() = trackerTest { tracker, scope ->
        stubSharedSong()
        startTracking(tracker, scope)

        seekTo(50)
        tracker.trackProgress(player, scope, toListen)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.markSongAsListened(sentSongId) }
    }

    @Test
    fun `the half way milestone fires once across repeated polls`() = trackerTest { tracker, scope ->
        stubSharedSong()
        startTracking(tracker, scope)

        seekTo(50)
        repeat(5) {
            tracker.trackProgress(player, scope, toListen)
        }
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.markSongAsListened(sentSongId) }
    }

    @Test
    fun `seeking back after the half way milestone does not replay it`() = trackerTest { tracker, scope ->
        stubSharedSong()
        startTracking(tracker, scope)

        seekTo(50)
        tracker.trackProgress(player, scope, toListen)
        advanceUntilIdle()

        // Scrub back to the start and play through the milestone again.
        seekTo(5)
        tracker.trackProgress(player, scope, toListen)
        seekTo(60)
        tracker.trackProgress(player, scope, toListen)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.markSongAsListened(sentSongId) }
    }

    @Test
    fun `reaching the end marks the song complete and stops tracking`() = trackerTest { tracker, scope ->
        stubSharedSong()
        startTracking(tracker, scope)

        seekTo(96)
        tracker.trackProgress(player, scope, toListen)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.markSongAsCompleted(sentSongId, songId) }
        assertNull(tracker.currentTrackingSongId)
        assertNull(tracker.currentSentSongId)
    }

    @Test
    fun `skipping straight to the end completes without a separate listened update`() = trackerTest { tracker, scope ->
        stubSharedSong()
        startTracking(tracker, scope)

        // The two milestones are mutually exclusive: passing 95% in one jump only completes.
        seekTo(96)
        tracker.trackProgress(player, scope, toListen)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.markSongAsCompleted(sentSongId, songId) }
        coVerify(exactly = 0) { repository.markSongAsListened(any()) }
    }

    @Test
    fun `milestones are not evaluated before the duration is known`() = trackerTest { tracker, scope ->
        stubSharedSong()
        startTracking(tracker, scope)

        every { player.duration } returns 0L
        every { player.currentPosition } returns 60_000L
        tracker.trackProgress(player, scope, toListen)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.markSongAsListened(any()) }
        coVerify(exactly = 0) { repository.markSongAsCompleted(any(), any()) }
    }

    // endregion

    // region blacklist and reset

    @Test
    fun `a song with no share record is only looked up once`() = trackerTest { tracker, scope ->
        every { database.checkInPlaylist(toListen, songId) } returns 1
        coEvery { repository.getSentSongBySongId(songId) } returns null

        tracker.trackProgress(player, scope, toListen)
        advanceUntilIdle()
        tracker.trackProgress(player, scope, toListen)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getSentSongBySongId(songId) }
    }

    @Test
    fun `tracking state does not leak into the next song`() = trackerTest { tracker, scope ->
        stubSharedSong()
        startTracking(tracker, scope)
        assertEquals(songId, tracker.currentTrackingSongId)

        val nextSongId = "newSong456"
        val next = mediaItem(nextSongId)
        every { player.currentMediaItem } returns next
        every { database.checkInPlaylist(toListen, nextSongId) } returns 0

        tracker.onMediaItemTransition(next, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, toListen)
        advanceUntilIdle()

        assertNull(tracker.currentTrackingSongId)
        assertNull(tracker.currentSentSongId)
    }

    @Test
    fun `going idle stops tracking`() = trackerTest { tracker, scope ->
        stubSharedSong()
        startTracking(tracker, scope)
        assertEquals(songId, tracker.currentTrackingSongId)

        tracker.onPlaybackStateChanged(Player.STATE_IDLE)
        advanceUntilIdle()

        assertNull(tracker.currentTrackingSongId)
        assertNull(tracker.currentSentSongId)
    }

    // endregion

    private fun stubSharedSong() {
        every { database.checkInPlaylist(toListen, songId) } returns 1
        coEvery { repository.getSentSongBySongId(songId) } returns sentSong
    }

    /** Runs the initial lookup so the tracker is armed before the test drives progress. */
    private suspend fun TestScope.startTracking(tracker: PlaybackProgressTracker, scope: CoroutineScope) {
        tracker.trackProgress(player, scope, toListen)
        advanceUntilIdle()
    }

    private fun seekTo(percent: Int) {
        every { player.currentPosition } returns DURATION_MS * percent / 100
    }

    private fun mediaItem(id: String) = MediaItem.Builder().setMediaId(id).build()

    /**
     * Builds a tracker whose async work runs on the test scheduler, so [advanceUntilIdle] is enough
     * to reach a settled state.
     */
    private fun trackerTest(
        body: suspend TestScope.(PlaybackProgressTracker, CoroutineScope) -> Unit,
    ) = runTest {
        val tracker = PlaybackProgressTracker(
            songSharingRepository = repository,
            database = database,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        body(tracker, this)
        tracker.cleanup()
    }

    private companion object {
        const val DURATION_MS = 100_000L
    }
}
