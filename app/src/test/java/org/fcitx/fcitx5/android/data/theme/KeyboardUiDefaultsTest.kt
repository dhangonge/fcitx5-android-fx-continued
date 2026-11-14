/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardUiDefaultsTest {
    @Test fun onlyUnsetModernBuiltinValuesReceiveTheNewDefaults() {
        for (modern in listOf(false, true)) for (builtin in listOf(false, true)) {
            assertEquals(modern && builtin, KeyboardUiDefaults.bordered(modern, builtin, null))
            assertEquals(if (modern && builtin) 6 else 4, KeyboardUiDefaults.radius(modern, builtin, null))
            assertEquals(if (modern && builtin) 10 else 4, KeyboardUiDefaults.extensionRadius(modern, builtin, null))
        }
    }

    @Test fun explicitUserSettingsRemainAuthoritativeAcrossProfilesAndThemes() {
        for (modern in listOf(false, true)) for (builtin in listOf(false, true)) {
            for (border in listOf(false, true)) {
                assertEquals(border, KeyboardUiDefaults.bordered(modern, builtin, border))
            }
            for (radius in listOf(0, 4, 6, 16, 48)) {
                assertEquals(radius, KeyboardUiDefaults.radius(modern, builtin, radius))
                assertEquals(radius, KeyboardUiDefaults.extensionRadius(modern, builtin, radius))
            }
        }
    }
}
