package com.wavlin.music.audio.xiaomi

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.audiofx.AudioEffect
import android.util.Log
import com.wavlin.music.audio.SystemAudioEffects
import com.wavlin.music.audio.SystemEffectAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Xiaomi Sound: routes the player's audio session through the system Mi Sound effect (libmisoundfx.so).
 * The effect is built into Xiaomi/Redmi/POCO ROMs and is registered on other devices by the Mi Sound
 * Magisk module (reiryuki/Mi-Sound-Redmi-K40-Magisk-Module). Devices without it report UNSUPPORTED.
 */
object XiaomiSoundManager {
    private const val TAG = "XiaomiSoundManager"
    private const val PREFS_NAME = "wavlin_xiaomi_sound"
    const val KEY_XIAOMI_SOUND_ENABLED = "xiaomi_sound_enabled"
    const val MISOUND_PACKAGE = "com.miui.misound"

    private val MISOUND_UUID: UUID = UUID.fromString("5b8e36a5-144a-4c38-b1d7-0002a5d5c51b")

    private var prefs: SharedPreferences? = null
    private var effect: AudioEffect? = null
    private var sessionId: Int = 0

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _availability = MutableStateFlow(SystemEffectAvailability.UNKNOWN)
    val availability: StateFlow<SystemEffectAvailability> = _availability.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        _isEnabled.value = sp.getBoolean(KEY_XIAOMI_SOUND_ENABLED, false)
        refreshAvailability()
    }

    fun refreshAvailability() {
        _availability.value =
            when {
                MISOUND_UUID !in SystemAudioEffects.registeredUuids() -> SystemEffectAvailability.UNSUPPORTED
                _isEnabled.value && effect == null && sessionId > 0 -> SystemEffectAvailability.ATTACH_FAILED
                else -> SystemEffectAvailability.AVAILABLE
            }
    }

    @Synchronized
    fun setEnabled(on: Boolean) {
        _isEnabled.value = on
        prefs?.edit()?.putBoolean(KEY_XIAOMI_SOUND_ENABLED, on)?.apply()
        if (on) {
            if (sessionId > 0 && effect == null) createEffect()
        } else {
            releaseEffect()
        }
        refreshAvailability()
    }

    @Synchronized
    fun attachSession(id: Int) {
        if (id <= 0) return
        if (id == sessionId && effect != null) return
        releaseEffect()
        sessionId = id
        if (_isEnabled.value) createEffect()
        refreshAvailability()
    }

    @Synchronized
    fun detachSession() {
        releaseEffect()
        sessionId = 0
    }

    @Synchronized
    fun release() {
        detachSession()
        prefs = null
    }

    fun launchIntent(context: Context): Intent? =
        try {
            context.packageManager
                .getLaunchIntentForPackage(MISOUND_PACKAGE)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (e: Exception) {
            null
        }

    private fun createEffect() {
        if (MISOUND_UUID !in SystemAudioEffects.registeredUuids()) return
        try {
            val created = SystemAudioEffects.create(MISOUND_UUID, sessionId)
            if (created.setEnabled(true) != AudioEffect.SUCCESS) {
                created.release()
                Log.w(TAG, "Mi Sound effect refused to enable on session $sessionId")
                return
            }
            effect = created
            _isActive.value = true
            Log.i(TAG, "Mi Sound attached to session $sessionId")
        } catch (e: Exception) {
            Log.w(TAG, "Mi Sound unavailable for session $sessionId", e)
            effect = null
            _isActive.value = false
        }
    }

    private fun releaseEffect() {
        try {
            effect?.release()
        } catch (_: Exception) {
        }
        effect = null
        _isActive.value = false
    }
}
