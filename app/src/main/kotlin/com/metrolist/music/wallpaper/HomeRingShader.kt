/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wallpaper

import android.graphics.RuntimeShader

/**
 * Home-screen ring renderer for the live wallpaper.
 *
 * **This is a port of `ui/player/BorderGlowShader.kt`** — the AGSL below is the same
 * shader the MiniPlayer already uses, verbatim. Nothing was "simplified for the
 * wallpaper": same SDF, same core/bloom falloffs, same orbiting hotspot, same
 * `hotspotMult` modulation.
 *
 * Two mechanical differences, both forced by the rendering context and neither changing
 * the maths:
 *
 * 1. **Canvas, not Compose.** The wallpaper paints into an `android.graphics.Canvas` from
 *    `SurfaceHolder.lockHardwareCanvas()` (§9a), so uniforms are set via
 *    [RuntimeShader.setFloatUniform] instead of `ShaderBrush`.
 * 2. **One shader instance per ring, each clipped.** The existing shader fills the whole
 *    DrawScope and computes one ring's SDF. Four rings at four unrelated rects cannot
 *    share a single full-screen pass without reworking the maths, so each ring gets its
 *    own instance and its own clip.
 *
 * ### Coordinate spaces
 *
 * The AGSL derives the ring centre from `ring.xy + ring.zw * 0.5`, i.e. it expects
 * `ring.xy` in the *same* space as `fragCoord`. Callers therefore
 * [translate the canvas][drawRing] to the rect origin and pass `ring = (0, 0, w, h)`, so
 * `fragCoord` is rect-local. This lets a single clipped pass cover only the pixels the
 * ring can actually reach — the glow's widest falloff is
 * `12.0 - bass * 6.0` px, so a small margin is enough.
 */
object HomeRingShader {

    /** Widest glow falloff in the AGSL is `12.0 - bass * 6.0`; clip a little beyond it. */
    const val CLIP_MARGIN_PX = 24f

    private val SHADER_SRC = """
        uniform float4  ring;
        uniform float   cornerRadius;
        uniform float   ringAlpha;
        uniform float   bass;
        uniform float   cr, cg, cb;
        uniform float   time;
        uniform float   hotspotMult;

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

            // --- Core glow (tight, 1.5x brighter) ---
            float coreFalloff = 2.5 - b * 1.0;
            float core = exp(-dist / coreFalloff) * b * 1.5;

            // --- Bloom (wide, 2x distance) ---
            float bloomFalloff = 12.0 - b * 6.0;
            float bloom = exp(-dist / bloomFalloff) * b * 0.6;

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
            // Strong modulation — hotspot is hotspotMult x brighter than surrounding glow
            baseGlow *= 1.0 + hotspot * hotspotMult;

            // --- Final intensity ---
            float intensity = baseGlow * (1.0 + b * 0.8);
            float a = clamp(intensity * ringAlpha, 0.0, 1.0);

            // PREMULTIPLIED output. AGSL/RuntimeShader results are interpreted as
            // premultiplied alpha, and blending over black is `dst = src + dst*(1-a)`.
            // Returning the colour channels unpremultiplied therefore ignores `a`
            // completely and paints the full-strength colour across the whole falloff —
            // i.e. a flat fill instead of a glow. RGB must be scaled by alpha.
            return half4(cr * a, cg * a, cb * a, a);
        }
    """.trimIndent()

    /**
     * Per-ring instance. One is held per [HomeRingGeometry] entry because uniforms are
     * per-instance state.
     */
    class Ring(val geometry: HomeRingGeometry) {
        private var shader: RuntimeShader? = null

        /**
         * Compiles the AGSL. **Not a `const` / not done in an initialiser** — a SkSL
         * compile error throws at construction, and doing that while the wallpaper service
         * is being bound would take down the wallpaper rather than degrade it. Callers
         * wrap in try/catch; see [MetrolistWallpaperService].
         */
        fun compile(): RuntimeShader =
            RuntimeShader(SHADER_SRC).also { shader = it }

        /**
         * Pushes this frame's uniforms and paints the ring, translated and clipped to its
         * own rect. Call inside a [android.graphics.Canvas.save]/[restore] pair.
         */
        fun draw(
            canvas: android.graphics.Canvas,
            paint: android.graphics.Paint,
            color: Int,
            bass: Float,
            alpha: Float,
            timeSeconds: Float,
            hotspotMult: Float,
        ) {
            val s = shader ?: return
            val g = geometry

            val left = g.x
            val top = g.y
            val right = g.x + g.width
            val bottom = g.y + g.height
            val w = g.width.toFloat()
            val h = g.height.toFloat()

            val save = canvas.save()
            canvas.clipRect(
                left - CLIP_MARGIN_PX,
                top - CLIP_MARGIN_PX,
                right + CLIP_MARGIN_PX,
                bottom + CLIP_MARGIN_PX,
            )
            // Shift fragCoord into rect-local space so `ring` can be passed as (0,0,w,h).
            canvas.translate(left.toFloat(), top.toFloat())

            s.setFloatUniform("ring", 0f, 0f, w, h)
            s.setFloatUniform("cornerRadius", g.cornerRadiusPx)
            s.setFloatUniform("ringAlpha", alpha)
            s.setFloatUniform("bass", bass)
            // The colour is a single packed int; the AGSL wants three float channels.
            s.setFloatUniform("cr", android.graphics.Color.red(color) / 255f)
            s.setFloatUniform("cg", android.graphics.Color.green(color) / 255f)
            s.setFloatUniform("cb", android.graphics.Color.blue(color) / 255f)
            // Negative direction mirrors the orbit (Q4: Claude and Nothing X counter-rotate).
            s.setFloatUniform("time", timeSeconds * g.orbitDirection)
            s.setFloatUniform("hotspotMult", if (g.hasHotspot) hotspotMult else 0f)
            // `resolution` is deliberately NOT declared. The ring maths never reads it, and a
            // SkSL uniform that is never used is dead-code-eliminated out of the compiled
            // program — after which setting it corrupts the remaining uniform slots and
            // the whole ring renders as a flat fill.

            paint.shader = s
            // The drawn area must include the margin, not just the rect interior. The
            // wallpaper renders *behind* the widgets, so a fill limited to the rect would
            // be hidden except where the widget's own rounded corners expose it —
            // producing corner brackets instead of a ring.
            canvas.drawRect(
                -CLIP_MARGIN_PX,
                -CLIP_MARGIN_PX,
                w + CLIP_MARGIN_PX,
                h + CLIP_MARGIN_PX,
                paint,
            )

            canvas.restoreToCount(save)
        }
    }
}