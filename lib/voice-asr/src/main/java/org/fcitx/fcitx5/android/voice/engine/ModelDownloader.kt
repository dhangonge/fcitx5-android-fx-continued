/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.voice.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Downloads a model's weight files from the China mirror straight into
 * `filesDir/models/<id>`, one HTTP request per file.
 *
 * This does not go through the download manager: a model there is a single
 * request per archive, and the mirror serves loose files that would otherwise
 * need a second unpacking pass. Files land in a dot-prefixed staging directory
 * that [ModelStore] ignores, so an interrupted transfer never looks like an
 * installed model.
 *
 * State is process-scoped and polled by the settings screen, which is the only
 * consumer: the transfer lives as long as the app process, not as long as the
 * activity.
 */
object ModelDownloader {

    private const val TAG = "FcitxVoiceAsr"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val BUFFER_BYTES = 64 * 1024

    data class Snapshot(
        val modelId: String,
        val done: Long,
        val total: Long,
        val running: Boolean,
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()
    private val states = mutableMapOf<String, Snapshot>()
    private val finished = ArrayList<Snapshot>()

    fun snapshot(modelId: String): Snapshot? = synchronized(lock) { states[modelId] }

    fun isRunning(modelId: String): Boolean = synchronized(lock) { jobs.containsKey(modelId) }

    /**
     * Terminal results the screen has not shown yet. Drained rather than pushed so
     * a finished transfer still reports itself after a rotation.
     */
    fun drainFinished(): List<Snapshot> = synchronized(lock) {
        if (finished.isEmpty()) return emptyList()
        // Terminal entries are dropped once reported: the row is rebuilt from what
        // is on disk, not from the last attempt.
        states.entries.removeAll { !it.value.running }
        finished.toList().also { finished.clear() }
    }

    fun start(context: Context, model: RemoteModel) {
        if (!model.hasMirror) return
        val app = context.applicationContext
        synchronized(lock) {
            if (jobs.containsKey(model.id)) return
            states[model.id] = Snapshot(model.id, 0L, model.mirrorBytes, running = true)
            jobs[model.id] = scope.launch {
                var error: String? = null
                try {
                    download(app, model)
                } catch (e: Throwable) {
                    // A cancelled transfer is the user's own doing; it must not
                    // surface as a failure notification.
                    if (e !is kotlinx.coroutines.CancellationException) {
                        Log.w(TAG, "Mirror download of ${model.id} failed", e)
                        error = e.message ?: "Download failed"
                    }
                } finally {
                    val snapshot = synchronized(lock) {
                        jobs.remove(model.id)
                        Snapshot(
                            modelId = model.id,
                            done = states[model.id]?.done ?: 0L,
                            total = model.mirrorBytes,
                            running = false,
                            error = error,
                        ).also { states[model.id] = it }
                    }
                    synchronized(lock) { finished += snapshot }
                }
            }
        }
    }

    fun cancel(modelId: String) {
        synchronized(lock) { jobs[modelId] }?.cancel()
    }

    private fun publish(modelId: String, done: Long, total: Long) {
        synchronized(lock) {
            states[modelId] = Snapshot(modelId, done, total, running = true)
        }
    }

    private suspend fun download(app: Context, model: RemoteModel) {
        val modelsDir = ModelStore.modelsDir(app).apply { mkdirs() }
        val staging = File(modelsDir, ".incoming-${model.id}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            model.mirrorFiles.forEachIndexed { index, file ->
                currentCoroutineContext().ensureActive()
                val target = File(staging, file.name)
                fetch(ModelCatalog.mirrorUrl(model, file), target, file.bytes) {
                    // Files already written plus what this one has received so far.
                    val previous = model.mirrorFiles.take(index).sumOf { it.bytes }
                    publish(model.id, previous + it, model.mirrorBytes)
                }
            }
            val usable = when (model.kind) {
                ModelKind.STREAMING -> StreamingModel.fromDirectory(staging) != null
                ModelKind.BATCH -> BatchModel.fromDirectory(staging) != null
            }
            if (!usable) throw IOException("Downloaded files do not form a ${model.kind} model")
            val destination = File(modelsDir, model.id)
            destination.deleteRecursively()
            if (!staging.renameTo(destination)) {
                if (!staging.copyRecursively(destination, overwrite = true)) {
                    destination.deleteRecursively()
                    throw IOException("Could not move the model into place")
                }
            }
            if (ModelStore.activeStreaming(app) == null && model.kind == ModelKind.STREAMING) {
                ModelStore.setActiveStreaming(app, model.id)
            }
            if (ModelStore.activeBatch(app) == null && model.kind == ModelKind.BATCH) {
                ModelStore.setActiveBatch(app, model.id)
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun fetch(
        url: String,
        destination: File,
        expectedBytes: Long,
        onProgress: (Long) -> Unit,
    ) {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            // Some PHP/nginx front-ends answer gzip for .onnx otherwise, which would
            // both corrupt the weights and hide the real length.
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for ${destination.name}")
            var written = 0L
            destination.outputStream().use { out ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    onProgress(0L)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        onProgress(written)
                    }
                }
            }
            if (expectedBytes > 0 && written != expectedBytes) {
                throw IOException("${destination.name} is $written bytes, expected $expectedBytes")
            }
        } finally {
            connection.disconnect()
        }
    }
}
