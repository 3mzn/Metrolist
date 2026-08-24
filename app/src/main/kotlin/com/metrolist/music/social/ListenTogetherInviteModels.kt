/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

/**
 * A pending Listen Together invite, stored at `invites/{recipientUid}` — one document per
 * recipient, so a newer invite simply overwrites an older one (last-write-wins).
 *
 * Expiry is judged by the READER's clock against [createdAt] alone (no expiresAt field),
 * which makes the check immune to sender/receiver clock skew: `now - createdAt >=
 * [EXPIRY_MS]` means expired, wherever you are.
 */
data class ListenTogetherInvite(
    val roomCode: String,
    val fromUid: String,
    val fromName: String,
    val createdAt: Long,
    val status: String, // "pending" -> "accepted" | "declined"
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        now - createdAt >= EXPIRY_MS

    fun isPending(): Boolean = status == STATUS_PENDING

    companion object {
        const val EXPIRY_MS = 15 * 60 * 1000L
        const val STATUS_PENDING = "pending"
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_DECLINED = "declined"

        fun fromMap(map: Map<String, Any?>): ListenTogetherInvite? {
            val roomCode = map["roomCode"] as? String ?: return null
            val fromUid = map["fromUid"] as? String ?: return null
            val fromName = map["fromName"] as? String ?: return null
            val createdAt = (map["createdAt"] as? Number)?.toLong() ?: return null
            val status = map["status"] as? String ?: STATUS_PENDING
            return ListenTogetherInvite(roomCode, fromUid, fromName, createdAt, status)
        }
    }
}
