/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.voice.engine

import android.content.Context
import org.fcitx.fcitx5.android.lib.voice_asr.BuildConfig
import java.io.File

/**
 * Loads sherpa-onnx's JNI library, which normally ships in a separate, optional APK.
 *
 * The library is loaded from the engine package's own lib directory rather than
 * from the app, so the engine still runs inside the IME process: a second process
 * gets frozen by aggressive ROMs and then never answers. The trust policy (same
 * signature, matching JNI API) belongs to the host, which installs
 * [installDirectoryResolver]; this object only resolves it once and loads from it.
 *
 * [isAvailable] is what lets the providers and the settings screen say "engine
 * not installed" instead of dying of an unsatisfied link at the first utterance.
 */
object EngineLibrary {

    /**
     * Bumped whenever the Kotlin side needs symbols the shipped library does not
     * have. The engine APK publishes it as `@integer/voice_engine_api` and the host
     * refuses any package whose value differs; both sides read the number from
     * `gradle.properties` (`voiceEngineApi`).
     */
    val API = BuildConfig.VOICE_ENGINE_API

    const val LIBRARY_FILE = "libsherpa-onnx-jni.so"
    private const val LIBRARY_NAME = "sherpa-onnx-jni"

    @Volatile
    private var directoryResolver: (() -> String?)? = null

    private var loaded = false

    /** Installed by the app; returns the verified engine package's lib directory. */
    fun installDirectoryResolver(resolver: () -> String?) {
        directoryResolver = resolver
    }

    /** The verified engine package's native library directory, if one is installed. */
    fun nativeLibraryDir(): String? = directoryResolver?.invoke()

    /**
     * Whether the engine can run at all: a verified engine package is installed, or
     * a development build bundled the library into the app itself.
     */
    fun isAvailable(context: Context): Boolean {
        nativeLibraryDir()?.let { return File(it, LIBRARY_FILE).isFile }
        return File(context.applicationInfo.nativeLibraryDir, LIBRARY_FILE).isFile
    }

    /**
     * Loads the library once. Called from the JNI wrappers' initializers, so an
     * engine that is not installed surfaces as [UnsatisfiedLinkError] there;
     * [isAvailable] is what keeps the UI from getting that far.
     */
    @Synchronized
    fun load() {
        if (loaded) return
        val dir = nativeLibraryDir()
        if (dir != null) {
            System.load(File(dir, LIBRARY_FILE).absolutePath)
        } else {
            System.loadLibrary(LIBRARY_NAME)
        }
        loaded = true
    }
}
