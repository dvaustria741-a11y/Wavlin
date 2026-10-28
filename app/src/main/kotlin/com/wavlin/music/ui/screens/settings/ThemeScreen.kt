package com.wavlin.music.ui.screens.settings

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import com.wavlin.music.R
import com.wavlin.music.constants.DarkModeKey
import com.wavlin.music.constants.DynamicThemeKey
import com.wavlin.music.constants.PinkNowPlayingModifiedKey
import com.wavlin.music.constants.PreviousDarkModeBeforeSecretPinkKey
import com.wavlin.music.constants.PureBlackKey
import com.wavlin.music.constants.PureBlackMiniPlayerKey
import com.wavlin.music.constants.SecretPinkThemeUnlockedKey
import com.wavlin.music.constants.SelectedThemeColorKey
import com.wavlin.music.ui.component.TextFieldDialog
import com.wavlin.music.ui.component.pinkTopBarColors
import com.wavlin.music.ui.theme.DefaultThemeColor
import com.wavlin.music.ui.theme.SecretPinkThemeColor
import com.wavlin.music.ui.theme.WavlinTheme
import com.wavlin.music.utils.rememberEnumPreference
import com.wavlin.music.utils.rememberPreference

data class ThemePalette(
    val nameRes: Int,
    val seedColor: Color
)

val PaletteColors = listOf(
    ThemePalette(R.string.palette_dynamic, Color.Transparent),
    ThemePalette(R.string.palette_crimson, Color(0xFFEC5464)),
    ThemePalette(R.string.palette_rose, Color(0xFFD81B60)),
    ThemePalette(R.string.palette_purple, Color(0xFF8E24AA)),
    ThemePalette(R.string.palette_deep_purple, Color(0xFF5E35B1)),
    ThemePalette(R.string.palette_indigo, Color(0xFF3949AB)),
    ThemePalette(R.string.palette_blue, Color(0xFF1E88E5)),
    ThemePalette(R.string.palette_sky_blue, Color(0xFF039BE5)),
    ThemePalette(R.string.palette_cyan, Color(0xFF00ACC1)),
    ThemePalette(R.string.palette_teal, Color(0xFF00897B)),
    ThemePalette(R.string.palette_green, Color(0xFF43A047)),
    ThemePalette(R.string.palette_light_green, Color(0xFF7CB342)),
    ThemePalette(R.string.palette_lime, Color(0xFFC0CA33)),
    ThemePalette(R.string.palette_yellow, Color(0xFFFDD835)),
    ThemePalette(R.string.palette_amber, Color(0xFFFFB300)),
    ThemePalette(R.string.palette_orange, Color(0xFFFB8C00)),
    ThemePalette(R.string.palette_deep_orange, Color(0xFFF4511E)),
    ThemePalette(R.string.palette_brown, Color(0xFF6D4C41)),
    ThemePalette(R.string.palette_grey, Color(0xFF757575)),
    ThemePalette(R.string.palette_blue_grey, Color(0xFF546E7A)),
)

private const val PALETTE_COLUMNS = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeScreen(
    navController: NavController,
) {
    val (darkMode, onDarkModeChange) = rememberEnumPreference(DarkModeKey, DarkMode.AUTO)
    val (pureBlack, onPureBlackChangeRaw) = rememberPreference(PureBlackKey, defaultValue = false)
    val (_, onPureBlackMiniPlayerChange) = rememberPreference(
        PureBlackMiniPlayerKey,
        defaultValue = false
    )
    val onPureBlackChange: (Boolean) -> Unit = { enabled ->
        onPureBlackChangeRaw(enabled)
        onPureBlackMiniPlayerChange(enabled)
    }
    val (selectedThemeColorInt, onSelectedThemeColorChange) = rememberPreference(
        SelectedThemeColorKey,
        DefaultThemeColor.toArgb()
    )
    val (_, onDynamicThemeChange) = rememberPreference(DynamicThemeKey, defaultValue = true)
    val selectedThemeColor = Color(selectedThemeColorInt)

    val handleColorSelection: (Color) -> Unit = { color ->
        onSelectedThemeColorChange(color.toArgb())
        onDynamicThemeChange(color == DefaultThemeColor)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            colors = pinkTopBarColors(),
            title = { Text(stringResource(R.string.theme_colors)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = stringResource(R.string.cd_back)
                    )
                }
            }
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            ThemeLivePreview(
                darkMode = darkMode,
                pureBlack = pureBlack,
                themeColor = selectedThemeColor,
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
            )

            ThemeSection(title = stringResource(R.string.theme_mode)) {
                ThemeModeSelector(
                    darkMode = darkMode,
                    pureBlack = pureBlack,
                    onDarkModeChange = onDarkModeChange,
                    onPureBlackChange = onPureBlackChange
                )
            }

            ThemeSection(title = stringResource(R.string.color_palette)) {
                ThemePaletteGrid(
                    darkMode = darkMode,
                    selectedThemeColor = selectedThemeColor,
                    onDarkModeChange = onDarkModeChange,
                    onSelectedThemeColorChange = handleColorSelection
                )
            }

            AnimatedVisibility(
                visible = selectedThemeColor == SecretPinkThemeColor,
                enter = fadeIn(tween(250)) + expandVertically(),
                exit = fadeOut(tween(150)) + shrinkVertically()
            ) {
                var pinkNowPlayingModified by rememberPreference(PinkNowPlayingModifiedKey, defaultValue = true)
                ThemeSection(title = stringResource(R.string.pink_now_playing_style)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(
                            selected = !pinkNowPlayingModified,
                            onClick = { pinkNowPlayingModified = false },
                            label = { Text(stringResource(R.string.pink_now_playing_default)) }
                        )
                        FilterChip(
                            selected = pinkNowPlayingModified,
                            onClick = { pinkNowPlayingModified = true },
                            label = { Text(stringResource(R.string.pink_now_playing_modified)) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(140.dp))
        }
    }
}

@Composable
private fun ThemeSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp)
        )
        content()
    }
}

