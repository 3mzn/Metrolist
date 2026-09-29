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

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
@ExperimentalSerializationApi
data class TokenResponse(
    @ProtoNumber(1)
    @SerialName("response_type")
    val responseType: String?,
    @ProtoNumber(2)
    @SerialName("granted_token")
    val grantedToken: GrantedToken?,
) {
    @Serializable
    data class GrantedToken(
        @ProtoNumber(1)
        val token: String?,
        @ProtoNumber(2)
        @SerialName("expires_after_seconds")
        val expiresAfterSeconds: Int?,
        @ProtoNumber(3)
        @SerialName("refresh_after_seconds")
        val refreshAfterSeconds: Int?,
        @ProtoNumber(4)
        val domains: List<Domain>?,
    ) {
        @Serializable
        data class Domain(
            @ProtoNumber(1)
            val domain: String?,
        )
    }
}
/*
{
    "response_type": "RESPONSE_GRANTED_TOKEN_RESPONSE",
    "granted_token": {
        "token": "AAD1YNe8Mp081SMIQt1mlxBOLekd/hhng6ihNEsZ2Qi+cdSzR7LZ49mT7ODe1G+gbfx4TOcohfHXmCPJPmshEBV/dol8AkiLXnEv0bcrN+kp22Ul6HXAE6G0TgqqHX+FFBJXF//7YRAxwT+Q1zWwDtPBJ/ZFTRcQgl7cVn+xrI4f48rAArEVnH66R3jVip1/a2RqlDMJxw0XBnDKnY8X5qbjFFUocbl7AHo0hV2CZANCobv5g3mWQfYSDqc6brVsogTRTZpoX5PCPl7C1HGrcxLD/IEoDlls7pTCn9FuK/47hZggrYmRiS3+ppAC7ahkHZ4ahm5N3xbieSkU",
        "expires_after_seconds": 1216800,
        "refresh_after_seconds": 1209600,
        "domains": [
            {
                "domain": "spotify.com"
            },
            {
                "domain": "spotify.net"
            }
        ]
    }
}
 */