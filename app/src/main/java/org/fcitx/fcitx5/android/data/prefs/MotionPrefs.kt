/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.SharedPreferences
import org.fcitx.fcitx5.android.R

class MotionPrefs(sharedPreferences: SharedPreferences) :
    ManagedPreferenceCategory(R.string.keyboard_motion_settings, sharedPreferences) {

    val master = switch(
        R.string.keyboard_motion_master, "keyboard_motion_master", true,
        summary = R.string.keyboard_motion_master_summary
    )
    val panels = switch(R.string.keyboard_motion_panels, "keyboard_motion_panels", true) {
        master.getValue()
    }
    val candidates = switch(R.string.keyboard_motion_candidates, "keyboard_motion_candidates", true) {
        master.getValue()
    }
    val layout = switch(R.string.keyboard_motion_layout, "keyboard_motion_layout", true) {
        master.getValue()
    }
    val voice = switch(R.string.keyboard_motion_voice, "keyboard_motion_voice", true) {
        master.getValue()
    }
    val material = switch(
        R.string.keyboard_motion_material, "keyboard_motion_material", false,
        summary = R.string.keyboard_motion_material_summary
    ) { master.getValue() }
    val tools = switch(R.string.keyboard_motion_tools, "keyboard_motion_tools", true) {
        master.getValue()
    }
    val scenes = switch(R.string.keyboard_motion_scenes, "keyboard_motion_scenes", true) {
        master.getValue()
    }
}
