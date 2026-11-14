/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.os.Bundle
import androidx.preference.SwitchPreference
import androidx.annotation.Keep
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.data.theme.ThemeManager

class ThemeSettingsFragment : ManagedPreferenceFragment(ThemeManager.prefs) {

    private val followSystemDayNightTheme = ThemeManager.prefs.followSystemDayNightTheme

    private lateinit var switchPreference: SwitchPreference

    @Keep
    private val onProfileChange = ManagedPreferenceProvider.OnChangeListener { key ->
        val prefs = ThemeManager.prefs
        if (key == prefs.keyboardUiStyle.key || key in prefs.dayNightModePrefNames || key == prefs.normalModeTheme.key) {
            view?.post {
                // ThemeManager and this listener have no guaranteed iteration order.
                if (isResumed) onCreatePreferences(null, null)
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        switchPreference = findPreference(followSystemDayNightTheme.key)!!
    }

    override fun onResume() {
        super.onResume()
        // Recreate controls so their DataStore re-reads profile defaults without persisting them.
        onCreatePreferences(null, null)
        ThemeManager.prefs.registerOnChangeListener(onProfileChange)
        switchPreference.isChecked = followSystemDayNightTheme.getValue()
    }

    override fun onPause() {
        ThemeManager.prefs.unregisterOnChangeListener(onProfileChange)
        super.onPause()
    }
}
