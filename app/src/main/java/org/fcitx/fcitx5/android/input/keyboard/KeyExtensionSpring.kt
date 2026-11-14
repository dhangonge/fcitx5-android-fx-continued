/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Analytic mass-one spring. Only finite, positive, underdamped parameters are supported. */
internal class KeyExtensionSpring(
    initialValue: Double = 0.0,
    stiffness: Double = 260.0,
    damping: Double = 26.0
) {
    private val gamma = damping / 2.0
    private val frequencySquared = stiffness - gamma * gamma
    private val omega: Double

    init {
        require(initialValue.isFinite())
        require(stiffness.isFinite() && stiffness > 0.0 && damping.isFinite() && damping > 0.0)
        require(frequencySquared.isFinite() && frequencySquared > 0.0) { "Spring must be underdamped" }
        omega = sqrt(frequencySquared)
    }

    var value = initialValue
        private set
    var velocity = 0.0
        private set
    var target = initialValue
        private set

    val isAtRest: Boolean
        get() = value == target && velocity == 0.0

    /** Caller advances to the interruption time first; retarget itself preserves p and v. */
    fun retarget(target: Double) {
        require(target.isFinite() && (value - target).isFinite())
        this.target = target
    }

    fun advance(elapsedSeconds: Double) {
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0.0)
        if (elapsedSeconds == 0.0 || isAtRest) return
        val decay = exp(-gamma * elapsedSeconds)
        if (decay == 0.0) {
            reset(target)
            return
        }
        val a = value - target
        val b = (velocity + gamma * a) / omega
        val angle = omega * elapsedSeconds
        val c = cos(angle)
        val s = sin(angle)
        val displacement = a * c + b * s
        value = target + decay * displacement
        velocity = decay * (-gamma * displacement + omega * (-a * s + b * c))
        // Envelopes, not instantaneous zero crossings, determine whether motion is finished.
        val envelope = decay * hypot(a, b)
        if (envelope <= 0.0001 && envelope * (gamma + omega) <= 0.001) reset(target)
    }

    fun reset(value: Double) {
        require(value.isFinite())
        this.value = value
        velocity = 0.0
        target = value
    }
}
