/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.voice_asr.engine

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
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
 * model id used everywhere else.
 */
object ModelInstaller {

    const val ACTION_MODEL_STATE_CHANGED =
        "org.fcitx.fcitx5.android.plugin.voice_asr.MODEL_STATE_CHANGED"
    const val EXTRA_MODEL_ID = "modelId"
    const val EXTRA_ERROR = "error"

    private const val PREFS = "voice_asr_downloads"
    private const val DOWNLOAD_DIR = "downloads"

    /** Finished downloads that still need unpacking. */
    fun pendingArchives(context: Context): List<File> =
        downloadDir(context).listFiles().orEmpty()
            .filter { it.isFile && isArchive(it.name) }
            .sortedBy { it.name }

    private fun isArchive(name: String): Boolean =
        name.endsWith(".tar.bz2", true) || name.endsWith(".tbz2", true) ||
            name.endsWith(".tar.gz", true) || name.endsWith(".tgz", true) ||
            name.endsWith(".zip", true) || name.endsWith(".tar", true)

    fun downloadDir(context: Context): File =
        File(context.getExternalFilesDir(null), DOWNLOAD_DIR).apply { mkdirs() }

    fun archiveName(model: RemoteModel): String = "${model.id}.tar.bz2"

    /**
     * Hands the transfer to the system download manager so it survives the
     * settings screen (and a flaky network drops no progress).
     */
    fun enqueueDownload(context: Context, model: RemoteModel): Long {
        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle(model.label)
            .setDescription(context.getString(org.fcitx.fcitx5.android.plugin.voice_asr.R.string.download_notification_desc))
            .setDestinationInExternalFilesDir(context, null, "$DOWNLOAD_DIR/${archiveName(model)}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val id = context.getSystemService(DownloadManager::class.java).enqueue(request)
        downloadPrefs(context).edit().putString(id.toString(), model.id).apply()
        return id
    }

    fun pendingModelId(context: Context, downloadId: Long): String? =
        downloadPrefs(context).getString(downloadId.toString(), null)

    /** modelId -> downloadId for transfers that are still running. */
    fun pendingDownloads(context: Context): Map<String, Long> =
        downloadPrefs(context).all.mapNotNull { (key, value) ->
            val downloadId = key.toLongOrNull() ?: return@mapNotNull null
            (value as? String)?.let { it to downloadId }
        }.toMap()

    fun forgetDownload(context: Context, downloadId: Long) {
        downloadPrefs(context).edit().remove(downloadId.toString()).apply()
    }

    /**
     * Unpacks [archive] into the models directory. Returns the installed model id
     * on success, or null when the archive does not contain a usable model.
     */
    fun installArchive(context: Context, archive: File): String? {
        val name = archive.name
        val destRoot = ModelStore.modelsDir(context).apply { mkdirs() }
        val staging = File(destRoot, ".incoming-${System.currentTimeMillis()}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val open: (InputStream) -> InputStream = { input ->
                when {
                    name.endsWith(".tar.bz2", true) || name.endsWith(".tbz2", true) ->
                        BZip2CompressorInputStream(input, true)
                    else -> input
                }
            }
            archive.inputStream().use { raw ->
                open(raw).use { decompressed ->
                    if (name.endsWith(".zip", true)) {
                        unzip(decompressed, staging)
                    } else {
                        untar(decompressed, staging)
                    }
                }
            }
            // A single top-level folder is the model itself; flatten it.
            val top = staging.listFiles().orEmpty()
            val modelDir = if (top.size == 1 && top[0].isDirectory) {
                val target = File(destRoot, top[0].name)
                target.deleteRecursively()
                if (!top[0].renameTo(target)) {
                    top[0].copyRecursively(target, overwrite = true)
                }
                target
            } else {
                val target = File(destRoot, name.substringBefore(".tar").substringBefore(".zip"))
                target.deleteRecursively()
                if (!staging.renameTo(target)) staging.copyRecursively(target, overwrite = true)
                target
            }
            val model = StreamingModel.fromDirectory(modelDir)
            if (model == null) {
                modelDir.deleteRecursively()
                return null
            }
            return model.id
        } finally {
            staging.deleteRecursively()
            archive.delete()
        }
    }

    fun importFromUri(context: Context, uri: Uri, displayName: String?): String? {
        val safeName = (displayName ?: "import.tar.bz2").substringAfterLast('/')
        val temp = File(context.cacheDir, safeName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        return installArchive(context, temp)
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
        if (entryName.endsWith("/__MACOSX/") || entryName.contains("__MACOSX/")) return null
        return File(dest, clean)
    }

    private fun downloadPrefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
