/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data.theme

import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceCategory
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

class ThemePrefs(sharedPreferences: SharedPreferences) :
    ManagedPreferenceCategory(R.string.theme, sharedPreferences) {

    private fun themePreference(
        @StringRes
        title: Int,
        key: String,
        defaultValue: Theme,
        @StringRes
        summary: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ): ManagedThemePreference {
        val pref = ManagedThemePreference(sharedPreferences, key, defaultValue)
        val ui = ManagedThemePreferenceUi(title, key, defaultValue, summary, enableUiOn)
        pref.register()
        ui.registerUi()
        return pref
    }

    enum class KeyboardUiStyle(override val stringRes: Int) : ManagedPreferenceEnum {
        Modern(R.string.keyboard_ui_style_modern),
        Classic(R.string.keyboard_ui_style_classic);
    }

    val keyboardUiStyle = enumList(
        R.string.keyboard_ui_style,
        "keyboard_ui_style",
        KeyboardUiStyle.Modern
    )

    val keyBorder = switch(R.string.key_border, "key_border", false)

    // Profile defaults are resolved, never migrated into the user's old values.
    fun resolveKeyBorder(theme: Theme): Boolean = KeyboardUiDefaults.bordered(
        keyboardUiStyle.getValue() == KeyboardUiStyle.Modern, theme is Theme.Builtin,
        if (sharedPreferences.contains(keyBorder.key)) keyBorder.getValue() else null
    )

    fun resolveKeyRadius(theme: Theme): Int = KeyboardUiDefaults.radius(
        keyboardUiStyle.getValue() == KeyboardUiStyle.Modern, theme is Theme.Builtin,
        if (sharedPreferences.contains(keyRadius.key)) keyRadius.getValue() else null
    )

    fun resolvePopupRadius(theme: Theme): Int = KeyboardUiDefaults.extensionRadius(
        keyboardUiStyle.getValue() == KeyboardUiStyle.Modern, theme is Theme.Builtin,
        if (sharedPreferences.contains(keyRadius.key)) keyRadius.getValue() else null
    )

    override fun createUi(screen: PreferenceScreen) {
        managedPreferencesUi.forEach { ui ->
            val preference = ui.createUi(screen.context)
            if (ui.key == keyBorder.key || ui.key == keyRadius.key) {
                preference.preferenceDataStore = object : PreferenceDataStore() {
                    override fun getBoolean(key: String?, defValue: Boolean) = resolveKeyBorder(ThemeManager.activeTheme)
                    override fun putBoolean(key: String?, value: Boolean) = keyBorder.setValue(value)
                    override fun getInt(key: String?, defValue: Int) = resolveKeyRadius(ThemeManager.activeTheme)
                    override fun putInt(key: String?, value: Int) = keyRadius.setValue(value)
                }
                preference.setDefaultValue(if (ui.key == keyBorder.key) resolveKeyBorder(ThemeManager.activeTheme)
                    else resolveKeyRadius(ThemeManager.activeTheme))
            }
            preference.isEnabled = if (ui.key == keyBorderStroke.key) resolveKeyBorder(ThemeManager.activeTheme) else ui.isEnabled()
            screen.addPreference(preference)
        }
    }

    val keyBorderStroke = switch(
        R.string.key_border_stroke, "key_border_stroke", false,
        enableUiOn = { resolveKeyBorder(ThemeManager.activeTheme) }
    )

    val specialKeyOvalShape = switch(R.string.special_key_oval_shape, "special_key_oval_shape", false)

    val keyRippleEffect = switch(R.string.key_ripple_effect, "key_ripple_effect", false)

    val keyHorizontalMargin: ManagedPreference.PInt
    val keyHorizontalMarginLandscape: ManagedPreference.PInt

    init {
        val (primary, secondary) = twinInt(
            R.string.key_horizontal_margin,
            R.string.portrait,
            "key_horizontal_margin",
            3,
            R.string.landscape,
            "key_horizontal_margin_landscape",
            3,
            0,
            24,
            "dp"
        )
        keyHorizontalMargin = primary
        keyHorizontalMarginLandscape = secondary
    }

    val keyVerticalMargin: ManagedPreference.PInt
    val keyVerticalMarginLandscape: ManagedPreference.PInt

    init {
        val (primary, secondary) = twinInt(
            R.string.key_vertical_margin,
            R.string.portrait,
            "key_vertical_margin",
            7,
            R.string.landscape,
            "key_vertical_margin_landscape",
            4,
            0,
            24,
            "dp"
        )
        keyVerticalMargin = primary
        keyVerticalMarginLandscape = secondary
    }

    val keyRadius = int(R.string.key_radius, "key_radius", 4, 0, 48, "dp")

    val textEditingButtonRadius =
        int(R.string.text_editing_button_radius, "text_editing_button_radius", 8, 0, 48, "dp")

    val clipboardEntryRadius =
        int(R.string.clipboard_entry_radius, "clipboard_entry_radius", 2, 0, 48, "dp")

    enum class PunctuationPosition(override val stringRes: Int) : ManagedPreferenceEnum {
        None(R.string.punctuation_pos_none),
        Bottom(R.string.punctuation_pos_bottom),
        TopRight(R.string.punctuation_pos_top_right),
        TopCenter(R.string.punctuation_pos_top_center);
    }

    val punctuationPosition = enumList(
        R.string.punctuation_position,
        "punctuation_position",
        PunctuationPosition.Bottom
    )

    enum class NavbarBackground(override val stringRes: Int) : ManagedPreferenceEnum {
        None(R.string.navbar_bkg_none),
        ColorOnly(R.string.navbar_bkg_color_only),
        Full(R.string.navbar_bkg_full);
    }

    val navbarBackground = enumList(
        R.string.navbar_background,
        "navbar_background",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) NavbarBackground.Full else NavbarBackground.ColorOnly,
        // 35+ forces edge to edge
        enableUiOn = { Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM }
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            sharedPreferences.edit {
                remove(this@apply.key)
            }
        }
    }

    /**
     * When [followSystemDayNightTheme] is disabled, this theme is used.
     * This is effectively an internal preference which does not need UI.
     */
    val normalModeTheme = ManagedThemePreference(
        sharedPreferences, "normal_mode_theme", ThemeManager.DefaultTheme
    ).also {
        it.register()
    }

    val followSystemDayNightTheme = switch(
        R.string.follow_system_day_night_theme,
        "follow_system_dark_mode",
        true,
        summary = R.string.follow_system_day_night_theme_summary
    )

    val lightModeTheme = themePreference(
        R.string.light_mode_theme,
        "light_mode_theme",
        if (BuildConfig.DEBUG) ThemePreset.MaterialLight else ThemePreset.PixelLight,
        enableUiOn = {
            followSystemDayNightTheme.getValue()
        })

    val darkModeTheme = themePreference(
        R.string.dark_mode_theme,
        "dark_mode_theme",
        if (BuildConfig.DEBUG) ThemePreset.MaterialDark else ThemePreset.PixelDark,
        enableUiOn = {
            followSystemDayNightTheme.getValue()
        })

    val dayNightModePrefNames = setOf(
        followSystemDayNightTheme.key,
        lightModeTheme.key,
        darkModeTheme.key
    )
}
