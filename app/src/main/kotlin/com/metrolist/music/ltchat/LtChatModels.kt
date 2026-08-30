/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ltchat

import com.google.firebase.Timestamp

/**
 * A single message in the couple chat (SPEC_LT_CHAT), mirrored from a Firestore doc at
 * `lt_chat_messages/{autoId}`.
 *
 * Messages are IMMUTABLE once written (no edit, no unsend — read state lives on the presence
 * doc, not here). [replyTo]/[replyText]/[replySenderName] denormalize the quoted message at
 * send time so the preview survives even after the referenced message is pruned by the
 * retention policy.
 *
 * [memberUids] is the sorted pair of the couple's UIDs; it exists so the security rules can
 * prove membership per document (same trick as the `friends.members` rule).
 */
data class LtChatMessage(
    val id: String = "",
    val coupleId: String = "",
    val senderUid: String = "",
    val senderName: String = "",
    val senderAvatar: String? = null,
    val text: String = "",
    val replyTo: String? = null,
    val replyText: String? = null,
    val replySenderName: String? = null,
    val createdAtMs: Long = 0L,
    val memberUids: List<String> = emptyList(),
    val type: String = TYPE_TEXT,
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_EMOJI = "emoji"
        const val TYPE_SYSTEM = "system"

        fun fromMap(id: String, map: Map<String, Any?>): LtChatMessage? {
            val senderUid = map["sender_uid"] as? String ?: return null
            val text = map["text"] as? String ?: return null
            @Suppress("UNCHECKED_CAST")
            val memberUids = (map["member_uids"] as? List<String>) ?: emptyList()
            return LtChatMessage(
                id = id,
                coupleId = map["couple_id"] as? String ?: "",
                senderUid = senderUid,
                senderName = map["sender_name"] as? String ?: "",
                senderAvatar = map["sender_avatar"] as? String,
                text = text,
                replyTo = map["reply_to"] as? String,
                replyText = map["reply_text"] as? String,
                replySenderName = map["reply_sender_name"] as? String,
                // serverTimestamp() reads back as null on the latency-compensated local write;
                // 0 marks "still pending" and keeps ordering from the query itself.
                createdAtMs = map["created_at"].asEpochMillis() ?: 0L,
                memberUids = memberUids,
                type = map["type"] as? String ?: TYPE_TEXT,
            )
        }

        private fun Any?.asEpochMillis(): Long? =
            when (this) {
                is Number -> toLong()
                is Timestamp -> toDate().time
                else -> null
            }
    }
}

/**
 * One user's chat presence doc at `lt_chat_presence/{coupleId}_{userUid}` (deterministic id —
 * one doc per user per couple, upserted with merge so last_read_at survives typing updates).
 *
 * Read receipts are DERIVED: my message is "read" when the partner's [lastReadAtMs] is at or
 * after the message's createdAt. Keeping messages immutable forever is what makes this safe.
 */
data class LtChatPresence(
    val userUid: String = "",
    val isTyping: Boolean = false,
    val lastSeenMs: Long = 0L,
    val lastReadAtMs: Long = 0L,
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): LtChatPresence? {
            val userUid = map["user_uid"] as? String ?: return null
            return LtChatPresence(
                userUid = userUid,
                isTyping = map["is_typing"] as? Boolean ?: false,
                lastSeenMs = map["last_seen"].asEpochMillis() ?: 0L,
                lastReadAtMs = map["last_read_at"].asEpochMillis() ?: 0L,
            )
        }

        private fun Any?.asEpochMillis(): Long? =
            when (this) {
                is Number -> toLong()
                is Timestamp -> toDate().time
                else -> null
            }
    }
}

/**
 * Stable conversation id from the two partner UIDs: sorted, "_"-joined. Both phones compute
 * the same value, which scopes every chat query without a lookup table.
 */
fun coupleIdOf(uidA: String, uidB: String): String = listOf(uidA, uidB).sorted().joinToString("_")
