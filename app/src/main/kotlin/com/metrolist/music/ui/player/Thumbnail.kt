/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.metrolist.music.ui.utils.resize
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.CoverPulseIntensity
import com.metrolist.music.constants.CropAlbumArtKey
import com.metrolist.music.constants.HidePlayerThumbnailKey
import com.metrolist.music.constants.PlayerBackgroundStyle
import com.metrolist.music.constants.PlayerBackgroundStyleKey
import com.metrolist.music.constants.PlayerCoverPulseIntensityKey
import com.metrolist.music.constants.PlayerCoverPulseKey
import com.metrolist.music.constants.UseNewPlayerDesignKey
import com.metrolist.music.constants.PlayerHorizontalPadding
import com.metrolist.music.constants.SeekExtraSeconds
import com.metrolist.music.constants.SwipeThumbnailKey
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.listentogether.RoomRole
import com.metrolist.music.ui.component.CastButton
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.delay
import timber.log.Timber

/** Cover art center in root coordinates, updated by ThumbnailItem. */
val LocalCoverArtCenter = staticCompositionLocalOf { Offset.Zero }

/**
 * SPEC_SPOTIFY_CANVAS Phase 5: the Canvas hold duration (spec §6.1).
 *
 * **1000ms — reduced from the original 2000ms at the user's request.** Invisible: no progress
 * ring, no overlay, one haptic at the threshold and cancel on early release.
 *
 * The shorter window makes the "no fetch before the threshold" rule more load-bearing rather than
 * less: a 1s hold is short enough that an accidental brush of the artwork reaches it, so the early
 * release path carries more of the real usage. If it turns out too easy to trigger by accident,
 * this is the single value to change — nothing else in the gesture depends on its magnitude.
 *
 * No fetch is issued before this window completes, whatever the value.
 */
private const val CANVAS_HOLD_MS = 1000L

/**
 * Log tag for the Canvas hold gesture.
 *
 * Deliberately kept, unlike other diagnostics. The first implementation of this gesture failed
 * *silently and permanently* — a wrong exception type meant every press after the first was
 * ignored with no crash and no log — and diagnosing that cost several build cycles. Timber is
 * stripped by Metrolist's ProGuard config in release, so this costs nothing in the shipped build
 * but leaves a trail in debug. One tag, used at both terminal paths, so "nothing happened" is never
 * ambiguous again.
 */
private const val TAG_HOLD = "CanvasHold"

/**
 * SPEC_SPOTIFY_CANVAS §6.3: how far the finger must travel upward to count as a swipe.
 *
 * A distance, not a fling: there is no velocity term and no direction tolerance. Swiping down
 * is not a gesture at all (spec §6.3), so the gesture is one-sided by construction rather than by
 * a direction check that could be got wrong.
 */
private val CANVAS_SWIPE_THRESHOLD = 60.dp

/**
 * SPEC_SPOTIFY_CANVAS §6.4: the artwork's own fade, in both directions.
 *
 * **1000ms — raised from the spec's 400ms by user decision during Phase 6.**
 *
 * Deliberately *not* equal to `CANVAS_WASH_MS`. The artwork comes back in half the time the
 * background crossfade takes, so it is restored well before that finishes. Tying the two together
 * would look tidier in the source and would mean watching a semi-transparent artwork sit over the
 * old background for the whole two seconds.
 */
private const val CANVAS_ARTWORK_FADE_MS = 1000

/**
 * Log tag for the Canvas swipe gesture.
 *
 * Same rationale as [TAG_HOLD]: a handler that silently stops responding produces no crash and
 * no symptom, so both terminal paths are logged. Kept separate from [TAG_HOLD] because the two
 * gestures are supposed to exclude each other, and a log showing both firing on one touch is the
 * clearest possible evidence that the arbitration below has failed.
 */
private const val TAG_SWIPE = "CanvasSwipe"

/**
 * Pre-calculated thumbnail dimensions to avoid repeated calculations during recomposition.
 * All values are computed once and cached.
 */
@Immutable
data class ThumbnailDimensions(
    val itemWidth: Dp,
    val containerSize: Dp,
    val thumbnailSize: Dp,
    val cornerRadius: Dp
)

/**
 * Cached media items data to prevent recalculation on every recomposition.
 */
