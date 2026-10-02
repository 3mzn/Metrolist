/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import timber.log.Timber

/**
 * Phase 1 probe for the home-screen ring visualizer. **Not the final wallpaper.**
 *
 * Purpose is to answer, on a real device, four questions that cannot be resolved from
 * documentation — see `SPEC_HOME_WALLPAPER_RINGS.md` §5.3, §6, §7, §9a:
 *
 *  1. Does a third-party live wallpaper run at all on HyperOS 3?
 *  2. What is the **surface** size handed to `onSurfaceChanged`? Not necessarily the
 *     screen size — Android may hand a wallpaper a wider buffer so it can scroll across
 *     home-screen pages. This is the number ring placement depends on.
 *  3. Does the surface move when swiping pages (`onOffsetsChanged`)?
 *  4. Does `lockHardwareCanvas()` succeed? Required for AGSL (§9a).
 *
 * Draws an animated gradient and nothing else. **Deliberately does not touch**
 * `CoverBassPulse`, `Player.kt`, `MiniPlayer.kt`, `BorderGlowShader.kt`, settings, or
 * any preference — audio integration is Phase 4, and geometry is Phase 2.
 *
 * Findings are logged under the [TAG] tag; read them with:
 *   `adb logcat -s MetrolistWallpaper`
 */
class MetrolistWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ProbeEngine()

    private inner class ProbeEngine : Engine() {

        private val paint = Paint()
        private var shader: RuntimeShader? = null

        /** Set once per attach; reported in the log so the AGSL path is unambiguous. */
        private var agslActive = false

        private var running = false
        private var visible = false
        private var surfaceReady = false

        private var frameCount = 0
        private var lastReportedWidth = 0
        private var lastReportedHeight = 0

        private val handler = Handler(Looper.getMainLooper())

        private val drawRunnable = object : Runnable {
            override fun run() {
                if (!running) return
                val startedAt = SystemClock.uptimeMillis()
                drawFrame()
                // Target ~60 fps. The system paces us; this only bounds our own rate.
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
            lastReportedWidth = width
            lastReportedHeight = height
            prepareShader(width, height)
            Timber.tag(TAG).d(
                "PROBE surface=${width}x$height format=$format " +
                    "screen=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels} " +
                    "agslSupported=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU} " +
                    "shaderReady=${shader != null}",
            )
            start()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            // The system asks for a repaint when the wallpaper becomes visible again
            // (e.g. after the screen wakes). Without this the wallpaper can stay blank.
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
            // Idle behaviour is a spec requirement (§7.2): do no work when hidden.
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
            // xPixelOffset is the direct measure of whether the buffer is wider than the
            // screen: if it moves on a page swipe, the surface is scrollable (§5.3).
            Timber.tag(TAG).d("PROBE offsets xPixel=$xPixelOffset yPixel=$yPixelOffset step=$xOffsetStep")
        }

        // ── Shader setup ───────────────────────────────────────────────────

        private fun prepareShader(width: Int, height: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                shader = null
                agslActive = false
                return
            }
            shader = try {
                RuntimeShader(PROBE_SHADER).apply {
                    setFloatUniform("resolution", width.toFloat(), height.toFloat())
                }
            } catch (e: Exception) {
                // A syntax error in AGSL throws here, not at draw time — and the stack
                // trace may not name the shader.
                Timber.tag(TAG).e(e, "RuntimeShader construction failed; falling back to gradient")
                null
            }
            agslActive = shader != null
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

            // §9a: RuntimeShader needs a hardware canvas. lockCanvas() returns a software
            // canvas and throws "Software rendering doesn't support RuntimeShader".
            // Keep "wallpaper runs" and "AGSL available" as two separate facts (§9a).
            var canvas: Canvas? = null
            var hardware = true
            try {
                canvas = holder.lockHardwareCanvas()
            } catch (e: IllegalStateException) {
                // SurfaceHolder doesn't support lockHardwareCanvas.
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
                    // Surface not ready yet, or lost between visibility check and lock.
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

            frameCount++
            if (frameCount == FRAME_REPORT_INTERVAL) {
                Timber.tag(TAG).d("PROBE frames=$frameCount hw=$hardware agsl=${agslActive && hardware}")
                frameCount = 0
            }
        }

        private fun renderFrame(canvas: Canvas, hardware: Boolean) {
            val w = canvas.width.toFloat()
            val h = canvas.height.toFloat()
            if (w <= 0f || h <= 0f) return

            val useShader = hardware && shader != null
            if (useShader) {
                val time = (SystemClock.uptimeMillis() % LOOP_PERIOD_MS) / LOOP_PERIOD_MS.toFloat()
                shader?.setFloatUniform("resolution", w, h)
                shader?.setFloatUniform("time", time)
                paint.shader = shader
                canvas.drawRect(0f, 0f, w, h, paint)
            } else {
                // Fallback: a plain gradient so the probe still proves the wallpaper runs
                // even where AGSL is unavailable. §9a — keep these outcomes distinct.
                val time = (SystemClock.uptimeMillis() % LOOP_PERIOD_MS) / LOOP_PERIOD_MS.toFloat()
                val shift = time * w
                paint.shader = LinearGradient(
                    shift - w, 0f, shift, h,
                    intArrayOf(Color.BLACK, Color.rgb(0, 90, 160), Color.BLACK),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, w, h, paint)
            }
        }
    }

    private companion object {
        const val TAG = "MetrolistWallpaper"

        /** ~60 fps target. The system still paces us; this bounds our own loop. */
        const val FRAME_BUDGET_MS = 16L

        /** Gradient period. */
        const val LOOP_PERIOD_MS = 4000L

        /** Emit a status line every N frames so logcat stays readable. */
        const val FRAME_REPORT_INTERVAL = 120

        /**
         * Trivial moving gradient. Exists only to prove the AGSL path works in a
         * wallpaper surface; the real ring shader arrives in Phase 3.
         *
         * Not `const` — `trimIndent()` is not a compile-time constant.
         */
        val PROBE_SHADER = """
            uniform float2 resolution;
            uniform float  time;

            half4 main(float2 fragCoord) {
                float2 uv = fragCoord / resolution;
                float phase = time * 6.2831853;
                float wave = 0.5 + 0.5 * sin(uv.x * 6.2831853 + phase);
                float vignette = 1.0 - length(uv - float2(0.5)) * 0.9;
                float v = max(vignette, 0.0);
                return half4(v * 0.20, v * wave * 0.45, v * 0.85, 1.0);
            }
        """.trimIndent()
    }
}