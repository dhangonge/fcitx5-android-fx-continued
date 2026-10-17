/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.voice_asr.engine

import java.io.File

/**
 * Files of a streaming zipformer/transducer model as laid out by sherpa-onnx.
 */
data class StreamingModel(
    val id: String,
    val dir: File,
    val encoder: File,
    val decoder: File,
    val joiner: File,
    val tokens: File,
) {
    val sizeBytes: Long
        get() = listOf(encoder, decoder, joiner, tokens).sumOf { it.length() }

    companion object {
        const val SAMPLE_RATE = 16000

        // int8 encoders are ~4x smaller than fp32 and decode several times faster on
        // CPU, so a directory that ships both flavours should use the quantized one.
        private val PREFERRED_SUFFIXES = listOf(".int8.onnx", ".onnx")

        fun fromDirectory(dir: File): StreamingModel? {
            if (!dir.isDirectory) return null
            val files = dir.listFiles().orEmpty().filter { it.isFile }
            fun pick(part: String): File? = files
                .filter { it.name.contains(part, ignoreCase = true) && it.extension == "onnx" }
                .minByOrNull { f ->
                    PREFERRED_SUFFIXES.indexOfFirst { f.name.endsWith(it, ignoreCase = true) }
                        .let { if (it < 0) PREFERRED_SUFFIXES.size else it }
                }
            val encoder = pick("encoder") ?: return null
            val decoder = pick("decoder") ?: return null
            val joiner = pick("joiner") ?: return null
            val tokens = files.firstOrNull { it.name.equals("tokens.txt", ignoreCase = true) }
                ?: return null
            return StreamingModel(dir.name, dir, encoder, decoder, joiner, tokens)
        }
    }
}
