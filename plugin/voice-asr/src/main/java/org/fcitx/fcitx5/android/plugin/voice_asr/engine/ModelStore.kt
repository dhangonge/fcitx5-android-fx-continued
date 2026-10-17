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
    private const val PREFS = "voice_asr"
    private const val KEY_ACTIVE = "active_model"

    fun modelsDir(context: Context): File = File(context.filesDir, MODELS_DIR)

    fun available(context: Context): List<StreamingModel> {
        val dir = modelsDir(context)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { StreamingModel.fromDirectory(it) }
            .sortedBy { it.id }
    }

    /** The model the user picked, or the only installed one when nothing is pinned. */
    fun active(context: Context): StreamingModel? {
        val installed = available(context)
        if (installed.isEmpty()) return null
        val picked = prefs(context).getString(KEY_ACTIVE, null)
        return installed.firstOrNull { it.id == picked } ?: installed.first()
    }

    fun setActive(context: Context, id: String?) {
        prefs(context).edit().apply {
            if (id == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, id)
        }.apply()
    }

    fun numThreads(context: Context): Int =
        prefs(context).getInt("num_threads", DEFAULT_THREADS).coerceIn(1, 8)

    fun setNumThreads(context: Context, threads: Int) {
        prefs(context).edit().putInt("num_threads", threads.coerceIn(1, 8)).apply()
    }

    fun delete(context: Context, model: StreamingModel): Boolean {
        if (active(context)?.id == model.id) setActive(context, null)
        return model.dir.deleteRecursively()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val DEFAULT_THREADS = 2
}
