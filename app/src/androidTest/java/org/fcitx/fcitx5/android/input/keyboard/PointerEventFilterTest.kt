/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PointerEventFilterTest {
    private fun event(action: Int, ids: IntArray = intArrayOf(5, 7)): MotionEvent {
        val properties = Array(ids.size) { index -> MotionEvent.PointerProperties().apply {
            id = ids[index]; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coords = Array(ids.size) { index -> MotionEvent.PointerCoords().apply {
            x = 10f + index * 30f; y = 20f; pressure = 0.7f; size = 0.5f
        } }
        return MotionEvent.obtain(10L, 20L, action, ids.size, properties, coords,
            1, 0, 1f, 1f, 2, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    @Test fun singleRemainingPointerGetsDownAndCancelledOwnerUpBecomesMove() {
        val down = event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        val ownerUp = event(MotionEvent.ACTION_POINTER_UP)
        val remainingUp = event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        try {
            for ((source, expected) in listOf(down to MotionEvent.ACTION_DOWN,
                    ownerUp to MotionEvent.ACTION_MOVE, remainingUp to MotionEvent.ACTION_UP)) {
                val filtered = PointerEventFilter.copy(source, 1 shl 7)!!
                try {
                    assertEquals(expected, filtered.actionMasked)
                    assertEquals(1, filtered.pointerCount)
                    assertEquals(7, filtered.getPointerId(0))
                    assertEquals(40f, filtered.x, 0f)
                } finally { filtered.recycle() }
            }
        } finally { down.recycle(); ownerUp.recycle(); remainingUp.recycle() }
    }

    @Test fun remainingMultiplePointersHaveRemappedActionIndex() {
        val source = event(MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            intArrayOf(3, 9, 5))
        try {
            val filtered = PointerEventFilter.copy(source, (1 shl 3) or (1 shl 5))!!
            try {
                assertEquals(MotionEvent.ACTION_POINTER_DOWN, filtered.actionMasked)
                assertEquals(1, filtered.actionIndex)
                assertEquals(5, filtered.getPointerId(filtered.actionIndex))
                assertEquals(2, filtered.pointerCount)
            } finally { filtered.recycle() }
        } finally { source.recycle() }
    }

    @Test fun moveHistoryAndMetadataAreCopiedWithoutMutatingSource() {
        val source = event(MotionEvent.ACTION_MOVE)
        val next = Array(2) { index -> MotionEvent.PointerCoords().also { coords ->
            source.getPointerCoords(index, coords); coords.x += 8f
        } }
        source.addBatch(30L, next, source.metaState)
        try {
            val filtered = PointerEventFilter.copy(source, 1 shl 7)!!
            try {
                assertEquals(1, filtered.historySize)
                assertEquals(40f, filtered.getHistoricalX(0, 0), 0f)
                assertEquals(48f, filtered.x, 0f)
                assertEquals(20L, filtered.getHistoricalEventTime(0))
                assertEquals(30L, filtered.eventTime)
                assertEquals(source.downTime, filtered.downTime)
                assertEquals(source.metaState, filtered.metaState)
                assertEquals(source.source, filtered.source)
                assertEquals(source.deviceId, filtered.deviceId)
                assertEquals(0.7f, filtered.pressure, 0f)
                assertEquals(2, source.pointerCount)
            } finally { filtered.recycle() }
        } finally { source.recycle() }
    }

    @Test fun noRemainingPointersReturnsNullAndCancelIsPreserved() {
        val source = event(MotionEvent.ACTION_CANCEL)
        try {
            assertNull(PointerEventFilter.copy(source, 0))
            val filtered = PointerEventFilter.copy(source, 1 shl 7)!!
            try { assertEquals(MotionEvent.ACTION_CANCEL, filtered.actionMasked) }
            finally { filtered.recycle() }
        } finally { source.recycle() }
    }
}
