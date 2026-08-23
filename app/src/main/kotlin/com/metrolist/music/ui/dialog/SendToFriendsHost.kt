/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.dialog

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.metrolist.music.R
import com.metrolist.music.db.entities.Song
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.social.PartnerIdentity
import com.metrolist.music.social.PartnerResolver
import com.metrolist.music.social.SocialRepository
import com.metrolist.music.social.SongSharingRepository
import com.metrolist.music.social.UserProfile
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SocialRepositoryEntryPoint {
    fun socialRepository(): SocialRepository
    fun songSharingRepository(): SongSharingRepository
    fun partnerResolver(): PartnerResolver
}

/** Convenience accessor for composables outside the Hilt graph that need partner identity. */
@Composable
fun rememberPartnerIdentity(): PartnerIdentity {
    val context = LocalContext.current
    val resolver = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            SocialRepositoryEntryPoint::class.java,
        ).partnerResolver()
    }
    return resolver.identity.collectAsState().value
}

/**
 * Confirms and performs the send to the single partner.
 *
 * Owns everything the caller would otherwise have to repeat: identity resolution, the send itself
 * and the result toast. Menus only decide *which* songs to offer, so the multi-select menu and the
 * per-song menu stay in sync.
 *
 * Local files are dropped -- the partner cannot resolve a song that only exists on this device.
 *
 * @param songs songs to offer for sending.
 * @param onDismiss called when the dialog closes, whether or not anything was sent.
 * @param onSent called after a successful send, for callers that also need to leave selection mode.
 */
@Composable
fun SendToFriendsHost(
    songs: List<Song>,
    onDismiss: () -> Unit,
    onSent: () -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val entryPoint = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            SocialRepositoryEntryPoint::class.java,
        )
    }
    val songSharingRepository = remember(entryPoint) { entryPoint.songSharingRepository() }
    val identity = rememberPartnerIdentity()
    val partnerName = identity.partnerName

    when {
        // Not logged in yet, or logged in but the heuristic hasn't run — nothing sensible to show.
        partnerName == null -> Unit

        // Partner's account doesn't exist yet (or Firestore lookup failed).
        identity.partnerUid == null -> {
            AlertDialogUnavailable(onDismiss = onDismiss, partnerName = partnerName)
        }

        else -> SendToFriendsDialog(
            songCount = songs.size,
            partnerName = partnerName,
            onDismiss = onDismiss,
            onSend = {
                coroutineScope.launch {
                    try {
                        val songsToSend =
                            songs.filterNot { it.song.isLocal }.map { it.toMediaMetadata() }
                        if (songsToSend.isEmpty()) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.local_songs_cannot_be_sent),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            onDismiss()
                            return@launch
                        }

                        val partnerUid = identity.partnerUid!!
                        val successCount = songSharingRepository.sendSongsToFriends(
                            songs = songsToSend,
                            friendUids = listOf(partnerUid),
                            friendProfiles = mapOf(
                                partnerUid to UserProfile(uid = partnerUid, username = partnerName),
                            ),
                        )

                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.sent_n_songs_to_friends,
                                    successCount,
                                    partnerName,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }

                        onDismiss()
                        onSent()
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to send songs to partner")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.send_to_friends_failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        onDismiss()
                    }
                }
            },
        )
    }
}

/** Shown when the partner account cannot be resolved yet. */
@Composable
private fun AlertDialogUnavailable(onDismiss: () -> Unit, partnerName: String) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.send))
        },
        text = {
            Text(text = stringResource(R.string.no_friends_to_send, partnerName))
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}
