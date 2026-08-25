/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who am I, and who is my partner?
 *
 * Identity is a pure function of the account email — no usernames, no friend graphs:
 *  - email contains "eman"   -> this is [HANDLE_EMAN]
 *  - email contains "test"   -> this is [HANDLE_ASWINI_TEST] (testing stand-in for aswini)
 *  - email contains "sylesh" -> this is [HANDLE_ASWINI]
 *
 * Every directional surface derives from [identity]: "From eman"/"From aswini" labels, send-dialog
 * names and notification fallbacks use [PartnerIdentity.partnerName] (display only), while actual
 * delivery targets [PartnerIdentity.partnerUid].
 */
data class PartnerIdentity(
    val myName: String? = null,
    val partnerName: String? = null,
    val partnerUid: String? = null,
)

@Singleton
class PartnerResolver @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _identity = MutableStateFlow(PartnerIdentity())
    val identity: StateFlow<PartnerIdentity> = _identity.asStateFlow()

    init {
        FirebaseAuth.AuthStateListener { firebaseAuth ->
            if (firebaseAuth.currentUser == null) {
                _identity.value = PartnerIdentity()
                scope.launch { clearCache() }
            } else {
                refresh()
            }
        }.also { listener ->
            auth.addAuthStateListener(listener)
        }

        if (auth.currentUser != null) {
            refresh()
        }
    }

    /**
     * Display name for the current account based purely on the signup email.
     * Used to prefill the username field. Null while logged out or unrecognized.
     */
    fun suggestedMyName(email: String? = auth.currentUser?.email): String? =
        myHandleFromEmail(email)

    /** Re-run resolution. Safe to call repeatedly. */
    fun refresh() {
        val user = auth.currentUser ?: return

        // Phase 1 (instant, offline): derive both names from my own email.
        val mine = myHandleFromEmail(user.email)
        _identity.value =
            PartnerIdentity(
                myName = mine,
                partnerName = partnerDisplayName(mine),
            )

        scope.launch {
            // Seed the cached UID first so late-starting consumers aren't blocked on Firestore.
            runCatching { context.dataStore.data.first() }.getOrNull()?.let { prefs ->
                val cachedUid = prefs[PARTNER_UID_KEY]
                if (cachedUid != null && _identity.value.partnerUid == null) {
                    _identity.value = _identity.value.copy(partnerUid = cachedUid)
                }
            }

            // The scan is the ONLY path to a fresh partner UID, and this coroutine is the
            // only place it runs — a transient failure here used to leave partnerUid null
            // for the whole process lifetime. Retry with backoff; also retry "no match"
            // because the partner's profile doc can appear AFTER our scan (fresh signup).
            val candidateUsernames = partnerCandidateHandles(mine)
            for (attempt in 1..SCAN_ATTEMPTS) {
                try {
                    val snapshot = firestore.collection("users").get().await()
                    val partnerDoc = snapshot.documents.firstOrNull { doc ->
                        doc.id != user.uid &&
                            (
                                doc.getString("username") in candidateUsernames ||
                                    partnerEmailMatches(doc.getString("email"), mine)
                                )
                    }

                    if (partnerDoc != null) {
                        _identity.value = _identity.value.copy(partnerUid = partnerDoc.id)
                        cachePartner(partnerDoc.id, partnerDisplayName(mine))
                        Timber.d("PartnerResolver: partner resolved to %s (attempt %d)", partnerDoc.id, attempt)
                        return@launch
                    }
                    Timber.d("PartnerResolver: no matching partner doc (attempt %d/%d)", attempt, SCAN_ATTEMPTS)
                } catch (e: Exception) {
                    Timber.e(e, "PartnerResolver: Firestore lookup failed (attempt %d/%d)", attempt, SCAN_ATTEMPTS)
                }
                if (attempt < SCAN_ATTEMPTS) delay(SCAN_RETRY_DELAY_MS * attempt)
            }
            Timber.w("PartnerResolver: giving up after %d attempts; cached UID (if any) remains", SCAN_ATTEMPTS)
        }
    }

    /**
     * Waits up to [timeoutMs] for the Firestore lookup to produce the partner UID.
     * Returns the cached value immediately when present.
     */
    suspend fun awaitPartnerUid(timeoutMs: Long = 10_000): String? =
        withTimeoutOrNull(timeoutMs) {
            identity.first { it.partnerUid != null }.partnerUid
        }

    private fun myHandleFromEmail(email: String?): String? =
        when {
            email == null -> null
            email.contains(EMAIL_TAG_EMAN, ignoreCase = true) -> HANDLE_EMAN
            email.contains(EMAIL_TAG_TEST, ignoreCase = true) -> HANDLE_ASWINI_TEST
            email.contains(EMAIL_TAG_SYLESH, ignoreCase = true) -> HANDLE_ASWINI
            else -> null
        }

    private fun partnerDisplayName(myHandle: String?): String? =
        when (myHandle) {
            HANDLE_EMAN -> HANDLE_ASWINI
            HANDLE_ASWINI, HANDLE_ASWINI_TEST -> HANDLE_EMAN
            else -> null
        }

    /**
     * Usernames the partner seat may be held under. While testing, aswinitest occupies aswini's
     * seat; both are accepted so eman pairs correctly whichever exists.
     */
    private fun partnerCandidateHandles(myHandle: String?): Set<String> =
        when (myHandle) {
            HANDLE_EMAN -> setOf(HANDLE_ASWINI, HANDLE_ASWINI_TEST)
            else -> setOf(HANDLE_EMAN)
        }

    /**
     * Email-based fallback matching for the partner seat, mirroring the same rules: an account
     * counts as aswini's seat if its email carries the sylesh or test tag.
     */
    private fun partnerEmailMatches(email: String?, myHandle: String?): Boolean =
        when (myHandle) {
            HANDLE_EMAN ->
                email != null &&
                    (
                        email.contains(EMAIL_TAG_SYLESH, ignoreCase = true) ||
                            email.contains(EMAIL_TAG_TEST, ignoreCase = true)
                        )

            else -> email?.contains(EMAIL_TAG_EMAN, ignoreCase = true) == true
        }

    private suspend fun cachePartner(uid: String, displayName: String?) {
        runCatching {
            context.dataStore.edit { prefs ->
                prefs[PARTNER_UID_KEY] = uid
                displayName?.let { prefs[PARTNER_NAME_KEY] = it }
            }
        }
    }

    private suspend fun clearCache() {
        runCatching {
            context.dataStore.edit { prefs ->
                prefs.remove(PARTNER_UID_KEY)
                prefs.remove(PARTNER_NAME_KEY)
            }
        }
    }

    companion object {
        const val HANDLE_EMAN = "eman"
        const val HANDLE_ASWINI = "aswini"
        const val HANDLE_ASWINI_TEST = "aswinitest"

        private const val EMAIL_TAG_EMAN = "eman"
        private const val EMAIL_TAG_SYLESH = "sylesh"
        private const val EMAIL_TAG_TEST = "test"

        /** Cached so background contexts (FCM service) can personalize without Firestore. */
        val PARTNER_UID_KEY = stringPreferencesKey("partner_uid_cached")
        val PARTNER_NAME_KEY = stringPreferencesKey("partner_name_cached")

        private const val SCAN_ATTEMPTS = 3
        private const val SCAN_RETRY_DELAY_MS = 2_000L

        /** Blocking read of the cached partner display name for non-suspend callers (e.g. FCM). */
        fun cachedPartnerNameBlocking(context: Context): String? =
            runCatching {
                kotlinx.coroutines.runBlocking {
                    context.dataStore.data.first()[PARTNER_NAME_KEY]
                }
            }.getOrNull()
    }
}
