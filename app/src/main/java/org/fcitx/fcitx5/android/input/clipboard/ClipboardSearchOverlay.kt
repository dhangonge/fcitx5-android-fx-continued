/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 * Ported from the swan (天鹅) fork: https://github.com/boomker/fcitx5-android
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.clipboardsync.MainService
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.ClipboardSearchCategory
import org.fcitx.fcitx5.android.data.clipboard.ClipboardSearchDismissReason
import org.fcitx.fcitx5.android.data.clipboard.shouldDismissClipboardSearch
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.theme.Theme

class ClipboardSearchOverlay(
    context: Context,
    theme: Theme,
    entryRadius: Float,
    maskSensitive: Boolean,
    private val scope: CoroutineScope,
    private val onClose: () -> Unit,
    private val onCursorPositioned: () -> Unit,
    private val onEntryClick: (ClipboardEntry, Boolean) -> Unit
) {
    private val ui = ClipboardSearchUi(context, theme)
    val root get() = ui.root
    private val inputState = ClipboardSearchInputState()

    private val adapter = ClipboardSearchAdapter(theme, entryRadius, maskSensitive) { entry ->
        onEntryClick(entry, isPinned)
        requestDismiss(ClipboardSearchDismissReason.ResultClick)
    }
    private var searchJob: Job? = null
    private var selectedCategory = ClipboardSearchCategory.Local
    private var categoryExplicitlySelected = false
    private var selectionJob: Job? = null
    var isPinned = false
        private set

    val selectionMode: Boolean
        get() = adapter.selectionMode

    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var dragActivated = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var initialTranslationX = 0f
    private var initialTranslationY = 0f
    private val activateDrag = Runnable { dragActivated = true }

    init {
        ui.recyclerView.layoutManager =
            StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        ui.recyclerView.itemAnimator = null
        ui.recyclerView.adapter = adapter
        ui.backButton.setOnClickListener {
            requestDismiss(ClipboardSearchDismissReason.Explicit)
        }
        ui.pinButton.setOnClickListener {
            isPinned = !isPinned
            ui.setPinned(isPinned)
        }
        ui.clearButton.setOnClickListener {
            inputState.clear()
            onInputChanged()
        }
        ui.selectButton.setOnClickListener {
            setSelectionMode(!adapter.selectionMode)
        }
        ui.selectAllButton.setOnClickListener { adapter.selectAll() }
        ui.invertSelectionButton.setOnClickListener { adapter.invertSelection() }
        ui.batchDeleteButton.setOnClickListener { batchDelete() }
        ui.batchPinButton.setOnClickListener { batchSetPinned(true) }
        ui.batchUnpinButton.setOnClickListener { batchSetPinned(false) }
        adapter.onSelectionChanged = { count -> ui.setSelectionCount(count) }
        ui.setOnCategorySelectedListener { category ->
            selectedCategory = category
            categoryExplicitlySelected = true
            onInputChanged()
        }
        ui.setOnCursorPositionedListener(::setCursor)
        ui.setOnSelectionListener(::selectWordAt, ::extendSelectionTo)
        setupDragging()
        ui.setSelectedCategory(selectedCategory)
        ui.setPinned(isPinned)
        showInitialState()
    }

    fun open() {
        inputState.clear()
        selectedCategory = ClipboardSearchCategory.Local
        categoryExplicitlySelected = false
        isPinned = false
        ui.panel.translationX = 0f
        ui.panel.translationY = 0f
        adapter.submitList(emptyList())
        setSelectionMode(false)
        ui.setSelectedCategory(selectedCategory)
        ui.setPinned(isPinned)
        ui.renderInput(inputState)
        showInitialState()
    }

    fun close() {
        ui.dragHandle.removeCallbacks(activateDrag)
        dragActivated = false
        ui.stopCursorBlink()
        searchJob?.cancel()
        searchJob = null
        selectionJob?.cancel()
        selectionJob = null
        setSelectionMode(false)
        adapter.submitList(emptyList())
        inputState.clear()
    }

    fun setSelectionMode(enabled: Boolean) {
        if (adapter.selectionMode == enabled) return
        adapter.setSelectionMode(enabled)
        ui.setSelectionMode(enabled)
        ui.setSelectionCount(0)
    }

    fun requestDismiss(reason: ClipboardSearchDismissReason): Boolean {
        if (!shouldDismissClipboardSearch(isPinned, reason)) return false
        onClose()
        return true
    }

    fun commit(text: String, cursor: Int = -1) {
        inputState.commit(text, cursor)
        onInputChanged()
    }

    fun setPreedit(text: FormattedText) {
        inputState.setPreedit(text)
        onInputChanged()
    }

    fun backspace() {
        inputState.backspace()
        onInputChanged()
    }

    fun delete() {
        inputState.delete()
        onInputChanged()
    }

    fun moveCursor(delta: Int) {
        inputState.moveCursor(delta)
        ui.renderInput(inputState)
    }

    private fun setCursor(offset: Int) {
        val inputChanged = inputState.setCursor(offset)
        onCursorPositioned()
        if (inputChanged) onInputChanged() else ui.renderInput(inputState)
    }

    private fun selectWordAt(offset: Int) {
        onCursorPositioned()
        if (inputState.selectWordAt(offset)) ui.renderInput(inputState)
    }

    private fun extendSelectionTo(offset: Int) {
        if (inputState.extendSelectionTo(offset)) ui.renderInput(inputState)
    }

    fun deleteSurrounding(before: Int, after: Int) {
        inputState.deleteSurrounding(before, after)
        onInputChanged()
    }

    private fun onInputChanged() {
        ui.renderInput(inputState)
        searchJob?.cancel()
        val query = inputState.text.trim()
        if (query.isEmpty() && !categoryExplicitlySelected) {
            adapter.submitList(emptyList())
            showInitialState()
            return
        }
        val category = selectedCategory
        ui.showMessage(ui.ctx.getString(R.string.clipboard_search_searching))
        searchJob = scope.launch(Dispatchers.Main.immediate) {
            delay(120)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ClipboardManager.search(
                        query = query,
                        category = category,
                        fallbackFromLocalToAll = !categoryExplicitlySelected
                    )
                }
            }.getOrElse {
                if (inputState.text.trim() == query && selectedCategory == category) {
                    adapter.submitList(emptyList())
                    ui.showMessage(ui.ctx.getString(R.string.clipboard_search_no_results))
                }
                return@launch
            }
            if (inputState.text.trim() != query || selectedCategory != category) return@launch
            adapter.submitList(result.entries)
            if (result.entries.isEmpty()) {
                ui.showMessage(ui.ctx.getString(R.string.clipboard_search_no_results))
            } else {
                val status = if (result.usedAutomaticFallback) {
                    R.string.clipboard_search_auto_all_results
                } else when (result.category) {
                    ClipboardSearchCategory.All -> R.string.clipboard_search_all_results
                    ClipboardSearchCategory.Favorites -> R.string.clipboard_search_favorite_results
                    ClipboardSearchCategory.Local -> R.string.clipboard_search_local_results
                    ClipboardSearchCategory.Remote -> R.string.clipboard_search_remote_results
                    ClipboardSearchCategory.Media -> R.string.clipboard_search_media_results
                }
                ui.showResults(ui.ctx.getString(status, result.entries.size))
            }
        }
    }

    private fun showInitialState() {
        ui.showMessage(ui.ctx.getString(R.string.clipboard_search_initial))
    }

    private fun batchDelete() {
        val ids = adapter.selectedEntryIds()
        if (ids.isEmpty()) return
        val context = ui.ctx
        selectionJob?.cancel()
        selectionJob = scope.launch {
            val (targets, suppressed) = withContext(Dispatchers.IO) {
                val targets = ids.mapNotNull { id ->
                    ClipboardManager.mediaDeletionTarget(id)?.let { id to it }
                }
                val suppressed = ids.mapNotNull { ClipboardManager.remoteSuppressionContent(it) }
                targets to suppressed
            }
            withContext(Dispatchers.IO) {
                ClipboardManager.deleteAll(ids)
                if (targets.isNotEmpty()) {
                    ClipboardManager.deleteClipboardSourceFiles(targets.map { it.second })
                }
                if (suppressed.isNotEmpty()) {
                    MainService.suppressRemoteClipboardContents(context, suppressed)
                }
            }
            Toast.makeText(
                context,
                context.getString(R.string.clipboard_search_batch_deleted, ids.size),
                Toast.LENGTH_SHORT
            ).also { it.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 0) }.show()
            setSelectionMode(false)
            onInputChanged()
        }
    }

    private fun batchSetPinned(pinned: Boolean) {
        val ids = adapter.selectedEntryIds()
        if (ids.isEmpty()) return
        val context = ui.ctx
        selectionJob?.cancel()
        selectionJob = scope.launch {
            withContext(Dispatchers.IO) {
                if (pinned) ClipboardManager.pinAll(ids) else ClipboardManager.unpinAll(ids)
            }
            adapter.applyPinnedState(ids, pinned)
            Toast.makeText(
                context,
                context.getString(
                    if (pinned) R.string.clipboard_search_batch_pinned
                    else R.string.clipboard_search_batch_unpinned,
                    ids.size
                ),
                Toast.LENGTH_SHORT
            ).also { it.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 0) }.show()
            setSelectionMode(false)
        }
    }

    private fun setupDragging() {
        ui.dragHandle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    initialTranslationX = ui.panel.translationX
                    initialTranslationY = ui.panel.translationY
                    dragActivated = false
                    view.postDelayed(activateDrag, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragActivated && dx * dx + dy * dy > touchSlop * touchSlop) {
                        view.removeCallbacks(activateDrag)
                    }
                    if (dragActivated) {
                        val minX = -ui.panel.left.toFloat()
                        val maxX = (root.width - ui.panel.right).toFloat()
                        val minY = -ui.panel.top.toFloat()
                        val maxY = (root.height - ui.panel.bottom).toFloat()
                        ui.panel.translationX = (initialTranslationX + dx).coerceIn(minX, maxX)
                        ui.panel.translationY = (initialTranslationY + dy).coerceIn(minY, maxY)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(activateDrag)
                    val handled = dragActivated
                    dragActivated = false
                    if (!handled && event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                    true
                }
                else -> false
            }
        }
    }
}
