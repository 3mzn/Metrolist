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
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
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
import androidx.media3.common.VideoSize
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
 * Spec §6.4: how long the Canvas crossfades in and out.
 *
 * **2000ms — set by user decision during Phase 5.** The spec originally said 800ms, and the first
 * implementation reported as "a quick kinda-smooth 200ms-feeling snap". The cause was not the
 * duration at all: only the Canvas side was animated, while the normal background was hard-cut the
 * instant the first frame landed. Nothing was crossfading, so the perceived speed was the cut.
 *
 * Both sides now ride this one value, and the duration is the thing you actually see. Raised
 * 800ms \u2192 1000ms \u2192 2000ms by user preference once the crossfade worked; 2000ms is the value that
 * reads as smooth.
 */
const val CANVAS_WASH_MS = 2000

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

    /**
     * Centre-crop, applied as a transform on the surface itself.
     *
     * Sizing this view to the video's aspect and letting Compose clip it does **not** work:
     * `dumpsys activity top` reported it at `0,0-1080,2400` on a 1080x2400 screen with
     * `width(maxHeight * aspect)` already applied, so the width request was being dropped and the
     * frame stretched. A TextureView is a native `Surface` holder; its layout bounds do not crop
     * what ExoPlayer draws into it, and Compose's clip does not reach into the surface.
     *
     * So the crop is a transform on the surface content, and it must be **non-uniform**.
     *
     * That last point is the whole difficulty, and it cost three wrong attempts. The scale has to
     * grow on the axis that is overflowing while leaving the other alone — scaling both equally
     * just scales the squash up or down, it never removes it. For a 1080x1920 video in a
     * 1080x2400 view:
     *
     *     scaleX = viewWidth  / videoWidth  = 1.00   <- already full width, leave it
     *     scaleY = viewHeight / videoHeight = 1.25   <- too short, stretch
     *     X gets the 1.25, Y stays 1.00
     *
     * and the overflow that leaves on the horizontal axis is the crop. Sizing by a single
     * uniform `max(...)` cannot express this at all: `max(1080 / (2400 * 0.5625), 1f)` evaluates
     * to exactly `1f`, which is why an earlier version silently applied no transform whatsoever.
     *
     * Scaling about the view centre crops symmetrically, which is what "centre" in centre-crop
     * means and what the spec §5.2 requires.
     */
    fun updateSurfaceTransform(videoWidth: Int, videoHeight: Int) {
        if (surfaceTexture == null) return
        val viewW = width
        val viewH = height
        if (viewW <= 0 || viewH <= 0 || videoWidth <= 0 || videoHeight <= 0) return

        val scaleX = viewW.toFloat() / videoWidth
        val scaleY = viewH.toFloat() / videoHeight

        // Whichever axis overflows sets the crop factor; the other is corrected to match so the
        // aspect ratio is preserved. Equal factors mean the aspect already matches and the view is
        // left untouched — the exact-fill case (spec §5.2, no letterbox, no crop).
        val scale = maxOf(scaleX, scaleY)
        val x = scale / scaleX
        val y = scale / scaleY

        setTransform(
            Matrix().apply {
                setScale(x, y, viewW / 2f, viewH / 2f)
            },
        )
    }
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
 * system composites it *above* the UI layer, which would ignore the alpha wash, sit above the
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
     * Animated wash value, 0..1, owned by the caller (spec §6.4 — 2000ms in and out). Multiplied
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

    // The video's intrinsic size in pixels, driving the surface crop below.
    //
    // Kept as width/height rather than a pre-computed aspect: the crop needs both axes separately
    // to derive the non-uniform scale, and collapsing them into one number early is what hid the
    // arithmetic error for three attempts.
    //
    // Deliberately NOT keyed on the URL. The size listener lives in a `DisposableEffect` keyed
    // only on the player, so its closure captures whichever state object exists at first
    // composition. A URL-keyed remember would hand the layout a fresh object on every engagement
    // while the listener kept writing the discarded one — the size would stay null forever and the
    // crop would never be applied. Instead the object is stable and a `LaunchedEffect` re-seeds
    // it per track.
    var reportedSize by remember { mutableStateOf<VideoSize?>(null) }
    LaunchedEffect(canvas?.canvasUrl) {
        // A stale size must not leak across tracks — but neither can we just clear it: consecutive
        // Canvases almost always share a resolution, in which case ExoPlayer reports no size
        // *change* and no callback comes. Re-seeding from the player's current size covers that; a
        // genuinely new size overwrites it via the listener below.
        reportedSize = exoPlayer.videoSize.takeIf { it.width > 0 && it.height > 0 }
    }

    DisposableEffect(exoPlayer) {
        val listener =
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Timber.tag(TAG).w(error, "canvas playback failed; falling back to the normal background")
                    onPlaybackErrorState()
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    // The only source of truth for the crop math below. ExoPlayer reports this
                    // from the container headers, so it arrives before the first frame — the
                    // §7.4 gate is still holding the old background at that point.
                    reportedSize = videoSize.takeIf { it.width > 0 && it.height > 0 }
                    Timber.tag(TAG).d("video size ${videoSize.width}x${videoSize.height}")
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
    // view promptly even while the fade is still running out.
    val onScreen = isActive && canvas != null && sheetUp
    // Outer box owns visibility: full size on screen, zero size + transparent otherwise, exactly
    // as before. The AndroidView below stays composed in both cases (spec 7.3) — only its
    // container collapses.
    Box(
        modifier =
            (if (onScreen) modifier else modifier.size(0.dp))
                .graphicsLayer { alpha = if (onScreen) washAlpha.coerceIn(0f, 1f) else 0f },
        contentAlignment = Alignment.Center,
    ) {
        // The crop lives here, not in the player. `setVideoScalingMode(SCALE_TO_FIT_WITH_CROPPING)`
        // below is a no-op on TextureView — ExoPlayer applies scaling modes to SurfaceView output
        // only — so a bare TextureView stretches its frame to its own bounds. On any screen taller
        // than 9:16 that reads as a horizontal squish. Sizing the view to the video's own aspect and
        // centring it in a clipped full-screen box is a real centre-crop on every aspect ratio:
        // a 9:20 phone matches height and crops the side overflow, a landscape screen matches width
        // and crops top/bottom, a 9:16 screen is an exact fill.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CanvasTextureView(
                videoSize = reportedSize,
                exoPlayer = exoPlayer,
                isActive = isActive,
                canvas = canvas,
                sheetUp = sheetUp,
                attachedTextureView = attachedTextureView,
                onAttachedTextureViewChange = { attachedTextureView = it },
            )
        }
    }
}

