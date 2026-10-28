/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wavlin.music.R
import com.wavlin.music.ui.component.ChipsRow
import com.wavlin.music.ui.component.LocalInterfaceAppleMusic
import com.wavlin.music.ui.screens.library.LibrarySegmentedFilter

/** Period picker: a segmented control for the range type, with the concrete periods as chips underneath. */
@Composable
fun StatsPeriodSelector(
    chips: List<Pair<Int, String>>,
    options: List<Pair<OptionStats, String>>,
    selectedOption: OptionStats,
    onSelectionChange: (OptionStats) -> Unit,
    currentValue: Int,
    onValueUpdate: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LibrarySegmentedFilter(
            options = options,
            selected = selectedOption,
            onSelect = onSelectionChange,
        )
        Spacer(Modifier.height(4.dp))
        ChipsRow(
            chips = chips,
            currentValue = currentValue,
            onValueUpdate = onValueUpdate,
        )
    }
}

/** Hero card summarising the selected period: total listening time plus play, song, artist and album counts. */
@Composable
fun StatsSummaryCard(
    totalTimeMs: Long,
    plays: Int,
    songs: Int,
    artists: Int,
    albums: Int,
    modifier: Modifier = Modifier,
) {
    val appleMusic = LocalInterfaceAppleMusic.current
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(if (appleMusic) 24.dp else 28.dp)
    val totalMinutes = totalTimeMs / 60_000
    val timeText =
        if (totalMinutes >= 60) {
            stringResource(R.string.stats_duration_hours_minutes, totalMinutes / 60, totalMinutes % 60)
        } else {
            stringResource(R.string.stats_duration_minutes, totalMinutes)
        }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(colors.primaryContainer, colors.tertiaryContainer)))
            .then(
                if (appleMusic) {
                    Modifier.border(BorderStroke(0.5.dp, colors.outlineVariant), shape)
                } else {
                    Modifier
                },
            )
            .padding(20.dp),
    ) {
        Column {
            Text(
                text = stringResource(R.string.stats_listening_time),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onPrimaryContainer.copy(alpha = 0.8f),
            )
            Text(
                text = timeText,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = colors.onPrimaryContainer,
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            StatsSummaryValue(plays, stringResource(R.string.stats_plays), Modifier.weight(1f))
            StatsSummaryValue(songs, stringResource(R.string.songs), Modifier.weight(1f))
            StatsSummaryValue(artists, stringResource(R.string.artists), Modifier.weight(1f))
            StatsSummaryValue(albums, stringResource(R.string.albums), Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatsSummaryValue(
    value: Int,
    label: String,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.onPrimaryContainer
    Column(horizontalAlignment = Alignment.Start, modifier = modifier) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color.copy(alpha = 0.8f),
            maxLines = 1,
        )
    }
}
