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
import coil3.ImageLoader
import coil3.imageLoader
import com.metrolist.music.ui.player.CoverBassPulse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import timber.log.Timber

/**
 * Live wallpaper that draws bass-reactive glow rings around the home-screen widgets.
 *
 * Renders with AGSL via [HomeRingShader], a port of the shader the MiniPlayer already
 * uses. The wallpaper is what makes this possible at all: an app widget is a `RemoteViews`
 * bitmap over a ~1 MB Binder pipe with a ~2 fps ceiling, whereas this is a real `Surface`
 * on the real frame clock — measured at **60.13 fps** on HyperOS 3.
 *
 * Findings and the full rationale live in `SPEC_HOME_WALLPAPER_RINGS.md`. The ones that
 * shape this file:
 *
 * - The surface is 1080×2400 and maps **1:1** to the screen (§5.3), so ring coordinates
 *   are plain screen pixels with no transform.
 * - The surface **does not scroll** (`xOffsetStep == -1.0`), so rings cannot drift across
 *   home-screen pages (§6).
 * - `lockHardwareCanvas()` works here, which AGSL requires (§9a). Without it the canvas is
 *   software-backed and `RuntimeShader` throws.
 * - Services run in the same process as the app, so the wallpaper reads the real
 *   `CoverBassPulse` singleton directly — no IPC, no second audio capture.
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

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        private var running = false
        private var visible = false
        private var surfaceReady = false
        private var captureHeld = false

        /** Fades toward the current target rather than snapping — see [driveAudio]. */
        private var ringAlpha = 0f
        private var lastFrameMs = 0L
        private var lastAdvanceNs = 0L

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
            HomeRingSettings.start(scope, this@MetrolistWallpaperService)
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
            stopLoop()
            surfaceReady = false
            Timber.tag(TAG).d("onSurfaceDestroyed after $frameCount frames")
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            this.visible = visible
            Timber.tag(TAG).d("onVisibilityChanged visible=$visible")
            if (visible) start() else stopLoop()
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
            // static geometry in HomeRings stays correct. Logged to detect a change.
            Timber.tag(TAG).d("PROBE offsets xPixel=$xPixelOffset yPixel=$yPixelOffset step=$xOffsetStep")
        }

        override fun onDestroy() {
            stopLoop()
            HomeRingSettings.stop()
            scope.cancel()
            super.onDestroy()
        }

        // ── Audio capture ownership ────────────────────────────────────────

        /**
         * Claims the shared `CoverBassPulse` capture for as long as the wallpaper is on
         * screen. The player screen releases it whenever its own conditions lapse, which
         * would otherwise kill the capture the moment the app is backgrounded — exactly
         * when the rings need it.
         */
        private fun holdCapture() {
            if (captureHeld) return
            captureHeld = true
            CoverBassPulse.retain(RETAIN_TOKEN)
            if (HomeRingAudioState.audioSessionValid) {
                CoverBassPulse.init(HomeRingAudioState.audioSessionId, RETAIN_TOKEN)
            }
        }

        private fun releaseCapture() {
            if (!captureHeld) return
            captureHeld = false
            CoverBassPulse.unretain(RETAIN_TOKEN)
            HomeRingAudioState.clearPlayback()
            HomeRingColorSource.clear()
            ringAlpha = 0f
            lastFrameMs = 0L
            lastAdvanceNs = 0L
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
            holdCapture()
            handler.removeCallbacks(drawRunnable)
            handler.post(drawRunnable)
            Timber.tag(TAG).d("Frame loop started")
        }

        private fun stopLoop() {
            running = false
            handler.removeCallbacks(drawRunnable)
            // Hidden: hand the capture back and let the rings fade to nothing next time.
            releaseCapture()
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
                    "PROBE frames=$frameCount hw=$hardwareCanvasSeen rings=${rings?.size ?: 0} " +
                        "alpha=${"%.2f".format(ringAlpha)} " +
                        "playing=${HomeRingAudioState.isPlaying} " +
                        "session=${HomeRingAudioState.audioSessionId} " +
                        "sessionValid=${HomeRingAudioState.audioSessionValid} " +
                        "gate=${HomeRingSettings.debugWidgetOn} " +
                        "active=${HomeRingAudioState.isActive()} " +
                        "bass=${CoverBassPulse.smoothedBass} " +
                        "color=${Integer.toHexString(HomeRingColorSource.color)}",
                )
                frameCount = 0
            }
        }

        private fun renderFrame(canvas: Canvas, hardware: Boolean) {
            // Background. Pure black per spec §8.3 — on AMOLED those pixels are simply off.
            canvas.drawColor(Color.BLACK)

            val activeRings = rings
            if (!hardware || activeRings == null) return

            val driven = driveAudio() ?: return
            for (ring in activeRings) {
                ring.draw(
                    canvas = canvas,
                    paint = paint,
                    color = driven.color,
                    bass = driven.bass,
                    alpha = driven.alpha,
                    timeSeconds = driven.timeSeconds,
                    hotspotMult = driven.hotspotMult,
                )
            }
        }

        /**
         * Advances the audio envelope and resolves this frame's ring alpha.
         *
         * Returns `null` when nothing should be painted, so the idle state is an honest
         * black screen rather than a frozen glow that implies a stalled visualizer.
         */
        private fun driveAudio(): FrameInput? {
            // Shader time MUST stay small. The AGSL evaluates `cos(angle - time*orbitSpeed)`
            // and float32 loses the fractional part of a large argument, so the orbit
            // freezes. MiniPlayer hits the same trap and wraps its time
            // (`MiniPlayer.kt:468`); wrap identically rather than passing raw uptime.
            val timeSeconds = (SystemClock.uptimeMillis() % TIME_WRAP_MS) / 1000f

            // §4.3 gate: the widget must show *my* song. On the partner's track there is no
            // local audio to visualise, so the rings stay completely inactive.
            val gateOpen = HomeRingSettings.debugWidgetOn
            val audioActive = gateOpen && HomeRingAudioState.isActive()
            if (audioActive && HomeRingAudioState.audioSessionId > 0 &&
                !CoverBassPulse.isCapturing(HomeRingAudioState.audioSessionId)
            ) {
                // Only when there is genuinely nothing bound. Asking every frame is fine in
                // principle (init early-returns) but it hides ordering mistakes in
                // CoverBassPulse, and it did hide one.
                CoverBassPulse.init(HomeRingAudioState.audioSessionId, RETAIN_TOKEN)
            }

            // Drive the 60 fps display envelope from this loop: composition is torn down
            // when the app is backgrounded, and the wallpaper has its own frame clock.
            // `advanceFrame` guards against double-advancing while both are alive.
            if (audioActive) {
                val nowNs = System.nanoTime()
                if (lastAdvanceNs != 0L) CoverBassPulse.advanceFrame(lastAdvanceNs, nowNs)
                lastAdvanceNs = nowNs
            } else {
                lastAdvanceNs = 0L
            }

            val bass = if (audioActive) {
                CoverBassPulse.smoothedBass.coerceIn(0f, 1f)
            } else {
                0f
            }
            val peak = HomeRingSettings.peak
            val target = when {
                !visible -> 0f
                !gateOpen -> 0f
                !audioActive -> 0f
                // Dimmed on mute or casting — matches the MiniPlayer exactly (§4b).
                HomeRingAudioState.isDimmed() -> IDLE_ALPHA
                // The MiniPlayer's curve: 0.2 + bass * (peak - 0.2).
                else -> (IDLE_ALPHA + bass * (peak - IDLE_ALPHA)).coerceIn(IDLE_ALPHA, peak)
            }

            // Ease toward the target so stopping/hiding genuinely fades rather than snaps.
            val nowMs = SystemClock.uptimeMillis()
            val dtMs = if (lastFrameMs == 0L) 0f else (nowMs - lastFrameMs).toFloat()
            lastFrameMs = nowMs
            ringAlpha += (target - ringAlpha) * (dtMs / FADE_MS).coerceIn(0f, 1f)
            if (ringAlpha < ALPHA_EPSILON && target == 0f) {
                HomeRingColorSource.clear()
                return null
            }

            if (audioActive) {
                HomeRingColorSource.request(
                    context = this@MetrolistWallpaperService,
                    imageLoader = this@MetrolistWallpaperService.imageLoader,
                    songId = HomeRingAudioState.songId,
                    thumbnailUrl = HomeRingAudioState.thumbnailUrl,
                )
            }

            return FrameInput(
                bass = bass,
                alpha = ringAlpha,
                color = HomeRingColorSource.color,
                timeSeconds = timeSeconds,
                hotspotMult = HomeRingSettings.hotspotMult,
            )
        }
    }

    private companion object {
        const val TAG = "MetrolistWallpaper"

        /** Ownership token for the shared CoverBassPulse capture. */
        const val RETAIN_TOKEN = "homeWallpaper"

        /** ~60 fps target. The system still paces us; this only bounds our own loop. */
        const val FRAME_BUDGET_MS = 16L

        /** Emit a status line every N frames so logcat stays readable. */
        const val FRAME_REPORT_INTERVAL = 120

        /**
         * Wrap shader time every 60s, matching MiniPlayer (`MiniPlayer.kt:468`). Keeps the
         * `cos()` argument small enough that float32 does not quantise the orbit away.
         */
        const val TIME_WRAP_MS = 60_000L

        /** MiniPlayer's floor (`MiniPlayer.kt:456`) — the ring persists while playing. */
        const val IDLE_ALPHA = 0.2f

        /** Time constant for the fade in/out, in ms. */
        const val FADE_MS = 260f

        /** Below this the ring is indistinguishable from black; skip the draw entirely. */
        const val ALPHA_EPSILON = 0.004f
    }
}

/** What one frame needs, after the alpha envelope has been resolved. */
private data class FrameInput(
    val bass: Float,
    val alpha: Float,
    val color: Int,
    val timeSeconds: Float,
    val hotspotMult: Float,
)