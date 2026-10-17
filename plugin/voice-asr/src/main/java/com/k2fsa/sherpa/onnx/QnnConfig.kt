/*
 * Vendored from k2-fsa/sherpa-onnx (sherpa-onnx/kotlin-api/QnnConfig.kt, master), Apache-2.0.
 * Kept byte-for-byte apart from this header so upstream updates stay reviewable.
 */
package com.k2fsa.sherpa.onnx

data class QnnConfig(
    var backendLib: String = "",
    var contextBinary: String = "",
    var systemLib: String = "",
)
