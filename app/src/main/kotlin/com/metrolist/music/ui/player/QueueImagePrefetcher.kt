package com.metrolist.music.ui.player

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.CachePolicy
import com.metrolist.music.extensions.metadata
import com.metrolist.music.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Prefetches album art for upcoming and recently played tracks so cover art
 * is instantly available from Coil's memory/disk cache when the user skips.
 *
 * - Next 10 tracks prefetched on every track change
 * - Past 5 tracks kept in cache (not re-prefetched, already loaded)
 * - Cancels stale prefetches when the user skips past them
 */
object QueueImagePrefetcher {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentJob: Job? = null
    private val prefetchedIds = mutableSetOf<String>()

    private const val LOOKAHEAD_NEXT = 10
    private const val LOOKBACK_PREV = 5

    fun onTrackChanged(
        playerConnection: PlayerConnection,
        context: Context,
    ) {
        currentJob?.cancel()
        currentJob = scope.launch {
            val queueWindows = playerConnection.queueWindows.value
            val currentIndex = playerConnection.currentWindowIndex.value

            if (queueWindows.isEmpty() || currentIndex < 0) return@launch

            // Track which IDs are still in our prefetch window
            val activeIds = mutableSetOf<String>()
            val startIdx = (currentIndex - LOOKBACK_PREV).coerceAtLeast(0)
            val endIdx = (currentIndex + LOOKAHEAD_NEXT).coerceAtMost(queueWindows.lastIndex)

            for (i in startIdx..endIdx) {
                val mediaItem = queueWindows[i].mediaItem
                val id = mediaItem.mediaId
                activeIds.add(id)
            }

            // Drop tracking for IDs no longer in our window
            prefetchedIds.retainAll(activeIds)

            // Prefetch next N tracks (skip current and already-prefetched)
            val lookaheadEnd = (currentIndex + LOOKAHEAD_NEXT).coerceAtMost(queueWindows.lastIndex)
            for (i in (currentIndex + 1)..lookaheadEnd) {
                if (!isActive) return@launch
                val mediaItem = queueWindows[i].mediaItem
                val id = mediaItem.mediaId
                if (id in prefetchedIds) continue

                val thumbnailUrl = mediaItem.metadata?.thumbnailUrl
                    ?: mediaItem.mediaMetadata.artworkUri?.toString()
                    ?: continue

                val request =
                    ImageRequest.Builder(context)
                        .data(thumbnailUrl)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .networkCachePolicy(CachePolicy.ENABLED)
                        .crossfade(false)
                        .size(100, 100) // Match BLUR background request size for cache key compatibility
                        .build()

                SingletonImageLoader.get(context).enqueue(request)
                prefetchedIds.add(id)
            }

            // Prefetch past 5 tracks for back-skip responsiveness
            val lookbackStart = (currentIndex - LOOKBACK_PREV).coerceAtLeast(0)
            for (i in lookbackStart until currentIndex) {
                if (!isActive) return@launch
                val mediaItem = queueWindows[i].mediaItem
                val id = mediaItem.mediaId
                if (id in prefetchedIds) continue

                val thumbnailUrl = mediaItem.metadata?.thumbnailUrl
                    ?: mediaItem.mediaMetadata.artworkUri?.toString()
                    ?: continue

                val request =
                    ImageRequest.Builder(context)
                        .data(thumbnailUrl)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .networkCachePolicy(CachePolicy.ENABLED)
                        .crossfade(false)
                        .size(100, 100) // Match BLUR background request size
                        .build()

                SingletonImageLoader.get(context).enqueue(request)
                prefetchedIds.add(id)
            }
        }
    }
}
