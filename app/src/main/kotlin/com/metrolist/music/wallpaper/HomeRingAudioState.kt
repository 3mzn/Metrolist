/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wallpaper

/**
 * Playback facts the live wallpaper needs, published by [MusicService].
 *
 * The wallpaper is a [android.service.wallpaper.WallpaperService], so it cannot reach the
 * player the way the UI does: `LocalPlayerConnection` is a Compose `staticCompositionLocalOf`
 * provided by `MainActivity`, and there is no Hilt binding for the `ExoPlayer` itself.
 *
 * A service *does* run in the same process as the app (Android's `Services` docs:
 * *"A service runs in the same process as the application in which it is declared"*), so a
 * plain process-wide object is both sufficient and much cheaper than binding a
 * `MediaController`.
 *
 * Written only from the main thread by `MusicService`, read only from the wallpaper's main
 * thread, so plain `@Volatile` fields are sufficient — no locks, no Compose state. Compose
 * state would be actively wrong here: nothing recomposes when these change.
 */
object HomeRingAudioState {

    /**
     * ExoPlayer's audio session id, needed to attach a `Visualizer`. Invalid until the
     * player has produced one, which is why [audioSessionValid] exists separately.
     */
    @Volatile
    var audioSessionId: Int = 0
        private set

    @Volatile
    var isPlaying: Boolean = false
        private set

    @Volatile
    var isMuted: Boolean = false
        private set

    @Volatile
    var isCasting: Boolean = false
        private set

    @Volatile
    var songId: String? = null
        private set

    /** Cover art URL for the colour source. Null while nothing is loaded. */
    @Volatile
    var thumbnailUrl: String? = null
        private set

    /** True once a real session id has been seen, so `0` can mean "not ready". */
    @Volatile
    var audioSessionValid: Boolean = false
        private set

    /**
     * Called by `MusicService` on every playback-state change. Safe to call redundantly.
     */
    fun publish(
        sessionId: Int,
        playing: Boolean,
        muted: Boolean,
        casting: Boolean,
        songId: String?,
        thumbnailUrl: String?,
    ) {
        if (sessionId > 0) {
            audioSessionId = sessionId
            audioSessionValid = true
        }
        isPlaying = playing
        isMuted = muted
        isCasting = casting
        this.songId = songId
        this.thumbnailUrl = thumbnailUrl
    }

    /**
     * Playback stopped entirely. The session id is kept: the audio session outlives a
     * pause, so a resume within the same track does not have to wait for a new one.
     */
    fun clearPlayback() {
        isPlaying = false
    }

    /** True when there is genuinely something to visualise. */
    fun isActive(): Boolean = audioSessionValid && audioSessionId > 0 && isPlaying

    /**
     * The MiniPlayer dims its ring while muted or casting; the wallpaper matches that.
     * Returns true when the ring should sit at the flat dim floor.
     */
    fun isDimmed(): Boolean = isMuted || isCasting
}