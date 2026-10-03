/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wallpaper

import android.content.Context
import com.metrolist.music.constants.BorderGlowIntensity
import com.metrolist.music.constants.HomeRingHotspotKey
import com.metrolist.music.constants.HomeRingIntensityKey
import com.metrolist.music.constants.HomeRingStrokeRingsKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.widget.PartnerWidgetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Cached settings for the home-screen rings.
 *
 * DataStore is a suspending [kotlinx.coroutines.flow.Flow] and the wallpaper's render loop
 * is a synchronous 60 fps callback — reading the store per frame would be wrong on both
 * counts. So the values are collected into `@Volatile` fields by a scope owned by the
 * engine, and the render loop only ever reads a plain field.
 *
 * Hotspot strength and peak brightness are the **wallpaper's own** keys, not the
 * MiniPlayer's, so the two visuals cannot change each other (§4a).
 */
object HomeRingSettings {

    private const val TAG = "HomeRingSettings"

    /** Mirrors the MiniPlayer's slider default so the wallpaper looks familiar out of the box. */
    const val DEFAULT_HOTSPOT = 10f

    @Volatile
    var hotspotMult: Float = DEFAULT_HOTSPOT
        private set

    @Volatile
    var peak: Float = peakFor(BorderGlowIntensity.MEDIUM)
        private set

    /**
     * The gate: rings only run while the partner widget is showing *my* song, because that
     * is the only time the audio they visualise is actually on this device (§4.3).
     */
    @Volatile
    var debugWidgetOn: Boolean = false
        private set

    /**
     * Which rings draw the crisp core stroke, by [HomeRingGeometry.id]. Empty by default
     * (4c) - the soft glow alone is the better look at wallpaper scale. A subset is
     * perfectly valid, e.g. only the two wide flat rings.
     */
    @Volatile
    var strokeRings: Set<String> = emptySet()
        private set

    /** True when [ringId] should draw the crisp stroke. */
    fun hasStroke(ringId: String): Boolean = ringId in strokeRings

    private var job: Job? = null

    /** Last logged settings snapshot, so only genuine changes reach the log. */
    @Volatile private var lastSnapshot: String? = null

    /**
     * Peak brightness per intensity band — copied from the MiniPlayer's `peak`
     * (`MiniPlayer.kt:444-449`) so the two rings feel like the same effect.
     */
    private fun peakFor(intensity: BorderGlowIntensity): Float =
        when (intensity) {
            BorderGlowIntensity.LOW -> 0.7f
            BorderGlowIntensity.MEDIUM -> 0.85f
            BorderGlowIntensity.HIGH -> 1f
        }

    /** Starts collecting. Safe to call repeatedly - a previous scope is cancelled first. */
    fun start(scope: CoroutineScope, context: Context) {
        stop()
        job = scope.launch {
            runCatching {
                context.dataStore.data.collectLatest { prefs ->
                    val rawIntensity = prefs[HomeRingIntensityKey]
                    hotspotMult = prefs[HomeRingHotspotKey] ?: DEFAULT_HOTSPOT
                    peak = peakFor(
                        rawIntensity?.let { name ->
                            runCatching { BorderGlowIntensity.valueOf(name) }.getOrNull()
                        } ?: BorderGlowIntensity.MEDIUM,
                    )
                    debugWidgetOn = prefs[PartnerWidgetManager.WIDGET_UI_DEBUG_TEST_KEY] ?: false
                    strokeRings = prefs[HomeRingStrokeRingsKey] ?: emptySet()

                    // Log only on an actual change. DataStore emits for every write to the
                    // store, and this collector is the wallpaper's only proof that a setting
                    // change actually reached the render loop.
                    val snapshot = "$hotspotMult/$rawIntensity/$peak/$debugWidgetOn/" +
                        strokeRings.sorted().joinToString(",")
                    if (snapshot != lastSnapshot) {
                        lastSnapshot = snapshot
                        android.util.Log.i(TAG, "settings -> hotspot=$hotspotMult peak=$peak gate=$debugWidgetOn stroke=[${strokeRings.sorted().joinToString(",")}] rawIntensity=$rawIntensity")
                    }
                }
            }.onFailure {
                // A cancelled scope lands here too; only a real failure is worth logging.
                if (it !is kotlinx.coroutines.CancellationException) {
                    Timber.tag(TAG).e(it, "settings collection failed")
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
