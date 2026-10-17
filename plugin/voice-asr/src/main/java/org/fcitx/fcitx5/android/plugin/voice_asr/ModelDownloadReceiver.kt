/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.voice_asr

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fcitx.fcitx5.android.plugin.voice_asr.engine.ModelInstaller

/**
 * Announces that a model download finished. Unpacking deliberately happens in
 * the settings screen instead of here: broadcast receivers are killed after a
 * few seconds and a 100 MB bzip2 archive takes longer than that to extract.
 */
class ModelDownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId < 0) return
        val modelId = ModelInstaller.pendingModelId(context, downloadId) ?: return
        ModelInstaller.forgetDownload(context, downloadId)
        context.sendBroadcast(
            Intent(ModelInstaller.ACTION_MODEL_STATE_CHANGED)
                .setPackage(context.packageName)
                .putExtra(ModelInstaller.EXTRA_MODEL_ID, modelId)
        )
    }
}
