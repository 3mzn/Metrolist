/*
 * Spotify Canvas support in Metrolist.
 *
 * Copyright (C) 2025 maxrave-dev (SimpMusic)
 * Copyright (C) 2025 The Metrolist Group
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * ---------------------------------------------------------------------------
 * Spec 9.2's failure taxonomy, as pure logic.
 *
 * This is Metrolist-side code, not ported SimpMusic code - SimpMusic has no equivalent,
 * it just returns Resource.Error and lets the caller decide. But the rule it encodes
 * ("only clean negatives may ever be cached as no-canvas") is the one guarantee that stops
 * a flaky network from permanently burying a Canvas the user actually wants, so it is
 * extracted here to be unit-testable rather than reachable only through a live network.
 *
 * The behaviour mirrors SimpMusic's intent, not its wording: SimpMusic simply never
 * cached negatives at all, so any caching policy here is strictly more conservative than
 * the reference implementation.
 * ---------------------------------------------------------------------------
 */

package com.metrolist.music.utils.spotify

/** Why a lookup ended the way it did. */
internal enum class OutcomeKind {
    /** A Canvas was found. */
    SUCCESS,

    /** Spotify answered normally and there is genuinely no Canvas for this track. */
    CLEAN_NEGATIVE,

    /** Spotify was unreachable, timed out, 5xx, or rejected the token. Says nothing about the track. */
    TRANSPORT_FAILURE,
}

/**
 * What the cache should do in response to an [OutcomeKind].
 *
 * @param newAttempts the track's confirmed-empty attempt count after this outcome
 * @param confirmedNoCanvas true only once a clean negative has reached the threshold
 * @param failuresDelta how much to add to the consecutive-Spotify-failure counter
 * @param resetFailures whether the consecutive-failure counter should be zeroed
 */
internal data class CacheDecision(
    val newAttempts: Int,
    val confirmedNoCanvas: Boolean,
    val failuresDelta: Int,
    val resetFailures: Boolean,
)

/** Spec 9.2: a "no canvas" verdict is only trusted after this many confirmed-empty attempts. */
internal const val NEGATIVE_CACHE_THRESHOLD = 3

/**
 * Decides how a lookup outcome affects the cache.
 *
 * The two rules that matter, and the reason this is a separate testable function:
 *
 * 1. **A transport failure must never, under any circumstance, produce a cached "no canvas".**
 *    A timeout proves nothing about whether the track has a Canvas. It moves only the
 *    consecutive-failure counter, which is what eventually auto-disables a feature that is
 *    talking to a broken API - as opposed to one that is wrongly convinced a track is empty.
 *
 * 2. **The consecutive-failure counter resets on any successful load, including a clean
 *    negative.** Most tracks have no Canvas, so a Canvas actually being *found* is the rare
 *    case. If only that reset it, three network blips across a week - on tracks that
 *    legitimately have no Canvas - would accumulate and auto-disable the feature permanently.
 */
internal fun decideCacheUpdate(
    kind: OutcomeKind,
    currentAttempts: Int,
    threshold: Int = NEGATIVE_CACHE_THRESHOLD,
): CacheDecision =
    when (kind) {
        OutcomeKind.SUCCESS ->
            CacheDecision(
                newAttempts = 0,
                confirmedNoCanvas = false,
                failuresDelta = 0,
                resetFailures = true,
            )

        OutcomeKind.CLEAN_NEGATIVE -> {
            val attempts = currentAttempts + 1
            CacheDecision(
                newAttempts = attempts,
                confirmedNoCanvas = attempts >= threshold,
                failuresDelta = 0,
                resetFailures = true,
            )
        }

        OutcomeKind.TRANSPORT_FAILURE ->
            CacheDecision(
                newAttempts = currentAttempts,
                confirmedNoCanvas = false,
                failuresDelta = 1,
                resetFailures = false,
            )
    }
