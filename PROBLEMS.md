# PROBLEMS.md

Open, non-urgent problems. Parked here until prioritized. New entries go on top with the date observed.

---

## 1. Partner widget self-show mode sometimes drops cover art (FIXED in 13.9.10)

- Debug mode now owns the widget while on: heartbeat idle/live pushes suppressed, debug status written to cache, toggle-off clears + rechecks partner state. Timber breadcrumbs on every path.

- **What:** With the Storage-settings widget debug toggle on (render this phone's own playback into the Partner widget), the widget sometimes shows only the song/artist name with no cover art.
- **When:** Intermittent. The same song's art is fully loaded in the main player and the miniplayer at the time.
- **Expected:** Widget art matches what the player shows, every time.
- **Suspects (unverified):** `MusicService.updateWidgetUI` debug-test branch hands `song.thumbnailUrl` to `PartnerWidgetManager.updateFromStatus`; the widget loads art itself via Coil 300x300 (`loadCoverSquare`) with a URL-keyed single-entry cache — a null URL at broadcast time, a failed Coil fetch with no retry, or a stale cache entry would all produce exactly this symptom.
- **Files:** `playback/MusicService.kt` (debug-test branch), `widget/PartnerWidgetManager.kt` (`loadCoverSquare`, art cache).

## 2. Miniplayer ring visualizer sometimes falls back to white (FIXED in 13.9.10)

- Root cause was the palette extractor hitting the raw URL with no fallback while display recovered via 544. Fixed: raw + 544 fallback ported to big cover, background blur, queue art, and both palette extractors (ring + wash/gradient).

- **What:** Since the miniplayer cover-art fix (`fa863ac09`, raw URL + 544 fallback), some songs load art fine in the miniplayer but the bass-reactive ring keeps the default white instead of taking the art's palette.
- **When:** Intermittent, per-song. Not every song; art itself always ends up loading.
- **Expected:** Ring color follows the displayed art whenever art is visible.
- **Suspects (unverified):** Palette extraction races the art load — if the palette is sampled from the failed first attempt (or before the fallback 544 resolves), the fallback path repaints the art but never re-runs extraction, leaving the white default.
- **Files:** `ui/player/MiniPlayer.kt`, `ui/player/BorderGlowShader.kt`, `ui/player/CoverBassPulse.kt`.
