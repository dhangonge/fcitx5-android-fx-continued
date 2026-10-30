/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.voice.engine

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a real Chinese recording with the shipped configuration.
 *
 * The engine needs the JNI library and a model, neither of which fits in a unit
 * test, so this runs on a device. The model and the clip are not checked in:
 * push them into the test app's external files dir first, or the test is skipped.
 *
 *   adb push sense-voice/ /sdcard/Android/data/org.fcitx.fcitx5.android.lib.voice_asr.test/files/
 *   adb push zh.wav /sdcard/Android/data/org.fcitx.fcitx5.android.lib.voice_asr.test/files/
 */
class BatchEngineDeviceTest {

    private val files: File
        get() = requireNotNull(
            InstrumentationRegistry.getInstrumentation().context.getExternalFilesDir(null)
        )

    @Test
    fun decodesChineseSpeech() {
        val root = files
        val modelDir = File(root, MODEL_ID)
        val wav = File(root, "zh.wav")
        assumeTrue("push $MODEL_ID and zh.wav into ${root.path}", modelDir.isDirectory && wav.isFile)

        val model = requireNotNull(BatchModel.fromDirectory(modelDir)) { "unrecognised model dir" }
        assertTrue("expected a fused model", model.model != null)

        val samples = readWav(wav)
        val engine = BatchEngine(model, numThreads = 4)
        val text = try {
            engine.transcribe(samples, SAMPLE_RATE)
        } finally {
            engine.close()
        }
        Log.i(TAG, "decoded ${samples.size} samples as: $text")
        assertTrue("no text for a speech clip", text.isNotBlank())
        assertTrue("transcript is not Chinese: $text", text.any { it.code in 0x4E00..0x9FFF })
    }

    /** 16-bit little-endian mono WAV, which is what sherpa-onnx publishes. */
    private fun readWav(file: File): FloatArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = buffer.getInt(pos + 4)
            val body = pos + 8
            if (id == "data") {
                val count = minOf(size, bytes.size - body) / 2
                return FloatArray(count) { i ->
                    buffer.getShort(body + 2 * i) / 32768f
                }
            }
            pos = body + size + (size and 1)
        }
        error("no data chunk in ${file.name}")
    }

    private companion object {
        const val TAG = "FcitxVoiceAsrTest"
        const val SAMPLE_RATE = 16000
        const val MODEL_ID = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
    }
}
