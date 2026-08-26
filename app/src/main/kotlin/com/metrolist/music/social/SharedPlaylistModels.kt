/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import com.google.firebase.Timestamp

/**
 * Mirrors a Firestore doc at `sharedPlaylists/{playlistId}`.
 *
 * One doc per shared playlist; doc id = the local Room playlist id ("LP" + 8 chars).
 * Both phones write the same id on the same playlist. Last-writer-wins per field
 * (Firestore's natural behavior). Songs are stored as a flat array of YouTube song
 * ids; each phone resolves the song metadata from its own local Room `song` table.
 */
data class SharedPlaylistCloud(
    val id: String,
    val name: String,
    val thumbnailUrl: String?,
    val sharedByUid: String,
    val sharedByName: String?,
    val sharedWith: String,
    val songs: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        fun fromMap(id: String, map: Map<String, Any?>): SharedPlaylistCloud? {
            val name = map["name"] as? String ?: return null
            val sharedByUid = map["sharedByUid"] as? String ?: return null
            val sharedWith = map["sharedWith"] as? String ?: return null
            val createdAt = map["createdAt"].asEpochMillis() ?: return null
            val updatedAt = map["updatedAt"].asEpochMillis() ?: createdAt
            val thumbnailUrl = map["thumbnailUrl"] as? String
            val sharedByName = map["sharedByName"] as? String
            @Suppress("UNCHECKED_CAST")
            val songs = (map["songs"] as? List<String>) ?: emptyList()
            return SharedPlaylistCloud(
                id = id,
                name = name,
                thumbnailUrl = thumbnailUrl,
                sharedByUid = sharedByUid,
                sharedByName = sharedByName,
                sharedWith = sharedWith,
                songs = songs,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
        }
    }
}

private fun Any?.asEpochMillis(): Long? =
    when (this) {
        is Number -> toLong()
        is Timestamp -> toDate().time
        else -> null
    }

/** Tombstone doc at `partners_deleted/{uid}`. Persistent, signals account deletion (D8). */
data class PartnerDeletedTombstone(
    val uid: String,
    val at: Long,
)
