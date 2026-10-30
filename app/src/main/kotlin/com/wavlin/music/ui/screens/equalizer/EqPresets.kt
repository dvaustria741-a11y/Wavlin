package com.wavlin.music.ui.screens.equalizer

import androidx.annotation.StringRes
import com.wavlin.music.R
import com.wavlin.music.eq.data.FilterType
import com.wavlin.music.eq.data.ParametricEQBand

/** Center frequencies (Hz) of the custom graphic equalizer, one octave apart. */
val EqBandFrequencies: List<Double> = listOf(
    31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
)

val EqBandLabels: List<String> = listOf(
    "31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"
)

const val EqBandCount = 10
const val EqMaxGainDb = 12f
const val EqGainStepDb = 0.5f
private const val EqBandQ = 1.41

/** Built-in starting points for the custom equalizer. */
data class EqPreset(
    val key: String,
    @StringRes val nameRes: Int,
    val gains: List<Float>,
)

val EqBuiltInPresets: List<EqPreset> = listOf(
    EqPreset("flat", R.string.eq_preset_flat, List(EqBandCount) { 0f }),
    EqPreset("bass_boost", R.string.eq_preset_bass_boost, listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)),
    EqPreset("treble_boost", R.string.eq_preset_treble_boost, listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f)),
    EqPreset("vocal", R.string.eq_preset_vocal, listOf(-3f, -2f, -1f, 1f, 3f, 4f, 3f, 2f, 0f, -1f)),
    EqPreset("rock", R.string.eq_preset_rock, listOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f)),
    EqPreset("pop", R.string.eq_preset_pop, listOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 1f, 2f)),
    EqPreset("electronic", R.string.eq_preset_electronic, listOf(5f, 4f, 1f, 0f, -2f, 2f, 1f, 2f, 4f, 5f)),
    EqPreset("hip_hop", R.string.eq_preset_hip_hop, listOf(5f, 4f, 2f, 3f, -1f, -1f, 2f, -1f, 2f, 3f)),
    EqPreset("classical", R.string.eq_preset_classical, listOf(4f, 3f, 2f, 2f, -1f, -1f, 0f, 2f, 3f, 3f)),
    EqPreset("acoustic", R.string.eq_preset_acoustic, listOf(4f, 4f, 3f, 1f, 2f, 1f, 2f, 3f, 3f, 2f)),
    EqPreset("loudness", R.string.eq_preset_loudness, listOf(5f, 3f, 0f, 0f, -1f, 0f, -1f, 0f, 3f, 4f)),
)

/** Converts slider gains into the parametric bands consumed by the audio processor. */
fun gainsToBands(gains: List<Float>): List<ParametricEQBand> =
    EqBandFrequencies.mapIndexed { index, frequency ->
        ParametricEQBand(
            frequency = frequency,
            gain = gains.getOrElse(index) { 0f }.toDouble(),
            q = EqBandQ,
            filterType = FilterType.PK,
            // Skipping flat bands saves a biquad per channel
            enabled = gains.getOrElse(index) { 0f } != 0f,
        )
    }

/** Reads slider gains back from bands that were produced by [gainsToBands]; null if the layout differs. */
fun bandsToGains(bands: List<ParametricEQBand>): List<Float>? {
    if (bands.size != EqBandCount) return null
    val matches = bands.indices.all { i -> bands[i].frequency == EqBandFrequencies[i] }
    if (!matches) return null
    return bands.map { if (it.enabled) it.gain.toFloat() else 0f }
}
