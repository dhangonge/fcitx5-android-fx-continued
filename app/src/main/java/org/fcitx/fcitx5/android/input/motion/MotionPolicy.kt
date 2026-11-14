/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.motion

import android.animation.ValueAnimator
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

enum class MotionDomain {
    Panels, Candidates, Layout, Voice, Material, Tools, Scenes
}

object MotionPolicy {
    @Volatile
    private var legacySystemAnimationsEnabled = false

    val systemMotionSettingsUri: Uri
        get() = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)

    /** Call on lifecycle entry and from a lifecycle-owned observer of [systemMotionSettingsUri]. */
    fun refreshSystemMotion(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            legacySystemAnimationsEnabled = try {
                Settings.Global.getFloat(
                    context.applicationContext.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f
                ) > 0f
            } catch (_: SecurityException) {
                false
            }
        }
    }

    fun enabled(domain: MotionDomain): Boolean {
        val prefs = AppPrefs.getInstance()
        val motion = prefs.motion
        if (!motion.master.getValue() || prefs.advanced.disableAnimation.getValue()) return false
        val domainEnabled = when (domain) {
            MotionDomain.Panels -> motion.panels
            MotionDomain.Candidates -> motion.candidates
            MotionDomain.Layout -> motion.layout
            MotionDomain.Voice -> motion.voice
            MotionDomain.Material -> motion.material
            MotionDomain.Tools -> motion.tools
            MotionDomain.Scenes -> motion.scenes
        }.getValue()
        if (!domainEnabled) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ValueAnimator.areAnimatorsEnabled()
        } else {
            legacySystemAnimationsEnabled
        }
    }
}
