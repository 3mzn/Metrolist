/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.di

import androidx.media3.datasource.cache.Cache
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Lets the player UI reach the Canvas video cache that `provideCanvasCache` builds.
 *
 * The Canvas `ExoPlayer` is owned by the UI layer (spec 7.2), and that layer is a Composable rather
 * than an injectable class, so it goes through an entry point \u2014 the same pattern `Player.kt`
 * already uses for `LyricsHelperEntryPoint`.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CanvasCacheEntryPoint {
    @CanvasCache
    fun canvasCache(): Cache
}
