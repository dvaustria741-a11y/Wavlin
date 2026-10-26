/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.wavlin.music.ui.screens.hub.HubScaffold
import com.wavlin.music.viewmodels.HubSection
import com.wavlin.music.viewmodels.YouTubeBrowseViewModel

/** A single mood (Chill, Workout, ...) rendered on the shared hub page. */
@Composable
fun MoodDetailScreen(
    navController: NavController,
    moodTitle: String?,
    viewModel: YouTubeBrowseViewModel = hiltViewModel(),
) {
    val browseResult by viewModel.result.collectAsStateWithLifecycle()
    val title = moodTitle?.takeIf(String::isNotBlank) ?: browseResult?.title.orEmpty()

    val sections =
        remember(browseResult, title) {
            browseResult?.items?.mapNotNull { section ->
                section.items
                    .takeIf { it.isNotEmpty() }
                    ?.let { HubSection(section.title?.takeIf(String::isNotBlank) ?: title, it) }
            }
        }

    HubScaffold(
        navController = navController,
        title = title,
        sections = sections,
        fallbackAccent = MaterialTheme.colorScheme.primary,
    )
}