@Immutable
data class MediaItemsData(
    val items: List<MediaItem>,
    val currentIndex: Int
)

/**
 * Calculate thumbnail dimensions once based on container size.
 * This function is marked as @Stable to indicate it produces stable results.
 * In landscape mode, uses the smaller dimension (height) to ensure square thumbnail fits.
 */
@Stable
private fun calculateThumbnailDimensions(
    containerWidth: Dp,
    containerHeight: Dp = containerWidth,
    horizontalPadding: Dp = PlayerHorizontalPadding,
    cornerRadius: Dp = ThumbnailCornerRadius,
    isLandscape: Boolean = false
): ThumbnailDimensions {
    // In landscape, use height as the constraining dimension for a square thumbnail
    val effectiveSize = if (isLandscape) {
        minOf(containerWidth, containerHeight) - (horizontalPadding * 2)
    } else {
        containerWidth - (horizontalPadding * 2)
    }
    return ThumbnailDimensions(
        itemWidth = containerWidth,
        containerSize = containerWidth,
        thumbnailSize = effectiveSize,
        cornerRadius = cornerRadius * 2
    )
}

/**
 * Get media items for the thumbnail carousel.
 * Calculates previous, current, and next items based on shuffle mode.
 */
@Stable
private fun getMediaItems(
    player: Player,
    swipeThumbnail: Boolean
): MediaItemsData {
    val timeline = player.currentTimeline
    val currentIndex = player.currentMediaItemIndex
    val shuffleModeEnabled = player.shuffleModeEnabled
    
    val currentMediaItem = try {
        player.currentMediaItem
    } catch (e: Exception) { null }
    
    val previousMediaItem = if (swipeThumbnail && !timeline.isEmpty) {
        val previousIndex = timeline.getPreviousWindowIndex(
            currentIndex,
            Player.REPEAT_MODE_OFF,
            shuffleModeEnabled
        )
        if (previousIndex != C.INDEX_UNSET) {
            try { player.getMediaItemAt(previousIndex) } catch (e: Exception) { null }
        } else null
    } else null

    val nextMediaItem = if (swipeThumbnail && !timeline.isEmpty) {
        val nextIndex = timeline.getNextWindowIndex(
            currentIndex,
            Player.REPEAT_MODE_OFF,
            shuffleModeEnabled
        )
        if (nextIndex != C.INDEX_UNSET) {
            try { player.getMediaItemAt(nextIndex) } catch (e: Exception) { null }
        } else null
    } else null

    val items = listOfNotNull(previousMediaItem, currentMediaItem, nextMediaItem)
    val currentMediaIndex = items.indexOf(currentMediaItem)
    
    return MediaItemsData(items, currentMediaIndex)
}

/**
 * Get text color based on player background style.
 * Computed once per background style change.
 */
