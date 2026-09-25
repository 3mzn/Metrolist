/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalSpotifyMirrorRepository
import com.metrolist.music.R
import com.metrolist.music.ui.component.EmptyPlaceholder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Review list of Spotify rows a tracked playlist failed to match (SPEC_MIRROR_REVIEW).
 * Per-row Retry re-searches now; Remove silences the row permanently (it never
 * resurfaces, even after a relink).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MirrorReviewScreen(
    navController: NavController,
    playlistId: String,
) {
    val mirrorRepo = LocalSpotifyMirrorRepository.current
    val coroutineScope = rememberCoroutineScope()
    val skips by mirrorRepo.skipsFlow(playlistId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val lazyListState = rememberLazyListState()

    var busyRow by remember { mutableStateOf<String?>(null) }
    var retryingAll by remember { mutableStateOf(false) }
    val busy = busyRow != null || retryingAll

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        ) {
            if (skips.isEmpty()) {
                item(key = "empty") {
                    EmptyPlaceholder(
                        icon = R.drawable.check,
                        text = stringResource(R.string.mirror_review_empty),
                        modifier = Modifier.animateItem(),
                    )
                }
            } else {
                item(key = "review_header") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.mirror_review_count, skips.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        )
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                retryingAll = true
                                coroutineScope.launch(Dispatchers.IO) {
                                    mirrorRepo.retryAllSkips(playlistId)
                                    retryingAll = false
                                }
                            },
                        ) {
                            if (retryingAll) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.sync),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.mirror_review_retry_all))
                            }
                        }
                    }
                }

                items(skips, key = { it.spotifyId }) { skip ->
                    val retrying = busyRow == skip.spotifyId
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy && !retrying) {
                                busyRow = skip.spotifyId
                                coroutineScope.launch(Dispatchers.IO) {
                                    mirrorRepo.retrySkip(playlistId, skip)
                                    busyRow = null
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = skip.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = skip.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            enabled = !busy,
                            onClick = {
                                busyRow = skip.spotifyId
                                coroutineScope.launch(Dispatchers.IO) {
                                    mirrorRepo.retrySkip(playlistId, skip)
                                    busyRow = null
                                }
                            },
                        ) {
                            if (retrying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.sync),
                                    contentDescription = stringResource(R.string.mirror_review_retry),
                                )
                            }
                        }
                        IconButton(
                            enabled = !busy,
                            onClick = {
                                coroutineScope.launch(Dispatchers.IO) { mirrorRepo.dismissSkip(playlistId, skip.spotifyId) }
                            },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.close),
                                contentDescription = stringResource(R.string.mirror_review_remove),
                            )
                        }
                    }
                }
            }
        }

        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.mirror_review_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            },
        )
    }
}
