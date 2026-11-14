/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import kotlin.math.min

internal interface KeyExtensionPathSink {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float)
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float)
    fun close()
}

/** One contour in key-local coordinates; the lower key edges never move. */
internal class KeyExtensionGeometry(
    keyWidth: Float,
    keyHeight: Float,
    extensionWidth: Float,
    extensionRise: Float,
    extensionLeft: Float,
    keyRadius: Float,
    extensionRadius: Float
) {
    init {
        require(listOf(keyWidth, keyHeight, extensionWidth, extensionRise, extensionLeft,
            keyRadius, extensionRadius).all { it.isFinite() }) { "Geometry must be finite" }
        require(keyWidth > 0f && keyHeight > 0f && extensionWidth > 0f && extensionRise >= 0f)
        require(kotlin.math.abs(extensionLeft.toDouble() + extensionWidth) <= Float.MAX_VALUE)
    }

    private val w = keyWidth.toDouble()
    private val h = keyHeight.toDouble()
    private val width = extensionWidth.toDouble()
    private val rise = extensionRise.toDouble()
    private val extensionX = extensionLeft.toDouble()
    private val r = keyRadius.toDouble().coerceIn(0.0, min(w, h) / 2.0)
    private val upperR = extensionRadius.toDouble().coerceIn(0.0, min(width, rise) / 2.0)

    fun trace(progress: Float, sink: KeyExtensionPathSink) {
        val t = if (progress.isNaN()) 0.0 else progress.toDouble().coerceIn(0.0, 1.0)
        val left = extensionX * t
        val right = w + (extensionX + width - w) * t
        val top = -rise * t
        val shoulder = r - (r + upperR * 0.4) * t
        val upper = min(r + (upperR - r) * t, (right - left) / 2.0)
        val lower = min(upperR * t, (shoulder - top - upper).coerceAtLeast(0.0) / 2.0)
        val inner = min(upperR * 0.8 * t, (h - r - shoulder).coerceAtLeast(0.0))
        val join = shoulder + inner
        val k = 0.5522847498307936
        fun move(x: Double, y: Double) = sink.moveTo(x.toFloat(), y.toFloat())
        fun line(x: Double, y: Double) = sink.lineTo(x.toFloat(), y.toFloat())
        fun cubic(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) =
            sink.cubicTo(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), x3.toFloat(), y3.toFloat())

        // The accepted keyContour, with theme radii instead of its 6/10px constants.
        move(r, h)
        line(w - r, h)
        cubic(w - r + k * r, h, w, h - r + k * r, w, h - r)
        line(w, join)
        cubic(w, join - k * inner, right - lower - k * (right - lower - w), shoulder, right - lower, shoulder)
        cubic(right - lower + k * lower, shoulder, right, shoulder - lower + k * lower, right, shoulder - lower)
        line(right, top + upper)
        cubic(right, top + upper - k * upper, right - upper + k * upper, top, right - upper, top)
        line(left + upper, top)
        cubic(left + upper - k * upper, top, left, top + upper - k * upper, left, top + upper)
        line(left, shoulder - lower)
        cubic(left, shoulder - lower + k * lower, left + lower - k * lower, shoulder, left + lower, shoulder)
        cubic(left + lower + k * (-left - lower), shoulder, 0.0, join - k * inner, 0.0, join)
        line(0.0, h - r)
        cubic(0.0, h - r + k * r, r - k * r, h, r, h)
        sink.close()
    }
}
