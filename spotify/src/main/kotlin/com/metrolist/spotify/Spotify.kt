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
package com.metrolist.spotify

import com.metrolist.spotify.auth.SpotifyAuth
import com.metrolist.spotify.model.response.spotify.CanvasResponse
import com.metrolist.spotify.model.response.spotify.ClientTokenResponse
import com.metrolist.spotify.model.response.spotify.PersonalTokenResponse
import com.metrolist.spotify.model.response.spotify.SpotifyLyricsResponse
import com.metrolist.spotify.model.response.spotify.search.SpotifySearchResponse
import io.ktor.client.call.body
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.http
import io.ktor.http.isSuccess

class Spotify {
    private val spotifyClient = SpotifyClient()
    private val spotifyAuth = SpotifyAuth(spotifyClient)

    /**
     * Remove proxy for client
     */
    fun removeProxy() {
        spotifyClient.proxy = null
    }

    /**
     * Set the proxy for client
     */
    fun setProxy(
        isHttp: Boolean,
        host: String,
        port: Int,
    ) {
        val verifiedHost =
            if (!host.contains("http")) {
                "http://$host"
            } else {
                host
            }
        runCatching {
            if (isHttp) ProxyBuilder.http("$verifiedHost:$port") else ProxyBuilder.socks(verifiedHost, port)
        }.onSuccess {
            spotifyClient.proxy = it
        }.onFailure {
            it.printStackTrace()
        }
    }

    /**
     * Get personal token using the standard method
     */
    suspend fun getPersonalToken(spdc: String) =
        runCatching {
            spotifyClient.getSpotifyLyricsToken(spdc).body<PersonalTokenResponse>()
        }

    /**
     * Get personal token using the more reliable TOTP-based method
     * This should be used when the standard method fails
     */
    suspend fun getPersonalTokenWithTotp(spdc: String) = spotifyAuth.refreshToken(spdc)

    suspend fun getClientToken() =
        runCatching {
            spotifyClient
                .getSpotifyClientToken()
                .body<ClientTokenResponse>()
        }

    suspend fun searchSpotifyTrack(
        query: String,
        authToken: String,
        clientToken: String,
    ) = runCatching {
        spotifyClient
            .searchSpotifyTrack(query, authToken, clientToken)
            .body<SpotifySearchResponse>()
    }

    suspend fun getSpotifyLyrics(
        trackId: String,
        token: String,
        clientToken: String,
    ) = runCatching {
        spotifyClient
            .getSpotifyLyrics(
                token = token,
                clientToken = clientToken,
                trackId,
            ).body<SpotifyLyricsResponse>()
    }

    suspend fun getSpotifyCanvas(
        trackId: String,
        token: String,
        clientToken: String,
    ): Result<CanvasResponse> = runCatching {
        val response = spotifyClient.getSpotifyCanvas(trackId, token, clientToken)
        // `expectSuccess = false` (SpotifyClient.kt), so a 401/403 or 5xx arrives here as an
        // ordinary response whose body is Spotify's protobuf *error*, not a CanvasResponse.
        // Decoding it blind cannot tell "this request failed" apart from "this track has no
        // Canvas", and for Canvas that difference is not cosmetic: a misread error becomes a
        // cached "no Canvas" verdict that outlives the outage which caused it.
        //
        // This is the only deliberate deviation from the SimpMusic port, and it changes nothing
        // on a successful fetch. See SPEC_SPOTIFY_CANVAS.md 14.1l.
        if (!response.status.isSuccess()) throw SpotifyHttpException(response.status.value)
        response.body<CanvasResponse>()
    }
}

/**
 * A non-2xx response from Spotify, carrying the status code.
 *
 * Metrolist addition - SimpMusic has no equivalent. It exists solely because the client is
 * configured with `expectSuccess = false`, so an error would otherwise be silently decoded as if
 * it were a valid payload.
 *
 * @param status the HTTP status code
 */
class SpotifyHttpException(val status: Int) : Exception("Spotify returned HTTP $status")