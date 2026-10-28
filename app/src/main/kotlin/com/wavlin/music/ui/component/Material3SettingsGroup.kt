/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp

/**
 * A Material 3 Expressive style settings group component
 * @param title The title of the settings group
 * @param items List of settings items to display
 */
@Composable
fun Material3SettingsGroup(
    title: String? = null,
    items: List<Material3SettingsItem>,
    useLowContrast: Boolean = false
) {
    if (LocalInterfaceAppleMusic.current) {
        AppleSettingsGroup(title, items)
    } else {
        ClearSettingsGroup(title, items, useLowContrast)
    }
}

/**
 * Data class for Material 3 settings item
 */
data class Material3SettingsItem(
    val icon: Painter? = null,
    val leadingContent: (@Composable () -> Unit)? = null,
    val title: @Composable () -> Unit,
    val description: (@Composable () -> Unit)? = null,
    val trailingContent: (@Composable () -> Unit)? = null,
    val showBadge: Boolean = false,
    val isHighlighted: Boolean = false,
    val enabled: Boolean = true,
    val onClick: (() -> Unit)? = null
)


private val AppleTilePalette = listOf(
    Color(0xFF0A84FF), // blue
    Color(0xFFFF9F0A), // orange
    Color(0xFF30D158), // green
    Color(0xFFBF5AF2), // purple
    Color(0xFFFF375F), // pink
    Color(0xFF64D2FF), // sky
    Color(0xFFFF453A), // red
    Color(0xFF5E5CE6), // indigo
)

/**
 * Material 3 layout: one rounded container per group, rows split by inset hairlines
 * (instead of a stack of separate cards), round tinted icon badges.
 */
@Composable
private fun ClearSettingsGroup(
    title: String?,
    items: List<Material3SettingsItem>,
    useLowContrast: Boolean,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(modifier = Modifier.fillMaxWidth()) {
        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
            )
        }
        val pinkLily = LocalPinkLilyTheme.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .let { base ->
                    if (pinkLily) {
                        base.pinkGlass(shape, floating = false)
                    } else {
                        base
                            .clip(shape)
                            .background(
                                if (useLowContrast) {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)
                                },
                            )
                    }
                }
                .animateContentSize(),
        ) {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    val hasIcon = item.leadingContent != null || item.icon != null
                    HorizontalDivider(
                        modifier = Modifier.padding(start = if (hasIcon) 72.dp else 20.dp, end = 20.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    )
                }
                SettingsRow(
                    item = item,
                    index = index,
                    apple = false,
                )
            }
        }
    }
}

/**
 * Apple Music layout: iOS inset-grouped list. Small uppercase section header, 14dp rounded group,
 * colored rounded-square icon tiles, lighter titles, chevrons on rows that open something.
 */
@Composable
private fun AppleSettingsGroup(
    title: String?,
    items: List<Material3SettingsItem>,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier = Modifier.fillMaxWidth()) {
        title?.let {
            Text(
                text = it.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, letterSpacing = 0.6.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 7.dp),
            )
        }
        val pinkLily = LocalPinkLilyTheme.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .let { base ->
                    if (pinkLily) {
                        base.pinkGlass(shape, floating = false)
                    } else {
                        base
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    }
                }
                .animateContentSize(),
        ) {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    val hasIcon = item.leadingContent != null || item.icon != null
                    HorizontalDivider(
                        modifier = Modifier.padding(start = if (hasIcon) 58.dp else 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    )
                }
                SettingsRow(
                    item = item,
                    index = index,
                    apple = true,
                )
            }
        }
    }
}

@Composable
private fun SettingsRow(
    item: Material3SettingsItem,
    index: Int,
    apple: Boolean,
) {
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (apple) 52.dp else 64.dp)
            .clickable(
                enabled = item.enabled && item.onClick != null,
                onClick = { item.onClick?.invoke() },
            )
            .padding(horizontal = if (apple) 16.dp else 20.dp, vertical = if (apple) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.leadingContent != null) {
            item.leadingContent.invoke()
            Spacer(Modifier.width(if (apple) 12.dp else 16.dp))
        } else if (item.icon != null) {
            val tileModifier =
                if (apple) {
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (item.enabled) AppleTilePalette[index % AppleTilePalette.size] else disabledColor)
                } else {
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = if (item.isHighlighted) 0.24f else 0.14f),
                        )
                }
            val iconTint =
                when {
                    apple -> Color.White
                    !item.enabled -> disabledColor
                    else -> MaterialTheme.colorScheme.primary
                }
            Box(modifier = tileModifier, contentAlignment = Alignment.Center) {
                val glyph: @Composable () -> Unit = {
                    Icon(
                        painter = item.icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(if (apple) 18.dp else 22.dp),
                    )
                }
                if (item.showBadge) {
                    BadgedBox(badge = { Badge(containerColor = MaterialTheme.colorScheme.error) }) { glyph() }
                } else {
                    glyph()
                }
            }
            Spacer(Modifier.width(if (apple) 12.dp else 16.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            ProvideTextStyle(
                (if (apple) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium).copy(
                    color = if (item.enabled) MaterialTheme.colorScheme.onSurface else disabledColor,
                    fontWeight = if (apple) FontWeight.Normal else FontWeight.Medium,
                ),
            ) {
                item.title()
            }
            item.description?.let { desc ->
                Spacer(Modifier.height(2.dp))
                ProvideTextStyle(
                    (if (apple) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium).copy(
                        color = if (item.enabled) MaterialTheme.colorScheme.onSurfaceVariant else disabledColor,
                    ),
                ) {
                    desc()
                }
            }
        }

        val trailing = item.trailingContent
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        } else if (apple && item.onClick != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                painter = painterResource(com.wavlin.music.R.drawable.navigate_next),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
