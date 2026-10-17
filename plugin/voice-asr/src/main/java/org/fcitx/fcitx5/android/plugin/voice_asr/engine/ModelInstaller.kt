/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.voice_asr.engine

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Gets model archives onto the device (system download manager or a SAF pick),
 * unpacks them next to the installed models and reports the result through a
 * package-scoped broadcast.
 *
 * Archives are unpacked with the model directory stripped of its single
 * top-level folder, so a directory named after the release asset becomes the
 * model id used everywhere else. Unpacking runs on a background thread and the
 * archive is consumed either way: it either becomes a model or reports why it
 * could not.
 */
object ModelInstaller {

    const val ACTION_MODEL_STATE_CHANGED =
        "org.fcitx.fcitx5.android.plugin.voice_asr.MODEL_STATE_CHANGED"
    const val EXTRA_ERROR = "error"

    private const val TAG = "FcitxVoiceAsr"
    private const val PREFS = "voice_asr_downloads"
    private const val DOWNLOAD_DIR = "downloads"

    /** DownloadManager.STATUS_PAUSED, which only exists from API 24. */
    private const val STATUS_PAUSED = 4

    private val ARCHIVE_EXTENSIONS =
        listOf(".tar.bz2", ".tbz2", ".tar.gz", ".tgz", ".zip", ".tar")

    /** Finished downloads that still need unpacking. */
    fun pendingArchives(context: Context): List<File> =
        downloadDir(context).listFiles().orEmpty()
            .filter { it.isFile && it.length() > 0 && extensionOf(it.name) != null }
            .sortedBy { it.name }

