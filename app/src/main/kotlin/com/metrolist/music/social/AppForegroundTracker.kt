/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import android.app.Activity
import android.app.Application
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks whether the app currently has any started activity, i.e. is visible to the user.
 *
 * Used by background surfaces (the gentle-nudge worker) to stay silent while the user is
 * literally inside the app. Counting started/stopped (not resumed/paused) is the standard
 * foreground signal: activities between those two callbacks are at least partially visible.
 */
object AppForegroundTracker {

    private val startedActivities = AtomicInteger(0)

    /** True while any activity is started — the app is open and visible. */
    val isForeground: Boolean
        get() = startedActivities.get() > 0

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    startedActivities.incrementAndGet()
                }

                override fun onActivityStopped(activity: Activity) {
                    startedActivities.decrementAndGet()
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }
}
