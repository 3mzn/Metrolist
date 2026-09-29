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
 * New code, following Metrolist's existing integration-screen conventions
 * (mirrors LastFMSettings' account group) and SimpMusic's Spotify section
 * (GPL-3.0): a login row routing to the WebView, and a logout row that clears
 * the cookie and both tokens.
 * ---------------------------------------------------------------------------
 */

package com.metrolist.music.ui.screens.settings.integrations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.SpotifySpdcKey
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.SpotifySettingsViewModel
import androidx.compose.runtime.getValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifySettings(
    navController: NavController,
    viewModel: SpotifySettingsViewModel = hiltViewModel(),
) {
    val spdc by rememberPreference(SpotifySpdcKey, "")
    val isLoggedIn = spdc.isNotBlank()

    Column(
        modifier = Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top
                )
            )
        )

        Material3SettingsGroup(
            title = stringResource(R.string.account),
            items = listOf(
                Material3SettingsItem(
                    title = {
                        Text(
                            text = if (isLoggedIn) stringResource(R.string.spotify_logged_in) else stringResource(R.string.not_logged_in),
                            modifier = Modifier.alpha(if (isLoggedIn) 1f else 0.5f),
                        )
                    },
                    trailingContent = {
                        if (isLoggedIn) {
                            OutlinedButton(onClick = {
                                viewModel.logout()
                            }) {
                                Text(stringResource(R.string.action_logout))
                            }
                        } else {
                            OutlinedButton(onClick = {
                                navController.navigate("settings/integrations/spotify/login")
                            }) {
                                Text(stringResource(R.string.log_in_to_spotify))
                            }
                        }
                    },
                    icon = painterResource(R.drawable.music_note)
                ),
            )
        )

        Spacer(Modifier.height(8.dp))
    }
}
