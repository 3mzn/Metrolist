/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.metrolist.music.widget.PartnerTrackStatus
import com.metrolist.music.widget.PartnerWidgetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the Partner widget alive in both directions.
 *
 * **Read side:** listens to `status/{partnerUid}` and repaints the widget on every change.
 * **Write side** lives in [SongSharingRepository.updateMyStatus], called from MusicService on
 * song changes; this monitor only owns the listening half plus auth/identity lifecycle.
 *
 * Started once from [com.metrolist.music.App]; re-attaches its Firestore listener whenever the
 * resolved partner UID changes (login, account switch).
 */
@Singleton
class PartnerHeartbeatMonitor @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val partnerResolver: PartnerResolver,
    private val partnerWidgetManager: PartnerWidgetManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var statusRegistration: com.google.firebase.firestore.ListenerRegistration? = null

    init {
        FirebaseAuth.AuthStateListener { firebaseAuth ->
            if (firebaseAuth.currentUser == null) {
                stop()
            } else {
                start()
            }
        }.also { listener ->
            auth.addAuthStateListener(listener)
        }

        if (auth.currentUser != null) {
            start()
        }
    }

    private fun start() {
        scope.launch {
            // Wait (bounded) for identity to resolve, then watch the partner's status document.
            val partnerUid = partnerResolver.awaitPartnerUid() ?: run {
                Timber.tag(TAG).d("No partner resolvable — heartbeat read side idle")
                return@launch
            }
            if (_attachedTo == partnerUid) return@launch
            statusRegistration?.remove()

            Timber.tag(TAG).d("Watching status of $partnerUid")
            _attachedTo = partnerUid
            statusRegistration =
                firestore.collection("status")
                    .document(partnerUid)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            // Permission-denied (freshly deployed rules, revoked access…) cancels
                            // the registration PERMANENTLY. Re-attach after a delay so a rules fix
                            // propagates without needing an app restart.
                            Timber.tag(TAG).e(error, "status listener failed — retrying in 60s")
                            statusRegistration?.remove()
                            statusRegistration = null
                            _attachedTo = null
                            scope.launch {
                                kotlinx.coroutines.delay(60_000)
                                start()
                            }
                            return@addSnapshotListener
                        }
                        val doc = snapshot ?: return@addSnapshotListener
                        val partnerName = partnerResolver.identity.value.partnerName
                        if (!doc.exists()) {
                            // Partner stopped broadcasting — drop to the idle placeholder.
                            scope.launch {
                                partnerWidgetManager.writeCachedStatus(null, partnerName)
                            }
                            partnerWidgetManager.updateFromStatus(null, partnerName)
                            return@addSnapshotListener
                        }

                        val status = PartnerTrackStatus(
                            songId = doc.getString("songId").orEmpty(),
                            title = doc.getString("title").orEmpty(),
                            artist = doc.getString("artist").orEmpty(),
                            coverUrl = doc.getString("coverUrl"),
                            updatedAt = doc.getLong("updatedAt") ?: 0L,
                        )
                        // Snapshot callbacks run off-coroutine; suspend calls go through scope.
                        scope.launch {
                            partnerWidgetManager.writeCachedStatus(status, partnerName)
                            partnerWidgetManager.updateFromStatus(status, partnerName)
                        }
                    }
        }
    }

    private fun stop() {
        statusRegistration?.remove()
        statusRegistration = null
        _attachedTo = null
        scope.launch { partnerWidgetManager.writeCachedStatus(null, null) }
        partnerWidgetManager.updateFromStatus(null, null)
    }

    private var _attachedTo: String? = null

    companion object {
        private const val TAG = "PartnerHeartbeat"
    }
}
