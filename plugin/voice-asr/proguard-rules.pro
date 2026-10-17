# Keep the AIDL service class and its Binder stub; they are instantiated via
# Class.forName by the framework.
-keep class org.fcitx.fcitx5.android.plugin.voice_asr.VoiceAsrService { *; }