@Composable
private fun ThemeLivePreview(
    darkMode: DarkMode,
    pureBlack: Boolean,
    themeColor: Color,
    modifier: Modifier = Modifier
) {
    val isSystemDark = isSystemInDarkTheme()
    val useDark = when (darkMode) {
        DarkMode.AUTO -> isSystemDark
        DarkMode.ON -> true
        DarkMode.OFF -> false
    }

    WavlinTheme(
        darkTheme = useDark,
        pureBlack = pureBlack,
        themeColor = themeColor
    ) {
        val cs = MaterialTheme.colorScheme
        val pill = RoundedCornerShape(50)
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(28.dp))
                .background(cs.surface)
                .border(1.dp, cs.outlineVariant, RoundedCornerShape(28.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(84.dp)
                        .height(14.dp)
                        .clip(pill)
                        .background(cs.onSurface.copy(alpha = 0.85f))
                )
                Spacer(modifier = Modifier.weight(1f))
                Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(cs.secondaryContainer))
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(cs.primaryContainer))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val tileColors = listOf(cs.primary, cs.secondaryContainer, cs.tertiaryContainer)
                val lineColors = listOf(cs.onPrimary, cs.onSecondaryContainer, cs.onTertiaryContainer)
                for (i in 0 until 3) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(tileColors[i]),
                        contentAlignment = Alignment.BottomStart
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(10.dp)
                                .fillMaxWidth(0.6f)
                                .height(8.dp)
                                .clip(pill)
                                .background(lineColors[i].copy(alpha = 0.75f))
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(cs.surfaceContainerHigh)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(cs.primary))
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height(10.dp)
                            .clip(pill)
                            .background(cs.onSurface)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.35f)
                            .height(8.dp)
                            .clip(pill)
                            .background(cs.onSurfaceVariant)
                    )
                }
                Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(cs.primaryContainer))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(cs.surfaceContainer)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (i in 0 until 3) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .clip(CircleShape)
                            .background(if (i == 0) cs.onSurface.copy(alpha = 0.14f) else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (i == 0) cs.primary else cs.onSurfaceVariant)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeModeSelector(
    darkMode: DarkMode,
    pureBlack: Boolean,
    onDarkModeChange: (DarkMode) -> Unit,
    onPureBlackChange: (Boolean) -> Unit
) {
    val labels = listOf(
        R.string.theme_mode_auto,
        R.string.theme_mode_light,
        R.string.theme_mode_dark,
        R.string.theme_mode_black
    )
    val selectedIndex = when {
        darkMode == DarkMode.AUTO -> 0
        darkMode == DarkMode.OFF -> 1
        pureBlack -> 3
        else -> 2
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp)
    ) {
        val segmentWidth = maxWidth / labels.size
        val thumbX by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "modeThumb"
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(thumbX.roundToPx(), 0) }
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
        )

        Row(modifier = Modifier.fillMaxSize()) {
            labels.forEachIndexed { index, labelRes ->
                val selected = index == selectedIndex
                val textColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(250),
                    label = "modeLabel"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            when (index) {
                                0 -> onDarkModeChange(DarkMode.AUTO)
                                1 -> {
                                    onDarkModeChange(DarkMode.OFF)
                                    onPureBlackChange(false)
                                }
                                2 -> {
                                    onDarkModeChange(DarkMode.ON)
                                    onPureBlackChange(false)
                                }
                                else -> {
                                    onDarkModeChange(DarkMode.ON)
                                    onPureBlackChange(true)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = textColor,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemePaletteGrid(
    darkMode: DarkMode,
    selectedThemeColor: Color,
    onDarkModeChange: (DarkMode) -> Unit,
    onSelectedThemeColorChange: (Color) -> Unit
) {
    val context = LocalContext.current
    val isSystemDark = isSystemInDarkTheme()
    val previewDark = when (darkMode) {
        DarkMode.AUTO -> isSystemDark
        DarkMode.ON -> true
        DarkMode.OFF -> false
    }

    var secretPinkUnlocked by rememberPreference(SecretPinkThemeUnlockedKey, defaultValue = false)
    var showUnlockDialog by remember { mutableStateOf(false) }
    var previousDarkModeName by rememberPreference(
        PreviousDarkModeBeforeSecretPinkKey,
        defaultValue = DarkMode.AUTO.name
    )
    val isCurrentlyPink = selectedThemeColor == SecretPinkThemeColor

    val cells = buildList<@Composable () -> Unit> {
        add {
            if (secretPinkUnlocked) {
                ThemeSwatch(
                    label = stringResource(R.string.palette_secret_pink),
                    contentDescription = stringResource(
                        R.string.cd_palette_item,
                        stringResource(R.string.palette_secret_pink)
                    ),
                    isSelected = isCurrentlyPink,
                    seedColor = SecretPinkThemeColor,
                    previewDark = previewDark,
                    onClick = {
                        if (!isCurrentlyPink) {
                            previousDarkModeName = darkMode.name
                            onDarkModeChange(DarkMode.OFF)
                        }
                        onSelectedThemeColorChange(SecretPinkThemeColor)
                    }
                )
            } else {
                ThemeLockedSwatch(onClick = { showUnlockDialog = true })
            }
        }
        PaletteColors.forEach { palette ->
            add {
                val isDynamicPalette = palette.seedColor == Color.Transparent
                val isSelected = if (isDynamicPalette) {
                    selectedThemeColor == DefaultThemeColor
                } else {
                    selectedThemeColor == palette.seedColor
                }
                val name = stringResource(palette.nameRes)
                ThemeSwatch(
                    label = name,
                    contentDescription = stringResource(R.string.cd_palette_item, name),
                    isSelected = isSelected,
                    seedColor = if (isDynamicPalette) null else palette.seedColor,
                    previewDark = previewDark,
                    onClick = {
                        val colorToSave = if (isDynamicPalette) DefaultThemeColor else palette.seedColor
                        if (isCurrentlyPink) {
                            val restoredMode = runCatching { DarkMode.valueOf(previousDarkModeName) }
                                .getOrDefault(DarkMode.AUTO)
                            onDarkModeChange(restoredMode)
                        }
                        onSelectedThemeColorChange(colorToSave)
                    }
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 8.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        cells.chunked(PALETTE_COLUMNS).forEach { rowCells ->
            Row(modifier = Modifier.fillMaxWidth()) {
                rowCells.forEach { cell ->
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        cell()
                    }
                }
                repeat(PALETTE_COLUMNS - rowCells.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }

    if (showUnlockDialog) {
        TextFieldDialog(
            icon = {
                Icon(
                    painter = painterResource(R.drawable.lock),
                    contentDescription = null
                )
            },
            title = { Text(stringResource(R.string.secret_theme_unlock_title)) },
            placeholder = { Text(stringResource(R.string.secret_theme_unlock_placeholder)) },
            isInputValid = { it.isNotBlank() },
            onDismiss = { showUnlockDialog = false },
            autoDismiss = false,
            onDone = { input ->
                if (input.trim().equals("pink", ignoreCase = true)) {
                    secretPinkUnlocked = true
                    if (!isCurrentlyPink) {
                        previousDarkModeName = darkMode.name
                        onDarkModeChange(DarkMode.OFF)
                    }
                    onSelectedThemeColorChange(SecretPinkThemeColor)
                    showUnlockDialog = false
                    Toast.makeText(context, R.string.secret_theme_unlocked_toast, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, R.string.secret_theme_incorrect_toast, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

@Composable
private fun ThemeSwatch(
    label: String,
    contentDescription: String,
    isSelected: Boolean,
    seedColor: Color?,
    previewDark: Boolean,
    onClick: () -> Unit
) {
    val ringWidth by animateDpAsState(
        targetValue = if (isSelected) 3.dp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "swatchRing"
    )
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.06f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "swatchScale"
    )
    val labelColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(250),
        label = "swatchLabel"
    )

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick
            )
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .semantics { this.contentDescription = contentDescription },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .then(
                    if (ringWidth > 0.dp) {
                        Modifier.border(ringWidth, MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier
                    }
                )
                .padding(if (ringWidth > 0.dp) 5.dp else 0.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (seedColor == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.palette),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                val scheme = rememberDynamicColorScheme(
                    seedColor = seedColor,
                    isDark = previewDark,
                    style = PaletteStyle.TonalSpot
                )
                val primary = scheme.primary
                val secondary = scheme.secondary
                val tertiary = scheme.tertiary
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawArc(primary, startAngle = -90f, sweepAngle = 120f, useCenter = true)
                    drawArc(secondary, startAngle = 30f, sweepAngle = 120f, useCenter = true)
                    drawArc(tertiary, startAngle = 150f, sweepAngle = 120f, useCenter = true)
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = isSelected,
                enter = fadeIn(tween(200)) + scaleIn(
                    initialScale = 0.3f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ),
                exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.3f, animationSpec = tween(120))
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.check),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ThemeLockedSwatch(
    onClick: () -> Unit
) {
    val description = stringResource(R.string.secret_theme_locked_cd)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick
            )
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.lock),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = stringResource(R.string.palette_locked_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}