/**
 * The TextureView itself, unchanged apart from taking its size from the caller.
 *
 * Stays composed for the life of the player (spec 7.3) — collapsing only shrinks its container
 * to zero and drops it to GONE, never unmounts it, so re-expanding never rebuilds a surface.
 */
@OptIn(UnstableApi::class)
@Composable
private fun CanvasTextureView(
    videoSize: VideoSize?,
    exoPlayer: ExoPlayer,
    isActive: Boolean,
    canvas: CanvasResult?,
    sheetUp: Boolean,
    attachedTextureView: TextureView?,
    onAttachedTextureViewChange: (TextureView?) -> Unit,
) {
    AndroidView(
        // Full size always. The crop is a surface transform, not a layout size — sizing the view
        // to the video's aspect was measured to have no effect (the view still came out at the
        // container's size), so this is back to a plain fill and the transform does the work.
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            // Named rather than using `apply`, because the listener needs a reference to the
            // TextureView itself and `this` inside the anonymous object is the listener.
            NonInteractiveTextureView(ctx).also { textureView ->
                // Kept, but read the note in the caller first: on TextureView this call changes
                // nothing — ExoPlayer applies scaling modes to SurfaceView output only. With the
                // view sized to the video's own aspect above, every mode is the identity anyway.
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
                        ) {
                            // The surface can arrive or resize before or after the video size is
                            // known, and can resize again on rotation. Re-applying the crop on
                            // every one of those is what makes it correct in all three orders.
                            val size = videoSize ?: return
                            textureView.updateSurfaceTransform(size.width, size.height)
                        }

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
            // Re-apply the crop whenever the video size becomes known. This is the path that matters
            // in practice: the surface callback above usually fired while the size was still
            // null (the player prepares after the view exists), so this is where the transform
            // actually gets set.
            videoSize?.let { size ->
                (view as? NonInteractiveTextureView)?.updateSurfaceTransform(size.width, size.height)
            }
            if (shouldShow) {
                if (attachedTextureView !== view) {
                    attachedTextureView?.let(exoPlayer::clearVideoTextureView)
                    exoPlayer.setVideoTextureView(view)
                    onAttachedTextureViewChange(view)
                }
            } else if (attachedTextureView != null) {
                exoPlayer.clearVideoTextureView(attachedTextureView!!)
                onAttachedTextureViewChange(null)
            }
        },
    )
}
