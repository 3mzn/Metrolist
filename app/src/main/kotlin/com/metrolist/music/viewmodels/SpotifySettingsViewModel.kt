/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.utils.spotify.SpotifySessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Spotify settings. Phase 2 of SPEC_SPOTIFY_CANVAS.
 *
 * Signing out clears the cookie and both tokens through [SpotifySessionRepository], so no
 * stale credential can outlive the logout.
 */
@HiltViewModel
class SpotifySettingsViewModel @Inject constructor(
    private val spotifySessionRepository: SpotifySessionRepository,
) : ViewModel() {

    fun logout() {
        viewModelScope.launch {
            spotifySessionRepository.logout()
        }
    }
}
