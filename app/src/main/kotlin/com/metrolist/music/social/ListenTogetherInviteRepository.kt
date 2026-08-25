/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room discovery for feature #7: sending, observing, answering and cleaning up Listen
 * Together invites in the Firestore `invites` collection. One document per recipient
 * (`invites/{recipientUid}`) — a newer invite overwrites an older one for free.
 *
 * The actual room creation/joining stays in `ListenTogetherClient`/`ListenTogetherManager`;
 * this repository only moves the invitation between the two phones.
 */
@Singleton
class ListenTogetherInviteRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val partnerResolver: PartnerResolver,
    @ApplicationContext private val context: Context,
) {
    private val invitesCollection get() = firestore.collection("invites")
    private val TAG = "LTInvite"

    companion object {
        /**
         * Shared notification dedupe: the poll worker and InviteNotifier both record the
         * createdAt of the invite they last posted a system notification for, so the same
         * invite never notifies twice.
         */
        val LT_LAST_NOTIFIED_INVITE_CREATED_AT = longPreferencesKey("lt_last_notified_invite_created_at")

        private val OUTGOING_SENT_AT = longPreferencesKey("lt_outgoing_invite_sent_at")
    }

    init {
        // Reconcile the cached outgoing state with reality: if we believed an invite was in
        // flight but the doc is gone or no longer ours, it was declined/cancelled/superseded
        // while this device was dead. Clear the cache so the UI doesn't wait forever.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            reconcileOutgoingState()
        }
    }

    /**
     * Writes `invites/{partnerUid}` with [roomCode]. Overwrites any previous invite to the
     * partner (simultaneous invites: newest wins). Fails with a typed error when there is
     * no signed-in user, the partner is unresolved, or Firestore rejects the write.
     */
    suspend fun sendInvite(roomCode: String): Result<Unit> {
        val myUid = auth.currentUser?.uid
        if (myUid == null) {
            Timber.tag(TAG).w("sendInvite failed: not signed in")
            return Result.failure(IllegalStateException("Not signed in"))
        }

        var partnerUid = partnerResolver.awaitPartnerUid()
        if (partnerUid == null) {
            // The resolver's users-scan runs once per process; if it failed (transient error
            // at app start) or ran before the partner's profile doc existed, kick it and
            // wait once more before giving up.
            Timber.tag(TAG).w("sendInvite: partner unresolved, refreshing resolver and retrying")
            partnerResolver.refresh()
            partnerUid = partnerResolver.awaitPartnerUid(5_000)
        }
        if (partnerUid == null) {
            Timber.tag(TAG).w("sendInvite failed: partner not resolved after refresh")
            return Result.failure(IllegalStateException("Partner not resolved"))
        }
        if (partnerUid == myUid) {
            Timber.tag(TAG).w("sendInvite failed: partner resolved to SELF (bad users data)")
            return Result.failure(IllegalStateException("Partner resolved to self"))
        }

        val myName = partnerResolver.identity.value.myName
        if (myName == null) {
            Timber.tag(TAG).w("sendInvite failed: identity unresolved")
            return Result.failure(IllegalStateException("Identity not resolved"))
        }

        return try {
            invitesCollection.document(partnerUid).set(
                mapOf(
                    "roomCode" to roomCode,
                    "fromUid" to myUid,
                    "fromName" to myName,
                    "createdAt" to System.currentTimeMillis(),
                    "status" to ListenTogetherInvite.STATUS_PENDING,
                ),
            ).await()
            context.dataStore.edit { it[OUTGOING_SENT_AT] = System.currentTimeMillis() }
            Timber.tag(TAG).d("Invite sent to partner (%s)", partnerUid)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "sendInvite failed: Firestore write error")
            Result.failure(e)
        }
    }

    /**
     * The invite currently addressed TO this device, or null. Emits null for missing doc,
     * listener errors and signed-out state. Expiry is NOT filtered here — callers decide,
     * so "expired but present" states stay observable (the UI shows the expired toast).
     */
    fun observeIncomingInvite(): Flow<ListenTogetherInvite?> = callbackFlow {
        val myUid = auth.currentUser?.uid
        if (myUid == null) {
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }
        val registration = invitesCollection.document(myUid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                Timber.tag(TAG).e(error, "Incoming invite listener error")
                trySend(null)
                return@addSnapshotListener
            }
            val invite = snapshot
                ?.takeIf { it.exists() }
                ?.data
                ?.let { ListenTogetherInvite.fromMap(it) }
            trySend(invite)
        }
        awaitClose { registration.remove() }
    }

    /**
     * OUR invite sitting at the partner's doc (or null). Lets the sender see
     * accepted/declined/deleted in real time.
     *
     * Implemented as a QUERY (not a doc listener) on purpose: the rules deny sender reads
     * of a NONEXISTENT `invites/{partnerUid}` doc (resource.data errors), which is exactly
     * the "no pending invite" state we must observe. A `whereEqualTo(fromUid)` query is a
     * list operation — it only touches existing docs and is provably safe to the rules.
     */
    fun observeOutgoingInvite(): Flow<ListenTogetherInvite?> = callbackFlow {
        val myUid = auth.currentUser?.uid
        if (myUid == null) {
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }
        val registration = invitesCollection
            .whereEqualTo("fromUid", myUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Timber.tag(TAG).e(error, "Outgoing invite listener error")
                    trySend(null)
                    return@addSnapshotListener
                }
                val invite = snapshot?.documents
                    ?.firstOrNull()
                    ?.data
                    ?.let { ListenTogetherInvite.fromMap(it) }
                trySend(invite)
            }
        awaitClose { registration.remove() }
    }

    /**
     * Marks the incoming invite accepted (best-effort — the sender's toast reads this) and
     * deletes the doc. The caller performs the actual LT room join; a failed status write
     * must not block joining.
     */
    suspend fun acceptInvite(invite: ListenTogetherInvite) {
        val myUid = auth.currentUser?.uid ?: return
        try {
            invitesCollection.document(myUid)
                .update("status", ListenTogetherInvite.STATUS_ACCEPTED)
                .await()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not stamp invite accepted (continuing)")
        }
        try {
            invitesCollection.document(myUid).delete().await()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to delete accepted invite")
        }
    }

    /**
     * Marks the incoming invite declined so the sender's toast can say WHY it vanished,
     * then deletes the doc.
     */
    suspend fun declineInvite(invite: ListenTogetherInvite) {
        val myUid = auth.currentUser?.uid ?: return
        try {
            invitesCollection.document(myUid)
                .update("status", ListenTogetherInvite.STATUS_DECLINED)
                .await()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not stamp invite declined (continuing)")
        }
        try {
            invitesCollection.document(myUid).delete().await()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to delete declined invite")
        }
    }

    /** Sender-side cancel: removes our pending invite from the partner's doc. */
    suspend fun cancelInvite() {
        val myUid = auth.currentUser?.uid ?: return
        try {
            // Query, not get(): a get() on a nonexistent partner doc is denied by the
            // rules (resource.data errors), while the query only touches existing docs.
            val snapshot = invitesCollection
                .whereEqualTo("fromUid", myUid)
                .get()
                .await()
            snapshot.documents.forEach { doc ->
                doc.reference.delete().await()
            }
            if (!snapshot.isEmpty) {
                Timber.tag(TAG).d("Outgoing invite cancelled (%d doc(s))", snapshot.size())
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to cancel outgoing invite")
        } finally {
            context.dataStore.edit { it.remove(OUTGOING_SENT_AT) }
        }
    }

    /** Recipient-side cleanup: removes whatever is addressed to me. */
    suspend fun clearMyInvite() {
        val myUid = auth.currentUser?.uid ?: return
        try {
            invitesCollection.document(myUid).delete().await()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to clear my invite")
        }
    }

    /**
     * Opportunistic housekeeping: delete my incoming invite if expired, and our outgoing
     * invite if expired. Called on app open and by the poll worker — no TTL server-side.
     * Sender-side cleanup uses a query for the same rules reason as [cancelInvite].
     */
    suspend fun cleanupExpiredInvites() {
        val myUid = auth.currentUser?.uid ?: return

        try {
            val mine = invitesCollection.document(myUid).get().await()
            val invite = mine.takeIf { it.exists() }?.data?.let { ListenTogetherInvite.fromMap(it) }
            if (invite != null && invite.isExpired()) {
                mine.reference.delete().await()
                Timber.tag(TAG).d("Cleaned up expired incoming invite")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Incoming invite cleanup failed")
        }

        try {
            val snapshot = invitesCollection
                .whereEqualTo("fromUid", myUid)
                .get()
                .await()
            snapshot.documents.forEach { doc ->
                val invite = doc.data?.let { ListenTogetherInvite.fromMap(it) }
                if (invite != null && invite.isExpired()) {
                    doc.reference.delete().await()
                    context.dataStore.edit { it.remove(OUTGOING_SENT_AT) }
                    Timber.tag(TAG).d("Cleaned up expired outgoing invite")
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Outgoing invite cleanup failed")
        }
    }

    /** True when the DataStore cache says an invite of ours is still considered in flight. */
    suspend fun hasPendingOutgoingInvite(): Boolean =
        runCatching {
            context.dataStore.data.firstOrNull()?.contains(OUTGOING_SENT_AT) == true
        }.getOrDefault(false)

    private suspend fun reconcileOutgoingState() {
        val myUid = auth.currentUser?.uid ?: return
        if (!hasPendingOutgoingInvite()) return

        try {
            val snapshot = invitesCollection
                .whereEqualTo("fromUid", myUid)
                .get()
                .await()
            val invite = snapshot.documents.firstOrNull()?.data?.let { ListenTogetherInvite.fromMap(it) }
            val stillOursAndLive = invite != null && !invite.isExpired() && invite.isPending()
            if (!stillOursAndLive) {
                context.dataStore.edit { it.remove(OUTGOING_SENT_AT) }
                Timber.tag(TAG).d("Outgoing invite cache reconciled (doc gone or no longer live)")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Outgoing invite reconciliation failed; keeping cache")
        }
    }
}
