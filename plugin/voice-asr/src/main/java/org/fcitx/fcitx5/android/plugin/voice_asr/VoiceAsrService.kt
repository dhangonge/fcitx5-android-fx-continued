/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.voice_asr

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputCallback
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputProvider
import org.fcitx.fcitx5.android.common.ipc.VoiceInputIpc
import org.fcitx.fcitx5.android.plugin.voice_asr.engine.ModelStore
import org.fcitx.fcitx5.android.plugin.voice_asr.engine.Pcm
import org.fcitx.fcitx5.android.plugin.voice_asr.engine.StreamingEngine
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    /** Audio blocks handed to the worker and not decoded yet. */
    private val queuedBlocks = AtomicInteger(0)

    @Volatile
    private var destroyed = false

    /** Set synchronously by cancel/stop so audio already queued is dropped. */
    @Volatile
    private var stopRequested = false

    @Volatile
    private var engine: StreamingEngine? = null
    private var engineModelId: String? = null
    private var session: StreamingEngine.Session? = null
    private var callback: IVoiceInputCallback? = null
    private var sessionActive = false
    private var lastPartial = ""

    @Volatile
    private var sampleRate = SAMPLE_RATE

    @Volatile
    private var silenceMs = ENDPOINT_SILENCE_MS

    private val binder = object : IVoiceInputProvider.Stub() {

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            VoiceInputCallers.enforceAllowed(this@VoiceAsrService)
            return super.onTransact(code, data, reply, flags)
        }

        override fun isAvailable(): Boolean {
            // availability is about having a model on disk, not about it being
            // loaded: a cold start still works, it is just slower to answer.
            val available = runCatching { ModelStore.activeStreaming(this@VoiceAsrService) != null }
                .getOrDefault(false)
            if (available) preloadEngine()
            return available
        }

        override fun getPreferredConfig(): Bundle = Bundle().apply {
            putInt(VoiceInputIpc.ConfigKeys.SAMPLE_RATE, sampleRate)
            putInt(VoiceInputIpc.ConfigKeys.BITS_PER_SAMPLE, BITS_PER_SAMPLE)
            putInt(VoiceInputIpc.ConfigKeys.CHANNELS, CHANNELS)
            putLong(VoiceInputIpc.ConfigKeys.SILENCE_MS, silenceMs)
        }

        override fun configure(params: Bundle) {
            params.getInt(VoiceInputIpc.ConfigKeys.SAMPLE_RATE, SAMPLE_RATE)
                .takeIf { it > 0 }?.let { sampleRate = it }
            params.getLong(VoiceInputIpc.ConfigKeys.SILENCE_MS, ENDPOINT_SILENCE_MS)
                .takeIf { it > 0 }?.let { silenceMs = it }
            Log.i(TAG, "configure: rate=$sampleRate silence=$silenceMs keys=${params.keySet()}")
        }

        override fun startSession(cb: IVoiceInputCallback) {
            stopRequested = false
            submit { startSessionOnWorker(cb) }
        }

        override fun feedAudio(pcm: ByteArray, offset: Int, len: Int, ptsMs: Long) {
            if (offset < 0 || len < 0 || offset + len > pcm.size) {
                submit {
                    callback?.let {
                        fail(
                            it,
                            VoiceInputIpc.ErrorCodes.INVALID_AUDIO,
                            "Invalid audio range $offset+$len of ${pcm.size}"
                        )
                    }
                }
                return
            }
            if (len == 0 || stopRequested) return
            // A slow decoder would otherwise grow the queue without bound and the
            // transcript would trail the speaker by seconds. Dropping whole blocks
            // keeps the recognizer near realtime.
            if (queuedBlocks.get() >= MAX_QUEUED_BLOCKS) return
            val copy = pcm.copyOfRange(offset, offset + len)
            queuedBlocks.incrementAndGet()
            submit { feedOnWorker(copy) }
        }

        override fun endStream() = submit { endStreamOnWorker() }

        override fun cancelSession() {
            stopRequested = true
            submit { finishOnWorker() }
        }

        override fun stopSession() {
            stopRequested = true
            submit { finishOnWorker() }
        }
    }

    private fun submit(block: () -> Unit) {
        if (destroyed) return
        runCatching { worker.execute(block) }
            .onFailure { Log.w(TAG, "worker rejected task", it) }
    }

    /** Loads the model in the background so the next session answers instantly. */
    private fun preloadEngine() {
        if (engine != null) return
        submit {
            runCatching { ensureEngine() }.onFailure { Log.w(TAG, "preload failed", it) }
        }
    }

    private fun startSessionOnWorker(cb: IVoiceInputCallback) {
        if (sessionActive || session != null) {
            Log.w(TAG, "startSession while a session is live, dropping the old one")
            finishOnWorker()
        }
        callback = cb
        sessionActive = true
        lastPartial = ""
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
        val model = ModelStore.activeStreaming(this) ?: return null
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
        try {
            if (stopRequested) return
            val active = session ?: return
            val cb = callback ?: return
            if (!sessionActive) return
            val samples = Pcm.toFloatSamples(pcm, 0, pcm.size)
            if (samples.isEmpty()) return
            cb.onVolumeLevel(Pcm.rmsOf(samples))
            val text = active.accept(samples, sampleRate)
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
            callback?.let { fail(it, VoiceInputIpc.ErrorCodes.DECODE_FAILED, e.message ?: "Decode failed") }
        } finally {
            queuedBlocks.updateAndGet { if (it > 0) it - 1 else 0 }
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
        queuedBlocks.set(0)
        closeSession()
        callback = null
        if (wasActive) cb?.onSessionEnded()
    }

    private fun fail(cb: IVoiceInputCallback, code: Int, message: String) {
        val wasActive = sessionActive
        sessionActive = false
        lastPartial = ""
        queuedBlocks.set(0)
        closeSession()
        callback = null
        runCatching { cb.onError(code, message) }
        if (wasActive) runCatching { cb.onSessionEnded() }
    }

    private fun closeSession() {
        runCatching { session?.close() }
        session = null
    }

    /** Reads the pending transcript and closes the stream. */
    private fun StreamingEngine.Session.finishOrNull(): String? =
        runCatching { finish() }.getOrNull()

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "onBind: ${intent?.action}")
        return binder
    }

    override fun onDestroy() {
        stopRequested = true
        submit { finishOnWorker() }
        submit { closeEngine() }
        destroyed = true
        worker.shutdown()
        runCatching { worker.awaitTermination(2, TimeUnit.SECONDS) }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "FcitxVoiceAsr"
        const val SAMPLE_RATE = 16000
        const val BITS_PER_SAMPLE = 16
        const val CHANNELS = 1

        /** Matches the second endpoint rule of [StreamingEngine] (1.4 s of silence). */
        const val ENDPOINT_SILENCE_MS = 1400L

        /** 100 ms per block from the IME, so this caps the backlog at two seconds. */
        const val MAX_QUEUED_BLOCKS = 20
    }
}
