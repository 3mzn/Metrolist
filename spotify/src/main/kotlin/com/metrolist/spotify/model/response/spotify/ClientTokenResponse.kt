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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ClientTokenResponse(
    @SerialName("response_type")
    val responseType: String,
    @SerialName("granted_token")
    val grantedToken: GrantedToken
) {
    @Serializable
    data class GrantedToken(
        val token: String,
        @SerialName("expires_after_seconds")
        val expiresAfterSeconds: Int,
        @SerialName("refresh_after_seconds")
        val refreshAfterSeconds: Int,
        val domains: List<Domain>
    ) {
        @Serializable
        data class Domain(
            val domain: String
        )
    }
}