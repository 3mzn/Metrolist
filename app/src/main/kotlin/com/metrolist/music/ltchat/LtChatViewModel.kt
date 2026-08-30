/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ltchat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.metrolist.music.constants.LtChatAutoDeleteDaysKey
import com.metrolist.music.constants.LtChatLastPruneMsKey
import com.metrolist.music.social.PartnerIdentity
import com.metrolist.music.social.PartnerResolver
import com.metrolist.music.utils.dataStore
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * State holder for the couple chat (SPEC_LT_CHAT). Everything keys off the resolved couple
 * (my UID + partner UID from [PartnerResolver]); while either side is unresolved the flows
 * sit at their empty defaults and the UI hides the bubble.
 */
@HiltViewModel
class LtChatViewModel @Inject constructor(
    private val repository: LtChatRepository,
    private val partnerResolver: PartnerResolver,
    private val auth: FirebaseAuth,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private companion object {
        /** Typing indicator auto-clears this long after the last keystroke. */
        const val TYPING_DEBOUNCE_MS = 3_000L
    }

    val partnerIdentity: StateFlow<PartnerIdentity> = partnerResolver.identity

    private val authUidFlow: kotlinx.coroutines.flow.Flow<String?> = kotlinx.coroutines.flow.callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { fa -> trySend(fa.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        trySend(auth.currentUser?.uid)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    /** The signed-in UID, observable — used by the UI to split mine vs partner bubbles. */
    val myUid: StateFlow<String?> = authUidFlow.stateIn(viewModelScope, SharingStarted.Eagerly, auth.currentUser?.uid)

    /** couple_id for the signed-in pair, or null while logged out / partner unresolved. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val coupleIdFlow = combine(partnerIdentity, myUid) { identity, uid ->
        uid?.let { identity.partnerUid?.let { p -> coupleIdOf(it, p) } }
    }.distinctUntilChanged()

    /** Messages NEWEST FIRST (index 0 = newest; the chat list uses reverseLayout). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<LtChatMessage>> = coupleIdFlow
        .flatMapLatest { coupleId ->
            if (coupleId == null) flowOf(emptyList()) else repository.observeMessages(coupleId)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** The partner's presence doc: typing indicator + their read marker for my receipts. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val partnerPresence: StateFlow<LtChatPresence?> = coupleIdFlow
        .flatMapLatest { coupleId ->
            val partnerUid = partnerIdentity.value.partnerUid
            if (coupleId == null || partnerUid == null) {
                flowOf(null)
            } else {
                repository.observePresence(coupleId, partnerUid)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** MY presence doc: my last_read_at is the unread-count baseline. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val myPresence: StateFlow<LtChatPresence?> = coupleIdFlow
        .flatMapLatest { coupleId ->
            val myUid = myUidOrNull()
            if (coupleId == null || myUid == null) {
                flowOf(null)
            } else {
                repository.observePresence(coupleId, myUid)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** Messages from the partner I haven't seen yet — drives the bubble badge. */
    val unreadCount: StateFlow<Int> = combine(messages, myPresence) { list, mine ->
        val baseline = mine?.lastReadAtMs ?: 0L
        val myUid = myUidOrNull()
        list.count { it.senderUid != myUid && it.createdAtMs != 0L && it.createdAtMs > baseline }
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

    private var currentCoupleId: String? = null

    init {
        viewModelScope.launch {
            coupleIdFlow.collect { currentCoupleId = it }
        }
    }

    fun isMessageRead(message: LtChatMessage): Boolean {
        val partnerReadAt = partnerPresence.value?.lastReadAtMs ?: 0L
        return message.createdAtMs != 0L && message.createdAtMs <= partnerReadAt
    }

    fun send(text: String, replyTo: LtChatMessage?) {
        val myUid = myUidOrNull() ?: return
        val coupleId = currentCoupleId ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > 1000) return
        viewModelScope.launch {
            val result = repository.sendMessage(
                coupleId = coupleId,
                senderUid = myUid,
                senderName = partnerResolver.identity.value.myName ?: "me",
                text = trimmed,
                replyTo = replyTo,
            )
            result.onFailure {
                Timber.tag("LtChatVM").e(it, "send failed")
            }
        }
    }

    /** Keystroke hook: flips typing on (once), auto-clears [TYPING_DEBOUNCE_MS] after the last. */
    fun onInputChanged() {
        val myUid = myUidOrNull() ?: return
        val coupleId = currentCoupleId ?: return
        if (!isTyping) {
            isTyping = true
            viewModelScope.launch { repository.setTyping(coupleId, myUid, true) }
        }
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            delay(TYPING_DEBOUNCE_MS)
            isTyping = false
            repository.setTyping(coupleId, myUid, false)
        }
    }

    private var markReadJob: Job? = null
    private var lastMarkedId: String? = null

    /** Called when the chat box opens and whenever new messages arrive while it's open. */
    fun markChatOpened() {
        val myUid = myUidOrNull() ?: return
        val coupleId = currentCoupleId ?: return
        val latestId = messages.value.firstOrNull()?.id
        if (latestId != null && latestId == lastMarkedId) return
        markReadJob?.cancel()
        markReadJob = viewModelScope.launch {
            delay(300)
            lastMarkedId = messages.value.firstOrNull()?.id
            repository.markRead(coupleId, myUid)
        }
    }

    /** Retention prune, honoring the user's auto-delete setting (0 = never). */
    fun pruneOldMessages() {
        val coupleId = currentCoupleId ?: return
        viewModelScope.launch {
            // Throttle to once/hour per client.
            val now = System.currentTimeMillis()
            val lastPrune = runCatching { context.dataStore.data.first()[LtChatLastPruneMsKey] ?: 0L }
                .getOrDefault(0L)
            if (now - lastPrune < 60 * 60 * 1000L) return@launch
            val days = runCatching {
                context.dataStore.data.first()[LtChatAutoDeleteDaysKey]
                    ?: LtChatRepository.DEFAULT_AUTO_DELETE_DAYS
            }.getOrDefault(LtChatRepository.DEFAULT_AUTO_DELETE_DAYS)
            repository.pruneOldMessages(coupleId, days)
            runCatching { context.dataStore.edit { it[LtChatLastPruneMsKey] = now } }
        }
    }

    private fun myUidOrNull(): String? = auth.currentUser?.uid

    private var typingJob: Job? = null
    private var isTyping = false

    override fun onCleared() {
        super.onCleared()
        val coupleId = currentCoupleId ?: return
        val myUid = myUidOrNull() ?: return
        // Best-effort clear typing — ViewModel is being destroyed (tab left / process).
        // Use repository directly without viewModelScope (already cancelled).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                repository.setTyping(coupleId, myUid, false)
            } catch (_: Exception) {}
        }
    }
}
