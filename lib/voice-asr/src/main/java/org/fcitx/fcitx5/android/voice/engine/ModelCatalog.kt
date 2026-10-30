/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.voice.engine

/** Which engine a model belongs to; the two are separate IME voice providers. */
enum class ModelKind {
    /** Transducer decoder, emits text while the user is still speaking. */
    STREAMING,

    /** Whole-utterance model, transcribes once the mic is released. */
    BATCH,
}

/** One weight file of a mirrored model, with its size on the mirror. */
data class ModelFile(val name: String, val bytes: Long)

/**
 * Models we offer for in-app download.
 *
 * Streaming entries are limited to transducer ("zipformer") archives: the
 * engine configures a transducer decoder, so CTC-only releases would load but
 * never decode. Batch entries were Whisper until 0.1.3; SenseVoice and
 * Paraformer replaced it because Whisper's weakest language is exactly the one
 * this IME is used in.
 *
 * Every model has two sources. [url] is the release archive on GitHub, one
 * tarball per model; [mirrorRepo] is the same model on Hugging Face, which
 * `hf-mirror.com` serves from inside China without a VPN, and which is
 * downloaded file by file ([mirrorFiles], the int8 weights the engine prefers).
 * The mirror is the default because the GitHub release CDN is what stalls when
 * a connection to it is throttled; the archive stays available for the case
 * where the mirror is unreachable instead.
 */
data class RemoteModel(
    val id: String,
    val label: String,
    val archiveBytes: Long,
    val url: String,
    val kind: ModelKind,
    val mirrorRepo: String = "",
    val mirrorFiles: List<ModelFile> = emptyList(),
) {
    val mirrorBytes: Long get() = mirrorFiles.sumOf { it.bytes }

    val hasMirror: Boolean get() = mirrorRepo.isNotEmpty() && mirrorFiles.isNotEmpty()
}

object ModelCatalog {

    private const val BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"

    private const val MIRROR_BASE = "https://hf-mirror.com/"

    private fun asset(name: String) = BASE + name

    private fun weights(vararg files: ModelFile) = files.toList()

    private fun file(name: String, bytes: Long) = ModelFile(name, bytes)

    val streaming: List<RemoteModel> = listOf(
        RemoteModel(
            id = "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23",
            label = "中文普通话 · 轻量 14M",
            archiveBytes = 74_004_050L,
            url = asset("sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2"),
            kind = ModelKind.STREAMING,
            mirrorRepo = "csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23",
            mirrorFiles = weights(
                file("encoder-epoch-99-avg-1.int8.onnx", 21_621_684L),
                file("decoder-epoch-99-avg-1.int8.onnx", 1_888_682L),
                file("joiner-epoch-99-avg-1.int8.onnx", 1_795_562L),
                file("tokens.txt", 48_697L),
            ),
        ),
        RemoteModel(
            id = "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
            label = "中文普通话 · 2025 更准",
            archiveBytes = 132_634_597L,
            url = asset("sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2"),
            kind = ModelKind.STREAMING,
            mirrorRepo = "csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
            mirrorFiles = weights(
                file("encoder.int8.onnx", 161_141_793L),
                file("decoder.onnx", 5_165_083L),
                file("joiner.int8.onnx", 1_033_416L),
                file("tokens.txt", 20_628L),
            ),
        ),
        RemoteModel(
            id = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17",
            label = "English · 20M",
            archiveBytes = 127_887_156L,
            url = asset("sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2"),
            kind = ModelKind.STREAMING,
            mirrorRepo = "csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17",
            mirrorFiles = weights(
                file("encoder-epoch-99-avg-1.int8.onnx", 42_845_182L),
                file("decoder-epoch-99-avg-1.int8.onnx", 539_499L),
                file("joiner-epoch-99-avg-1.int8.onnx", 259_572L),
                file("tokens.txt", 5_048L),
            ),
        ),
    )

    val batch: List<RemoteModel> = listOf(
        RemoteModel(
            id = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
            label = "中英日韩粤 · SenseVoice（推荐）",
            archiveBytes = 163_002_883L,
            url = asset("sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"),
            kind = ModelKind.BATCH,
            // The mirror repo carries the fp32 weights next to the int8 ones;
            // BatchModel picks model.int8.onnx.
            mirrorRepo = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
            mirrorFiles = weights(
                file("model.int8.onnx", 239_233_841L),
                file("tokens.txt", 315_894L),
            ),
        ),
        RemoteModel(
            id = "sherpa-onnx-paraformer-zh-small-2024-03-09",
            label = "中文普通话 · Paraformer 轻量",
            archiveBytes = 77_920_048L,
            url = asset("sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2"),
            kind = ModelKind.BATCH,
            mirrorRepo = "csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09",
            mirrorFiles = weights(
                file("model.int8.onnx", 81_828_675L),
                file("tokens.txt", 75_352L),
            ),
        ),
    )

    fun all(): List<RemoteModel> = streaming + batch

    fun find(id: String): RemoteModel? = all().firstOrNull { it.id == id }

    fun mirrorUrl(model: RemoteModel, file: ModelFile): String =
        "$MIRROR_BASE${model.mirrorRepo}/resolve/main/${file.name}"
}
