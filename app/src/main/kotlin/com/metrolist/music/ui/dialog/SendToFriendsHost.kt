/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.dialog

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.metrolist.music.R
import com.metrolist.music.db.entities.Song
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.social.RelationshipState
import com.metrolist.music.social.SocialRepository
import com.metrolist.music.social.SongSharingRepository
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
}

/**
 * Shows [SendToFriendsDialog] and performs the send.
 *
 * Owns everything the caller would otherwise have to repeat: the repository lookups, the friend
 * list, the send itself and the result toast. Menus only decide *which* songs to offer, so the
 * multi-select menu and the per-song menu stay in sync.
 *
 * Local files are dropped -- a friend cannot resolve a song that only exists on this device.
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
    val socialRepository = remember(entryPoint) { entryPoint.socialRepository() }
    val songSharingRepository = remember(entryPoint) { entryPoint.songSharingRepository() }

    val relationshipState by socialRepository.observeRelationships()
        .collectAsState(initial = RelationshipState(emptyMap(), emptyMap(), emptySet()))
    val allUsers by socialRepository.getAllUsers().collectAsState(initial = emptyList())

    val friendProfiles = remember(allUsers, relationshipState) {
        allUsers.filter { it.uid in relationshipState.friends }.associateBy { it.uid }
    }

    SendToFriendsDialog(
        songCount = songs.size,
        relationshipState = relationshipState,
        friendProfiles = friendProfiles,
        onDismiss = onDismiss,
        onSend = { selectedFriendUids ->
            coroutineScope.launch {
                try {
                    val songsToSend = songs.filterNot { it.song.isLocal }.map { it.toMediaMetadata() }
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

                    val successCount = songSharingRepository.sendSongsToFriends(
                        songs = songsToSend,
                        friendUids = selectedFriendUids,
                        friendProfiles = friendProfiles,
                    )

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.sent_n_songs_to_friends,
                                successCount,
                                selectedFriendUids.size,
                            ),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }

                    onDismiss()
                    onSent()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to send songs to friends")
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
