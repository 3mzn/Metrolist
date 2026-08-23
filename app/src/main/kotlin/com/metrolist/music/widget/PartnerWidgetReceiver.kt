/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Home-screen widget showing the partner's current playback as cover art.
 *
 * Rendering is driven by [PartnerHeartbeatMonitor] whenever a status snapshot arrives, and by
 * [onUpdate] (first placement + the system's periodic refresh) from the last-known cached state,
 * so the widget always shows something honest even with both apps closed.
 */
class PartnerWidgetReceiver : AppWidgetProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PartnerWidgetEntryPoint {
        fun partnerWidgetManager(): PartnerWidgetManager
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val manager =
                    EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        PartnerWidgetEntryPoint::class.java,
                    ).partnerWidgetManager()
                manager.renderFromCache()
            } catch (e: Exception) {
                android.util.Log.e("PartnerWidget", "onUpdate render failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // Nothing per-instance to clean up yet — preferences are global.
    }
}
