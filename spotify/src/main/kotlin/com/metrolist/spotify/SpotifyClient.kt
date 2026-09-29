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

import com.metrolist.spotify.curl.CurlLogger
import com.metrolist.spotify.encoding.brotli
import com.metrolist.spotify.model.body.CanvasBody
import com.metrolist.spotify.model.body.SpotifyClientBody
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.userAgent
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.serialization.kotlinx.protobuf.protobuf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.random.Random

class SpotifyClient {
    private var spotifyClient = createSpotifyClient()
    private var jsonClient =
        createSpotifyClient(
            onlyJson = true,
        )

    var proxy: ProxyConfig? = null
        set(value) {
            if (field == value) return
            field = value
            spotifyClient.close()
            spotifyClient = createSpotifyClient()
        }

    @OptIn(ExperimentalSerializationApi::class)
    private fun createSpotifyClient(onlyJson: Boolean = false) =
        // SimpMusic called `getEngine()`, a KMP `expect` that resolved to OkHttp on Android.
        // Metrolist is Android-only, so it is spelled out directly. This matches :innertube.
        HttpClient(OkHttp) {
            followRedirects = true
            expectSuccess = false
            install(HttpCache)
            install(Logging) {
                logger = Logger.DEFAULT
                // Metrolist change, not a port of SimpMusic. SimpMusic runs LogLevel.ALL
                // unconditionally, which prints the sp_dc cookie, the personal
                // "Authorization: Bearer" token and the Client-Token in full. Its logs go
                // through println, so Metrolist's ProGuard rule that strips android.util.Log
                // cannot remove them and they would survive into release builds. Nothing is
                // calling this client yet; the verbosity is only worth it while wiring it up.
                level = if (BuildConfig.DEBUG) LogLevel.ALL else LogLevel.NONE
            }
            install(CurlLogger) {
                logger = { Logger.DEFAULT.log(it) }
                // Metrolist change. The plugin already supports redaction and prints
                // "<redacted>"; SimpMusic never sets it, so its curl output leaks the same
                // three secrets. See SPEC_SPOTIFY_CANVAS.md 14.1b.
                redactHeaders = setOf("Cookie", "Authorization", "Client-Token")
            }
            install(HttpSend) {
                maxSendCount = 100
            }
            install(HttpCookies) {
                storage = AcceptAllCookiesStorage()
            }
            install(ContentNegotiation) {
                if (!onlyJson) {
                    register(
                        ContentType.Text.Plain,
                        KotlinxSerializationConverter(
                            Json {
                                prettyPrint = true
                                isLenient = true
                                ignoreUnknownKeys = true
                                explicitNulls = false
                                encodeDefaults = true
                            },
                        ),
                    )
                    protobuf(
                        ProtoBuf {
                            encodeDefaults = true
                        },
                    )
                }
                json(
                    Json {
                        prettyPrint = true
                        isLenient = true
                        ignoreUnknownKeys = true
                        explicitNulls = false
                        encodeDefaults = true
                    },
                )
            }
            install(ContentEncoding) {
                brotli(1.0F)
                gzip(0.9F)
                deflate(0.8F)
            }
            defaultRequest {
                url("https://api.spotify.com")
            }
            if (proxy != null) {
                engine {
                    proxy = this@SpotifyClient.proxy
                }
            }
        }

    /**
     * Thanks to @Thereallo1026 and @misiektoja for providing the latest TOTP secret
     */
    suspend fun getSpotifyLastestTotpSecret() =
        spotifyClient.get("https://raw.githubusercontent.com/xyloflake/spot-secrets-go/refs/heads/main/secrets/secretDict.json") {
            userAgent(USER_AGENT)
            contentType(ContentType.Application.Json)
        }

    suspend fun getSpotifyServerTime(spdc: String) =
        spotifyClient.get("https://open.spotify.com/api/server-time") {
            userAgent(USER_AGENT)
            header(
                "Cookie",
                "sp_dc=$spdc",
            )
            header("App-platform", "WebPlayer")
            header("Spotify-App-Version", "1.2.61.20.g3b4cd5b2")
            header("Accept", "application/json")
            header("Origin", "https://open.spotify.com")
            header("Referer", "https://open.spotify.com/")
        }

