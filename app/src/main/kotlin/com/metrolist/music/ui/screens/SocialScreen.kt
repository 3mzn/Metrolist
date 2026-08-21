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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.AppBarHeight
import com.metrolist.music.social.UserProfile
import com.metrolist.music.viewmodels.AuthViewModel
import com.metrolist.music.viewmodels.SocialViewModel

/**
 * Bottom-nav destination for the social features. Routes between sign-in, one-time username setup,
 * and the friends dashboard depending on how far the Firebase session has progressed.
 *
 * The top app bar comes from [com.metrolist.music.MainActivity] like the other main screens, so this
 * only draws content.
 */
@Composable
fun SocialScreen(
    navController: NavController,
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val user by authViewModel.user.collectAsStateWithLifecycle()
    val profile by authViewModel.profile.collectAsStateWithLifecycle()

    when {
        user == null -> FirebaseLoginScreen(authViewModel = authViewModel)

        profile?.username.isNullOrEmpty() ->
            ProfileSetupScreen(
                // Nothing to do: `profile` updates once the username is stored, which swaps this
                // screen out for the dashboard.
                onProfileComplete = {},
                authViewModel = authViewModel,
            )

        else ->
            SocialDashboard(
                navController = navController,
                displayName = profile?.username.orEmpty(),
                email = profile?.email ?: user?.email.orEmpty(),
                photoUrl = profile?.photoUrl,
                onLogout = authViewModel::logout,
            )
    }
}

@Composable
private fun SocialDashboard(
    navController: NavController,
    displayName: String,
    email: String,
    photoUrl: String?,
    onLogout: () -> Unit,
    socialViewModel: SocialViewModel = hiltViewModel(),
) {
    val relationships by socialViewModel.relationships.collectAsStateWithLifecycle()
    val users by socialViewModel.users.collectAsStateWithLifecycle()
    val windowInsets = LocalPlayerAwareWindowInsets.current

    val friends = users.filter { it.uid in relationships.friends }
    val pendingRequestCount = relationships.incomingRequests.values.count { it.status == "pending" }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = windowInsets.asPaddingValues().calculateTopPadding() + 16.dp,
            bottom = windowInsets.asPaddingValues().calculateBottomPadding() + 16.dp + AppBarHeight,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("profile_card") {
            ProfileCard(
                displayName = displayName,
                email = email,
                photoUrl = photoUrl,
                onLogout = onLogout,
            )
        }

        item("actions") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { navController.navigate(SOCIAL_USERS_ROUTE) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.group_add),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.social_add_friend))
                }

                OutlinedButton(
                    onClick = { navController.navigate(SOCIAL_REQUESTS_ROUTE) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = if (pendingRequestCount > 0) {
                            stringResource(R.string.social_requests_with_count, pendingRequestCount)
                        } else {
                            stringResource(R.string.social_requests)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        item("friends_header") {
            Text(
                text = "${stringResource(R.string.social_friends)} (${friends.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (friends.isEmpty()) {
            item("friends_empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.social_no_friends_yet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(friends, key = { it.uid }) { friend ->
                FriendRow(
                    friend = friend,
                    onRemove = { socialViewModel.removeFriend(friend.uid) },
                )
            }
        }
    }
}

@Composable
private fun ProfileCard(
    displayName: String,
    email: String,
    photoUrl: String?,
    onLogout: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SocialAvatar(
                name = displayName,
                photoUrl = photoUrl,
                size = 56.dp,
            )

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.social_greeting, displayName),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (email.isNotBlank()) {
                    Text(
                        text = email,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            IconButton(onClick = onLogout) {
                Icon(
                    painter = painterResource(R.drawable.logout),
                    contentDescription = stringResource(R.string.social_logout),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun FriendRow(
    friend: UserProfile,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SocialAvatar(
            name = friend.username.ifBlank { friend.email },
            photoUrl = friend.photoUrl,
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = friend.username.ifBlank { friend.email },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (friend.username.isNotBlank() && friend.email.isNotBlank()) {
                Text(
                    text = friend.email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        IconButton(onClick = onRemove) {
            Icon(
                painter = painterResource(R.drawable.person_remove),
                contentDescription = stringResource(R.string.social_remove_friend),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Profile picture, falling back to the first letter of [name] the way the Listen Together user
 * avatars do.
 */
@Composable
internal fun SocialAvatar(
    name: String,
    photoUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    if (photoUrl != null) {
        AsyncImage(
            model = photoUrl,
            contentDescription = null,
            modifier = modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        Surface(
            modifier = modifier.size(size),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// Namespaced under the tab's own route so the navigation rail keeps the Social tab highlighted while
// a sub-screen is open — see `isRouteSelected` in AppNavigation.kt.
internal const val SOCIAL_USERS_ROUTE = "social/users"
internal const val SOCIAL_REQUESTS_ROUTE = "social/requests"
