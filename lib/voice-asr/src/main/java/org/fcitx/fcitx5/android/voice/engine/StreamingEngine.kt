/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.voice.engine

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Thin wrapper over sherpa-onnx's streaming transducer recognizer.
 *
 * One engine loads the model once and serves many sessions; a [Session] owns a
 * single [OnlineStream] and yields a growing transcript as audio arrives.
 */
class StreamingEngine(model: StreamingModel, numThreads: Int = 1) : AutoCloseable {

    private val recognizer = OnlineRecognizer(
        assetManager = null,
        config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = StreamingModel.SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = model.encoder.absolutePath,
                    decoder = model.decoder.absolutePath,
                    joiner = model.joiner.absolutePath,
                ),
                tokens = model.tokens.absolutePath,
                numThreads = numThreads,
            ),
            // Trailing-silence endpoints let the host commit a sentence without
            // waiting for the user to release the mic.
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),
                rule2 = EndpointRule(true, 1.4f, 0f),
                rule3 = EndpointRule(false, 0f, 20f),
            ),
            enableEndpoint = true,
        )
    )

    fun newSession(): Session = Session(recognizer)

    override fun close() = recognizer.release()

    class Session(private val recognizer: OnlineRecognizer) : AutoCloseable {

        private val stream: OnlineStream = recognizer.createStream()

        /** Feeds mono float samples and returns the current transcript. */
        fun accept(samples: FloatArray, sampleRate: Int = StreamingModel.SAMPLE_RATE): String {
            val rate = if (sampleRate > 0) sampleRate else StreamingModel.SAMPLE_RATE
            stream.acceptWaveform(samples, rate)
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            return recognizer.getResult(stream).text
        }

        fun isEndpoint(): Boolean = recognizer.isEndpoint(stream)

        /** Clears the decoder state after an endpoint so the next sentence starts fresh. */
        fun resetAfterEndpoint() = recognizer.reset(stream)

        /** Flushes whatever is still buffered; call once, right before closing. */
        fun finish(): String {
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            return recognizer.getResult(stream).text
        }

        override fun close() = stream.release()
    }
}
