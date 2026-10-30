package com.wavlin.music.ui.screens.equalizer

import android.annotation.SuppressLint
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wavlin.music.LocalNavController
import com.wavlin.music.LocalPlayerAwareWindowInsets
import com.wavlin.music.R
import com.wavlin.music.eq.data.SavedEQProfile
import com.wavlin.music.ui.component.pinkTopBarColors
import timber.log.Timber
import java.util.Locale
import kotlin.math.roundToInt

private const val TAB_CUSTOM = 0
private const val TAB_PROFILES = 1

/**
 * EQ Screen - customizable graphic equalizer plus imported / AutoEQ profiles
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun EqScreen(
    viewModel: EQViewModel = hiltViewModel(),
) {
    val navController = LocalNavController.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showError by remember { mutableStateOf<String?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }

    // Make sure the last slider movement is stored even when leaving the screen mid-debounce
    DisposableEffect(viewModel) {
        onDispose { viewModel.flush() }
    }

    // File picker for custom EQ import
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val contentResolver = context.contentResolver

                // Extract file name from URI
                var fileName = "custom_eq.txt"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (displayNameIndex >= 0) {
                            val name = cursor.getString(displayNameIndex)
                            if (!name.isNullOrBlank()) {
                                fileName = name
                            }
                        }
                    }
                }

                val inputStream = contentResolver.openInputStream(uri)

                if (inputStream != null) {
                    viewModel.importCustomProfile(
                        fileName = fileName,
                        inputStream = inputStream,
                        onSuccess = {
                            Timber.d("Custom EQ profile imported successfully: $fileName")
                        },
                        onError = { error ->
                            Timber.d("Error: Unable to import Custom EQ profile: $fileName")
                            showError = context.getString(R.string.import_error_title) + ": " + error.message
                        })
                } else {
                    showError = context.getString(R.string.error_file_read)
                }
            } catch (e: Exception) {
                showError = context.getString(R.string.error_file_open, e.message)
            }
        }
    }

    EqScreenContent(
        state = state,
        onNavigateBack = { navController.navigateUp() },
        showAddMenu = showAddMenu,
        onAddClicked = { showAddMenu = true },
        onAddMenuDismissed = { showAddMenu = false },
        onWizardClicked = {
            showAddMenu = false
            navController.navigate("eq_wizard")
        },
        onImportClicked = {
            showAddMenu = false
            filePickerLauncher.launch("text/plain")
        },
        onEnabledChange = viewModel::setEnabled,
        onProfileSelected = viewModel::selectProfile,
        onDeleteProfile = viewModel::deleteProfile,
        onGainChange = viewModel::setGain,
        onBuiltInPreset = viewModel::applyBuiltInPreset,
        onUserPreset = viewModel::applyUserPreset,
        onDeleteUserPreset = viewModel::deleteUserPreset,
        onSaveUserPreset = viewModel::saveUserPreset,
        onReset = viewModel::resetCustom,
        onAutoPreampChange = viewModel::setAutoPreamp,
        onManualPreampChange = viewModel::setManualPreamp,
    )

    // Error dialog
    if (showError != null) {
        AlertDialog(
            onDismissRequest = { showError = null },
            title = {
                Text(stringResource(R.string.import_error_title))
            },
            text = {
                Text(showError ?: "")
            },
            confirmButton = {
                TextButton(onClick = { showError = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }

    // Error dialog for apply failure
    if (state.error != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = {
                Text(stringResource(R.string.error_title))
            },
            text = {
                Text(stringResource(R.string.error_eq_apply_failed, state.error ?: ""))
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EqScreenContent(
    state: EQState,
    onNavigateBack: () -> Unit,
    showAddMenu: Boolean,
    onAddClicked: () -> Unit,
    onAddMenuDismissed: () -> Unit,
    onWizardClicked: () -> Unit,
    onImportClicked: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onProfileSelected: (String?) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onGainChange: (Int, Float) -> Unit,
    onBuiltInPreset: (EqPreset) -> Unit,
    onUserPreset: (SavedEQProfile) -> Unit,
    onDeleteUserPreset: (String) -> Unit,
    onSaveUserPreset: (String) -> Unit,
    onReset: () -> Unit,
    onAutoPreampChange: (Boolean) -> Unit,
    onManualPreampChange: (Float) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(TAB_CUSTOM) }
    var draggingBand by remember { mutableIntStateOf(-1) }

    // Sliders rise from flat to their saved position when the screen opens
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    // Displayed gains ease towards the target (preset changes); the band under the finger follows it exactly
    val displayGains = List(EqBandCount) { index ->
        animateFloatAsState(
            targetValue = if (appeared) state.gains[index] else 0f,
            animationSpec = if (draggingBand == index) {
                snap()
            } else {
                spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)
            },
            label = "eqGain$index",
        ).value
    }

    val activeProfile = state.profiles.find { it.id == state.activeProfileId }
    val graphBands = remember(tab, displayGains, activeProfile) {
        if (tab == TAB_CUSTOM) gainsToBands(displayGains) else activeProfile?.bands.orEmpty()
    }
    val graphPreamp = if (tab == TAB_CUSTOM) {
        effectivePreampDb(state.gains, state.autoPreamp, state.manualPreampDb)
    } else {
        activeProfile?.preamp ?: 0.0
    }
    val graphDim by animateFloatAsState(
        targetValue = when {
            tab == TAB_CUSTOM && !state.customActive -> 0.45f
            tab == TAB_PROFILES && activeProfile == null -> 0.45f
            else -> 1f
        },
        animationSpec = tween(300),
        label = "eqGraphDim",
    )

    Scaffold(
        topBar = {
            TopAppBar(
                colors = pinkTopBarColors(),
                title = { Text(stringResource(R.string.equalizer_header)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = onAddClicked) {
                            Icon(
                                painter = painterResource(R.drawable.add),
                                contentDescription = stringResource(R.string.import_profile)
                            )
                        }
                        DropdownMenu(
                            expanded = showAddMenu,
                            onDismissRequest = onAddMenuDismissed
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.eq_wizard)) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.discover_tune),
                                        contentDescription = null
                                    )
                                },
                                onClick = onWizardClicked
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_from_file)) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.upload),
                                        contentDescription = null
                                    )
                                },
                                onClick = onImportClicked
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(paddingValues),
            contentPadding = PaddingValues(
                bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding() + 16.dp
            )
        ) {
            // Live frequency response
            item {
                EqFrequencyResponseGraph(
                    bands = graphBands,
                    preamp = graphPreamp,
                    modifier = Modifier.alpha(graphDim),
                    showBandMarkers = tab == TAB_CUSTOM,
                    graphHeight = 190.dp,
                )
            }

            // Master switch
            item {
                EqPowerCard(
                    enabled = state.eqEnabled,
                    statusText = when {
                        !state.eqEnabled -> stringResource(R.string.eq_status_off)
                        state.customActive -> stringResource(R.string.eq_status_custom)
                        else -> activeProfile?.name ?: stringResource(R.string.eq_status_custom)
                    },
                    onEnabledChange = onEnabledChange,
                )
            }

            // Custom / Profiles switcher
            item {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    val tabs = listOf(R.string.eq_tab_custom, R.string.eq_tab_profiles)
                    tabs.forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = tab == index,
                            onClick = { tab = index },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                        ) {
                            Text(stringResource(label))
                        }
                    }
                }
            }

            item {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val direction = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(tween(300)) { fullWidth -> direction * fullWidth / 6 } + fadeIn(tween(240))) togetherWith
                            (slideOutHorizontally(tween(220)) { fullWidth -> -direction * fullWidth / 6 } + fadeOut(tween(140)))
                    },
                    label = "eqTabContent",
                ) { page ->
                    if (page == TAB_CUSTOM) {
                        CustomEqualizerPanel(
                            state = state,
                            displayGains = displayGains,
                            onGainChange = onGainChange,
                            onDragStateChange = { index, dragging ->
                                if (dragging) draggingBand = index else if (draggingBand == index) draggingBand = -1
                            },
                            onBuiltInPreset = onBuiltInPreset,
                            onUserPreset = onUserPreset,
                            onDeleteUserPreset = onDeleteUserPreset,
                            onSaveUserPreset = onSaveUserPreset,
                            onReset = onReset,
                            onAutoPreampChange = onAutoPreampChange,
                            onManualPreampChange = onManualPreampChange,
                        )
                    } else {
                        ProfilesPanel(
                            profiles = state.profiles,
                            activeProfileId = state.activeProfileId,
                            onProfileSelected = onProfileSelected,
                            onDeleteProfile = onDeleteProfile,
                            onImportClicked = onAddClicked,
                        )
                    }
                }
            }
        }
    }
}

// --- CUSTOM EQUALIZER ---

@Composable
private fun EqPowerCard(
    enabled: Boolean,
    statusText: String,
    onEnabledChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .clickable { onEnabledChange(!enabled) }
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.equalizer_header),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Crossfade(targetState = statusText, label = "eqStatus") { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
    }
}

@Composable
private fun CustomEqualizerPanel(
    state: EQState,
    displayGains: List<Float>,
    onGainChange: (Int, Float) -> Unit,
    onDragStateChange: (Int, Boolean) -> Unit,
    onBuiltInPreset: (EqPreset) -> Unit,
    onUserPreset: (SavedEQProfile) -> Unit,
    onDeleteUserPreset: (String) -> Unit,
    onSaveUserPreset: (String) -> Unit,
    onReset: () -> Unit,
    onAutoPreampChange: (Boolean) -> Unit,
    onManualPreampChange: (Float) -> Unit,
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetToDelete by remember { mutableStateOf<SavedEQProfile?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(8.dp))

        // Presets
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(EqBuiltInPresets, key = { it.key }) { preset ->
                FilterChip(
                    selected = state.selectedPresetKey == preset.key,
                    onClick = { onBuiltInPreset(preset) },
                    label = { Text(stringResource(preset.nameRes)) },
                )
            }
            items(state.userPresets, key = { it.id }) { preset ->
                FilterChip(
                    selected = state.selectedPresetKey == preset.id,
                    onClick = { onUserPreset(preset) },
                    label = { Text(preset.name) },
                    trailingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = stringResource(R.string.eq_delete_preset, preset.name),
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { presetToDelete = preset },
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Band sliders
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    EqBandLabels.forEachIndexed { index, label ->
                        EqBandSlider(
                            gain = displayGains[index],
                            label = label,
                            onGainChange = { onGainChange(index, it) },
                            onDragStateChange = { onDragStateChange(index, it) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.eq_gesture_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Preamp
        val preampDb = remember(state.gains, state.autoPreamp, state.manualPreampDb) {
            effectivePreampDb(state.gains, state.autoPreamp, state.manualPreampDb)
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.eq_preamp),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(
                                R.string.eq_preamp_value,
                                String.format(Locale.getDefault(), "%.1f", preampDb)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = stringResource(R.string.eq_preamp_auto),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = state.autoPreamp, onCheckedChange = onAutoPreampChange)
                }
                Slider(
                    value = preampDb.toFloat().coerceIn(-12f, 0f),
                    onValueChange = { onManualPreampChange((it * 10f).roundToInt() / 10f) },
                    valueRange = -12f..0f,
                    enabled = !state.autoPreamp,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) {
                Icon(
                    painter = painterResource(R.drawable.replay),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.eq_reset))
            }
            Button(onClick = { showSaveDialog = true }, modifier = Modifier.weight(1f)) {
                Icon(
                    painter = painterResource(R.drawable.add),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.eq_save_preset))
            }
        }
    }

    if (showSaveDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(R.string.eq_save_preset)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    label = { Text(stringResource(R.string.eq_preset_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        onSaveUserPreset(name)
                        showSaveDialog = false
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    presetToDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { presetToDelete = null },
            title = { Text(stringResource(R.string.delete_profile_desc)) },
            text = { Text(stringResource(R.string.delete_profile_confirmation, preset.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteUserPreset(preset.id)
                        presetToDelete = null
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { presetToDelete = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

// --- IMPORTED PROFILES ---

@Composable
private fun ProfilesPanel(
    profiles: List<SavedEQProfile>,
    activeProfileId: String?,
    onProfileSelected: (String?) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onImportClicked: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painter = painterResource(R.drawable.equalizer),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.no_profiles),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onImportClicked) {
                        Text(stringResource(R.string.import_profile))
                    }
                }
            }
        } else {
            profiles.forEach { profile ->
                EQProfileItem(
                    profile = profile,
                    isSelected = activeProfileId == profile.id,
                    onSelected = { onProfileSelected(profile.id) },
                    onDelete = { onDeleteProfile(profile.id) }
                )
            }
        }
    }
}

@Composable
private fun EQProfileItem(
    profile: SavedEQProfile,
    isSelected: Boolean,
    onSelected: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = {
            Text(
                text = profile.deviceModel,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        supportingContent = {
            Text(
                pluralStringResource(
                    id = R.plurals.band_count,
                    count = profile.bands.size,
                    profile.bands.size
                )
            )
        },
        leadingContent = {
            RadioButton(
                selected = isSelected,
                onClick = onSelected
            )
        },
        trailingContent = {
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    painter = painterResource(R.drawable.delete),
                    contentDescription = stringResource(R.string.delete_profile_desc),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        },
        modifier = Modifier
            .clickable(onClick = onSelected)
            .padding(horizontal = 8.dp)
    )

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_profile_desc)) },
            text = {
                Text(
                    stringResource(R.string.delete_profile_confirmation, profile.name)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}