@Stable
@Composable
private fun getTextColor(playerBackground: PlayerBackgroundStyle): Color {
    return when (playerBackground) {
        PlayerBackgroundStyle.DEFAULT -> MaterialTheme.colorScheme.onBackground
        PlayerBackgroundStyle.BLUR -> Color.White
        PlayerBackgroundStyle.GRADIENT -> Color.White
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Thumbnail(
    sliderPositionProvider: () -> Long?,
    modifier: Modifier = Modifier,
    isPlayerExpanded: () -> Boolean = { true },
    isLandscape: Boolean = false,
    isListenTogetherGuest: Boolean = false,
    onCoverArtCenterChanged: (Offset) -> Unit = {},
    onCanvasHold: () -> Unit = {},
    canvasSwipeAvailable: Boolean = false,
    canvasArtworkRevealed: Boolean = false,
    onCanvasSwipe: () -> Unit = {},
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current

    // Collect states
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val error by playerConnection.error.collectAsState()
    val queueTitle by playerConnection.queueTitle.collectAsStateWithLifecycle()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsStateWithLifecycle()
    val canSkipNext by playerConnection.canSkipNext.collectAsStateWithLifecycle()

    // Preferences - computed once
    // Disable swipe for Listen Together guests
    val swipeThumbnailPref by rememberPreference(SwipeThumbnailKey, true)
    val swipeThumbnail = swipeThumbnailPref && !isListenTogetherGuest
    val hidePlayerThumbnail by rememberPreference(HidePlayerThumbnailKey, false)
    val cropAlbumArt by rememberPreference(CropAlbumArtKey, false)
    val playerBackground by rememberEnumPreference(
        key = PlayerBackgroundStyleKey,
        defaultValue = PlayerBackgroundStyle.DEFAULT
    )
    
    // Pre-calculate text color based on background style
    val textBackgroundColor = getTextColor(playerBackground)
    
    // Grid state
    val thumbnailLazyGridState = rememberLazyGridState()
    
    // Calculate media items data - memoized
    val mediaItemsData by remember(
        playerConnection.player.currentMediaItemIndex,
        playerConnection.player.shuffleModeEnabled,
        swipeThumbnail,
        mediaMetadata
    ) {
        derivedStateOf {
            getMediaItems(playerConnection.player, swipeThumbnail)
        }
    }
    
    val mediaItems = mediaItemsData.items
    val currentMediaIndex = mediaItemsData.currentIndex

    // Snap behavior - created once per grid state
    val thumbnailSnapLayoutInfoProvider = remember(thumbnailLazyGridState) {
        ThumbnailSnapLayoutInfoProvider(
            lazyGridState = thumbnailLazyGridState,
            positionInLayout = { layoutSize, itemSize ->
                (layoutSize / 2f - itemSize / 2f)
            },
            velocityThreshold = 500f
        )
    }

    // Current item tracking - derived state for efficiency
    val currentItem by remember { derivedStateOf { thumbnailLazyGridState.firstVisibleItemIndex } }
    val itemScrollOffset by remember { derivedStateOf { thumbnailLazyGridState.firstVisibleItemScrollOffset } }

    // Handle swipe to change song
    LaunchedEffect(itemScrollOffset) {
        if (!thumbnailLazyGridState.isScrollInProgress || !swipeThumbnail || itemScrollOffset != 0 || currentMediaIndex < 0) return@LaunchedEffect

        if (currentItem > currentMediaIndex && canSkipNext) {
            playerConnection.player.seekToNext()
        } else if (currentItem < currentMediaIndex && canSkipPrevious) {
            playerConnection.player.seekToPreviousMediaItem()
        }
    }

    // Update position when song changes
    LaunchedEffect(mediaMetadata, canSkipPrevious, canSkipNext) {
        val index = maxOf(0, currentMediaIndex)
        if (index >= 0 && index < mediaItems.size) {
            try {
                thumbnailLazyGridState.animateScrollToItem(index)
            } catch (e: Exception) {
                thumbnailLazyGridState.scrollToItem(index)
            }
        }
    }

    LaunchedEffect(playerConnection.player.currentMediaItemIndex) {
        val index = mediaItemsData.currentIndex
        if (index >= 0 && index != currentItem) {
            thumbnailLazyGridState.scrollToItem(index)
        }
    }

    // Seek effect state
    var showSeekEffect by remember { mutableStateOf(false) }
    var seekDirection by remember { mutableStateOf("") }

    // Cover art center position for particle system
    var coverArtCenter by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .graphicsLayer {
                // Use hardware layer for entire Thumbnail to ensure smooth 120Hz animations
                compositingStrategy = CompositingStrategy.Offscreen
            }
    ) {
        // Error view
        AnimatedVisibility(
            visible = error != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .padding(32.dp)
                .align(Alignment.Center),
        ) {
            error?.let { playbackError ->
                PlaybackError(
                    error = playbackError,
                    retry = playerConnection.player::prepare,
                )
            }
        }

        // Main thumbnail view
        AnimatedVisibility(
            visible = error == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .fillMaxSize()
                .then(if (!isLandscape) Modifier.statusBarsPadding() else Modifier),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (isLandscape) Arrangement.Center else Arrangement.Top
            ) {
                // Now Playing header - hide in landscape mode
                if (!isLandscape) {
                    ThumbnailHeader(
                        queueTitle = queueTitle,
                        albumTitle = mediaMetadata?.album?.title,
                        textColor = textBackgroundColor
                    )
                }
                
                // Thumbnail content
                BoxWithConstraints(
                    contentAlignment = Alignment.Center,
                    modifier = if (isLandscape) {
                        Modifier.weight(1f, false)
                    } else {
                        Modifier.fillMaxSize()
                    }
                ) {
                    // Calculate dimensions once per size change, considering landscape mode
                    val dimensions = remember(maxWidth, maxHeight, isLandscape) {
                        calculateThumbnailDimensions(
                            containerWidth = maxWidth,
                            containerHeight = maxHeight,
                            isLandscape = isLandscape
                        )
                    }

                    // Remember the onSeek callback to prevent recomposition
                    val onSeekCallback = remember {
                        { direction: String, showEffect: Boolean ->
                            seekDirection = direction
                            showSeekEffect = showEffect
                        }
                    }
                    
                    // Derive scroll enabled state to prevent unnecessary recomposition
                    val isScrollEnabled by remember(swipeThumbnail) {
                        derivedStateOf { swipeThumbnail && isPlayerExpanded() }
                    }
                    
                    LazyHorizontalGrid(
                        state = thumbnailLazyGridState,
                        rows = GridCells.Fixed(1),
                        flingBehavior = rememberSnapFlingBehavior(thumbnailSnapLayoutInfoProvider),
                        userScrollEnabled = isScrollEnabled,
                        modifier = if (isLandscape) {
                            Modifier.size(dimensions.thumbnailSize + (PlayerHorizontalPadding * 2))
                        } else {
                            Modifier.fillMaxSize()
                        }
                    ) {
                        items(
                            items = mediaItems,
                            key = { item -> 
                                item.mediaId.ifEmpty { "unknown_${item.hashCode()}" }
                            }
                        ) { item ->
                            ThumbnailItem(
                                item = item,
                                dimensions = dimensions,
                                hidePlayerThumbnail = hidePlayerThumbnail,
                                cropAlbumArt = cropAlbumArt,
                                textBackgroundColor = textBackgroundColor,
                                layoutDirection = layoutDirection,
                                onSeek = onSeekCallback,
                                playerConnection = playerConnection,
                                context = context,
                                isLandscape = isLandscape,
                                isListenTogetherGuest = isListenTogetherGuest,
                                currentMediaId = mediaMetadata?.id,
                                currentMediaThumbnail = mediaMetadata?.thumbnailUrl,
                                onCoverArtCenter = {
                                    coverArtCenter = it
                                    onCoverArtCenterChanged(it)
                                },
                                onCanvasHold = onCanvasHold,
                                canvasSwipeAvailable = canvasSwipeAvailable,
                                canvasArtworkRevealed = canvasArtworkRevealed,
                                onCanvasSwipe = onCanvasSwipe,
                            )
                        }
                    }
                }
            }
        }

        // Seek effect
        LaunchedEffect(showSeekEffect) {
            if (showSeekEffect) {
                delay(1000)
                showSeekEffect = false
            }
        }

        AnimatedVisibility(
            visible = showSeekEffect,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            SeekEffectOverlay(seekDirection = seekDirection)
        }
    }
}

