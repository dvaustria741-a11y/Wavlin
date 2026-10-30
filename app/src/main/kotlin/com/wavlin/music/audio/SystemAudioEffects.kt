package com.wavlin.music.audio

import android.media.audiofx.AudioEffect
import android.util.Log
import java.util.UUID

enum class SystemEffectAvailability {
    UNKNOWN,
    AVAILABLE,
    UNSUPPORTED,
    ATTACH_FAILED,
}

/** Access to vendor effects registered in the system audio effect config (not part of the public SDK surface). */
internal object SystemAudioEffects {
    private const val TAG = "SystemAudioEffects"
    private val EFFECT_TYPE_NULL: UUID = UUID.fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210")

    fun registeredUuids(): Set<UUID> =
        try {
            AudioEffect.queryEffects()?.map { it.uuid }?.toSet().orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Failed querying audio effects", e)
            emptySet()
        }

    /** The public SDK only exposes AudioEffect's no-arg constructor, so the UUID-based one is reached via reflection. */
    fun create(
        uuid: UUID,
        session: Int,
    ): AudioEffect {
        val intType = Int::class.javaPrimitiveType!!
        val ctor =
            AudioEffect::class.java.getDeclaredConstructor(
                UUID::class.java,
                UUID::class.java,
                intType,
                intType,
            )
        ctor.isAccessible = true
        return ctor.newInstance(EFFECT_TYPE_NULL, uuid, 0, session)
    }
}
