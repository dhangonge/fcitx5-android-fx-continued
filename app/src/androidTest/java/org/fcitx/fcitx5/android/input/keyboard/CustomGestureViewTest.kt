/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomGestureViewTest {
    private fun onMain(test: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(test)

    private fun touch(view: CustomGestureView, action: Int, down: Long) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 20f, 20f, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun cancelDoesNotDispatchUpOrPerformClick() = onMain {
        val view = CustomGestureView(InstrumentationRegistry.getInstrumentation().targetContext)
        var clicks = 0
        var cancellations = 0
        val events = mutableListOf<CustomGestureView.GestureType>()
        view.setOnClickListener { clicks++ }
        view.onGestureCancelled = { cancellations++ }
        view.onGestureListener = CustomGestureView.OnGestureListener { _, event -> events.add(event.type); false }
        val down = SystemClock.uptimeMillis()
        touch(view, MotionEvent.ACTION_DOWN, down)
        touch(view, MotionEvent.ACTION_CANCEL, down)
        touch(view, MotionEvent.ACTION_UP, down)
        assertEquals(0, clicks)
        assertEquals(1, cancellations)
        assertEquals(listOf(CustomGestureView.GestureType.Down), events)
    }

    @Test fun cancelIsIdempotentAndNextOrdinaryTapStillClicksOnce() = onMain {
        val view = CustomGestureView(InstrumentationRegistry.getInstrumentation().targetContext)
        var clicks = 0
        var cancellations = 0
        view.setOnClickListener { clicks++ }
        view.onGestureCancelled = { cancellations++ }
        val down = SystemClock.uptimeMillis()
        touch(view, MotionEvent.ACTION_DOWN, down)
        view.cancelGestures()
        view.cancelGestures()
        touch(view, MotionEvent.ACTION_UP, down)
        val nextDown = SystemClock.uptimeMillis()
        touch(view, MotionEvent.ACTION_DOWN, nextDown)
        touch(view, MotionEvent.ACTION_UP, nextDown)
        assertEquals(1, cancellations)
        assertEquals(1, clicks)
    }

    @Test fun ordinaryTapDispatchesOneUpAndOneClick() = onMain {
        val view = CustomGestureView(InstrumentationRegistry.getInstrumentation().targetContext)
        var clicks = 0
        var ups = 0
        view.setOnClickListener { clicks++ }
        view.onGestureListener = CustomGestureView.OnGestureListener { _, event ->
            if (event.type == CustomGestureView.GestureType.Up) ups++
            false
        }
        val down = SystemClock.uptimeMillis()
        touch(view, MotionEvent.ACTION_DOWN, down)
        touch(view, MotionEvent.ACTION_UP, down)
        touch(view, MotionEvent.ACTION_UP, down)
        assertEquals(1, ups)
        assertEquals(1, clicks)
    }
}
