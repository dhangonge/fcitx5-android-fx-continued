/*
 * Vendored from k2-fsa/sherpa-onnx (sherpa-onnx/kotlin-api/HomophoneReplacerConfig.kt, master), Apache-2.0.
 * Kept byte-for-byte apart from this header so upstream updates stay reviewable.
 */
package com.k2fsa.sherpa.onnx

data class HomophoneReplacerConfig(
    var dictDir: String = "", // unused
    var lexicon: String = "",
    var ruleFsts: String = "",
)