/**
 * Header component showing "Now Playing" and queue/album title.
 */
@Composable
private fun ThumbnailHeader(
    queueTitle: String?,
    albumTitle: String?,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val listenTogetherManager = LocalListenTogetherManager.current
    val listenTogetherRoleState = listenTogetherManager?.role?.collectAsStateWithLifecycle(initialValue = RoomRole.NONE)
    val isListenTogetherGuest = listenTogetherRoleState?.value == RoomRole.GUEST
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 48.dp)
        ) {
            // Listen Together indicator
            if (listenTogetherRoleState?.value != RoomRole.NONE) {
                Text(
                    text = if (listenTogetherRoleState?.value == RoomRole.HOST) "Hosting Listen Together" else "Listening Together",
                    style = MaterialTheme.typography.titleMedium,
                    color = textColor
                )
            } else {
                Text(
                    text = stringResource(R.string.now_playing),
                    style = MaterialTheme.typography.titleMedium,
                    color = textColor
                )
            }
            val playingFrom = queueTitle ?: albumTitle
            if (!playingFrom.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = playingFrom,
                    style = MaterialTheme.typography.titleMedium,
                    color = textColor.copy(alpha = 0.8f),
                    maxLines = 1,
                    modifier = Modifier.basicMarquee()
                )
            }
        }
    }
}

