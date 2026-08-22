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
 * Exactly two people use this build: eman and aswini. Every directional label in the app ("From
 * eman" vs "From aswini", "Send to …", notification fallbacks) derives from [identity] instead of
 * re-implementing the check.
 *
 * Resolution happens in two phases:
 * 1. **Instant** (no network): the account email containing "eman" means this device is eman's;
 *    anything else is aswini's. Correct the moment either account logs in, even before profile
 *    setup.
 * 2. **Authoritative** (async): the Firestore username overrides the heuristic once set, and the
 *    partner's UID is resolved from the `users` collection (username match preferred, then "the
 *    only other account"). The UID is cached in DataStore so consumers rarely hit the network.
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
     * Suggested display name for the current account based on the email heuristic alone.
     * Used to prefill the signup username field. Null while logged out.
     */
    fun suggestedMyName(email: String? = auth.currentUser?.email): String? = when {
        email == null -> null
        email.contains(EMAN, ignoreCase = true) -> EMAN
        else -> ASWINI
    }

    /** Re-run both resolution phases. Safe to call repeatedly. */
    fun refresh() {
        val user = auth.currentUser ?: return

        // Phase 1: instant, offline-correct.
        val mine = suggestedMyName(user.email)
        _identity.value = PartnerIdentity(
            myName = mine,
            partnerName = if (mine == EMAN) ASWINI else EMAN,
        )

        scope.launch {
            // Seed the cached UID first so late-starting consumers aren't blocked on Firestore.
            runCatching { context.dataStore.data.first() }.getOrNull()?.let { prefs ->
                val cachedUid = prefs[PARTNER_UID_KEY]
                if (cachedUid != null && _identity.value.partnerUid == null) {
                    _identity.value = _identity.value.copy(partnerUid = cachedUid)
                }
            }

            try {
                // Phase 2a: the claimed username is authoritative over the email heuristic.
                val myUsername = firestore.collection("users")
                    .document(user.uid)
                    .get()
                    .await()
                    .getString("username")
                when (myUsername) {
                    EMAN -> _identity.value =
                        _identity.value.copy(myName = EMAN, partnerName = ASWINI)

                    ASWINI -> _identity.value =
                        _identity.value.copy(myName = ASWINI, partnerName = EMAN)
                }

                // Phase 2b: find the partner's UID.
                val snapshot = firestore.collection("users").get().await()
                val others = snapshot.documents.mapNotNull { doc ->
                    val uid = doc.id
                    if (uid == user.uid) return@mapNotNull null
                    UserProfile(
                        uid = uid,
                        email = doc.getString("email") ?: "",
                        username = doc.getString("username") ?: "",
                        photoUrl = doc.getString("photoUrl"),
                    )
                }
                val wanted = _identity.value.partnerName
                val partner = others.firstOrNull { it.username.equals(wanted, ignoreCase = true) }
                    ?: others.firstOrNull()

                if (partner != null) {
                    _identity.value = _identity.value.copy(partnerUid = partner.uid)
                    cachePartner(partner.uid, wanted)
                }
            } catch (e: Exception) {
                Timber.e(e, "PartnerResolver: authoritative phase failed; keeping heuristic identity")
            }
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

    private suspend fun cachePartner(uid: String, name: String?) {
        runCatching {
            context.dataStore.edit { prefs ->
                prefs[PARTNER_UID_KEY] = uid
                name?.let { prefs[PARTNER_NAME_KEY] = it }
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
        const val EMAN = "eman"
        const val ASWINI = "aswini"

        /** Cached so background contexts (FCM service) can personalize without Firestore. */
        val PARTNER_UID_KEY = stringPreferencesKey("partner_uid_cached")
        val PARTNER_NAME_KEY = stringPreferencesKey("partner_name_cached")

        /** Blocking read of the cached partner name for non-suspend callers (e.g. FCM service). */
        fun cachedPartnerNameBlocking(context: Context): String? =
            runCatching {
                kotlinx.coroutines.runBlocking {
                    context.dataStore.data.first()[PARTNER_NAME_KEY]
                }
            }.getOrNull()
    }
}
