/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.voice_asr.engine

import android.content.Context
import java.io.File

/**
 * Where streaming models live on disk: one directory per model under
 * filesDir/models/. A directory counts as installed only when it holds the whole
 * transducer triple plus tokens.txt.
 */
object ModelStore {

    private const val MODELS_DIR = "models"

    fun modelsDir(context: Context): File = File(context.filesDir, MODELS_DIR)

    fun available(context: Context): List<StreamingModel> {
        val dir = modelsDir(context)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { StreamingModel.fromDirectory(it) }
            .sortedBy { it.id }
    }

    /**
     * The model to use when the user has not picked one. Stage 1 has no picker
     * yet, so this is simply the first complete installation.
     */
    fun active(context: Context): StreamingModel? = available(context).firstOrNull()
}
