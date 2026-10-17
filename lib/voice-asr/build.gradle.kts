import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    id("org.fcitx.fcitx5.android.lib-convention")
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

val sherpaJniLibsDir = layout.buildDirectory.dir("sherpa-onnx/jniLibs")

/**
 * Downloads the pinned sherpa-onnx tarball, verifies its sha256 and unpacks the
 * jniLibs tree into [sherpaJniLibsDir], registered below as a static jniLibs
 * source directory of every variant.
 *
 * The archive is cached in build/downloads (nothing watches it) so a rebuild does
 * not hit the network, and the URL can be pointed at a mirror with
 * -PsherpaOnnxUrl=... while the checksum keeps the pin honest.
 */
val fetchSherpaOnnx = tasks.register("fetchSherpaOnnx") {
    description = "Download and verify the prebuilt sherpa-onnx JNI libraries"
    group = "build"
    // Bumping the pin (or pointing at a mirror) must invalidate the extract.
    inputs.property("version", sherpaVersion)
    inputs.property("sha256", sherpaArchiveSha256)
    inputs.property("url", sherpaArchiveUrl)
    outputs.dir(sherpaJniLibsDir)
    doLast {
        val dir = layout.buildDirectory.dir("downloads").get().asFile
        dir.mkdirs()
        val archive = dir.resolve(sherpaArchiveName)

        fun sha256Of(file: File): String {
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

        if (!archive.exists() || sha256Of(archive) != sherpaArchiveSha256) {
            logger.lifecycle("Downloading $sherpaArchiveUrl")
            val tmp = dir.resolve("$sherpaArchiveName.part")
            tmp.delete()
            val connection = URI(sherpaArchiveUrl).toURL().openConnection()
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("User-Agent", "fcitx5-android-voice-asr-build")
            connection.getInputStream().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            val actual = sha256Of(tmp)
            if (actual != sherpaArchiveSha256) {
                tmp.delete()
                error("sha256 mismatch for $sherpaArchiveUrl: expected $sherpaArchiveSha256, got $actual")
            }
            tmp.renameTo(archive)
        }

        // Unpack beside the output and swap it in, so a failed run leaves the
        // previous tree (and Gradle's snapshot of it) intact.
        val target = sherpaJniLibsDir.get().asFile
        val staging = File(target.parentFile, "${target.name}.tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        providers.exec {
            commandLine("tar", "xjf", archive.absolutePath, "-C", staging.absolutePath)
        }.result.get()
        val extracted = staging.resolve("jniLibs")
        require(extracted.isDirectory) { "jniLibs not found in $sherpaArchiveName" }
        target.deleteRecursively()
        target.parentFile.mkdirs()
        extracted.renameTo(target)
        staging.deleteRecursively()
    }
}

android {
    namespace = "org.fcitx.fcitx5.android.lib.voice_asr"

    defaultConfig {
        consumerProguardFiles("proguard-rules.pro")
    }
}

androidComponents {
    onVariants { variant ->
        // The directory is filled in by fetchSherpaOnnx before anything reads it.
        variant.sources.jniLibs?.addStaticSourceDirectory(
            sherpaJniLibsDir.get().asFile.absolutePath
        )
    }
}

tasks.matching {
    it.name.contains("JniLib") || it.name.startsWith("merge") ||
        it.name.startsWith("bundle") || it.name.startsWith("extract")
}.configureEach { dependsOn(fetchSherpaOnnx) }

dependencies {
    // The provider interfaces (IVoiceInputProvider) live here, and they are part
    // of this module's public surface.
    api(project(":lib:common"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.splitties.views.dsl)
    // bzip2 + tar, for the .tar.bz2 model archives sherpa-onnx publishes
    implementation("org.apache.commons:commons-compress:1.28.0")
}
