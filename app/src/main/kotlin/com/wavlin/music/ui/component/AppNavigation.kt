/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Spacer
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.wavlin.music.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.wavlin.music.ui.screens.Screens
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

@Immutable
private data class NavItemState(
    val isSelected: Boolean,
    val iconRes: Int
)

@Stable
private fun isRouteSelected(currentRoute: String?, screenRoute: String, navigationItems: List<Screens>): Boolean {
    if (currentRoute == null) return false
    if (currentRoute == screenRoute) return true
    if (navigationItems.any { it.route == screenRoute } &&
        currentRoute.startsWith("$screenRoute/")) return true

    // Fix: match the route template, not the resolved route
    if (screenRoute == "search_input" &&
        (currentRoute.startsWith("search/") || currentRoute == "search/{query}")) return true

    return false
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AppNavigationRail(
    navigationItems: List<Screens>,
    currentRoute: String?,
    onItemClick: (Screens, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    pureBlack: Boolean = false,
    onSearchLongClick: (() -> Unit)? = null
) {
    val haptics = LocalHapticFeedback.current
    val viewConfiguration = LocalViewConfiguration.current
    val hazeState = LocalHazeState.current

    NavigationRail(
        modifier = modifier.hazeEffect(state = hazeState, style = if (pureBlack) pureBlackHazeStyle() else HazeMaterials.thin()),
        containerColor = Color.Transparent
    ) {
        Spacer(modifier = Modifier.weight(1f))

        navigationItems.forEach { screen ->
            val isSelected = remember(currentRoute, screen.route) {
                isRouteSelected(currentRoute, screen.route, navigationItems)
            }
            val currentIsSelected by rememberUpdatedState(isSelected)
            val iconRes = remember(isSelected, screen) {
                if (isSelected) screen.iconIdActive else screen.iconIdInactive
            }

            val isSearchItem = screen == Screens.Search && onSearchLongClick != null
            val interactionSource = remember { MutableInteractionSource() }

            // Long press detection using InteractionSource
            if (isSearchItem) {
                LaunchedEffect(interactionSource) {
                    var isLongClick = false
                    interactionSource.interactions.collectLatest { interaction ->
                        when (interaction) {
                            is PressInteraction.Press -> {
                                isLongClick = false
                                delay(viewConfiguration.longPressTimeoutMillis)
                                isLongClick = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSearchLongClick.invoke()
                            }
                            is PressInteraction.Release -> {
                                if (!isLongClick) {
                                    onItemClick(screen, currentIsSelected)
                                }
                            }
                            is PressInteraction.Cancel -> {
                                isLongClick = false
                            }
                        }
                    }
                }
            }

            NavigationRailItem(
                selected = isSelected,
                onClick = {
                    if (!isSearchItem) {
                        onItemClick(screen, currentIsSelected)
                    }
                    // For search item, click is handled via InteractionSource
                },
                interactionSource = interactionSource,
                icon = {
                    Icon(
                        painter = painterResource(id = iconRes),
                        contentDescription = stringResource(screen.titleId)
                    )
                }
            )
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AppNavigationBar(
    navigationItems: List<Screens>,
    currentRoute: String?,
    onItemClick: (Screens, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    pureBlack: Boolean = false,
    slimNav: Boolean = false,
    onSearchLongClick: (() -> Unit)? = null
) {
    // Pure black is an explicit OLED preference - keep a heavy black tint, but still blur
    // through it rather than falling back to a flat, zero-variation fill.
    val contentColor = if (pureBlack) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val hazeState = LocalHazeState.current

    if (LocalInterfaceAppleMusic.current) {
        AppleFloatingTabBar(
            navigationItems = navigationItems,
            currentRoute = currentRoute,
            onItemClick = onItemClick,
            modifier = modifier,
            slimNav = slimNav,
            onSearchLongClick = onSearchLongClick,
        )
        return
    }

    // Material 3: edge-to-edge frosted bar. Secret Pink only swaps the haze tint, it is the same glass.
    val pinkLily = LocalPinkLilyTheme.current
    NavigationBar(
        modifier = modifier
            .hazeEffect(
                state = hazeState,
                style = when {
                    pinkLily -> pinkLilyNavHazeStyle()
                    pureBlack -> pureBlackHazeStyle()
                    else -> HazeMaterials.thin()
                },
            )
            .glassTopEdgeGlow(),
        containerColor = Color.Transparent,
        contentColor = contentColor
    ) {
        MaterialGlideTabs(
            navigationItems = navigationItems,
            currentRoute = currentRoute,
            onItemClick = onItemClick,
            slimNav = slimNav,
            onSearchLongClick = onSearchLongClick,
        )
    }
}

/**
 * Apple Music style: a single floating glass capsule holding every tab (Home, Search, Library)
 * as an equal item, instead of the full-width Material 3 navigation bar.
 */
/**
 * Shared state for the gliding tab pill: the pill and the page transition start on the same frame and use the same
 * spring stiffness, so the page glides together with the pill.
 */
@Stable
private class TabGlide(
    val pillPos: Animatable<Float, AnimationVector1D>,
    val targetIndex: Int,
    private val onTap: (Int, () -> Unit) -> Unit,
) {
    fun tap(index: Int, navigate: () -> Unit) = onTap(index, navigate)
}

@Composable
private fun rememberTabGlide(foundIndex: Int): TabGlide {
    val scope = rememberCoroutineScope()
    val lastIndex = remember { IntArray(1) }
    if (foundIndex >= 0) lastIndex[0] = foundIndex

    var pendingIndex by remember { mutableStateOf<Int?>(null) }
    var navJob by remember { mutableStateOf<Job?>(null) }
    val targetIndex = pendingIndex ?: lastIndex[0]

    val pillPos = remember { Animatable(lastIndex[0].toFloat()) }
    LaunchedEffect(targetIndex) {
        pillPos.animateTo(
            targetValue = targetIndex.toFloat(),
            animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        )
    }
    LaunchedEffect(foundIndex) { pendingIndex = null }

    return remember(pillPos, targetIndex) {
        TabGlide(pillPos, targetIndex) { index, navigate ->
            pendingIndex = index
            navJob?.cancel()
            navJob = scope.launch {
                navigate()
                delay(800)
                pendingIndex = null
            }
        }
    }
}

/** Material 3 look with ONE pill that glides across the tabs (instead of one indicator fading per tab). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RowScope.MaterialGlideTabs(
    navigationItems: List<Screens>,
    currentRoute: String?,
    onItemClick: (Screens, Boolean) -> Unit,
    slimNav: Boolean,
    onSearchLongClick: (() -> Unit)?,
) {
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val foundIndex = navigationItems.indexOfFirst { isRouteSelected(currentRoute, it.route, navigationItems) }
    val glide = rememberTabGlide(foundIndex)
    val pillColor = if (LocalPinkLilyTheme.current) PinkGlassTint.copy(alpha = 0.45f) else colors.secondaryContainer
    val count = navigationItems.size.coerceAtLeast(1)

    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val tabWidth = size.width / count
                    val pillWidth = 64.dp.toPx()
                    val pillHeight = 32.dp.toPx()
                    val contentHeight = if (slimNav) pillHeight else pillHeight + 20.dp.toPx()
                    val x = glide.pillPos.value * tabWidth + (tabWidth - pillWidth) / 2f
                    val y = (size.height - contentHeight) / 2f
                    drawRoundRect(
                        color = pillColor,
                        topLeft = Offset(x, y),
                        size = Size(pillWidth, pillHeight),
                        cornerRadius = CornerRadius(pillHeight / 2f),
                    )
                },
        ) {
            navigationItems.forEachIndexed { index, screen ->
                val routeSelected = isRouteSelected(currentRoute, screen.route, navigationItems)
                val visuallySelected = index == glide.targetIndex
                val iconTint by animateColorAsState(
                    targetValue = if (visuallySelected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                    animationSpec = tween(260),
                    label = "tabIconTint",
                )
                val textTint by animateColorAsState(
                    targetValue = if (visuallySelected) colors.onSurface else colors.onSurfaceVariant,
                    animationSpec = tween(260),
                    label = "tabTextTint",
                )
                val longClick = if (screen == Screens.Search) onSearchLongClick else null

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                if (routeSelected) {
                                    onItemClick(screen, true)
                                } else {
                                    glide.tap(index) { onItemClick(screen, false) }
                                }
                            },
                            onLongClick = longClick?.let { cb ->
                                {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    cb()
                                }
                            },
                        )
                        .semantics {
                            role = Role.Tab
                            selected = visuallySelected
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier.size(width = 64.dp, height = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(id = if (visuallySelected) screen.iconIdActive else screen.iconIdInactive),
                            contentDescription = stringResource(screen.titleId),
                            tint = iconTint,
                        )
                    }
                    if (!slimNav) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(modifier = Modifier.height(16.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(screen.titleId),
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                fontWeight = if (visuallySelected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = textTint,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AppleFloatingTabBar(
    navigationItems: List<Screens>,
    currentRoute: String?,
    onItemClick: (Screens, Boolean) -> Unit,
    modifier: Modifier,
    slimNav: Boolean,
    onSearchLongClick: (() -> Unit)?,
) {
    val barHeight: Dp = if (slimNav) 52.dp else 60.dp
    val haptics = LocalHapticFeedback.current
    val foundIndex = navigationItems.indexOfFirst { isRouteSelected(currentRoute, it.route, navigationItems) }
    val glide = rememberTabGlide(foundIndex)

    Row(
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(barHeight)
                .let { base ->
                    if (LocalPinkLilyTheme.current) base.pinkGlass(CircleShape) else base.appleGlass(CircleShape, HazeMaterials.thin())
                }
                .padding(6.dp),
        ) {
            val tabWidth = maxWidth / navigationItems.size.coerceAtLeast(1)
            val indicatorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)

            Box(
                modifier = Modifier
                    .offset { IntOffset((glide.pillPos.value * tabWidth.toPx()).roundToInt(), 0) }
                    .width(tabWidth)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(indicatorColor),
            )

            Row(modifier = Modifier.fillMaxSize()) {
                navigationItems.forEachIndexed { index, screen ->
                    val routeSelected = isRouteSelected(currentRoute, screen.route, navigationItems)
                    val visuallySelected = index == glide.targetIndex
                    val longClick = if (screen == Screens.Search) onSearchLongClick else null
                    val tint by animateColorAsState(
                        targetValue = if (visuallySelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(260),
                        label = "tabTint",
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    if (routeSelected) {
                                        onItemClick(screen, true)
                                    } else {
                                        glide.tap(index) { onItemClick(screen, false) }
                                    }
                                },
                                onLongClick = longClick?.let { cb ->
                                    {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        cb()
                                    }
                                },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            painter = painterResource(id = if (visuallySelected) screen.iconIdActive else screen.iconIdInactive),
                            contentDescription = stringResource(screen.titleId),
                            modifier = Modifier.size(22.dp),
                            tint = tint,
                        )
                        if (!slimNav) {
                            Text(
                                text = stringResource(screen.titleId),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = tint,
                            )
                        }
                    }
                }
            }
        }
    }
}
