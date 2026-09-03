# VIZ_IDEAS.md — Mini Player Visualizer Concepts

> All ideas use Android's built-in `android.media.audiofx.Visualizer` for real-time
> FFT from the audio session. No pre-computed sidecars, no download-time generation,
> ~50 lines of code, works for both downloads and streams.

---

## Idea 1: Bass-Reactive Border

**What:** The mini player pill border glows brighter on bass hits. The border color
comes from the song's album art palette (extracted via Coil + Palette).

**Behavior:**
- Idle: subtle glow at `BASE_GLOW` (0.35)
- Sustained bass: glow proportional to bass energy
- Kick transients: bright flash that fades over ~200ms

**Visual:** A neon-like border that breathes with the beat. Album art colors make
it feel connected to the song.

**Technical:** Visualizer → 20-100Hz band RMS → glow alpha. One draw call per frame.

---

## Idea 2: Bass Pulse Album Art

**What:** The album art in the mini player scales up slightly on each bass kick,
then springs back. Think "living album art."

**Behavior:**
- Idle: normal scale (1.0)
- Bass kick: scale to 1.08-1.15 based on bass energy
- Spring animation back to 1.0 using `animateSplineDecay`

**Visual:** The album art feels alive, pulsing with the music. Subtle but satisfying.

**Technical:** Visualizer → bass energy → scale state. Compose animation handles the spring back.

---

## Idea 3: Bass Ring Pulse

**What:** Concentric rings emanate from the mini player pill on each bass hit.
Rings expand outward and fade, colored from the album art palette.

**Behavior:**
- Bass kick spawns a new ring at the pill border
- Ring expands to ~2x pill size over ~400ms
- Ring alpha fades from 0.8 to 0 as it expands
- Multiple rings can overlap for busy sections

**Visual:** Like ripples in water on each kick. Colored rings make it feel premium.

**Technical:** Visualizer → bass kick detection → spawn ring. Each ring is an animated
`drawCircle` with expanding radius + decaying alpha. Ring list managed in a
`mutableStateListOf`.

---

## Why These Are Better Than Pre-Computed

| | Pre-computed (old) | Real-time Visualizer |
|---|---|---|
| Lines of code | 600+ | ~50 per idea |
| Playback CPU | ~0% | ~3-5% |
| Works for streams | No | Yes |
| Sync | Can drift | Always perfect |
| Storage | ~50KB/song | None |
| Battery (idle) | None | None (only runs while playing) |

---

*Added 2026-09-01*
