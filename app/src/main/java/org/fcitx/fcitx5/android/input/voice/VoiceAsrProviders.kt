/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.lib.voice_asr.R
import org.fcitx.fcitx5.android.voice.provider.BatchProvider
import org.fcitx.fcitx5.android.voice.provider.StreamingProvider
import timber.log.Timber

/**
 * The built-in offline ASR engines, registered as in-process providers.
 *
 * They are the same engines the `voice-asr` plugin publishes over a bound
 * service, hosted inside the IME process instead. That matters on handsets whose
 * ROM freezes cached background apps: a plugin that has been frozen never
 * finishes a bind, so the IME reports a timeout, while an in-process engine
 * inherits the IME's own exemption.
 *
 * The ids carry the historical `plugin:` prefix so that
 * [VoiceInputProviderManager.isProviderId] keeps classifying them as
 * registry-backed rather than PackageManager-discovered.
 */
object VoiceAsrProviders {

    const val STREAMING_ID = "plugin:voice_asr_streaming"
    const val BATCH_ID = "plugin:voice_asr_batch"

    /**
     * Id prefix of the providers the retired `voice-asr` plugin used to publish.
     * `VoiceInputProviderManager` builds those ids as `plugin:` + the flattened
     * component name, e.g. `plugin:org.fcitx.fcitx5.android.plugin.voice_asr/.VoiceAsrService`.
     */
    private const val LEGACY_PLUGIN_PREFIX = "plugin:org.fcitx.fcitx5.android.plugin.voice_asr"

    /**
     * Registers both engines. Idempotent and cheap: no model is touched and no
     * thread starts until the first session.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        migrateLegacySelection()
        if (VoiceInputProviderRegistry.get(STREAMING_ID) != null) return
        val pkg = appContext.packageName
        VoiceInputProviderRegistry.register(
            VoiceInputProviderRegistry.Entry(
                id = STREAMING_ID,
                label = appContext.getString(R.string.provider_streaming),
                packageName = pkg,
                provider = StreamingProvider(appContext),
            )
        )
        VoiceInputProviderRegistry.register(
            VoiceInputProviderRegistry.Entry(
                id = BATCH_ID,
                label = appContext.getString(R.string.provider_batch),
                packageName = pkg,
                provider = BatchProvider(appContext),
            )
        )
    }

    /**
     * Moves a selection left behind by the plugin onto the built-in engine. The
     * plugin is no longer published, so such a selection points at a provider
     * that is usually not installed any more; the engine behind the old id and
     * the new one is the same code.
     */
    private fun migrateLegacySelection() {
        val pref = AppPrefs.getInstance().keyboard.preferredVoiceInput
        val current = pref.getValue()
        if (!current.startsWith(LEGACY_PLUGIN_PREFIX)) return
        val migrated = if (current.contains("Batch")) BATCH_ID else STREAMING_ID
        Timber.i("Migrating voice input selection $current -> $migrated")
        pref.setValue(migrated)
    }
}
