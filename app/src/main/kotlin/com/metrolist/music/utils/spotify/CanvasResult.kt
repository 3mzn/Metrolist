/*
 * Spotify Canvas support in Metrolist.
 *
 * Copyright (C) 2025 maxrave-dev (SimpMusic)
 * Copyright (C) 2025 The Metrolist Group
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * ---------------------------------------------------------------------------
 * Ported from SimpMusic's `mapping/Mapping.kt` (`CanvasResponse.toCanvasResult`,
 * lines 429-450) and its `CanvasResult` model, both GPL-3.0. Copied as-is apart
 * from package and import rewriting.
 *
 * DO NOT REWRITE THE LOGIC. See SPEC_SPOTIFY_CANVAS.md 4.2. In particular the
 * thumbnail choice — largest by (height + width), falling back to the first — is
 * upstream's choice and is not a mistake to be tidied.
 * ---------------------------------------------------------------------------
 */

package com.metrolist.music.utils.spotify

import com.metrolist.spotify.model.response.spotify.CanvasResponse
import kotlinx.serialization.Serializable

/**
 * Ported from SimpMusic's `CanvasResult`.
 *
 * @param isVideo `true` for the animated `.mp4` form, `false` for a still image.
 */
@Serializable
data class CanvasResult(
    val isVideo: Boolean,
    val canvasUrl: String,
    val canvasThumbUrl: String? = null,
)

/**
 * Ported verbatim from SimpMusic's `CanvasResponse.toCanvasResult()`.
 *
 * Returns null when Spotify sent no canvas at all, which is the "clean negative" the
 * caching policy distinguishes from a transport failure — see spec 9.2.
 */
internal fun CanvasResponse.toCanvasResult(): CanvasResult? {
    val canvasUrl = this.canvases.firstOrNull()?.canvas_url ?: return null
    val canvasThumbs = this.canvases.firstOrNull()?.thumbsOfCanva
    val thumbUrl =
        if (!canvasThumbs.isNullOrEmpty()) {
            (
                canvasThumbs.let { thumb ->
                    thumb
                        .maxByOrNull {
                            (it.height ?: 0) + (it.width ?: 0)
                        }?.url
                } ?: canvasThumbs.first().url
            )
        } else {
            null
        }
    return CanvasResult(
        isVideo = canvasUrl.contains(".mp4"),
        canvasUrl = canvasUrl,
        canvasThumbUrl = thumbUrl,
    )
}
