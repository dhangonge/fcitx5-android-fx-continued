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
import org.fcitx.fcitx5.android.plugin.voice_asr.engine.ModelStore
import org.fcitx.fcitx5.android.plugin.voice_asr.engine.StreamingEngine
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Offline streaming ASR provider.
 *
 * The IME records audio and pushes PCM here, so every recognizer call happens on
 * a single worker thread (sherpa-onnx streams are not thread-safe) while the IME
 * only ever sees oneway callbacks.
 */
class VoiceAsrService : Service() {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "voice-asr").apply { isDaemon = true }
    }

    private var engine: StreamingEngine? = null
    private var engineModelId: String? = null
    private var session: StreamingEngine.Session? = null
    private var callback: IVoiceInputCallback? = null
    private var sessionActive = false
    private var lastPartial = ""

    private val binder = object : IVoiceInputProvider.Stub() {

        override fun isAvailable(): Boolean = runCatching {
            engine != null || ModelStore.active(this@VoiceAsrService) != null
        }.getOrDefault(false)

        override fun getPreferredConfig(): Bundle = Bundle().apply {
            putInt(VoiceInputIpc.ConfigKeys.SAMPLE_RATE, SAMPLE_RATE)
            putInt(VoiceInputIpc.ConfigKeys.BITS_PER_SAMPLE, BITS_PER_SAMPLE)
            putInt(VoiceInputIpc.ConfigKeys.CHANNELS, CHANNELS)
            putLong(VoiceInputIpc.ConfigKeys.SILENCE_MS, SILENCE_MS)
        }

        override fun configure(params: Bundle) {
            Log.i(TAG, "configure: ${params.keySet()}")
        }

        override fun startSession(cb: IVoiceInputCallback) = worker.execute {
            callback = cb
            sessionActive = true
            lastPartial = ""
            startSessionOnWorker(cb)
        }

        override fun feedAudio(pcm: ByteArray, offset: Int, len: Int, ptsMs: Long) {
            val copy = pcm.copyOfRange(offset, offset + len)
            worker.execute { feedOnWorker(copy) }
        }

        override fun endStream() = worker.execute { endStreamOnWorker() }

        override fun cancelSession() = worker.execute { finishOnWorker() }

        override fun stopSession() = worker.execute { finishOnWorker() }
    }

    private fun startSessionOnWorker(cb: IVoiceInputCallback) {
        if (session != null) {
            Log.w(TAG, "startSession while a session is live, dropping the old one")
            closeSession()
        }
        try {
            val active = ensureEngine()
            if (active == null) {
                fail(cb, VoiceInputIpc.ErrorCodes.NOT_AVAILABLE, "No voice model installed")
                return
            }
            session = active.newSession()
            cb.onReady()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start session", e)
            fail(cb, VoiceInputIpc.ErrorCodes.MODEL_LOAD_FAILED, e.message ?: "Model load failed")
        }
    }

    private fun ensureEngine(): StreamingEngine? {
        val model = ModelStore.active(this) ?: return null
        engine?.let { if (engineModelId == model.id) return it }
        closeEngine()
        Log.i(TAG, "Loading model ${model.id} (${model.sizeBytes / 1024 / 1024} MiB)")
        val started = System.currentTimeMillis()
        return StreamingEngine(model, ModelStore.numThreads(this)).also {
            engine = it
            engineModelId = model.id
            Log.i(TAG, "Model loaded in ${System.currentTimeMillis() - started} ms")
        }
    }

    private fun closeEngine() {
        runCatching { engine?.close() }
        engine = null
        engineModelId = null
    }

    private fun feedOnWorker(pcm: ByteArray) {
        val active = session ?: return
        val cb = callback ?: return
        if (!sessionActive) return
        try {
            val samples = toFloatSamples(pcm)
            if (samples.isEmpty()) return
            cb.onVolumeLevel(rmsOf(samples))
            val text = active.accept(samples)
            if (text.isNotBlank() && text != lastPartial) {
                lastPartial = text
                cb.onPartialResult(text)
            }
            if (active.isEndpoint()) {
                active.resetAfterEndpoint()
                lastPartial = ""
                if (text.isNotBlank()) cb.onSegmentFinal(text)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Decode failed", e)
            fail(cb, VoiceInputIpc.ErrorCodes.DECODE_FAILED, e.message ?: "Decode failed")
        }
    }

    private fun endStreamOnWorker() {
        val active = session
        val cb = callback
        if (active != null && cb != null && sessionActive) {
            try {
                val finalText = active.finishOrNull()
                if (!finalText.isNullOrBlank()) cb.onSegmentFinal(finalText)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to finalize session", e)
            }
        }
        finishOnWorker()
    }

    /**
     * Ends the session without finalizing. The engine stays loaded so the next
     * session (which the IME starts on every mic tap) skips model loading.
     */
    private fun finishOnWorker() {
        val cb = callback
        val wasActive = sessionActive
        sessionActive = false
        lastPartial = ""
        closeSession()
        callback = null
        if (wasActive) cb?.onSessionEnded()
    }

    private fun fail(cb: IVoiceInputCallback, code: Int, message: String) {
        sessionActive = false
        lastPartial = ""
        closeSession()
        callback = null
        cb.onError(code, message)
    }

    private fun closeSession() {
        runCatching { session?.close() }
        session = null
    }

    /** Reads the pending transcript and closes the stream. */
    private fun StreamingEngine.Session.finishOrNull(): String? =
        runCatching { finish() }.getOrNull()

    private fun toFloatSamples(pcm: ByteArray): FloatArray {
        val count = pcm.size / 2
        if (count == 0) return FloatArray(0)
        val out = FloatArray(count)
        for (i in 0 until count) {
            val lo = pcm[2 * i].toInt() and 0xFF
            val hi = pcm[2 * i + 1].toInt()
            val value = (hi shl 8) or lo
            out[i] = value / 32768f
        }
        return out
    }

    private fun rmsOf(samples: FloatArray): Int {
        var sum = 0.0
        for (s in samples) sum += (s * 32767f) * (s * 32767f)
        return sqrt(sum / samples.size).toInt()
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "onBind: ${intent?.action}")
        return binder
    }

    override fun onDestroy() {
        worker.execute { finishOnWorker() }
        worker.execute { closeEngine() }
        worker.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "FcitxVoiceAsr"
        const val SAMPLE_RATE = 16000
        const val BITS_PER_SAMPLE = 16
        const val CHANNELS = 1
        const val SILENCE_MS = 3000L
    }
}
