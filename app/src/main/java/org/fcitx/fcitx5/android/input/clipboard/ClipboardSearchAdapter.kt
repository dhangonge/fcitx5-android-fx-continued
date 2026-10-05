/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 * Ported from the swan (天鹅) fork: https://github.com/boomker/fcitx5-android
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp

class ClipboardSearchAdapter(
    private val theme: Theme,
    private val entryRadius: Float,
    private val maskSensitive: Boolean,
    private val onEntryClick: (ClipboardEntry) -> Unit
) : ListAdapter<ClipboardEntry, ClipboardSearchAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(
        val ui: ClipboardEntryUi,
        val indicator: ImageView,
        itemView: View
    ) : RecyclerView.ViewHolder(itemView) {
        var thumbnailJob: Job? = null
        var boundThumbnailKey: String? = null
    }

    var selectionMode = false
        private set

    var onSelectionChanged: ((Int) -> Unit)? = null

    private val selectedIds = linkedSetOf<Int>()

    fun selectedEntryIds(): List<Int> = selectedIds.toList()

    fun setSelectionMode(enabled: Boolean) {
        if (selectionMode == enabled) return
        selectionMode = enabled
        if (!enabled) selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.size)
    }

    fun selectAll() {
        currentList.forEach { selectedIds.add(it.id) }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.size)
    }

    fun invertSelection() {
        currentList.forEach {
            if (!selectedIds.remove(it.id)) selectedIds.add(it.id)
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.size)
    }

    private fun toggleSelection(entry: ClipboardEntry) {
        if (!selectedIds.remove(entry.id)) selectedIds.add(entry.id)
        val index = currentList.indexOf(entry)
        if (index >= 0) notifyItemChanged(index)
        onSelectionChanged?.invoke(selectedIds.size)
    }

    /**
     * Keeps the locally held pinned state in sync after a batch pin/unpin so the
     * result list does not need a full re-query.
     */
    fun applyPinnedState(ids: Collection<Int>, pinned: Boolean) {
        if (ids.isEmpty()) return
        val idSet = ids.toHashSet()
        submitList(currentList.map { if (it.id in idSet) it.copy(pinned = pinned) else it })
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        val ui = ClipboardEntryUi(ctx, theme, entryRadius, searchResultLayout = true)
        val indicator = ImageView(ctx).apply {
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        val container = FrameLayout(ctx)
        container.addView(
            ui.root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        container.addView(
            indicator,
            FrameLayout.LayoutParams(ctx.dp(18), ctx.dp(18), Gravity.TOP or Gravity.END).apply {
                topMargin = ctx.dp(3)
                marginEnd = ctx.dp(3)
            }
        )
        container.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return ViewHolder(ui, indicator, container)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        val thumbnailKey = ClipboardAdapter.imagePreviewKey(entry)
        val isImage = thumbnailKey != null
        val excerpt = when {
            isImage -> null
            entry.isUriEntry() -> ClipboardAdapter.TextExcerpt(
                ClipboardAdapter.compactUriLabel(holder.ui.ctx, entry)
            )
            else -> ClipboardAdapter.excerpt(
                entry.text,
                entry.sensitive && maskSensitive,
                lines = SEARCH_MAX_LINES,
                chars = SEARCH_EXCERPT_CHARS
            )
        }
        val displayText = excerpt?.text.orEmpty()
        val excerptTruncated = excerpt?.truncated == true
        val cachedThumbnail = thumbnailKey?.let(ClipboardAdapter.thumbnailCache::get)
        holder.thumbnailJob?.cancel()
        holder.boundThumbnailKey = thumbnailKey
        holder.ui.setEntry(
            displayText,
            entry.pinned,
            previewBitmap = cachedThumbnail,
            compactMedia = isImage,
            excerptTruncated = excerptTruncated
        )
        if (thumbnailKey != null && cachedThumbnail == null) {
            holder.thumbnailJob = scope.launch {
                val bitmap = ClipboardAdapter.loadImagePreview(holder.ui.ctx, entry)
                if (bitmap != null) ClipboardAdapter.thumbnailCache.put(thumbnailKey, bitmap)
                if (holder.boundThumbnailKey == thumbnailKey) {
                    holder.ui.setEntry(
                        displayText,
                        entry.pinned,
                        bitmap,
                        compactMedia = true,
                        excerptTruncated = excerptTruncated
                    )
                }
            }
        }
        bindSelectionIndicator(holder, entry)
        // Listeners must sit on the entry root: it is clickable and consumes touches,
        // so a listener on the wrapping container would never fire.
        holder.ui.root.setOnClickListener {
            if (selectionMode) toggleSelection(entry) else onEntryClick(entry)
        }
        holder.ui.root.setOnLongClickListener {
            if (selectionMode) {
                toggleSelection(entry)
                true
            } else false
        }
    }

    private fun bindSelectionIndicator(holder: ViewHolder, entry: ClipboardEntry) {
        val indicator = holder.indicator
        indicator.visibility = if (selectionMode) View.VISIBLE else View.GONE
        if (!selectionMode) return
        if (entry.id in selectedIds) {
            indicator.background = null
            indicator.setImageResource(R.drawable.ic_baseline_check_circle_24)
            indicator.imageTintList = ColorStateList.valueOf(theme.accentKeyBackgroundColor)
        } else {
            indicator.setImageDrawable(null)
            indicator.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(holder.ui.ctx.dp(2), theme.altKeyTextColor)
            }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.thumbnailJob?.cancel()
        holder.thumbnailJob = null
        holder.boundThumbnailKey = null
        super.onViewRecycled(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        scope.cancel()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    private companion object {
        /** Must match [ClipboardEntryUi]'s search result line limit. */
        const val SEARCH_MAX_LINES = 5

        /** Enough to fill the lines above with narrow glyphs before the view starts folding. */
        const val SEARCH_EXCERPT_CHARS = 256

        val DiffCallback = object : DiffUtil.ItemCallback<ClipboardEntry>() {
            override fun areItemsTheSame(oldItem: ClipboardEntry, newItem: ClipboardEntry) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ClipboardEntry, newItem: ClipboardEntry) =
                oldItem == newItem
        }
    }
}
