/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import timber.log.Timber

/**
 * Query file display name for uri
 *
 * ref: [androidx.documentfile](https://github.com/androidx/androidx/blob/8e30346c2bb3b53a3bd45e9a56f3344d98f2356f/documentfile/documentfile/src/main/java/androidx/documentfile/provider/DocumentsContractApi19.java#L150)
 * @see android.provider.DocumentsContract.Document#COLUMN_DISPLAY_NAME
 */
fun ContentResolver.queryFileName(uri: Uri): String? = runCatching {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
    }
}.onFailure { error ->
    Timber.w(error, "Failed to query file name for uri: %s", uri)
}.getOrNull()

/**
 * Best-effort display name for a clipboard uri entry, falling back to the last path segment.
 */
fun resolveClipboardUriFileName(context: Context, uri: Uri): String? = when (uri.scheme) {
    "content" -> context.contentResolver.queryFileName(uri) ?: uri.lastPathSegment ?: uri.path
    else -> uri.lastPathSegment ?: uri.path
}?.let { Uri.decode(it).substringAfterLast('/').substringAfterLast(':') }
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
