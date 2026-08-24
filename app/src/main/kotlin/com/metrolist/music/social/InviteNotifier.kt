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

    private var lastNotifiedCreatedAt: Long? = null

    fun start() {
        scope.launch {
            // Track foreground transitions so a banner that was up when the user left the
            // app converts into a notification right away.
            launch {
                var wasForeground = AppForegroundTracker.isForeground
                AppForegroundTracker.isForegroundFlow.collect { foreground ->
                    val becameBackground = wasForeground && !foreground
                    wasForeground = foreground
                    val invite = _bannerInvite.value
                    if (becameBackground && invite != null) {
                        postNotification(invite)
                    }
                }
            }

            inviteRepository.observeIncomingInvite().collect { invite ->
                val live = invite != null && invite.isPending() && !invite.isExpired()
                if (!live) {
                    _bannerInvite.value = null
                    return@collect
                }

                if (AppForegroundTracker.isForeground) {
                    _bannerInvite.value = invite
                } else {
                    _bannerInvite.value = null
                    postNotification(invite)
                }
            }
        }
    }

    private suspend fun postNotification(invite: ListenTogetherInvite) {
        // Same dedupe key as InvitePollWorker: one system notification per invite, ever.
        if (lastNotifiedCreatedAt == invite.createdAt) return
        lastNotifiedCreatedAt = invite.createdAt
        runCatching {
            context.dataStore.edit {
                it[ListenTogetherInviteRepository.LT_LAST_NOTIFIED_INVITE_CREATED_AT] = invite.createdAt
            }
        }
        SongNotificationHelper.showInviteNotification(context, invite.fromName)
    }
}
