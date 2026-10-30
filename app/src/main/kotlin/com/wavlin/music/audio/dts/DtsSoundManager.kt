package com.wavlin.music.audio.dts

import com.wavlin.music.audio.SystemEffectController
import java.util.UUID

/**
 * DTS Sound: the Transsion DTS post-process effect (libdtsaudio.so, com.transsion.dtsaudio), built into
 * Tecno/Infinix/itel ROMs and registered elsewhere by reiryuki/DTS-Sound-Infinix-ZERO-ULTRA-Magisk-Module.
 */
object DtsSoundManager : SystemEffectController(
    tag = "DtsSoundManager",
    prefsName = "wavlin_dts_sound",
    prefKey = "dts_sound_enabled",
    effectUuids = listOf(UUID.fromString("146edfc0-7ed2-11e4-80eb-0002a5d5c51b")),
    settingsPackage = "com.transsion.dtsaudio",
)
