/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.strategy.ContentHints
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.constants.AudioQualityKey
import com.metrolist.music.constants.DownloadLyricsWithDownloadsKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.di.DownloadCache
import com.metrolist.music.di.PlayerCache
import com.metrolist.music.lyrics.LyricsHelper
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.utils.NetworkConnectivityObserver
import com.metrolist.music.utils.YTPlayerUtils
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.enumPreference
import com.metrolist.music.utils.get
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import timber.log.Timber
import java.time.LocalDateTime
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil
@Inject
constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: Cache,
    @PlayerCache val playerCache: Cache,
    private val lyricsHelper: LyricsHelper,
    private val networkConnectivity: NetworkConnectivityObserver,
) {
    private val TAG = "DownloadUtil"
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
    private val songUrlCache = StreamUrlCache()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var reconcileJob: Job? = null

    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    private val dataSourceFactory =
        ResolvingDataSource.Factory(
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(
                    OkHttpDataSource.Factory(
                        OkHttpClient.Builder()
                            .proxy(YouTube.proxy)
                            .proxyAuthenticator { _, response ->
                                YouTube.proxyAuth?.let { auth ->
                                    response.request.newBuilder()
                                        .header("Proxy-Authorization", auth)
                                        .build()
                                } ?: response.request
                            }
                            .build(),
                    ),
                ),
        ) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")
            val length = if (dataSpec.length >= 0) dataSpec.length else 1

            if (playerCache.isCached(mediaId, dataSpec.position, length)) {
                return@Factory dataSpec
            }

            songUrlCache[mediaId]?.let { cachedStream ->
                return@Factory dataSpec
                    .withUri(cachedStream.url.toUri())
                    .withRequestHeaders(dataSpec.httpRequestHeaders + cachedStream.requestHeaders)
            }
            val cacheGeneration = songUrlCache.generation(mediaId)

            val playbackData = runBlocking(Dispatchers.IO) {
                val song = database.songEntity(mediaId)
                YTPlayerUtils.playerResponseForPlayback(
                    mediaId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                    contentHints = ContentHints(
                        isExplicit = song?.explicit,
                        isUploaded = song?.isUploaded,
                    ),
                )
            }.getOrThrow()
            val format = playbackData.format

            val actualContentLength = format.contentLength ?: run {
                var length: Long? = null
                val client = OkHttpClient.Builder()
                    .proxy(YouTube.proxy)
                    .proxyAuthenticator { _, response ->
                        YouTube.proxyAuth?.let { auth ->
                            response.request.newBuilder()
                                .header("Proxy-Authorization", auth)
                                .build()
                        } ?: response.request
                    }
                    .build()
                val request = okhttp3.Request.Builder()
                    .head()
                    .url(playbackData.streamUrl)
                    .apply {
                        playbackData.streamHeaders.forEach { (name, value) ->
                            header(name, value)
                        }
                    }
                    .build()
                client.newCall(request).execute().use { response ->
                    length = response.header("Content-Length")?.toLongOrNull()
                }
                length ?: error("Failed to retrieve content length")
            }

            database.query {
                upsert(
                    FormatEntity(
                        id = mediaId,
                        itag = format.itag,
                        mimeType = format.mimeType.substringBefore(";"),
                        codecs =
                            format.mimeType
                                .substringAfter("codecs=", missingDelimiterValue = "")
                                .substringBefore(";")
                                .trim()
                                .removeSurrounding("\""),
                        bitrate = format.bitrate,
                        sampleRate = format.audioSampleRate,
                        contentLength = actualContentLength,
                        loudnessDb = playbackData.audioConfig?.loudnessDb,
                        perceptualLoudnessDb = playbackData.audioConfig?.perceptualLoudnessDb,
                        playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    ),
                )

                // Metadata registration only — dateDownload is intentionally NOT set here.
                // It belongs solely to onDownloadChanged()'s STATE_COMPLETED branch below,
                // which only fires once the download has actually finished. Setting it here
                // (at URL-resolve time, i.e. the moment the download merely *starts*) would
                // mark the song as "cached" before a single byte is written.
                val existing = getSongByIdBlocking(mediaId)?.song
                val updatedSong = existing ?: SongEntity(
                    id = mediaId,
                    title = playbackData.videoDetails?.title ?: "Unknown",
                    duration = playbackData.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0,
                    thumbnailUrl = playbackData.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url,
                    dateDownload = null,
                    isDownloaded = false
                )

                upsert(updatedSong)
            }

            val streamUrl = playbackData.streamUrl.let {
                "${it}&range=0-${actualContentLength}"
            }

            songUrlCache.put(
                mediaId = mediaId,
                url = streamUrl,
                requestHeaders = playbackData.streamHeaders,
                clientName = playbackData.streamClient,
                expiresInSeconds = playbackData.streamExpiresInSeconds,
                expectedGeneration = cacheGeneration,
            )
            dataSpec
                .withUri(streamUrl.toUri())
                .withRequestHeaders(dataSpec.httpRequestHeaders + playbackData.streamHeaders)
        }

    val downloadNotificationHelper =
        DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

    @OptIn(DelicateCoroutinesApi::class)
    val downloadManager: DownloadManager =
        DownloadManager(
            context,
            databaseProvider,
            downloadCache,
            dataSourceFactory,
            Executor(Runnable::run)
        ).apply {
            maxParallelDownloads = 3
            addListener(
                object : DownloadManager.Listener {
                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: Download,
                        finalException: Exception?,
                    ) {
                        if (download.state == Download.STATE_FAILED && finalException.isExpiredStreamError()) {
                            songUrlCache.invalidate(download.request.id)
                        }

                        downloads.update { map ->
                            map.toMutableMap().apply {
                                set(download.request.id, download)
                            }
                        }

                        scope.launch {
                            when (download.state) {
                                Download.STATE_COMPLETED -> {
                                    database.updateDownloadedInfo(download.request.id, true, LocalDateTime.now())
                                    // Lyrics-for-downloads: fetch + store now that audio is on disk.
                                    maybeWarmLyrics(download.request.id)
                                }
                                Download.STATE_FAILED,
                                Download.STATE_STOPPED,
                                Download.STATE_REMOVING -> {
                                    database.updateDownloadedInfo(download.request.id, false, null)
                                }
                                else -> {
                                }
                            }
                        }
                    }

                    override fun onDownloadRemoved(
                        downloadManager: DownloadManager,
                        download: Download,
                    ) {
                        val downloadId = download.request.id
                        songUrlCache.invalidate(downloadId)

                        runCatching {
                            database.updateDownloadedInfo(downloadId, false, null)
                            // Lyrics live exactly as long as the download: drop the cached row.
                            database.deleteLyricsById(downloadId)
                        }.onSuccess {
                            downloads.update { map ->
                                map.toMutableMap().apply {
                                    remove(downloadId)
                                }
                            }
                            Timber.tag(TAG).d("Successfully removed download $downloadId from in-memory map")
                        }.onFailure { error ->
                            Timber.tag(TAG).e(error, "Failed to update database for removed download $downloadId, keeping in-memory entry")
                        }
                    }
                }
            )
        }

    init {
        val result = mutableMapOf<String, Download>()
        val cursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            result[cursor.download.request.id] = cursor.download
        }
        downloads.value = result

        // Backfill + retry: every downloaded song still missing a lyrics row gets one.
        // Runs once per process start; the per-song guards make re-runs no-ops.
        reconcileJob = scope.launch {
            reconcileMissingDownloadLyrics()
        }

        // Auto-retry on reconnect: wifi dropping mid-pass (or coming back after an
        // offline stretch) re-triggers the pass without waiting for the next cold start.
        scope.launch {
            var wasOnline = true
            networkConnectivity.networkStatus.collect { online ->
                val reconnected = online && !wasOnline
                wasOnline = online
                if (reconnected && reconcileJob?.isActive != true) {
                    reconcileJob = scope.launch { reconcileMissingDownloadLyrics() }
                }
            }
        }
    }

    /**
     * Lyrics-for-downloads: fetch and permanently store lyrics for a downloaded song.
     *
     * Same [LyricsEntity] convention as playback-time fetch (MusicService): a successful
     * fetch is stored as-is. A NOT_FOUND outcome is NOT stored immediately — rate-limit
     * errors are indistinguishable from genuine misses, and cementing one would mark the
     * song lyricless forever. Instead it retries with backoff ([WARM_RETRY_DELAYS_MS]);
     * only after the retries exhaust is the marker written. A process death mid-retry
     * simply leaves the row absent, so the next startup pass retries again.
     *
     * Backoff waits always happen OUTSIDE semaphore permits (see the reconcile loop):
     * a sleeping retry never blocks other songs' fetches.
     *
     * Skips (leaving the row absent so a later pass retries) when the toggle is off,
     * the device is offline, or the row already exists. Episodes are skipped: lyric
     * providers search by song title/artist and episode titles only waste their quota.
     */
    private suspend fun maybeWarmLyrics(songId: String) {
        for (attempt in 0..WARM_RETRY_DELAYS_MS.size) {
            if (tryWarmLyricsOnce(songId, isFinalAttempt = attempt == WARM_RETRY_DELAYS_MS.size)) return
            if (attempt < WARM_RETRY_DELAYS_MS.size) {
                Timber.tag(TAG).d("No lyrics for $songId (attempt ${attempt + 1}) — retrying in ${WARM_RETRY_DELAYS_MS[attempt] / 1000}s")
                delay(WARM_RETRY_DELAYS_MS[attempt])
            }
        }
    }

    /**
     * One fetch attempt. Returns true when there is nothing left to do for [songId]
     * (row stored, or a guard says stop); false only for NOT_FOUND with retries left.
     * Never sleeps — callers own the backoff, outside any semaphore permit.
     */
    private suspend fun tryWarmLyricsOnce(songId: String, isFinalAttempt: Boolean): Boolean {
        if (!isLyricsWarmingEnabled()) return true
        if (runCatching { database.lyrics(songId).first() }.getOrNull() != null) return true
        if (!runCatching { networkConnectivity.isCurrentlyConnected() }.getOrDefault(true)) return true

        val metadata = runCatching {
            val song = database.getSongByIdBlocking(songId) ?: return true
            if (song.song.isEpisode) return true
            song.toMediaMetadata()
        }.getOrNull() ?: return true

        val result = runCatching { lyricsHelper.getLyrics(metadata) }.getOrNull() ?: return true
        if (result.lyrics == LyricsEntity.LYRICS_NOT_FOUND && !isFinalAttempt) return false
        runCatching {
            database.query {
                upsert(LyricsEntity(songId, result.lyrics, result.provider))
            }
        }.onFailure { error ->
            Timber.tag(TAG).e(error, "Failed to store warmed lyrics for $songId")
        }
        return true
    }

    /**
     * Startup/backfill pass: every downloaded song with no lyrics row goes through
     * [maybeWarmLyrics] with bounded parallelism (network is the bottleneck; same
     * Semaphore(4) shape as JSON import). DB upserts are PK-idempotent and each song
     * id appears once, so no mutex is needed. This single pass covers both the
     * one-time backfill of pre-existing downloads and retries for songs whose fetch
     * failed while offline.
     */
    private suspend fun reconcileMissingDownloadLyrics() {
        if (!isLyricsWarmingEnabled()) return
        if (!runCatching { networkConnectivity.isCurrentlyConnected() }.getOrDefault(true)) return

        val downloaded = runCatching {
            database.downloadedSongsByCreateDateAsc().first()
        }.getOrDefault(emptyList())
        if (downloaded.isEmpty()) return

        val missingIds = downloaded
            .filterNot { it.song.isEpisode }
            .map { it.song.id }
            .distinct()
            .filter { id ->
                runCatching { database.lyrics(id).first() == null }.getOrDefault(false)
            }
        if (missingIds.isEmpty()) return

        val warmed = AtomicInteger(0)
        coroutineScope {
            missingIds.map { songId ->
                async {
                    ensureActive()
                    // Permit covers active fetches only: the backoff delay below runs
                    // outside it, so a sleeping retry never stalls the other 5 songs.
                    for (attempt in 0..WARM_RETRY_DELAYS_MS.size) {
                        val stored = lyricsWarmSemaphore.withPermit {
                            ensureActive()
                            tryWarmLyricsOnce(songId, isFinalAttempt = attempt == WARM_RETRY_DELAYS_MS.size)
                        }
                        if (stored) break
                        if (attempt < WARM_RETRY_DELAYS_MS.size) delay(WARM_RETRY_DELAYS_MS[attempt])
                    }
                    warmed.incrementAndGet()
                }
            }.awaitAll()
        }
        Timber.tag(TAG).d("Download lyrics reconcile warmed ${warmed.get()} song(s)")
    }

    private suspend fun isLyricsWarmingEnabled(): Boolean =
        runCatching {
            context.dataStore.data.first()[DownloadLyricsWithDownloadsKey] ?: true
        }.getOrDefault(true)

    fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

    companion object {
        /** Bounded parallelism for the lyrics backfill: network-bound. */
        private val lyricsWarmSemaphore = Semaphore(6)

        /** Backoff between NOT_FOUND retries: 10s, 30s, 90s (~2min total per song, background). */
        private val WARM_RETRY_DELAYS_MS = longArrayOf(10_000L, 30_000L, 90_000L)
    }

    fun release() {
        scope.cancel()
    }

    private fun Throwable?.isExpiredStreamError(): Boolean {
        var current = this
        while (current != null) {
            if (current is HttpDataSource.InvalidResponseCodeException &&
                (current.responseCode == 403 || current.responseCode == 410)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
