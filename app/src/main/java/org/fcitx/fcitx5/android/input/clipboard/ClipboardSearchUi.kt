/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 * Ported from the swan (天鹅) fork: https://github.com/boomker/fcitx5-android
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.core.text.buildSpannedString
import androidx.core.view.isVisible
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardSearchCategory
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.preedit.PreeditUi
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.backgroundColor
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.gravityCenter
import splitties.views.imageDrawable
import splitties.views.setPaddingDp

class ClipboardSearchUi(override val ctx: Context, private val theme: Theme) : Ui {

    private var renderedCursor = 0
    private var lastState: ClipboardSearchInputState? = null
    private var cursorVisible = true
    private var caretRendered = true
    private var selecting = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var onCursorPositioned: ((Int) -> Unit)? = null
    private var onSelectionStarted: ((Int) -> Unit)? = null
    private var onSelectionExtended: ((Int) -> Unit)? = null

    private val touchSlopSquared =
        ViewConfiguration.get(ctx).scaledTouchSlop.let { it * it }

    private val selectWord = Runnable {
        selecting = true
        onSelectionStarted?.invoke(offsetForTouch(lastTouchX, lastTouchY))
    }

    private val cursorBlink = object : Runnable {
        override fun run() {
            cursorVisible = !cursorVisible
            drawInput()
            queryText.postDelayed(this, CURSOR_BLINK_INTERVAL)
        }
    }

    val backButton = ToolButton(ctx, R.drawable.ic_baseline_arrow_back_24, theme).apply {
        contentDescription = ctx.getString(R.string.back_to_keyboard)
    }

    val clearButton = ToolButton(ctx, R.drawable.ic_baseline_close_24, theme).apply {
        contentDescription = ctx.getString(R.string.clear)
        visibility = View.INVISIBLE
    }

    val pinButton = ToolButton(ctx, R.drawable.ic_outline_push_pin_24, theme)

    val selectButton = ToolButton(ctx, R.drawable.ic_baseline_list_alt_24, theme).apply {
        contentDescription = ctx.getString(R.string.clipboard_search_select)
    }

    val dragHandle = textView {
        text = ctx.getString(R.string.clipboard_search_title)
        typeface = Typeface.defaultFromStyle(Typeface.BOLD)
        textSize = 16f
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(theme.altKeyTextColor)
    }

    private val categoryButtons = linkedMapOf(
        ClipboardSearchCategory.All to createCategoryButton(R.string.clipboard_category_all),
        ClipboardSearchCategory.Favorites to createCategoryButton(R.string.clipboard_category_favorites),
        ClipboardSearchCategory.Local to createCategoryButton(R.string.clipboard_category_local),
        ClipboardSearchCategory.Remote to createCategoryButton(R.string.clipboard_category_remote),
        ClipboardSearchCategory.Media to createCategoryButton(R.string.clipboard_category_media)
    )

