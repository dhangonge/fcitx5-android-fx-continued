/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.voice_asr

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.plugin.voice_asr.engine.ModelCatalog
import org.fcitx.fcitx5.android.plugin.voice_asr.engine.ModelInstaller
import org.fcitx.fcitx5.android.plugin.voice_asr.engine.ModelStore
import org.fcitx.fcitx5.android.plugin.voice_asr.engine.RemoteModel
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.button
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.startMargin
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.core.wrapInScrollView

/**
 * Install, pick and remove offline ASR models. Every row is rebuilt on state
 * changes; the only thing that updates in place is the download progress line.
 */
class VoiceAsrSettingsActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private var ticker: Job? = null

    private val progressViews = mutableMapOf<String, TextView>()
    private var pending: Map<String, Long> = emptyMap()
    private val installing = mutableSetOf<String>()

    private val importModel =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val name = queryDisplayName(uri)
            toast(getString(R.string.downloading, name ?: uri.lastPathSegment ?: ""))
            lifecycleScope.launch {
                val installed = withContext(Dispatchers.IO) {
                    runCatching { ModelInstaller.importFromUri(this@VoiceAsrSettingsActivity, uri, name) }
                        .getOrNull()
                }
                toast(
                    if (installed == null) getString(R.string.import_failed)
                    else getString(R.string.import_succeeded, installed)
                )
                refreshAll()
            }
        }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra(ModelInstaller.EXTRA_ERROR)?.let {
                toast("${getString(R.string.download_failed)}: $it")
            }
            refreshAll()
            installPendingArchives()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        content = verticalLayout {
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        val root = content.wrapInScrollView()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.setPadding(dp(20), dp(20) + bars.top, dp(20), dp(24) + bars.bottom)
            insets
        }
        setContentView(root)
        refreshAll()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(ModelInstaller.ACTION_MODEL_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ticker = lifecycleScope.launch {
            while (isActive) {
                updateProgress()
                delay(500)
            }
        }
        installPendingArchives()
    }

    override fun onStop() {
        ticker?.cancel()
        ticker = null
        runCatching { unregisterReceiver(stateReceiver) }
        super.onStop()
    }

    private fun refreshAll() {
        pending = ModelInstaller.pendingDownloads(this)
        progressViews.clear()
        content.removeAllViews()

        val installed = ModelStore.available(this)
        val active = ModelStore.active(this)

        content.addLabel(
            if (active != null) getString(R.string.active_model, active.id)
            else getString(R.string.active_model_none),
            size = 16f,
            top = 0,
            bottom = 4
        )
        if (installed.isEmpty()) {
            content.addLabel(getString(R.string.no_model_hint), size = 14f, dim = true, bottom = 8)
        }

        content.addHeader(getString(R.string.installed_section))
        if (installed.isEmpty()) {
            content.addLabel("—", size = 14f, dim = true, bottom = 8)
        }
        installed.forEach { model ->
            val row = horizontalLayout {
                add(textView {
                    text = model.id
                    textSize = 14f
                }, lParams(0, wrapContent) { weight = 1f })
                add(button {
                    text = getString(if (active?.id == model.id) R.string.in_use else R.string.use)
                    isEnabled = active?.id != model.id
                    setOnClickListener {
                        ModelStore.setActive(this@VoiceAsrSettingsActivity, model.id)
                        refreshAll()
                    }
                }, lParams(wrapContent, wrapContent) { startMargin = dp(8) })
                add(button {
                    text = getString(R.string.delete)
                    setOnClickListener { confirmDelete(model.id) }
                }, lParams(wrapContent, wrapContent) { startMargin = dp(4) })
            }
            content.addBlock(row, bottom = 8)
        }

        content.addHeader(getString(R.string.download_section))
        ModelCatalog.streaming.forEach { remote ->
            val alreadyInstalled = installed.any { it.id == remote.id }
            val inFlight = pending.containsKey(remote.id)
            val column = verticalLayout {
                add(textView {
                    text = "${remote.label} · ${formatSize(remote.archiveBytes)}"
                    textSize = 14f
                }, lParams(matchParent, wrapContent))
                val progress = textView {
                    textSize = 12f
                    alpha = 0.7f
                    visibility = View.GONE
                }
                progressViews[remote.id] = progress
                add(progress, lParams(matchParent, wrapContent))
                if (!alreadyInstalled && !inFlight) {
                    add(button {
                        text = getString(R.string.download)
                        setOnClickListener { startDownload(remote) }
                    }, lParams(wrapContent, wrapContent) { topMargin = dp(4) })
                }
            }
            content.addBlock(column, bottom = 12)
        }

        content.addBlock(button {
            text = getString(R.string.import_model)
            setOnClickListener { runCatching { importModel.launch(arrayOf("*/*")) } }
        }, top = 4)

        updateProgress()
    }

    private fun startDownload(remote: RemoteModel) {
        runCatching { ModelInstaller.enqueueDownload(this, remote) }
            .onSuccess {
                pending = ModelInstaller.pendingDownloads(this)
                refreshAll()
            }
            .onFailure { toast("${getString(R.string.download_failed)}: ${it.message}") }
    }

    private fun updateProgress() {
        if (pending.isEmpty()) return
        val manager = getSystemService(DownloadManager::class.java)
        pending.forEach { (modelId, downloadId) ->
            val view = progressViews[modelId] ?: return@forEach
            val text = runCatching {
                manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val status =
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val done = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    )
                    val total = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    )
                    when (status) {
                        DownloadManager.STATUS_FAILED -> getString(R.string.download_failed)
                        DownloadManager.STATUS_SUCCESSFUL -> getString(R.string.installing)
                        else -> getString(
                            R.string.downloading,
                            if (total > 0) "${done * 100 / total}%" else formatSize(done)
                        )
                    }
                }
            }.getOrNull()
            if (text != null) {
                view.text = text
                view.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Unpacks archives that the download manager already finished. Runs here (and
     * not in the broadcast receiver) because extraction easily outlives the few
     * seconds a receiver is allowed to live.
     */
    private fun installPendingArchives() {
        val archives = ModelInstaller.pendingArchives(this).filter { it.name !in installing }
        if (archives.isEmpty()) return
        archives.forEach { archive ->
            installing.add(archive.name)
            toast(getString(R.string.installing))
            lifecycleScope.launch {
                val installed = withContext(Dispatchers.IO) {
                    runCatching { ModelInstaller.installArchive(this@VoiceAsrSettingsActivity, archive) }
                        .getOrNull()
                }
                installing.remove(archive.name)
                toast(
                    if (installed == null) getString(R.string.import_failed)
                    else getString(R.string.import_succeeded, installed)
                )
                refreshAll()
            }
        }
    }

    private fun confirmDelete(modelId: String) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm_message, modelId))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                ModelStore.available(this).firstOrNull { it.id == modelId }?.let {
                    ModelStore.delete(this, it)
                }
                refreshAll()
            }
            .show()
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index < 0) null else cursor.getString(index)
        }
    }.getOrNull()

    private fun formatSize(bytes: Long): String =
        getString(R.string.model_size, String.format("%.1f", bytes / 1024.0 / 1024.0))

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun LinearLayout.addHeader(title: String) = addLabel(
        title, size = 16f, top = 12, bottom = 8
    )

    private fun LinearLayout.addLabel(
        label: String,
        size: Float,
        top: Int = 0,
        bottom: Int = 0,
        dim: Boolean = false,
    ) = addBlock(textView {
        text = label
        textSize = size
        if (dim) alpha = 0.7f
    }, top = top, bottom = bottom)

    private fun LinearLayout.addBlock(view: View, top: Int = 0, bottom: Int = 0) {
        add(view, lParams(matchParent, wrapContent) {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        })
    }
}
