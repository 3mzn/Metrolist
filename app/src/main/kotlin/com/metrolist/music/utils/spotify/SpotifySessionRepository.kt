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
 * Ported from SimpMusic's `LogInViewModel.saveSpotifySpdc` / `setFullSpotifyCookies`
 * and the token-expiry handling in `LyricsCanvasRepositoryImpl.getCanvas`
 * (lines 134-179), both GPL-3.0.
 *
 * DO NOT REWRITE THE AUTH LOGIC. The TOTP flow, the retry-with-"init" behaviour
 * and the 374-character check all live in :spotify and are load-bearing. This
 * file is only the app-side state holder around them.
 * ---------------------------------------------------------------------------
 */

package com.metrolist.music.utils.spotify

import android.content.Context
import com.metrolist.music.constants.SpotifyClientTokenExpiresKey
import com.metrolist.music.constants.SpotifyClientTokenKey
import com.metrolist.music.constants.SpotifyPersonalTokenExpiresKey
import com.metrolist.music.constants.SpotifyPersonalTokenKey
import com.metrolist.music.constants.SpotifySpdcKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.model.response.spotify.ClientTokenResponse
import com.metrolist.spotify.model.response.spotify.PersonalTokenResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the Spotify session: the `sp_dc` cookie captured from the WebView login, and the two
 * tokens derived from it.
 *
 * Token lifetime is short (a personal token measured ~4 hours on 2026-09-30), so
 * [validTokens] re-mints on demand rather than trusting whatever is on disk. SimpMusic
 * checks the stored expiry first and only then fetches; that order is preserved.
 */
@Singleton
class SpotifySessionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val spdc: Flow<String> = context.dataStore.data.map { it[SpotifySpdcKey] ?: "" }

    val isLoggedIn: Flow<Boolean> = spdc.map { it.isNotBlank() }

    /** Milliseconds epoch, or 0 when never stored. */
    private val personalTokenExpires: Flow<Long> =
        context.dataStore.data.map { it[SpotifyPersonalTokenExpiresKey] ?: 0L }

    private val clientTokenExpires: Flow<Long> =
        context.dataStore.data.map { it[SpotifyClientTokenExpiresKey] ?: 0L }

    /**
     * Stores the `sp_dc` value out of a raw Cookie header.
     *
     * The header looks like `sp_dc=<value>; other=x`, so it is split and the single key picked
     * out. Ported from `LogInViewModel.saveSpotifySpdc`, which does the same split/associate
     * dance; the intent is identical.
     */
    suspend fun saveSpdc(cookieHeader: String) {
        val value =
            cookieHeader
                .split("; ")
                .filter { it.isNotEmpty() }
                .firstNotNullOfOrNull { part ->
                    val key = part.substringBefore('=', missingDelimiterValue = "")
                    if (key == "sp_dc") part.substringAfter('=', missingDelimiterValue = "") else null
                }
                .orEmpty()
        context.safeDataStoreEdit { it[SpotifySpdcKey] = value }
    }

    /** Signing out clears the cookie and both tokens, so no stale credential outlives it. */
    suspend fun logout() {
        context.safeDataStoreEdit {
            it[SpotifySpdcKey] = ""
            it[SpotifyPersonalTokenKey] = ""
            it[SpotifyPersonalTokenExpiresKey] = 0L
            it[SpotifyClientTokenKey] = ""
            it[SpotifyClientTokenExpiresKey] = 0L
        }
    }

    /**
     * Returns a personal and client token pair, re-minting only what has expired.
     *
     * Returns null when there is no cookie, or when Spotify refuses. Callers treat null as
     * "not logged in" and fall back to the normal player background — a Canvas failure must
     * never interrupt playback.
     */
    suspend fun validTokens(now: Long = System.currentTimeMillis()): SpotifyTokens? =
        withContext(Dispatchers.IO) {
            val cookie = spdc.first()
            if (cookie.isBlank()) return@withContext null

            val prefs = context.dataStore.data.first()
            var personal = prefs[SpotifyPersonalTokenKey].orEmpty()
            var client = prefs[SpotifyClientTokenKey].orEmpty()

            val personalFresh =
                personal.isNotBlank() &&
                    prefs[SpotifyPersonalTokenExpiresKey]?.let { it > now } == true
            val clientFresh =
                client.isNotBlank() &&
                    prefs[SpotifyClientTokenExpiresKey]?.let { it > now } == true

            if (!personalFresh) {
                // A stale sp_dc yields a valid-looking *anonymous* token rather than an
                // error, so check isAnonymous instead of trusting the response.
                val minted = mintPersonalToken(cookie) ?: return@withContext null
                if (minted.isAnonymous) return@withContext null
                personal = minted.accessToken
                context.safeDataStoreEdit {
                    it[SpotifyPersonalTokenKey] = minted.accessToken
                    it[SpotifyPersonalTokenExpiresKey] = minted.accessTokenExpirationTimestampMs
                }
            }

            if (!clientFresh) {
                val minted = mintClientToken() ?: return@withContext null
                client = minted.grantedToken.token
                context.safeDataStoreEdit {
                    it[SpotifyClientTokenKey] = client
                    it[SpotifyClientTokenExpiresKey] =
                        now + minted.grantedToken.expiresAfterSeconds * 1000L
                }
            }

            SpotifyTokens(personal = personal, client = client)
        }

    private suspend fun mintPersonalToken(cookie: String): PersonalTokenResponse? =
        Spotify().getPersonalTokenWithTotp(cookie).getOrNull()

    private suspend fun mintClientToken(): ClientTokenResponse? =
        Spotify().getClientToken().getOrNull()

    data class SpotifyTokens(
        val personal: String,
        val client: String,
    )
}
