/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import android.media.audiofx.Visualizer
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
    private var kickEnv = 0f
    private var kickTarget = 0f

    /**
     * Owner token (the initiating item's media id). The Visualizer is a
     * process-wide singleton shared by every carousel item, so [releaseIf]
     * lets a non-owning item's teardown no-op instead of killing the live
     * capture. [release] force-releases regardless of owner.
     */
    private var owner: String? = null

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
        kickEnv = 0f
        kickTarget = 0f
        lastCaptureMs = 0L
    }

    fun init(audioSessionId: Int, owner: String) {
        if (audioSessionId <= 0) return
        try {
            release()
            reset()
            this.owner = owner
            visualizer =
                Visualizer(audioSessionId).apply {
                    enabled = false
                    captureSize = 1024
                    setDataCaptureListener(
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
                    enabled = true
                }
            Timber.tag(TAG).d("Initialized with session %d", audioSessionId)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Visualizer init failed, cover stays static")
            release()
        }
    }

    fun release() {
        try {
            visualizer?.release()
        } catch (_: Exception) {
            // Best effort; a half-initialized Visualizer must not crash playback.
        } finally {
            visualizer = null
            owner = null
        }
    }

    /**
     * 60fps display driver, called once per frame from composition (which owns
     * the frame clock — a standalone scope has none and crashes). Captures
     * arrive at ~20Hz and only move [kickTarget]; this re-samples the 250ms
     * release glide every frame for smooth motion with identical timing.
     */
    fun advanceFrame(prevNs: Long, nowNs: Long) {
        val dtMs = (nowNs - prevNs) / 1_000_000f
        if (dtMs !in 0f..250f) return
        kickEnv = maxOf(kickTarget, kickEnv * exp(-dtMs / 250f))
        smoothedBass = (kickEnv * 0.9f + baseline * 0.1f).coerceIn(0f, 1f)
    }

    /** Releases only if [requester] owns the live capture; else no-op. */
    fun releaseIf(requester: String) {
        if (owner == requester) release()
    }

    private fun onFft(fft: ByteArray) {
        val raw = kickBand(fft)
        val now = SystemClock.elapsedRealtime()
        if (lastCaptureMs == 0L) {
            follow = raw
            baseline = raw
            kickEnv = 0f
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
            // Absolute gate: spikes smaller than this are bin noise, vocal
            // wobble or bass-note edges — not kicks. Kills the trembling in
            // quiet passages where the relative division would amplify them.
            val spike = maxOf(0f, follow - baseline - 0.065f)
            kickTarget = (spike / (0.39f + baseline * 0.5f)).coerceIn(0f, 1f)
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
