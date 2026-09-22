/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.NetworkConnectivityObserver
import com.metrolist.music.utils.YTPlayerUtils
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firestore-backed repository for SPEC_8 "Us" shared playlists.
 *
 * Cloud model: one doc per shared playlist at `sharedPlaylists/{playlistId}` where the doc id
 * equals the local Room playlist id. Both phones read/write the same doc; Firestore's
 * last-writer-wins semantics handle concurrent edits (D5). Songs are stored as a flat array
 * of YouTube video ids; each phone resolves metadata from its own local Room `song` table.
 *
 * Sync model: a single hot [observed] StateFlow holds every cloud doc the current user is a
 * member of (as creator or recipient). [SharedPlaylistSyncListener] collects it and calls
 * [reconcileLocal] to apply cloud state to local Room. No per-playlist listeners are needed —
 * the global query covers all shared playlists with two Firestore listeners total.
 *
 * Delete semantics:
 *  - Normal delete (menu): cloud doc removed → partner's listener fires → local row removed (D28).
 *  - Account delete: `partners_deleted/{uid}` tombstone written first → partner promotes local
 *    rows to local-only playlists instead of removing them (D8).
 */
@Singleton
class SharedPlaylistRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val partnerResolver: PartnerResolver,
    private val database: MusicDatabase,
    private val networkConnectivity: NetworkConnectivityObserver,
    @ApplicationContext private val context: Context,
) {
    private val sharedPlaylistsCollection get() = firestore.collection("sharedPlaylists")
    private val partnersDeletedCollection get() = firestore.collection("partners_deleted")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Receive concurrency (SPEC_SHARED_RECEIVE_SPEED) ─────────────────────────────────────────

    /**
     * Global metadata-fetch permit pool: 6 slots shared across ALL reconciles, never 6 per
     * playlist. Held through fetch AND insert; [withPermit] releases automatically on throw.
     */
    private val fetchSemaphore = Semaphore(6)

    /**
     * Striped per-playlist locks (16 fixed mutexes keyed by playlist id hash). A fixed pool
     * avoids the create/remove race a lock map would have. Guards the diff-and-insert critical
     * section so overlapping reconciles of the same playlist can't both pass the
     * check-then-insert guard (playlist_song_map has no unique constraint on (playlistId, songId)).
     * Never held across network I/O — only Room work.
     */
    private val reconcileStripes = Array(16) { Mutex() }

    private fun stripeFor(playlistId: String): Mutex =
        reconcileStripes[playlistId.hashCode().and(Int.MAX_VALUE) % reconcileStripes.size]

    // ── Hot observed state ──────────────────────────────────────────────────────────────────────

    private val _observed = MutableStateFlow<List<SharedPlaylistCloud>>(emptyList())

    /** All shared playlists the current user is a member of. Hot, app-lifetime. */
    val observed: StateFlow<List<SharedPlaylistCloud>> = _observed.asStateFlow()

    // ── Tombstone state (D8) ────────────────────────────────────────────────────────────────────

    private val _deletedPartnerUids = MutableStateFlow<Set<String>>(emptySet())

    /** UIDs whose accounts have been deleted (per `partners_deleted` tombstones). */
    val deletedPartnerUids: StateFlow<Set<String>> = _deletedPartnerUids.asStateFlow()

    /** Current partner identity, used by share affordances and their missing-partner state. */
    val partnerIdentity: StateFlow<PartnerIdentity> = partnerResolver.identity

    /** Display name of the partner, for UI labels like "Share with aswini". */
    val partnerDisplayName: StateFlow<String?> = partnerResolver.identity
        .map { it.partnerName }
        .stateIn(scope, SharingStarted.Eagerly, partnerResolver.identity.value.partnerName)

    // ── "X new" badge persistence (SPEC_8) ──────────────────────────────────────────────────────

    private val openedAtKey = stringPreferencesKey("us_playlist_opened_at")
    private val unseenSongIdsKey = stringPreferencesKey("us_playlist_unseen_song_ids")

    /**
     * Number of unseen songs added from the cloud for each shared playlist. The listener records
     * cloud additions before applying them to Room, which lets it exclude this device's local adds.
     */
    val newSongCounts: StateFlow<Map<String, Int>> = context.dataStore.data
        .map { preferences ->
            parseStringSets(preferences[unseenSongIdsKey].orEmpty()).mapValues { it.value.size }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Record that the user opened a shared playlist; clears its "new" badge. */
    suspend fun markPlaylistOpened(playlistId: String) {
        context.dataStore.edit { prefs ->
            val openedAt = parseLongMap(prefs[openedAtKey].orEmpty()).toMutableMap()
            openedAt[playlistId] = System.currentTimeMillis()
            prefs[openedAtKey] = encodeLongMap(openedAt)

            val unseen = parseStringSets(prefs[unseenSongIdsKey].orEmpty()).toMutableMap()
            unseen.remove(playlistId)
            prefs[unseenSongIdsKey] = encodeStringSets(unseen)
        }
    }

    /**
     * Capture partner additions before [reconcileLocal] inserts them into Room. Initial snapshots
     * use the local song set as the baseline, so sharing your own existing playlist does not mark
     * every song new while receiving a partner's playlist does.
     */
    suspend fun recordIncomingAdditions(
        cloud: SharedPlaylistCloud,
        previous: SharedPlaylistCloud?,
    ) = withContext(Dispatchers.IO) {
        val localSongIds = database.playlistSongIds(cloud.id).toSet()
        val additions = remoteAdditions(
            previousCloudSongs = previous?.songs?.toSet(),
            cloudSongs = cloud.songs,
            localSongs = localSongIds,
        )
        if (additions.isEmpty()) return@withContext

        context.dataStore.edit { prefs ->
            val unseen = parseStringSets(prefs[unseenSongIdsKey].orEmpty()).toMutableMap()
            unseen[cloud.id] = unseen[cloud.id].orEmpty() + additions
            prefs[unseenSongIdsKey] = encodeStringSets(unseen)
        }
    }

    private suspend fun clearBadgeState(playlistId: String) {
        context.dataStore.edit { prefs ->
            val openedAt = parseLongMap(prefs[openedAtKey].orEmpty()).toMutableMap()
            openedAt.remove(playlistId)
            prefs[openedAtKey] = encodeLongMap(openedAt)
            val unseen = parseStringSets(prefs[unseenSongIdsKey].orEmpty()).toMutableMap()
            unseen.remove(playlistId)
            prefs[unseenSongIdsKey] = encodeStringSets(unseen)
        }
    }

    // ── Listener bookkeeping ────────────────────────────────────────────────────────────────────

    private val createdByMe = mutableMapOf<String, SharedPlaylistCloud>()
    private val sharedWithMe = mutableMapOf<String, SharedPlaylistCloud>()
    private var createdReg: ListenerRegistration? = null
    private var sharedWithReg: ListenerRegistration? = null
    private var tombstoneReg: ListenerRegistration? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null
    private var tombstoneJob: Job? = null
    private var attachedUid: String? = null

    init {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val myUid = firebaseAuth.currentUser?.uid
            detachListeners()
            if (myUid != null) {
                attachListeners(myUid)
            } else {
                _observed.value = emptyList()
                _deletedPartnerUids.value = emptySet()
            }
        }
        authListener = listener
        auth.addAuthStateListener(listener)
        // Attach immediately if auth is already restored (cold-start fix).
        auth.currentUser?.uid?.let { attachListeners(it) }
    }

    private fun attachListeners(myUid: String) {
        if (attachedUid == myUid) return
        attachedUid = myUid
        createdReg = sharedPlaylistsCollection
            .whereEqualTo("sharedByUid", myUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Timber.tag(TAG).e(error, "sharedByMe listener error")
                    return@addSnapshotListener
                }
                createdByMe.clear()
                snapshot?.documents?.forEach { doc ->
                    SharedPlaylistCloud.fromMap(doc.id, doc.data ?: emptyMap())?.let {
                        createdByMe[doc.id] = it
                    }
                }
                emitObserved()
            }

        sharedWithReg = sharedPlaylistsCollection
            .whereEqualTo("sharedWith", myUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Timber.tag(TAG).e(error, "sharedWithMe listener error")
                    return@addSnapshotListener
                }
                sharedWithMe.clear()
                snapshot?.documents?.forEach { doc ->
                    SharedPlaylistCloud.fromMap(doc.id, doc.data ?: emptyMap())?.let {
                        sharedWithMe[doc.id] = it
                    }
                }
                emitObserved()
            }

        // Tombstone listener: watch the partner's deletion marker so we can promote local
        // shared playlists to local-only when their account is gone (D8).
        tombstoneJob?.cancel()
        tombstoneJob = scope.launch {
            partnerResolver.identity.collect { identity ->
                val partnerUid = identity.partnerUid ?: return@collect
                tombstoneReg?.remove()
                tombstoneReg = partnersDeletedCollection.document(partnerUid)
                    .addSnapshotListener { snap, err ->
                        if (err != null) {
                            Timber.tag(TAG).e(err, "partners_deleted listener error")
                            return@addSnapshotListener
                        }
                        _deletedPartnerUids.value = if (snap?.exists() == true) {
                            _deletedPartnerUids.value + partnerUid
                        } else {
                            _deletedPartnerUids.value - partnerUid
                        }
                    }
            }
        }
    }

    private fun detachListeners() {
        createdReg?.remove()
        sharedWithReg?.remove()
        tombstoneReg?.remove()
        tombstoneJob?.cancel()
        createdReg = null
        sharedWithReg = null
        tombstoneReg = null
        tombstoneJob = null
        attachedUid = null
        createdByMe.clear()
        sharedWithMe.clear()
    }

    private fun emitObserved() {
        _observed.value = (createdByMe.values + sharedWithMe.values).distinctBy { it.id }
    }

    private fun authUidFlow(): Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser?.uid)
        }
        auth.addAuthStateListener(listener)
        trySend(auth.currentUser?.uid)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    /** Observe one shared playlist, reattaching after Firebase Auth restores or changes users. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(playlistId: String): Flow<SharedPlaylistCloud?> =
        authUidFlow().flatMapLatest { myUid ->
            if (myUid == null) {
                flowOf<SharedPlaylistCloud?>(null)
            } else {
                callbackFlow {
                    var creatorCloud: SharedPlaylistCloud? = null
                    var recipientCloud: SharedPlaylistCloud? = null
                    fun emitCloud() {
                        trySend(creatorCloud ?: recipientCloud)
                    }

                    val creatorRegistration = sharedPlaylistsCollection
                        .whereEqualTo(FieldPath.documentId(), playlistId)
                        .whereEqualTo("sharedByUid", myUid)
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Timber.tag(TAG).e(error, "Shared playlist listener error for $playlistId")
                                trySend(null)
                                return@addSnapshotListener
                            }
                            creatorCloud = snapshot?.documents?.firstOrNull()?.data
                                ?.let { SharedPlaylistCloud.fromMap(playlistId, it) }
                            emitCloud()
                        }
                    val recipientRegistration = sharedPlaylistsCollection
                        .whereEqualTo(FieldPath.documentId(), playlistId)
                        .whereEqualTo("sharedWith", myUid)
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Timber.tag(TAG).e(error, "Shared playlist recipient listener error for $playlistId")
                                trySend(null)
                                return@addSnapshotListener
                            }
                            recipientCloud = snapshot?.documents?.firstOrNull()?.data
                                ?.let { SharedPlaylistCloud.fromMap(playlistId, it) }
                            emitCloud()
                        }
                    awaitClose {
                        creatorRegistration.remove()
                        recipientRegistration.remove()
                    }
                }
            }
        }.distinctUntilChanged()

    /** All shared playlists for the signed-in user, backed by the two membership queries. */
    fun observeAll(): Flow<List<SharedPlaylistCloud>> = observed

    // ── Write operations ────────────────────────────────────────────────────────────────────────

    /**
     * Share a local playlist with the partner (D1). Creates the Firestore doc and marks the
     * local row with `sharedWith = partnerUid`. Idempotent — returns failure if already shared.
     */
    suspend fun share(playlistId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val myUid = auth.currentUser?.uid
            ?: return@withContext Result.failure(IllegalStateException("Not signed in"))
        val partnerUid = partnerResolver.awaitPartnerUid()
            ?: return@withContext Result.failure(IllegalStateException("Partner not resolved"))
        if (partnerUid == myUid) {
            return@withContext Result.failure(IllegalStateException("Cannot share with self"))
        }

        val local = database.playlistBlocking(playlistId)
            ?: return@withContext Result.failure(IllegalStateException("Local playlist not found"))
        if (local.playlist.sharedWith != null) {
            return@withContext Result.failure(IllegalStateException("Already shared"))
        }

        val localSongIds = database.playlistSongIds(playlistId)
        val now = System.currentTimeMillis()
        val myName = partnerResolver.identity.value.myName

        // D19 revised: covers are device-local; cloud thumbnailUrl is always null and ignored by reconcileLocal.
        val doc = mapOf(
            "name" to local.playlist.name,
            "thumbnailUrl" to null,
            "sharedByUid" to myUid,
            "sharedByName" to myName,
            "sharedWith" to partnerUid,
            "songs" to localSongIds,
            "createdAt" to now,
            "updatedAt" to now,
        )

        try {
            sharedPlaylistsCollection.document(playlistId).set(doc).await()
            database.query {
                update(local.playlist.copy(sharedWith = partnerUid))
            }
            Timber.tag(TAG).d("Shared playlist $playlistId with $partnerUid")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to share playlist $playlistId")
            Result.failure(e)
        }
    }

    /**
     * Add a song to a shared playlist. Uses `arrayUnion` for idempotency (D5).
     * Also applies the change locally so the UI updates immediately.
     */
    suspend fun addSong(playlistId: String, songId: String): Result<Unit> = withContext(Dispatchers.IO) {
        auth.currentUser?.uid
            ?: return@withContext Result.failure(IllegalStateException("Not signed in"))
        val local = database.playlistBlocking(playlistId)
            ?: return@withContext Result.failure(IllegalStateException("Local playlist not found"))
        if (local.playlist.sharedWith == null) {
            return@withContext Result.failure(IllegalStateException("Not a shared playlist"))
        }

        try {
            // Apply locally first. Firestore persists the write in its offline queue and the UI
            // remains editable while disconnected.
            database.transaction {
                if (database.checkInPlaylist(playlistId, songId) == 0) {
                    val currentMax = database.playlistSongMaps(playlistId, 0)
                        .maxOfOrNull { it.position } ?: -1
                    database.insert(
                        PlaylistSongMap(
                            songId = songId,
                            playlistId = playlistId,
                            position = currentMax + 1,
                        ),
                    )
                    database.updatePlaylistLastUpdated(playlistId)
                }
            }

            sharedPlaylistsCollection.document(playlistId).update(
                mapOf(
                    "songs" to FieldValue.arrayUnion(songId),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to add song $songId to shared playlist $playlistId")
            Result.failure(e)
        }
    }

    /**
     * Remove a song from a shared playlist. Uses `arrayRemove` (idempotent).
     * Also applies the change locally.
     */
    suspend fun removeSong(playlistId: String, songId: String): Result<Unit> = withContext(Dispatchers.IO) {
        auth.currentUser?.uid
            ?: return@withContext Result.failure(IllegalStateException("Not signed in"))
        val local = database.playlistBlocking(playlistId)
            ?: return@withContext Result.failure(IllegalStateException("Local playlist not found"))
        if (local.playlist.sharedWith == null) {
            return@withContext Result.failure(IllegalStateException("Not a shared playlist"))
        }

        try {
            database.transaction {
                database.playlistSongsBlocking(playlistId)
                    .find { it.song.id == songId }
                    ?.let { database.delete(it.map) }
                database.updatePlaylistLastUpdated(playlistId)
            }

            sharedPlaylistsCollection.document(playlistId).update(
                mapOf(
                    "songs" to FieldValue.arrayRemove(songId),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to remove song $songId from shared playlist $playlistId")
            Result.failure(e)
        }
    }

    /** Rename a shared playlist. Updates both cloud and local. */
    suspend fun rename(playlistId: String, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        auth.currentUser?.uid
            ?: return@withContext Result.failure(IllegalStateException("Not signed in"))
        val local = database.playlistBlocking(playlistId)
            ?: return@withContext Result.failure(IllegalStateException("Local playlist not found"))
        if (local.playlist.sharedWith == null) {
            return@withContext Result.failure(IllegalStateException("Not a shared playlist"))
        }

        try {
            database.query {
                update(local.playlist.copy(name = name))
            }
            sharedPlaylistsCollection.document(playlistId).update(
                mapOf(
                    "name" to name,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to rename shared playlist $playlistId")
            Result.failure(e)
        }
    }

    /**
     * Update the cover thumbnail URL for a shared playlist.
     * D19 revised: covers are device-local; only the local row is updated, cloud is untouched.
     */
    suspend fun setCover(playlistId: String, url: String?): Result<Unit> = withContext(Dispatchers.IO) {
        auth.currentUser?.uid
            ?: return@withContext Result.failure(IllegalStateException("Not signed in"))
        val local = database.playlistBlocking(playlistId)
            ?: return@withContext Result.failure(IllegalStateException("Local playlist not found"))
        if (local.playlist.sharedWith == null) {
            return@withContext Result.failure(IllegalStateException("Not a shared playlist"))
        }

        try {
            database.query {
                update(local.playlist.copy(thumbnailUrl = url))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set cover for shared playlist $playlistId")
            Result.failure(e)
        }
    }

    /**
     * Delete a shared playlist (D28 symmetric delete). Removes the cloud doc and the local row.
     * The partner's listener will fire and remove their local copy via [reconcileLocal].
     */
    suspend fun deleteRemote(playlistId: String): Result<Unit> = withContext(Dispatchers.IO) {
        auth.currentUser?.uid
            ?: return@withContext Result.failure(IllegalStateException("Not signed in"))
        val local = database.playlistBlocking(playlistId)
            ?: return@withContext Result.failure(IllegalStateException("Local playlist not found"))

        try {
            sharedPlaylistsCollection.document(playlistId).delete().await()
            database.query {
                delete(local.playlist)
            }
            clearBadgeState(playlistId)
            Timber.tag(TAG).d("Deleted shared playlist $playlistId (symmetric)")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to delete shared playlist $playlistId")
            Result.failure(e)
        }
    }

    /**
     * Unshare is not supported (D7). This is a no-op that always returns failure.
     * Kept for API completeness; callers should not offer an unshare action.
     */
    suspend fun unshare(playlistId: String): Result<Unit> =
        Result.failure(IllegalStateException("Unshare is not supported (D7)"))

    // ── Reconciliation ──────────────────────────────────────────────────────────────────────────

    /**
     * Reconcile a single playlist's local Room state against its cloud doc.
     *
     * Called by [SharedPlaylistSyncListener] on every cloud emission. Idempotent.
     *
     * Cases:
     *  - Cloud exists, local missing → create local row + insert songs (first-time receive).
     *  - Cloud exists, local exists → diff name/thumbnail/songs; apply changes.
     *  - Cloud gone, local shared → D28 (remove) or D8 (promote) depending on tombstone.
     */
    suspend fun reconcileLocal(
        playlistId: String,
        knownCloud: SharedPlaylistCloud? = null,
        cloudStateKnown: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        // Cloud resolution stays OUTSIDE the stripe: it may hit the network on the
        // reconcileAll / cold-read path.
        val resolvedCloud = if (cloudStateKnown) {
            knownCloud
        } else {
            try {
                readCloudForMember(playlistId)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to read cloud doc for reconcile $playlistId")
                return@withContext
            }
        }

        // The tombstone probe may also hit the network — same rule, compute it outside the lock.
        val initialLocal = database.playlistBlocking(playlistId)
        val partnerUid = initialLocal?.playlist?.sharedWith
        val partnerDeleted = partnerUid != null && (
            partnerUid in _deletedPartnerUids.value ||
                runCatching { partnersDeletedCollection.document(partnerUid).get().await().exists() }
                    .getOrDefault(false)
            )

        // Diff + structural changes run under the playlist stripe so a queued overlapping
        // reconcile recomputes against fresh local state instead of a stale emission snapshot.
        var toAdd: List<String> = emptyList()
        var cloudSongSet: Set<String> = emptySet()
        stripeFor(playlistId).withLock {
            // Converge to the newest snapshot: a later emission may have superseded
            // resolvedCloud while we waited on the stripe. observed is in-memory, so this
            // re-check is lock-safe; fall back to the resolved snapshot on a miss.
            val cloud = if (cloudStateKnown) {
                _observed.value.find { it.id == playlistId } ?: resolvedCloud
            } else {
                resolvedCloud
            }

            val local = database.playlistBlocking(playlistId)

            if (cloud == null) {
                // Cloud doc gone.
                if (local != null && local.playlist.sharedWith != null) {
                    if (partnerDeleted) {
                        // D8 survivor: promote to local-only playlist.
                        Timber.tag(TAG).d("D8 survivor: promoting $playlistId to local")
                        database.query {
                            update(local.playlist.copy(sharedWith = null))
                        }
                        clearBadgeState(playlistId)
                    } else {
                        // D28 symmetric delete: remove local row + songs.
                        Timber.tag(TAG).d("D28 symmetric delete: removing local $playlistId")
                        database.query {
                            delete(local.playlist)
                        }
                        clearBadgeState(playlistId)
                    }
                }
                return@withLock
            }

            // Cloud doc exists. Apply diff.
            // D19 revised: thumbnailUrl is device-local; cloud value is ignored (always null after this fix, but old docs may still carry a value).
            if (local == null) {
                // First time receiving this shared playlist. Create local row. Synchronous
                // (not database.query{}): flushes run on other threads and an all-known
                // sub-50 first receive can flush before a fire-and-forget insert lands,
                // violating playlist_song_map's FK.
                Timber.tag(TAG).d("First-time receive: creating local row for $playlistId")
                database.withTransaction {
                    insert(
                        PlaylistEntity(
                            id = playlistId,
                            name = cloud.name,
                            browseId = null,
                            isLocal = true,
                            isEditable = true,
                            bookmarkedAt = LocalDateTime.now(),
                            sharedWith = cloud.sharedByUid, // partner's uid from our perspective
                        ),
                    )
                }
            } else if (local.playlist.name != cloud.name) {
                database.query {
                    update(
                        local.playlist.copy(
                            name = cloud.name,
                        ),
                    )
                }
            }

            // Diff songs: add missing, remove extra. Positions are local-only (D21).
            val localSongIds = database.playlistSongIds(playlistId).toSet()
            val cloudSongIds = cloud.songs.toSet()
            toAdd = (cloudSongIds - localSongIds).toList()
            cloudSongSet = cloudSongIds
            val toRemove = localSongIds - cloudSongIds

            if (toRemove.isNotEmpty()) {
                database.transaction {
                    toRemove.forEach { songId ->
                        database.playlistSongsBlocking(playlistId)
                            .find { it.song.id == songId }
                            ?.let { database.delete(it.map) }
                    }
                    database.updatePlaylistLastUpdated(playlistId)
                }
            }
        }

        // Adds run AFTER the stripe is released: fetches must never happen while holding the
        // lock. Fan out across songs; the repository-wide fetchSemaphore bounds the whole app
        // to 6 concurrent fetch+inserts across all playlists. Finished fetches buffer by
        // CLOUD INDEX and flush contiguous runs, so positions on this phone match the
        // sharer's order exactly (toAdd follows cloud order). Arrival speed is parallelism,
        // display smoothness is batching, order is the buffer.
        coroutineScope {
            val pending = mutableMapOf<Int, ReceivedSong>()
            val resolved = mutableSetOf<Int>() // indices needing no insert (skipped/failed)
            val pendingMutex = Mutex()
            var nextFlushIndex = 0
            // Set when a chunk flush fails: further flushes stop and the tail is skipped, so
            // committed state stays a clean order-prefix and the next diff rediscovers the
            // remainder in order. All accesses happen under pendingMutex.
            var aborted = false

            // Caller holds pendingMutex. Flushes full 50s from the contiguous resolved run;
            // a run reaching the end of toAdd is provably final (every index resolves exactly
            // once), so the tail flushes at any size. Sub-chunk non-tail runs stay buffered.
            // A failed chunk aborts the pass (see aborted) instead of crashing or committing
            // later chunks out of order.
            suspend fun flushContiguousLocked() {
                if (aborted) return
                var runEnd = nextFlushIndex
                while (runEnd < toAdd.size &&
                    (pending.containsKey(runEnd) || resolved.contains(runEnd))
                ) {
                    runEnd++
                }
                while (runEnd - nextFlushIndex >= FLUSH_CHUNK ||
                    (runEnd == toAdd.size && runEnd > nextFlushIndex)
                ) {
                    val take = minOf(FLUSH_CHUNK, runEnd - nextFlushIndex)
                    val chunk = (nextFlushIndex until nextFlushIndex + take).mapNotNull {
                        pending.remove(it).also { _ -> resolved.remove(it) }
                    }
                    nextFlushIndex += take
                    if (chunk.isNotEmpty()) {
                        try {
                            flushReceiveChunk(playlistId, chunk, cloudSongSet)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Ordered receive chunk failed — aborting pass, remainder retried next reconcile")
                            aborted = true
                            return
                        }
                    }
                }
            }

            toAdd.withIndex().map { (index, songId) ->
                async {
                    // Never let one song's failure kill the coroutineScope (which would
                    // cancel sibling fetches and crash the app via the scope's unhandled
                    // exception). The song stays absent; the next reconcile's diff rediscovers it.
                    // Every path below resolves its index or the ordered pointer stalls.
                    try {
                        fetchSemaphore.withPermit {
                            if (database.checkInPlaylist(playlistId, songId) > 0) {
                                pendingMutex.withLock {
                                    resolved += index
                                    flushContiguousLocked()
                                }
                                return@withPermit
                            }

                            val existing = database.getSongByIdBlocking(songId)
                            val metadata = if (existing == null) {
                                // Offline short-circuit: skip doomed fetches instead of burning all
                                // six slots on failures. The song stays absent; the next reconcile
                                // retries it.
                                if (!networkConnectivity.isCurrentlyConnected()) {
                                    Timber.tag(TAG).d("Offline — deferring metadata fetch for $songId")
                                    pendingMutex.withLock {
                                        resolved += index
                                        flushContiguousLocked()
                                    }
                                    return@withPermit
                                }
                                // Bounded fetch: one hung song must not stall the ordered pointer
                                // (and the whole playlist's display) indefinitely. Timeout is
                                // treated like any other fetch failure — retried next reconcile.
                                val fetched = try {
                                    kotlinx.coroutines.withTimeout(FETCH_TIMEOUT_MS) {
                                        fetchSongMetadata(songId)
                                    }
                                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                                    Timber.tag(TAG).w("Fetch timed out for $songId — retried next reconcile")
                                    pendingMutex.withLock {
                                        resolved += index
                                        flushContiguousLocked()
                                    }
                                    return@withPermit
                                }
                                if (fetched == null) {
                                    Timber.tag(TAG).w("Skipping song $songId — metadata fetch failed")
                                    pendingMutex.withLock {
                                        resolved += index
                                        flushContiguousLocked()
                                    }
                                    return@withPermit
                                }
                                fetched
                            } else null

                            pendingMutex.withLock {
                                pending[index] = ReceivedSong(songId, metadata)
                                flushContiguousLocked()
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Receive failed for $songId — retried next reconcile")
                        pendingMutex.withLock {
                            resolved += index
                            flushContiguousLocked()
                        }
                    }
                }
            }.awaitAll()

            // Flush the tail in order; awaitAll guarantees every index resolved, so the
            // pointer provably reaches toAdd.size here. Skipped entirely if a chunk failed
            // (aborted): the remainder is redone next pass, preserving order.
            try {
                pendingMutex.withLock {
                    if (!aborted) {
                        val tail = mutableListOf<ReceivedSong>()
                        while (nextFlushIndex < toAdd.size) {
                            pending.remove(nextFlushIndex)?.let { tail += it }
                            resolved.remove(nextFlushIndex)
                            nextFlushIndex++
                        }
                        if (tail.isNotEmpty()) flushReceiveChunk(playlistId, tail, cloudSongSet)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Final receive flush failed for $playlistId — retried next reconcile")
            }
        }
    }

    /**
     * Reads a single member document through membership-constrained queries. A direct read of a
     * missing document evaluates `resource.data` in Firestore rules and is denied; queries safely
     * return an empty snapshot for the missing-doc case used by D8/D28 recovery.
     */
    private suspend fun readCloudForMember(playlistId: String): SharedPlaylistCloud? {
        val myUid = auth.currentUser?.uid ?: return null
        val creator = sharedPlaylistsCollection
            .whereEqualTo(FieldPath.documentId(), playlistId)
            .whereEqualTo("sharedByUid", myUid)
            .get()
            .await()
            .documents
            .firstOrNull()
            ?.let { SharedPlaylistCloud.fromMap(playlistId, it.data ?: emptyMap()) }
        if (creator != null) return creator

        return sharedPlaylistsCollection
            .whereEqualTo(FieldPath.documentId(), playlistId)
            .whereEqualTo("sharedWith", myUid)
            .get()
            .await()
            .documents
            .firstOrNull()
            ?.let { SharedPlaylistCloud.fromMap(playlistId, it.data ?: emptyMap()) }
    }

    /**
     * Reconcile all shared playlists. Called on app start to catch up on any missed changes.
     */
    suspend fun reconcileAll() {
        val allIds = _observed.value.map { it.id }
        // Also reconcile any local shared rows whose cloud doc may have been deleted while offline.
        val localSharedIds = withContext(Dispatchers.IO) {
            database.playlistEntitiesByNameAsc()
                .filter { it.sharedWith != null }
                .map { it.id }
        }
        (allIds + localSharedIds).distinct().forEach { reconcileLocal(it) }
    }

    /**
     * Delete all shared playlist cloud docs where [uid] is a member. Called during account
     * deletion (D8). Writes the `partners_deleted` tombstone first so the surviving partner
     * promotes their local copies instead of removing them.
     */
    suspend fun clearAllCloudForUid(uid: String) {
        try {
            // Write tombstone first so the partner's listener promotes rather than deletes.
            partnersDeletedCollection.document(uid).set(
                mapOf("at" to System.currentTimeMillis()),
            ).await()

            // Delete all docs where uid is creator or recipient.
            val asCreator = sharedPlaylistsCollection.whereEqualTo("sharedByUid", uid).get().await()
            val asRecipient = sharedPlaylistsCollection.whereEqualTo("sharedWith", uid).get().await()
            (asCreator.documents + asRecipient.documents)
                .distinctBy { it.id }
                .chunked(FIRESTORE_BATCH_LIMIT)
                .forEach { documents ->
                    val batch = firestore.batch()
                    documents.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }
            Timber.tag(TAG).d("Cleared all shared playlists for uid $uid")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to clear shared playlists for uid $uid")
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    /**
     * One buffered receive result: a song id plus its fetched metadata (null when the song
     * row already exists locally and only the playlist map row is needed).
     */
    private data class ReceivedSong(val songId: String, val metadata: MediaMetadata?)

    /**
     * Flush one completion-order chunk of received songs in a single transaction under the
     * playlist's stripe. Keeps the [PlaylistSongMap] FK fix (song row first, re-checked
     * inside the transaction) and assigns positions from the live max inside the transaction
     * so a batch can never leave gaps or collide. A failed chunk rolls back as a unit — at
     * most FLUSH_CHUNK songs are lost, callers catch and the next reconcile's diff
     * rediscovers them. Never called with the stripe already held (lock order: buffer → stripe).
     *
     * [fallbackCloudSongs] is the cloud set the diff was computed from. Under the stripe the
     * live [observed] snapshot is re-read: if the partner removed songs (or the whole doc)
     * while these fetches were in flight, items no longer in the live set are dropped here —
     * a deletion child either already updated observed (we skip) or queues on this stripe
     * (it cleans up after we commit), so phantoms cannot survive either interleaving.
     */
    private suspend fun flushReceiveChunk(
        playlistId: String,
        chunk: List<ReceivedSong>,
        fallbackCloudSongs: Set<String>,
    ) {
        stripeFor(playlistId).withLock {
            val liveSongs = _observed.value.find { it.id == playlistId }
                ?.songs?.toSet()
                ?: fallbackCloudSongs
            database.withTransaction {
                var currentMax = playlistSongMaps(playlistId, 0).maxOfOrNull { it.position } ?: -1
                chunk.forEach { item ->
                    if (item.songId !in liveSongs) return@forEach
                    if (item.metadata != null && getSongByIdBlocking(item.songId) == null) {
                        insert(item.metadata)
                    }
                    if (checkInPlaylist(playlistId, item.songId) == 0) {
                        currentMax += 1
                        insert(
                            PlaylistSongMap(
                                songId = item.songId,
                                playlistId = playlistId,
                                position = currentMax,
                            ),
                        )
                    }
                }
                updatePlaylistLastUpdated(playlistId)
            }
        }
    }

    /**
     * Fetch song metadata from YouTube via the player endpoint. Returns null on failure.
     * Uses [YTPlayerUtils.playerResponseForMetadata] which handles PoTokens internally.
     */
    private suspend fun fetchSongMetadata(songId: String): MediaMetadata? =
        withContext(Dispatchers.IO) {
            try {
                val response: PlayerResponse = YTPlayerUtils.playerResponseForMetadata(songId)
                    .getOrNull() ?: return@withContext null
                val details = response.videoDetails ?: return@withContext null
                val thumbnailUrl = details.thumbnail.thumbnails.lastOrNull()?.url
                MediaMetadata(
                    id = details.videoId,
                    title = details.title ?: "Unknown",
                    artists = listOf(
                        MediaMetadata.Artist(
                            id = details.channelId,
                            name = details.author ?: "Unknown",
                        ),
                    ),
                    duration = details.lengthSeconds.toIntOrNull() ?: 0,
                    thumbnailUrl = thumbnailUrl,
                    musicVideoType = details.musicVideoType,
                )
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to fetch metadata for song $songId")
                null
            }
        }

    companion object {
        private const val TAG = "SharedPlaylistRepository"
        private const val FIRESTORE_BATCH_LIMIT = 450

        /** Songs per Room transaction during a bulk receive (A5). */
        private const val FLUSH_CHUNK = 50

        /** Per-song fetch bound: one hung fetch must not stall the ordered pointer. */
        private const val FETCH_TIMEOUT_MS = 30_000L

        internal fun remoteAdditions(
            previousCloudSongs: Set<String>?,
            cloudSongs: List<String>,
            localSongs: Set<String>,
        ): Set<String> =
            cloudSongs.toSet() - (previousCloudSongs ?: emptySet()) - localSongs

        internal fun applyArrayUnion(songs: List<String>, songId: String): List<String> =
            if (songId in songs) songs else songs + songId

        internal fun applyArrayRemove(songs: List<String>, songId: String): List<String> =
            songs.filterNot { it == songId }

        private fun parseLongMap(json: String): Map<String, Long> =
            if (json.isBlank()) emptyMap() else runCatching {
                val obj = JSONObject(json)
                obj.keys().asSequence().associateWith { obj.optLong(it, 0L) }
            }.getOrDefault(emptyMap())

        private fun encodeLongMap(values: Map<String, Long>): String =
            JSONObject().apply { values.forEach { (id, value) -> put(id, value) } }.toString()

        private fun parseStringSets(json: String): Map<String, Set<String>> =
            if (json.isBlank()) emptyMap() else runCatching {
                val obj = JSONObject(json)
                obj.keys().asSequence().associateWith { id ->
                    val array = obj.optJSONArray(id)
                    buildSet {
                        if (array != null) {
                            repeat(array.length()) { index ->
                                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                            }
                        }
                    }
                }
            }.getOrDefault(emptyMap())

        private fun encodeStringSets(values: Map<String, Set<String>>): String =
            JSONObject().apply {
                values.forEach { (id, songIds) ->
                    put(id, org.json.JSONArray().apply { songIds.forEach(::put) })
                }
            }.toString()
    }
}
