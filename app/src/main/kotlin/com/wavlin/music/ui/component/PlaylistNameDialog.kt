/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.wavlin.music.R
import kotlinx.coroutines.delay

/**
 * Asks for the name of a new playlist. A name that another playlist already uses is refused with
 * "This name already exists", so the user picks a different one instead of ending up with two.
 */
@Composable
fun PlaylistNameDialog(
    initialName: String,
    existingNames: Collection<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(TextFieldValue(initialName, selection = TextRange(initialName.length))) }
    val focusRequester = remember { FocusRequester() }
    val trimmed = name.text.trim()
    val alreadyExists = trimmed.isNotEmpty() && existingNames.any { it.trim().equals(trimmed, ignoreCase = true) }
    val canSave = trimmed.isNotEmpty() && !alreadyExists

    LaunchedEffect(Unit) {
        delay(300)
        focusRequester.requestFocus()
    }

    GlassDialog(onDismiss = onDismiss, maxWidth = 340.dp) {
        Text(
            text = stringResource(R.string.spotify_save_as_new),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(Modifier.height(16.dp))

        TextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            isError = alreadyExists,
            placeholder = { Text(stringResource(R.string.playlist_name)) },
            supportingText = if (alreadyExists) ({ Text(stringResource(R.string.playlist_name_already_exists)) }) else null,
            shape = RoundedCornerShape(16.dp),
            colors =
                TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
                    errorContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
                ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSave) onConfirm(trimmed) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        )

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            Spacer(Modifier.width(4.dp))
            FilledTonalButton(enabled = canSave, onClick = { onConfirm(trimmed) }) {
                Text(stringResource(R.string.save))
            }
        }
    }
}
