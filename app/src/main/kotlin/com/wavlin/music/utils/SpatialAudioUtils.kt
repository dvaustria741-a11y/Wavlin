/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.utils

import android.content.Context
import android.media.AudioManager
import android.media.Spatializer
import android.media.audiofx.AudioEffect
import android.os.Build
import androidx.core.content.getSystemService
import timber.log.Timber

/**
 * Detection helpers for immersive/spatial audio support ("Dolby Atmos", "Spatial Audio",
 * "360 Reality Audio" etc., depending on OEM branding).
 *
 * There isn't a single public "is Dolby Atmos supported" API third-party apps can call — Dolby's
 * own SDK is licensed per-OEM. What Wavlin can honestly check, with public Android APIs, are two
 * independent things:
 *
 * 1. Android's own [Spatializer] (API 32+): reports whether the platform can virtualize audio
 *    into an immersive/object-based mix for the *currently connected* output device. This is
 *    what backs "Spatial Audio" on stock Android and Pixel devices, and on some OEM skins this
 *    same platform feature is what gets marketed as "Dolby Atmos for headphones".
 * 2. A vendor Dolby (or other) audio-effect being present on the device at all, via
 *    [AudioEffect.queryEffects]. Many OEM phones (Xiaomi/HyperOS, realme, OnePlus, etc.) ship a
 *    system Dolby effect that automatically attaches itself to any app's audio session once that
 *    app broadcasts [AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION] — which
 *    MusicService already does for every playback session. Detecting the effect here is purely
 *    to tell the user whether their phone has one, not to control it directly (there's no public
 *    API to toggle a vendor effect's on/off state from another app).
 */
object SpatialAudioUtils {

    enum class ImmersiveLevel {
        /** No spatial/immersive audio capability for the current output route. */
        NONE,

        /** Multichannel content can be spatialized, but not individual audio objects. */
        MULTICHANNEL,

        /** Full object-based spatial audio — the closest public-API equivalent to Atmos. */
        OBJECT_AUDIO,

        /** Device is below API 32, so the platform Spatializer API doesn't exist at all. */
        UNSUPPORTED_API_LEVEL,
    }

    /**
     * The platform's current immersive-audio capability for whatever output is connected right
     * now (wired, Bluetooth, or built-in speaker all report differently). Requires headphones
     * or a compatible output to report anything above [ImmersiveLevel.NONE] on most devices.
     */
    fun getImmersiveLevel(context: Context): ImmersiveLevel {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return ImmersiveLevel.UNSUPPORTED_API_LEVEL
        }
        return try {
            val audioManager = context.getSystemService<AudioManager>() ?: return ImmersiveLevel.NONE
            when (audioManager.spatializer.immersiveAudioLevel) {
                Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL -> ImmersiveLevel.MULTICHANNEL
                Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_OBJECT_AUDIO -> ImmersiveLevel.OBJECT_AUDIO
                else -> ImmersiveLevel.NONE
            }
        } catch (e: Exception) {
            Timber.tag("SpatialAudioUtils").e(e, "Failed to query Spatializer")
            ImmersiveLevel.NONE
        }
    }

    /**
     * Whether the platform spatializer is available and enabled right now for the active output
     * route. False on most devices unless compatible headphones are connected.
     */
    fun isSpatializerActiveNow(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            val audioManager = context.getSystemService<AudioManager>() ?: return false
            val spatializer = audioManager.spatializer
            spatializer.isAvailable && spatializer.isEnabled
        } catch (e: Exception) {
            Timber.tag("SpatialAudioUtils").e(e, "Failed to query Spatializer availability")
            false
        }
    }

    /**
     * True if the device advertises a vendor audio effect whose name/type mentions Dolby (or a
     * similar branded spatial-audio processor). Purely informational — Wavlin can't control a
     * vendor effect directly, only broadcast the session-open intent it already sends and let
     * the OEM's own effect attach itself.
     */
    fun hasVendorSpatialEffect(): Boolean =
        try {
            AudioEffect.queryEffects()?.any { descriptor ->
                val name = descriptor.name?.lowercase().orEmpty()
                val implementor = descriptor.implementor?.lowercase().orEmpty()
                DOLBY_MARKERS.any { marker -> marker in name || marker in implementor }
            } ?: false
        } catch (e: Exception) {
            Timber.tag("SpatialAudioUtils").e(e, "Failed to query audio effects")
            false
        }

    /** True if there's any reason at all to show spatial-audio UI to this user. */
    fun isSupportedOnThisDevice(context: Context): Boolean {
        val level = getImmersiveLevel(context)
        val hasPlatformSupport = level != ImmersiveLevel.NONE && level != ImmersiveLevel.UNSUPPORTED_API_LEVEL
        return hasPlatformSupport || hasVendorSpatialEffect()
    }

    private val DOLBY_MARKERS = listOf("dolby", "atmos", "dap")
}
