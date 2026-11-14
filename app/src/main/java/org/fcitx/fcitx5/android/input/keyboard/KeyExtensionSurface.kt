/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** The owner keeps this surface and its contour both at rest and in the window draw host. */
internal class KeyExtensionSurface(private val owner: KeyView) : KeyExtensionPathSink,
    Choreographer.FrameCallback {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val metrics = Paint.FontMetrics()
    private val location = IntArray(2)
    private val spring = KeyExtensionSpring()
    private var geometry: KeyExtensionGeometry? = null
    private var width = 0f
    private var height = 0f
    private var fill = Color.TRANSPARENT
    private var shadow = Color.TRANSPARENT
    private var stroke = false
    private var solid = false
    private var labels = emptyArray<String>()
    private var extensionWidth = 0f
    private var extensionLeft = 0f
    private var rise = 0f
    private var cellWidth = 0f
    private var rowHeight = 0f
    private var hostView: DrawHost? = null
    private var framePending = false
    private var lastFrame = 0L
    private var selected = 0
    private var selectionValid = true
    private var committed = false
    private val density = owner.resources.displayMetrics.density
    private val padding = 8f * density
    private val hostPadding = 10f * density
    private val minInteractiveProgress = 0.86f
    private var safeInset = padding
    private var rowX = 0f
    private var rowY = 0f
    val active: Boolean get() = hostView != null
    val background: Drawable = contourDrawable(null, false)

    fun configure(fillColor: Int, shadowColor: Int, bordered: Boolean, borderStroke: Boolean) {
        cancel()
        fill = fillColor
        shadow = shadowColor
        solid = bordered
        stroke = borderStroke
        rebuild()
    }

    fun resize(w: Int, h: Int) {
        val newWidth = (w - owner.hMargin * 2).toFloat()
        val newHeight = (h - owner.vMargin * 2).toFloat()
        if (width == newWidth && height == newHeight) return
        cancel()
        width = newWidth
        height = newHeight
        rebuild()
    }

    fun mask(color: Int, outline: Boolean = false): Drawable = contourDrawable(color, outline)

    private fun contourDrawable(color: Int?, outline: Boolean): Drawable = object : Drawable() {
        private var opacity = 255
        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.translate(owner.hMargin.toFloat(), owner.vMargin.toFloat())
            paint.clearShadowLayer()
            paint.alpha = opacity
            if (color == null) {
                if (solid) {
                    paint.style = Paint.Style.FILL
                    paint.color = fill
                    if (!stroke && Color.alpha(shadow) > 0) {
                        if (Build.VERSION.SDK_INT >= 28 && canvas.isHardwareAccelerated) {
                            paint.setShadowLayer(density * (0.35f + 2f * progress()), 0f,
                                density * (0.7f + 1.5f * progress()), shadow)
                        } else {
                            // API 23 path shadows do not require a window-sized software bitmap.
                            paint.color = shadow
                            canvas.translate(0f, density)
                            canvas.drawPath(path, paint)
                            canvas.translate(0f, -density)
                            paint.color = fill
                        }
                    }
                    canvas.drawPath(path, paint)
                    paint.clearShadowLayer()
                    if (stroke) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = density
                        paint.color = shadow
                        canvas.drawPath(path, paint)
                    }
                }
            } else {
                paint.color = color
                paint.style = if (outline) Paint.Style.STROKE else Paint.Style.FILL
                paint.strokeWidth = 2f * density
                canvas.drawPath(path, paint)
            }
            canvas.restoreToCount(save)
        }
        override fun getPadding(padding: android.graphics.Rect): Boolean {
            if (color != null) return super.getPadding(padding)
            padding.set(owner.hMargin, owner.vMargin, owner.hMargin, owner.vMargin)
            return owner.hMargin != 0 || owner.vMargin != 0
        }
        override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun progress(): Float = spring.value.toFloat().coerceIn(0f, 1f)

    private fun rebuild() {
        if (width <= 0f || height <= 0f) { path.rewind(); return }
        geometry = KeyExtensionGeometry(width, height,
            if (active) extensionWidth else width, if (active) rise else 0f,
            if (active) extensionLeft else 0f,
            if (solid) owner.radius else 0f, owner.keyExtensionRadius)
        trace()
    }

    private fun trace() {
        path.rewind()
        geometry?.trace(progress(), this)
        background.invalidateSelf()
        hostView?.invalidate()
        owner.invalidateKeyExtensionAppearance()
    }

    fun show(host: ViewGroup, options: Array<String>, textSize: Float,
             typeface: Typeface?, animate: Boolean): Boolean {
        if (host !is FrameLayout || !host.isAttachedToWindow || !owner.isAttachedToWindow ||
            !solid || width <= 0f || height <= 0f || options.isEmpty() ||
            !textSize.isFinite() || textSize <= 0f || options.any { it.isEmpty() || '\n' in it || '\r' in it }) return false
        val sameContent = labels.contentEquals(options) && labelPaint.textSize == textSize &&
            labelPaint.typeface == typeface
        labelPaint.textSize = textSize
        labelPaint.typeface = typeface
        labelPaint.getFontMetrics(metrics)
        val extensionRadius = owner.keyExtensionRadius
        val newSafeInset = max(padding, extensionRadius + density)
        val maxLabelWidth = options.maxOf { labelPaint.measureText(it) }
        val newCellWidth = max(width, maxLabelWidth + padding * 2f)
        val rowRequired = newCellWidth * options.size
        // At the readiness threshold the cap, not just its final shape, must fit unscaled cells.
        val newExtensionWidth = max(width, (rowRequired + newSafeInset * 2f -
            (1f - minInteractiveProgress) * width) / minInteractiveProgress)
        val newRowHeight = max(height * 0.6f, metrics.bottom - metrics.top + padding)
        // The lower cap corners stay above shoulder >= -0.4r, lower radius <= r.
        val newRise = (newRowHeight + newSafeInset * 2f + extensionRadius * 1.4f) /
            minInteractiveProgress
        if (active && (!sameContent || extensionWidth != newExtensionWidth || rise != newRise ||
                cellWidth != newCellWidth || rowHeight != newRowHeight)) cancel()
        owner.getKeyExtensionLocation(location)
        val baseX = location[0] + owner.hMargin.toFloat()
        val baseY = location[1] + owner.vMargin.toFloat()
        host.getLocationInWindow(location)
        val hostX = location[0].toFloat()
        val hostY = location[1].toFloat()
        val guard = hostPadding
        if (!newExtensionWidth.isFinite() || !newRise.isFinite() ||
            newExtensionWidth > host.width - guard * 2f || baseY - newRise < hostY + guard ||
            baseY + height > hostY + host.height - guard) return false
        val newExtensionLeft = ((width - newExtensionWidth) / 2f).coerceIn(
            hostX + guard - baseX, hostX + host.width - guard - newExtensionWidth - baseX)
        val sameGeometry = extensionWidth == newExtensionWidth && rise == newRise &&
            extensionLeft == newExtensionLeft && cellWidth == newCellWidth && rowHeight == newRowHeight
        if (hostView?.parent !== host || !sameContent || !sameGeometry) cancel()
        val newSession = !active || spring.target != 1.0
        advanceTo(System.nanoTime())
        extensionWidth = newExtensionWidth
        extensionLeft = newExtensionLeft
        cellWidth = newCellWidth
        rowHeight = newRowHeight
        rise = newRise
        safeInset = newSafeInset
        if (!sameContent || labels.isEmpty()) labels = options.copyOf()
        if (newSession) selected = 0
        selectionValid = false
        committed = false
        if (hostView == null) {
            val left = min(0f, extensionLeft) - hostPadding
            val right = max(width, extensionLeft + extensionWidth) + hostPadding
            val top = -rise - hostPadding
            val child = DrawHost()
            hostView = child
            child.translationX = baseX - owner.hMargin - hostX + left
            child.translationY = baseY - owner.vMargin - hostY + top
            host.addView(child, FrameLayout.LayoutParams(ceil(right - left + owner.hMargin * 2).toInt(),
                ceil(height - top + hostPadding + owner.vMargin * 2).toInt(), Gravity.TOP or Gravity.LEFT))
            owner.captureKeyExtensionAppearance()
        }
        rebuild()
        retarget(1.0, animate)
        return true
    }

    private fun updateRow(): Boolean {
        val t = progress()
        val left = extensionLeft * t
        val right = width + (extensionLeft + extensionWidth - width) * t
        val required = cellWidth * labels.size
        rowX = (left + right - required) / 2f
        rowY = -rise * t + safeInset
        return labels.isNotEmpty() && t > minInteractiveProgress &&
            right - left >= required + safeInset * 2f - 0.01f * density &&
            rowY + rowHeight <= -owner.keyExtensionRadius * 1.4f - safeInset + 0.01f * density
    }

    private fun labelAlpha(): Int = (255f * ((progress() - minInteractiveProgress) /
        (1f - minInteractiveProgress)).coerceIn(0f, 1f)).toInt()

    private fun synchronizeInteraction(): Boolean {
        if (!active) { selectionValid = false; return false }
        if (!owner.isAttachedToWindow || !owner.isKeyExtensionAppearanceUnchanged()) {
            cancel(); return false
        }
        advanceTo(System.nanoTime())
        trace()
        val ready = !committed && spring.target == 1.0 && updateRow() && labelAlpha() > 0
        if (!ready) selectionValid = false
        return ready
    }

    fun select(windowX: Float, windowY: Float): Int? {
        if (!synchronizeInteraction()) return null
        owner.getKeyExtensionLocation(location)
        val x = windowX - location[0] - owner.hMargin
        val y = windowY - location[1] - owner.vMargin
        val inBase = x in 0f..width && y in 0f..height
        val inRow = x >= rowX && x < rowX + cellWidth * labels.size &&
            y >= rowY && y <= rowY + rowHeight
        selectionValid = windowX.isFinite() && windowY.isFinite() && (inBase || inRow)
        if (!selectionValid) return null
        selected = if (inBase) 0 else ((x - rowX) / cellWidth).toInt().coerceIn(0, labels.lastIndex)
        hostView?.invalidate()
        return selected
    }

    fun complete(): Int? {
        if (!synchronizeInteraction() || !selectionValid) return null
        committed = true
        return selected
    }

    fun dismiss(animate: Boolean) {
        if (!active) return
        committed = true
        selectionValid = false
        retarget(0.0, animate)
    }

    fun cancel() {
        if (framePending) Choreographer.getInstance().removeFrameCallback(this)
        framePending = false
        lastFrame = 0L
        spring.reset(0.0)
        val child = hostView
        hostView = null
        (child?.parent as? ViewGroup)?.removeView(child)
        labels = emptyArray()
        selectionValid = false
        committed = false
        rebuild()
        owner.invalidateKeyExtensionAppearance()
    }

    private fun advanceTo(now: Long) {
        val currentTime = max(now, lastFrame)
        if (lastFrame != 0L) spring.advance((currentTime - lastFrame) / 1_000_000_000.0)
        lastFrame = currentTime
    }

    private fun retarget(target: Double, animate: Boolean) {
        advanceTo(System.nanoTime())
        if (!animate) {
            if (target == 0.0) { cancel(); return }
            spring.reset(target)
            if (framePending) Choreographer.getInstance().removeFrameCallback(this)
            framePending = false
            lastFrame = 0L
        } else {
            spring.retarget(target)
            if (!framePending && !spring.isAtRest) {
                framePending = true
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        trace()
    }

    override fun doFrame(frameTimeNanos: Long) {
        framePending = false
        if (!active || !owner.isAttachedToWindow || !owner.isKeyExtensionAppearanceUnchanged()) {
            cancel(); return
        }
        advanceTo(frameTimeNanos)
        if (spring.isAtRest && spring.target == 0.0) { cancel(); return }
        trace()
        if (!spring.isAtRest) {
            framePending = true
            Choreographer.getInstance().postFrameCallback(this)
        } else lastFrame = 0L
    }

    fun drawPressOverlay(canvas: Canvas) {
        if (!owner.isPressed || !owner.isEnabled) return
        val save = canvas.save()
        canvas.translate(owner.hMargin.toFloat(), owner.vMargin.toFloat())
        paint.clearShadowLayer()
        val outline = owner.bordered && stroke && !owner.rippled
        paint.style = if (outline) Paint.Style.STROKE else Paint.Style.FILL
        paint.strokeWidth = 2f * density
        paint.color = if (outline) shadow else owner.theme.keyPressHighlightColor
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
    }

    fun drawLabels(canvas: Canvas) {
        val alpha = labelAlpha()
        if (!updateRow() || alpha <= 0) { selectionValid = false; return }
        val y = owner.vMargin + rowY
        val x = owner.hMargin + rowX
        labelPaint.color = owner.theme.popupTextColor
        labelPaint.alpha = alpha
        for (i in labels.indices) {
            if (i == selected) {
                paint.clearShadowLayer()
                paint.style = Paint.Style.FILL
                paint.color = owner.theme.keyPressHighlightColor
                paint.alpha = alpha
                canvas.drawRoundRect(x + i * cellWidth, y, x + (i + 1) * cellWidth,
                    y + rowHeight, 5f * density, 5f * density, paint)
            }
            canvas.drawText(labels[i], x + (i + 0.5f) * cellWidth,
                y + (rowHeight - metrics.top - metrics.bottom) / 2f, labelPaint)
        }
    }

    private inner class DrawHost : View(owner.context) {
        private val origin = IntArray(2)
        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        override fun onDraw(canvas: Canvas) {
            if (!active) return
            if (!owner.isKeyExtensionAppearanceUnchanged()) { cancel(); return }
            getLocationInWindow(origin)
            owner.drawKeyExtension(canvas, origin[0], origin[1])
        }
        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            if (hostView === this) cancel()
        }
    }

    override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
    override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
    override fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float) = path.quadTo(x1, y1, x2, y2)
    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
        path.cubicTo(x1, y1, x2, y2, x3, y3)
    override fun close() = path.close()
}
