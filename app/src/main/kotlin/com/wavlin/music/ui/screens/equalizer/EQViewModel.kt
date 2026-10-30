package com.wavlin.music.ui.screens.equalizer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wavlin.music.eq.EqualizerService
import com.wavlin.music.eq.data.EQProfileRepository
import com.wavlin.music.eq.data.ParametricEQParser
import com.wavlin.music.eq.data.SavedEQProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import javax.inject.Inject

/**
 * ViewModel for EQ Screen
 * Manages the custom graphic equalizer, imported EQ profiles and applies them to the EqualizerService
 */
@HiltViewModel
class EQViewModel @Inject constructor(
    private val eqProfileRepository: EQProfileRepository,
    private val equalizerService: EqualizerService
) : ViewModel() {

    companion object {
        /** Id of the profile that stores the current state of the custom graphic equalizer. */
        const val CUSTOM_PROFILE_ID = "wavlin_graphic_eq"
        private const val USER_PRESET_PREFIX = "geq_user_"
        private const val SOURCE_AUTO_PREAMP = "geq_auto"
        private const val SOURCE_MANUAL_PREAMP = "geq_manual"
        private const val CUSTOM_PROFILE_NAME = "Custom"

        // Filters are updated while dragging, but only saved (which makes the player re-buffer) once idle
        private const val LIVE_APPLY_DEBOUNCE_MS = 40L
        private const val PERSIST_DEBOUNCE_MS = 500L
    }

    private val _state = MutableStateFlow(EQState())
    val state: StateFlow<EQState> = _state.asStateFlow()

    private var restoredCustomState = false
    private var applyJob: Job? = null
    private var persistJob: Job? = null
    private var hasUnsavedCustomEdits = false

    /** Last imported profile that was active, so the on/off switch can bring it back. */
    private var lastImportedActiveId: String? = null

    init {
        loadProfiles()
    }

    /**
     * Load all saved EQ profiles (sorted: AutoEQ first, then custom)
     */
    private fun loadProfiles() {
        // Observe profiles changes
        viewModelScope.launch {
            eqProfileRepository.profiles.collect { all ->
                val sortedProfiles = eqProfileRepository.getSortedProfiles()
                val presets = all
                    .filter { it.id.startsWith(USER_PRESET_PREFIX) }
                    .sortedByDescending { it.addedTimestamp }
                val savedCustom = all.find { it.id == CUSTOM_PROFILE_ID }
                val restore = !restoredCustomState
                restoredCustomState = true

                _state.update { current ->
                    var next = current.copy(profiles = sortedProfiles, userPresets = presets)
                    if (restore && savedCustom != null) {
                        val gains = bandsToGains(savedCustom.bands) ?: current.gains
                        val manual = savedCustom.source == SOURCE_MANUAL_PREAMP
                        next = next.copy(
                            gains = gains,
                            autoPreamp = !manual,
                            manualPreampDb = if (manual) savedCustom.preamp.toFloat() else 0f,
                        )
                    }
                    next.copy(selectedPresetKey = matchPresetKey(next.gains, next.userPresets))
                }
            }
        }

        // Observe active profile changes separately
        viewModelScope.launch {
            eqProfileRepository.activeProfile.collect { activeProfile ->
                if (activeProfile != null && activeProfile.id != CUSTOM_PROFILE_ID) {
                    lastImportedActiveId = activeProfile.id
                }
                _state.update {
                    it.copy(activeProfileId = activeProfile?.id)
                }
            }
        }
    }

    // --- Custom graphic equalizer ---

    /** Sets one band; the change is audible immediately and turns the custom equalizer on. */
    fun setGain(index: Int, gain: Float) {
        if (index !in 0 until EqBandCount) return
        _state.update { current ->
            val gains = current.gains.toMutableList().also { it[index] = gain }
            current.copy(gains = gains, selectedPresetKey = matchPresetKey(gains, current.userPresets))
        }
        onCustomEdited()
    }

    fun applyBuiltInPreset(preset: EqPreset) {
        _state.update { it.copy(gains = preset.gains, selectedPresetKey = preset.key) }
        onCustomEdited()
    }

    fun applyUserPreset(preset: SavedEQProfile) {
        val gains = bandsToGains(preset.bands) ?: return
        _state.update { it.copy(gains = gains, selectedPresetKey = preset.id) }
        onCustomEdited()
    }

    fun resetCustom() {
        applyBuiltInPreset(EqBuiltInPresets.first())
    }

    fun setAutoPreamp(auto: Boolean) {
        _state.update { current ->
            if (auto) {
                current.copy(autoPreamp = true)
            } else {
                // Start the manual preamp where the automatic one was so the level does not jump
                val manual = effectivePreampDb(current.gains, true, 0f).toFloat()
                current.copy(autoPreamp = false, manualPreampDb = manual)
            }
        }
        onCustomEdited()
    }

    fun setManualPreamp(preampDb: Float) {
        _state.update { it.copy(autoPreamp = false, manualPreampDb = preampDb) }
        onCustomEdited()
    }

    /** Saves the current curve as a named preset the user can come back to. */
    fun saveUserPreset(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val current = _state.value
            val id = USER_PRESET_PREFIX + System.currentTimeMillis()
            eqProfileRepository.saveProfile(
                SavedEQProfile(
                    id = id,
                    name = trimmed,
                    deviceModel = trimmed,
                    bands = gainsToBands(current.gains),
                    preamp = effectivePreampDb(current.gains, current.autoPreamp, current.manualPreampDb),
                    source = if (current.autoPreamp) SOURCE_AUTO_PREAMP else SOURCE_MANUAL_PREAMP,
                    isCustom = false,
                )
            )
            _state.update { it.copy(selectedPresetKey = id) }
        }
    }

    fun deleteUserPreset(presetId: String) {
        viewModelScope.launch {
            eqProfileRepository.deleteProfile(presetId)
            _state.update { current ->
                val remaining = current.userPresets.filterNot { it.id == presetId }
                current.copy(selectedPresetKey = matchPresetKey(current.gains, remaining))
            }
        }
    }

    /** Writes any pending custom edits right away; call when the screen goes away. */
    fun flush() {
        if (!hasUnsavedCustomEdits) return
        applyJob?.cancel()
        persistJob?.cancel()
        viewModelScope.launch {
            applyCustomLive()
            persistCustom()
        }
    }

    private fun onCustomEdited() {
        hasUnsavedCustomEdits = true
        applyJob?.cancel()
        applyJob = viewModelScope.launch {
            delay(LIVE_APPLY_DEBOUNCE_MS)
            applyCustomLive()
        }
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            persistCustom()
        }
    }

    private fun buildCustomProfile(state: EQState): SavedEQProfile = SavedEQProfile(
        id = CUSTOM_PROFILE_ID,
        name = CUSTOM_PROFILE_NAME,
        deviceModel = CUSTOM_PROFILE_NAME,
        bands = gainsToBands(state.gains),
        preamp = effectivePreampDb(state.gains, state.autoPreamp, state.manualPreampDb),
        source = if (state.autoPreamp) SOURCE_AUTO_PREAMP else SOURCE_MANUAL_PREAMP,
        isCustom = false,
    )

    /** Pushes the current curve to the audio processor without touching storage or the player. */
    private fun applyCustomLive() {
        equalizerService.applyProfile(buildCustomProfile(_state.value)).onFailure { e ->
            _state.update { it.copy(error = e.message ?: "Unknown error") }
        }
    }

    /** Saves the curve and makes it the active profile (which re-buffers playback once). */
    private suspend fun persistCustom() {
        eqProfileRepository.saveProfile(buildCustomProfile(_state.value))
        if (eqProfileRepository.getActiveProfile()?.id != CUSTOM_PROFILE_ID) {
            eqProfileRepository.setActiveProfile(CUSTOM_PROFILE_ID)
        }
        hasUnsavedCustomEdits = false
    }

    private fun cancelPendingCustomWork() {
        applyJob?.cancel()
        persistJob?.cancel()
    }

    private fun matchPresetKey(gains: List<Float>, userPresets: List<SavedEQProfile>): String? {
        EqBuiltInPresets.firstOrNull { it.gains == gains }?.let { return it.key }
        return userPresets.firstOrNull { bandsToGains(it.bands) == gains }?.id
    }

    // --- Switch and imported profiles ---

    /** Master on/off switch: off disables EQ, on restores the last used profile (custom by default). */
    fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            selectProfile(null)
            return
        }
        val importedId = lastImportedActiveId?.takeIf { id -> _state.value.profiles.any { it.id == id } }
        selectProfile(importedId ?: CUSTOM_PROFILE_ID)
    }

    /**
     * Select and apply an EQ profile
     * Pass null to disable EQ, or [CUSTOM_PROFILE_ID] for the custom graphic equalizer
     */
    fun selectProfile(profileId: String?) {
        cancelPendingCustomWork()
        viewModelScope.launch {
            if (profileId == null) {
                // Disable EQ
                equalizerService.disable()
                eqProfileRepository.setActiveProfile(null)
            } else if (profileId == CUSTOM_PROFILE_ID) {
                val profile = buildCustomProfile(_state.value)
                eqProfileRepository.saveProfile(profile)
                equalizerService.applyProfile(profile).onSuccess {
                    eqProfileRepository.setActiveProfile(CUSTOM_PROFILE_ID)
                    hasUnsavedCustomEdits = false
                }.onFailure { e ->
                    _state.update { it.copy(error = e.message ?: "Unknown error") }
                }
            } else {
                // Apply the selected profile
                val profile = _state.value.profiles.find { it.id == profileId }
                if (profile != null) {
                    val result = equalizerService.applyProfile(profile)
                    result.onSuccess {
                        eqProfileRepository.setActiveProfile(profileId)
                    }.onFailure { e ->
                        _state.update { it.copy(error = e.message ?: "Unknown error") }
                    }
                }
            }
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Delete an EQ profile
     */
    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            eqProfileRepository.deleteProfile(profileId)
        }
    }

    /**
     * Import a custom EQ profile from a file
     */
    fun importCustomProfile(
        fileName: String,
        inputStream: InputStream,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        viewModelScope.launch {
            try {
                // Read the file content
                val content = inputStream.bufferedReader().use { it.readText() }
                inputStream.close()

                // Parse the ParametricEQ format
                val parametricEQ = ParametricEQParser.parseText(content)

                // Validate the parsed EQ
                val validationErrors = ParametricEQParser.validate(parametricEQ)
                if (validationErrors.isNotEmpty()) {
                    onError(Exception("Invalid EQ file: ${validationErrors.first()}"))
                    return@launch
                }

                // Extract profile name from file name (remove .txt extension)
                val profileName = fileName.removeSuffix(".txt")

                // Import the profile
                eqProfileRepository.importCustomProfile(profileName, parametricEQ)

                _state.update { it.copy(importStatus = "Successfully imported $profileName") }
                onSuccess()
            } catch (e: Exception) {
                onError(Exception("Failed to import EQ profile: ${e.message}"))
            }
        }
    }
}
