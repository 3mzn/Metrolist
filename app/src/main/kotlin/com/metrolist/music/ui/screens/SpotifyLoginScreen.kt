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
 * Ported from SimpMusic's `ui/screen/login/SpotifyLoginScreen.kt` (GPL-3.0),
 * reduced to what Metrolist needs: the WebView, the login URL and the success
 * detection. SimpMusic's developer-mode cookie-paste sheets and its Koin/Haze
 * chrome are deliberately omitted.
 *
 * The completion regex is SimpMusic's, and its rationale is preserved because it
 * records a real bug: Spotify's login lands on /en/status, sometimes with a query
 * string such as /en/status?flow_ctx=..., and a naive match fails silently, so sp_dc
 * is never saved and "auto-login appears to do nothing".
 *
 * The explicit `layoutParams` below are also SimpMusic's, and they are load-bearing
 * in a way that is invisible until the screen is blank. Compose's `AndroidView` sizes
 * the WebView view itself to the full available bounds, but the WebView lays out its
 * document from its `LayoutParams`. Left unset, the page's <body> measures zero height
 * and paints nothing while the DOM, the network traffic and `onPageFinished` all look
 * perfectly healthy. A root-level background still reaches the canvas (CSS propagates
 * it), which is why simple pages appear to render and only this one looks empty.
 * ---------------------------------------------------------------------------
 */

package com.metrolist.music.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.metrolist.music.R
import com.metrolist.music.utils.spotify.SpotifySessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Ported from SimpMusic's `Config.SPOTIFY_LOG_IN_URL`. */
private const val SPOTIFY_LOGIN_URL = "https://accounts.spotify.com/en/login"

/**
 * Where a completed Spotify login lands.
 *
 * `(?:[^/]+/)?` allows the locale segment (`/en/status`); `(?:\?.*)?` allows the query
 * string. Both are load-bearing: without the first, non-English locales never match;
 * without the second, `?flow_ctx=...` landings never match. SimpMusic fixed this after
 * "auto-login appeared to do nothing" — see the header.
 */
private val SPOTIFY_STATUS_URL = Regex("^https://accounts\\.spotify\\.com/(?:[^/]+/)?status(?:\\?.*)?$")

@HiltViewModel
class SpotifyLoginViewModel @Inject constructor(
    private val repository: SpotifySessionRepository,
) : ViewModel() {
    /** Persists the `sp_dc` cookie parsed out of the WebView's Cookie header. */
    suspend fun saveCookie(cookieHeader: String) = repository.saveSpdc(cookieHeader)
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginScreen(
    onBack: () -> Unit,
    viewModel: SpotifyLoginViewModel = hiltViewModel(),
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val successMessage = stringResource(R.string.spotify_login_success)
    var checking by remember { mutableStateOf(false) }

    /**
     * Runs on every page load. The cookie is read for every page, but only acted on when
     * the URL is the completion page — matching SimpMusic's ordering, because intermediate
     * pages carry a partial or unrelated cookie.
     */
    fun onUrlChanged(url: String) {
        if (!SPOTIFY_STATUS_URL.matches(url)) return
        scope.launch {
            checking = true
            val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
            if (cookie.isBlank()) {
                // No sp_dc yet — the page loaded before the cookie was committed. Leave the
                // WebView in place so a retry is possible instead of dead-ending the user.
                checking = false
                return@launch
            }
            viewModel.saveCookie(cookie)
            CookieManager.getInstance().removeAllCookies(null)
            checking = false
            snackbarHostState.showSnackbar(successMessage)
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.log_in_to_spotify)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { _ ->
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams =
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        webViewClient =
                            object : WebViewClient() {
                                override fun onPageFinished(
                                    view: WebView,
                                    url: String?,
                                ) {
                                    onUrlChanged(url.orEmpty())
                                }
                            }
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        loadUrl(SPOTIFY_LOGIN_URL)
                    }
                },
            )

            if (checking) {
                CircularProgressIndicator()
            }
        }
    }
}
