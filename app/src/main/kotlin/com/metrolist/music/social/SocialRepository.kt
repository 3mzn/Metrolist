/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

data class FriendRequest(
    val id: String,
    val fromUid: String,
    val toUid: String,
    val status: String,
)

data class RelationshipState(
    val outgoingRequests: Map<String, FriendRequest>, // key: toUid
    val incomingRequests: Map<String, FriendRequest>, // key: fromUid
    val friends: Set<String>, // uids
)

@Singleton
class SocialRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val sharedPlaylistRepository: SharedPlaylistRepository,
) {
    private val usersCollection get() = firestore.collection("users")
    private val friendRequestsCollection get() = firestore.collection("friendRequests")
    private val friendsCollection get() = firestore.collection("friends")

    fun getAllUsers(): Flow<List<UserProfile>> = callbackFlow {
        val registration =
            usersCollection.addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                val currentUid = auth.currentUser?.uid
                val users =
                    snapshot.documents.mapNotNull { doc ->
                        val uid = doc.id
                        if (currentUid != null && uid == currentUid) return@mapNotNull null
                        UserProfile(
                            uid = uid,
                            email = doc.getString("email") ?: "",
                            username = doc.getString("username") ?: "",
                            photoUrl = doc.getString("photoUrl"),
                        )
                    }
                trySend(users)
            }
        awaitClose { registration.remove() }
    }

    fun observeRelationships(): Flow<RelationshipState> = callbackFlow {
        val currentUid = auth.currentUser?.uid
        if (currentUid == null) {
            trySend(RelationshipState(emptyMap(), emptyMap(), emptySet()))
            awaitClose { }
            return@callbackFlow
        }

        var incomingRegistration: com.google.firebase.firestore.ListenerRegistration? = null
        var friendsRegistration: com.google.firebase.firestore.ListenerRegistration? = null

        val requestsRegistration =
            friendRequestsCollection
                .whereEqualTo("fromUid", currentUid)
                .addSnapshotListener { outgoingSnapshot, _ ->
                    val outgoing =
                        outgoingSnapshot?.documents.orEmpty()
                            .associate { doc ->
                                val toUid = doc.getString("toUid") ?: ""
                                toUid to
                                    FriendRequest(
                                        id = doc.id,
                                        fromUid = doc.getString("fromUid") ?: "",
                                        toUid = toUid,
                                        status = doc.getString("status") ?: "pending",
                                    )
                            }

                    incomingRegistration?.remove()
                    incomingRegistration =
                        friendRequestsCollection
                            .whereEqualTo("toUid", currentUid)
                            .addSnapshotListener { incomingSnapshot, _ ->
                                val incoming =
                                    incomingSnapshot?.documents.orEmpty()
                                        .associate { doc ->
                                            val fromUid = doc.getString("fromUid") ?: ""
                                            fromUid to
                                                FriendRequest(
                                                    id = doc.id,
                                                    fromUid = fromUid,
                                                    toUid = doc.getString("toUid") ?: "",
                                                    status = doc.getString("status") ?: "pending",
                                                )
                                        }

                                friendsRegistration?.remove()
                                friendsRegistration =
                                    friendsCollection
                                        .whereArrayContains("members", currentUid)
                                        .addSnapshotListener { friendsSnapshot, _ ->
                                            val friends =
                                                friendsSnapshot?.documents.orEmpty()
                                                    .mapNotNull { doc ->
                                                        val members = doc.get("members") as? List<*>
                                                        members?.firstOrNull { it != currentUid } as? String
                                                    }
                                                    .toSet()

                                            trySend(
                                                RelationshipState(
                                                    outgoingRequests = outgoing,
                                                    incomingRequests = incoming,
                                                    friends = friends,
                                                ),
                                            )
                                        }
                            }
                }

        awaitClose {
            requestsRegistration.remove()
            incomingRegistration?.remove()
            friendsRegistration?.remove()
        }
    }

    fun sendFriendRequest(toUid: String, onResult: (Boolean, String?) -> Unit) {
        val currentUid = auth.currentUser?.uid
        if (currentUid == null) {
            onResult(false, "Not logged in")
            return
        }
        if (currentUid == toUid) {
            onResult(false, "You cannot add yourself")
            return
        }

        // Avoid duplicate pending requests
        friendRequestsCollection
            .whereEqualTo("fromUid", currentUid)
            .whereEqualTo("toUid", toUid)
            .whereEqualTo("status", "pending")
            .get()
            .addOnSuccessListener { existing ->
                if (!existing.isEmpty) {
                    onResult(false, "Request already sent")
                    return@addOnSuccessListener
                }

                val data =
                    mapOf(
                        "fromUid" to currentUid,
                        "toUid" to toUid,
                        "status" to "pending",
                        "createdAt" to com.google.firebase.Timestamp.now(),
                    )
                friendRequestsCollection
                    .add(data)
                    .addOnSuccessListener { onResult(true, null) }
                    .addOnFailureListener { e ->
                        onResult(false, e.message ?: "Failed to send request")
                    }
            }
            .addOnFailureListener { e ->
                onResult(false, e.message ?: "Failed to send request")
            }
    }

    fun acceptFriendRequest(request: FriendRequest, onResult: (Boolean, String?) -> Unit) {
        val currentUid = auth.currentUser?.uid
        if (currentUid == null || currentUid != request.toUid) {
            onResult(false, "Not allowed")
            return
        }

        val members = listOf(request.fromUid, request.toUid).sorted()
        val pairKey = "${members[0]}_${members[1]}"

        firestore.runBatch { batch ->
            val requestRef = friendRequestsCollection.document(request.id)
            batch.update(requestRef, "status", "accepted")

            val friendsRef = friendsCollection.document(pairKey)
            batch.set(
                friendsRef,
                mapOf(
                    "members" to members,
                    "createdAt" to com.google.firebase.Timestamp.now(),
                ),
            )
        }.addOnSuccessListener {
            onResult(true, null)
        }.addOnFailureListener { e ->
            onResult(false, e.message ?: "Failed to accept request")
        }
    }

    fun rejectFriendRequest(requestId: String, onResult: (Boolean, String?) -> Unit) {
        val currentUid = auth.currentUser?.uid
        if (currentUid == null) {
            onResult(false, "Not logged in")
            return
        }

        val requestRef = friendRequestsCollection.document(requestId)
        requestRef
            .update("status", "rejected")
            .addOnSuccessListener { onResult(true, null) }
            .addOnFailureListener { e ->
                onResult(false, e.message ?: "Failed to reject request")
            }
    }

    fun removeFriend(otherUid: String, onResult: (Boolean, String?) -> Unit) {
        val currentUid = auth.currentUser?.uid
        if (currentUid == null) {
            onResult(false, "Not logged in")
            return
        }

        val members = listOf(currentUid, otherUid).sorted()
        val pairKey = "${members[0]}_${members[1]}"
        friendsCollection
            .document(pairKey)
            .delete()
            .addOnSuccessListener { onResult(true, null) }
            .addOnFailureListener { e ->
                onResult(false, e.message ?: "Failed to remove friend")
            }
    }

    /**
     * Wipe every cloud document this account owns, as part of account deletion.
     *
     * Removes the profile doc, both friendship edges and all friend requests involving the user.
     * Sent songs are deliberately left orphaned: their rules forbid client deletes, and in a
     * two-person build the leftovers are invisible to everyone except the partner.
     */
    suspend fun wipeMyCloudData(uid: String) {
        usersCollection.document(uid).delete().await()

        friendsCollection
            .whereArrayContains("members", uid)
            .get()
            .await()
            .documents
            .forEach { it.reference.delete().await() }

        friendRequestsCollection
            .whereEqualTo("fromUid", uid)
            .get()
            .await()
            .documents
            .forEach { it.reference.delete().await() }

        friendRequestsCollection
            .whereEqualTo("toUid", uid)
            .get()
            .await()
            .documents
            .forEach { it.reference.delete().await() }

        // SPEC_8 D8: wipe shared playlist cloud docs and leave a partners_deleted tombstone
        // so the surviving phone promotes its local copies instead of deleting them.
        sharedPlaylistRepository.clearAllCloudForUid(uid)
    }
}
