/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.voice_asr

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import org.fcitx.fcitx5.android.voice.provider.StreamingProvider

/**
 * Bound-service front end for the streaming engine.
 *
 * The engine itself lives in :lib:voice-asr and is shared with the IME app, so
 * this class only adds the two things a plugin needs: a caller whitelist and a
 * shutdown hook for [Service.onDestroy].
 */
class VoiceAsrService : Service() {

    private var provider: StreamingProvider? = null

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "onBind: ${intent?.action}")
        return provider ?: StreamingProvider(this) { VoiceInputCallers.enforceAllowed(this) }
            .also { provider = it }
    }

    override fun onDestroy() {
        provider?.shutdown()
        provider = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "FcitxVoiceAsr"
    }
}
