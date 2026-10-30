/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.voice.engine

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

    /**
     * Drops leading and trailing silence, keeping one frame of context on each
     * side so a soft onset is not clipped into a click. Whole-utterance models
     * read a stretch of room tone as speech and invent a sentence for it, so this
     * runs before every decode.
     *
     * The threshold follows the loudest frame in the recording (at
     * [RELATIVE_THRESHOLD]) but never drops below [FLOOR], so a quietly spoken
     * sentence is not trimmed away entirely.
     */
    fun trimSilence(
        samples: FloatArray,
        sampleRate: Int,
        frameMs: Int = 20,
        relativeThreshold: Float = 0.05f,
        floor: Float = 0.004f,
    ): FloatArray {
        val frame = sampleRate / 1000 * frameMs
        if (frame <= 0 || samples.size <= frame * 3) return samples
        val frames = samples.size / frame
        val levels = FloatArray(frames)
        var peak = 0f
        for (i in 0 until frames) {
            var sum = 0.0
            val base = i * frame
            for (j in 0 until frame) {
                val s = samples[base + j].toDouble()
                sum += s * s
            }
            val level = sqrt(sum / frame).toFloat()
            levels[i] = level
            if (level > peak) peak = level
        }
        val threshold = maxOf(peak * relativeThreshold, floor)
        var start = 0
        while (start < frames && levels[start] < threshold) start++
        if (start == frames) return FloatArray(0)
        var end = frames - 1
        while (end > start && levels[end] < threshold) end--
        val from = maxOf(0, (start - 1) * frame)
        val to = minOf(samples.size, (end + 2) * frame)
        return samples.copyOfRange(from, to)
    }
}
