/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens.library

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wavlin.music.R
import com.wavlin.music.constants.LibraryFilter
import com.wavlin.music.ui.component.LocalInterfaceAppleMusic

@Immutable
data class LibraryCategory(
    val filter: LibraryFilter,
    @DrawableRes val iconRes: Int,
    val label: String,
)

@Immutable
data class LibraryCollection(
    val key: String,
    val title: String,
    @DrawableRes val iconRes: Int,
    val route: String,
    val colorSlot: Int,
)

/** Three-by-two grid of tappable tiles that replaces the old category list / chip row on the library root. */
@Composable
fun LibraryCategoryGrid(
    categories: List<LibraryCategory>,
    onSelect: (LibraryFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val appleMusic = LocalInterfaceAppleMusic.current
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        categories.chunked(CATEGORY_COLUMNS).forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                rowItems.forEach { category ->
                    LibraryCategoryTile(
                        category = category,
                        appleMusic = appleMusic,
                        onClick = { onSelect(category.filter) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(CATEGORY_COLUMNS - rowItems.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LibraryCategoryTile(
    category: LibraryCategory,
    appleMusic: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(if (appleMusic) 20.dp else 28.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .height(88.dp)
            .clip(shape)
            .background(if (appleMusic) colors.surfaceContainer else colors.surfaceContainerHigh)
            .then(
                if (appleMusic) {
                    Modifier.border(BorderStroke(0.5.dp, colors.outlineVariant), shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .background(colors.primaryContainer, CircleShape),
        ) {
            Icon(
                painter = painterResource(category.iconRes),
                contentDescription = null,
                tint = colors.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = category.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Horizontally scrolling shelf for the automatic playlists (Liked, Downloaded, Cached, Top, Uploaded). */
@Composable
fun LibraryCollectionsRow(
    collections: List<LibraryCollection>,
    onClick: (LibraryCollection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val appleMusic = LocalInterfaceAppleMusic.current
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.library_collections_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(collections, key = { it.key }) { collection ->
                LibraryCollectionCard(
                    collection = collection,
                    appleMusic = appleMusic,
                    onClick = { onClick(collection) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LibraryCollectionCard(
    collection: LibraryCollection,
    appleMusic: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(if (appleMusic) 22.dp else 26.dp)
    val (start, end) = when (collection.colorSlot % 3) {
        0 -> colors.primaryContainer to colors.tertiaryContainer
        1 -> colors.secondaryContainer to colors.primaryContainer
        else -> colors.tertiaryContainer to colors.secondaryContainer
    }
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .width(150.dp)
            .height(104.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(start, end)))
            .then(
                if (appleMusic) {
                    Modifier.border(BorderStroke(0.5.dp, colors.outlineVariant), shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Icon(
            painter = painterResource(collection.iconRes),
            contentDescription = null,
            tint = colors.onPrimaryContainer,
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = collection.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = colors.onPrimaryContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val CATEGORY_COLUMNS = 3
