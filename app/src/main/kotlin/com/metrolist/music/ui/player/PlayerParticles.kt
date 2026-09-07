/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ambient particle field for the main expanded player background.
 *
 * Particles spawn across the full screen, drift slowly, and despawn after
 * ~2s with fade in/out. On bass hits (via [CoverBassPulse]), they vibrate
 * rapidly and gain a soft blur ring.
 */

private const val PARTICLE_COUNT = 80

private class Particle(
    var baseX: Float = 0f,
    var baseY: Float = 0f,
    var driftVx: Float = 0f,
    var driftVy: Float = 0f,
    var size: Float = 3f,
    var maxAlpha: Float = 0.5f,
    var hueShift: Float = 0f,
    var life: Float = 0f,
    var maxLife: Float = 2f,
) {
    val alive get() = life > 0f

    fun spawn(w: Float, h: Float) {
        baseX = Random.nextFloat() * w
        baseY = Random.nextFloat() * h
        driftVx = (Random.nextFloat() - 0.5f) * 1.5f // px/frame @60fps
        driftVy = (Random.nextFloat() - 0.5f) * 1.5f
        size = 2f + Random.nextFloat() * 4f
        maxAlpha = 0.25f + Random.nextFloat() * 0.45f
        hueShift = Random.nextFloat() * 0.15f - 0.075f
        maxLife = 1f + Random.nextFloat() * 1f // 1–2s
        life = maxLife
    }
}

private fun DrawScope.drawParticles(
    particles: Array<Particle>,
    baseColor: Color,
    shakeX: FloatArray,
    shakeY: FloatArray,
    blurRadius: Float,
) {
    for (i in particles.indices) {
        val p = particles[i]
        if (!p.alive) continue

        val lifeRatio = (p.life / p.maxLife).coerceIn(0f, 1f)
        // Fade in first 15%, fade out last 30%
        val fade = when {
            lifeRatio > 0.85f -> (1f - lifeRatio) / 0.15f
            lifeRatio < 0.3f -> lifeRatio / 0.3f
            else -> 1f
        }
        val a = p.maxAlpha * fade
        if (a < 0.01f) continue

        val x = p.baseX + shakeX[i]
        val y = p.baseY + shakeY[i]

        // Blur ring during vibration — very subtle
        if (blurRadius > 0.5f) {
            drawCircle(
                color = baseColor.copy(alpha = a * 0.04f),
                radius = p.size + blurRadius * 0.3f,
                center = Offset(x, y),
            )
        }
        // Glow
        drawCircle(
            color = baseColor.copy(alpha = a * 0.25f),
            radius = p.size * 3f,
            center = Offset(x, y),
        )
        // Core
        drawCircle(
            color = baseColor.copy(alpha = a),
            radius = p.size,
            center = Offset(x, y),
        )
    }
}

@Composable
fun PlayerParticles(
    modifier: Modifier = Modifier,
    baseColor: Color = Color.Unspecified,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "particles")
    val frame by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "particleFrame",
    )
    @Suppress("UNUSED_VARIABLE")
    val _frame = frame

    val particles = remember { Array(PARTICLE_COUNT) { Particle() } }
    val shakeX = remember { FloatArray(PARTICLE_COUNT) }
    val shakeY = remember { FloatArray(PARTICLE_COUNT) }

    Canvas(
        modifier = modifier.graphicsLayer {
            // Always-on soft blur — subtle base + extra during bass
            val b = CoverBassPulse.smoothedBass
            val blurPx = (3f + b * 6f).coerceAtMost(10f)
            renderEffect = android.graphics.RenderEffect
                .createBlurEffect(blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
    ) {
        val w = size.width
        val h = size.height
        val bass = CoverBassPulse.smoothedBass
        val kick = CoverBassPulse.kickEnv
        val nowNs = System.nanoTime()
        val dtSec = 16f / 1000f // ~60fps frame interval
        val t = (nowNs % 60_000_000_000L) / 60_000_000_000f

        // Blur radius scales with bass
        val blur = (bass * 20f + kick * 15f).coerceAtMost(30f)

        // Vibration amplitude
        val shakeAmp = (bass * 15f + kick * 10f).coerceAtMost(20f)

        for (i in particles.indices) {
            val p = particles[i]

            // Age particles
            if (p.alive) {
                p.life -= dtSec
                // Freeze drift during vibration — particles shake in place
                if (bass < 0.1f) {
                    p.baseX += p.driftVx
                    p.baseY += p.driftVy
                }
                // Wrap around screen
                if (p.baseX < -20f) p.baseX = w + 20f
                if (p.baseX > w + 20f) p.baseX = -20f
                if (p.baseY < -20f) p.baseY = h + 20f
                if (p.baseY > h + 20f) p.baseY = -20f
            }

            // Respawn dead particles
            if (!p.alive) {
                p.spawn(w, h)
            }

            // Vibration shake
            val phase = t * 576f + i * 47f // 60% faster vibration
            val rad = Math.toRadians(phase.toDouble()).toFloat()
            shakeX[i] = cos(rad) * shakeAmp * (0.5f + Random.nextFloat() * 0.5f)
            shakeY[i] = sin(rad) * shakeAmp * (0.5f + Random.nextFloat() * 0.5f)
        }

        drawParticles(particles, baseColor, shakeX, shakeY, blur)
    }
}
