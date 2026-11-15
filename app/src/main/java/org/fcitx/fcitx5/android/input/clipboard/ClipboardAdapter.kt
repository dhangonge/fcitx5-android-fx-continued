/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.content.Context
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import android.util.LruCache
import android.util.Patterns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.net.toUri
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
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
import org.fcitx.fcitx5.android.utils.loadThumbnailBitmap
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import org.fcitx.fcitx5.android.utils.resolveClipboardUriFileName
import splitties.dimensions.dp
import splitties.resources.styledColor
import timber.log.Timber
import kotlin.math.min

abstract class ClipboardAdapter(
    private val theme: Theme,
    private val entryRadius: Float,
    private val maskSensitive: Boolean
) : PagingDataAdapter<ClipboardEntry, ClipboardAdapter.ViewHolder>(diffCallback) {

    /**
     * Text to show in a clipboard entry, plus whether it hides the rest of the original text.
     */
    class TextExcerpt(val text: String, val truncated: Boolean = false)

    companion object {
        internal val thumbnailCache = object : LruCache<String, Bitmap>(24) {}
        private val cnMainlandMobilePattern = Regex("^1[3-9]\\d{9}$")

        private val diffCallback = object : DiffUtil.ItemCallback<ClipboardEntry>() {
            override fun areItemsTheSame(
                oldItem: ClipboardEntry,
                newItem: ClipboardEntry
            ): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(
                oldItem: ClipboardEntry,
                newItem: ClipboardEntry
            ): Boolean {
                return oldItem == newItem
            }
        }

        /**
         * excerpt text to show on ClipboardEntryUi, to reduce render time of very long text
         * @param str text to excerpt
         * @param mask mask text content with "•"
         * @param lines max output lines
         * @param chars max chars per output line
         */
        fun excerptText(
            str: String,
            mask: Boolean = false,
            lines: Int = 4,
            chars: Int = 128
        ): String = excerpt(str, mask, lines, chars).text

        /**
         * Same as [excerptText], plus whether characters had to be dropped. Callers can use it to
         * tell the entry view that the excerpt hides the rest of the text (note that text without
         * line breaks is cut to [chars] characters, so the view may not ellipsize on its own).
         */
        fun excerpt(
            str: String,
            mask: Boolean = false,
            lines: Int = 4,
            chars: Int = 128
        ): TextExcerpt {
            val length = str.length
            var lineBreak = -1
            var consumed = 0
            val builder = StringBuilder()
            for (i in 1..lines) {
                val start = lineBreak + 1   // skip previous '\n'
                val excerptEnd = min(start + chars, length)
                lineBreak = str.indexOf('\n', start)
                if (lineBreak < 0) {
                    // no line breaks remaining, substring to end of text
                    if (mask) {
                        builder.append(ClipboardEntry.BULLET.repeat(excerptEnd - start))
                    } else {
                        builder.append(str, start, excerptEnd)
                    }
                    consumed = excerptEnd
                    break
                } else {
                    val end = min(excerptEnd, lineBreak)
                    // append one line exactly
                    if (mask) {
                        builder.append(ClipboardEntry.BULLET.repeat(end - start))
                    } else {
                        builder.append(str, start, end)
                    }
                    builder.append('\n')
                    consumed = end + 1
                }
            }
            return TextExcerpt(builder.toString(), consumed < length)
        }

        internal fun compactUriLabel(context: Context, entry: ClipboardEntry): String {
            if (entry.type.startsWith("image/")) return ""
            val uri = runCatching { Uri.parse(entry.text) }.getOrNull()
            val fileName = uri?.let { resolveClipboardUriFileName(context, it) }
            return if (fileName.isNullOrBlank()) {
                context.getString(R.string.clipboard_entry_file)
            } else {
                context.getString(R.string.clipboard_entry_file_named, fileName)
            }
        }

        internal fun imagePreviewKey(entry: ClipboardEntry): String? =
            if (entry.isUriEntry() && entry.type.startsWith("image/")) entry.text else null

        internal suspend fun loadImagePreview(context: Context, entry: ClipboardEntry): Bitmap? =
            entry.loadThumbnailBitmap(context)
    }

    private var popupMenu: PopupWindow? = null

    class ViewHolder(val entryUi: ClipboardEntryUi) : RecyclerView.ViewHolder(entryUi.root) {
        var thumbnailJob: Job? = null
        var boundThumbnailKey: String? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ClipboardEntryUi(parent.context, theme, entryRadius))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position) ?: return
        with(holder.entryUi) {
            val displayText = if (entry.isUriEntry()) {
                compactUriLabel(ctx, entry)
            } else {
                excerptText(entry.text, entry.sensitive && maskSensitive)
            }
            val linkUri = entry.openableLinkUri()
            val searchQuery = entry.searchableQuery()
            val dialNumber = entry.dialableCnMobileNumber()
            val splittableText = entry.splittableText()
            val thumbnailKey = imagePreviewKey(entry)
            val cachedThumbnail = thumbnailKey?.let { thumbnailCache.get(it) }
            holder.thumbnailJob?.cancel()
            holder.boundThumbnailKey = thumbnailKey
            setEntry(displayText, entry.pinned, cachedThumbnail)
            if (thumbnailKey != null && cachedThumbnail == null) {
                holder.thumbnailJob = scope.launch {
                    val bitmap = loadImagePreview(ctx, entry)
                    if (bitmap != null) {
                        thumbnailCache.put(thumbnailKey, bitmap)
                    }
                    if (holder.boundThumbnailKey == thumbnailKey) {
                        holder.entryUi.setEntry(displayText, entry.pinned, bitmap)
                    }
                }
            }
            root.setOnClickListener {
                if (entry.isUriEntry()) {
                    // For media entries, click shows menu
                    showEntryMenu(
                        anchor = root,
                        entry = entry,
                        linkUri = linkUri,
                        searchQuery = searchQuery,
                        dialNumber = dialNumber,
                        splittableText = splittableText
                    )
                } else {
                    // For text entries, click pastes
                    onPaste(entry)
                }
            }
            root.setOnLongClickListener {
                // For all entries, long click shows menu. The touch that gets here is
                // delivered from a coroutine inside CustomGestureView, so anything thrown
                // on the way (a popup the window manager refuses, a dead anchor window in
                // some host apps) takes the whole input method down instead of just
                // closing the menu. Swallow it and keep the keyboard alive.
                runCatching {
                    showEntryMenu(
                        anchor = root,
                        entry = entry,
                        linkUri = linkUri,
                        searchQuery = searchQuery,
                        dialNumber = dialNumber,
                        splittableText = splittableText
                    )
                }.onFailure {
                    Timber.w(it, "failed to show the menu for a clipboard entry")
                    popupMenu?.dismiss()
                    popupMenu = null
                }
                true
            }
        }
    }

    private fun showEntryMenu(
        anchor: View,
        entry: ClipboardEntry,
        linkUri: Uri?,
        searchQuery: String?,
        dialNumber: String?,
        splittableText: String?
    ) {
        val ctx = anchor.context
        val iconTint = ctx.styledColor(android.R.attr.colorControlNormal)
        val isUriEntry = entry.isUriEntry()
        val imageUri = entry.viewableImageUri()

        val rows = buildList {
            if (!isUriEntry) {
                add(EntryMenuRow(android.R.string.paste, R.drawable.ic_baseline_content_paste_24) { onPaste(entry) })
            }
            if (entry.pinned) {
                add(EntryMenuRow(R.string.remove_from_favorites, R.drawable.ic_outline_push_pin_24) { onUnpin(entry.id) })
            } else {
                add(EntryMenuRow(R.string.add_to_favorites, R.drawable.ic_baseline_push_pin_24) { onPin(entry.id) })
            }
            if (!isUriEntry) {
                add(EntryMenuRow(R.string.edit, R.drawable.ic_baseline_edit_24) { onEdit(entry.id) })
            } else if (imageUri == null) {
                add(EntryMenuRow(R.string.open_link, R.drawable.ic_baseline_language_24) { onOpenFile(entry) })
            }
            add(EntryMenuRow(R.string.share, R.drawable.ic_baseline_share_24) { onShare(entry) })
            if (shouldShowUpload(entry)) {
                add(EntryMenuRow(R.string.upload, R.drawable.ic_baseline_send_24) { onUpload(entry) })
            }
            if (splittableText != null) {
                add(EntryMenuRow(R.string.split_words, R.drawable.ic_baseline_spellcheck_24) { onSplitText(splittableText) })
            }
            imageUri?.let { uri ->
                add(EntryMenuRow(R.string.view_image, R.drawable.ic_baseline_image_24) { onViewImage(uri) })
            }
            if (linkUri != null) {
                add(EntryMenuRow(R.string.open_link, R.drawable.ic_baseline_language_24) { onOpenLink(linkUri) })
            }
            if (searchQuery != null) {
                add(EntryMenuRow(R.string.search, R.drawable.ic_baseline_search_24) { onSearch(searchQuery) })
            }
            if (dialNumber != null) {
                add(EntryMenuRow(R.string.dial, R.drawable.ic_baseline_call_24) { onDial(dialNumber) })
            }
            add(EntryMenuRow(R.string.delete, R.drawable.ic_baseline_delete_24) { onDelete(entry.id) })
        }

        // Our own window instead of PopupMenu's: the platform menu makes its window
        // focusable, which drags focus away from the host editor. When the menu closes,
        // some hosts (Flutter webviews among them) treat the momentary focus loss as
        // "editor gone" and hide the keyboard. NOT_FOCUSABLE plus INPUT_METHOD_NEEDED
        // is the platform combination for input-method sub-windows: the popup takes
        // touches and keys without ever becoming the focused window.
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            rows.forEach { row ->
                addView(LinearLayout(ctx).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = pressHighlightDrawable(theme.keyPressHighlightColor)
                    setPadding(dp(8), 0, dp(16), 0)
                    isClickable = true
                    isFocusable = false
                    setOnClickListener {
                        popupMenu?.dismiss()
                        runCatching(row.action).onFailure {
                            Timber.w(it, "menu action failed for a clipboard entry")
                        }
                    }
                    addView(ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER
                        setColorFilter(iconTint)
                        setImageResource(row.icon)
                    }, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.MATCH_PARENT))
                    addView(TextView(ctx).apply {
                        text = ctx.getString(row.title)
                        textSize = 16f
                        setTextColor(ctx.styledColor(android.R.attr.textColorPrimary))
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        isSingleLine = true
                        ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    ))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)))
            }
        }
        // One shared width for all rows, sized by the widest label like ListPopupWindow.
        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        // The vertical padding around the rows has to fit inside the window too.
        val menuWidth = min(content.measuredWidth, dp(320))
        val menuHeight = dp(48) * rows.size + dp(8)
        for (i in 0 until content.childCount) {
            content.getChildAt(i).layoutParams = LinearLayout.LayoutParams(menuWidth, dp(48))
        }
        content.layoutParams = LinearLayout.LayoutParams(menuWidth, menuHeight)

        popupMenu?.dismiss()
        val popup = PopupWindow(content, menuWidth, menuHeight).apply {
            isFocusable = false
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            // Outside-touch dismissal is only armed while a background is set.
            val attrs = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.popupBackground))
            val background = attrs.getDrawable(0)
            attrs.recycle()
            setBackgroundDrawable(background ?: ColorDrawable(theme.popupBackgroundColor))
            elevation = dp(4).toFloat()
            setOnDismissListener {
                if (it === popupMenu) popupMenu = null
            }
        }
        popupMenu = popup

        val screenWidth = ctx.resources.displayMetrics.widthPixels
        val screenHeight = ctx.resources.displayMetrics.heightPixels
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        var x = location[0]
        var y = location[1] + anchor.height
        if (y + menuHeight > screenHeight && location[1] > menuHeight) {
            // No room below the anchor: open above it instead of getting clipped.
            y = location[1] - menuHeight
        }
        if (x + menuWidth > screenWidth) {
            x = (location[0] + anchor.width - menuWidth).coerceAtLeast(0)
        }
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.thumbnailJob?.cancel()
        holder.thumbnailJob = null
        holder.boundThumbnailKey = null
        super.onViewRecycled(holder)
    }

    fun getEntryAt(position: Int) = getItem(position)

    fun onDetached() {
        scope.cancel()
        popupMenu?.dismiss()
        popupMenu = null
    }

    abstract fun onPaste(entry: ClipboardEntry)

    abstract fun onPin(id: Int)

    abstract fun onUnpin(id: Int)

    abstract fun onEdit(id: Int)

    abstract fun onOpenFile(entry: ClipboardEntry)

    abstract fun onShare(entry: ClipboardEntry)

    open fun shouldShowUpload(entry: ClipboardEntry): Boolean = false

    open fun onUpload(entry: ClipboardEntry) = Unit

    abstract fun onSplitText(text: String)

    abstract fun onSearch(query: String)

    abstract fun onDial(number: String)

    abstract fun onOpenLink(uri: Uri)

    abstract fun onViewImage(uri: Uri)

    abstract fun onDelete(id: Int)

    private fun ClipboardEntry.openableLinkUri(): Uri? {
        if (isUriEntry()) return null
        val raw = text.trim()
        if (raw.isEmpty()) return null
        val direct = runCatching { raw.toUri() }.getOrNull()
        if (direct != null &&
            direct.scheme?.lowercase() in setOf("http", "https") &&
            !direct.host.isNullOrBlank()
        ) {
            return direct
        }
        return if (!raw.contains('\n') && Patterns.WEB_URL.matcher(raw).matches()) {
            "https://$raw".toUri()
        } else {
            null
        }
    }

    private fun ClipboardEntry.searchableQuery(): String? {
        if (isUriEntry()) return null
        val raw = text.trim()
        return raw.takeIf { it.isNotEmpty() }
    }

    private fun ClipboardEntry.splittableText(): String? {
        if (isUriEntry()) return null
        val raw = text.trim()
        return raw.takeIf { it.length > 10 }
    }

    private fun ClipboardEntry.dialableCnMobileNumber(): String? {
        if (isUriEntry()) return null
        val raw = text.trim()
        if (raw.isEmpty() || raw.contains('\n')) return null
        val compact = raw
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")
            .replace("（", "")
            .replace("）", "")
        val normalized = when {
            compact.startsWith("+86") -> compact.removePrefix("+86")
            compact.startsWith("86") && compact.length > 11 -> compact.removePrefix("86")
            else -> compact
        }
        if (!normalized.all { it.isDigit() }) return null
        return normalized.takeIf { cnMainlandMobilePattern.matches(it) }
    }

    private fun ClipboardEntry.viewableImageUri(): Uri? {
        if (!isUriEntry() || !type.startsWith("image/")) return null
        return runCatching { Uri.parse(text) }.getOrNull()
    }

}

private class EntryMenuRow(
    val title: Int,
    val icon: Int,
    val action: () -> Unit
)
