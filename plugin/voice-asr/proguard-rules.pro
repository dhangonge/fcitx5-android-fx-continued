# Keep the AIDL service classes and their Binder stubs; they are instantiated via
# Class.forName by the framework.
-keep class org.fcitx.fcitx5.android.plugin.voice_asr.VoiceAsrService { *; }
-keep class org.fcitx.fcitx5.android.plugin.voice_asr.VoiceAsrBatchService { *; }

# The sherpa-onnx keeps now come from :lib:voice-asr's consumer rules.
