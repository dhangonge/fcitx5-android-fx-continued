# Consumer rules: the app and the plugin both ship this engine, and both run it
# through JNI, so the keeps have to travel with the module.

# sherpa-onnx's JNI reads config objects through GetFieldID with hard-coded
# names (e.g. "decodingMethod", "featConfig"). Renaming or removing a field
# makes the engine fail at runtime with
# "RuntimeException: Failed to get field ID for ...", so these classes must
# survive R8 untouched. -keep keeps the whole package including nested config
# classes (FeatureConfig, OfflineWhisperModelConfig, ...).
-keep class com.k2fsa.sherpa.onnx.** { *; }

# JNI entry points are bound by name at System.loadLibrary time.
-keepclasseswithmembernames class * {
    native <methods>;
}