/**
 * SPEC_SPOTIFY_CANVAS §6.3: arbitrates the two artwork gestures when a single touch satisfies
 * both.
 *
 * The hold and the swipe are separate `pointerInput` blocks, and that is the documented pattern
 * rather than a shortcut: the Compose gesture detectors are top-level and the first one blocks
 * the coroutine forever, so two detectors in one block means the second never runs. Separate
 * blocks is the only way to have two listeners at all.
 *
 * The cost of separate blocks is that neither can cancel the other. Both consume nothing — which
 * is exactly what leaves the carousel's horizontal song-swipe working — so neither ever finds out
 * that the touch was taken. That matters here because the hold deliberately ignores finger drift
 * (platform long-press semantics), so one touch can satisfy both:
 *
 *     hold 1s -> Canvas engages -> finger still down -> flick up 60dp -> swipe fires -> Canvas
 *     dismisses again
 *
 * The user watches it appear and then vanish. The latch closes that in *both* directions:
 * whoever acts first claims the touch, and the other stands down before it can act.
 *
 * One-shot would have fixed only the sequence above and left its mirror open — flick up to hide
 * the artwork, then pause without lifting, and the hold fires at 1s and brings the artwork back.
 *
 * [open] is keyed on the pointer id rather than resetting unconditionally, because the two blocks
 * reach it a frame or two apart and a blind reset from whichever runs second would discard a
 * claim the other had already made.
 */
private class CanvasGestureLatch {
    private var pointerId: PointerId? = null

    var claimed: Boolean = false
        private set

    fun open(id: PointerId) {
        if (pointerId != id) {
            pointerId = id
            claimed = false
        }
    }

    /** Returns false if the touch is already claimed, in which case the caller must not act. */
    fun claim(): Boolean {
        if (claimed) return false
        claimed = true
        return true
    }
}

/**
 * Individual thumbnail item in the carousel.
 */
