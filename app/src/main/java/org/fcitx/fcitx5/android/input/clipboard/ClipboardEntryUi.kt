/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.graphics.Bitmap
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.unset
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import splitties.views.setPaddingDp

class ClipboardEntryUi(
    override val ctx: Context,
    private val theme: Theme,
    radius: Float,
    private val searchResultLayout: Boolean = false
) : Ui {

    val preview = imageView {
        visibility = View.GONE
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        minimumHeight = dp(72)
        background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(theme.keyBackgroundColor)
        }
    }

    private val imagePlaceholder = imageView {
        visibility = View.GONE
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        minimumHeight = dp(72)
        setPaddingDp(16, 8, 16, 8)
        background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(theme.keyBackgroundColor)
        }
        imageDrawable = drawable(R.drawable.ic_baseline_image_24)?.apply {
            setTint(theme.altKeyTextColor)
        }
    }

    val textView = textView {
        minLines = 1
        maxLines = if (searchResultLayout) SEARCH_MAX_LINES else 4
        textSize = 14f
        includeFontPadding = false
        setPaddingDp(8, 4, 8, 4)
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(theme.keyTextColor)
    }

    /**
     * Fades the bottom of a truncated search result into the card colour, rounded like the
     * card itself, so a clipped entry reads as "there is more text below".
     *
     * Kept fully laid out and switched via [View.setAlpha]: toggling visibility from a layout
     * callback would need another layout pass before it draws anything.
     */
    private val fadeOut = View(ctx).apply {
        alpha = 0f
        background = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(Color.TRANSPARENT, theme.clipboardEntryColor)
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
        }
    }

    val pin = imageView {
        imageDrawable = drawable(R.drawable.ic_baseline_push_pin_24)!!.apply {
            setTint(theme.altKeyTextColor)
            setAlpha(0.3f)
        }
    }

    val layout = constraintLayout {
        add(preview, lParams(matchParent, dp(72)) {
            topOfParent(dp(6))
        })
        add(imagePlaceholder, lParams(matchParent, dp(72)) {
            topOfParent(dp(6))
        })
        add(textView, lParams(matchParent, wrapContent) {
            centerVertically()
        })
        add(fadeOut, lParams(matchParent, dp(FADE_OUT_HEIGHT_DP)) {
            bottomOfParent()
        })
        add(pin, lParams(dp(12), dp(12)) {
            bottomOfParent(dp(2))
            endOfParent(dp(2))
        })
    }

    override val root = CustomGestureView(ctx).apply {
        isClickable = true
        minimumHeight = dp(if (searchResultLayout) 34 else 30)
        foreground = RippleDrawable(
            ColorStateList.valueOf(theme.keyPressHighlightColor), null,
            GradientDrawable().apply {
                cornerRadius = radius
                setColor(Color.WHITE)
            }
        )
        background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(theme.clipboardEntryColor)
        }
        add(layout, lParams(matchParent, matchParent))
    }

    init {
        // View holders outlive a single bind, so register the listener once per view.
        textView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateFadeOut() }
    }

    private var excerptTruncated = false

    private fun updateFadeOut() {
        val textLayout = textView.layout
        if (!searchResultLayout || textView.visibility != View.VISIBLE || textLayout == null) {
            fadeOut.alpha = 0f
            return
        }
        val lastLine = textLayout.lineCount - 1
        // Either the excerpt dropped the rest of the text, or the view ellipsized it, or the line
        // breaker silently dropped it (as it does for long runs without break opportunities).
        val truncated = excerptTruncated || (
            lastLine >= 0 && (
                textLayout.getEllipsisCount(lastLine) > 0 ||
                    textLayout.getLineEnd(lastLine) < textView.text.length
                )
            )
        fadeOut.alpha = if (truncated) 1f else 0f
    }

    fun setEntry(
        text: String,
        pinned: Boolean,
        previewBitmap: Bitmap? = null,
        compactMedia: Boolean = false,
        excerptTruncated: Boolean = false
    ) {
        textView.text = text
        pin.visibility = if (pinned) View.VISIBLE else View.GONE
        fadeOut.alpha = 0f
        this.excerptTruncated = excerptTruncated
        if (searchResultLayout) {
            if (compactMedia) {
                preview.visibility = if (previewBitmap != null) View.VISIBLE else View.GONE
                imagePlaceholder.visibility = if (previewBitmap == null) View.VISIBLE else View.GONE
                textView.visibility = View.GONE
                previewBitmap?.let(preview::setImageBitmap)
            } else {
                preview.visibility = View.GONE
                imagePlaceholder.visibility = View.GONE
                textView.visibility = View.VISIBLE
                textView.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    width = 0
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    marginStart = 0
                }
                textView.setPaddingDp(8, 4, 8, 4)
                textView.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                textView.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                textView.post { updateFadeOut() }
            }
            return
        }
        if (previewBitmap != null) {
            preview.setImageBitmap(previewBitmap)
            preview.visibility = View.VISIBLE
            imagePlaceholder.visibility = View.GONE
            if (text.isEmpty()) {
                // Image only, no text
                textView.visibility = View.GONE
                root.minimumHeight = ctx.dp(84)
            } else {
                // Image with text
                textView.visibility = View.VISIBLE
                textView.maxLines = 2
                textView.setPaddingDp(8, 82, 8, 6)
                root.minimumHeight = ctx.dp(122)
            }
        } else {
            preview.visibility = View.GONE
            if (text.isEmpty()) {
                // No image preview and no text - show image placeholder icon
                imagePlaceholder.visibility = View.VISIBLE
                textView.visibility = View.GONE
                root.minimumHeight = ctx.dp(84)
            } else {
                imagePlaceholder.visibility = View.GONE
                textView.visibility = View.VISIBLE
                textView.maxLines = 4
                textView.setPaddingDp(8, 4, 8, 4)
                root.minimumHeight = ctx.dp(30)
            }
        }
    }

    private companion object {
        /** Search results grow with their text but never past this many lines. */
        const val SEARCH_MAX_LINES = 5
        const val FADE_OUT_HEIGHT_DP = 18
    }
}
