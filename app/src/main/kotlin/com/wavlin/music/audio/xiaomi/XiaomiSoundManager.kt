package com.wavlin.music.audio.xiaomi

import com.wavlin.music.audio.SystemEffectController
import java.util.UUID

/**
 * Xiaomi Sound: the system Mi Sound effect (libmisoundfx.so), built into Xiaomi/Redmi/POCO ROMs and
 * registered elsewhere by the Mi Sound Magisk module (reiryuki/Mi-Sound-Redmi-K40-Magisk-Module).
 */
object XiaomiSoundManager : SystemEffectController(
    tag = "XiaomiSoundManager",
    prefsName = "wavlin_xiaomi_sound",
    prefKey = "xiaomi_sound_enabled",
    effectUuids = listOf(UUID.fromString("5b8e36a5-144a-4c38-b1d7-0002a5d5c51b")),
    settingsPackage = "com.miui.misound",
)
