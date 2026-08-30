/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ltchat

import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.metrolist.music.constants.LtChatAutoDeleteDaysKey
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firestore-backed repository for the couple chat (SPEC_LT_CHAT).
 *
 * Cloud model:
 *  - `lt_chat_messages/{autoId}` — one doc per message, scoped by [coupleIdOf] with a
 *    denormalized `member_uids` array so the rules can prove membership per document.
 *  - `lt_chat_presence/{coupleId}_{userUid}` — deterministic per-user presence doc (typing
 *    flag, last_seen heartbeat, last_read_at for read receipts). Upserted with merge.
 *
 * Realtime pattern matches ListenTogetherInviteRepository / SharedPlaylistRepository:
 * authUidFlow().flatMapLatest { callbackFlow { addSnapshotListener } } — listeners are
 * (re-)attached per uid because Firebase Auth restores its session asynchronously on cold
 * start (a listener attached with a null uid would never attach at all).
 */
@Singleton
class LtChatRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    @ApplicationContext private val context: Context,
) {
    private val messagesCollection get() = firestore.collection("lt_chat_messages")
    private val presenceCollection get() = firestore.collection("lt_chat_presence")
    private val TAG = "LtChatRepo"

    companion object {
        /** History window per query — a two-person chat never needs more. */
        private const val MESSAGE_HISTORY_LIMIT = 300L

        /** Firestore batch limit is 500; stay under it for the retention prune. */
        private const val PRUNE_BATCH_SIZE = 400

        /** Default retention window (days); 0 disables auto-delete. */
        const val DEFAULT_AUTO_DELETE_DAYS = 30

        fun presenceDocId(coupleId: String, userUid: String) = "${coupleId}_$userUid"
    }

    /** The signed-in UID as a flow; see class doc for why listeners key off this. */
    private fun authUidFlow(): Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { auth -> trySend(auth.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        trySend(auth.currentUser?.uid)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    /**
     * Live message history for the couple, NEWEST FIRST (matches the query's created_at DESC
     * order; the UI's LazyColumn uses reverseLayout so index 0 renders at the bottom). Local
     * pending writes appear in their correct position via Firestore latency compensation.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeMessages(coupleId: String): Flow<List<LtChatMessage>> =
        authUidFlow().flatMapLatest { myUid ->
            if (myUid == null || coupleId.isBlank()) {
                flowOf(emptyList())
            } else {
                callbackFlow {
                    val registration = messagesCollection
                        .whereEqualTo("couple_id", coupleId)
                        .whereArrayContains("member_uids", myUid)
                        .orderBy("created_at", Query.Direction.DESCENDING)
                        .limit(MESSAGE_HISTORY_LIMIT)
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Timber.tag(TAG).e(error, "Chat messages listener error")
                                trySend(emptyList())
                                return@addSnapshotListener
                            }
                            val messages = snapshot?.documents
                                ?.mapNotNull { doc -> doc.data?.let { LtChatMessage.fromMap(doc.id, it) } }
                                .orEmpty()
                            trySend(messages)
                        }
                    awaitClose { registration.remove() }
                }
            }
        }

    /**
     * Live presence (typing flag + read marker) for [userUid]'s doc, or null when the doc
     * doesn't exist yet (e.g. the partner never opened the chat).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observePresence(coupleId: String, userUid: String): Flow<LtChatPresence?> =
        authUidFlow().flatMapLatest { myUid ->
            if (myUid == null || coupleId.isBlank() || userUid.isBlank()) {
                flowOf(null)
            } else {
                callbackFlow<LtChatPresence?> {
                    val registration = presenceCollection
                        .document(presenceDocId(coupleId, userUid))
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Timber.tag(TAG).e(error, "Presence listener error for %s", userUid)
                                trySend(null)
                                return@addSnapshotListener
                            }
                            val presence = snapshot
                                ?.takeIf { it.exists() }
                                ?.data
                                ?.let { LtChatPresence.fromMap(it) }
                            trySend(presence)
                        }
                    awaitClose { registration.remove() }
                }
            }
        }.distinctUntilChanged()

    /**
     * Sends a text/emoji message. The doc is immutable after create (rules deny update), and
     * the quoted message is denormalized so the preview survives retention pruning.
     */
    suspend fun sendMessage(
        coupleId: String,
        senderUid: String,
        senderName: String,
        text: String,
        replyTo: LtChatMessage? = null,
    ): Result<Unit> {
        if (coupleId.isBlank()) return Result.failure(IllegalStateException("Couple not resolved"))
        if (text.isBlank()) return Result.failure(IllegalArgumentException("Empty message"))
        if (text.length > 1000) return Result.failure(IllegalArgumentException("Message too long"))
        val memberUids = coupleId.split("_").filter { it.isNotBlank() }
        if (senderUid !in memberUids) {
            return Result.failure(IllegalStateException("Sender not part of the couple"))
        }

        val addResult = try {
            messagesCollection.add(
                mapOf(
                    "couple_id" to coupleId,
                    "member_uids" to memberUids,
                    "sender_uid" to senderUid,
                    "sender_name" to senderName,
                    "sender_avatar" to null,
                    "text" to text,
                    "reply_to" to replyTo?.id,
                    "reply_text" to replyTo?.text,
                    "reply_sender_name" to replyTo?.senderName,
                    "created_at" to FieldValue.serverTimestamp(),
                    "type" to messageTypeFor(text),
                ),
            ).await()
            Timber.tag(TAG).d("Message sent to couple %s", coupleId)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to send chat message")
            Result.failure<Unit>(e)
        }
        // Best-effort: clearing typing must not flip a successful send to failure.
        if (addResult.isSuccess) {
            try {
                setTyping(coupleId, senderUid, false)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Clear typing after send failed")
            }
        }
        return addResult
    }

    /**
     * Upserts MY presence doc. Merge keeps `last_read_at` intact so typing transitions never
     * clobber the read marker.
     */
    suspend fun setTyping(coupleId: String, userUid: String, typing: Boolean) {
        upsertPresence(coupleId, userUid, typing)
    }

    /**
     * Marks the chat as read up to NOW: my presence doc's `last_read_at` moves forward, which
     * simultaneously (a) clears my unread badge baseline and (b) flips the partner's receipts.
     */
    suspend fun markRead(coupleId: String, userUid: String) {
        upsertPresence(coupleId, userUid, typing = false, markRead = true)
    }

    private suspend fun upsertPresence(
        coupleId: String,
        userUid: String,
        typing: Boolean,
        markRead: Boolean = false,
    ) {
        if (coupleId.isBlank() || userUid.isBlank()) return
        val memberUids = coupleId.split("_").filter { it.isNotBlank() }
        if (userUid !in memberUids) return
        try {
            val map = mutableMapOf<String, Any?>(
                "couple_id" to coupleId,
                "member_uids" to memberUids,
                "user_uid" to userUid,
                "is_typing" to typing,
                "last_seen" to FieldValue.serverTimestamp(),
            )
            if (markRead) map["last_read_at"] = FieldValue.serverTimestamp()
            presenceCollection
                .document(presenceDocId(coupleId, userUid))
                .set(map, SetOptions.merge())
                .await()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Presence update failed (typing=%s read=%s)", typing, markRead)
        }
    }

    /**
     * Client-side retention: deletes messages older than [days] (0 = never). Runs in the
     * foreground when the chat opens — no Cloud Functions on the free tier.
     */
    suspend fun pruneOldMessages(coupleId: String, days: Int) {
        if (coupleId.isBlank() || days <= 0) return
        val myUid = auth.currentUser?.uid ?: return
        withContext(Dispatchers.IO) {
            try {
                val cutoff = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000L
                val cutoffTs = Timestamp(java.util.Date(cutoff))
                var totalDeleted = 0
                while (true) {
                    val snapshot = messagesCollection
                        .whereEqualTo("couple_id", coupleId)
                        .whereArrayContains("member_uids", myUid)
                        .whereLessThan("created_at", cutoffTs)
                        .orderBy("created_at", Query.Direction.ASCENDING)
                        .limit(PRUNE_BATCH_SIZE.toLong())
                        .get()
                        .await()
                    if (snapshot.isEmpty) break
                    val batch = firestore.batch()
                    snapshot.documents.forEach { doc -> batch.delete(doc.reference) }
                    batch.commit().await()
                    totalDeleted += snapshot.size()
                    // Last page partial → done.
                    if (snapshot.size() < PRUNE_BATCH_SIZE) break
                }
                if (totalDeleted > 0) {
                    Timber.tag(TAG).d("Pruned %d chat messages older than %d days", totalDeleted, days)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Chat message pruning failed")
            }
        }
    }

    /** The configured retention window in days (0 = keep forever). */
    suspend fun autoDeleteDays(): Int =
        runCatching {
            context.dataStore.data.first()[LtChatAutoDeleteDaysKey] ?: DEFAULT_AUTO_DELETE_DAYS
        }.getOrDefault(DEFAULT_AUTO_DELETE_DAYS)

    /**
     * "emoji" when the text carries no letters or digits and contains an emoji codepoint;
     * punctuation-only stays "text". "system" is reserved for future server-side notices.
     */
    private fun messageTypeFor(text: String): String {
        if (text.any { it.isLetterOrDigit() }) return LtChatMessage.TYPE_TEXT
        if (text.any { it.code > 127 }) return LtChatMessage.TYPE_EMOJI
        return LtChatMessage.TYPE_TEXT
    }
}
