/*
 * Vendored from k2-fsa/sherpa-onnx (sherpa-onnx/kotlin-api/FeatureConfig.kt, master), Apache-2.0.
 * Kept byte-for-byte apart from this header so upstream updates stay reviewable.
 */
package com.k2fsa.sherpa.onnx

data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
    var dither: Float = 0.0f
)

fun getFeatureConfig(sampleRate: Int, featureDim: Int): FeatureConfig {
    return FeatureConfig(sampleRate = sampleRate, featureDim = featureDim)
}
