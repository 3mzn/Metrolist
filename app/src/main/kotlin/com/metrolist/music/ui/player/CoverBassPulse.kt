/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import android.media.audiofx.Visualizer
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.metrolist.music.constants.CoverPulseIntensity
import timber.log.Timber
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * SPEC_COVER_PULSE Phase 2: real-time bass driver for the pulsing player cover.
 *
 * FFT capture ported from OuterTune's `AudioVisualizerUtils`, narrowed to the
 * kick-drum bins (~40-130Hz) so mids/vocals never move the cover, plus a
 * gated transient detector: a quick follower vs a floor-tracking baseline
 * (falls fast, rises very slowly so kicks can't pump it), an absolute spike
 * gate to ignore bin noise, and an asymmetric envelope (eased attack, slow
 * release). Only real kicks pop the cover; sustained bass barely registers.
 * [smoothedBass] is safe to read inside a `graphicsLayer` block
 * (no per-callback recomposition).
 *
 * Lifecycle is owned by the caller (Thumbnail): [init] when playing with a
 * valid session, [release] on pause/background, [reset] on song change.
 */
object CoverBassPulse {
    private const val TAG = "CoverBassPulse"

    /** Smoothed bass 0..1. Frozen when the Visualizer is released. */
    var smoothedBass by mutableFloatStateOf(0f)
        private set

    /**
     * SPEC_MINI_BORDER B1: split signals for the mini-player border.
     * [kickEnv] is the kick-only envelope (attack snaps in captures, release
     * glides in [advanceFrame]); [sustainLevel] is the slow floor (sustain
     * proxy); [onsetEnv] spikes to 1 on each kick onset crossing and decays
     * over ~200ms for the width pop. All safe for draw-scope reads.
     */
    var kickEnv by mutableFloatStateOf(0f)
        private set
    var sustainLevel by mutableFloatStateOf(0f)
        private set
    var onsetEnv by mutableFloatStateOf(0f)
        private set

    /** Monotonically increasing count of kick onsets. */
    var onsetCount by mutableLongStateOf(0L)
        private set

    private var visualizer: Visualizer? = null
    private var lastCaptureMs: Long = 0L

    // Transient-kick detector state (all 0..1): fast follower catches onsets,
    // floor-tracking baseline rides the quiet gaps (falls fast, rises very
    // slowly so kick peaks can't pump it), envelope holds only the jumps.
    // Captures (~20Hz) write follow/baseline/kickTarget; the frame loop below
    // eases the displayed kickEnv/smoothedBass every frame (60fps motion with
    // identical timing — sampling a curve more finely adds no lag).
    private var follow = 0f
    private var baseline = 0f
    private var kickTarget = 0f
    private var onsetArmed = true
    private var wasStalled = false

    /**
     * Owner token (the initiating item's media id). The Visualizer is a
     * process-wide singleton shared by every carousel item, so [releaseIf]
     * lets a non-owning item's teardown no-op instead of killing the live
     * capture. [release] force-releases regardless of owner.
     */
    private var owner: String? = null
    private var lastInitSession: Int? = null

    /**
     * Tokens that need the capture kept alive even when their own "do I want this?"
     * condition is false — see [retain]. The live wallpaper is the only current user: it
     * renders while the app is backgrounded, where the player screen is not composed and
     * would otherwise have released everything.
     */
    private val retainers = mutableSetOf<String>()

    /**
     * Keeps the capture alive regardless of [release] calls until [unretain] is called.
     * Process-wide and thread-safe enough for our two callers (main thread in both cases).
     */
    /**
     * Keeps the capture alive regardless of [release] calls until [unretain] is called.
     *
     * **Every consumer must hold a claim while it needs data**, not just the wallpaper.
     * A consumer that instead calls [init] once and [release] opportunistically will break:
     * it only asks again when its own composable restarts, so if another holder lets go
     * first the capture dies underneath it and its visuals silently stop updating. That
     * was a real bug — the MiniPlayer ring died ~2s after foregrounding, and came back
     * only when a preference toggle re-ran its effect.
     */
    fun retain(token: String) {
        retainers += token
    }

    /** Drops this consumer's claim; the Visualizer dies only once nobody holds it. */
    fun unretain(token: String) {
        retainers -= token
        if (retainers.isEmpty()) hardRelease()
    }

    /** True while some consumer still needs the capture. */
    fun isRetained(): Boolean = retainers.isNotEmpty()

    /**
     * True when a live capture is already bound to [sessionId], so a caller can skip
     * [init] entirely. The wallpaper checks this every frame; `init` is cheap when it
     * early-returns, but making the intent explicit keeps per-frame callers honest.
     */
    fun isCapturing(sessionId: Int): Boolean =
        visualizer != null && sessionId > 0 && sessionId == lastInitSession

    /**
     * Monotonic guard for [advanceFrame]. Composition and the wallpaper's render loop are
     * two independent 60 fps drivers in the same process; without this, whichever ran
     * second would re-apply the release glide and the envelope would decay twice as fast.
     * Both pass `System.nanoTime()`-based stamps, so the larger one always wins.
     */
    private var lastAdvanceNs = 0L

    fun peakFor(intensity: CoverPulseIntensity): Float =
        when (intensity) {
            CoverPulseIntensity.LOW -> 1.06f
            CoverPulseIntensity.MEDIUM -> 1.12f
            CoverPulseIntensity.HIGH -> 1.18f
        }

    /** Cover scale for the current smoothed bass at [intensity]. */
    fun scaleFor(intensity: CoverPulseIntensity): Float =
        1f + smoothedBass * (peakFor(intensity) - 1f)

    fun reset() {
        smoothedBass = 0f
        follow = 0f
        baseline = 0f
        sustainLevel = 0f
        kickEnv = 0f
        onsetEnv = 0f
        onsetCount = 0L
        onsetArmed = true
        wasStalled = false
        kickTarget = 0f
        lastCaptureMs = 0L
    }

    /**
     * Idempotent start: returns without touching anything if a live capture
     * already exists for this session+owner (effect restarts from buffering
     * flaps must not destroy it). New session/owner (or dead instance)
     * recreates. Never resets the envelope — song changes reset separately
     * so flap churn preserves motion instead of zeroing it.
     */
    fun init(audioSessionId: Int, owner: String) {
        if (audioSessionId <= 0) return
        // A live capture for this session is already good enough — adopt it. Without this,
        // the player screen and the wallpaper would tear down and recreate the Visualizer
        // every time ownership ping-ponged between them.
        if (visualizer != null && audioSessionId == lastInitSession) return
        try {
            // Force the previous instance down even while retained. `init` means "give me
            // a capture for THIS session"; honouring a retainer here would leave the old
            // Visualizer unreleased and then overwrite the reference, leaking it — and the
            // two instances would fight over the same audio session.
            //
            // ORDER MATTERS: hardRelease() clears lastInitSession, so the session must be
            // recorded *after* it. Recording it first left it null again, and the next
            // init() for the same session would then tear down and rebuild the Visualizer
            // again — every single call. The wallpaper calls init() once per frame, so that
            // churn meant the Visualizer was recreated 60x a second and never delivered a
            // capture: the rings sat frozen while the in-app visuals looked fine.
            hardRelease()
            lastInitSession = audioSessionId
            this.owner = owner
            val v = Visualizer(audioSessionId)
            v.captureSize = 1024
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray,
                        samplingRate: Int,
                    ) = Unit

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray,
                        samplingRate: Int,
                    ) {
                        onFft(fft)
                    }
                },
                Visualizer.getMaxCaptureRate(),
                false,
                true,
            )
            v.enabled = true
            visualizer = v
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Visualizer init failed for session %d", audioSessionId)
            release()
        }
    }

    /**
     * Drops this consumer's claim on the capture. The Visualizer only dies once **nobody**
     * holds it — both the player screen and the live wallpaper must [retain], otherwise
     * whichever one lets go last silently kills the other.
     */
    fun release() {
        if (retainers.isNotEmpty()) return
        hardRelease()
    }

    /** Unconditional teardown, ignoring retainers. Only for [init] replacing a session. */
    private fun hardRelease() {
        try {
            visualizer?.release()
        } catch (_: Exception) {
        } finally {
            visualizer = null
            owner = null
            lastInitSession = null
        }
    }

    /**
     * 60fps display driver, called once per frame from composition (which owns
     * the frame clock — a standalone scope has none and crashes). Captures
     * arrive at ~20Hz and only move [kickTarget]; this re-samples the 250ms
     * release glide every frame for smooth motion with identical timing.
     *
     * Watchdog: if captures stall (dead session, DSP hiccup) the last target
     * would pin the envelope at max forever — after 1s of silence the target
     * is forced to 0 so visuals glide home instead. Recovers automatically
     * when captures resume.
     */
    fun advanceFrame(prevNs: Long, nowNs: Long) {
        // Two independent 60 fps drivers exist (composition, and the wallpaper's own
        // render loop). Whichever fires second for the same instant must not re-apply the
        // release glide, or the envelope decays at double rate. See [lastAdvanceNs].
        if (nowNs <= lastAdvanceNs) return
        lastAdvanceNs = nowNs
        val dtMs = (nowNs - prevNs) / 1_000_000f
        if (dtMs !in 0f..250f) return
        val stalled = SystemClock.elapsedRealtime() - lastCaptureMs > 1000L
        if (stalled) {
            if (!wasStalled) {
                wasStalled = true
                kickTarget = 0f
            }
        } else {
            wasStalled = false
        }
        kickEnv = maxOf(kickTarget, kickEnv * exp(-dtMs / 250f))
        onsetEnv *= exp(-dtMs / 200f)
        smoothedBass = (kickEnv * 0.9f + baseline * 0.1f).coerceIn(0f, 1f)
    }

    /** Releases only if [requester] owns the live capture; else no-op. */
    fun releaseIf(requester: String) {
        if (owner == requester) release()
    }

    /** Milliseconds since the last FFT capture (huge if none yet). */
    fun feedAgeMs(): Long = SystemClock.elapsedRealtime() - lastCaptureMs

    private fun onFft(fft: ByteArray) {
        val raw = kickBand(fft)
        val now = SystemClock.elapsedRealtime()
        if (lastCaptureMs == 0L) {
            follow = raw
            baseline = raw
            sustainLevel = raw
            kickEnv = 0f
            onsetEnv = 0f
            onsetArmed = true
            smoothedBass = 0f
        } else {
            val dt = (now - lastCaptureMs).coerceAtLeast(0L).toFloat()
            // Follower with quick attack (~50ms) so kicks still land on the
            // beat, smooth release (~120ms) so single noisy frames can't
            // chatter the cover.
            follow =
                if (raw > follow) {
                    follow + (raw - follow) * (1f - exp(-dt / 50f))
                } else {
                    follow * exp(-dt / 120f)
                }
            // Floor tracker: chase quiet gaps down fast, creep up very slowly
            // so kick peaks never pump the reference they are judged against.
            baseline =
                if (follow < baseline) {
                    baseline + (follow - baseline) * (1f - exp(-dt / 400f))
                } else {
                    baseline + (follow - baseline) * (1f - exp(-dt / 3000f))
                }
            sustainLevel = baseline
            // Absolute gate: spikes smaller than this are bin noise, vocal
            // wobble or bass-note edges — not kicks. Kills the trembling in
            // quiet passages where the relative division would amplify them.
            val spike = maxOf(0f, follow - baseline - 0.065f)
            kickTarget = (spike / (0.39f + baseline * 0.5f)).coerceIn(0f, 1f)
            // Onset edge-trigger for the border width pop: fires once per
            // crossing above 0.55, re-arms below 0.2.
            if (kickTarget > 0.55f) {
                if (onsetArmed) {
                    onsetEnv = 1f
                    onsetCount++
                    onsetArmed = false
                }
            } else if (kickTarget < 0.2f) {
                onsetArmed = true
            }
            // Attack snaps here (identical timing to before); the frame loop
            // owns the release glide, so single noisy frames can't chatter.
            if (kickTarget > kickEnv) kickEnv = kickTarget
        }
        lastCaptureMs = now
    }

    /**
     * Kick-drum band only (~40-130Hz at 44.1/48kHz): FFT bins 1..2, skipping
     * the DC bin. Narrow on purpose — mids/vocals/snares must not move
     * the cover.
     *
     * Bytes are SIGNED (-128..127): near-silence hovers around 0, loud bins
     * swing wide. (Unsigned `& 0xFF` handling would map noise like -2 to 254
     * and make quiet passages read louder than kicks — the old jitter bug.)
     */
    private fun kickBand(fft: ByteArray): Float {
        if (fft.size < 6) return 0f
        var sum = 0f
        var count = 0
        for (k in 1..2) {
            val i = k * 2
            if (i + 1 >= fft.size) break
            val real = fft[i].toFloat()
            val imag = fft[i + 1].toFloat()
            sum += sqrt(real * real + imag * imag)
            count++
        }
        return if (count > 0) (sum / count / 128f).coerceIn(0f, 1f) else 0f
    }
}
