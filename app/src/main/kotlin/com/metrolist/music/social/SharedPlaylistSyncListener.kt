/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.social

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-lifetime listener that applies cloud shared-playlist changes to local Room.
 *
 * Collects [SharedPlaylistRepository.observed] (the hot StateFlow of all cloud docs the user
 * is a member of) and calls [SharedPlaylistRepository.reconcileLocal] for each doc. Also
 * detects deletions by diffing the current snapshot against the previous one.
 *
 * Started once from [com.metrolist.music.App.initializeSocialFeatures] via
 * `SharedPlaylistSyncListener.get()`. Idempotent — calling [start] multiple times is safe.
 *
 * A short debounce (300 ms) batches rapid successive Firestore emissions (e.g. a bulk song-add)
 * into a single reconcile pass, avoiding redundant Room writes.
 */
@Singleton
@OptIn(kotlinx.coroutines.FlowPreview::class)
class SharedPlaylistSyncListener @Inject constructor(
    private val repository: SharedPlaylistRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val previousDocs = mutableMapOf<String, SharedPlaylistCloud>()

    /**
     * Start collecting cloud changes. Safe to call multiple times; only the first call
     * attaches the collector.
     */
    fun start() {
        if (job?.isActive == true) return
        previousDocs.clear()
        job = scope.launch {
            // Catch up on anything missed while the process was dead (incl. D8/D28 while offline).
            repository.reconcileAll()

            repository.observeAll()
                .debounce(DEBOUNCE_MS)
                .collect { current ->
                    val currentIds = current.map { it.id }.toSet()

                    // Capture badge inputs against the CURRENT previousDocs before anything
                    // below mutates it — badge math must see a stable prior snapshot.
                    val badgeInputs = current.map { it to previousDocs[it.id] }

                    // Detect deletions: ids we saw before that are no longer present.
                    // Each reconcile runs as a child coroutine so a slow playlist can't stall
                    // the others; overlapping work for the same playlist serializes on the
                    // repository's striped lock. Plain collect — never flatMapLatest, which
                    // would cancel an in-flight reconcile and strand half-applied state.
                    val deletedIds = previousDocs.keys - currentIds
                    deletedIds.forEach { id ->
                        Timber.tag(TAG).d("Cloud doc deleted: $id — reconciling")
                        launch { repository.reconcileLocal(id, cloudStateKnown = true) }
                    }

                    // Badge accounting runs sequentially BEFORE fan-out (stable snapshot).
                    badgeInputs.forEach { (cloud, previous) ->
                        repository.recordIncomingAdditions(cloud, previous)
                    }

                    // Fan out every present doc (idempotent; handles adds, renames, song diffs).
                    badgeInputs.forEach { (cloud, _) ->
                        launch {
                            repository.reconcileLocal(cloud.id, knownCloud = cloud, cloudStateKnown = true)
                        }
                    }

                    previousDocs.clear()
                    previousDocs.putAll(current.associateBy { it.id })
                }
        }
        Timber.tag(TAG).d("SharedPlaylistSyncListener started")
    }

    /** Stop collecting. Called on app teardown (not normally needed). */
    fun stop() {
        job?.cancel()
        job = null
        previousDocs.clear()
    }

    companion object {
        private const val TAG = "SharedPlaylistSyncListener"
        private const val DEBOUNCE_MS = 300L
    }
}
