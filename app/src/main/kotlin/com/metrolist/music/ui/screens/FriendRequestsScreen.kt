/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.social.UserProfile
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.viewmodels.SocialViewModel

/**
 * Incoming friend requests, with accept / reject. Accepting writes the friendship both ways, which
 * is what makes the pair show up in each other's "send to friends" list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendRequestsScreen(
    navController: NavController,
    socialViewModel: SocialViewModel = hiltViewModel(),
) {
    val relationships by socialViewModel.relationships.collectAsStateWithLifecycle()
    val users by socialViewModel.users.collectAsStateWithLifecycle()
    val windowInsets = LocalPlayerAwareWindowInsets.current

    val incoming = relationships.incomingRequests.values.filter { it.status == "pending" }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = windowInsets.asPaddingValues().calculateTopPadding() + 8.dp,
            bottom = windowInsets.asPaddingValues().calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (incoming.isEmpty()) {
            item("empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.social_no_pending_requests),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(incoming, key = { it.id }) { request ->
                FriendRequestRow(
                    requester = users.find { it.uid == request.fromUid },
                    fallbackName = request.fromUid,
                    onAccept = { socialViewModel.acceptFriendRequest(request) },
                    onReject = { socialViewModel.rejectFriendRequest(request.id) },
                )
            }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.social_friend_requests)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}

@Composable
private fun FriendRequestRow(
    requester: UserProfile?,
    fallbackName: String,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val displayName = requester?.username?.takeIf { it.isNotBlank() }
        ?: requester?.email?.takeIf { it.isNotBlank() }
        ?: fallbackName

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SocialAvatar(
                name = displayName,
                photoUrl = requester?.photoUrl,
            )

            Spacer(Modifier.width(12.dp))

            Text(
                text = stringResource(R.string.social_request_from, displayName),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onReject,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.reject))
            }
            Button(
                onClick = onAccept,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.social_accept))
            }
        }
    }
}
