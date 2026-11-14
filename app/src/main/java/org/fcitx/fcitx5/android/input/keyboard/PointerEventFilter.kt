/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.MotionEvent

/** Public-SDK equivalent of pointer splitting for a cancelled key extension. */
internal object PointerEventFilter {
    fun copy(event: MotionEvent, idBits: Int): MotionEvent? {
        val indices = (0 until event.pointerCount).filter {
            idBits and (1 shl event.getPointerId(it)) != 0
        }
        if (indices.isEmpty()) return null
        val properties = Array(indices.size) { MotionEvent.PointerProperties() }
        val coordinates = Array(indices.size) { MotionEvent.PointerCoords() }
        indices.forEachIndexed { target, source -> event.getPointerProperties(source, properties[target]) }
        val mappedIndex = indices.indexOf(event.actionIndex)
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> when {
                mappedIndex < 0 -> MotionEvent.ACTION_MOVE
                indices.size == 1 -> if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN)
                    MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP
                else -> event.actionMasked or (mappedIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            else -> event.actionMasked
        }
        fun sample(history: Int?) {
            indices.forEachIndexed { target, source ->
                if (history == null) event.getPointerCoords(source, coordinates[target])
                else event.getHistoricalPointerCoords(source, history, coordinates[target])
            }
        }
        val preserveHistory = action == MotionEvent.ACTION_MOVE && event.historySize > 0
        sample(if (preserveHistory) 0 else null)
        val result = MotionEvent.obtain(
            event.downTime, if (preserveHistory) event.getHistoricalEventTime(0) else event.eventTime,
            action, indices.size, properties, coordinates, event.metaState, event.buttonState,
            event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags, event.source, event.flags
        )
        if (preserveHistory) {
            for (history in 1 until event.historySize) {
                sample(history)
                result.addBatch(event.getHistoricalEventTime(history), coordinates, event.metaState)
            }
            sample(null)
            result.addBatch(event.eventTime, coordinates, event.metaState)
        }
        return result
    }
}
