/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.voice.engine

import java.io.File

/**
 * A Whisper (offline, whole-utterance) model directory: one encoder, one decoder
 * and a token table. Int8 weights are preferred when the archive ships both.
 */
data class BatchModel(
    val id: String,
    val dir: File,
    val encoder: File,
    val decoder: File,
    val tokens: File,
) {
    val sizeBytes: Long
        get() = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        private val PREFERRED_SUFFIXES = listOf(".int8.onnx", ".onnx")

        fun fromDirectory(dir: File): BatchModel? {
            if (!dir.isDirectory) return null
            val files = dir.listFiles().orEmpty().filter { it.isFile }
            // A streaming transducer also has encoder/decoder files; the joiner
            // and the absence of a Whisper-shaped token table tell them apart.
            if (files.any { it.name.contains("joiner", true) }) return null
            val encoder = files.pick("encoder") ?: return null
            val decoder = files.pick("decoder") ?: return null
            val tokens = files.firstOrNull { it.name.contains("tokens", true) } ?: return null
            return BatchModel(
                id = dir.name,
                dir = dir,
                encoder = encoder,
                decoder = decoder,
                tokens = tokens,
            )
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
