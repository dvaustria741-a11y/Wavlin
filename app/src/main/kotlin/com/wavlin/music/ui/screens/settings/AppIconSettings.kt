package com.wavlin.music.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.wavlin.music.LocalPlayerAwareWindowInsets
import com.wavlin.music.R
import com.wavlin.music.ui.component.IconButton
import com.wavlin.music.ui.component.pinkTopBarColors
import com.wavlin.music.ui.utils.backToMain
import com.wavlin.music.utils.AppIcon
import com.wavlin.music.utils.IconUtils

private data class IconOption(
    val icon: AppIcon,
    val label: Int,
    val background: Int,
    val foreground: Int,
)

private val iconOptions = listOf(
    IconOption(AppIcon.DEFAULT, R.string.app_icon_default, R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground),
    IconOption(AppIcon.PINK, R.string.app_icon_pink, R.drawable.ic_launcher_pink_background, R.drawable.ic_launcher_pink_foreground),
    IconOption(AppIcon.RED, R.string.app_icon_red, R.drawable.ic_launcher_red_background, R.drawable.ic_launcher_red_foreground),
    IconOption(AppIcon.BLACK, R.string.app_icon_black, R.drawable.ic_launcher_black_background, R.drawable.ic_launcher_black_foreground),
    IconOption(AppIcon.CLASSIC, R.string.app_icon_classic, R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppIconSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(IconUtils.getCurrentIcon(context)) }

    Column(
        modifier = Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.app_icon_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        iconOptions.chunked(2).forEach { rowOptions ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowOptions.forEach { option ->
                    IconOptionCard(
                        option = option,
                        isSelected = selected == option.icon,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            IconUtils.setAppIcon(context, option.icon)
                            // Re-read from the system so the check mark reflects what the launcher uses
                            selected = IconUtils.getCurrentIcon(context)
                        },
                    )
                }
                if (rowOptions.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        Text(
            text = stringResource(R.string.app_icon_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Spacer(Modifier.height(27.dp))
    }

    TopAppBar(
        colors = pinkTopBarColors(),
        title = { Text(stringResource(R.string.app_icon)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun IconOptionCard(
    option: IconOption,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Adaptive icon layers are 108dp with a 72dp visible window, so scale 72 -> 108
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(22.dp))
            ) {
                val layer = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.5f; scaleY = 1.5f }
                Image(
                    painter = painterResource(option.background),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = layer,
                )
                Image(
                    painter = painterResource(option.foreground),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = layer,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(option.label),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (isSelected) {
                Spacer(Modifier.height(4.dp))
                Icon(
                    painter = painterResource(R.drawable.check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
