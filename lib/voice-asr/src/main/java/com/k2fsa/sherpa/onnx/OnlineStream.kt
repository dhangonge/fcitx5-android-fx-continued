/*
 * Vendored from k2-fsa/sherpa-onnx (sherpa-onnx/kotlin-api/OnlineStream.kt, master), Apache-2.0.
 * Kept close to upstream apart from this header and the library load in the companion
 * object, so upstream updates stay reviewable.
 */
package com.k2fsa.sherpa.onnx

import org.fcitx.fcitx5.android.voice.engine.EngineLibrary

class OnlineStream(var ptr: Long = 0) {
    init {
        require(ptr != 0L) { "Failed to create native OnlineStream" }
    }

    fun acceptWaveform(samples: FloatArray, sampleRate: Int) =
        acceptWaveform(ptr, samples, sampleRate)

    fun inputFinished() = inputFinished(ptr)

    fun setOption(key: String, value: String) = setOption(ptr, key, value)

    fun getOption(key: String): String = getOption(ptr, key)

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun use(block: (OnlineStream) -> Unit) {
        try {
            block(this)
        } finally {
            release()
        }
    }

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun inputFinished(ptr: Long)
    private external fun setOption(ptr: Long, key: String, value: String)
    private external fun getOption(ptr: Long, key: String): String
    private external fun delete(ptr: Long)


    companion object {
        init {
            // Patched: the library ships in the voice-engine plugin APK, which the
            // host loads by absolute path; see EngineLibrary.
            EngineLibrary.load()
        }
    }
}
