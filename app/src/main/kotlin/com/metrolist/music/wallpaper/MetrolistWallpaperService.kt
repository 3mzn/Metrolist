/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import timber.log.Timber

/**
 * Live wallpaper that draws bass-reactive glow rings around the home-screen widgets.
 *
 * Renders with AGSL via [HomeRingShader], a port of the shader the MiniPlayer already
 * uses. The wallpaper is what makes this possible at all: a widget is a `RemoteViews`
 * bitmap over a ~1 MB Binder pipe with a ~2 fps ceiling, whereas this is a real `Surface`
 * on the real frame clock — measured at **60.13 fps** on HyperOS 3.
 *
 * **Phase 3 state:** rings are drawn at the geometry measured in Phase 2, driven by fixed
 * constants. Audio arrives in Phase 4 — keeping them separate means a placement problem
 * can't be mistaken for an audio problem.
 *
 * Findings and the full rationale live in `SPEC_HOME_WALLPAPER_RINGS.md`. Findings that
 * shape this file:
 * - The surface is 1080×2400 and maps **1:1** to the screen (§5.3), so ring coordinates
 *   are plain screen pixels with no transform.
 * - The surface **does not scroll** (`xOffsetStep == -1.0`), so rings cannot drift across
 *   home-screen pages (§6).
 * - `lockHardwareCanvas()` works here, which AGSL requires (§9a). Without it the canvas is
 *   software-backed and `RuntimeShader` throws.
 *
 * Findings are logged under the [TAG] tag; read them with:
 *   `adb logcat -s MetrolistWallpaper`
 */
class MetrolistWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = RingEngine()

    private inner class RingEngine : Engine() {

        private val paint = Paint()

        /** One compiled shader per ring. `null` until [compileRings] succeeds. */
        private var rings: List<HomeRingShader.Ring>? = null

        private var running = false
        private var visible = false
        private var surfaceReady = false

        private var frameCount = 0
        private var hardwareCanvasSeen = false

        private val handler = Handler(Looper.getMainLooper())

        private val drawRunnable = object : Runnable {
            override fun run() {
                if (!running) return
                val startedAt = SystemClock.uptimeMillis()
                drawFrame()
                // Target ~60 fps. The system paces us; this only bounds our own loop.
                val elapsed = SystemClock.uptimeMillis() - startedAt
                handler.postDelayed(this, (FRAME_BUDGET_MS - elapsed).coerceAtLeast(1))
            }
        }

        // ── Surface lifecycle ──────────────────────────────────────────────

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            Timber.tag(TAG).d("onSurfaceCreated")
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceReady = true
            compileRings()
            Timber.tag(TAG).d(
                "PROBE surface=${width}x$height screen=" +
                    "${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels} " +
                    "rings=${rings?.size ?: 0}",
            )
            start()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            // The system asks for a repaint when the wallpaper becomes visible again (e.g.
            // after the screen wakes). Without this the wallpaper can stay blank.
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stop()
            surfaceReady = false
            Timber.tag(TAG).d("onSurfaceDestroyed after $frameCount frames")
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            this.visible = visible
            Timber.tag(TAG).d("onVisibilityChanged visible=$visible")
            // Spec §7.2: do no work when hidden.
            if (visible) start() else stop()
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int,
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)
            // Verified in Phase 2 that this wallpaper does not scroll (step == -1.0), so the
            // static ring geometry in HomeRings stays correct. Logged to detect a change.
            Timber.tag(TAG).d("PROBE offsets xPixel=$xPixelOffset yPixel=$yPixelOffset step=$xOffsetStep")
        }

        // ── Shader setup ───────────────────────────────────────────────────

        private fun compileRings() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                rings = null
                Timber.tag(TAG).w("RuntimeShader needs API 33+; drawing plain background")
                return
            }
            rings = try {
                HomeRings.RINGS.map { HomeRingShader.Ring(it).apply { compile() } }
            } catch (e: Exception) {
                // A SkSL compile error throws here, at construction — not at draw time.
                // Never let this kill the wallpaper service.
                Timber.tag(TAG).e(e, "Ring shader compile failed; drawing plain background")
                null
            }
        }

        // ── Frame loop ─────────────────────────────────────────────────────

        private fun start() {
            if (running || !surfaceReady || !visible) return
            running = true
            handler.removeCallbacks(drawRunnable)
            handler.post(drawRunnable)
            Timber.tag(TAG).d("Frame loop started")
        }

        private fun stop() {
            running = false
            handler.removeCallbacks(drawRunnable)
            Timber.tag(TAG).d("Frame loop stopped")
        }

        private fun drawFrame() {
            val holder = surfaceHolder ?: return
            if (!holder.surface.isValid) return

            // AGSL needs a hardware canvas: lockCanvas() returns a software one and
            // RuntimeShader throws "Software rendering doesn't support RuntimeShader" (§9a).
            var canvas: Canvas? = null
            var hardware = true
            try {
                canvas = holder.lockHardwareCanvas()
            } catch (e: IllegalStateException) {
                hardware = false
                Timber.tag(TAG).w("lockHardwareCanvas unsupported: ${e.message}")
            } catch (e: IllegalArgumentException) {
                hardware = false
                Timber.tag(TAG).w("lockHardwareCanvas rejected: ${e.message}")
            }

            if (canvas == null) {
                canvas = try {
                    holder.lockCanvas()
                } catch (e: Exception) {
                    // Surface not ready yet, or lost between the visibility check and the lock.
                    Timber.tag(TAG).d("lockCanvas failed: ${e.message}")
                    null
                } ?: return
            }

            try {
                renderFrame(canvas, hardware)
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    Timber.tag(TAG).d("unlockCanvasAndPost failed: ${e.message}")
                }
            }

            if (hardware) hardwareCanvasSeen = true
            frameCount++
            if (frameCount == FRAME_REPORT_INTERVAL) {
                Timber.tag(TAG).d(
                    "PROBE frames=$frameCount hw=$hardwareCanvasSeen rings=${rings?.size ?: 0}",
                )
                frameCount = 0
            }
        }

        private fun renderFrame(canvas: Canvas, hardware: Boolean) {
            // Background. Pure black per spec §8.3 — on AMOLED those pixels are simply off.
            canvas.drawColor(Color.BLACK)

            val activeRings = rings
            if (!hardware || activeRings == null) {
                // Without AGSL the honest appearance is the plain black background rather
                // than something that implies a visualizer is running.
                return
            }

            // Fixed values until Phase 4 wires up CoverBassPulse.
            //
            // Shader time MUST stay small. The AGSL evaluates `cos(angle - time*orbitSpeed)`,
            // and float32 precision degrades as the argument grows — at ~1e6 the fractional
            // part is lost and the orbit freezes. MiniPlayer already hits this and wraps its
            // time (`MiniPlayer.kt:468`); do the same here rather than passing raw uptime.
            val timeSeconds = (SystemClock.uptimeMillis() % TIME_WRAP_MS) / 1000f
            // Synthetic bass so Phase 3 can verify the animation path end to end: a slow
            // breath that makes the glow pulse and the hotspot orbit visibly. Phase 4
            // replaces this with the real CoverBassPulse value.
            val testBass = 0.5f + 0.45f * kotlin.math.sin(timeSeconds * TEST_BREATH_HZ)
            for (ring in activeRings) {
                ring.draw(
                    canvas = canvas,
                    paint = paint,
                    color = RING_COLOR,
                    bass = testBass,
                    alpha = RING_ALPHA,
                    timeSeconds = timeSeconds,
                    hotspotMult = HOTSPOT_MULT,
                )
            }
        }
    }

    private companion object {
        const val TAG = "MetrolistWallpaper"

        /** ~60 fps target. The system still paces us; this only bounds our own loop. */
        const val FRAME_BUDGET_MS = 16L

        /** Emit a status line every N frames so logcat stays readable. */
        const val FRAME_REPORT_INTERVAL = 120

        // ── Phase 3 placeholders ──────────────────────────────────────────
        // Replaced with the real CoverBassPulse value and the cover-art colour in Phase 4.
        /**
         * Wrap shader time every 60s, matching MiniPlayer (`MiniPlayer.kt:468`). Keeps the
         * `cos()` argument small enough that float32 does not quantise the orbit away.
         */
        const val TIME_WRAP_MS = 60_000L

        /** Breath frequency of the synthetic bass, radians/second ÷ 2π — ~0.29 Hz. */
        const val TEST_BREATH_HZ = 1.8f

        const val RING_ALPHA = 1f

        /** Matches the MiniPlayer default (`MiniPlayerBorderHotspotKey`, default 10f). */
        const val HOTSPOT_MULT = 10f

        /**
         * Stand-in for the cover-art palette colour until Phase 4 reads `borderSongColor`.
         */
        const val RING_COLOR = 0xFF7FD4FF.toInt()
    }
}