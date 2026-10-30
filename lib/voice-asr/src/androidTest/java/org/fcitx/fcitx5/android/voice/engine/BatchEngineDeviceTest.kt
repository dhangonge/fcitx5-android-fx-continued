/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.voice.engine

import android.util.Log
import org.junit.Assert.assertNotNull
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
 * stage them under [STAGING] first, or the test is skipped.
 *
 * The JNI library no longer ships in the app: install the voice-engine plugin APK
 * built from the same commit, or build this variant with -PvoiceEngineEmbedded=true.
 *
 *   adb push sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17 \
 *       /data/local/tmp/voice-asr-test/
 *   adb push zh.wav /data/local/tmp/voice-asr-test/
 *
 * Not the app's own external files dir: a directory that `adb push` created is
 * owned by `shell`, and the storage FUSE layer then hides its entries from the
 * app's uid, which shows up as an empty `listFiles()`.
 */
class BatchEngineDeviceTest {

    private val root = File(STAGING)

    private val wav: File
        get() = File(root, "zh.wav")

    @Test
    fun decodesChineseSpeech() {
        val modelDir = File(root, MODEL_ID)
        assumeTrue("stage the model under ${root.path}", modelDir.isDirectory && wav.isFile)

        val listing = modelDir.listFiles().orEmpty().joinToString { "${it.name}(${it.length()})" }
        Log.i(TAG, "model dir ${modelDir.path}: [$listing]")
        val model = BatchModel.fromDirectory(modelDir)
        assertNotNull("unrecognised model dir, saw [$listing]", model)
        model!!
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
        const val STAGING = "/data/local/tmp/voice-asr-test"
        const val SAMPLE_RATE = 16000
        const val MODEL_ID = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
    }
}
