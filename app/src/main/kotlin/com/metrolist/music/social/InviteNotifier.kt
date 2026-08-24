/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.ListenTogetherAutoApproveSuggestionsKey
import com.metrolist.music.listentogether.ListenTogetherEvent
import com.metrolist.music.listentogether.ListenTogetherManager
import com.metrolist.music.utils.SongNotificationHelper
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide LT-invite controller (SPEC_7). Two responsibilities:
 *
 * 1. DELIVERY (D13) — the Firestore listener is the seconds-fast path while this process
 *    is alive: foreground -> [bannerInvite] (in-app banner + tab badge), backgrounded ->
 *    system notification, foreground->background transition -> immediate notification.
 *    [InvitePollWorker] is only the dead-process safety net.
 *
 * 2. ACTIONS — the single UI surface for sending/cancelling/declining/accepting invites,
 *    including the full join-via-invite flow (D6 stale cleanup, D8 auto-approve, D12
 *    mutual-invite cancellation). Centralized here because the joiner may tap Join from
 *    the banner on ANY tab, where no LT screen exists to observe the socket events.
 */
@Singleton
class InviteNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inviteRepository: ListenTogetherInviteRepository,
    private val partnerResolver: PartnerResolver,
    private val listenTogetherManager: ListenTogetherManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _bannerInvite = MutableStateFlow<ListenTogetherInvite?>(null)

    /** Non-null while a live pending invite should show the in-app banner. */
    val bannerInvite: StateFlow<ListenTogetherInvite?> = _bannerInvite.asStateFlow()

    private val _outgoingInvite = MutableStateFlow<ListenTogetherInvite?>(null)

    /** OUR invite at the partner's doc; status flips drive the waiting UI and host toasts. */
    val outgoingInvite: StateFlow<ListenTogetherInvite?> = _outgoingInvite.asStateFlow()

    val partnerIdentity: StateFlow<PartnerIdentity> = partnerResolver.identity

    // The live incoming invite as of the latest emission. StateFlow (not a plain field)
    // because the Firestore collector and the foreground-transition collector can update
    // and read it concurrently.
    private val currentInvite = MutableStateFlow<ListenTogetherInvite?>(null)

    private var lastNotifiedCreatedAt: Long? = null
    private var expiryJob: Job? = null

    // Guards against double-taps racing two joinFromInvite flows (both would wait on the
    // same socket events; the loser would time out with a spurious failure toast).
    private val joinInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    // Callbacks run on the main thread: callers touch Compose state, navigate, and toast.
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        scope.launch {
            inviteRepository.observeIncomingInvite().collect { invite ->
                currentInvite.value = invite?.takeIf { it.isPending() && !it.isExpired() }
            }
        }

        scope.launch {
            inviteRepository.observeOutgoingInvite().collect { invite ->
                // Deliberately NOT filtering by status: the accepted/declined flips are the
                // whole point of this flow (host-side toasts). Only expiry hides a doc.
                _outgoingInvite.value = invite?.takeIf { !it.isExpired() }
            }
        }

        // Re-evaluate delivery on EVERY foreground transition, both directions:
        //  - backgrounding with an unanswered invite -> notification fires immediately
        //  - foregrounding (notification tap or launcher) -> banner takes over and the
        //    shade notification is retracted; no Firestore emission fires for a transition.
        scope.launch {
            AppForegroundTracker.isForegroundFlow.collect {
                reevaluateDelivery()
            }
        }

        scope.launch {
            currentInvite.collect { invite ->
                reevaluateDelivery()
                scheduleExpiryCheck(invite)
            }
        }
    }

    // ---------------------------------------------------------------- delivery

    private suspend fun reevaluateDelivery() {
        // Re-validate at evaluation time, not just emission time: an invite can expire
        // while sitting in currentInvite with no new Firestore emission (the doc doesn't
        // change), and backgrounding after that must not notify about a dead invite.
        val invite = currentInvite.value?.takeIf { it.isPending() && !it.isExpired() }
        if (currentInvite.value != invite) {
            currentInvite.value = invite
            if (invite == null) return // the currentInvite collector re-evaluates
        }

        if (invite == null) {
            _bannerInvite.value = null
            SongNotificationHelper.cancelInviteNotification(context)
            return
        }

        if (AppForegroundTracker.isForeground) {
            _bannerInvite.value = invite
            // The banner owns delivery now; don't leave a tap target in the shade.
            SongNotificationHelper.cancelInviteNotification(context)
        } else {
            _bannerInvite.value = null
            postNotification(invite)
        }
    }

    private fun scheduleExpiryCheck(invite: ListenTogetherInvite?) {
        expiryJob?.cancel()
        expiryJob = null
        if (invite == null) return
        val remaining = invite.createdAt + ListenTogetherInvite.EXPIRY_MS - System.currentTimeMillis()
        if (remaining <= 0) {
            currentInvite.value = null
            return
        }
        expiryJob = scope.launch {
            delay(remaining + 1_000) // small grace so we never clear a hair early
            // Re-validate: the invite may have been replaced by a newer one meanwhile.
            if (currentInvite.value?.createdAt == invite.createdAt) {
                currentInvite.value = null
            }
        }
    }

    private suspend fun postNotification(invite: ListenTogetherInvite) {
        // Same dedupe key as InvitePollWorker: one system notification per invite, ever.
        // The DataStore check (not just the in-memory cache) matters: the process can be
        // restarted with the same invite still live, which resets the memory but not this.
        if (lastNotifiedCreatedAt == invite.createdAt) return
        val alreadyNotified = runCatching {
            context.dataStore.data.first()[ListenTogetherInviteRepository.LT_LAST_NOTIFIED_INVITE_CREATED_AT]
        }.getOrNull() == invite.createdAt
        if (alreadyNotified) {
            lastNotifiedCreatedAt = invite.createdAt
            return
        }
        lastNotifiedCreatedAt = invite.createdAt
        runCatching {
            context.dataStore.edit {
                it[ListenTogetherInviteRepository.LT_LAST_NOTIFIED_INVITE_CREATED_AT] = invite.createdAt
            }
        }
        SongNotificationHelper.showInviteNotification(context, invite.fromName)
    }

    // ---------------------------------------------------------------- actions

    /**
     * The full join-via-invite flow, callable from ANY tab (banner) or the LT screen.
     * D6: cleans up any stale/in-progress session first. The invite is only stamped
     * accepted after the server actually approves the join (D11 retry: a failed join
     * leaves the invite alive). D12: accepting cancels our own outgoing invite.
     * D8: forces suggestion auto-approve ON so both participants add songs freely.
     *
     * [onJoined] runs on success (UI navigates to the LT screen).
     * [onFailed] runs on timeout/rejection (invite survives; UI toasts).
     */
    fun joinFromInvite(
        invite: ListenTogetherInvite,
        onJoined: () -> Unit,
        onFailed: (rejected: Boolean) -> Unit,
    ) {
        if (!joinInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                val myName = partnerResolver.identity.value.myName ?: "guest"
                if (listenTogetherManager.isInRoom) {
                    listenTogetherManager.leaveRoom()
                }
                listenTogetherManager.connect()
                listenTogetherManager.joinRoom(invite.roomCode, myName)

                val outcome = withTimeoutOrNull(20_000) {
                    listenTogetherManager.events.first {
                        it is ListenTogetherEvent.JoinApproved || it is ListenTogetherEvent.JoinRejected
                    }
                }

                when (outcome) {
                    is ListenTogetherEvent.JoinApproved -> {
                        inviteRepository.acceptInvite(invite)
                        inviteRepository.cancelInvite()
                        forceAutoApproveOn()
                        mainHandler.post { onJoined() }
                    }

                    is ListenTogetherEvent.JoinRejected ->
                        mainHandler.post { onFailed(true) }

                    else -> mainHandler.post { onFailed(false) } // timeout; invite survives
                }
            } finally {
                joinInFlight.set(false)
            }
        }
    }

    /**
     * D8: suggestion auto-approve is forced ON for invite sessions so BOTH participants can
     * add songs to the queue without approval prompts. Only the host's setting is
     * functionally relevant, but setting it on both is harmless and keeps them in sync.
     */
    suspend fun forceAutoApproveOn() {
        runCatching {
            context.dataStore.edit { it[ListenTogetherAutoApproveSuggestionsKey] = true }
        }
    }

    /** Sender side of the flow: called by the LT screen once RoomCreated arrives. */
    suspend fun sendInvite(roomCode: String): Result<Unit> = inviteRepository.sendInvite(roomCode)

    fun cancelInvite() {
        scope.launch { inviteRepository.cancelInvite() }
    }

    fun declineInvite(invite: ListenTogetherInvite) {
        scope.launch { inviteRepository.declineInvite(invite) }
    }

    /**
     * D12, other direction: OUR outgoing invite was accepted -> drop any incoming banner,
     * because we are now in a session together.
     */
    fun onOutgoingAccepted() {
        scope.launch { inviteRepository.clearMyInvite() }
    }
}
