package com.metrolist.music.ui.player

import android.os.Build
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.ui.graphics.toArgb

/**
 * AGSL shader that renders a soft glow around a rounded-rectangle ring.
 *
 * Per-pixel it computes a signed-distance field to the ring centerline and
 * applies an exponential falloff whose radius is modulated by [bass].
 *
 * API 33+ only — callers must check [Build.VERSION.SDK_INT] and fall back
 * to a plain [Stroke] ring on older devices.
 */
object BorderGlowShader {

    // ---------------------------------------------------------------
    // AGSL source — runs on the GPU via Skia
    // ---------------------------------------------------------------
    private val SHADER_SRC = """
        uniform float2  resolution;   // draw scope size in px
        uniform float4  ring;         // x, y, w, h  of the ring rect
        uniform float   cornerRadius;
        uniform float   ringAlpha;    // overall opacity
        uniform float   bass;         // 0..1  — controls glow spread
        uniform float   cr, cg, cb;  // palette color (0..1 per channel)

        // Signed-distance to a rounded rectangle centered at origin.
        float sdRoundedRect(vec2 p, vec2 b, float r) {
            vec2 q = abs(p) - b + r;
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
        }

        half4 main(float2 fragCoord) {
            // Ring geometry in local coords (y-down).
            float2 ringCenter = ring.xy + ring.zw * 0.5;
            float2 halfSize   = ring.zw * 0.5;

            float sdf = sdRoundedRect(fragCoord - ringCenter, halfSize, cornerRadius);

            // Absolute distance from the ring centerline.
            float dist = abs(sdf);

            // Scale bass aggressively: <0.08 is silent (no visible glow),
            // then ramps to 1.0 quickly so kicks pop.
            float b = smoothstep(0.08, 0.55, bass);

            // --- Layer 1: tight core glow ---
            float coreFalloff = 3.0 - b * 1.5;   // 3.0 → 1.5
            float core = exp(-dist / coreFalloff) * b;

            // --- Layer 2: wide bloom (light scattering) ---
            float bloomFalloff = 8.0 - b * 4.0;  // 8.0 → 4.0
            float bloom = exp(-dist / bloomFalloff) * b * 0.5;

            // Additive composite — values > 1 are fine (clamped to max
            // brightness, which reads as "blown-out light emission").
            float intensity = (core + bloom) * (1.0 + b * 1.0);

            return half4(cr, cg, cb, intensity * ringAlpha);
        }
    """.trimIndent()

    /**
     * Creates (or reuses) a [ShaderBrush] for the glow ring.
     * The returned brush must be drawn via [drawGlowRing] which sets the
     * per-frame uniforms.
     */
    fun createBrush(): GlowBrush = GlowBrush()

    class GlowBrush : ShaderBrush() {
        private var shader: RuntimeShader? = null

        override fun createShader(size: Size): androidx.compose.ui.graphics.Shader {
            val s = RuntimeShader(SHADER_SRC)
            shader = s
            return s
        }

        /** Call every frame inside [DrawScope] to update ring geometry + bass. */
        fun updateUniforms(
            drawScope: DrawScope,
            ringTopLeft: Offset,
            ringSize: Size,
            cornerRadius: Float,
            color: Color,
            alpha: Float,
            bass: Float,
        ) {
            val s = shader ?: return
            s.setFloatUniform("resolution", drawScope.size.width, drawScope.size.height)
            s.setFloatUniform(
                "ring",
                ringTopLeft.x,
                ringTopLeft.y,
                ringSize.width,
                ringSize.height,
            )
            s.setFloatUniform("cornerRadius", cornerRadius)
            s.setFloatUniform("ringAlpha", alpha)
            s.setFloatUniform("bass", bass)
            s.setFloatUniform("cr", color.red)
            s.setFloatUniform("cg", color.green)
            s.setFloatUniform("cb", color.blue)
        }
    }

    /**
     * Returns `true` when the device supports AGSL shaders (API 33+).
     */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * Draw the glow ring followed by a crisp core stroke.
     * Designed to be called inside [androidx.compose.ui.draw.DrawModifier.DrawScope].
     */
    fun DrawScope.drawGlowRing(
        glowBrush: GlowBrush,
        ringColor: Color,
        bass: Float,
        alpha: Float,
    ) {
        val pad = 2.dp.toPx()          // match the stroke centerline
        val cr = 32.dp.toPx() - pad    // corner radius at the stroke center
        val ringTopLeft = Offset(pad, pad)
        val ringSize = Size(size.width - pad * 2f, size.height - pad * 2f)

        // --- glow layer (shader) ---
        glowBrush.updateUniforms(
            drawScope = this,
            ringTopLeft = ringTopLeft,
            ringSize = ringSize,
            cornerRadius = cr,
            color = ringColor,
            alpha = alpha,
            bass = bass,
        )
        drawRoundRect(
            brush = glowBrush,
            topLeft = ringTopLeft,
            size = ringSize,
            cornerRadius = CornerRadius(cr),
        )

        // --- crisp core ring on top ---
        val strokeWidth = (2.dp.toPx() + bass * 2.dp.toPx())
        drawRoundRect(
            color = ringColor.copy(alpha = alpha),
            topLeft = Offset(pad - strokeWidth / 2f, pad - strokeWidth / 2f),
            size = Size(size.width - (pad - strokeWidth / 2f) * 2f, size.height - (pad - strokeWidth / 2f) * 2f),
            cornerRadius = CornerRadius(cr + strokeWidth / 2f),
            style = Stroke(width = strokeWidth),
        )
    }
}
