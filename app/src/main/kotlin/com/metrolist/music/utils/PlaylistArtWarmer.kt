package com.metrolist.music.utils

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.ui.utils.resize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

data class ArtworkWarmProgress(
    val total: Int,
    val done: Int,
)

/**
 * Warms artwork for every song in a playlist so covers show up even offline.
 *
 * Two failure modes are handled:
 * - Songs with a thumbnailUrl: enqueued into Coil's disk cache (Coil serves
 *   cached images with no network, so one successful fetch = offline forever).
 * - Songs with a null thumbnailUrl: the URL is first recovered from InnerTube's
 *   /next endpoint, written back to song.thumbnailUrl, then prefetched like rest.
 *
 * Already-warmed songs are tracked per warmer instance so recompositions and
 * repeat visits don't re-enqueue. Coil itself dedupes identical in-flight requests
 * and skips network for disk-cached images, so no manual cache probing is needed.
 */
class PlaylistArtWarmer(
    private val database: MusicDatabase,
    private val playlistId: String,
) {
    private val _progress = MutableStateFlow<ArtworkWarmProgress?>(null)
    val progress: StateFlow<ArtworkWarmProgress?> = _progress

    private var job: Job? = null
    private val warmedIds = mutableSetOf<String>()

    fun start(
        scope: CoroutineScope,
        context: Context,
    ) {
        if (job?.isActive == true) return
        job =
            scope.launch(Dispatchers.IO) {
                try {
                    val pending =
                        database
                            .playlistSongs(playlistId)
                            .first()
                            .filter { it.song.id !in warmedIds }
                    if (pending.isEmpty()) return@launch

                    _progress.value = ArtworkWarmProgress(pending.size, 0)
                    var done = 0
                    val loader = SingletonImageLoader.get(context)

                    for (playlistSong in pending) {
                        if (!isActive) return@launch
                        val song = playlistSong.song

                        var url = song.song.thumbnailUrl
                        if (url == null && !song.song.isLocal) {
                            url = fetchThumbnailUrl(song.id)
                            if (url != null) {
                                database.updateThumbnailUrl(song.id, url)
                            }
                        }

                        if (url != null) {
                            val request =
                                ImageRequest.Builder(context)
                                    .data(url.resize(544, 544))
                                    // Memory cache stays DISABLED: a bulk warm of hundreds of
                                    // covers must not churn the 15% memory cache and evict
                                    // currently-visible art. Disk is what matters for offline.
                                    .memoryCachePolicy(CachePolicy.DISABLED)
                                    .diskCachePolicy(CachePolicy.ENABLED)
                                    .networkCachePolicy(CachePolicy.ENABLED)
                                    .crossfade(false)
                                    .build()
                            loader.enqueue(request)
                            warmedIds.add(song.id)
                        }

                        done++
                        _progress.value = ArtworkWarmProgress(pending.size, done)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Playlist artwork warm failed")
                    reportException(e)
                } finally {
                    _progress.value = null
                }
            }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private suspend fun fetchThumbnailUrl(videoId: String): String? {
        // Gentle pacing — InnerTube rate-limits aggressive bursts.
        delay(250)
        return try {
            YouTube
                .next(WatchEndpoint(videoId = videoId))
                .getOrNull()
                ?.items
                ?.find { it.id == videoId }
                ?.thumbnail
        } catch (e: CancellationException) {
            // Never swallow cancellation (delay/next are cancellable) or the loop
            // would grind through the rest of the playlist after the user left.
            throw e
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "PlaylistArtWarmer"
    }
}
