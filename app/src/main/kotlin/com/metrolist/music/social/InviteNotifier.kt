/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.metrolist.music.utils.SongNotificationHelper
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live LT-invite delivery while this process is alive (SPEC_7 D13).
 *
 * Delivery channel follows app state:
 *  - foreground  -> [bannerInvite] (the app-wide banner + LT tab badge, Phase 3 UI)
 *  - backgrounded -> system notification, tapping it opens the join UI directly (no banner)
 *  - foreground -> background transition with an unanswered invite -> notification fires
 *    immediately (no waiting for the 15-min poll)
 *
 * The poll worker only covers a fully dead process; this class is the seconds-fast path.
 */
@Singleton
class InviteNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inviteRepository: ListenTogetherInviteRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _bannerInvite = MutableStateFlow<ListenTogetherInvite?>(null)

    /** Non-null while a live pending invite should show the in-app banner. */
    val bannerInvite: StateFlow<ListenTogetherInvite?> = _bannerInvite.asStateFlow()

    // The live pending invite as of the latest emission, if any. Kept across emissions so
    // foreground/background transitions can re-evaluate delivery without a new doc change.
    private var currentInvite: ListenTogetherInvite? = null

    private var lastNotifiedCreatedAt: Long? = null

    fun start() {
        scope.launch {
            // Re-evaluate delivery on EVERY foreground transition, both directions:
            //  - backgrounding with an unanswered invite -> notification fires immediately
            //  - foregrounding (e.g. via the notification tap or the launcher) -> the
            //    banner takes over and the shade notification is retracted, even though no
            //    Firestore emission will fire for the transition.
            launch {
                var wasForeground = AppForegroundTracker.isForeground
                AppForegroundTracker.isForegroundFlow.collect { foreground ->
                    val transitioned = wasForeground != foreground
                    wasForeground = foreground
                    if (transitioned) {
                        reevaluateDelivery()
                    }
                }
            }

            inviteRepository.observeIncomingInvite().collect { invite ->
                currentInvite = invite?.takeIf { it.isPending() && !it.isExpired() }
                reevaluateDelivery()
            }
        }
    }

    private suspend fun reevaluateDelivery() {
        // Re-validate at evaluation time, not just emission time: an invite can expire
        // while sitting in currentInvite with no new Firestore emission (the doc doesn't
        // change), and backgrounding after that must not notify about a dead invite.
        val invite = currentInvite?.takeIf { it.isPending() && !it.isExpired() }
        currentInvite = invite
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
}
