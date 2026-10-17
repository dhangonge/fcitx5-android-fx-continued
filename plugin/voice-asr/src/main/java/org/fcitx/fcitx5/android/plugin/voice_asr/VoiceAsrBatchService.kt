/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.voice_asr

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import org.fcitx.fcitx5.android.voice.provider.BatchProvider

/**
 * Bound-service front end for the Whisper engine, advertised as its own voice
 * input entry next to [VoiceAsrService] because the two behave very differently
 * from the user's point of view (no live words here).
 */
class VoiceAsrBatchService : Service() {

    private var provider: BatchProvider? = null

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "onBind: ${intent?.action}")
        return provider ?: BatchProvider(this) { VoiceInputCallers.enforceAllowed(this) }
            .also { provider = it }
    }

    override fun onDestroy() {
        provider?.shutdown()
        provider = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "FcitxVoiceAsrBatch"
    }
}
