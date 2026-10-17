/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.voice_asr.engine

/**
 * Streaming models we offer for in-app download.
 *
 * Only transducer ("zipformer") archives are listed: [StreamingEngine] configures
 * a transducer decoder, so CTC-only releases would load but never decode.
 * Sizes are the archive sizes on the release page, shown before the download
 * starts; the unpacked model is larger.
 */
data class RemoteModel(
    val id: String,
    val label: String,
    val archiveBytes: Long,
    val url: String,
)

object ModelCatalog {

    private const val BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"

    private fun asset(name: String) = BASE + name

    val streaming: List<RemoteModel> = listOf(
        RemoteModel(
            id = "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23",
            label = "中文普通话 · 轻量 14M",
            archiveBytes = 74_004_050L,
            url = asset("sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2"),
        ),
        RemoteModel(
            id = "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
            label = "中文普通话 · 2025 更准",
            archiveBytes = 132_634_597L,
            url = asset("sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2"),
        ),
        RemoteModel(
            id = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17",
            label = "English · 20M",
            archiveBytes = 127_887_156L,
            url = asset("sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2"),
        ),
    )

    fun find(id: String): RemoteModel? = streaming.firstOrNull { it.id == id }
}
