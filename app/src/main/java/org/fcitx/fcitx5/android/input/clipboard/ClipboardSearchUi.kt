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
import android.graphics.drawable.InsetDrawable
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
    private var selecting = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var handleGrabOffset = 0f
    private var onCursorPositioned: ((Int) -> Unit)? = null
    private var onSelectionStarted: ((Int) -> Unit)? = null
    private var onSelectionEdge: ((Int, Boolean) -> Unit)? = null

    private val touchSlopSquared =
        ViewConfiguration.get(ctx).scaledTouchSlop.let { it * it }

    private val selectWord = Runnable {
        selecting = true
        onSelectionStarted?.invoke(offsetForTouch(lastTouchX, lastTouchY))
    }

    private val cursorBlink = object : Runnable {
        override fun run() {
            cursorVisible = !cursorVisible
            updateCaretAndHandles()
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
        gravity = Gravity.TOP
        isSingleLine = true
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setHorizontallyScrolling(true)
        setPaddingDp(0, QUERY_TEXT_PADDING_TOP_DP, 0, 0)
        setTextColor(theme.keyTextColor)
    }

    /** Caret drawn as its own view so text offsets stay 1:1 with the input state. */
    private val caretView = View(ctx).apply {
        setBackgroundColor(theme.keyTextColor)
        visibility = View.INVISIBLE
    }

    private fun createHandleView() = View(ctx).apply {
        background = InsetDrawable(
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.accentKeyBackgroundColor)
                setStroke(ctx.dp(1), theme.accentKeyTextColor)
            },
            ctx.dp(HANDLE_INSET_DP)
        )
        visibility = View.INVISIBLE
    }

    private val startHandle = createHandleView()
    private val endHandle = createHandleView()

    private val queryContainer = frameLayout {
        add(queryText, FrameLayout.LayoutParams(matchParent, matchParent))
        add(
            caretView,
            FrameLayout.LayoutParams(dp(2), dp(CARET_HEIGHT_DP), Gravity.TOP).apply {
                topMargin = dp(CARET_TOP_MARGIN_DP)
            }
        )
        add(
            startHandle,
            FrameLayout.LayoutParams(dp(HANDLE_SIZE_DP), dp(HANDLE_SIZE_DP), Gravity.BOTTOM)
        )
        add(
            endHandle,
            FrameLayout.LayoutParams(dp(HANDLE_SIZE_DP), dp(HANDLE_SIZE_DP), Gravity.BOTTOM)
        )
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
        add(queryContainer, lParams(0, dp(QUERY_CONTAINER_HEIGHT_DP)) { weight = 1f })
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

    init {
        setupSelectionGestures()
        queryText.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateCaretAndHandles()
        }
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
    }

    /**
     * Wires the query text gestures: tap to place the caret, long press to select a word,
     * then keep dragging the same gesture to extend the selection.
     */
    private fun setupSelectionGestures() {
        queryText.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    queryText.requestFocus()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastTouchX = event.x
                    lastTouchY = event.y
                    selecting = false
                    view.postDelayed(selectWord, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    if (selecting) {
                        onSelectionEdge?.invoke(offsetForTouch(event.x, event.y), false)
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
        setupHandle(startHandle, isStart = true)
        setupHandle(endHandle, isStart = false)
    }

    private fun setupHandle(handle: View, isStart: Boolean) {
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Keep the grab point under the finger so the handle does not jump.
                    handleGrabOffset = event.rawX - (handle.x + ctx.dp(HANDLE_SIZE_DP) / 2f)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    // The handle is positioned from the (clamped) selection state, so it
                    // stops at the text edges instead of following the finger out of range.
                    onSelectionEdge?.invoke(offsetForX(event.rawX - handleGrabOffset), isStart)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true

                else -> false
            }
        }
    }

    /** Enables long press selection on the query text. */
    fun setOnSelectionListener(onStart: (Int) -> Unit, onEdge: (Int, Boolean) -> Unit) {
        onSelectionStarted = onStart
        onSelectionEdge = onEdge
    }

    private fun offsetForTouch(x: Float, y: Float): Int {
        val layout = queryText.layout ?: return renderedCursor
        val line = layout.getLineForVertical(
            (y - queryText.totalPaddingTop + queryText.scrollY).toInt()
        )
        return offsetForX(x, line)
    }

    private fun offsetForX(x: Float, line: Int = 0): Int {
        val layout = queryText.layout ?: return renderedCursor
        if (layout.lineCount == 0) return 0
        val tx = x - queryText.compoundPaddingLeft + queryText.scrollX
        val safeLine = line.coerceIn(0, layout.lineCount - 1)
        return layout.getOffsetForHorizontal(safeLine, tx)
            .coerceIn(0, layout.text.length)
    }

    /** x of [offset] in queryContainer coordinates. */
    private fun xForOffset(offset: Int): Float {
        val layout = queryText.layout ?: return 0f
        val o = offset.coerceIn(0, layout.text.length)
        return queryText.compoundPaddingLeft - queryText.scrollX + layout.getPrimaryHorizontal(o)
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
        updateCaretAndHandles()
    }

    /** Kept for callers that only need to reposition the caret and handles. */
    fun refreshCaret() = updateCaretAndHandles()

    private fun drawInput() {
        val state = lastState ?: return
        clearButton.visibility = if (state.text.isEmpty()) View.INVISIBLE else View.VISIBLE
        val text = state.text
        val selectionStart = state.selectionStart
        val selectionEnd = state.selectionEnd
        renderedCursor = state.displayCursor.coerceIn(0, text.length)
        val scrollX = queryText.scrollX
        queryText.text = buildSpannedString {
            if (state.hasSelection) {
                append(text, 0, selectionStart)
                val from = length
                append(text, selectionStart, selectionEnd)
                setSpan(selectionSpan, from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(text, selectionEnd, text.length)
            } else {
                append(text)
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
        updateCaretAndHandles()
    }

    private fun updateCaretAndHandles() {
        val state = lastState ?: return
        val layout = queryText.layout
        if (layout == null || layout.text.toString() != queryText.text.toString()) {
            // The layout still describes the previous text; it will trigger a layout pass.
            return
        }
        caretView.x = xForOffset(renderedCursor)
        caretView.visibility =
            if (!state.hasSelection && cursorVisible) View.VISIBLE else View.INVISIBLE

        val hasSelection = state.hasSelection
        startHandle.visibility = if (hasSelection) View.VISIBLE else View.INVISIBLE
        endHandle.visibility = if (hasSelection) View.VISIBLE else View.INVISIBLE
        if (!hasSelection) return
        val half = ctx.dp(HANDLE_SIZE_DP) / 2f
        // Clamp so a handle at the text edge stays fully visible and grabbable.
        val width = (if (queryContainer.width > 0) queryContainer.width else queryText.width)
        val maxX = (width - ctx.dp(HANDLE_SIZE_DP)).coerceAtLeast(0).toFloat()
        startHandle.x = (xForOffset(state.selectionStart) - half).coerceIn(0f, maxX)
        endHandle.x = (xForOffset(state.selectionEnd) - half).coerceIn(0f, maxX)
    }

    private companion object {
        const val CURSOR_BLINK_INTERVAL = 500L
        const val SELECTION_ALPHA = 0x55000000

        /** Text sits in the upper part; the lower part is reserved for the selection handles. */
        const val QUERY_CONTAINER_HEIGHT_DP = 58
        const val QUERY_TEXT_PADDING_TOP_DP = 12
        const val CARET_TOP_MARGIN_DP = 13
        const val HANDLE_SIZE_DP = 28
        const val HANDLE_INSET_DP = 8
        const val CARET_HEIGHT_DP = 18
    }
}
