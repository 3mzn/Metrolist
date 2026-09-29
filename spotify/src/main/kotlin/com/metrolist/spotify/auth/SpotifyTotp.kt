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

import dev.turingcomplete.kotlinonetimepassword.GoogleAuthenticator
import io.ktor.utils.io.core.*
import java.util.Date
import kotlin.io.encoding.Base64

/**
 * Implementation of Time-based One-Time Password for Spotify authentication
 * Logic from https://github.com/misiektoja/spotify_monitor/blob/main/debug/spotify_monitor_totp_test.py
 */
object SpotifyTotp {
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    val TOTP_SECRET_V22 = 22 to listOf(99,101,119,123,69,120,91,123,97,74,53,48,76,102,55,69,110,54)

    /**
     * Generate a TOTP value for the given timestamp
     */
    fun at(timestamp: Long, totpSecret: Pair<Int, List<Int>>?): String = generate(timestamp, totpSecret)

    private fun generate(timestamp: Long, totpSecret: Pair<Int, List<Int>>?): String {
        val secret = totpSecret?.let { generateSecret(it) } ?: generateSecret(TOTP_SECRET_V22)
        return generateTotp(secret, timestamp)
    }

    private fun generateSecret(totpSecret: Pair<Int, List<Int>>): String {

        val secretCipherBytes = totpSecret.second
        println("TOTP cipher: $secretCipherBytes")

        // Transform bytes: e ^ ((t % 33) + 9) for each byte
        val transformed = secretCipherBytes.mapIndexed { index, byte ->
            byte xor ((index % 33) + 9)
        }

        // Join numbers as string and convert to hex
        val joined = transformed.joinToString("")
        val hexStr = joined.toByteArray().toHexString()

        // Convert to base32 secret (without padding)
        val secret = base64ToBase32(Base64.encode(hexStr.hexToByteArray()))
            .trimEnd('=')

        println("Computed secret: $secret")
        return secret
    }

    private fun base64ToBase32(base64: String): String {
        val bytes = Base64.decode(base64)
        return base32Encode(bytes)
    }

    private fun base32Encode(data: ByteArray): String {
        if (data.isEmpty()) return ""

        val result = StringBuilder()
        var bits = 0
        var value = 0

        for (byte in data) {
            value = (value shl 8) or (byte.toInt() and 0xFF)
            bits += 8

            while (bits >= 5) {
                result.append(BASE32_ALPHABET[(value shr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }

        if (bits > 0) {
            result.append(BASE32_ALPHABET[(value shl (5 - bits)) and 0x1F])
        }

        // Add padding
        while (result.length % 8 != 0) {
            result.append('=')
        }

        return result.toString()
    }
}

/**
 * Android implementation, ported from SimpMusic's `androidMain` actual.
 *
 * SimpMusic declared this as `expect` and supplied a separate `actual` per platform
 * (android/jvm/ios). Metrolist is Android-only, so the declaration and the Android
 * actual are collapsed into one function here. The body is unchanged.
 */
fun generateTotp(secret: String, timestamp: Long): String {
    val googleAuthenticator = GoogleAuthenticator(secret.toByteArray())
    return googleAuthenticator.generate(timestamp = Date(timestamp))
}