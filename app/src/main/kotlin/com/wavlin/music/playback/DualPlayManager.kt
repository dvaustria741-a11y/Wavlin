/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.playback

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.getSystemService
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.wavlin.music.constants.DualPlayBalanceKey
import com.wavlin.music.constants.DualPlayBluetoothOnlyKey
import com.wavlin.music.extensions.toMediaItem
import com.wavlin.music.playback.audio.ChannelPanAudioProcessor
import com.wavlin.music.utils.dataStore
import com.wavlin.music.utils.get
import com.wavlin.music.utils.safeDataStoreEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.min
import kotlin.random.Random

/**
 * Dual Play mode: the main player carries one song hard-panned to the left channel while a
 * second, fully independent player carries a different song hard-panned to the right channel.
 * Both ExoPlayer instances write to their own hardware AudioTrack; the device mixes them, so
 * each ear genuinely hears a different song from the same headphones.
 *
 * Requires a Bluetooth audio output by default (checked via [isBluetoothAudioConnected]) since
 * wired/earpiece stereo splitting this way isn't a meaningful use case and two independent
 * decode+output pipelines are unnecessary battery/CPU cost when not actually needed.
 */
class DualPlayManager(private val service: MusicService) {

    private companion object {
        private const val TAG = "DualPlayManager"
    }

    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var partnerPlayer: ExoPlayer? = null
    private var partnerPanProcessor: ChannelPanAudioProcessor? = null
    private var mainPanProcessor: ChannelPanAudioProcessor? = null

    private val _isActive = MutableStateFlow(false)
    val isActive = _isActive.asStateFlow()

    private val _balance = MutableStateFlow(0.5f)
    val balance = _balance.asStateFlow()

    private val _partnerIsPlaying = MutableStateFlow(false)
    val partnerIsPlaying = _partnerIsPlaying.asStateFlow()

    private val _partnerMediaItem = MutableStateFlow<MediaItem?>(null)
    val partnerMediaItem = _partnerMediaItem.asStateFlow()

    /** True once a start attempt has been refused for lacking a Bluetooth output. */
    private val _bluetoothRequired = MutableStateFlow(false)
    val bluetoothRequired = _bluetoothRequired.asStateFlow()

    private val partnerListener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _partnerIsPlaying.value = isPlaying
            }

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) {
                _partnerMediaItem.value = mediaItem
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val player = partnerPlayer ?: return
                if (playbackState == Player.STATE_ENDED && !player.hasNextMediaItem()) {
                    autoQueueNext()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Timber.tag(TAG).e(error, "Dual Play partner player error, skipping")
                autoQueueNext()
            }
        }

    init {
        managerScope.launch {
            _balance.value = service.applicationContext.dataStore.get(DualPlayBalanceKey, 0.5f)
        }
    }

    fun isBluetoothOnlyEnabled(): Boolean =
        service.applicationContext.dataStore.get(DualPlayBluetoothOnlyKey, true)

    /** Whether the current audio output route includes a Bluetooth device. */
    fun isBluetoothAudioConnected(): Boolean {
        val audioManager = service.getSystemService<AudioManager>() ?: return false
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    /**
     * Starts Dual Play: main player keeps playing whatever it already has queued (now confined
     * to the left channel), and [partnerItem] starts playing independently on the right channel.
     * Returns false (and does nothing) if Bluetooth is required but not connected.
     */
    fun start(partnerItem: MediaItem): Boolean {
        if (isBluetoothOnlyEnabled() && !isBluetoothAudioConnected()) {
            _bluetoothRequired.value = true
            return false
        }
        _bluetoothRequired.value = false

        val existingMain = mainPanProcessor ?: service.playerPanProcessors[service.player]
        if (existingMain == null) {
            Timber.tag(TAG).w("No pan processor registered for main player; cannot start Dual Play")
            return false
        }
        mainPanProcessor = existingMain

        stopInternal(revertMainPan = false)

        val panProcessor = ChannelPanAudioProcessor().also { it.panMode = ChannelPanAudioProcessor.PanMode.RIGHT_ONLY }
        partnerPanProcessor = panProcessor
        val player = service.buildDualPlayPartnerPlayer(panProcessor)
        player.addListener(partnerListener)
        player.setMediaItem(partnerItem)
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.prepare()
        player.playWhenReady = true
        partnerPlayer = player
        _partnerMediaItem.value = partnerItem

        existingMain.panMode = ChannelPanAudioProcessor.PanMode.LEFT_ONLY
        applyBalance(_balance.value)

        _isActive.value = true
        return true
    }

    fun stop() {
        stopInternal(revertMainPan = true)
        _isActive.value = false
        _partnerMediaItem.value = null
        _partnerIsPlaying.value = false
    }

    private fun stopInternal(revertMainPan: Boolean) {
        partnerPlayer?.let { p ->
            p.removeListener(partnerListener)
            service.playerPanProcessors.remove(p)
            p.stop()
            p.release()
        }
        partnerPlayer = null
        partnerPanProcessor = null

        if (revertMainPan) {
            mainPanProcessor?.panMode = ChannelPanAudioProcessor.PanMode.NONE
            service.player.volume = 1f
        }
    }

    fun togglePartnerPlayPause() {
        val player = partnerPlayer ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun skipPartnerNext() {
        val player = partnerPlayer ?: return
        if (player.hasNextMediaItem()) {
            player.seekToNext()
        } else {
            autoQueueNext()
        }
    }

    fun replacePartnerSong(item: MediaItem) {
        val player = partnerPlayer ?: return
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    fun seekPartnerTo(positionMs: Long) {
        partnerPlayer?.seekTo(positionMs)
    }

    /** 0f = song A only (left), 1f = song B only (right), 0.5f = equal emphasis. */
    fun setBalance(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _balance.value = clamped
        applyBalance(clamped)
        managerScope.launch {
            service.applicationContext.safeDataStoreEdit { it[DualPlayBalanceKey] = clamped }
        }
    }

    private fun applyBalance(t: Float) {
        val gainA = min(1f, 2f * (1f - t))
        val gainB = min(1f, 2f * t)
        service.player.volume = gainA
        partnerPlayer?.volume = gainB
    }

    private fun autoQueueNext() {
        val player = partnerPlayer ?: return
        managerScope.launch {
            val excludeIds = setOfNotNull(
                player.currentMediaItem?.mediaId,
                service.player.currentMediaItem?.mediaId,
            )
            val next = pickRandomLibrarySong(excludeIds)
            if (next == null) {
                Timber.tag(TAG).w("Dual Play auto-queue: no library songs available")
                return@launch
            }
            replacePartnerSong(next)
        }
    }

    private suspend fun pickRandomLibrarySong(excludeIds: Set<String>): MediaItem? {
        val songs = try {
            service.database.songsByRowIdAsc().first()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Dual Play auto-queue: failed to read library")
            return null
        }
        val candidates = songs.filterNot { it.id in excludeIds }
        if (candidates.isEmpty()) return null
        return candidates[Random.nextInt(candidates.size)].toMediaItem()
    }

    fun release() {
        stopInternal(revertMainPan = true)
        managerScope.cancel()
    }
}
