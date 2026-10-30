/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.voice.engine

import java.io.File

/** Which architecture a whole-utterance model directory holds. */
enum class BatchFamily {
    /** Fused zh/en/ja/ko/yue model with its own language token. */
    SENSE_VOICE,

    /** Fused Chinese-only model, the smallest of the three. */
    PARAFORMER,

    /** Encoder + decoder pair. No longer offered for download. */
    WHISPER,
}

/**
 * A whole-utterance model directory. Two shapes exist: SenseVoice and Paraformer
 * ship one fused `model.onnx`, Whisper needs a separate encoder and decoder.
 *
 * Int8 weights are preferred when the directory has both.
 */
data class BatchModel(
    val id: String,
    val dir: File,
    val family: BatchFamily,
    /** SenseVoice and Paraformer only. */
    val model: File? = null,
    /** Whisper only. */
    val encoder: File? = null,
    /** Whisper only. */
    val decoder: File? = null,
    val tokens: File,
) {
    val sizeBytes: Long
        get() = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        private val PREFERRED_SUFFIXES = listOf(".int8.onnx", ".onnx")

        fun fromDirectory(dir: File): BatchModel? {
            if (!dir.isDirectory) return null
            val files = dir.listFiles().orEmpty().filter { it.isFile }
            // A streaming transducer also has encoder/decoder files; its joiner
            // gives it away.
            if (files.any { it.name.contains("joiner", true) }) return null
            val tokens = files.firstOrNull { it.name.contains("tokens", true) } ?: return null
            return when (familyOf(dir.name)) {
                BatchFamily.SENSE_VOICE, BatchFamily.PARAFORMER -> {
                    val model = files.pickFused() ?: return null
                    BatchModel(dir.name, dir, familyOf(dir.name), model = model, tokens = tokens)
                }

                BatchFamily.WHISPER -> {
                    val encoder = files.pick("encoder") ?: return null
                    val decoder = files.pick("decoder") ?: return null
                    BatchModel(
                        id = dir.name,
                        dir = dir,
                        family = BatchFamily.WHISPER,
                        encoder = encoder,
                        decoder = decoder,
                        tokens = tokens,
                    )
                }
            }
        }

        /**
         * The fused architectures are told apart by directory name, which is the
         * model id both the archive and the mirror download create. A directory
         * of unknown shape is treated as Whisper, the only variant that then
         * still has a chance of loading.
         */
        private fun familyOf(dirName: String): BatchFamily = when {
            dirName.contains("sense", ignoreCase = true) -> BatchFamily.SENSE_VOICE
            dirName.contains("paraformer", ignoreCase = true) -> BatchFamily.PARAFORMER
            else -> BatchFamily.WHISPER
        }

        private fun List<File>.pickFused(): File? =
            PREFERRED_SUFFIXES.firstNotNullOfOrNull { suffix ->
                firstOrNull { it.name.equals("model$suffix", ignoreCase = true) }
            }

        private fun List<File>.pick(kind: String): File? {
            val candidates = filter { it.name.contains(kind, ignoreCase = true) }
            if (candidates.isEmpty()) return null
            return PREFERRED_SUFFIXES.firstNotNullOfOrNull { suffix ->
                candidates.firstOrNull { it.name.endsWith(suffix, ignoreCase = true) }
            }
        }
    }
}
