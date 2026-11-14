/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

internal object KeyboardUiDefaults {
    fun bordered(modern: Boolean, builtin: Boolean, stored: Boolean?): Boolean =
        stored ?: (modern && builtin)

    fun radius(modern: Boolean, builtin: Boolean, stored: Int?): Int =
        stored ?: if (modern && builtin) 6 else 4

    fun extensionRadius(modern: Boolean, builtin: Boolean, stored: Int?): Int =
        stored ?: if (modern && builtin) 10 else 4
}
