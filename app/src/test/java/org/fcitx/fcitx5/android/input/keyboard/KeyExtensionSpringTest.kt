/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyExtensionSpringTest {
    @Test
    fun defaultTrajectoryMatchesMassOne260Over26Solution() {
        val omega = sqrt(91.0)
        listOf(0.0, 0.016, 0.08, 0.2, 0.4).forEach { time ->
            val spring = KeyExtensionSpring()
            spring.retarget(1.0)
            spring.advance(time)
            val decay = exp(-13.0 * time)
            val expected = 1.0 - decay * (cos(omega * time) + 13.0 / omega * sin(omega * time))
            val expectedVelocity = 260.0 / omega * decay * sin(omega * time)
            assertEquals(expected, spring.value, 1e-12)
            assertEquals(expectedVelocity, spring.velocity, 1e-12)
            assertEquals(1.0, spring.target, 0.0)
        }
    }

    @Test
    fun reversalAtSameInstantPreservesPositionAndVelocity() {
        val spring = KeyExtensionSpring()
        spring.retarget(1.0)
        spring.advance(0.08)
        val position = spring.value
        val velocity = spring.velocity
        spring.retarget(0.0)
        spring.advance(0.0)
        assertEquals(position, spring.value, 0.0)
        assertEquals(velocity, spring.velocity, 0.0)
        spring.retarget(0.0)
        assertEquals(velocity, spring.velocity, 0.0)
        spring.advance(1e-7)
        assertEquals(velocity, (spring.value - position) / 1e-7, 1e-4)
    }

    @Test
    fun analyticalSolutionIsIndependentOfFrameSubdivision() {
        val whole = KeyExtensionSpring()
        val frames = KeyExtensionSpring()
        whole.retarget(1.0)
        frames.retarget(1.0)
        whole.advance(0.24)
        repeat(24) { frames.advance(0.01) }
        assertEquals(whole.value, frames.value, 1e-12)
        assertEquals(whole.velocity, frames.velocity, 1e-12)
        whole.retarget(0.0)
        frames.retarget(0.0)
        whole.advance(0.12)
        repeat(12) { frames.advance(0.01) }
        assertEquals(whole.value, frames.value, 1e-12)
        assertEquals(whole.velocity, frames.velocity, 1e-12)
    }

    @Test
    fun resetAndSettlingRemoveResidualMotion() {
        val spring = KeyExtensionSpring(0.25)
        assertTrue(spring.isAtRest)
        spring.retarget(1.0)
        assertFalse(spring.isAtRest)
        spring.advance(0.1)
        spring.reset(0.4)
        assertEquals(0.4, spring.value, 0.0)
        assertEquals(0.4, spring.target, 0.0)
        assertEquals(0.0, spring.velocity, 0.0)
        assertTrue(spring.isAtRest)
        spring.retarget(0.0)
        spring.advance(3.0)
        assertTrue(spring.isAtRest)
        assertEquals(0.0, spring.value, 0.0)
        spring.advance(Double.MAX_VALUE)
        assertTrue(spring.isAtRest)
    }

    @Test
    fun velocityTurningPointIsNotMistakenForRest() {
        val spring = KeyExtensionSpring(stiffness = 25.0, damping = 8.0)
        spring.retarget(1.0)
        spring.advance(PI / 3.0)
        assertFalse(spring.isAtRest)
        assertTrue(spring.value > 1.0)
        spring.advance(3.0)
        assertTrue(spring.isAtRest)
    }

    @Test
    fun veryLongAdvanceSettlesBeforeComputingAnInfinitePhase() {
        val spring = KeyExtensionSpring()
        spring.retarget(1.0)
        spring.advance(Double.MAX_VALUE)
        assertEquals(1.0, spring.value, 0.0)
        assertEquals(0.0, spring.velocity, 0.0)
        assertTrue(spring.isAtRest)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCriticalDamping() {
        KeyExtensionSpring(stiffness = 169.0, damping = 26.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteTime() {
        KeyExtensionSpring().advance(Double.NaN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeTime() {
        KeyExtensionSpring().advance(-0.01)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteTarget() {
        KeyExtensionSpring().retarget(Double.POSITIVE_INFINITY)
    }
}
