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
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.widget.RemoteViews
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
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
 * Renders and refreshes the Partner home-screen widget.
 *
 * Layout mirrors a chat-bubble style card: the partner's cover art on the left, and on the right
 * a panel whose background color is extracted from that same artwork via androidx Palette â€” the
 * same extraction family Metrolist uses for its dynamic player theme. Title and artist lines
 * marquee-scroll when long; tapping the widget plays the partner's current song.
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
    private var cachedSquareArt: Bitmap? = null

    suspend fun renderFromCache() {
        val status = readCachedStatus()
        updateFromStatus(status)
    }

    fun updateFromStatus(status: PartnerTrackStatus?, partnerName: String? = null) {
        scope.launch {
            try {
                push(status, partnerName ?: readCachedPartnerName())
            } catch (e: Exception) {
                android.util.Log.e("PartnerWidget", "update failed", e)
            }
        }
    }

    private suspend fun push(status: PartnerTrackStatus?, partnerName: String?) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val ids = appWidgetManager.getAppWidgetIds(
            ComponentName(context, PartnerWidgetReceiver::class.java),
        )
        if (ids.isEmpty()) return

        val live = status != null && status.isLive()
        val art = loadShapedCover(status?.coverUrl, stale = status != null && !live)

        ids.forEach { widgetId ->
            val options = appWidgetManager.getAppWidgetOptions(widgetId)
            val views =
                if (isCompact(options)) {
                    buildCompactViews(art, status)
                } else {
                    buildFullViews(art, status, partnerName)
                }
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    /** Below ~2 cells wide there is no room for text â€” fall back to cover-only rendering. */
    private fun isCompact(options: Bundle): Boolean =
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) in 1..179

    /**
     * Full layout: left = cover art, right = an info panel composed as a bitmap of the SAME
     * dimensions as the art. Both render through fitCenter in equal-weight cells, so their
     * rendered sizes can never diverge, regardless of launcher cell geometry.
     */
    private fun buildFullViews(
        art: Bitmap?,
        status: PartnerTrackStatus?,
        partnerName: String?,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_partner)
        views.setImageViewBitmap(R.id.widget_partner_art, art)

        val panelSize = art?.width ?: 300
        views.setImageViewBitmap(
            R.id.widget_partner_panel,
            buildPanelBitmap(panelSize, status, partnerName, art),
        )

        views.setOnClickPendingIntent(R.id.widget_partner_root, tapIntentFor(status))
        return views
    }

    /**
     * Draws the info panel: album-colored rounded background (Palette extraction, same family as
     * the dynamic player theme) with the header, song title and artist rendered directly into the
     * bitmap. Long text is ellipsized â€” MIUI hosts reject reflection-based marquee forcing.
     */
    private fun buildPanelBitmap(
        size: Int,
        status: PartnerTrackStatus?,
        partnerName: String?,
        art: Bitmap?,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val live = status != null && status.isLive()
        val swatch = art?.let { extractSwatch(it) }

        val bgColor =
            if (status == null || !live) Color.argb(210, 28, 28, 32)
            else swatch?.rgb ?: Color.argb(235, 32, 32, 36)
        val titleColor = swatch?.titleTextColor ?: Color.WHITE
        val bodyColor = swatch?.bodyTextColor ?: Color.WHITE

        val bgPaint = Paint().apply {
            isAntiAlias = true
            color = bgColor
        }
        val radius = size * 0.14f
        canvas.drawRoundRect(
            RectF(0f, 0f, size.toFloat(), size.toFloat()),
            radius,
            radius,
            bgPaint,
        )

        val pad = size * 0.09f
        val textWidth = size - pad * 2

        val headerPaint = TextPaint().apply {
            isAntiAlias = true
            color = bodyColor
            textSize = size * 0.085f
        }
        val titlePaint = TextPaint().apply {
            isAntiAlias = true
            color = titleColor
            textSize = size * 0.145f
            isFakeBoldText = true
        }
        val artistPaint = TextPaint().apply {
            isAntiAlias = true
            color = bodyColor
            textSize = size * 0.095f
        }

        if (status == null || !status.isLive()) {
            // Idle: partner name prominent, soft message underneath.
            val name = partnerName ?: context.getString(R.string.song_listened_fallback_friend)
            val nameY = size * 0.42f
            canvas.drawText(name, pad, nameY, headerPaint.apply { textSize = size * 0.12f })
            val msgPaint = TextPaint(headerPaint).apply { textSize = size * 0.095f }
            canvas.drawText(
                TextUtils.ellipsize(
                    context.getString(R.string.partner_widget_idle_message),
                    msgPaint,
                    textWidth,
                    TextUtils.TruncateAt.END,
                ).toString(),
                pad,
                nameY + size * 0.12f,
                msgPaint,
            )
            return bitmap
        }

        // Live: header, title, artist â€” top-aligned stack.
        val headerText =
            context.getString(R.string.partner_widget_listening_label) + " " + (partnerName ?: "")
        canvas.drawText(
            TextUtils.ellipsize(headerText, headerPaint, textWidth, TextUtils.TruncateAt.END).toString(),
            pad,
            pad + headerPaint.textSize,
            headerPaint,
        )

        val titleY = size * 0.52f
        canvas.drawText(
            TextUtils.ellipsize(status.title.ifBlank { " " }, titlePaint, textWidth, TextUtils.TruncateAt.END).toString(),
            pad,
            titleY,
            titlePaint,
        )

        canvas.drawText(
            TextUtils.ellipsize(status.artist.ifBlank { " " }, artistPaint, textWidth, TextUtils.TruncateAt.END).toString(),
            pad,
            titleY + size * 0.13f,
            artistPaint,
        )
        return bitmap
    }

    private fun buildCompactViews(
        art: Bitmap?,
        status: PartnerTrackStatus?,
    ): RemoteViews {
        // Compact fallback reuses the single-pane layout with just the shaped cover.
        val views = RemoteViews(context.packageName, R.layout.widget_partner_compact)
        views.setImageViewBitmap(R.id.widget_partner_art, art)
        views.setOnClickPendingIntent(R.id.widget_partner_root, tapIntentFor(status))
        return views
    }

    private fun tapIntentFor(status: PartnerTrackStatus?): PendingIntent =
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

    // ---------------------------------------------------------------- artwork & palette

    private suspend fun loadShapedCover(coverUrl: String?, stale: Boolean): Bitmap? =
        withContext(Dispatchers.IO) {
            if (coverUrl.isNullOrBlank()) return@withContext dimIfStale(defaultCircularIcon(), stale)

            if (coverUrl == cachedCoverUrl && cachedSquareArt != null) {
                return@withContext applyShape(cachedSquareArt!!, stale)
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

            cachedCoverUrl = coverUrl
            cachedSquareArt = square
            applyShape(square, stale)
        }

    /**
     * Dominant-or-vibrant swatch selection, simplified from PlayerColorExtractor: population
     * decides ties, and the swatch's own text colors guarantee readable panel text.
     */
    private fun extractSwatch(art: Bitmap): Palette.Swatch? {
        val palette = Palette.from(art).maximumColorCount(24).generate()
        return palette.dominantSwatch
            ?: palette.vibrantSwatch
            ?: palette.mutedSwatch
    }

    private fun dimIfStale(bitmap: Bitmap, stale: Boolean): Bitmap {
        if (!stale) return bitmap
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(output).drawColor(Color.argb(115, 0, 0, 0))
        return output
    }

    private fun applyShape(square: Bitmap, stale: Boolean): Bitmap {
        val base =
            when (readShape()) {
                SHAPE_SQUARE -> square
                SHAPE_ROUNDED -> roundCorners(square, 48f)
                else -> circle(square)
            }
        return dimIfStale(base, stale)
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
        // Idle placeholder follows the same shape setting as live covers.
        return applyShape(cropToSquare(bitmap), stale = false)
    }

    private fun cropToSquare(bitmap: Bitmap): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        val xOffset = (bitmap.width - size) / 2
        val yOffset = (bitmap.height - size) / 2
        return Bitmap.createBitmap(bitmap, xOffset, yOffset, size, size)
    }

    private fun openAppPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

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

    suspend fun writeCachedStatus(status: PartnerTrackStatus?, partnerName: String?) {
        runCatching {
            context.dataStore.edit { prefs ->
                prefs[PREF_SONG_ID] = status?.songId ?: ""
                prefs[PREF_TITLE] = status?.title ?: ""
                prefs[PREF_ARTIST] = status?.artist ?: ""
                prefs[PREF_COVER] = status?.coverUrl ?: ""
                prefs[PREF_UPDATED_AT] = status?.updatedAt ?: 0L
                prefs[PREF_PARTNER_NAME] = partnerName ?: ""
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

    private suspend fun readCachedPartnerName(): String? =
        runCatching { context.dataStore.data.first()[PREF_PARTNER_NAME] }
            .getOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        const val SHAPE_CIRCLE = "circle"
        const val SHAPE_ROUNDED = "rounded"
        const val SHAPE_SQUARE = "square"

        /** Whether this device broadcasts its own playback to the partner. Default on. */
        val HEARTBEAT_ENABLED_KEY = booleanPreferencesKey("partner_heartbeat_enabled")
        val PARTNER_WIDGET_SHAPE_KEY = stringPreferencesKey("partner_widget_shape")

        /**
         * Debug aid: when on, THIS device's own current song is rendered into the local Partner
         * widget (instead of the partner's broadcast) so the UI can be inspected without waiting
         * for the partner to play anything.
         */
        val WIDGET_UI_DEBUG_TEST_KEY = booleanPreferencesKey("widget_ui_debug_test")

        private val PREF_SONG_ID = stringPreferencesKey("partner_status_song_id")
        private val PREF_TITLE = stringPreferencesKey("partner_status_title")
        private val PREF_ARTIST = stringPreferencesKey("partner_status_artist")
        private val PREF_COVER = stringPreferencesKey("partner_status_cover")
        private val PREF_UPDATED_AT = longPreferencesKey("partner_status_updated_at")
        private val PREF_PARTNER_NAME = stringPreferencesKey("partner_status_partner_name")

        private const val REQUEST_CODE_OPEN = 6201
        private const val REQUEST_CODE_PLAY = 6202
    }
}
