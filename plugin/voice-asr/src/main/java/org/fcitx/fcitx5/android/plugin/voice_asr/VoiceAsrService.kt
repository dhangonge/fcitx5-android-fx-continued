/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.voice_asr

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputCallback
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputProvider
import org.fcitx.fcitx5.android.common.ipc.VoiceInputIpc

/**
 * Stage 0 scaffold: implements the AIDL contract without an ASR engine, so the
 * discovery -> bind -> commit chain can be verified on its own before the native
 * side is added. Replaced by the real engine in the next stage.
 */
class VoiceAsrService : Service() {

    private var callback: IVoiceInputCallback? = null
    private var sessionActive = false

    private val binder = object : IVoiceInputProvider.Stub() {

        override fun isAvailable() = true

        override fun getPreferredConfig(): Bundle = Bundle().apply {
            putInt(VoiceInputIpc.ConfigKeys.SAMPLE_RATE, SAMPLE_RATE)
            putInt(VoiceInputIpc.ConfigKeys.BITS_PER_SAMPLE, BITS_PER_SAMPLE)
            putInt(VoiceInputIpc.ConfigKeys.CHANNELS, CHANNELS)
            putLong(VoiceInputIpc.ConfigKeys.SILENCE_MS, SILENCE_MS)
        }

        override fun configure(params: Bundle) {
            Log.i(TAG, "configure: ${params.keySet()}")
        }

        override fun startSession(cb: IVoiceInputCallback) {
            synchronized(this@VoiceAsrService) {
                callback = cb
                sessionActive = true
            }
            cb.onReady()
        }

        override fun feedAudio(pcm: ByteArray, offset: Int, len: Int, ptsMs: Long) {
            // No engine yet: audio is accepted and dropped.
        }

        override fun endStream() {
            val cb = synchronized(this@VoiceAsrService) {
                if (!sessionActive) return
                callback
            }
            cb?.onSegmentFinal(getString(R.string.scaffold_result))
            finishSession()
        }

        override fun cancelSession() = finishSession()

        override fun stopSession() = finishSession()
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "onBind: ${intent?.action}")
        return binder
    }

    override fun onDestroy() {
        finishSession()
        super.onDestroy()
    }

    private fun finishSession() {
        val cb = synchronized(this) {
            if (!sessionActive) return
            sessionActive = false
            callback.also { callback = null }
        }
        cb?.onSessionEnded()
    }

    private companion object {
        const val TAG = "FcitxVoiceAsr"
        const val SAMPLE_RATE = 16000
        const val BITS_PER_SAMPLE = 16
        const val CHANNELS = 1
        const val SILENCE_MS = 3000L
    }
}
