/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.models.MediaMetadata
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
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
) {
    private val sentSongsCollection get() = firestore.collection("sentSongs")
    private val TAG = "SongSharingRepository"

    /**
     * Initialize "To Listen" playlist if it doesn't exist.
     */
    suspend fun initializeToListenPlaylist() = withContext(Dispatchers.IO) {
        // playlistBlocking and query both hit Room synchronously, and the caller is a viewModelScope
        // coroutine on the main dispatcher, so this has to be confined to IO or Room rejects it.
        val existingPlaylist = database.playlistBlocking(PlaylistEntity.TO_LISTEN_PLAYLIST_ID)

        if (existingPlaylist == null) {
            Timber.tag(TAG).d("Creating 'To Listen' playlist")
            val toListenPlaylist =
                PlaylistEntity(
                    id = PlaylistEntity.TO_LISTEN_PLAYLIST_ID,
                    name = "To Listen",
                    browseId = null,
                    isEditable = false, // Users cannot manually edit this playlist
                    bookmarkedAt = LocalDateTime.now(),
                    isLocal = true,
                )
            database.query {
                insert(toListenPlaylist)
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

        // Test Firestore connectivity
        try {
            firestore.collection("sentSongs").document("test").get().await()
            Timber.tag(TAG).d("Firestore connection successful")
        } catch (e: Exception) {
            Timber.e(e, "Firestore connection failed")
            throw Exception("Firestore connection failed: ${e.message}")
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
     * Mark song as listened (50% milestone reached).
     */
    suspend fun markSongAsListened(sentSongId: String) {
        withContext(Dispatchers.IO) {
            try {
                sentSongsCollection.document(sentSongId).update(
                    mapOf("listenedAt" to System.currentTimeMillis()),
                ).await()
                Timber.tag(TAG).d("Marked song $sentSongId as listened")
            } catch (e: Exception) {
                Timber.e(e, "Error marking song as listened")
                throw e // Re-throw so caller knows it failed
            }
        }
    }

    /**
     * Mark song as completed and remove from "To Listen" playlist.
     */
    suspend fun markSongAsCompleted(sentSongId: String, songId: String) {
        withContext(Dispatchers.IO) {
            try {
                // Perform local removal first
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

                // Update Firestore
                sentSongsCollection.document(sentSongId).update(
                    mapOf("completedAt" to System.currentTimeMillis()),
                ).await()
                Timber.tag(TAG).d("Cloud: Marked song $sentSongId as completed")
            } catch (e: Exception) {
                Timber.e(e, "Error marking song as completed")
                throw e // Re-throw so caller knows it failed
            }
        }
    }

    /**
     * Clear all songs from "To Listen" playlist and mark them as completed in Firestore.
     */
    suspend fun clearToListenPlaylist() {
        val currentUid = auth.currentUser?.uid ?: return

        try {
            // 1. Get all pending documents from Firestore for this user
            val snapshot =
                sentSongsCollection
                    .whereEqualTo("toUid", currentUid)
                    .get()
                    .await()

            val pendingDocs = snapshot.documents.filter { doc ->
                doc.get("completedAt") == null
            }

            // 2. Mark them as completed in Firestore using a batch
            if (pendingDocs.isNotEmpty()) {
                val batch = firestore.batch()
                val now = System.currentTimeMillis()
                pendingDocs.forEach { doc ->
                    batch.update(doc.reference, "completedAt", now)
                }
                batch.commit().await()
            }

            // 3. Clear local database entries for this playlist
            database.transaction {
                clearPlaylist(PlaylistEntity.TO_LISTEN_PLAYLIST_ID)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error clearing To Listen playlist")
            throw e // Re-throw to show error in UI
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
