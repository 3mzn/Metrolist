/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the partner is (or was last) playing, as broadcast through their status document. */
data class PartnerTrackStatus(
    val songId: String,
    val title: String,
    val artist: String,
    val coverUrl: String?,
    val updatedAt: Long,
) {
    /** True when [updatedAt] is recent enough to trust as "right now". */
    fun isLive(staleAfterMs: Long = STALE_AFTER_MS): Boolean =
        System.currentTimeMillis() - updatedAt < staleAfterMs

    companion object {
        const val STALE_AFTER_MS = 2 * 60 * 1000L
    }
}

/**
 * Renders and refreshes the Partner home-screen widget: a circle (by default) filled with the
 * cover art of whatever the partner is listening to right now. Tapping it plays that song.
 *
 * All shape/size/broadcast preferences are owned here so the feature stays one self-contained
 * vertical slice.
 */
@Singleton
class PartnerWidgetManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val imageLoader by lazy {
        ImageLoader.Builder(context).crossfade(false).build()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Art cache keyed by cover URL, mirroring MetrolistWidgetManager's approach.
    private var cachedCoverUrl: String? = null
    private var cachedShapedArt: Bitmap? = null

    suspend fun renderFromCache() {
        val status = readCachedStatus()
        updateFromStatus(status)
    }

    fun updateFromStatus(status: PartnerTrackStatus?) {
        scope.launch {
            try {
                push(status)
            } catch (e: Exception) {
                android.util.Log.e("PartnerWidget", "update failed", e)
            }
        }
    }

    private suspend fun push(status: PartnerTrackStatus?) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val ids = appWidgetManager.getAppWidgetIds(
            ComponentName(context, PartnerWidgetReceiver::class.java),
        )
        if (ids.isEmpty()) return

        val live = status != null && status.isLive()
        val shaped = loadShapedCover(status?.coverUrl, stale = status != null && !live)

