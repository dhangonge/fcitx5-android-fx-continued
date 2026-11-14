/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.shapes.OvalShape
import android.graphics.drawable.ShapeDrawable
import android.view.View
import android.view.ViewPropertyAnimator
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.input.motion.MotionDomain
import org.fcitx.fcitx5.android.input.motion.MotionPolicy
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.borderlessRippleDrawable
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.views.imageDrawable
import splitties.views.imageResource
import splitties.views.padding

class ToolButton(context: Context) : CustomGestureView(context) {

    companion object {
        val disableAnimation get() = !MotionPolicy.enabled(MotionDomain.Tools)
    }

    val image = imageView {
        isClickable = false
        isFocusable = false
        padding = dp(10)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    val textView = view(::AutoScaleTextView) {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f)
        scaleMode = AutoScaleTextView.Mode.Proportional
        gravity = gravityCenter
        visibility = View.GONE
    }

    private val modern = ThemeManager.prefs.keyboardUiStyle.getValue() == ThemePrefs.KeyboardUiStyle.Modern
    private var theme: Theme? = null
    private var isActive: Boolean = false
    private var usingCustomDrawable: Boolean = false
    private var customDrawableTintWithTheme: Boolean = false
    @ColorInt
    private var pressHighlightColor: Int = Color.TRANSPARENT

    var iconRotation: Float
        get() = image.rotation
        set(value) {
            image.rotation = value
        }

    constructor(context: Context, @DrawableRes icon: Int, theme: Theme) : this(context) {
        this.theme = theme
        image.imageTintList = ColorStateList.valueOf(theme.altKeyTextColor)
        textView.setTextColor(theme.altKeyTextColor)
        setIcon(icon)
        setPressHighlightColor(theme.keyPressHighlightColor)
        add(image, lParams(wrapContent, wrapContent, gravityCenter))
        add(textView, lParams(wrapContent, wrapContent, gravityCenter))
    }

    fun iconAnimate(): ViewPropertyAnimator = image.animate()

    /**
     * End a touch ripple before this button's containing UI is replaced.
     */
    fun clearTransientPressState() {
        cancelGestures()
        jumpDrawablesToCurrentState()
    }

    fun setIcon(@DrawableRes icon: Int) {
        usingCustomDrawable = false
        textView.visibility = View.GONE
        image.visibility = View.VISIBLE
        image.imageTintList = currentIconColor()?.let { ColorStateList.valueOf(it) }
        image.imageResource = icon
        applyBackground()
    }

    fun setIconFromDrawable(drawable: Drawable?, tintWithTheme: Boolean = true) {
        if (drawable != null) {
            usingCustomDrawable = true
            customDrawableTintWithTheme = tintWithTheme
            textView.visibility = View.GONE
            image.visibility = View.VISIBLE
            image.imageDrawable = drawable.mutate()
            if (tintWithTheme) {
                image.imageTintList = null
                currentIconColor()?.let { image.imageDrawable?.setTint(it) }
            } else {
                image.imageTintList = null
                image.imageDrawable?.setTintList(null)
            }
            applyBackground()
        }
    }

    fun setText(text: String?) {
        if (!text.isNullOrEmpty()) {
            image.visibility = View.GONE
            textView.visibility = View.VISIBLE
            textView.text = text
            updateAppearance()
        }
    }

    fun setPressHighlightColor(@ColorInt color: Int) {
        pressHighlightColor = color
        applyBackground()
    }

    /**
     * Set the active state of this button.
     * Modern uses a shared active treatment for icons and text; Classic keeps its legacy treatment.
     */
    fun setActive(active: Boolean) {
        if (isActive == active || theme == null) return
        isActive = active
        updateAppearance()
    }

    private fun updateAppearance() {
        val theme = theme ?: return
        val iconColor = currentIconColor()
        if (usingCustomDrawable) {
            if (customDrawableTintWithTheme) {
                image.imageTintList = null
                if (iconColor != null) image.imageDrawable?.setTint(iconColor)
            } else {
                image.imageTintList = null
                image.imageDrawable?.setTintList(null)
            }
        } else {
            image.imageTintList = iconColor?.let { ColorStateList.valueOf(it) }
        }
        textView.setTextColor(if (modern && isActive) activeForeground(theme) else theme.altKeyTextColor)
        applyBackground()
    }

    @ColorInt
    private fun currentIconColor(): Int? {
        val theme = theme ?: return null
        return when {
            modern && isActive -> activeForeground(theme)
            isActive -> theme.accentKeyBackgroundColor
            else -> theme.altKeyTextColor
        }
    }

    private fun activeForeground(theme: Theme): Int =
        if (theme is Theme.Custom) theme.genericActiveForegroundColor else theme.keyTextColor

    fun onMotionPolicyChanged() {
        clearTransientPressState()
        applyBackground()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyBackground()
    }

    override fun onDetachedFromWindow() {
        image.animate().cancel()
        clearTransientPressState()
        super.onDetachedFromWindow()
    }

    private fun applyBackground() {
        val theme = theme ?: return
        val isText = textView.visibility == View.VISIBLE
        if (isActive && (modern || isText)) {
            val activeBg = GradientDrawable().apply {
                setColor(if (modern) {
                    if (theme is Theme.Custom) theme.genericActiveBackgroundColor
                    else androidx.core.graphics.ColorUtils.blendARGB(theme.barColor, theme.accentKeyBackgroundColor, 0.18f)
                } else theme.accentKeyBackgroundColor and 0x00ffffff or (0x3f shl 24))
                cornerRadius = dp(8).toFloat()
            }
            background = if (disableAnimation) {
                val pressedOverlay = ShapeDrawable(OvalShape()).apply { paint.color = pressHighlightColor }
                StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), LayerDrawable(arrayOf(activeBg, pressedOverlay)))
                    addState(intArrayOf(), activeBg)
                }
            } else {
                RippleDrawable(ColorStateList.valueOf(pressHighlightColor), activeBg, null)
            }
        } else {
            background = if (disableAnimation) {
                StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_pressed),
                        ShapeDrawable(OvalShape()).apply { paint.color = pressHighlightColor }
                    )
                }
            } else {
                borderlessRippleDrawable(pressHighlightColor, dp(20))
            }
        }
    }
}
