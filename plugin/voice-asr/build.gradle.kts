import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.plugin-app-convention")
    id("org.fcitx.fcitx5.android.data-descriptor")
}

// sherpa-onnx ships prebuilt JNI libraries only as a release tarball, and the
// "static-link-onnxruntime" flavour is the one we want: a single self-contained
// libsherpa-onnx-jni.so with no sibling .so to resolve at load time.
val sherpaVersion = "1.13.8"
val sherpaArchiveSha256 = "7583ca385ae7d981e65468455c2ea2c9f2da383921dccfc5658d0dc19d309e6f"
val sherpaArchiveName = "sherpa-onnx-v$sherpaVersion-android-static-link-onnxruntime.tar.bz2"
val sherpaArchiveUrl = providers.gradleProperty("sherpaOnnxUrl").getOrElse(
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/$sherpaArchiveName"
)
val sherpaDistDir = layout.buildDirectory.dir("sherpa-onnx")

/**
 * Downloads the pinned sherpa-onnx tarball into build/, verifies its sha256 and
 * extracts the jniLibs tree. The URL can be pointed at a mirror with
 * -PsherpaOnnxUrl=... while the checksum keeps the pin meaningful.
 */
val fetchSherpaOnnx = tasks.register("fetchSherpaOnnx") {
    description = "Download and verify the prebuilt sherpa-onnx JNI libraries"
    group = "build"
    // Bumping the pin (or pointing at a mirror) must invalidate the extract.
    inputs.property("version", sherpaVersion)
    inputs.property("sha256", sherpaArchiveSha256)
    inputs.property("url", sherpaArchiveUrl)
    outputs.dir(sherpaDistDir)
    doLast {
        val downloadDir = layout.buildDirectory.dir("downloads").get().asFile
        downloadDir.mkdirs()
        val archive = downloadDir.resolve(sherpaArchiveName)

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        if (!archive.exists() || sha256(archive) != sherpaArchiveSha256) {
            logger.lifecycle("Downloading $sherpaArchiveUrl")
            val tmp = downloadDir.resolve("$sherpaArchiveName.part")
            tmp.delete()
            val connection = URI(sherpaArchiveUrl).toURL().openConnection()
                .let { it as java.net.HttpURLConnection }
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("User-Agent", "fcitx5-android-voice-asr-build")
            try {
                connection.inputStream.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                connection.disconnect()
            }
            val actual = sha256(tmp)
            if (actual != sherpaArchiveSha256) {
                tmp.delete()
                throw GradleException(
                    "sha256 mismatch for $sherpaArchiveUrl: expected $sherpaArchiveSha256, got $actual"
                )
            }
            tmp.renameTo(archive)
        }

        // Extract beside the output and swap it in, so a failed run leaves the
        // previous tree (and Gradle's snapshot of it) intact.
        val dist = sherpaDistDir.get().asFile
        val staging = File(dist.parentFile, "${dist.name}.tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        providers.exec {
            commandLine("tar", "xjf", archive.absolutePath, "-C", staging.absolutePath)
        }.result.get()
        require(staging.resolve("jniLibs").exists()) { "jniLibs not found in $sherpaArchiveName" }
        dist.deleteRecursively()
        if (!staging.renameTo(dist)) staging.copyRecursively(dist, overwrite = true)
        staging.deleteRecursively()
    }
}

android {
    namespace = "org.fcitx.fcitx5.android.plugin.voice_asr"

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android.plugin.voice_asr"
    }

    buildFeatures {
        resValues = true
    }

    sourceSets.getByName("main") {
        jniLibs.directories.add(sherpaDistDir.get().asFile.absolutePath)
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

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(fetchSherpaOnnx) }

dependencies {
    implementation(project(":lib:plugin-base"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.splitties.views.dsl)
    // bzip2 + tar, for the .tar.bz2 model archives sherpa-onnx publishes
    implementation("org.apache.commons:commons-compress:1.28.0")
}
