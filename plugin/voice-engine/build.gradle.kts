plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.plugin-app-convention")
    id("org.fcitx.fcitx5.android.native-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
}

android {
    namespace = "org.fcitx.fcitx5.android.plugin.voice_engine"

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android.plugin.voice_engine"

        // The JNI contract the app checks before it loads anything from this package.
        // Read from gradle.properties so the two sides can never disagree.
        resValue("integer", "voice_engine_api", providers.gradleProperty("voiceEngineApi").get())
    }

    buildFeatures {
        resValues = true
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

    packaging {
        jniLibs {
            excludes += setOf(
                "**/libc++_shared.so",
                "**/libFcitx5*"
            )
        }
    }
}

// The archive pin, its checksum and the unpack step live in :lib:voice-asr, next to
// the Kotlin wrappers that have to match the library's JNI symbols. This module only
// packages that tree, so the app no longer carries ~9 MB of engine it may never use.
val sherpaJniLibs = project(":lib:voice-asr").layout.buildDirectory
    .dir("sherpa-onnx/jniLibs")

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            sherpaJniLibs.get().asFile.absolutePath
        )
    }
}

tasks.matching {
    it.name.contains("JniLib") || it.name.startsWith("merge") ||
        it.name.startsWith("bundle") || it.name.startsWith("extract")
}.configureEach { dependsOn(":lib:voice-asr:fetchSherpaOnnx") }

dependencies {
    implementation(project(":lib:plugin-base"))
}
