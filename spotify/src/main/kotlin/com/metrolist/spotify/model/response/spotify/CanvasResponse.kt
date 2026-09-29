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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * ---------------------------------------------------------------------------
 * Ported from SimpMusic, core/service/spotify (GPL-3.0), with only these
 * mechanical changes: package and import rewriting to Metrolist's namespace, and
 * collapsing the KMP expect/ctual TOTP split into a single Android file.
 *
 * DO NOT REWRITE THE LOGIC. It is working, device-verified code. The @ProtoNumber
 * annotations in particular MUST be preserved verbatim: Spotify is spoken to in
 * protobuf, and dropping them corrupts field mapping silently rather than
 * failing to compile. See SPEC_SPOTIFY_CANVAS.md 4.2.
 * ---------------------------------------------------------------------------
 */
package com.metrolist.spotify.model.response.spotify

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class CanvasResponse(
    @ProtoNumber(1)
    val canvases: List<Canvas>,
) {
    @Serializable
    data class Canvas(
        @ProtoNumber(1)
        val id: String,
        @ProtoNumber(2)
        val canvas_url: String,
        @ProtoNumber(5)
        val track_uri: String,
        @ProtoNumber(6)
        val artist: Artist,
        @ProtoNumber(9)
        val other_id: String? = null,
        @ProtoNumber(11)
        val canvas_uri: String,
        @ProtoNumber(13)
        val thumbsOfCanva: List<ThumbOfCanva>? = null,
    ) {
        @Serializable
        data class Artist(
            @ProtoNumber(1)
            val artist_uri: String,
            @ProtoNumber(2)
            val artist_name: String,
            @ProtoNumber(3)
            val artist_img_url: String,
        )

        @Serializable
        data class ThumbOfCanva(
            @ProtoNumber(1)
            val height: Int? = null,
            @ProtoNumber(2)
            val width: Int? = null,
            @ProtoNumber(3)
            val url: String? = null,
        )
    }
}

// message CanvasResponse {
//  message Canvas {
//    string id = 1;                // ef3bc2ac86ba4a39b2cddff19dca884a
//    string canvas_url = 2;        // https://canvaz.scdn.co/upload/artist/6i1GVNJCyyssRwXmnaeEFH/video/ef3bc2ac86ba4a39b2cddff19dca884a.cnvs.mp4
//    string track_uri = 5;         // spotify:track:5osCClSjGplWagDsJmyivf
//    message Artist {
//      string artist_uri = 1;      // spotify:artist:3E61SnNA9oqKP7hI0K3vZv
//      string artist_name = 2;     // CALVO
//      string artist_img_url = 3;  // https://i.scdn.co/image/2d7b0ebe1e06c74f5c6b9a2384d746673051241d
//    }
//    Artist artist = 6;
//    string other_id = 9;          // 957a9be5e5c1b9ef1ac1c96b7cebf396
//    string canvas_uri = 11;       // spotify:canvas:1OuybAWK7XOQMG725ZtFwG
//  }
//  repeated Canvas canvases = 1;
// }