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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.AppBarHeight
import com.metrolist.music.ui.dialog.SocialRepositoryEntryPoint
import com.metrolist.music.ui.dialog.rememberPartnerIdentity
import com.metrolist.music.viewmodels.AuthViewModel
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import android.widget.Toast

/**
 * Bottom-nav destination for the social features. Routes between sign-in, one-time username setup,
 * and a minimal account dashboard (profile card, logout, delete account).
 *
 * The two-person build has no friend discovery: songs always go to the partner, so the friends
 * list and request flows are gone. The top app bar comes from [com.metrolist.music.MainActivity]
 * like the other main screens, so this only draws content.
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
                displayName = profile?.username.orEmpty(),
                email = profile?.email ?: user?.email.orEmpty(),
                photoUrl = profile?.photoUrl,
                onLogout = authViewModel::logout,
            )
    }
}

@Composable
private fun SocialDashboard(
    displayName: String,
    email: String,
    photoUrl: String?,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    val windowInsets = LocalPlayerAwareWindowInsets.current

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

        item("danger_zone") {
            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                enabled = !isDeleting,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(
                    painter = painterResource(R.drawable.delete),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.delete_account))
            }
        }
    }

    if (showDeleteConfirm) {
        val partnerName = rememberPartnerIdentity().partnerName ?: "your partner"

        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteConfirm = false },
            title = {
                Text(stringResource(R.string.delete_account_confirm_title))
            },
            text = {
                Text(
                    stringResource(
                        R.string.delete_account_confirm_message,
                        partnerName,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val firebaseUser = FirebaseAuth.getInstance().currentUser ?: return@Button
                        isDeleting = true
                        coroutineScope.launch {
                            try {
                                EntryPointAccessors.fromApplication(
                                    context.applicationContext,
                                    SocialRepositoryEntryPoint::class.java,
                                ).socialRepository().wipeMyCloudData(firebaseUser.uid)
                                firebaseUser.delete().await()
                                // Auth listeners fire everywhere; SocialScreen swaps to the
                                // login screen on its own. Nothing to navigate manually.
                            } catch (e: FirebaseAuthRecentLoginRequiredException) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.delete_account_requires_recent_login),
                                    Toast.LENGTH_LONG,
                                ).show()
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.delete_account_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } finally {
                                isDeleting = false
                                showDeleteConfirm = false
                            }
                        }
                    },
                    enabled = !isDeleting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
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
