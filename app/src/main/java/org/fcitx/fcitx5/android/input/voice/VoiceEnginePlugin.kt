/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.voice.engine.EngineLibrary
import timber.log.Timber
import java.io.File

/**
 * Finds the installed voice-engine APK and tells [EngineLibrary] where its library
 * lives.
 *
 * The library is executed in this process, so the package it comes from has to be
 * trusted before anything is loaded: same signature as the app and a matching JNI
 * API level. A third-party package can advertise the plugin action, but neither of
 * those, and the failure stays a plain "engine not installed".
 *
 * Lookup goes through PackageManager rather than [DataManager]'s plugin list, which
 * is empty until the first sync finishes and would otherwise make the engine appear
 * missing on a cold start.
 */
object VoiceEnginePlugin {

    private const val API_RESOURCE = "voice_engine_api"

    /**
     * How long a lookup answer is trusted. Long enough to make the burst of calls
     * around a voice session cheap, short enough that installing the engine takes
     * effect without killing the IME process.
     */
    private const val CACHE_MS = 2_000L

    @Volatile
    private var cachedDir: String? = null

    @Volatile
    private var cachedAt = 0L

    fun installResolver(context: Context) {
        val appContext = context.applicationContext
        EngineLibrary.installDirectoryResolver { resolve(appContext) }
    }

    /**
     * Native library directory of a trusted engine package, or null when none is
     * installed. The query is a handful of PackageManager calls, cached for [CACHE_MS]
     * because the IME asks once per session and again from the settings screen.
     */
    private fun resolve(context: Context): String? {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedAt < CACHE_MS) return cachedDir
        val resolved = query(context)
        cachedDir = resolved
        cachedAt = now
        return resolved
    }

    private fun query(context: Context): String? {
        val pm = context.packageManager
        // A debug plugin build advertises the debug action, and it is also signed with
        // the debug key, so the release action would never match on a debug host.
        val actions = listOf(
            BuildConfig.ORIGINAL_PLUGIN_MANIFEST_ACTION,
            BuildConfig.ORIGINAL_DEBUG_PLUGIN_MANIFEST_ACTION
        ).distinct()
        val candidates = actions.flatMap { action ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(
                        Intent(action),
                        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(Intent(action), PackageManager.MATCH_ALL)
                }.map { it.activityInfo.packageName }
            } catch (e: Exception) {
                Timber.w(e, "Failed to query voice engine packages for $action")
                emptyList()
            }
        }.distinct()

        for (packageName in candidates) {
            if (!DataManager.hasSameSignature(packageName)) {
                Timber.w("Ignoring voice engine candidate $packageName: signature mismatch")
                continue
            }
            val api = apiLevel(pm, packageName)
            if (api != EngineLibrary.API) {
                Timber.w("Ignoring voice engine $packageName: API $api, expected ${EngineLibrary.API}")
                continue
            }
            val dir = try {
                pm.getPackageInfo(packageName, 0).applicationInfo?.nativeLibraryDir
            } catch (e: Exception) {
                Timber.w(e, "Failed to read $packageName")
                null
            } ?: continue
            if (File(dir, EngineLibrary.LIBRARY_FILE).isFile) return dir
            Timber.w("Ignoring voice engine $packageName: no ${EngineLibrary.LIBRARY_FILE} in $dir")
        }
        return null
    }

    private fun apiLevel(pm: PackageManager, packageName: String): Int = try {
        val resources = pm.getResourcesForApplication(packageName)
        @Suppress("DiscouragedApi")
        val id = resources.getIdentifier(API_RESOURCE, "integer", packageName)
        if (id == 0) 0 else resources.getInteger(id)
    } catch (e: Exception) {
        Timber.w(e, "Failed to read the voice engine API of $packageName")
        0
    }
}
