/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

import org.gradle.api.JavaVersion

object Versions {

    val java = JavaVersion.VERSION_17
    const val compileSdk = 36
    const val minSdk = 23
    const val targetSdk = 36

    const val defaultCMake = "3.31.6"
    const val defaultNDK = "28.0.13004108"
    const val defaultBuildTools = "36.1.0"

    // NOTE: increase this value to bump version code. It is a monotonic counter of its
    // own: a published build must never reuse a version code, however the version name
    // moves.
    const val baseVersionCode = 13

    // Release version name is `<upstream release>-<codename>-<fork release counter>`, e.g.
    // `0.1.3-Vega-1`. The build takes the real value from `git describe --tags --long`
    // (ProjectExtensions.buildVersionName), so the release tag decides it and these
    // constants are only the fallback for builds without git metadata.
    //
    // The codename is decorative (a short celestial-body name, ASCII, no digits); the
    // counter is the ordering key and keeps increasing even when the codename changes,
    // because the in-app update check compares numbers only.
    const val upstreamVersionName = "0.1.3"
    const val releaseCodename = "Vega"
    const val forkVersionName = "1"
    const val baseVersionName = "$upstreamVersionName-$releaseCodename-$forkVersionName"

    val supportedABIs = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    const val fallbackABI = "arm64-v8a"

    fun calculateVersionCode(abi: String = fallbackABI): Int {
        val abiId = when (abi) {
            "armeabi-v7a" -> 1
            "arm64-v8a" -> 2
            "x86" -> 3
            "x86_64" -> 4
            else -> 0
        }
        return baseVersionCode * 10 + abiId
    }
}
