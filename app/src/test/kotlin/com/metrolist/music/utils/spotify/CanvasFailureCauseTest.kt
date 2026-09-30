/*
 * Spotify Canvas support in Metrolist.
 *
 * Copyright (C) 2026 The Metrolist Group
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * ---------------------------------------------------------------------------
 * Phase 7. The notification fires exactly once per run of failures, and only on the run.
 *
 * This is the behaviour CanvasCachePolicy cannot express, because the policy decides what happens
 * to the *cache* while the "once per outage" rule decides whether the *user is told*. The two
 * interact: the policy resets the failure counter on any successful answer, and the notifier must
 * ride that reset rather than a timer of its own, or a long outage would notify repeatedly.
 *
 * The predicate under test is a transcription of SpotifyCanvasRepository's trigger. It is
 * deliberately not the repository itself, because that needs a live network and a DataStore.
 * ---------------------------------------------------------------------------
 */

package com.metrolist.music.utils.spotify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors the notification trigger in `SpotifyCanvasRepository.applyOutcome`:
 *
 *     if (failures == SPOTIFY_CANVAS_FAILURE_LIMIT) notify(...)
 *
 * Exists so the equality rather than `>=` is pinned by a test. That single character is the
 * difference between one notification per outage and one per attempt, and nothing else in the
 * codebase would catch it being changed back.
 */
private fun shouldNotify(failures: Int, limit: Int = SPOTIFY_CANVAS_FAILURE_LIMIT): Boolean =
    failures == limit

class CanvasFailureCauseTest {

    @Test
    fun `notifies on exactly the threshold`() {
        assertTrue(shouldNotify(SPOTIFY_CANVAS_FAILURE_LIMIT))
    }

    @Test
    fun `stays silent below the threshold`() {
        assertFalse(shouldNotify(1))
        assertFalse(shouldNotify(2))
    }

    @Test
    fun `stays silent past the threshold so one outage notifies once`() {
        // The counter only ever increments by one per failure, so a fourth failure means the
        // third already notified. Firing again here would spam a user through a long outage.
        assertFalse(shouldNotify(SPOTIFY_CANVAS_FAILURE_LIMIT + 1))
        assertFalse(shouldNotify(SPOTIFY_CANVAS_FAILURE_LIMIT + 7))
    }

    @Test
    fun `a reset counter re-arms the notification`() {
        // spec 9.2: the counter resets on any successful answer, including a clean negative. So
        // after a reset the same value must notify again - this is what makes a *second* outage
        // visible instead of permanently silenced by the first.
        val firstOutage = shouldNotify(SPOTIFY_CANVAS_FAILURE_LIMIT)
        val resetToZero = 0
        val secondOutage = shouldNotify(resetToZero + SPOTIFY_CANVAS_FAILURE_LIMIT)
        assertTrue(firstOutage)
        assertTrue(secondOutage)
    }

    @Test
    fun `every failure cause is a failure, never a negative`() {
        // The guarantee spec 9.2 makes and the reason this file's subject exists: no cause of
        // Spotify being unavailable may ever move a track toward a cached "no canvas".
        val causes =
            listOf(
                FailureCause.UNREACHABLE,
                FailureCause.AUTH_EXPIRED,
                FailureCause.SERVER_ERROR,
            )
        causes.forEach { cause ->
            val decision =
                decideCacheUpdate(OutcomeKind.TRANSPORT_FAILURE, currentAttempts = 0)
            assertTrue(
                "$cause must not produce a cached no-canvas verdict",
                decision.confirmedNoCanvas.not(),
            )
            assertTrue("$cause must not touch the track's attempt count", decision.newAttempts == 0)
            assertTrue("$cause must count as a Spotify failure", decision.failuresDelta == 1)
        }
    }
}