    fun downloadDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, DOWNLOAD_DIR).apply { mkdirs() }

    fun archiveName(model: RemoteModel): String =
        model.id + (extensionOf(model.url) ?: ".tar.bz2")

    /**
     * Hands the transfer to the system download manager so it survives the
     * settings screen (and a flaky network drops no progress).
     */
    fun enqueueDownload(context: Context, model: RemoteModel): Long {
        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle(model.label)
            .setDescription(
                context.getString(
                    org.fcitx.fcitx5.android.plugin.voice_asr.R.string.download_notification_desc
                )
            )
            .setDestinationInExternalFilesDir(context, null, "$DOWNLOAD_DIR/${archiveName(model)}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val id = context.getSystemService(DownloadManager::class.java).enqueue(request)
        downloadPrefs(context).edit().putString(id.toString(), model.id).apply()
        return id
    }

    /** DownloadManager status for [downloadId], or -1 when the system does not know it. */
    fun downloadStatus(context: Context, downloadId: Long): Int = runCatching {
        context.getSystemService(DownloadManager::class.java)
            .query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                if (!cursor.moveToFirst()) -1
                else cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                )
            }
    }.getOrDefault(-1)

    fun isRunning(status: Int): Boolean =
        status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING ||
            status == STATUS_PAUSED

    /**
     * modelId -> downloadId for transfers that are still running.
     *
     * Rows are reconciled against the download manager on every read: a transfer
     * that was deleted from the system UI or whose process died would otherwise
     * stay "in flight" forever, hiding the download button.
     */
    fun pendingDownloads(context: Context): Map<String, Long> {
        val prefs = downloadPrefs(context)
        val stored = prefs.all.mapNotNull { (key, value) ->
            val downloadId = key.toLongOrNull() ?: return@mapNotNull null
            (value as? String)?.let { it to downloadId }
        }
        if (stored.isEmpty()) return emptyMap()
        val live = mutableMapOf<String, Long>()
        val stale = mutableListOf<String>()
        stored.forEach { (modelId, downloadId) ->
            if (isRunning(downloadStatus(context, downloadId))) live[modelId] = downloadId
            else stale += downloadId.toString()
        }
        if (stale.isNotEmpty()) {
            prefs.edit().apply { stale.forEach { remove(it) } }.apply()
        }
        return live
    }

    fun forgetDownload(context: Context, downloadId: Long) {
        downloadPrefs(context).edit().remove(downloadId.toString()).apply()
    }

    /**
     * Unpacks [archive] into the models directory. Returns the installed model id
     * on success, or null when the archive does not hold a usable model.
     */
    fun installArchive(context: Context, archive: File): String? {
        val destRoot = ModelStore.modelsDir(context).apply { mkdirs() }
        val staging = File(destRoot, ".incoming-${System.currentTimeMillis()}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            unpack(archive, staging)
            val top = staging.listFiles().orEmpty().filterNot { it.name.startsWith(".") }
            val source = if (top.size == 1 && top[0].isDirectory) top[0] else staging
            val name = if (source === staging) {
                archive.name.substringBefore(".tar").substringBefore(".zip")
            } else {
                source.name
            }
            // Validate before touching anything already installed under that name.
            if (!isUsable(source)) {
                Log.w(TAG, "${archive.name} contains no usable model")
                notifyError(context, "Archive contains no usable model")
                return null
            }
            val target = File(destRoot, name)
            target.deleteRecursively()
            val moved = if (source === staging) {
                staging.renameTo(target) || staging.copyRecursively(target, overwrite = true)
            } else {
                source.renameTo(target) || source.copyRecursively(target, overwrite = true)
            }
            if (!moved) {
                target.deleteRecursively()
                notifyError(context, "Could not move model into place")
                return null
            }
            if (!isUsable(target)) {
                target.deleteRecursively()
                notifyError(context, "Unpacked model is incomplete")
                return null
            }
            return name
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to install ${archive.name}", e)
            notifyError(context, e.message ?: "Extraction failed")
            return null
        } finally {
            staging.deleteRecursively()
            // Neither a corrupt archive nor one without a usable model gets better
            // on a retry, so it is dropped; the error broadcast tells the user why,
            // and the download row offers a fresh attempt.
            archive.delete()
        }
    }

    /** A directory counts as a model when either engine can load it. */
    private fun isUsable(dir: File): Boolean =
        StreamingModel.fromDirectory(dir) != null || BatchModel.fromDirectory(dir) != null

    fun importFromUri(context: Context, uri: Uri, displayName: String?): String? {
        val safeName = (displayName ?: "import.tar.bz2").substringAfterLast('/')
        val temp = File(context.cacheDir, safeName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        return installArchive(context, temp)
    }

    private fun unpack(archive: File, dest: File) {
        val name = archive.name.lowercase()
        val isZip = name.endsWith(".zip")
        val known = isZip || ARCHIVE_EXTENSIONS.any { name.endsWith(it) }
        require(known) { "Unsupported archive: ${archive.name}" }
        BufferedInputStream(archive.inputStream()).use { raw ->
            val decompressed: InputStream = when {
                name.endsWith(".tar.bz2") || name.endsWith(".tbz2") ->
                    BZip2CompressorInputStream(raw, true)
                name.endsWith(".tar.gz") || name.endsWith(".tgz") -> GzipCompressorInputStream(raw)
                else -> raw
            }
            if (isZip) unzip(decompressed, dest) else untar(decompressed, dest)
        }
    }

    private fun untar(input: InputStream, dest: File) {
        TarArchiveInputStream(input).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val target = safeTarget(dest, entry.name) ?: continue
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { tar.copyTo(it) }
                }
            }
        }
    }

    private fun unzip(input: InputStream, dest: File) {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = safeTarget(dest, entry.name) ?: continue
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }

    /** Rejects absolute and parent-relative entries so an archive cannot escape [dest]. */
    private fun safeTarget(dest: File, entryName: String): File? {
        val clean = entryName.replace('\\', '/').trimStart('/')
        if (clean.isEmpty() || clean.split('/').any { it == ".." }) return null
        if (entryName.contains("__MACOSX/")) return null
        return File(dest, clean)
    }

    private fun extensionOf(nameOrUrl: String): String? =
        ARCHIVE_EXTENSIONS.firstOrNull { nameOrUrl.endsWith(it, ignoreCase = true) }

    private fun notifyError(context: Context, message: String) {
        context.sendBroadcast(
            Intent(ACTION_MODEL_STATE_CHANGED)
                .setPackage(context.packageName)
                .putExtra(EXTRA_ERROR, message)
        )
    }

    private fun downloadPrefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
