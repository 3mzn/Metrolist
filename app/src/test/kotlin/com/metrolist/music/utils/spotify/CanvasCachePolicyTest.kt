/*
 * Spotify Canvas support in Metrolist.
 *
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
 * Spec 9.2's failure taxonomy. These are the two safety guarantees that stop a flaky network
 * from permanently marking a good track as having no Canvas, so they are pinned here rather
 * than left reachable only through a live network.
 *
 * The second guarantee was a real bug found on device during Phase 3: the consecutive-failure
 * counter originally reset only when a Canvas was actually *found*, but most tracks have no
 * Canvas, so three network blips across a week could never reset it. See spec 14.1g.
 * ---------------------------------------------------------------------------
 */

package com.metrolist.music.utils.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasCachePolicyTest {
    @Test
    fun `a canvas found clears the track's attempt count and resets failures`() {
        val decision = decideCacheUpdate(OutcomeKind.SUCCESS, currentAttempts = 2)
        assertEquals(0, decision.newAttempts)
        assertFalse(decision.confirmedNoCanvas)
        assertEquals(0, decision.failuresDelta)
        assertTrue(decision.resetFailures)
    }

    @Test
    fun `clean negatives accumulate and only confirm on the third`() {
        val first = decideCacheUpdate(OutcomeKind.CLEAN_NEGATIVE, currentAttempts = 0)
        assertEquals(1, first.newAttempts)
        assertFalse("a single empty answer is not enough evidence", first.confirmedNoCanvas)

        val second = decideCacheUpdate(OutcomeKind.CLEAN_NEGATIVE, currentAttempts = 1)
        assertEquals(2, second.newAttempts)
        assertFalse(second.confirmedNoCanvas)

        val third = decideCacheUpdate(OutcomeKind.CLEAN_NEGATIVE, currentAttempts = 2)
        assertEquals(3, third.newAttempts)
        assertTrue("the third clean negative confirms no-canvas", third.confirmedNoCanvas)
    }

    @Test
    fun `a clean negative is a successful load and so resets the failure counter`() {
        // Most tracks have no Canvas, so this is the common path. If it did not reset, three
        // network blips on unrelated tracks would auto-disable the feature for good.
        val decision = decideCacheUpdate(OutcomeKind.CLEAN_NEGATIVE, currentAttempts = 0)
        assertEquals(0, decision.failuresDelta)
        assertTrue(decision.resetFailures)
    }

    @Test
    fun `a transport failure never confirms no-canvas, at any attempt count`() {
        // The core safety property: a timeout proves nothing about whether the track has a
        // Canvas, so it must not be able to poison the per-track verdict however many times
        // it happens.
        for (attempts in 0..10) {
            val decision = decideCacheUpdate(OutcomeKind.TRANSPORT_FAILURE, currentAttempts = attempts)
            assertFalse(
                "transport failure cached a negative at attempts=$attempts",
                decision.confirmedNoCanvas,
            )
            assertEquals("transport failure changed the attempt count", attempts, decision.newAttempts)
        }
    }

    @Test
    fun `a transport failure advances the consecutive-failure counter and does not reset it`() {
        val decision = decideCacheUpdate(OutcomeKind.TRANSPORT_FAILURE, currentAttempts = 0)
        assertEquals(1, decision.failuresDelta)
        assertFalse(decision.resetFailures)
    }

    @Test
    fun `the failure counter trips after three consecutive transport failures`() {
        var failures = 0
        repeat(3) { failures += decideCacheUpdate(OutcomeKind.TRANSPORT_FAILURE, currentAttempts = 0).failuresDelta }
        assertEquals(SPOTIFY_CANVAS_FAILURE_LIMIT, failures)

        // Any successful load clears it, so the trip is not permanent.
        val recovery = decideCacheUpdate(OutcomeKind.CLEAN_NEGATIVE, currentAttempts = 0)
        assertTrue(recovery.resetFailures)
        assertEquals(0, failures + recovery.failuresDelta - failures)
    }

    @Test
    fun `a transport failure between clean negatives does not push a track to confirmed`() {
        // Three empties spread across three timeouts must still not confirm, because the
        // policy only counts clean negatives toward the threshold.
        var attempts = 0
        repeat(3) {
            attempts = decideCacheUpdate(OutcomeKind.CLEAN_NEGATIVE, attempts).newAttempts
            attempts = decideCacheUpdate(OutcomeKind.TRANSPORT_FAILURE, attempts).newAttempts
        }
        assertEquals(3, attempts)
    }
}
