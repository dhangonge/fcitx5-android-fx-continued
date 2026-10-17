import java.io.File

plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.plugin-app-convention")
    id("org.fcitx.fcitx5.android.data-descriptor")
}

// The engine, the model manager and the settings activity all live in
// :lib:voice-asr, which the app links as well; they are the same classes, so the
// app can run this plugin's engines in process and skip the bind entirely.
android {
    namespace = "org.fcitx.fcitx5.android.plugin.voice_asr"

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android.plugin.voice_asr"
    }

    buildFeatures {
        resValues = true
    }

    splits.abi {
        isEnable = true
        isUniversalApk = false
        reset()
        val abis = (findProperty("buildABI") ?: System.getenv("ORG_GRADLE_PROJECT_BUILD_ABI"))
            ?.toString()?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
            ?: listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        abis.forEach { include(it) }
    }

    buildTypes {
        release {
            resValue("string", "app_name", "@string/app_name_release")
            proguardFile("proguard-rules.pro")
        }
        debug {
            resValue("string", "app_name", "@string/app_name_debug")
        }
    }
}

dependencies {
    implementation(project(":lib:plugin-base"))
    // Brings the sherpa-onnx JNI libraries (jniLibs of the AAR), the engine, the
    // settings UI and the shared provider classes.
    implementation(project(":lib:voice-asr"))
}
