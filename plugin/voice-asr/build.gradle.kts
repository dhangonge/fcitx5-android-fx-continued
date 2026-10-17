plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.plugin-app-convention")
}

android {
    namespace = "org.fcitx.fcitx5.android.plugin.voice_asr"

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android.plugin.voice_asr"
    }

    buildTypes {
        release {
            proguardFile("proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":lib:plugin-base"))
}