@Composable
private fun ThumbnailItem(
    item: MediaItem,
    dimensions: ThumbnailDimensions,
    hidePlayerThumbnail: Boolean,
    cropAlbumArt: Boolean,
    textBackgroundColor: Color,
    layoutDirection: LayoutDirection,
    onSeek: (String, Boolean) -> Unit,
    playerConnection: com.metrolist.music.playback.PlayerConnection,
    context: android.content.Context,
    isLandscape: Boolean = false,
    isListenTogetherGuest: Boolean = false,
    currentMediaId: String? = null,
    currentMediaThumbnail: String? = null,
    onCoverArtCenter: (Offset) -> Unit = {},
    modifier: Modifier = Modifier,
    onCanvasHold: () -> Unit = {},
    canvasSwipeAvailable: Boolean = false,
    canvasArtworkRevealed: Boolean = false,
    onCanvasSwipe: () -> Unit = {},
) {
    val incrementalSeekSkipEnabled by rememberPreference(SeekExtraSeconds, defaultValue = false)
    var skipMultiplier by remember { mutableIntStateOf(1) }
    var lastTapTime by remember { mutableLongStateOf(0L) }

    // "To Listen" playlist: double-tap seeking is disabled so a shared song can't be scrubbed past
    // the 50%/95% listen milestones. Swiping to change tracks stays available.
    val activePlaylistId by playerConnection.currentPlaylistId.collectAsStateWithLifecycle()
    val seekRestricted = isListenTogetherGuest ||
        activePlaylistId == PlaylistEntity.TO_LISTEN_PLAYLIST_ID

    // SPEC_COVER_PULSE Phase 3: bass-pulse for the current item's cover art only.
    // The Visualizer lifecycle + frame loop lives in BottomSheetPlayer (always
    // composed) because Thumbnail and MiniPlayer are mutually exclusive via
    // BottomSheet. Thumbnail only reads CoverBassPulse.scaleFor here.
    val useNewPlayerDesign by rememberPreference(UseNewPlayerDesignKey, true)
    val coverPulse by rememberPreference(PlayerCoverPulseKey, true)
    val pulseIntensity by rememberEnumPreference(
        PlayerCoverPulseIntensityKey,
        CoverPulseIntensity.MEDIUM,
    )
    val isCurrentItem = item.mediaId == currentMediaId
    val pulseGated = coverPulse && useNewPlayerDesign && isCurrentItem

    // SPEC_SPOTIFY_CANVAS Phase 5: the Canvas hold lives on the artwork square only.
    val latestOnCanvasHold by rememberUpdatedState(onCanvasHold)
    val canvasVibrator = remember(context) { context.getSystemService(Vibrator::class.java) }

    // SPEC_SPOTIFY_CANVAS Phase 6: the swipe-up reveal lives on the same square and shares one
    // latch with the hold, so a single touch can never drive both.
    val latestOnCanvasSwipe by rememberUpdatedState(onCanvasSwipe)
    val canvasGestureLatch = remember { CanvasGestureLatch() }
    val swipeThresholdPx = with(LocalDensity.current) { CANVAS_SWIPE_THRESHOLD.toPx() }
    // Spec §6.4/§6.5: a fixed 1000ms fade in both directions, never finger-tracked. The state is
    // two exclusive booleans rather than a scrubbed value, so a plain animateFloatAsState is the
    // whole model — there is nothing to interpolate from the finger.
    val artworkAlpha by animateFloatAsState(
        targetValue = if (canvasArtworkRevealed) 0f else 1f,
        animationSpec = tween(CANVAS_ARTWORK_FADE_MS),
        label = "canvasArtworkAlpha",
    )

    Box(
        modifier = modifier
            .then(
                if (isLandscape) {
                    Modifier.size(dimensions.thumbnailSize + (PlayerHorizontalPadding * 2))
                } else {
                    Modifier
                        .width(dimensions.itemWidth)
                        .fillMaxSize()
                }
            )
            .padding(horizontal = PlayerHorizontalPadding)
            .graphicsLayer {
                // Render entire thumbnail item on separate hardware layer for smooth animations
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        if (seekRestricted) return@detectTapGestures

                        val currentPosition = playerConnection.player.currentPosition
                        val duration = playerConnection.player.duration

                        val now = System.currentTimeMillis()
                        if (incrementalSeekSkipEnabled && now - lastTapTime < 1000) {
                            skipMultiplier++
                        } else {
                            skipMultiplier = 1
                        }
                        lastTapTime = now

                        val skipAmount = 5000 * skipMultiplier

                        val isLeftSide = (layoutDirection == LayoutDirection.Ltr && offset.x < size.width / 2) ||
                                (layoutDirection == LayoutDirection.Rtl && offset.x > size.width / 2)

                        if (isLeftSide) {
                            playerConnection.player.seekTo((currentPosition - skipAmount).coerceAtLeast(0))
                            onSeek(context.getString(R.string.seek_backward_dynamic, skipAmount / 1000), true)
                        } else {
                            playerConnection.player.seekTo((currentPosition + skipAmount).coerceAtMost(duration))
                            onSeek(context.getString(R.string.seek_forward_dynamic, skipAmount / 1000), true)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(dimensions.thumbnailSize)
                .clip(RoundedCornerShape(dimensions.cornerRadius))
                .onGloballyPositioned { coords ->
                    val pos = coords.positionInRoot()
                    val sz = coords.size
                    onCoverArtCenter(Offset(pos.x + sz.width / 2f, pos.y + sz.height / 2f))
                }
                .graphicsLayer {
                    // Overflow allowed: scale applies outside the clip, so the
                    // cover grows past its frame without re-laying out siblings.
                    val s = if (pulseGated) CoverBassPulse.scaleFor(pulseIntensity) else 1f
                    scaleX = s
                    scaleY = s
                    // Spec §6.4: the swipe's reveal. Folded into the layer that already exists
                    // for the pulse rather than adding a second one.
                    //
                    // This fades the whole square, which includes the cast button sitting at its
                    // top-right corner. That is deliberate: the cast button is positioned against
                    // the artwork square and is part of it, so leaving it behind would strand a
                    // control floating over the Canvas. Reading `artworkAlpha` inside the block
                    // defers it to the layer's update phase, so a reveal animates without
                    // recomposing this composable.
                    alpha = artworkAlpha
                }
                .pointerInput(isCurrentItem) {
                    // `detectTapGestures` cannot do this job: its long-press fires at the platform
                    // timeout (~400ms), not the 1s we want. So this is hand-rolled: hold still for the full
                    // window with the finger down and the hold registers; lift early, drift past
                    // touch slop, or get stolen by the carousel scroll and nothing happens.
                    //
                    // Current item only: a hold on a peeking neighbour must not engage the Canvas
                    // for the centred track.
                    if (!isCurrentItem) return@pointerInput
                    // Consuming nothing at all is deliberate: the carousel's own drag detector must
                    // keep winning, and Phase 5 depends on a stolen mid-hold silently cancelling
                    // rather than the horizontal swipe breaking.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        // Phase 6: arm the shared latch so the swipe can find out this touch
                        // started, whichever of the two handlers observes the down first.
                        canvasGestureLatch.open(down.id)
                        // The hold window, written with `withTimeoutOrNull` so there is **no
                        // exception to catch and therefore no exception type to get wrong**.
                        //
                        // That is deliberate, and it is a scar. The first version used `withTimeout`
                        // in a `try`/`catch`, and it failed silently and permanently: inside a
                        // pointerInput scope, `withTimeout` resolves to the AwaitPointerEventScope
                        // *member*, which shadows the kotlinx import and throws
                        // PointerEventTimeoutCancellationException — a plain CancellationException,
                        // unrelated to kotlinx's TimeoutCancellationException. Catching the wrong one
                        // missed forever, the exception escaped and killed this handler for good, and
                        // every subsequent press did nothing with no crash and no log.
                        //
                        // `withTimeoutOrNull` returns null instead of throwing, so that whole class
                        // of mistake cannot recur here. Read of ui 1.11.4 source
                        // (SuspendingPointerInputFilter.kt) confirmed the contract. If this gesture
                        // is ever moved out of a pointer scope, note that the *kotlinx*
                        // withTimeoutOrNull has a different signature and semantics.
                        //
                        // No touch-slop check: a drifting finger still counts as holding (platform
                        // long-press semantics). A real scroll is still a silent cancel, because the
                        // carousel's consumption surfaces as isConsumed below.
                        val reachedThreshold =
                            withTimeoutOrNull(CANVAS_HOLD_MS) {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    // (changedToUp() is foundation-internal; !pressed is the
                                    // public equivalent.)
                                    if (event.changes.fastAll { !it.pressed }) {
                                        // Lift before the window ends: abandoned press, costs
                                        // nothing, and — critically — issues no fetch (spec §6.1).
                                        return@withTimeoutOrNull
                                    }
                                    if (event.changes.fastAny { it.isConsumed }) {
                                        // Stolen mid-hold (carousel scroll): nothing.
                                        return@withTimeoutOrNull
                                    }
                                    if (canvasGestureLatch.claimed) {
                                        // Phase 6: the swipe already claimed this touch.
                                        // Standing down here rather than at the threshold
                                        // matters, because "no touch-slop check" above is why
                                        // this hold can still be running when the user flicks.
                                        return@withTimeoutOrNull
                                    }
                                }
                            } == null

                        if (reachedThreshold && canvasGestureLatch.claim()) {
                            // Full window elapsed with the finger still down: the hold registered.
                            // Haptic fires here, at the threshold — never on press (spec §6.1).
                            Timber.tag(TAG_HOLD).d("canvas hold threshold reached")
                            canvasVibrator?.vibrate(
                                VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE),
                            )
                            latestOnCanvasHold()
                        } else if (reachedThreshold) {
                            // Phase 6: the swipe got here first. The hold must stay silent — no
                            // haptic, no toggle — or the one touch would drive both.
                            Timber.tag(TAG_HOLD).d("hold lost the touch to the swipe")
                        } else {
                            Timber.tag(TAG_HOLD).d("hold abandoned before the threshold")
                        }
                    }
                }
                .pointerInput(isCurrentItem, canvasSwipeAvailable) {
                    // SPEC_SPOTIFY_CANVAS §6.3: swipe up to reveal, swipe up again to restore.
                    //
                    // Gated here rather than inside the loop, and gated on a Canvas actually
                    // rendering, which is what makes "independent of `swipeThumbnail`" true rather
                    // than merely intended: in every state without a Canvas this block does not
                    // exist, so the artwork has no vertical gesture at all for the carousel to
                    // compete with. With `swipeThumbnail` off there is likewise nothing to
                    // compete with, and the swipe still works.
                    if (!isCurrentItem || !canvasSwipeAvailable) return@pointerInput

                    // Deliberately hand-rolled, not `detectVerticalDragGestures`. That detector is
                    // built on `awaitVerticalTouchSlopOrCancellation`, the same helper recorded in
                    // spec §16.9 as suspending indefinitely in this codebase without ever
                    // delivering a down — the fault that broke the hold. Using the built-in here
                    // would walk straight back into it.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        canvasGestureLatch.open(down.id)
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.fastAll { !it.pressed }) {
                                // Released short of the threshold: ignored, silently.
                                return@awaitEachGesture
                            }
                            if (event.changes.fastAny { it.isConsumed }) {
                                // Stolen by the carousel's horizontal drag. Same rule as the hold:
                                // yield silently and leave the song-swipe intact.
                                return@awaitEachGesture
                            }
                            if (canvasGestureLatch.claimed) {
                                // The hold already acted on this touch.
                                return@awaitEachGesture
                            }
                            // Net displacement from where the finger landed rather than
                            // accumulated deltas: absolute positions cannot drift over a long drag,
                            // and `positionChange()` would report only the unconsumed remainder.
                            val travelledUp =
                                down.position.y - event.changes.first().position.y
                            // One-sided by construction. A downward drag cannot satisfy this, and
                            // because the value is net, a drag that first went down still has to
                            // end up 60dp up overall to count.
                            if (travelledUp >= swipeThresholdPx) {
                                if (canvasGestureLatch.claim()) {
                                    Timber.tag(TAG_SWIPE).d("canvas swipe up threshold reached")
                                    latestOnCanvasSwipe()
                                } else {
                                    Timber.tag(TAG_SWIPE).d("swipe lost the touch to the hold")
                                }
                                // The gesture is over either way; one swipe is one toggle, and
                                // staying in the loop would let the drift after the threshold
                                // claim a second one.
                                return@awaitEachGesture
                            }
                        }
                    }
                }
        ) {
            if (hidePlayerThumbnail) {
                HiddenThumbnailPlaceholder(textBackgroundColor = textBackgroundColor)
            } else {
                val artworkUriToUse = if (item.mediaId == currentMediaId && !currentMediaThumbnail.isNullOrBlank()) {
                    currentMediaThumbnail
                } else {
                    item.mediaMetadata.artworkUri?.toString()
                }

                ThumbnailImage(
                    artworkUri = artworkUriToUse,
                    cropArtwork = cropAlbumArt
                )
            }
            
            // Cast button at top-right corner of thumbnail
            CastButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                tintColor = textBackgroundColor
            )
        }
    }
}

