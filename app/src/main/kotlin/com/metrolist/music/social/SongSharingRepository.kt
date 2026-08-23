/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import android.content.Context
import com.metrolist.music.R
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.models.MediaMetadata
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SongSharingRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val database: MusicDatabase,
    @ApplicationContext private val context: Context,
    private val partnerResolver: PartnerResolver,
) {
    private val sentSongsCollection get() = firestore.collection("sentSongs")
    private val TAG = "SongSharingRepository"

    /**
     * Create the shared-songs playlist if it doesn't exist, and keep its label directional:
     * "From aswini" on eman's device, "From eman" on aswini's. Idempotent — runs at every app
     * start, so both fresh installs and pre-existing "To Listen" rows converge.
     */
    suspend fun initializeToListenPlaylist() = withContext(Dispatchers.IO) {
        // playlistBlocking and database.query both hit Room synchronously, and the callers are
        // viewModelScope coroutines on the main dispatcher — this must stay confined to IO or
        // Room throws IllegalStateException and the playlist silently never appears.
        Timber.tag("PLAYLIST_INIT").w(
            "PLAYLIST_INIT_V3 thread=%s partnerName=%s",
            Thread.currentThread().name,
            partnerResolver.identity.value.partnerName,
        )
        val desiredName =
            partnerResolver.identity.value.partnerName
                ?.let { context.getString(R.string.from_partner_format, it) }

        val existingPlaylist = database.playlistBlocking(PlaylistEntity.TO_LISTEN_PLAYLIST_ID)

        when {
            existingPlaylist == null -> {
                // Logged out or identity unresolved yet; next launch retries.
                desiredName ?: return@withContext
                Timber.tag(TAG).d("Creating shared-songs playlist '$desiredName'")
                val toListenPlaylist =
                    PlaylistEntity(
                        id = PlaylistEntity.TO_LISTEN_PLAYLIST_ID,
                        name = desiredName,
                        browseId = null,
                        isEditable = false, // Users cannot manually edit this playlist
                        bookmarkedAt = LocalDateTime.now(),
                        isLocal = true,
                    )
                database.query {
                    insert(toListenPlaylist)
                }
            }

            existingPlaylist.playlist.name != desiredName && desiredName != null -> {
                Timber.tag(TAG).d(
                    "Renaming shared-songs playlist '${existingPlaylist.playlist.name}' -> '$desiredName'",
                )
                database.query {
                    update(existingPlaylist.playlist.copy(name = desiredName))
                }
            }
        }
    }

    /**
     * Send songs to multiple friends.
     *
     * @param songs List of songs to send
     * @param friendUids List of friend UIDs to send to
     * @param friendProfiles Map of UID to UserProfile for username lookup
     * @return Number of songs successfully sent
     */
    suspend fun sendSongsToFriends(
        songs: List<MediaMetadata>,
        friendUids: List<String>,
        friendProfiles: Map<String, UserProfile>,
    ): Int {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Timber.tag(TAG).e("Cannot send songs: User not logged in")
            throw Exception("User not logged in")
        }

        val currentUsername = getCurrentUsername() ?: "Unknown"
        var successCount = 0

        songs.forEach { song ->
            friendUids.forEach { friendUid ->
                try {
                    val sentSong =
                        SentSong(
                            songId = song.id,
                            songTitle = song.title,
                            songArtist = song.artists.joinToString(", ") { it.name },
                            songDuration = song.duration,
                            thumbnailUrl = song.thumbnailUrl,
                            albumId = song.album?.id,
                            albumName = song.album?.title,
                            fromUid = currentUser.uid,
                            fromUsername = currentUsername,
                            toUid = friendUid,
                            sentAt = System.currentTimeMillis(),
                        )

                    sentSongsCollection.add(sentSong.toMap()).await()
                    successCount++
                    Timber.tag(TAG).d("Sent song ${song.title} to $friendUid")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to send song ${song.title} to $friendUid")
                }
            }
        }

        Timber.tag(TAG).d("Finished sending. Success count: $successCount")
        return successCount
    }

    /**
     * Listen for incoming songs for the current user.
     */
    fun observeIncomingSongs(): Flow<List<SentSong>> = callbackFlow {
        val currentUid = auth.currentUser?.uid
        if (currentUid == null) {
            Timber.tag(TAG).e("Cannot observe incoming songs: User not logged in")
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        // Simplified query to avoid needing a composite index.
        // Filter completedAt and sort by sentAt on the client side.
        val registration =
            sentSongsCollection
                .whereEqualTo("toUid", currentUid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Timber.e(error, "Error observing incoming songs")
                        return@addSnapshotListener
                    }
                    if (snapshot == null) {
                        Timber.w("Snapshot is null")
                        return@addSnapshotListener
                    }

                    val songs =
                        snapshot.documents
                            .mapNotNull { doc ->
                                try {
                                    val song = SentSong.fromMap(doc.id, doc.data ?: emptyMap())
                                    // Only include songs that haven't been completed
                                    if (song.completedAt == null) song else null
                                } catch (e: Exception) {
                                    Timber.e(e, "Error parsing sent song from doc ${doc.id}")
                                    null
                                }
                            }.sortedByDescending { it.sentAt } // Newest first

                    trySend(songs)
                }

        awaitClose {
            registration.remove()
        }
    }

    /**
     * Add an incoming song to the "To Listen" playlist.
     *
     * @return AddSongResult indicating SUCCESS, DUPLICATE, or ERROR
     */
    suspend fun addSongToToListenPlaylist(sentSong: SentSong, metadata: MediaMetadata): AddSongResult =
        withContext(Dispatchers.IO) {
            try {
                var result = AddSongResult.ERROR
                database.transaction {
                    // 1. Double check for duplicates inside the transaction
                    val isDuplicate =
                        database.checkInPlaylist(
                            PlaylistEntity.TO_LISTEN_PLAYLIST_ID,
                            sentSong.songId,
                        ) > 0

                    if (isDuplicate) {
                        Timber.tag(TAG).d("Song ${sentSong.songTitle} already in To Listen playlist")
                        result = AddSongResult.DUPLICATE
                        return@transaction
                    }

                    // 2. Check and insert song into library (also inserts artists)
                    val existing = database.getSongByIdBlocking(sentSong.songId)
                    if (existing == null) {
                        database.insert(metadata)
                    }

                    // 3. Get latest playlist state and shift positions
                    val currentSongs = database.playlistSongsBlocking(PlaylistEntity.TO_LISTEN_PLAYLIST_ID)
                    currentSongs.forEach { playlistSong ->
                        database.update(playlistSong.map.copy(position = playlistSong.map.position + 1))
                    }

                    // 4. Insert at position 0
                    database.insert(
                        PlaylistSongMap(
                            songId = sentSong.songId,
                            playlistId = PlaylistEntity.TO_LISTEN_PLAYLIST_ID,
                            position = 0,
                        ),
                    )

                    result = AddSongResult.SUCCESS
                }
                result
            } catch (e: Exception) {
                Timber.e(e, "Error adding song to To Listen playlist")
                AddSongResult.ERROR
            }
        }

    /**
     * Every still-pending document this user holds for [songId].
     *
     * A song can arrive from more than one friend, or twice from the same friend, which means several
     * documents share one songId. The playlist only ever holds one row for it, so acting on a single
     * document would leave the others pending forever: the sender would never be told it was heard,
     * and once the song left the playlist the incoming-songs listener would add it straight back.
     *
     * Equality-only filters, so Firestore serves this by merging single-field indexes -- no composite
     * index required.
     */
    private suspend fun pendingSentSongIdsFor(songId: String): List<String> {
        val currentUid = auth.currentUser?.uid ?: return emptyList()

        return try {
            sentSongsCollection
                .whereEqualTo("toUid", currentUid)
                .whereEqualTo("songId", songId)
                .get()
                .await()
                .documents
                .filter { it.get("completedAt") == null }
                .map { it.id }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Could not list pending documents for song $songId")
            emptyList()
        }
    }

    /**
     * Mark song as listened (50% milestone reached).
     *
     * @param songId when given, every pending document for this song is marked, so each friend who
     *   sent it is notified rather than only the most recent one.
     */
    suspend fun markSongAsListened(sentSongId: String, songId: String? = null) {
        withContext(Dispatchers.IO) {
            try {
                val ids = if (songId == null) {
                    listOf(sentSongId)
                } else {
                    (pendingSentSongIdsFor(songId) + sentSongId).distinct()
                }

                val listenedAt = System.currentTimeMillis()
                ids.forEach { id ->
                    sentSongsCollection.document(id).update(
                        mapOf("listenedAt" to listenedAt),
                    ).await()
                }
                Timber.tag(TAG).d("Marked song $sentSongId as listened")
            } catch (e: Exception) {
                Timber.e(e, "Error marking song as listened")
                throw e // Re-throw so caller knows it failed
            }
        }
    }

    /**
     * Mark song as completed and remove from "To Listen" playlist.
     *
     * Completes every pending document for [songId], not just [sentSongId]: the song is about to
     * leave the playlist, and any document left pending would be re-added by the incoming-songs
     * listener on the next launch.
     */
    suspend fun markSongAsCompleted(sentSongId: String, songId: String) {
        withContext(Dispatchers.IO) {
            try {
                val ids = (pendingSentSongIdsFor(songId) + sentSongId).distinct()

                // Firestore first: if these updates fail the song stays in the playlist and can be
                // retried, whereas removing it locally first would strand the documents as pending.
                val completedAt = System.currentTimeMillis()
                ids.forEach { id ->
                    sentSongsCollection.document(id).update(
                        mapOf("completedAt" to completedAt),
                    ).await()
                }
                Timber.tag(TAG).d("Cloud: Marked ${ids.size} document(s) for $songId as completed")

                database.transaction {
                    val playlistSongs = database.playlistSongsBlocking(PlaylistEntity.TO_LISTEN_PLAYLIST_ID)
                    val songToRemove = playlistSongs.find { it.song.id == songId }
                    if (songToRemove != null) {
                        val removedPosition = songToRemove.map.position
                        database.delete(songToRemove.map)

                        // Shift positions
                        playlistSongs
                            .filter { it.map.position > removedPosition }
                            .forEach { playlistSong ->
                                database.update(playlistSong.map.copy(position = playlistSong.map.position - 1))
                            }
                        Timber.tag(TAG).d("Local: Removed song $songId from To Listen playlist")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error marking song as completed")
                throw e // Re-throw so caller knows it failed
            }
        }
    }

    /**
     * Clear all songs from "To Listen" playlist and mark them as completed in Firestore.
     *
     * Marking comes first and is allowed to throw: clearing locally while documents were still
     * pending would only have the incoming-songs listener add every song back.
     */
    suspend fun clearToListenPlaylist() = withContext(Dispatchers.IO) {
        val currentUid = auth.currentUser?.uid

        try {
            // Signed out there is nothing to reconcile, and no listener running to undo the clear.
            if (currentUid != null) {
                val snapshot =
                    sentSongsCollection
                        .whereEqualTo("toUid", currentUid)
                        .get()
                        .await()

                val pendingDocs = snapshot.documents.filter { doc ->
                    doc.get("completedAt") == null
                }

                if (pendingDocs.isNotEmpty()) {
                    val batch = firestore.batch()
                    val now = System.currentTimeMillis()
                    pendingDocs.forEach { doc ->
                        batch.update(doc.reference, "completedAt", now)
                    }
                    batch.commit().await()
                }
            }

            database.transaction {
                clearPlaylist(PlaylistEntity.TO_LISTEN_PLAYLIST_ID)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error clearing To Listen playlist")
            throw e // Re-throw to show error in UI
        }
    }

    /**
     * Broadcast what this device is currently playing, for the partner's widget.
     * One document per user at `status/{uid}` — written once per song change, never per second.
     */
    suspend fun updateMyStatus(
        songId: String,
        title: String,
        artist: String,
        coverUrl: String?,
    ) {
        val currentUid = auth.currentUser?.uid ?: return
        try {
            firestore.collection("status").document(currentUid).set(
                mapOf(
                    "songId" to songId,
                    "title" to title,
                    "artist" to artist,
                    "coverUrl" to coverUrl,
                    "updatedAt" to System.currentTimeMillis(),
                ),
            ).await()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to update my playback status")
        }
    }

    /** Stop broadcasting (playback stopped or service shutting down). */
    suspend fun clearMyStatus() {
        val currentUid = auth.currentUser?.uid ?: return
        try {
            firestore.collection("status").document(currentUid).delete().await()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to clear my playback status")
        }
    }

    /**
     * Mark notification as sent for a song.
     */
    suspend fun markNotificationSent(sentSongId: String) {
        try {
            sentSongsCollection.document(sentSongId).update(
                mapOf("notificationSent" to true),
            ).await()
            Timber.tag(TAG).d("Marked notification as sent for $sentSongId")
        } catch (e: Exception) {
            Timber.e(e, "Error marking notification as sent")
            // Don't throw - notification was shown locally.
            // Prevents duplicate notifications on retry.
        }
    }

    /**
     * Get songs that have been listened to but the sender hasn't been notified yet.
     */
    suspend fun getListenedSongsNeedingNotification(fromUid: String, since: Long): List<SentSong> {
        return try {
            val snapshot =
                sentSongsCollection
                    .whereEqualTo("fromUid", fromUid)
                    .whereGreaterThan("sentAt", since)
                    .get()
                    .await()

            snapshot.documents.mapNotNull { doc ->
                try {
                    val song = SentSong.fromMap(doc.id, doc.data ?: emptyMap())
                    // Only include songs that have been listened to but not notified
                    if (song.listenedAt != null && !song.notificationSent) song else null
                } catch (e: Exception) {
                    Timber.e(e, "Error parsing sent song from doc ${doc.id}")
                    null
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting listened songs needing notification")
            emptyList()
        }
    }

    /**
     * Observe songs that have been listened to but the sender hasn't been notified yet.
     * Used for real-time notifications when the app is open.
     */
    fun observeListenedSongsNeedingNotification(fromUid: String, since: Long): Flow<List<SentSong>> = callbackFlow {
        val registration =
            sentSongsCollection
                .whereEqualTo("fromUid", fromUid)
                .whereGreaterThan("sentAt", since)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Timber.e(error, "Error observing listened songs")
                        return@addSnapshotListener
                    }
                    if (snapshot == null) {
                        Timber.w("Snapshot is null")
                        return@addSnapshotListener
                    }

                    val songs =
                        snapshot.documents.mapNotNull { doc ->
                            try {
                                val song = SentSong.fromMap(doc.id, doc.data ?: emptyMap())
                                // Only include songs that have been listened to but not notified
                                if (song.listenedAt != null && !song.notificationSent) song else null
                            } catch (e: Exception) {
                                Timber.e(e, "Error parsing sent song from doc ${doc.id}")
                                null
                            }
                        }

                    trySend(songs)
                }

        awaitClose {
            registration.remove()
        }
    }

    /**
     * Get sent song by song ID for the current user.
     */
    suspend fun getSentSongBySongId(songId: String): SentSong? {
        val currentUid = auth.currentUser?.uid ?: return null

        return try {
            val snapshot =
                sentSongsCollection
                    .whereEqualTo("toUid", currentUid)
                    .whereEqualTo("songId", songId)
                    .get()
                    .await()

            snapshot.documents
                .mapNotNull { doc ->
                    try {
                        SentSong.fromMap(doc.id, doc.data ?: emptyMap())
                    } catch (e: Exception) {
                        Timber.e(e, "Error parsing sent song from doc ${doc.id}")
                        null
                    }
                }.filter { it.completedAt == null }
                .sortedByDescending { it.sentAt }
                .firstOrNull()
        } catch (e: Exception) {
            Timber.e(e, "Error getting sent song by ID")
            null
        }
    }

    /**
     * Get the current user's username from Firestore.
     */
    private suspend fun getCurrentUsername(): String? {
        val currentUid = auth.currentUser?.uid ?: return null

        return try {
            val doc = firestore.collection("users").document(currentUid).get().await()
            doc.getString("username")
        } catch (e: Exception) {
            Timber.e(e, "Error getting current username")
            null
        }
    }
}
