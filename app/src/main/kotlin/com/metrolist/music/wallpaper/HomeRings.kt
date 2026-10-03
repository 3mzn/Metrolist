/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wallpaper

/**
 * One ring's placement on the home screen.
 *
 * @param x,y top-left of the widget's **visible** edge, in wallpaper-surface pixels.
 *   The surface is 1080×2400 and maps 1:1 to the screen (verified — see
 *   `SPEC_HOME_WALLPAPER_RINGS.md` §5.3), so these are plain screen pixels.
 * @param cornerRadiusPx measured from the rendered widget, not guessed. See §5.2a.
 * @param orbitDirection `+1` or `-1`; negative mirrors the orbiting hotspot.
 * @param hasHotspot `false` for the rings that must not carry an orbiting dot.
 */
data class HomeRingGeometry(
    val id: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val cornerRadiusPx: Float,
    val hasHotspot: Boolean,
    val orbitDirection: Float,
) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}

/**
 * Measured home-screen geometry for the ring visualizer.
 *
 * **All values below were measured on the device, not designed.** Bounds come from
 * `uiautomator dump` and were pixel-verified against a screenshot: the luminance
 * transition between wallpaper and widget lands on exactly the predicted row/column.
 *
 * Corner radii were measured two independent ways, which agreed:
 * - **Edge walk** — follow a widget's left edge down until it reaches solid colour; the
 *   offset at which the straight edge begins is the radius.
 * - **Corner diagonal** — from the bounding-box corner `(left, top)`, step inward along
 *   the diagonal until the widget's own pixels begin. For a rounded rect the arc sits at
 *   `s = R·(1 − 1/√2) = 0.2929·R`, so `R = s / 0.2929`. Calibrated against Nothing X,
 *   whose edge-walk radius is 56: the diagonal returned **56.3**. ✅
 *
 * The partner widget was first estimated at 40 and was visibly wrong — the ring cut
 * inside the corners. The diagonal measurement gives **66**, which is what ships.
 *
 * Measured on: Redmi Note 14, 1080×2400, density 450, HyperOS 3, Android 16 (SDK 36).
 *
 * ⚠️ **These are specific to one home-screen layout.** If widgets are moved or resized the
 * rings will not follow. `SPEC_HOME_WALLPAPER_RINGS.md` §10 Phase 5 covers the optional
 * calibration screen for that.
 *
 * Note these are the **visible** widget bounds, not the grid cell. MIUI reserves
 * transparent padding around each widget (0–38 px depending on the widget), and the ring
 * must hug the part the user can actually see. `HOST_FRAME` keeps the cell bounds for the
 * alternative "ring at the cell edge" placement.
 */
object HomeRings {

    /** Widget bounds as the wallpaper should draw them (visible edges). */
    val RINGS: List<HomeRingGeometry> = listOf(
        // 4×2 partner widget, top. Ring pulses, no orbiting dot.
        HomeRingGeometry(
            id = "partner",
            x = 74, y = 638, width = 932, height = 466,
            cornerRadiusPx = 66f,
            hasHotspot = false,
            orbitDirection = 1f,
        ),
        // 2×2 Nothing X earbud widget, lower left. Orbiting dot, counter-clockwise.
        HomeRingGeometry(
            id = "nothing",
            x = 78, y = 1173, width = 416, height = 416,
            cornerRadiusPx = 56f,
            hasHotspot = true,
            orbitDirection = 1f,
        ),
        // 2×2 Claude widget, lower right. Orbiting dot, opposite to Nothing X.
        // Geometry matches the Nothing X 2×2 exactly (416×416, R=56): the corner leak was
        // the 10px radius error, which reads at the corners and not along the straight
        // edges, whereas the 3px size error reads everywhere.
        HomeRingGeometry(
            id = "claude",
            x = 584, y = 1171, width = 416, height = 416,
            cornerRadiusPx = 56f,
            hasHotspot = true,
            orbitDirection = -1f,
        ),
        // 4×1 Brave search widget, bottom. Ring pulses, no orbiting dot.
        HomeRingGeometry(
            id = "brave",
            x = 74, y = 1696, width = 932, height = 135,
            cornerRadiusPx = 18f,
            hasHotspot = false,
            orbitDirection = 1f,
        ),
    )

    /**
     * Grid-cell bounds, kept for the alternative placement the spec offers. MIUI's
     * padding means a cell-edge ring floats 0–38 px outside the visible widget.
     */
    val HOST_FRAME: Map<String, List<Int>> = mapOf(
        "partner" to listOf(74, 638, 932, 466),
        "nothing" to listOf(74, 1148, 424, 466),
        "claude" to listOf(582, 1148, 424, 466),
        "brave" to listOf(74, 1658, 932, 211),
    )
}