/**
 * Placeholder shown when thumbnail is hidden.
 */
@Composable
private fun HiddenThumbnailPlaceholder(
    textBackgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.small_icon),
            contentDescription = stringResource(R.string.hide_player_thumbnail),
            modifier = Modifier.size(120.dp)
        )
    }
}

/**
 * Actual thumbnail image with caching and hardware layer rendering.
 */
@Composable
private fun ThumbnailImage(
    artworkUri: String?,
    cropArtwork: Boolean,
    modifier: Modifier = Modifier,
) {
    // Cover-art fix (mirrors MiniPlayer): request the raw URL every other surface
    // warms instead of a unique size variant Coil never cached, fall back to the
    // warmed 544 variant on error. Single-attempt loads stayed blank on any
    // slow/failed fetch with no recovery.
    val context = LocalContext.current
    var useFallback by remember(artworkUri) { mutableStateOf(false) }
    val request =
        remember(artworkUri, useFallback) {
            ImageRequest.Builder(context)
                .data(if (useFallback) artworkUri?.resize(544, 544) else artworkUri)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.ENABLED)
                .build()
        }
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                // Use offscreen compositing for hardware acceleration during animations
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = if (cropArtwork) ContentScale.Crop else ContentScale.Fit,
            onError = { if (!useFallback) useFallback = true },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * Seek effect overlay showing seek direction.
 */
@Composable
private fun SeekEffectOverlay(
    seekDirection: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = seekDirection,
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(8.dp)
    )
}
