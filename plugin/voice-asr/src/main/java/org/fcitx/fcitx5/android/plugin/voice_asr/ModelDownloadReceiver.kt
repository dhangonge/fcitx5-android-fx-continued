/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.voice_asr

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.fcitx.fcitx5.android.plugin.voice_asr.engine.ModelInstaller
import java.io.File

/**
 * Unpacks a finished model download. Registered in the manifest so the transfer
 * still lands when the settings screen is gone.
 */
class ModelDownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId < 0) return
        val modelId = ModelInstaller.pendingModelId(context, downloadId) ?: return

        val pending = goAsync()
        Thread {
            var error: String? = null
            try {
                val manager = context.getSystemService(DownloadManager::class.java)
                manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                    when {
                        !cursor.moveToFirst() -> error = "download not found"
                        cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                        ) != DownloadManager.STATUS_SUCCESSFUL -> error = "download failed"
                    }
                }
                if (error == null) {
                    val archive = File(ModelInstaller.downloadDir(context), "$modelId.tar.bz2")
                    if (ModelInstaller.installArchive(context, archive) == null) {
                        error = "archive contains no usable model"
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to install $modelId", t)
                error = t.message ?: t.javaClass.simpleName
            } finally {
                ModelInstaller.forgetDownload(context, downloadId)
                context.sendBroadcast(
                    Intent(ModelInstaller.ACTION_MODEL_STATE_CHANGED)
                        .setPackage(context.packageName)
                        .putExtra(ModelInstaller.EXTRA_MODEL_ID, modelId)
                        .putExtra(ModelInstaller.EXTRA_ERROR, error)
                )
                pending.finish()
            }
        }.start()
    }

    private companion object {
        const val TAG = "FcitxVoiceAsr"
    }
}
