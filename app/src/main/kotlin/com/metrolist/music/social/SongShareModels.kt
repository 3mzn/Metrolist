/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import java.io.Serializable

/**
 * Data models for the "Send to Friend" feature, ported from OuterTune.
 */

/**
 * Represents a song sent to a friend via Firebase.
 */
data class SentSong(
    val id: String = "", // Firebase document ID
    val songId: String = "", // YouTube/Local song ID
    val songTitle: String = "",
    val songArtist: String = "",
    val songDuration: Int = 0, // in seconds
    val thumbnailUrl: String? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    val fromUid: String = "", // Sender's Firebase UID
    val fromUsername: String = "", // Sender's username for display
    val toUid: String = "", // Recipient's Firebase UID
    val sentAt: Long = System.currentTimeMillis(),
    val listenedAt: Long? = null, // Timestamp when 50% milestone reached
    val completedAt: Long? = null, // Timestamp when song finished playing
    val notificationSent: Boolean = false, // Whether sender was notified of 50% milestone
) : Serializable {
    /**
     * Convert to map for Firebase.
     */
    fun toMap(): Map<String, Any?> =
        mapOf(
            "songId" to songId,
            "songTitle" to songTitle,
            "songArtist" to songArtist,
            "songDuration" to songDuration,
            "thumbnailUrl" to thumbnailUrl,
            "albumId" to albumId,
            "albumName" to albumName,
            "fromUid" to fromUid,
            "fromUsername" to fromUsername,
            "toUid" to toUid,
            "sentAt" to sentAt,
            "listenedAt" to listenedAt,
            "completedAt" to completedAt,
            "notificationSent" to notificationSent,
        )

    companion object {
        /**
         * Create from a Firebase document.
         */
        fun fromMap(id: String, map: Map<String, Any?>): SentSong =
            SentSong(
                id = id,
                songId = map["songId"] as? String ?: "",
                songTitle = map["songTitle"] as? String ?: "",
                songArtist = map["songArtist"] as? String ?: "",
                songDuration = (map["songDuration"] as? Long)?.toInt() ?: 0,
                thumbnailUrl = map["thumbnailUrl"] as? String,
                albumId = map["albumId"] as? String,
                albumName = map["albumName"] as? String,
                fromUid = map["fromUid"] as? String ?: "",
                fromUsername = map["fromUsername"] as? String ?: "",
                toUid = map["toUid"] as? String ?: "",
                sentAt = map["sentAt"] as? Long ?: System.currentTimeMillis(),
                listenedAt = map["listenedAt"] as? Long,
                completedAt = map["completedAt"] as? Long,
                notificationSent = map["notificationSent"] as? Boolean ?: false,
            )
    }
}

/**
 * Result of attempting to add a song to the To Listen playlist.
 */
enum class AddSongResult {
    SUCCESS,
    DUPLICATE,
    ERROR,
}

/**
 * Represents a friend selected for sending songs.
 */
data class FriendSelection(
    val uid: String,
    val username: String,
    val photoUrl: String?,
    val isSelected: Boolean = false,
)
