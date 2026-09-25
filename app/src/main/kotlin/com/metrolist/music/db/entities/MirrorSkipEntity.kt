/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db.entities

import androidx.room.Entity

/**
 * Spotify-mirror misses per tracked playlist (SPEC_MIRROR_REVIEW).
 *
 * One row per (playlist, Spotify track) that intake definitively failed to match.
 * Dismissed rows stay so future intakes never resurface them; deleting a row means
 * "resolved" (matched on retry, added by hand, or untracked).
 */
@Entity(
    tableName = "mirror_skip",
    primaryKeys = ["localPlaylistId", "spotifyId"],
)
data class MirrorSkipEntity(
    val localPlaylistId: String,
    val spotifyId: String,
    val title: String,
    val artist: String,
    val durationMs: Int?,
    val skippedAt: Long,
    val dismissed: Boolean = false,
)