        ids.forEach { widgetId ->
            appWidgetManager.updateAppWidget(widgetId, buildRemoteViews(shaped, status))
        }
    }

    private suspend fun buildRemoteViews(
        art: Bitmap?,
        status: PartnerTrackStatus?,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_partner)
        views.setImageViewBitmap(R.id.widget_partner_art, art)

        val tapTarget: PendingIntent =
            if (status != null && status.songId.isNotBlank()) {
                PendingIntent.getActivity(
                    context,
                    REQUEST_CODE_PLAY,
                    playSongIntent(status.songId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                openAppPendingIntent()
            }
        views.setOnClickPendingIntent(R.id.widget_partner_root, tapTarget)
        return views
    }

    private suspend fun loadShapedCover(coverUrl: String?, stale: Boolean): Bitmap? =
        withContext(Dispatchers.IO) {
            if (coverUrl.isNullOrBlank()) return@withContext dimIfStale(defaultCircularIcon(), stale)

            if (coverUrl == cachedCoverUrl && cachedShapedArt != null) {
                return@withContext cachedShapedArt
            }

            val square = try {
                val request = ImageRequest.Builder(context)
                    .data(coverUrl)
                    .size(300, 300)
                    .allowHardware(false)
                    .build()
                imageLoader.execute(request).image?.toBitmap()
            } catch (e: Exception) {
                null
            } ?: return@withContext dimIfStale(defaultCircularIcon(), stale)

            val shaped = applyShape(square, stale)
            cachedCoverUrl = coverUrl
            cachedShapedArt = shaped
            shaped
        }

    private fun dimIfStale(bitmap: Bitmap, stale: Boolean): Bitmap {
        if (!stale) return bitmap
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        canvas.drawColor(android.graphics.Color.argb(115, 0, 0, 0))
        return output
    }

    private fun applyShape(square: Bitmap, stale: Boolean): Bitmap {
        val base =
            when (readShape()) {
                SHAPE_SQUARE -> cropToSquare(square)
                SHAPE_ROUNDED -> roundCorners(cropToSquare(square), 48f)
                else -> circle(cropToSquare(square))
            }
        return dimIfStale(base, stale)
    }

    private fun cropToSquare(bitmap: Bitmap): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        val xOffset = (bitmap.width - size) / 2
        val yOffset = (bitmap.height - size) / 2
        return Bitmap.createBitmap(bitmap, xOffset, yOffset, size, size)
    }

    private fun circle(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val radius = minOf(source.width, source.height) / 2f
        canvas.drawCircle(radius, radius, radius, paint)
        return output
    }

    private fun roundCorners(source: Bitmap, cornerRadius: Float): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(
            RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()),
            cornerRadius,
            cornerRadius,
            paint,
        )
        return output
    }

    private fun defaultCircularIcon(): Bitmap {
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        val size = 300
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return circle(cropToSquare(bitmap))
    }

    private fun openAppPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Reuses MainActivity's YouTube deep-link handling, which already knows how to take a watch
     * URL and queue exactly that video.
     */
    private fun playSongIntent(songId: String): Intent =
        Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://music.youtube.com/watch?v=$songId"),
        ).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    // ---------------------------------------------------------------- preferences & cache

    fun readShape(): String =
        runCatching {
            kotlinx.coroutines.runBlocking {
                context.dataStore.data.first()[PARTNER_WIDGET_SHAPE_KEY] ?: SHAPE_CIRCLE
            }
        }.getOrDefault(SHAPE_CIRCLE)

    fun writeShape(value: String) {
        scope.launch {
            runCatching {
                context.dataStore.edit { it[PARTNER_WIDGET_SHAPE_KEY] = value }
            }
            // Re-render immediately so the change is visible without waiting for a song change.
            renderFromCache()
        }
    }

    suspend fun writeCachedStatus(status: PartnerTrackStatus?) {
        runCatching {
            context.dataStore.edit { prefs ->
                prefs[PREF_SONG_ID] = status?.songId ?: ""
                prefs[PREF_TITLE] = status?.title ?: ""
                prefs[PREF_ARTIST] = status?.artist ?: ""
                prefs[PREF_COVER] = status?.coverUrl ?: ""
                prefs[PREF_UPDATED_AT] = status?.updatedAt ?: 0L
            }
        }
    }

    private suspend fun readCachedStatus(): PartnerTrackStatus? {
        val prefs = runCatching { context.dataStore.data.first() }.getOrNull() ?: return null
        val songId = prefs[PREF_SONG_ID].orEmpty()
        if (songId.isBlank()) return null
        return PartnerTrackStatus(
            songId = songId,
            title = prefs[PREF_TITLE].orEmpty(),
            artist = prefs[PREF_ARTIST].orEmpty(),
            coverUrl = prefs[PREF_COVER],
            updatedAt = prefs[PREF_UPDATED_AT] ?: 0L,
        )
    }

    companion object {
        const val SHAPE_CIRCLE = "circle"
        const val SHAPE_ROUNDED = "rounded"
        const val SHAPE_SQUARE = "square"

        /** Whether this device broadcasts its own playback to the partner. Default on. */
        val HEARTBEAT_ENABLED_KEY = androidx.datastore.preferences.core.booleanPreferencesKey("partner_heartbeat_enabled")
        val PARTNER_WIDGET_SHAPE_KEY = stringPreferencesKey("partner_widget_shape")

        private val PREF_SONG_ID = stringPreferencesKey("partner_status_song_id")
        private val PREF_TITLE = stringPreferencesKey("partner_status_title")
        private val PREF_ARTIST = stringPreferencesKey("partner_status_artist")
        private val PREF_COVER = stringPreferencesKey("partner_status_cover")
        private val PREF_UPDATED_AT = androidx.datastore.preferences.core.longPreferencesKey("partner_status_updated_at")

        private const val REQUEST_CODE_OPEN = 6201
        private const val REQUEST_CODE_PLAY = 6202
    }
}
