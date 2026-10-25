/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Adapted from SpatialFlow (https://github.com/MythicalSHUB/SpatialFlow),
 * Copyright (c) 2026 Shubham Karande, permissively licensed (MIT per its README, Apache-2.0 per its LICENSE file).
 */

package com.wavlin.music.audio.dolby

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/**
 * Extension helpers for Media3 formats related to Dolby Atmos stream detection.
 *
 * These are member-extension functions; call them inside `with(DolbyAtmosExtensions) { }`.
 */
object DolbyAtmosExtensions {

    /**
     * Returns true when the Media3 [Format] represents a genuine Dolby Atmos stream
     * (E-AC-3 JOC, AC-4, or multichannel E-AC-3 with JOC coding in the codec string).
     */
    fun Format?.isDolbyAtmos(): Boolean {
        if (this == null) return false
        val mime = sampleMimeType ?: containerMimeType ?: ""
        return isEac3Joc(mime) ||
            isAc4(mime) ||
            (isEac3(mime) && (channelCount > 2 || codecs?.contains("joc", ignoreCase = true) == true))
    }

    /** E-AC-3 JOC — the primary Dolby Atmos container used by streaming services. */
    fun isEac3Joc(mime: String?): Boolean =
        mime?.equals(DolbyAtmosDetector.MIME_EAC3_JOC, ignoreCase = true) == true

    /** Dolby AC-4 — newer Atmos container (used on some OTT platforms). */
    fun isAc4(mime: String?): Boolean =
        mime?.equals(DolbyAtmosDetector.MIME_AC4, ignoreCase = true) == true ||
            mime?.equals(MimeTypes.AUDIO_AC4, ignoreCase = true) == true

    /** E-AC-3 (Dolby Digital Plus) — base codec used for JOC; also used standalone. */
    fun isEac3(mime: String?): Boolean =
        mime?.equals(DolbyAtmosDetector.MIME_EAC3, ignoreCase = true) == true ||
            mime?.equals(MimeTypes.AUDIO_E_AC3, ignoreCase = true) == true
}

