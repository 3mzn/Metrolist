/*
 * Spotify Canvas support in Metrolist.
 *
 * Copyright (C) 2025 maxrave-dev (SimpMusic)
 * Copyright (C) 2025 The Metrolist Group
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.metrolist.music.ui.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.metrolist.music.di.CanvasCacheEntryPoint
import com.metrolist.music.utils.spotify.CanvasResult
import dagger.hilt.android.EntryPointAccessors
import okhttp3.OkHttpClient
import timber.log.Timber

private const val TAG = "SpotifyCanvas"

/**
 * How far the player sheet must be raised before the Canvas is considered on screen.
 *
 * `BottomSheetState.isExpanded` cannot be used for this: it is `value == upperBound`, exact
 * equality against the anchor, so it stays false throughout the expand and collapse animations and
 * the Canvas would appear only after the sheet had finished moving. `progress` is continuous.
 *
 * Not zero, because below it the sheet is effectively the mini player, where a full-screen Canvas
 * layer would sit over the rest of the app. Not one either, so a collapsed sheet still gets the
 * 800ms fade-out to play (spec §6.4) before the view goes GONE.
 */
const val CANVAS_ACTIVE_PROGRESS_THRESHOLD = 0.35f

/**
 * A TextureView that never consumes touch.
 *
 * The Canvas sits behind the player and is **decorative only**. Left to itself a `TextureView`
 * becomes an opaque full-screen touch target: it sits inside the player's own layout, so it eats
 * every press meant for the seek bar, the transport row and the swipeable content above it. The
 * symptom is severe and confusing — the app works perfectly until a track *with* a Canvas starts,
 * at which point half the player stops responding.
 *
 * Both callbacks below are the fix, and both are needed:
 *  - [dispatchTouchEvent] returning false stops the view being considered a touch target at all.
 *  - [onTouchEvent] returning false stops it consuming a gesture that reached it anyway.
 *
 * The layer is visual by design (spec 5): controls stay fully interactive and readable above it.
 */
private class NonInteractiveTextureView(
    context: Context,
) : TextureView(context) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    override fun onTouchEvent(event: MotionEvent): Boolean = false
}

/**
 * Muted, video-only audio attributes.
 *
 * Built locally rather than borrowed from `MusicService` so this layer cannot mutate the music
 * player's attributes even by accident.
 */
private val CANVAS_AUDIO_ATTRIBUTES: AudioAttributes =
    AudioAttributes
        .Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

/**
 * The Canvas video layer: a full-screen, centre-cropped, muted looping video behind the player.
 *
 * **TextureView, not SurfaceView** (spec 7.1). A SurfaceView punches a hole in the window and the
 * system composites it *above* the UI layer, which would ignore the 800ms alpha wash, sit above the
 * controls instead of behind them, and pop in at full opacity. A TextureView goes through normal UI
 * compositing, so fades and z-order behave. The cost is slightly more GPU work per frame, which is
 * accepted for a 3-8 second loop on a music screen.
 *
 * **A separate, muted ExoPlayer, owned here** (spec 7.2). This is not a second output on the music
 * player. The previous attempt merged audio and video into one `MergingMediaSource` and died on
 * `IllegalStateException: Children enabled at different positions.` \u2014 see spec 16.8. It also shared
 * failure, so a video fault ended the music. Both are avoided by construction here:
 *
 *  - [CANVAS_AUDIO_ATTRIBUTES] with `handleAudioFocus = false`. The Canvas player **cannot take
 *    audio focus**, so the music is untouchable even if this layer misbehaves.
 *  - Its own `CacheDataSource` over the Canvas `SimpleCache`, so it shares no state with the music
 *    player's cache.
 *
 * **Centre-crop, never letterbox** (spec 5.2). A Canvas is a 9:16 vertical video; in landscape it
 * crops heavily and that is accepted rather than letterboxed.
 *
 * **First-frame gate** (spec 7.4): [onFirstFrameReady] fires on the first rendered frame.
 *
 * ⚠ **The gate is wired but NOT ACTUALLY GATING, and that is deliberate for now.** `Player.kt`
 * assigns the flag and never reads it, so the background is swapped the moment the Canvas becomes
 * active rather than when its first frame arrives. It has not been needed because the Canvas
 * currently appears on its own for any track that has one, and by then the surface already holds a
 * decoded frame.
 *
 * The gate only becomes load-bearing once the Canvas appears *after a deliberate gesture*: spec 6.1
 * issues no fetch before the 1s hold completes, so there is a real window between the gesture and
 * the first decoded frame, and spec 7.4's crossfade-from-the-existing-background is what hides it.
 * **Phase 5 must wire this up as part of the wash** - see its verification list. Until then there is
 * a theoretical black gap on the first play of a Canvas track, which has not been observed on
 * device but has also not been disproven.
 *
 * **The TextureView is never unmounted** (spec 7.3: *"Player re-expanded \u2014 Canvas still present,
 * no re-init, no black flash"*). An earlier version returned early when [canvas] was null, which
 * destroyed the view; re-expanding then had to allocate a fresh `SurfaceTexture`, re-attach it and
 * decode a frame, costing ~300-400ms and flashing black first. Instead the view stays composed for
 * the whole life of the player and is switched off with [isActive] while collapsed. That also means
 * a collapsed player never has a full-screen view sitting over the app \u2014 which is what broke every
 * touch when the view stayed mounted and visible.
 *
 * Lifecycle: the ExoPlayer and the TextureView are released only when this composable leaves the
 * tree. Neither is stopped when the song pauses or the app is backgrounded.
 *
 * Phase 5: the [onFirstFrameReady] gate is now honoured by the caller, so the normal background is
 * held until this reports a rendered frame and the 800ms crossfade begins (spec §7.4). Through
 * Phase 4 that gate was wired but dead, and only appeared to work because the Canvas arrived on its
 * own rather than after a gesture.
 */
