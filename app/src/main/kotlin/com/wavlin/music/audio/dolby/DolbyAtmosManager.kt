package com.wavlin.music.audio.dolby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.media.audiofx.Virtualizer
import com.wavlin.music.playback.audio.DolbyAtmosAudioProcessor
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Central coordinator for Dolby Atmos capability detection, routing changes, and playback state.
 *
 * Operates as a lifecycle-aware coordinator:
 * - Listens for audio-device connections/disconnections.
 * - Listens for Android Spatializer state transitions.
 * - Re-evaluates capabilities on background dispatcher.
 * - Caches static codec support to avoid redundant framework scans.
 */
object DolbyAtmosManager {

    private const val TAG = "DolbyAtmosManager"
    const val PREFS_NAME = "wavlin_dolby"
    const val KEY_DOLBY_ATMOS_ENABLED = "dolby_atmos_enabled"
    const val KEY_EXPANDED_STEREO = "dolby_expanded_stereo"

    /** Enhanced dialogue is fixed at its maximum (3), matching the system Dolby Atmos setting. */
    const val ENHANCED_DIALOGUE_LEVEL = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private var audioManager: AudioManager? = null

    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var spatializerListener: Any? = null
    private var noisyReceiver: BroadcastReceiver? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private var volumeFraction = 0.5f

    private val _liveDiagnostics = MutableStateFlow(DolbyAtmosDiagnostics())
    val liveDiagnostics: StateFlow<DolbyAtmosDiagnostics> = _liveDiagnostics.asStateFlow()

    private val _isAtmosActive = MutableStateFlow(false)
    val isAtmosActive: StateFlow<Boolean> = _isAtmosActive.asStateFlow()

    private val _isAtmosEnabledByUser = MutableStateFlow(false)
    val isAtmosEnabledByUser: StateFlow<Boolean> = _isAtmosEnabledByUser.asStateFlow()

    /** Expanded stereo strength 0..3 (default 3). Only audible on earphone routes, like system Dolby. */
    private val _expandedStereoLevel = MutableStateFlow(3)
    val expandedStereoLevel: StateFlow<Int> = _expandedStereoLevel.asStateFlow()

    private val _isHeadphoneRoute = MutableStateFlow(false)
    val isHeadphoneRoute: StateFlow<Boolean> = _isHeadphoneRoute.asStateFlow()

    private val processors: MutableSet<DolbyAtmosAudioProcessor> =
        Collections.newSetFromMap(WeakHashMap<DolbyAtmosAudioProcessor, Boolean>())

    private var currentSongHasAtmos: Boolean = false
    private var currentSongMimeType: String? = null
    private var currentSongChannelCount: Int = 2

    /**
     * Initializes the manager with the Application Context.
     */
    fun init(context: Context) {
        if (appContext != null) return

        val app = context.applicationContext
        appContext = app

        val sp = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        val isEnabled = sp.getBoolean(KEY_DOLBY_ATMOS_ENABLED, false)
        _isAtmosEnabledByUser.value = isEnabled
        _expandedStereoLevel.value = sp.getInt(KEY_EXPANDED_STEREO, 3).coerceIn(0, 3)

        audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        _isHeadphoneRoute.value = detectHeadphoneRoute()
        volumeFraction = readVolumeFraction()

        registerListeners(app)
        refreshCapabilities()
    }

