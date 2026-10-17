/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.voice_asr.engine

import kotlin.math.sqrt

/**
 * Audio conversion shared by the streaming and the Whisper provider. The IME
 * sends 16-bit little-endian PCM, while sherpa-onnx wants normalised floats.
 */
object Pcm {

    fun toFloatSamples(pcm: ByteArray, offset: Int, len: Int): FloatArray {
        val count = len / 2
        if (count <= 0) return FloatArray(0)
        val out = FloatArray(count)
        for (i in 0 until count) {
            val index = offset + 2 * i
            val lo = pcm[index].toInt() and 0xFF
            val hi = pcm[index + 1].toInt()
            out[i] = ((hi shl 8) or lo) / 32768f
        }
        return out
    }

    /** RMS in the 0..32767 range the IME expects for its level animation. */
    fun rmsOf(samples: FloatArray): Int {
        if (samples.isEmpty()) return 0
        var sum = 0.0
        for (s in samples) sum += (s * 32767f) * (s * 32767f)
        return sqrt(sum / samples.size).toInt()
    }
}
