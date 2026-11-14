/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyExtensionGeometryTest {
    private data class Command(val type: Char, val coordinates: List<Float>)

    private class RecordingSink : KeyExtensionPathSink {
        val commands = mutableListOf<Command>()
        override fun moveTo(x: Float, y: Float) { record('M', x, y) }
        override fun lineTo(x: Float, y: Float) { record('L', x, y) }
        override fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float) { record('Q', x1, y1, x2, y2) }
        override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            record('C', x1, y1, x2, y2, x3, y3)
        }
        override fun close() { record('Z') }
        private fun record(type: Char, vararg coordinates: Float) {
            commands += Command(type, coordinates.toList())
        }
    }

    private fun geometry(left: Float = -56f, keyRadius: Float = 6f, extensionRadius: Float = 10f) =
        KeyExtensionGeometry(40f, 48f, 152f, 51f, left, keyRadius, extensionRadius)

    private fun trace(geometry: KeyExtensionGeometry, progress: Float): List<Command> =
        RecordingSink().also { geometry.trace(progress, it) }.commands

    @Test
    fun singleClosedContourKeepsBaseFixedThroughoutExpansion() {
        val shape = geometry()
        val base = trace(shape, 0f)
        listOf(0f, 0.2f, 0.6f, 0.86f, 1f).forEach { progress ->
            val commands = trace(shape, progress)
            assertEquals(16, commands.size)
            assertEquals(1, commands.count { it.type == 'M' })
            assertEquals(1, commands.count { it.type == 'Z' })
            assertEquals('M', commands.first().type)
            assertEquals('Z', commands.last().type)
            listOf(0, 1, 2, 13, 14).forEach { assertEquals(base[it], commands[it]) }
            assertTrue(commands.flatMap { it.coordinates }.all { it.isFinite() })
            val top = -51f * progress
            assertEquals(top, commands[7].coordinates[5], 0.0001f)
            assertEquals(top, commands[8].coordinates[1], 0.0001f)
            assertEquals(-56f * progress, commands[9].coordinates[4], 0.0001f)
            assertEquals(40f + 56f * progress, commands[5].coordinates[4], 0.0001f)
        }
    }

    @Test
    fun expandedShoulderControlPointsMatchAcceptedContour() {
        val commands = trace(geometry(), 1f)
        val k = 0.5522847498307936f
        val expectedRight = listOf(40f, 4f - k * 8f, 86f - k * 46f, -4f, 86f, -4f)
        val expectedLeft = listOf(-46f + k * 46f, -4f, 0f, 4f - k * 8f, 0f, 4f)
        expectedRight.zip(commands[4].coordinates).forEach { (expected, actual) ->
            assertEquals(expected, actual, 0.00001f)
        }
        expectedLeft.zip(commands[12].coordinates).forEach { (expected, actual) ->
            assertEquals(expected, actual, 0.00001f)
        }
    }

    @Test
    fun closedStateStaysInsideOriginalRoundedKey() {
        val coordinates = trace(geometry(), 0f).flatMap { it.coordinates }.chunked(2)
        coordinates.forEach { (x, y) ->
            assertTrue(x in 0f..40f)
            assertTrue(y in 0f..48f)
        }
        assertEquals(listOf(6f, 48f), trace(geometry(), 0f).first().coordinates)
    }

    @Test
    fun boundaryAlignedExtensionDoesNotAssumeCenter() {
        listOf(0f, -112f).forEach { left ->
            val shape = geometry(left)
            listOf(0.2f, 0.6f, 1f).forEach { progress ->
                val commands = trace(shape, progress)
                assertEquals(left * progress, commands[9].coordinates[4], 0.0001f)
                assertEquals(40f + (left + 112f) * progress, commands[5].coordinates[4], 0.0001f)
                assertEquals(listOf(6f, 48f), commands.first().coordinates)
            }
        }
    }

    @Test
    fun clampsProgressAndRadiiWithoutNonFiniteCoordinates() {
        val shape = geometry()
        assertEquals(trace(shape, 0f), trace(shape, -1f))
        assertEquals(trace(shape, 0f), trace(shape, Float.NaN))
        assertEquals(trace(shape, 0f), trace(shape, Float.NEGATIVE_INFINITY))
        assertEquals(trace(shape, 1f), trace(shape, 2f))
        assertEquals(trace(shape, 1f), trace(shape, Float.POSITIVE_INFINITY))
        assertEquals(trace(geometry(keyRadius = 0f, extensionRadius = 0f), 1f),
            trace(geometry(keyRadius = -10f, extensionRadius = -10f), 1f))
        val tiny = KeyExtensionGeometry(8f, 6f, 12f, 2f, -2f, 1000f, 1000f)
        assertEquals(listOf(3f, 6f), trace(tiny, 0f).first().coordinates)
        listOf(0f, 0.2f, 0.6f, 0.86f, 1f).forEach {
            assertTrue(trace(tiny, it).flatMap { command -> command.coordinates }.all { value -> value.isFinite() })
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteGeometry() {
        KeyExtensionGeometry(40f, 48f, 152f, 51f, Float.NaN, 6f, 10f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyBase() {
        KeyExtensionGeometry(0f, 48f, 152f, 51f, -56f, 6f, 10f)
    }
}
