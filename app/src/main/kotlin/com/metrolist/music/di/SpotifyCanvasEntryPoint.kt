/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.di

import com.metrolist.music.utils.spotify.SpotifyCanvasRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Lets the player UI reach the Spotify Canvas repository.
 *
 * The Canvas ExoPlayer is owned by the UI layer, not by `MusicService` (spec 7.2), and that layer is
 * a Composable rather than an injectable class, so it goes through an entry point. This mirrors the
 * existing `LyricsHelperEntryPoint` usage in `Player.kt`.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SpotifyCanvasEntryPoint {
    fun spotifyCanvasRepository(): SpotifyCanvasRepository
}
