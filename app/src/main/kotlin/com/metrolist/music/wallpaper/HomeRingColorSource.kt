/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wallpaper

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.music.ui.theme.PlayerColorExtractor
import com.metrolist.music.ui.utils.resize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cover-art colour source for the wallpaper rings.
 *
 * Deliberately mirrors what `Player.kt`, `MiniPlayer.kt` and `ChatBox.kt` already do
 * rather than refactoring them: Coil load at 100×100 → `Palette.maximumColorCount(8)` →
 * `PlayerColorExtractor.extractGradientColors`, cached per song. Following the existing
 * pattern keeps the ring colour consistent with the colours the user already sees, and
 * avoids touching three shipped call sites for the sake of de-duplication.
 *
 * The MiniPlayer exposes no way to *read* its extracted colour — it is a local
 * `mutableStateOf` inside a composable — so the wallpaper cannot simply borrow it.
 *
 * Runs on its own supervisor scope: the wallpaper engine can be created and destroyed
 * repeatedly as the home screen is shown and hidden, and no result outlives its request.
 */
object HomeRingColorSource {

    private const val TAG = "HomeRingColor"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Song the cached colour belongs to, so a track change invalidates it. */
    @Volatile
    private var cachedSongId: String? = null

    @Volatile
    var color: Int = FALLBACK_COLOR
        private set

    /**
     * Requests the colour for [songId]/[thumbnailUrl]. No-ops when the song is already
     * cached. Failures fall back rather than throwing — a missing ring colour must never
     * take down the wallpaper.
     */
    fun request(context: Context, imageLoader: ImageLoader, songId: String?, thumbnailUrl: String?) {
        if (songId == null || thumbnailUrl == null) {
            cachedSongId = null
            color = FALLBACK_COLOR
            return
        }
        if (songId == cachedSongId) return
        cachedSongId = songId
        scope.launch {
            color = extract(context, imageLoader, thumbnailUrl) ?: run {
                cachedSongId = null
                FALLBACK_COLOR
            }
        }
    }

    /** Drops the cache, e.g. when playback stops. */
    fun clear() {
        cachedSongId = null
        color = FALLBACK_COLOR
    }

    private suspend fun extract(
        context: Context,
        imageLoader: ImageLoader,
        thumbnailUrl: String,
    ): Int? = withContext(Dispatchers.IO) {
        val bitmap = runCatching {
            val request = ImageRequest.Builder(context)
                .data(thumbnailUrl)
                .size(100, 100)
                .allowHardware(false)
                .build()
            imageLoader.execute(request).image?.toBitmap()
        }.getOrNull() ?: run {
            // Same fallback the art loaders use: when the raw URL fails (failing-songs
            // case), retry once against the warmed 544 variant.
            runCatching {
                val retry = ImageRequest.Builder(context)
                    .data(thumbnailUrl.resize(544, 544))
                    .size(100, 100)
                    .allowHardware(false)
                    .build()
                imageLoader.execute(retry).image?.toBitmap()
            }.getOrNull()
        } ?: return@withContext null

        val palette = withContext(Dispatchers.Default) {
            Palette.from(bitmap)
                .maximumColorCount(8)
                .resizeBitmapArea(100 * 100)
                .generate()
        }
        PlayerColorExtractor.extractGradientColors(
            palette = palette,
            fallbackColor = FALLBACK_COLOR,
        ).firstOrNull()?.let { it.toArgbCompat() }
    }

    private fun Color.toArgbCompat(): Int =
        android.graphics.Color.argb(
            (alpha * 255f).toInt().coerceIn(0, 255),
            (red * 255f).toInt().coerceIn(0, 255),
            (green * 255f).toInt().coerceIn(0, 255),
            (blue * 255f).toInt().coerceIn(0, 255),
        )

    /**
     * Matches the MiniPlayer's fallback, which uses white when no artwork colour is
     * available (`MiniPlayer.kt:461`).
     */
    private const val FALLBACK_COLOR = 0xFFFFFFFF.toInt()
}