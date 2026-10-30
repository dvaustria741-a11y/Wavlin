package com.wavlin.music.audio

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.audiofx.AudioEffect
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Switches a vendor effect that is already registered in the system audio effect config
 * (built into the ROM or added by a Magisk module) on and off for the player's audio session.
 * The effect is looked up by UUID; when none of [effectUuids] is registered the state is UNSUPPORTED.
 */
open class SystemEffectController(
    private val tag: String,
    private val prefsName: String,
    private val prefKey: String,
    private val effectUuids: List<UUID>,
    private val settingsPackage: String,
) {
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
        val sp = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs = sp
        _isEnabled.value = sp.getBoolean(prefKey, false)
        refreshAvailability()
    }

    fun refreshAvailability() {
        _availability.value =
            when {
                findRegisteredUuid() == null -> SystemEffectAvailability.UNSUPPORTED
                _isEnabled.value && effect == null && sessionId > 0 -> SystemEffectAvailability.ATTACH_FAILED
                else -> SystemEffectAvailability.AVAILABLE
            }
    }

    @Synchronized
    fun setEnabled(on: Boolean) {
        _isEnabled.value = on
        prefs?.edit()?.putBoolean(prefKey, on)?.apply()
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
                .getLaunchIntentForPackage(settingsPackage)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (e: Exception) {
            null
        }

    private fun findRegisteredUuid(): UUID? {
        val registered = SystemAudioEffects.registeredUuids()
        return effectUuids.firstOrNull { it in registered }
    }

    private fun createEffect() {
        val uuid = findRegisteredUuid() ?: return
        try {
            val created = SystemAudioEffects.create(uuid, sessionId)
            if (created.setEnabled(true) != AudioEffect.SUCCESS) {
                created.release()
                Log.w(tag, "Effect refused to enable on session $sessionId")
                return
            }
            effect = created
            _isActive.value = true
            Log.i(tag, "Effect attached to session $sessionId")
        } catch (e: Exception) {
            Log.w(tag, "Effect unavailable for session $sessionId", e)
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