    /**
     * NOTE (Metrolist): [sTime] and [cTime] are accepted but never put on the request, because
     * SimpMusic's original does the same. This is dead weight, not a porting mistake — Spotify
     * has not required them. Left as-is deliberately; see SPEC_SPOTIFY_CANVAS.md 4.2.
     */
    suspend fun getSpotifyAccessToken(
        spdc: String,
        otpValue: String,
        reason: String = "init",
        sTime: String,
        cTime: String,
        totpVersion: Int,
    ) = spotifyClient.get("https://open.spotify.com/api/token") {
        userAgent(USER_AGENT)
        contentType(ContentType.Application.Json)
        header(
            "Cookie",
            "sp_dc=$spdc",
        )
        parameter("reason", reason)
        parameter("productType", "mobile-web-player")
        parameter("totp", otpValue)
        parameter("totpServer", otpValue)
        parameter("totpVer", totpVersion)
        header("Cookie", "sp_dc=$spdc")
        header("Accept", "application/json")
        header("Origin", "https://open.spotify.com")
        header("Referer", "https://open.spotify.com/")
    }

    suspend fun getSpotifyLyricsToken(spdc: String) =
        spotifyClient.get("https://open.spotify.com/get_access_token?reason=transport&productType=web_player") {
            userAgent(USER_AGENT)
            contentType(ContentType.Application.Json)
            header("Cookie", "sp_dc=$spdc")
        }

    suspend fun getSpotifyLyrics(
        token: String,
        clientToken: String,
        trackId: String,
    ) = spotifyClient.get("https://spclient.wg.spotify.com/color-lyrics/v2/track/$trackId?format=json&vocalRemoval=false&market=from_token") {
        userAgent(USER_AGENT)
        contentType(ContentType.Application.Json)
        header("Authorization", "Bearer $token")
        header("Client-Token", clientToken)
        header("App-platform", "WebPlayer")
    }

    suspend fun searchSpotifyTrack(
        q: String,
        authToken: String,
        clientToken: String,
    ) = spotifyClient.get("https://api-partner.spotify.com/pathfinder/v1/query?operationName=searchTracks") {
        userAgent(USER_AGENT)
        contentType(ContentType.Application.Json)
        header("Authorization", "Bearer $authToken")
        header("Client-Token", clientToken)
        header(
            HttpHeaders
                .AcceptEncoding,
            "gzip, deflate, br",
        )
        val variable =
            "{\"searchTerm\":\"${q}\",\"offset\":0,\"limit\":3,\"numberOfTopResults\":3,\"includeAudiobooks\":true,\"includePreReleases\":false}"
        val sha = "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428"
        parameter(
            "variables",
            variable,
        )
        parameter(
            "extensions",
            "{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"${sha}\"}}",
        )
    }
    // {"searchTerm":"trình+hieuthuhai","offset":0,"limit":20,"numberOfTopResults":20,"includeAudiobooks":true,"includePreReleases":false}
    // {"persistedQuery":{"version":1,"sha256Hash":"e4ed1f91a2cc5415befedb85acf8671dc1a4bf3ca1a5b945a6386101a22e28a6"}}

    suspend fun getSpotifyCanvas(
        trackId: String,
        token: String,
        clientToken: String,
    ) = spotifyClient.post("https://spclient.wg.spotify.com/canvaz-cache/v0/canvases") {
        headers {
            append(HttpHeaders.Accept, "application/protobuf")
            append(HttpHeaders.ContentType, "application/protobuf")
            append(
                HttpHeaders
                    .AcceptEncoding,
                "gzip, deflate, br",
            )
            append(HttpHeaders.Authorization, "Bearer $token")
            append("Client-Token", clientToken)
            append(HttpHeaders.UserAgent, "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)")
        }
        setBody(
            CanvasBody(
                tracks =
                    listOf(
                        CanvasBody.Track(
                            track_uri = "spotify:track:$trackId",
                        ),
                    ),
            ),
        )
    }

    suspend fun getSpotifyClientToken() =
        jsonClient.post("https://clienttoken.spotify.com/v1/clienttoken") {
            headers {
                append(HttpHeaders.Accept, "application/json")
                append(HttpHeaders.ContentType, "application/json")
                append(HttpHeaders.AcceptEncoding, "gzip, deflate, br")
                append(
                    HttpHeaders.UserAgent,
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36 Edg/135.0.0.0",
                )
            }
            setBody(
                SpotifyClientBody(
                    client_data = SpotifyClientBody.ClientData(),
                ),
            )
        }

    fun getRandomUserAgent(): String {
        val macOSVersion = "${Random.nextInt(11, 15)}_${Random.nextInt(4, 9)}"
        val webKitVersion = "${Random.nextInt(530, 537)}.${Random.nextInt(30, 37)}"
        val chromeVersion = "${Random.nextInt(80, 105)}.0.${Random.nextInt(3000, 4500)}.${Random.nextInt(60, 125)}"
        val safariVersion = "${Random.nextInt(530, 537)}.${Random.nextInt(30, 36)}"

        return "Mozilla/5.0 (Macintosh; Intel Mac OS X $macOSVersion) AppleWebKit/$webKitVersion (KHTML, like Gecko) Chrome/$chromeVersion Safari/$safariVersion"
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.157 Safari/537.36"
    }
}