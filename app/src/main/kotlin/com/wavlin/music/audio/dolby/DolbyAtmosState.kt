package com.wavlin.music.audio.dolby

import androidx.compose.runtime.Immutable

/**
 * Capability level of the device and current audio routing environment for Dolby Atmos playback.
 */
enum class DolbyAtmosCapability {
    /** Neither hardware nor software E-AC-3 JOC / AC-4 decoders are present. */
    UNSUPPORTED,

    /** Dolby decoders are present, but spatializer is unavailable (e.g. Android < 12L or unsupported route). */
    DECODER_SUPPORTED,

    /** Spatializer API is available, but currently disabled or incompatible with current route. */
    SPATIALIZER_SUPPORTED,

    /** Fully supported: Decoders present, Spatializer active, and current route validated for spatial rendering. */
    FULLY_SUPPORTED,

    /** User has disabled Dolby Atmos playback in settings. */
    DISABLED
}

/**
 * Live playback mode for the currently playing track.
 */
enum class AtmosPlaybackMode {
    /** Playing genuine multichannel/object Atmos stream through spatialized pipeline. */
    ACTIVE_ATMOS,

    /** Track has an Atmos stream, but device/route cannot spatialize, falling back to stereo. */
    STEREO_FALLBACK,

    /** Dolby Atmos is manually disabled by user preference. */
    DISABLED,

    /** Normal stereo track (no Atmos stream representation). */
    NOT_ATMOS
}

/**
 * Realtime diagnostic telemetry model queried directly from Android framework APIs.
 * Zero hardcoding.
 */
@Immutable
data class DolbyAtmosDiagnostics(
    val hasEac3Decoder: Boolean = false,
    val hasEac3JocDecoder: Boolean = false,
    val hasAc4Decoder: Boolean = false,
    val isSpatializerAvailable: Boolean = false,
    val isSpatializerEnabled: Boolean = false,
    val canCurrentRouteSpatialize: Boolean = false,
    val activeOutputDeviceName: String = "Default Output",
    val activeOutputDeviceType: String = "Speaker",
    val capability: DolbyAtmosCapability = DolbyAtmosCapability.UNSUPPORTED,
    val isAtmosPreferred: Boolean = true,
    val currentMode: AtmosPlaybackMode = AtmosPlaybackMode.NOT_ATMOS,
    val activeMimeType: String? = null,
    val channelCount: Int = 2
) {
    val isReadyForAtmos: Boolean
        get() = capability == DolbyAtmosCapability.FULLY_SUPPORTED && isAtmosPreferred

    val displayRouteSummary: String
        get() = if (activeOutputDeviceName.isNotBlank() && activeOutputDeviceName != "Default Output") {
            "$activeOutputDeviceName ($activeOutputDeviceType)"
        } else {
            activeOutputDeviceType
        }
}