    private fun registerListeners(context: Context) {
        val am = audioManager ?: return

        // 1. Audio device routing listener (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioDeviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    Log.d(TAG, "Audio device added, re-evaluating Atmos route...")
                    refreshCapabilities()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    Log.d(TAG, "Audio device removed, re-evaluating Atmos route...")
                    refreshCapabilities()
                }
            }
            am.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        }

        // 2. Spatializer state listener (API 32+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            try {
                val spatializer = am.spatializer
                val listener = object : Spatializer.OnSpatializerStateChangedListener {
                    override fun onSpatializerAvailableChanged(spatializer: Spatializer, available: Boolean) {
                        Log.d(TAG, "Spatializer availability changed: $available")
                        refreshCapabilities()
                    }

                    override fun onSpatializerEnabledChanged(spatializer: Spatializer, enabled: Boolean) {
                        Log.d(TAG, "Spatializer enabled changed: $enabled")
                        refreshCapabilities()
                    }
                }
                spatializer.addOnSpatializerStateChangedListener(
                    { command -> mainHandler.post(command) },
                    listener
                )
                spatializerListener = listener
            } catch (e: Exception) {
                Log.w(TAG, "Failed registering Spatializer listener", e)
            }
        }

        // 3. Audio becoming noisy receiver (fallback for wired/BT disconnects)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent?.action) {
                    refreshCapabilities()
                }
            }
        }
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        context.registerReceiver(receiver, filter)
        noisyReceiver = receiver

        // 4. System music volume (drives the audio regulator: tighter limits at high volume).
        val volReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                volumeFraction = readVolumeFraction()
                syncProcessors()
            }
        }
        try {
            ContextCompat.registerReceiver(
                context,
                volReceiver,
                IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            volumeReceiver = volReceiver
        } catch (e: Exception) {
            Log.w(TAG, "Failed registering volume receiver", e)
        }
    }

    private fun readVolumeFraction(): Float {
        val am = audioManager ?: return 0.5f
        return try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        } catch (e: Exception) {
            0.5f
        }
    }

    /**
     * Re-evaluates capabilities asynchronously on Dispatchers.Default.
     */
    fun refreshCapabilities() {
        val context = appContext ?: return
        val isPreferred = _isAtmosEnabledByUser.value

        scope.launch {
            val diag = DolbyAtmosDetector.evaluate(context, isPreferred)
            val updatedDiag = diag.copy(
                activeMimeType = currentSongMimeType,
                channelCount = currentSongChannelCount
            )
            _liveDiagnostics.value = updatedDiag

            // isAtmosActive must only be true when the actual stream IS Dolby Atmos.
            // Checking just capability would light up the badge for plain AAC streams.
            val active = isPreferred && currentSongHasAtmos &&
                updatedDiag.capability != DolbyAtmosCapability.UNSUPPORTED &&
                updatedDiag.capability != DolbyAtmosCapability.DISABLED
            _isAtmosActive.value = active
            _isHeadphoneRoute.value = detectHeadphoneRoute()
            volumeFraction = readVolumeFraction()
            applyVirtualizerState(isPreferred)
            syncProcessors()
        }
    }

    /** True when audio is going to wired / USB / Bluetooth earphones (expanded stereo is earphones-only). */
    private fun detectHeadphoneRoute(): Boolean {
        val am = audioManager ?: return false
        return try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { d ->
                when (d.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    -> true
                    else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        d.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun effectiveStereoLevel(): Int = if (_isHeadphoneRoute.value) _expandedStereoLevel.value else 0

    /** Registers a player's processor and immediately pushes the current Dolby settings to it. */
    fun registerProcessor(proc: DolbyAtmosAudioProcessor) {
        synchronized(processors) { processors.add(proc) }
        pushTo(proc)
    }

    private fun syncProcessors() {
        val snapshot = synchronized(processors) { processors.toList() }
        snapshot.forEach { pushTo(it) }
    }

    private fun pushTo(proc: DolbyAtmosAudioProcessor) {
        proc.setParameters(
            enabled = _isAtmosEnabledByUser.value,
            dialogueLevel = ENHANCED_DIALOGUE_LEVEL,
            stereoLevel = effectiveStereoLevel(),
            volume = volumeFraction,
        )
    }

    /** Sets Expanded stereo strength (0..3). */
    fun setExpandedStereoLevel(level: Int) {
        val v = level.coerceIn(0, 3)
        _expandedStereoLevel.value = v
        prefs?.edit()?.putInt(KEY_EXPANDED_STEREO, v)?.apply()
        applyVirtualizerState(_isAtmosEnabledByUser.value)
        syncProcessors()
    }

    /**
     * Toggles Dolby Atmos preference.
     */
    fun setAtmosEnabled(enabled: Boolean) {
        _isAtmosEnabledByUser.value = enabled
        prefs?.edit()?.putBoolean(KEY_DOLBY_ATMOS_ENABLED, enabled)?.apply()
        applyVirtualizerState(enabled)
        syncProcessors()
        refreshCapabilities()
    }

    /**
     * Informs manager about current track Atmos metadata.
     */
    fun updateCurrentTrackAtmosStatus(hasAtmosStream: Boolean, mimeType: String?, channelCount: Int = 2) {
        currentSongHasAtmos = hasAtmosStream
        currentSongMimeType = mimeType
        currentSongChannelCount = channelCount

        val currentDiag = _liveDiagnostics.value
        val isPreferred = _isAtmosEnabledByUser.value
        val canSpatialize = currentDiag.capability == DolbyAtmosCapability.FULLY_SUPPORTED
        // Only mark active when there's a real Atmos stream — not just user preference.
        val active = isPreferred && hasAtmosStream &&
            currentDiag.capability != DolbyAtmosCapability.UNSUPPORTED &&
            currentDiag.capability != DolbyAtmosCapability.DISABLED
        _isAtmosActive.value = active
        applyVirtualizerState(isPreferred)
        _liveDiagnostics.value = currentDiag.copy(
            activeMimeType = mimeType,
            channelCount = channelCount,
            currentMode = when {
                !isPreferred -> AtmosPlaybackMode.DISABLED
                hasAtmosStream && canSpatialize -> AtmosPlaybackMode.ACTIVE_ATMOS
                hasAtmosStream -> AtmosPlaybackMode.STEREO_FALLBACK
                else -> AtmosPlaybackMode.NOT_ATMOS
            }
        )
    }

    private var virtualizer: Virtualizer? = null
    private var attachedSessionId: Int = 0

    /**
     * Binds the spatial (Virtualizer) effect to the player's audio session. It follows the user's toggle,
     * not the stream codec: YouTube Music never delivers E-AC-3 JOC/AC-4, so gating on the stream meant
     * the effect could never turn on. The Atmos badge (isAtmosActive) still only shows for real Atmos streams.
     */
    @Synchronized
    fun attachSession(sessionId: Int) {
        if (sessionId <= 0 || sessionId == attachedSessionId) return
        releaseVirtualizer()
        attachedSessionId = sessionId
        try {
            virtualizer = Virtualizer(0, sessionId).apply {
                val level = effectiveStereoLevel()
                if (strengthSupported) setStrength((level * 1000 / 3).toShort())
                enabled = _isAtmosEnabledByUser.value && level > 0
            }
            Log.i(TAG, "Virtualizer attached to session $sessionId, strengthSupported=${virtualizer?.strengthSupported}, enabled=${virtualizer?.enabled}")
        } catch (e: Exception) {
            Log.w(TAG, "Virtualizer unavailable for session $sessionId", e)
            virtualizer = null
        }
    }

    @Synchronized
    private fun applyVirtualizerState(active: Boolean) {
        try {
            val v = virtualizer ?: return
            val level = effectiveStereoLevel()
            if (v.strengthSupported) v.setStrength((level * 1000 / 3).toShort())
            v.enabled = active && level > 0
        } catch (e: Exception) {
            Log.w(TAG, "Failed toggling Virtualizer", e)
        }
    }

    @Synchronized
    private fun releaseVirtualizer() {
        try {
            virtualizer?.release()
        } catch (_: Exception) {}
        virtualizer = null
        attachedSessionId = 0
    }

    /**
     * Releases listeners during application shutdown.
     */
    fun release() {
        val context = appContext ?: return
        val am = audioManager
        releaseVirtualizer()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null && am != null) {
            am.unregisterAudioDeviceCallback(audioDeviceCallback)
            audioDeviceCallback = null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2 && spatializerListener != null && am != null) {
            try {
                (spatializerListener as? Spatializer.OnSpatializerStateChangedListener)?.let {
                    am.spatializer.removeOnSpatializerStateChangedListener(it)
                }
            } catch (_: Exception) {}
            spatializerListener = null
        }

        volumeReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {}
            volumeReceiver = null
        }

        noisyReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {}
            noisyReceiver = null
        }

        appContext = null
    }
}
