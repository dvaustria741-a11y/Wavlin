/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens.hub

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.wavlin.music.R
import com.wavlin.music.viewmodels.CategoryHubViewModel
import com.wavlin.music.viewmodels.HubKind
import com.wavlin.music.viewmodels.HubSection
import com.wavlin.music.viewmodels.NewReleaseViewModel

/** New releases, charts and podcasts all share this page; [kind] picks what it loads. */
@Composable
fun CategoryHubScreen(
    navController: NavController,
    kind: HubKind,
    viewModel: CategoryHubViewModel = hiltViewModel(),
) {
    LaunchedEffect(kind) { viewModel.load(kind) }
    val loaded by viewModel.sections.collectAsStateWithLifecycle()

    val newReleaseViewModel = if (kind == HubKind.NEW_RELEASES) hiltViewModel<NewReleaseViewModel>() else null
    val newAlbums = newReleaseViewModel?.newReleaseAlbums?.collectAsStateWithLifecycle()?.value.orEmpty()
    val albumsTitle = stringResource(R.string.hub_new_albums)
    val sections =
        remember(loaded, newAlbums, albumsTitle) {
            val base = loaded
            if (base == null || newAlbums.isEmpty()) {
                base
            } else {
                base.toMutableList().apply { add(minOf(1, size), HubSection(albumsTitle, newAlbums)) }
            }
        }

    HubScaffold(
        navController = navController,
        title =
            stringResource(
                when (kind) {
                    HubKind.NEW_RELEASES -> R.string.new_releases
                    HubKind.CHARTS -> R.string.charts
                    HubKind.PODCASTS -> R.string.filter_podcasts
                },
            ),
        sections = sections,
        fallbackAccent =
            when (kind) {
                HubKind.NEW_RELEASES -> Color(0xFF7C6CF0)
                HubKind.CHARTS -> Color(0xFFE5A33B)
                HubKind.PODCASTS -> Color(0xFF2BB3A0)
            },
        onRetry = { viewModel.load(kind, force = true) },
    )
}
