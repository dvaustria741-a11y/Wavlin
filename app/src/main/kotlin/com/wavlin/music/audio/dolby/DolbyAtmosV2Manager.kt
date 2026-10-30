package com.wavlin.music.audio.dolby

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.audiofx.AudioEffect
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class DolbyV2Availability {
    UNKNOWN,
    AVAILABLE,
    MODULE_MISSING,
    ATTACH_FAILED,
}

/**
 * Dolby Atmos V2: routes the player's audio session through the system Dolby DAP effect
 * (libswdap.so), which is registered by the Mi Sound / Dolby Atmos Magisk module
 * (reiryuki/Mi-Sound-Redmi-K40-Magisk-Module). The app ships no Dolby binaries; when the
 * module is not installed the effect is simply not found and the switch stays unavailable.
 */
object DolbyAtmosV2Manager {
    private const val TAG = "DolbyAtmosV2Manager"
    const val KEY_DOLBY_V2_ENABLED = "dolby_v2_enabled"
    const val DAX_PACKAGE = "com.dolby.daxservice"

    private val DAP_PROXY_UUID: UUID = UUID.fromString("9d4921da-8225-4f29-aefa-39537a04bcaa")
    private val DAP_SW_UUID: UUID = UUID.fromString("6ab06da4-c516-4611-8166-452799218539")

    private val EFFECT_TYPE_NULL: UUID = UUID.fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210")

    /** The public SDK only exposes AudioEffect's no-arg constructor, so the UUID-based one is reached via reflection. */
    private fun newDapEffect(
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

    private var prefs: SharedPreferences? = null
    private var effect: AudioEffect? = null
    private var sessionId: Int = 0

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _availability = MutableStateFlow(DolbyV2Availability.UNKNOWN)
    val availability: StateFlow<DolbyV2Availability> = _availability.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(DolbyAtmosManager.PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        _isEnabled.value = sp.getBoolean(KEY_DOLBY_V2_ENABLED, false)
        refreshAvailability()
    }

    /** Looks for the Dolby DAP effect among the effects registered in the system audio effect config. */
    fun refreshAvailability() {
        val found = findDapUuid() != null
        _availability.value =
            when {
                !found -> DolbyV2Availability.MODULE_MISSING
                _isEnabled.value && effect == null && sessionId > 0 -> DolbyV2Availability.ATTACH_FAILED
                else -> DolbyV2Availability.AVAILABLE
            }
    }

    private fun findDapUuid(): UUID? =
        try {
            val uuids = AudioEffect.queryEffects()?.map { it.uuid }.orEmpty()
            when {
                DAP_PROXY_UUID in uuids -> DAP_PROXY_UUID
                DAP_SW_UUID in uuids -> DAP_SW_UUID
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed querying audio effects", e)
            null
        }

    /** Turns V2 on or off. V2 and the built-in Dolby Atmos processor are mutually exclusive. */
    @Synchronized
    fun setEnabled(on: Boolean) {
        _isEnabled.value = on
        prefs?.edit()?.putBoolean(KEY_DOLBY_V2_ENABLED, on)?.apply()
        if (on) {
            if (DolbyAtmosManager.isAtmosEnabledByUser.value) DolbyAtmosManager.setAtmosEnabled(false)
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
                .getLaunchIntentForPackage(DAX_PACKAGE)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (e: Exception) {
            null
        }

    private fun createEffect() {
        val uuid = findDapUuid() ?: return
        try {
            val created = newDapEffect(uuid, sessionId)
            if (created.setEnabled(true) != AudioEffect.SUCCESS) {
                created.release()
                Log.w(TAG, "Dolby DAP effect refused to enable on session $sessionId")
                return
            }
            effect = created
            _isActive.value = true
            Log.i(TAG, "Dolby DAP attached to session $sessionId")
        } catch (e: Exception) {
            Log.w(TAG, "Dolby DAP unavailable for session $sessionId", e)
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
