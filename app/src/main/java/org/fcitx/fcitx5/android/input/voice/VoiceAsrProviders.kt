/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import org.fcitx.fcitx5.android.lib.voice_asr.R
import org.fcitx.fcitx5.android.voice.provider.BatchProvider
import org.fcitx.fcitx5.android.voice.provider.StreamingProvider

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
     * Registers both engines. Idempotent and cheap: no model is touched and no
     * thread starts until the first session.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
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
}
