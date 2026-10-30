/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.voice.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PcmTest {

    private val sampleRate = 16000

    private fun tone(ms: Int, amplitude: Float): FloatArray {
        val out = FloatArray(sampleRate / 1000 * ms)
        for (i in out.indices) out[i] = if (i % 2 == 0) amplitude else -amplitude
        return out
    }

    @Test
    fun `decodes little endian pcm to normalized floats`() {
        // 0x4000 = 16384 → 0.5, and -32768 → -1.0
        val pcm = byteArrayOf(0x00, 0x40, 0x00, 0x80.toByte())
        assertArrayEquals(floatArrayOf(0.5f, -1f), Pcm.toFloatSamples(pcm, 0, pcm.size), 0.0001f)
    }

    @Test
    fun `trim keeps loud middle and drops silent head and tail`() {
        val silence = tone(500, 0f)
        val speech = tone(700, 0.2f)
        val samples = silence + speech + silence
        val trimmed = Pcm.trimSilence(samples, sampleRate)
        assertTrue("expected less audio, got ${trimmed.size}", trimmed.size < samples.size)
        assertTrue("speech must survive, kept ${trimmed.size}", trimmed.size >= speech.size)
    }

    @Test
    fun `trim returns nothing for pure silence`() {
        assertEquals(0, Pcm.trimSilence(tone(1000, 0f), sampleRate).size)
    }

    @Test
    fun `trim leaves short clips untouched`() {
        val short = tone(40, 0.1f)
        assertArrayEquals(short, Pcm.trimSilence(short, sampleRate), 0f)
    }
}

class ModelCatalogTest {

    @Test
    fun `every mirrored model names files that the engine can load`() {
        for (model in ModelCatalog.all()) {
            assertTrue("${model.id} has no mirror", model.hasMirror)
            assertTrue("${model.id} has an empty file list", model.mirrorFiles.isNotEmpty())
            assertEquals(
                "${model.id} mirror size",
                model.mirrorFiles.sumOf { it.bytes },
                model.mirrorBytes,
            )
            assertTrue(
                "${model.id} has a token table",
                model.mirrorFiles.any { it.name == "tokens.txt" },
            )
        }
    }

    @Test
    fun `mirror url points at the repo main branch`() {
        val model = ModelCatalog.find("sherpa-onnx-paraformer-zh-small-2024-03-09")!!
        val file = model.mirrorFiles.first()
        assertEquals(
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09" +
                "/resolve/main/${file.name}",
            ModelCatalog.mirrorUrl(model, file),
        )
    }

    @Test
    fun `batch models are the ones sherpa can read without an encoder-decoder pair`() {
        val ids = ModelCatalog.batch.map { it.id }
        assertTrue(ids.contains("sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"))
        assertTrue(ids.contains("sherpa-onnx-paraformer-zh-small-2024-03-09"))
        assertTrue(ModelCatalog.batch.none { it.id.contains("whisper") })
    }
}

class BatchModelTest {

    private fun dir(name: String, vararg files: String): File {
        val root = File.createTempFile("model", "").apply { delete(); mkdirs() }
        val modelDir = File(root, name).apply { mkdirs() }
        files.forEach { File(modelDir, it).writeText("x") }
        return modelDir
    }

    @Test
    fun `recognizes sense voice`() {
        val model = BatchModel.fromDirectory(
            dir("sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17", "model.int8.onnx", "tokens.txt")
        )!!
        assertEquals(BatchFamily.SENSE_VOICE, model.family)
        assertEquals("model.int8.onnx", model.model!!.name)
        assertNull(model.encoder)
    }

    @Test
    fun `recognizes paraformer`() {
        val model = BatchModel.fromDirectory(
            dir("sherpa-onnx-paraformer-zh-small-2024-03-09", "model.int8.onnx", "tokens.txt")
        )!!
        assertEquals(BatchFamily.PARAFORMER, model.family)
    }

    @Test
    fun `recognizes whisper, which stays loadable but is no longer offered`() {
        val model = BatchModel.fromDirectory(
            dir("sherpa-onnx-whisper-tiny", "tiny-encoder.int8.onnx", "tiny-decoder.int8.onnx", "tiny-tokens.txt")
        )!!
        assertEquals(BatchFamily.WHISPER, model.family)
        assertEquals("tiny-encoder.int8.onnx", model.encoder!!.name)
    }

    @Test
    fun `rejects a streaming transducer`() {
        val model = BatchModel.fromDirectory(
            dir(
                "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23",
                "encoder-epoch-99-avg-1.int8.onnx",
                "decoder-epoch-99-avg-1.int8.onnx",
                "joiner-epoch-99-avg-1.int8.onnx",
                "tokens.txt",
            )
        )
        assertNull(model)
    }

    @Test
    fun `rejects an incomplete download`() {
        assertNull(BatchModel.fromDirectory(dir("sherpa-onnx-paraformer-zh-small-2024-03-09", "tokens.txt")))
    }
}
