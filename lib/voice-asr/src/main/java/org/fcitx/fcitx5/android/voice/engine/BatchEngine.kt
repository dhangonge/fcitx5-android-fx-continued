/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.voice.engine

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/**
 * Whole-utterance recognizer through sherpa-onnx (no extra native library: the
 * pinned JNI build already contains SenseVoice, Paraformer and Whisper).
 *
 * Unlike [StreamingEngine] there are no partial results; the caller buffers the
 * audio and gets one transcript for the whole recording. Leading and trailing
 * silence is trimmed first: these models hallucinate words on noise, which a few
 * seconds of room tone before the first syllable is enough to trigger.
 */
class BatchEngine(model: BatchModel, numThreads: Int = 2) : AutoCloseable {

    private val recognizer = OfflineRecognizer(
        assetManager = null,
        config = configOf(model, numThreads),
    )

    fun transcribe(samples: FloatArray, sampleRate: Int): String {
        val speech = Pcm.trimSilence(samples, sampleRate)
        if (speech.size < sampleRate / 1000 * MIN_SPEECH_MS) return ""
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(speech, sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()

    private companion object {
        const val FEATURE_DIM = 80
        const val MIN_SPEECH_MS = 200

        fun configOf(model: BatchModel, numThreads: Int): OfflineRecognizerConfig {
            val modelConfig = when (model.family) {
                BatchFamily.SENSE_VOICE -> OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = requireNotNull(model.model).absolutePath,
                        // Empty means "let the model decide". SenseVoice predicts
                        // the language from a dedicated token rather than from the
                        // decoder's first step, which is what makes Whisper guess
                        // wrong on short clips.
                        language = "",
                        useInverseTextNormalization = true,
                    ),
                    tokens = model.tokens.absolutePath,
                    numThreads = numThreads,
                )

                BatchFamily.PARAFORMER -> OfflineModelConfig(
                    paraformer = OfflineParaformerModelConfig(
                        model = requireNotNull(model.model).absolutePath,
                    ),
                    tokens = model.tokens.absolutePath,
                    numThreads = numThreads,
                    modelType = "paraformer",
                )

                BatchFamily.WHISPER -> OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = requireNotNull(model.encoder).absolutePath,
                        decoder = requireNotNull(model.decoder).absolutePath,
                        language = "",
                        task = "transcribe",
                        tailPaddings = 1000,
                    ),
                    tokens = model.tokens.absolutePath,
                    numThreads = numThreads,
                    modelType = "whisper",
                )
            }
            return OfflineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = StreamingModel.SAMPLE_RATE,
                    featureDim = FEATURE_DIM,
                ),
                modelConfig = modelConfig,
                decodingMethod = "greedy_search",
            )
        }
    }
}