    private val categoryBar = horizontalLayout {
        setPaddingDp(8, 3, 8, 5)
        categoryButtons.forEach { (_, button) ->
            add(button, LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
    }

    val recyclerView = recyclerView {
        addItemDecoration(SpacesItemDecoration(dp(4)))
        clipToPadding = false
        setPaddingDp(4, 0, 4, 4)
        visibility = View.GONE
    }

    private val messageView = textView {
        gravity = gravityCenter
        textSize = 14f
        isClickable = false
        setPaddingDp(16, 8, 16, 8)
        setTextColor(theme.altKeyTextColor)
    }

    private val queryText = textView {
        textSize = 15f
        gravity = Gravity.CENTER_VERTICAL
        isSingleLine = true
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setHorizontallyScrolling(true)
        setTextColor(theme.keyTextColor)
    }

    private val cursorSpan by lazy {
        PreeditUi.CursorSpan(ctx, theme.keyTextColor, queryText.paint.fontMetricsInt)
    }

    private val blankCursorSpan by lazy {
        PreeditUi.CursorSpan(ctx, Color.TRANSPARENT, queryText.paint.fontMetricsInt)
    }

    private val selectionSpan by lazy {
        BackgroundColorSpan(
            (theme.accentKeyBackgroundColor and 0x00FFFFFF) or SELECTION_ALPHA
        )
    }

    private val searchIcon = imageView {
        imageDrawable = drawable(R.drawable.ic_baseline_search_24)?.apply {
            setTint(theme.altKeyTextColor)
        }
        contentDescription = ctx.getString(R.string.search)
        setPaddingDp(10, 10, 10, 10)
    }

    private val inputBar = horizontalLayout {
        gravity = Gravity.CENTER_VERTICAL
        setPaddingDp(4, 0, 4, 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(theme.keyBackgroundColor)
        }
        add(searchIcon, lParams(dp(40), dp(40)))
        add(queryText, lParams(0, dp(44)) { weight = 1f })
        add(clearButton, lParams(dp(40), dp(40)))
    }

    val batchCountText = textView {
        textSize = 13f
        gravity = Gravity.CENTER_VERTICAL
        setPaddingDp(4, 0, 4, 0)
        setTextColor(theme.altKeyTextColor)
    }

    val selectAllButton = createSelectionActionButton(R.string.clipboard_search_select_all)

    val invertSelectionButton =
        createSelectionActionButton(R.string.clipboard_search_invert_selection)

    val batchPinButton = createSelectionActionButton(R.string.clipboard_search_batch_pin)

    val batchUnpinButton = createSelectionActionButton(R.string.clipboard_search_batch_unpin)

    val batchDeleteButton = createSelectionActionButton(R.string.clipboard_search_batch_delete)

    private val selectionBar = view(::HorizontalScrollView) {
        isHorizontalScrollBarEnabled = false
        visibility = View.GONE
        add(horizontalLayout {
            gravity = Gravity.CENTER_VERTICAL
            setPaddingDp(4, 0, 4, 0)
            add(batchCountText, LinearLayout.LayoutParams(dp(72), dp(36)))
            listOf(
                selectAllButton,
                invertSelectionButton,
                batchPinButton,
                batchUnpinButton,
                batchDeleteButton
            ).forEach { button ->
                add(
                    button,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(32)
                    ).apply { marginEnd = dp(6) }
                )
            }
        }, lParams(wrapContent, wrapContent))
    }

    val panel = verticalLayout {
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(theme.barColor)
        }
        clipToOutline = true
        elevation = dp(8).toFloat()
        add(horizontalLayout {
            gravity = Gravity.CENTER_VERTICAL
            add(backButton, lParams(dp(40), dp(40)))
            add(dragHandle, lParams(0, dp(40)) { weight = 1f })
            add(selectButton, lParams(dp(40), dp(40)))
            add(pinButton, lParams(dp(40), dp(40)))
        }, lParams(matchParent, dp(40)))
        add(categoryBar, lParams(matchParent, dp(40)))
        add(frameLayout {
            add(recyclerView, FrameLayout.LayoutParams(matchParent, matchParent))
            add(messageView, FrameLayout.LayoutParams(matchParent, matchParent))
        }, lParams(matchParent, 0) { weight = 1f })
        add(inputBar, lParams(matchParent, wrapContent) {
            setMargins(dp(8), dp(4), dp(8), dp(8))
        })
        add(selectionBar, lParams(matchParent, wrapContent) {
            setMargins(dp(8), dp(4), dp(8), dp(8))
        })
    }

    override val root = frameLayout {
        add(panel, FrameLayout.LayoutParams(matchParent, matchParent).apply {
            setMargins(dp(6), dp(4), dp(6), dp(30))
        })
    }

    private fun createSelectionActionButton(textRes: Int) = textView {
        gravity = gravityCenter
        text = ctx.getString(textRes)
        textSize = 13f
        setPaddingDp(12, 0, 12, 0)
        background = categoryBackground(selected = false)
        setTextColor(theme.keyTextColor)
    }

    fun setSelectionMode(enabled: Boolean) {
        selectButton.setActive(enabled)
        selectButton.contentDescription = ctx.getString(
            if (enabled) R.string.clipboard_search_select_exit else R.string.clipboard_search_select
        )
        selectionBar.visibility = if (enabled) View.VISIBLE else View.GONE
        inputBar.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    fun setSelectionCount(count: Int) {
        batchCountText.text = ctx.getString(R.string.clipboard_search_selected_count, count)
        val enabled = count > 0
        listOf(batchPinButton, batchUnpinButton, batchDeleteButton).forEach { button ->
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.4f
        }
    }

    private fun createCategoryButton(textRes: Int) = textView {
        gravity = gravityCenter
        text = ctx.getString(textRes)
        textSize = 13f
        setPaddingDp(4, 0, 4, 0)
        background = categoryBackground(selected = false)
        setTextColor(theme.keyTextColor)
    }

    private fun categoryBackground(selected: Boolean) = RippleDrawable(
        ColorStateList.valueOf(theme.keyPressHighlightColor),
        GradientDrawable().apply {
            cornerRadius = ctx.dp(16).toFloat()
            setColor(if (selected) theme.accentKeyBackgroundColor else theme.keyBackgroundColor)
        },
        GradientDrawable().apply {
            cornerRadius = ctx.dp(16).toFloat()
            setColor(Color.WHITE)
        }
    )

    fun setOnCategorySelectedListener(listener: (ClipboardSearchCategory) -> Unit) {
        categoryButtons.forEach { (category, button) ->
            button.setOnClickListener {
                setSelectedCategory(category)
                listener(category)
            }
        }
    }

    fun setSelectedCategory(category: ClipboardSearchCategory) {
        categoryButtons.forEach { (buttonCategory, button) ->
            val selected = buttonCategory == category
            button.background = categoryBackground(selected)
            button.setTextColor(if (selected) theme.accentKeyTextColor else theme.keyTextColor)
        }
    }

    fun setPinned(pinned: Boolean) {
        pinButton.setIcon(
            if (pinned) R.drawable.ic_baseline_push_pin_24 else R.drawable.ic_outline_push_pin_24
        )
        pinButton.setActive(pinned)
        pinButton.contentDescription = ctx.getString(
            if (pinned) R.string.clipboard_search_unpin else R.string.clipboard_search_pin
        )
    }

    fun setOnCursorPositionedListener(listener: (Int) -> Unit) {
        onCursorPositioned = listener
        queryText.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    queryText.requestFocus()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    selecting = false
                    lastTouchX = event.x
                    lastTouchY = event.y
                    view.postDelayed(selectWord, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    if (selecting) {
                        onSelectionExtended?.invoke(offsetForTouch(event.x, event.y))
                    } else {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (dx * dx + dy * dy > touchSlopSquared) view.removeCallbacks(selectWord)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(selectWord)
                    if (!selecting) {
                        onCursorPositioned?.invoke(offsetForTouch(event.x, event.y))
                    }
                    selecting = false
                    view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(selectWord)
                    selecting = false
                    true
                }
                else -> false
            }
        }
    }

