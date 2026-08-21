/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.music.R
import com.metrolist.music.social.FriendSelection
import com.metrolist.music.social.RelationshipState
import com.metrolist.music.social.UserProfile
import kotlinx.coroutines.delay

@Composable
fun SendToFriendsDialog(
    songCount: Int,
    relationshipState: RelationshipState,
    friendProfiles: Map<String, UserProfile>,
    onDismiss: () -> Unit,
    onSend: (List<String>) -> Unit,
) {
    var friendSelections by remember {
        mutableStateOf<List<FriendSelection>>(emptyList())
    }

    var isLoading by remember { mutableStateOf(true) }

    // Update friend selections when data changes
    LaunchedEffect(relationshipState.friends, friendProfiles) {
        if (relationshipState.friends.isNotEmpty()) {
            friendSelections =
                relationshipState.friends.map { uid ->
                    FriendSelection(
                        uid = uid,
                        username = friendProfiles[uid]?.username ?: "Unknown",
                        photoUrl = friendProfiles[uid]?.photoUrl,
                        isSelected = false,
                    )
                }
            isLoading = false
        } else {
            // Give it a moment to load, then show "no friends" if still empty
            delay(500)
            isLoading = false
        }
    }

    val selectedCount = friendSelections.count { it.isSelected }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text =
                    pluralStringResource(
                        R.plurals.send_n_songs_to_friends,
                        songCount,
                        songCount,
                    ),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column {
                if (isLoading) {
                    // Show loading indicator
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (friendSelections.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_friends_to_send),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.select_friends_to_send),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                    ) {
                        items(friendSelections) { friend ->
                            FriendSelectionItem(
                                friend = friend,
                                onToggle = {
                                    friendSelections =
                                        friendSelections.map {
                                            if (it.uid == friend.uid) {
                                                it.copy(isSelected = !it.isSelected)
                                            } else {
                                                it
                                            }
                                        }
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val selectedUids =
                        friendSelections
                            .filter { it.isSelected }
                            .map { it.uid }
                    onSend(selectedUids)
                },
                enabled = selectedCount > 0 && !isLoading,
            ) {
                Text(
                    if (selectedCount > 0) {
                        pluralStringResource(
                            R.plurals.send_to_n_friends,
                            selectedCount,
                            selectedCount,
                        )
                    } else {
                        stringResource(R.string.send)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun FriendSelectionItem(
    friend: FriendSelection,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Profile picture or placeholder
        if (friend.photoUrl != null) {
            AsyncImage(
                model = friend.photoUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.person),
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .padding(8.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = friend.username,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )

        Icon(
            painter =
                painterResource(
                    if (friend.isSelected) {
                        R.drawable.radio_button_checked
                    } else {
                        R.drawable.radio_button_unchecked
                    },
                ),
            contentDescription =
                if (friend.isSelected) {
                    stringResource(R.string.selected)
                } else {
                    stringResource(R.string.not_selected)
                },
            tint =
                if (friend.isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}
