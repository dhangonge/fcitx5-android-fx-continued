/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.voice_asr.engine

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/**
 * Whole-utterance recognizer backed by Whisper through sherpa-onnx (no extra
 * native library: the pinned JNI build already contains Whisper).
 *
 * Unlike [StreamingEngine] there are no partial results; the caller buffers the
 * audio and gets one transcript for the whole recording.
 */
class BatchEngine(model: BatchModel, numThreads: Int = 2) : AutoCloseable {

    private val recognizer = OfflineRecognizer(
        assetManager = null,
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = StreamingModel.SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = model.encoder.absolutePath,
                    decoder = model.decoder.absolutePath,
                    // Empty means "detect the language", which is what a
                    // multilingual Whisper model should do here.
                    language = "",
                    task = "transcribe",
                    tailPaddings = 1000,
                ),
                tokens = model.tokens.absolutePath,
                numThreads = numThreads,
                modelType = "whisper",
            ),
            decodingMethod = "greedy_search",
        )
    )

    fun transcribe(samples: FloatArray, sampleRate: Int): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()
}
