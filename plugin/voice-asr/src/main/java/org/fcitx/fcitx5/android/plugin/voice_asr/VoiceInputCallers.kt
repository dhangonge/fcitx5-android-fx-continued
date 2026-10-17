/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.voice_asr

import android.content.Context
import android.os.Binder
import android.os.Process

/**
 * Caller check for the voice input services, which are exported so the IME can
 * discover them with [android.content.pm.PackageManager.queryIntentServices].
 *
 * They cannot carry `android:permission="${mainApplicationId}.permission.PLUGIN"`
 * like other plugins do: `${mainApplicationId}` always resolves to the mainline
 * application id in a plugin, while every host declares that permission under its
 * own id, so a guard naming one id makes the service unbindable from the other
 * three variants. Identity is checked on the binder thread instead.
 */
internal object VoiceInputCallers {

    private val HOST_PACKAGES = setOf(
        "org.fcitx.fcitx5.android",
        "org.fcitx.fcitx5.android.debug",
        "org.fcitx.fcitx5.android.fx",
        "org.fcitx.fcitx5.android.fx.debug",
    )

    fun enforceAllowed(context: Context) {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID) return
        val callingPackages = context.packageManager.getPackagesForUid(uid).orEmpty()
        if (callingPackages.any { it in HOST_PACKAGES }) return
        throw SecurityException(
            "voice input provider called by uid $uid (${callingPackages.joinToString()})"
        )
    }
}
