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
package com.metrolist.spotify.auth

import com.metrolist.spotify.SpotifyClient
import com.metrolist.spotify.model.response.spotify.PersonalTokenResponse
import io.ktor.client.call.body
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Handles advanced Spotify authentication with TOTP
 */
class SpotifyAuth(
    private val spotifyClient: SpotifyClient,
) {
    var totpSecret: Pair<Int, List<Int>>? = null

    suspend fun getTotpSecret(): Result<Boolean> =
        runCatching {
            val response = spotifyClient.getSpotifyLastestTotpSecret().body<Map<String, List<Int>>>()
            if (response.isNotEmpty()) {
                val latestDict = response.entries.last()
                val firstKey = latestDict.key.toInt()
                totpSecret = Pair(
                    firstKey,
                    latestDict.value
                )
                return@runCatching true
            } else false
        }

    /**
     * Refresh Spotify token using the new TOTP-based authentication
     * This is more reliable than the standard token method
     */
    suspend fun refreshToken(spDc: String): Result<PersonalTokenResponse> =
        runCatching {
            if (totpSecret == null) {
                // Fetch the latest TOTP secret if not already fetched
                getTotpSecret().onSuccess {
                    println("Fetched TOTP secret successfully: $totpSecret")
                }.onFailure {
                    println("Failed to fetch TOTP secret: ${it.message}")
                }
            }
            // Get server time from Spotify
            val serverTimeResponse = spotifyClient.getSpotifyServerTime(spDc)
            val serverTimeJson = Json.parseToJsonElement(serverTimeResponse.body<String>()).jsonObject
            val serverTime =
                serverTimeJson["serverTime"]?.jsonPrimitive?.longOrNull
                    ?: throw Exception("Failed to get server time")
            println("Server time: $serverTime")

            // Generate TOTP secret and OTP value
            val otpValue = SpotifyTotp.at(serverTime * 1000L, totpSecret ?: SpotifyTotp.TOTP_SECRET_V22)
            println("Generated OTP: $otpValue")

            val sTime = "$serverTime"
            val cTime = "$serverTime"

            // First try with transport mode
            var response =
                try {
                    spotifyClient.getSpotifyAccessToken(
                        spdc = spDc,
                        otpValue = otpValue,
                        reason = "transport",
                        sTime = sTime,
                        cTime = cTime,
                        totpVersion = totpSecret?.first ?: SpotifyTotp.TOTP_SECRET_V22.first,
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }

            var tokenData =
                try {
                    response?.body<PersonalTokenResponse>()
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }

            // Check if token is valid (should be 374 characters)
            if (tokenData?.accessToken?.length != 374) {
                // Retry with init mode
                response =
                    spotifyClient.getSpotifyAccessToken(
                        spdc = spDc,
                        otpValue = otpValue,
                        reason = "init",
                        sTime = sTime,
                        cTime = cTime,
                        totpVersion = totpSecret?.first ?: SpotifyTotp.TOTP_SECRET_V22.first,
                    )
                tokenData = response.body<PersonalTokenResponse>()
            }

            // Validate token
            if (tokenData.accessToken.isEmpty()) {
                throw Exception("Unsuccessful token request")
            }

            tokenData
        }
}