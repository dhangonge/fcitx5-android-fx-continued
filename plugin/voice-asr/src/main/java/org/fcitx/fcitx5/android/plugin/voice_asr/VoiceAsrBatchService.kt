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
import org.fcitx.fcitx5.android.plugin.voice_asr.engine.BatchEngine
import org.fcitx.fcitx5.android.plugin.voice_asr.engine.ModelStore
import org.fcitx.fcitx5.android.plugin.voice_asr.engine.Pcm
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Whole-utterance provider: buffers the recording and transcribes it with
 * Whisper when the IME signals the end of the stream. Advertised as its own
 * voice input entry next to the streaming provider, because the two behave very
 * differently from the user's point of view (no live words here).
 */
class VoiceAsrBatchService : Service() {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "voice-asr-batch").apply { isDaemon = true }
    }

    @Volatile
    private var destroyed = false

    @Volatile
    private var stopRequested = false

    @Volatile
    private var engine: BatchEngine? = null
    private var engineModelId: String? = null

    private var callback: IVoiceInputCallback? = null
    private var sessionActive = false
    private val chunks = ArrayList<FloatArray>(64)
    private var sampleCount = 0
    private var overflowLogged = false

    @Volatile
    private var sampleRate = SAMPLE_RATE

    private val binder = object : IVoiceInputProvider.Stub() {

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            VoiceInputCallers.enforceAllowed(this@VoiceAsrBatchService)
            return super.onTransact(code, data, reply, flags)
        }

        override fun isAvailable(): Boolean {
            val available = runCatching { ModelStore.activeBatch(this@VoiceAsrBatchService) != null }
                .getOrDefault(false)
            if (available) preloadEngine()
            return available
        }

        override fun getPreferredConfig(): Bundle = Bundle().apply {
            putInt(VoiceInputIpc.ConfigKeys.SAMPLE_RATE, sampleRate)
            putInt(VoiceInputIpc.ConfigKeys.BITS_PER_SAMPLE, BITS_PER_SAMPLE)
            putInt(VoiceInputIpc.ConfigKeys.CHANNELS, CHANNELS)
            // Nothing is decoded while the user speaks, so silence must not end
            // the recording; releasing the mic does.
            putLong(VoiceInputIpc.ConfigKeys.SILENCE_MS, MAX_RECORDING_MS)
        }

        override fun configure(params: Bundle) {
            params.getInt(VoiceInputIpc.ConfigKeys.SAMPLE_RATE, SAMPLE_RATE)
                .takeIf { it > 0 }?.let { sampleRate = it }
            Log.i(TAG, "configure: rate=$sampleRate keys=${params.keySet()}")
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
            if (len == 0) return
            val copy = pcm.copyOfRange(offset, offset + len)
            submit { appendOnWorker(copy) }
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

    private fun preloadEngine() {
        if (engine != null) return
        submit {
            runCatching { ensureEngine() }.onFailure { Log.w(TAG, "preload failed", it) }
        }
    }

    private fun startSessionOnWorker(cb: IVoiceInputCallback) {
        if (sessionActive) finishOnWorker()
        callback = cb
        chunks.clear()
        sampleCount = 0
        sessionActive = true
        try {
            val active = ensureEngine()
            if (active == null) {
                fail(cb, VoiceInputIpc.ErrorCodes.NOT_AVAILABLE, "No Whisper model installed")
                return
            }
            cb.onReady()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start session", e)
            fail(cb, VoiceInputIpc.ErrorCodes.MODEL_LOAD_FAILED, e.message ?: "Model load failed")
        }
    }

    private fun ensureEngine(): BatchEngine? {
        val model = ModelStore.activeBatch(this) ?: return null
        engine?.let { if (engineModelId == model.id) return it }
        closeEngine()
        Log.i(TAG, "Loading Whisper model ${model.id}")
        val started = System.currentTimeMillis()
        return BatchEngine(model, ModelStore.numThreads(this)).also {
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

    private fun appendOnWorker(pcm: ByteArray) {
        if (stopRequested || !sessionActive) return
        val cb = callback ?: return
        val samples = Pcm.toFloatSamples(pcm, 0, pcm.size)
        if (samples.isEmpty()) return
        cb.onVolumeLevel(Pcm.rmsOf(samples))
        val room = MAX_SAMPLES - sampleCount
        if (room <= 0) {
            if (!overflowLogged) {
                overflowLogged = true
                Log.w(TAG, "Recording exceeded ${MAX_SAMPLES / SAMPLE_RATE} s, dropping the rest")
            }
            return
        }
        chunks.add(if (samples.size <= room) samples else samples.copyOf(room))
        sampleCount += minOf(samples.size, room)
    }

    private fun endStreamOnWorker() {
        val cb = callback
        if (!sessionActive || cb == null) {
            finishOnWorker()
            return
        }
        val total = sampleCount
        if (total <= 0) {
            finishOnWorker()
            return
        }
        val samples = FloatArray(total)
        var copied = 0
        chunks.forEach { chunk ->
            chunk.copyInto(samples, copied)
            copied += chunk.size
        }
        chunks.clear()
        sampleCount = 0
        sessionActive = false
        try {
            val text = ensureEngine()?.transcribe(samples, sampleRate).orEmpty()
            if (text.isNotBlank()) runCatching { cb.onSegmentFinal(text) }
        } catch (e: Throwable) {
            Log.e(TAG, "Transcription failed", e)
            fail(cb, VoiceInputIpc.ErrorCodes.DECODE_FAILED, e.message ?: "Transcription failed")
            return
        }
        callback = null
        runCatching { cb.onSessionEnded() }
    }

    private fun finishOnWorker() {
        val cb = callback
        val wasActive = sessionActive
        sessionActive = false
        chunks.clear()
        sampleCount = 0
        overflowLogged = false
        callback = null
        if (wasActive) cb?.onSessionEnded()
    }

    private fun fail(cb: IVoiceInputCallback, code: Int, message: String) {
        val wasActive = sessionActive
        sessionActive = false
        chunks.clear()
        sampleCount = 0
        overflowLogged = false
        callback = null
        runCatching { cb.onError(code, message) }
        if (wasActive) runCatching { cb.onSessionEnded() }
    }

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
        const val TAG = "FcitxVoiceAsrBatch"
        const val SAMPLE_RATE = 16000
        const val BITS_PER_SAMPLE = 16
        const val CHANNELS = 1

        /** Whisper is slow on long input; a minute is already a lot to decode. */
        const val MAX_RECORDING_MS = 60_000L
        const val MAX_SAMPLES = (MAX_RECORDING_MS * SAMPLE_RATE / 1000).toInt()
    }
}