@OptIn(UnstableApi::class)
@Composable
fun CanvasBackgroundLayer(
    canvas: CanvasResult?,
    modifier: Modifier = Modifier,
    /**
     * Whether the Canvas is currently on screen. `false` while the player is collapsed: the layer
     * keeps its view and its decoded state but is fully transparent and takes no touches.
     */
    isActive: Boolean = canvas != null,
    /**
     * Animated wash value, 0..1, owned by the caller (spec §6.4 — 800ms in and out). Multiplied
     * into the layer's own visibility alpha. Kept separate from [isActive] so the 800ms fade-out
     * can render while the layer is still mounted: `isActive` going false collapses the view
     * immediately, which would cut the fade dead.
     *
     * Named `washAlpha` and not `alpha` deliberately: inside the `graphicsLayer` block below, a
     * parameter named `alpha` would shadow `GraphicsLayerScope.alpha` and the assignment would try
     * to write the caller's val.
     */
    washAlpha: Float = 1f,
    /**
     * Whether the player sheet is meaningfully raised. When false the view goes GONE immediately,
     * even mid-fade: a collapsing sheet must never leave a full-screen layer over the app, and
     * nobody watches a crossfade on a sheet that is sliding away.
     */
    sheetUp: Boolean = true,
    /**
     * Bumped by the caller on every fresh engagement. The first-frame listener is keyed on it so
     * that re-engaging an already-playing item — same URL, player warm, no new callback coming —
     * still reports readiness instead of wedging the wash at 0 forever.
     */
    readinessKey: Any? = null,
    onFirstFrameReady: () -> Unit = {},
    onPlaybackError: () -> Unit = {},
) {
    val context = LocalContext.current

    val dataSourceFactory =
        remember(context) {
            val cache =
                EntryPointAccessors
                    .fromApplication(context.applicationContext, CanvasCacheEntryPoint::class.java)
                    .canvasCache()
            CacheDataSource
                .Factory()
                .setCache(cache)
                // Phase 3 pre-caches the video bytes into this same cache, so this normally serves
                // from disk rather than the network.
                .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(OkHttpClient()))
        }

    val onFirstFrameReadyState by rememberUpdatedState(onFirstFrameReady)
    val onPlaybackErrorState by rememberUpdatedState(onPlaybackError)

    val exoPlayer =
        remember {
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build()
                .apply {
                    setAudioAttributes(CANVAS_AUDIO_ATTRIBUTES, /* handleAudioFocus = */ false)
                    repeatMode = Player.REPEAT_MODE_ONE
                    playWhenReady = true
                }
        }

    DisposableEffect(exoPlayer) {
        val listener =
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Timber.tag(TAG).w(error, "canvas playback failed; falling back to the normal background")
                    onPlaybackErrorState()
                }
            }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Keep the ExoPlayer running across a collapse so re-expanding is instant (spec 7.3). Only a
    // genuine track change gets a new URL, so `play()` on an existing item just resumes.
    LaunchedEffect(canvas?.canvasUrl) {
        val url = canvas?.canvasUrl
        if (url == null) return@LaunchedEffect
        if (exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString() != url) {
            exoPlayer.setMediaItem(MediaItem.fromUri(url))
            exoPlayer.prepare()
        } else if (exoPlayer.playbackState == Player.STATE_IDLE) {
            // Same track, but the player was stopped rather than paused. Recover.
            exoPlayer.prepare()
        }
        exoPlayer.play()
    }

    // Latched per URL: a new track's video must earn its own first frame before the wash may
    // start (spec §7.4). Without the key, a previous track's latched `true` would open the gate
    // before the new video had decoded a single frame.
    var firstFrameSeen by remember(canvas?.canvasUrl) { mutableStateOf(false) }

    // Media3 exposes no getter for the currently attached TextureView, so attachment is tracked
    // here. The surface is attached whenever the Canvas is on screen and cleared when it is not;
    // clearing is what actually removes the view from the touch dispatch while collapsed.
    var attachedTextureView by remember { mutableStateOf<TextureView?>(null) }
    DisposableEffect(exoPlayer, canvas?.canvasUrl, readinessKey) {
        if (canvas != null) {
            val listener =
                object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        if (!firstFrameSeen) {
                            firstFrameSeen = true
                            Timber.tag(TAG).d("first canvas frame rendered")
                            onFirstFrameReadyState()
                        }
                    }
                }
            exoPlayer.addListener(listener)
            // Already warm (re-engagement of the same URL): no new callback is coming, because the
            // player never stopped. Report immediately instead of wedging the wash at 0.
            if (firstFrameSeen) onFirstFrameReadyState()
            onDispose { exoPlayer.removeListener(listener) }
        } else {
            onDispose { }
        }
    }

    // The view stays composed for the life of the player so re-expanding never rebuilds a surface
    // (spec 7.3). While inactive it is made genuinely inert.
    //
    // `isEnabled = false` alone is NOT enough here. Compose's AndroidView host sits above the
    // player's own content in the touch dispatch, so a disabled-but-present view still swallowed
    // every press on the seek bar, the transport row and the content behind the sheet. Clearing
    // the video surface is what actually removes it from the dispatch path, and it also stops the
    // player drawing into a view nobody can see. Re-attaching on the way back is a no-op for the
    // player - the item is still prepared - so this costs nothing on re-expand.
    // While inactive the layer is not merely transparent - it is collapsed to zero size and hidden.
    //
    // Three earlier attempts did not work, and the view dump says why:
    //  - `dispatchTouchEvent`/`onTouchEvent` returning false: a view that does not handle the event
    //    still had it delivered first, and the player beneath never got it.
    //  - `isEnabled = false`: the dump shows the view still `V..D` - visible and drawable. Android
    //    hit-tests disabled views; disabled only stops *handling*, not *receiving*.
    //  - `clearVideoTextureView()`: removes the video surface, but the View itself stays in the
    //    hierarchy at full size and keeps intercepting.
    //
    // Zero size plus GONE is the only thing that actually takes the view out of hit-testing: a
    // zero-area view contains no point, so no touch can land on it. Confirmed against
    // `dumpsys activity top`, which showed the view at `0,0-900,1600` before this change.
    // Full-size only while genuinely on screen. Zero-size + GONE is what takes the view out of
    // hit-testing (see the note above): `sheetUp` is part of the condition so a collapse hides the
    // view promptly even while the 800ms fade is still running out.
    val onScreen = isActive && canvas != null && sheetUp
    val layerModifier =
        if (onScreen) {
            modifier
        } else {
            // `size(0.dp)` rather than `fillMaxSize`, and GONE via alpha 0 + no layout footprint.
            modifier.size(0.dp)
        }

    AndroidView(
        modifier = layerModifier.graphicsLayer { alpha = if (onScreen) washAlpha.coerceIn(0f, 1f) else 0f },
        factory = { ctx ->
            // Named rather than using `apply`, because the listener needs a reference to the
            // TextureView itself and `this` inside the anonymous object is the listener.
            NonInteractiveTextureView(ctx).also { textureView ->
                // Centre-crop, never letterbox (spec 5.2). A Canvas is a 9:16 vertical video and
                // crops hard in landscape, which is accepted rather than letterboxed.
                exoPlayer.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                textureView.surfaceTextureListener =
                    object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            texture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            // Attaching here rather than in the factory is deliberate: the surface
                            // does not exist until this fires, and setVideoTextureView before that
                            // is a no-op that silently produces a black rectangle.
                            exoPlayer.setVideoTextureView(textureView)
                        }

                        override fun onSurfaceTextureSizeChanged(
                            texture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) = Unit

                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = true

                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                    }
            }
        },
        // Clearing the surface is what actually removes this view from the touch dispatch while
        // the sheet is collapsed. `isEnabled` is belt and braces for the case where the view
        // override is ever lost.
        update = { view ->
            // Same condition as the modifier above: the view must be gone from hit-testing the
            // moment the sheet drops, fade or no fade.
            val shouldShow = isActive && canvas != null && sheetUp
            // Hidden and zero-sized while inactive, so it is not merely transparent but genuinely
            // absent from hit-testing. See the note above the AndroidView.
            view.visibility = if (shouldShow) View.VISIBLE else View.GONE
            view.isClickable = false
            view.isFocusable = false
            if (shouldShow) {
                if (attachedTextureView !== view) {
                    attachedTextureView?.let(exoPlayer::clearVideoTextureView)
                    exoPlayer.setVideoTextureView(view)
                    attachedTextureView = view
                }
            } else if (attachedTextureView != null) {
                exoPlayer.clearVideoTextureView(attachedTextureView!!)
                attachedTextureView = null
            }
        },
    )
}
