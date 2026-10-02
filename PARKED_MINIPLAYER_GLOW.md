# PARKED — MiniPlayer glow uses unpremultiplied alpha

**Status:** PARKED, decision deferred by user on 2026-10-03. Not a bug report — a
**deliberate deferral** of a known rendering defect, kept here so it can be revisited and
judged on-device without re-deriving anything.

**Why parked:** the current look is the approved visual reference, and the "correct"
version has not been seen yet. Fixing it blind risks making the MiniPlayer ring *worse*.

---

## 1. The defect, in plain terms

A shader outputs two things per pixel: **what colour** and **how strong**. Think of a
lamp — the colour is the bulb, the strength is the dimmer.

Android expects those **already combined**: "blue at 20%" should be handed over as a
dimmed blue, not as "blue" plus a separate note saying "20%". This is *premultiplied
alpha*.

`BorderGlowShader` returns them **separately**:

```glsl
return half4(cr, cg, cb, intensity * ringAlpha);
```

Android's blend over black is `dst = src + dst·(1−a)`. With unpremultiplied colour the
colour channels are added **at full strength** and `a` is effectively ignored. A pixel
that should be a faint hint of blue comes out as solid blue.

**Consequence:** the glow has no falloff. It is a uniform bright band, not a soft halo.

---

## 2. Where

`app/src/main/kotlin/com/metrolist/music/ui/player/BorderGlowShader.kt:80`

Shipped and working. **Not touched** — it is the visual reference the ring feature was
designed to match.

---

## 3. Why it hasn't been noticed

The MiniPlayer pill is small and the ring is thin. A fully-lit 2 px line looks like a
crisp glowing outline, which is perfectly acceptable. The missing falloff is invisible at
that size.

The same shader ported into the **home-screen wallpaper rings** is ~932 px wide, where
"no falloff" renders as a **solid slab of colour** rather than a glow. That is how the
problem was found — see `SPEC_HOME_WALLPAPER_RINGS.md` §10 Phase 3a.

---

## 4. The fix (exact)

One line. Premultiply in the shader:

```glsl
float a = clamp(intensity * ringAlpha, 0.0, 1.0);
return half4(cr * a, cg * a, cb * a, a);
```

The clamp is needed because `intensity` reaches ≈3.8 at the centreline when bass is high.

**This is already applied** to the wallpaper copy in
`app/src/main/kotlin/com/metrolist/music/wallpaper/HomeRingShader.kt`. Only the MiniPlayer
path is still on the old return.

---

## 5. How to judge it — do this when revisiting

1. Apply the one-line change above to `BorderGlowShader.kt:80`.
2. Build, install, open the MiniPlayer with music playing.
3. Compare against the wallpaper rings, which already use the corrected version.
4. Answer three questions:
   - Does the ring still read as a confident outline, or does it become **too faint**?
   - Is the **orbiting dot** more visible now, or still lost?
   - Does the ring still match the wallpaper rings, or now differ?

**The honest risk:** the saturation is *why* the MiniPlayer ring looks punchy. Correct
premultiplication makes it dimmer. It may genuinely be worse. **That is the reason this is
parked rather than fixed.**

**Expected side effect worth checking:** the orb is currently invisible when the base
glow saturates — `hotspotMult` (default `10f`) multiplies an already-clipped alpha, so it
cannot show. Premultiplying may reveal it, which could be a bonus or a surprise.

---

## 6. Reverting

Single-line revert at `BorderGlowShader.kt:80`. No state, no schema, no migration —
purely a rendering expression. Trivially reversible.

---

## 7. Related, same root cause

- **Saturation hides the orbiting dot.** `hotspotMult` scales `baseGlow` by up to ×11, but
  at high bass `intensity` already clamps to 1, so the modulation is invisible. Inherent
  to the shader's gain, not introduced by the port. If the orb is still hard to see after
  premultiplying, `hotspotMult` and the falloff constants are the knobs — but changing
  them changes the approved look.
- **Unbounded shader time.** A separate trap, already documented at `MiniPlayer.kt:466-468`
  and fixed in the wallpaper port: `cos()` loses the fractional part of a large argument,
  so time must be wrapped (~60 s) or the orbit freezes. The MiniPlayer already wraps.