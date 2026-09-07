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
        uniform float2  resolution;
        uniform float4  ring;
        uniform float   cornerRadius;
        uniform float   ringAlpha;
        uniform float   bass;
        uniform float   cr, cg, cb;
        uniform float   time;
        uniform float   onset;
        uniform float   lastOnsetMs;

        float sdRoundedRect(vec2 p, vec2 b, float r) {
            vec2 q = abs(p) - b + r;
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
        }

        half4 main(float2 fragCoord) {
            float2 ringCenter = ring.xy + ring.zw * 0.5;
            float2 halfSize   = ring.zw * 0.5;

            float sdf = sdRoundedRect(fragCoord - ringCenter, halfSize, cornerRadius);
            float dist = abs(sdf);

            float b = smoothstep(0.08, 0.55, bass);

            // --- Core glow (tight) ---
            float coreFalloff = 2.5 - b * 1.0;
            float core = exp(-dist / coreFalloff) * b;

            // --- Bloom (wide) ---
            float bloomFalloff = 6.0 - b * 3.0;
            float bloom = exp(-dist / bloomFalloff) * b * 0.4;

            float baseGlow = core + bloom;

            // --- Orbiting hotspot (modulates base glow) ---
            float angle = atan(fragCoord.y - ringCenter.y, fragCoord.x - ringCenter.x);
            float orbitSpeed = 0.8 + b * 4.0;
            float hotspotPhase = angle - time * orbitSpeed;
            float sharpness = 1.5 + b * 2.5;   // wider lobe
            float hotspotLobe = pow(max(0.0, cos(hotspotPhase)), sharpness);
            // Wide mask — hotspot visible across the full pill area
            float hotspotMask = exp(-dist * dist / 200.0);
            float hotspot = hotspotLobe * hotspotMask * (0.5 + b * 0.5);
            // Strong modulation — hotspot is 4-5x brighter than surrounding glow
            baseGlow *= 1.0 + hotspot * 4.5;

            // --- Kick shockwave (modulates base glow) ---
            float shockwave = 0.0;
            float shockAge = lastOnsetMs / 300.0;
            if (shockAge < 1.0 && shockAge >= 0.0) {
                float maxRadius = 30.0;
                float waveRadius = shockAge * maxRadius;
                float waveDist = abs(dist - waveRadius);
                float waveWidth = 4.0 + (1.0 - shockAge) * 3.0;
                shockwave = onset * exp(-waveDist * waveWidth) * (1.0 - shockAge * 0.5);
            }
            baseGlow *= 1.0 + shockwave * 3.0;

            // --- Final intensity ---
            float intensity = baseGlow * (1.0 + b * 0.8);

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
            time: Float,
            onset: Float,
            lastOnsetMs: Float,
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
            s.setFloatUniform("time", time)
            s.setFloatUniform("onset", onset)
            s.setFloatUniform("lastOnsetMs", lastOnsetMs)
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
        time: Float,
        onset: Float,
        lastOnsetMs: Float,
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
            time = time,
            onset = onset,
            lastOnsetMs = lastOnsetMs,
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