    /** Enables long press selection on the query text. */
    fun setOnSelectionListener(onStart: (Int) -> Unit, onExtend: (Int) -> Unit) {
        onSelectionStarted = onStart
        onSelectionExtended = onExtend
    }

    private fun offsetForTouch(x: Float, y: Float): Int {
        val layout = queryText.layout ?: return renderedCursor
        val line = layout.getLineForVertical(
            (y - queryText.totalPaddingTop + queryText.scrollY).toInt()
        )
        val visualOffset = layout.getOffsetForHorizontal(
            line,
            x - queryText.totalPaddingLeft + queryText.scrollX
        )
        // The caret is a real character in the rendered string, so offsets past it shift by one.
        return if (caretRendered && visualOffset > renderedCursor) visualOffset - 1 else visualOffset
    }

    fun showMessage(text: String) {
        messageView.text = text
        messageView.gravity = Gravity.CENTER
        messageView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        messageView.isVisible = true
        recyclerView.isVisible = false
        recyclerView.setPadding(ctx.dp(4), 0, ctx.dp(4), ctx.dp(4))
    }

    fun showResults(status: String) {
        messageView.text = status
        messageView.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        messageView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ctx.dp(40),
            Gravity.TOP
        )
        messageView.isVisible = true
        recyclerView.isVisible = true
        recyclerView.setPadding(ctx.dp(4), ctx.dp(40), ctx.dp(4), ctx.dp(4))
    }

    fun renderInput(state: ClipboardSearchInputState) {
        lastState = state
        cursorVisible = true
        drawInput()
        queryText.requestFocus()
        queryText.removeCallbacks(cursorBlink)
        queryText.postDelayed(cursorBlink, CURSOR_BLINK_INTERVAL)
    }

    fun stopCursorBlink() {
        queryText.removeCallbacks(cursorBlink)
        cursorVisible = true
    }

    private fun drawInput() {
        val state = lastState ?: return
        clearButton.visibility = if (state.text.isEmpty()) View.INVISIBLE else View.VISIBLE
        val text = state.text
        caretRendered = !state.hasSelection
        val selectionStart = state.selectionStart
        val selectionEnd = state.selectionEnd
        val cursor = state.displayCursor.coerceIn(0, text.length)
        renderedCursor = cursor
        val scrollX = queryText.scrollX
        queryText.text = buildSpannedString {
            if (!caretRendered) {
                append(text, 0, selectionStart)
                val from = length
                append(text, selectionStart, selectionEnd)
                setSpan(selectionSpan, from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(text, selectionEnd, text.length)
            } else {
                append(text, 0, cursor)
                append('|')
                setSpan(
                    if (cursorVisible) cursorSpan else blankCursorSpan,
                    cursor,
                    cursor + 1,
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE
                )
                append(text, cursor, text.length)
            }
            if (text.isEmpty()) {
                val hintStart = length
                append(ctx.getString(R.string.clipboard_search_hint))
                setSpan(
                    ForegroundColorSpan(theme.altKeyTextColor),
                    hintStart,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        queryText.scrollX = scrollX
    }

    private companion object {
        const val CURSOR_BLINK_INTERVAL = 500L
        const val SELECTION_ALPHA = 0x55000000
    }
}
