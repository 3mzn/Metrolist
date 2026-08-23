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
        val cover = loadCoverSquare(status?.coverUrl)
        val density = context.resources.displayMetrics.density

        ids.forEach { widgetId ->
            val options = appWidgetManager.getAppWidgetOptions(widgetId)
            val views =
                if (isCompact(options)) {
                    buildCompactViews(
                        cover?.let { applyShape(it, stale = status != null && !live) },
                        status,
                    )
                } else {
                    val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                        .takeIf { it > 0 } ?: 250
                    val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                        .takeIf { it > 0 } ?: 110
                    buildUnifiedViews(
                        widthPx = (widthDp * density).toInt(),
                        heightPx = (heightDp * density).toInt(),
                        cover = cover,
                        stale = status != null && !live,
                        status = status,
                        partnerName = partnerName,
                    )
                }
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    /** Below ~2 cells wide there is no room for text â€” fall back to cover-only rendering. */
    private fun isCompact(options: Bundle): Boolean =
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) in 1..179

    /**
     * The whole widget as ONE composed bitmap: a single rounded card where the cover fills the
     * left square edge-to-edge and the info surface continues seamlessly to the right. One canvas
     * means the two halves can never render as separate pieces.
     */
    private fun buildUnifiedViews(
        widthPx: Int,
        heightPx: Int,
        cover: Bitmap?,
        stale: Boolean,
        status: PartnerTrackStatus?,
        partnerName: String?,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_partner)
        views.setImageViewBitmap(
            R.id.widget_partner_art,
            composeUnifiedWidget(widthPx, heightPx, cover, stale, status, partnerName),
        )
        views.setOnClickPendingIntent(R.id.widget_partner_full, tapIntentFor(status))
        return views
    }

    private fun composeUnifiedWidget(
        widthPx: Int,
        heightPx: Int,
        cover: Bitmap?,
        stale: Boolean,
        status: PartnerTrackStatus?,
        partnerName: String?,
    ): Bitmap {
        val width = widthPx.coerceIn(200, 1400)
        val height = heightPx.coerceIn(100, 600)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val live = status != null && status.isLive()
        val swatch = if (live) cover?.let { extractSwatch(it) } else null

        val cardColor =
            when {
                live -> swatch?.rgb ?: Color.rgb(30, 30, 34)
                cover != null -> Color.argb(255, 24, 24, 28)
                else -> Color.rgb(30, 30, 34)
            }
        val titleColor = swatch?.titleTextColor ?: Color.WHITE
        val bodyColor = swatch?.bodyTextColor ?: Color.WHITE

        val radius = height * 0.14f
        val cardPath = android.graphics.Path().apply {
            addRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                radius,
                radius,
                android.graphics.Path.Direction.CW,
            )
        }
        canvas.drawPath(cardPath, Paint().apply { isAntiAlias = true; color = cardColor })

        // Cover fills the left square completely, clipped by the card's rounded corners — flush
        // against the text surface with no gap.
        val coverSide = height.toFloat()
        if (cover != null) {
            canvas.save()
            canvas.clipPath(cardPath)
            canvas.drawBitmap(
                cover,
                null,
                RectF(0f, 0f, coverSide, coverSide),
                Paint().apply { isAntiAlias = true; isFilterBitmap = true },
            )
            canvas.restore()
        }

        val textX = coverSide + height * 0.09f
        val textWidth = width - textX - height * 0.09f

        if (!live) {
            // Idle: partner name prominent, soft message underneath.
            val namePaint = android.text.TextPaint().apply {
                isAntiAlias = true
                color = Color.WHITE
                textSize = height * 0.16f
                isFakeBoldText = true
            }
            val msgPaint = android.text.TextPaint().apply {
                isAntiAlias = true
                color = Color.argb(200, 255, 255, 255)
                textSize = height * 0.115f
            }
            canvas.drawText(
                partnerName ?: context.getString(R.string.song_listened_fallback_friend),
                textX,
                height * 0.45f,
                namePaint,
            )
            canvas.drawText(
                TextUtils.ellipsize(
                    context.getString(R.string.partner_widget_idle_message),
                    msgPaint,
                    textWidth,
                    TextUtils.TruncateAt.END,
                ).toString(),
                textX,
                height * 0.45f + msgPaint.textSize * 1.4f,
                msgPaint,
            )
            return bitmap
        }

        val headerPaint = android.text.TextPaint().apply {
            isAntiAlias = true
            color = bodyColor
            textSize = height * 0.105f
        }
        val titlePaint = android.text.TextPaint().apply {
            isAntiAlias = true
            color = titleColor
            textSize = height * 0.17f
            isFakeBoldText = true
        }
        val artistPaint = android.text.TextPaint().apply {
            isAntiAlias = true
            color = bodyColor
            textSize = height * 0.115f
        }

        val headerText =
            context.getString(R.string.partner_widget_listening_label) + " " + (partnerName ?: "")
        val headerY = height * 0.30f
        canvas.drawText(
            TextUtils.ellipsize(headerText, headerPaint, textWidth, TextUtils.TruncateAt.END).toString(),
            textX,
            headerY,
            headerPaint,
        )

        val titleY = height * 0.56f
        canvas.drawText(
            TextUtils.ellipsize(status.title, titlePaint, textWidth, TextUtils.TruncateAt.END).toString(),
            textX,
            titleY,
            titlePaint,
        )

        canvas.drawText(
            TextUtils.ellipsize(status.artist, artistPaint, textWidth, TextUtils.TruncateAt.END).toString(),
            textX,
            titleY + height * 0.17f,
            artistPaint,
        )

        if (stale) {
            canvas.drawColor(Color.argb(90, 0, 0, 0))
        }
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

    /**
     * Loads the cover as a raw centered square (no shape mask, no dimming) — callers decide how
     * to present it: the unified widget crops it flush into the card, compact mode applies the
     * shape mask on top.
     */
    private suspend fun loadCoverSquare(coverUrl: String?): Bitmap? =
        withContext(Dispatchers.IO) {
            if (coverUrl.isNullOrBlank()) return@withContext null

            if (coverUrl == cachedCoverUrl && cachedSquareArt != null) {
                return@withContext cachedSquareArt
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
            } ?: return@withContext null

            cachedCoverUrl = coverUrl
            cachedSquareArt = square
            square
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
