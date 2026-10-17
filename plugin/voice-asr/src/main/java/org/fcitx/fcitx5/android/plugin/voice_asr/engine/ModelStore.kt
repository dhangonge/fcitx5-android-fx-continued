/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.voice_asr.engine

import android.content.Context
import java.io.File

/**
 * Where models live on disk: one directory per model under filesDir/models/.
 *
 * A directory only counts as installed when it holds everything the matching
 * engine needs, so a half-unpacked folder is never offered to the user. The
 * active model is remembered per engine kind, since the two providers are
 * independent entries in the IME's voice input list.
 */
object ModelStore {

    private const val MODELS_DIR = "models"
    private const val PREFS = "voice_asr"
    private const val KEY_ACTIVE_STREAMING = "active_model"
    private const val KEY_ACTIVE_BATCH = "active_model_batch"
    private const val DEFAULT_THREADS = 2

    fun modelsDir(context: Context): File = File(context.filesDir, MODELS_DIR)

    private fun subdirs(context: Context): List<File> {
        val dir = modelsDir(context)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name }
    }

    fun streaming(context: Context): List<StreamingModel> =
        subdirs(context).mapNotNull { StreamingModel.fromDirectory(it) }

    fun batch(context: Context): List<BatchModel> =
        subdirs(context).mapNotNull { BatchModel.fromDirectory(it) }

    fun activeStreaming(context: Context): StreamingModel? =
        pickActive(streaming(context), prefs(context).getString(KEY_ACTIVE_STREAMING, null)) { it.id }

    fun activeBatch(context: Context): BatchModel? =
        pickActive(batch(context), prefs(context).getString(KEY_ACTIVE_BATCH, null)) { it.id }

    fun setActiveStreaming(context: Context, id: String?) =
        putString(context, KEY_ACTIVE_STREAMING, id)

    fun setActiveBatch(context: Context, id: String?) =
        putString(context, KEY_ACTIVE_BATCH, id)

    fun numThreads(context: Context): Int =
        prefs(context).getInt("num_threads", DEFAULT_THREADS).coerceIn(1, 8)

    fun setNumThreads(context: Context, threads: Int) {
        prefs(context).edit().putInt("num_threads", threads.coerceIn(1, 8)).apply()
    }

    /** Removes an installed model, including any "active" mark pointing at it. */
    fun delete(context: Context, dir: File): Boolean {
        if (prefs(context).getString(KEY_ACTIVE_STREAMING, null) == dir.name) {
            setActiveStreaming(context, null)
        }
        if (prefs(context).getString(KEY_ACTIVE_BATCH, null) == dir.name) {
            setActiveBatch(context, null)
        }
        return dir.deleteRecursively()
    }

    private fun <T> pickActive(installed: List<T>, activeId: String?, id: (T) -> String): T? {
        if (installed.isEmpty()) return null
        return installed.firstOrNull { id(it) == activeId } ?: installed.first()
    }

    private fun putString(context: Context, key: String, value: String?) {
        prefs(context).edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
