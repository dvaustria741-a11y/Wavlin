/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens.mix

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.wavlin.music.LocalDatabase
import com.wavlin.music.LocalPlayerConnection
import com.wavlin.music.R
import com.wavlin.music.db.entities.PlaylistSong
import com.wavlin.music.extensions.toMediaItem
import com.wavlin.music.mix.MixAnalyzer
import com.wavlin.music.mix.MixCurves
import com.wavlin.music.mix.MixEffect
import com.wavlin.music.mix.MixEq
import com.wavlin.music.mix.MixFilter
import com.wavlin.music.mix.MixLoop
import com.wavlin.music.mix.MixPoint
import com.wavlin.music.mix.MixPreset
import com.wavlin.music.mix.MixStore
import com.wavlin.music.mix.MixTrackAnalysis
import com.wavlin.music.mix.MixTransition
import com.wavlin.music.mix.MixVolume
import com.wavlin.music.playback.queues.ListQueue
import com.wavlin.music.ui.component.GlassDialog
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val OutColor = Color(0xFFFFA726)
private val InColor = Color(0xFF42A5F5)

@OptIn(UnstableApi::class)
@Composable
fun MixEditorScreen(
    navController: NavController,
    playlistId: String,
) {
    val database = LocalDatabase.current
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val songs by database.playlistSongs(playlistId).collectAsStateWithLifecycle(initialValue = emptyList())
    val analysis by MixStore.analysis.collectAsStateWithLifecycle()
    val transitions by MixStore.transitions.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Int?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var picked by remember { mutableStateOf(setOf<Int>()) }
    val adjacentMsg = stringResource(R.string.mix_select_adjacent)

    LaunchedEffect(songs) {
        val missing = songs.map { it.song.id }.distinct().filter { MixStore.analysisOf(it) == null }
        missing.forEachIndexed { i, id ->
            progress = (i + 1) to missing.size
            MixAnalyzer.analyze(context, id)
        }
        progress = null
    }

    val pair = selected
    if (pair != null && pair + 1 < songs.size) {
        val from = songs[pair]
        val to = songs[pair + 1]
        TransitionEditor(
            from = from,
            to = to,
            aFrom = analysis[from.song.id],
            aTo = analysis[to.song.id],
            initial = transitions["$playlistId|${from.song.id}|${to.song.id}"],
            onClose = { selected = null },
            onSave = {
                MixStore.saveTransition(playlistId, it)
                selected = null
            },
            onRemove = {
                MixStore.removeTransition(playlistId, from.song.id, to.song.id)
                selected = null
            },
        )
        return
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
        ) {
            IconButton(onClick = { navController.navigateUp() }) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
            Text(
                stringResource(R.string.mix_editor_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = picked.size >= 2 && progress == null,
                onClick = {
                    var made = 0
                    for (i in 0 until songs.size - 1) {
                        if (songs[i].map.id !in picked || songs[i + 1].map.id !in picked) continue
                        val a = analysis[songs[i].song.id]
                        val b = analysis[songs[i + 1].song.id]
                        if (a != null && b != null) {
                            MixStore.saveTransition(playlistId, MixCurves.auto(a, b, a.songId, b.songId))
                            made++
                        }
                    }
                    if (made == 0) android.widget.Toast.makeText(context, adjacentMsg, android.widget.Toast.LENGTH_SHORT).show()
                },
            ) { Text(stringResource(R.string.mix_auto_selected, picked.size)) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { picked = if (picked.size == songs.size) emptySet() else songs.map { it.map.id }.toSet() }) {
                Text(stringResource(if (picked.size == songs.size && songs.isNotEmpty()) R.string.mix_clear_selection else R.string.mix_select_all))
            }
        }

        progress?.let { (i, n) ->
            Text(
                stringResource(R.string.mix_analyzing, i, n),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        if (songs.size < 2) {
            Text(
                stringResource(R.string.mix_need_two_songs),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(20.dp),
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(songs, key = { _, s -> s.map.id }) { index, song ->
                MixSongRow(
                    song = song,
                    analysis = analysis[song.song.id],
                    checked = song.map.id in picked,
                    onToggle = { picked = if (song.map.id in picked) picked - song.map.id else picked + song.map.id },
                )
                if (index < songs.size - 1) {
                    val next = songs[index + 1]
                    val t = transitions["$playlistId|${song.song.id}|${next.song.id}"]
                    val ready = analysis[song.song.id] != null && analysis[next.song.id] != null
                    TransitionChip(
                        text =
                            if (t == null) {
                                stringResource(R.string.mix_transition_none)
                            } else {
                                stringResource(R.string.mix_transition_set, presetLabel(t.preset), t.bars)
                            },
                        enabled = ready,
                        highlighted = t != null,
                        onClick = {
                            selected = index
                        },
                        onRemove = if (t != null) ({ MixStore.removeTransition(playlistId, song.song.id, next.song.id) }) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun MixSongRow(
    song: PlaylistSong,
    analysis: MixTrackAnalysis?,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 8.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        AsyncImage(
            model = song.song.song.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.song.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(
                song.song.artists.joinToString { it.name },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (analysis != null && analysis.bpm > 0f) {
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.mix_bpm, analysis.bpm.roundToInt().toString()), style = MaterialTheme.typography.labelMedium)
                if (analysis.key.isNotEmpty()) KeyBadge(analysis.key)
            }
        }
    }
}

@Composable
private fun KeyBadge(key: String) {
    val n = key.dropLast(1).toIntOrNull() ?: 1
    val color = Color.hsv(((n - 1) * 30f) % 360f, 0.55f, 0.95f)
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(key, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
    }
}

@Composable
private fun TransitionChip(
    text: String,
    enabled: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(start = 64.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(50),
            color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.graphic_eq), contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(text, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(painterResource(R.drawable.close), contentDescription = stringResource(R.string.mix_remove_transition), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun presetLabel(p: MixPreset): String =
    stringResource(
        when (p) {
            MixPreset.AUTO -> R.string.mix_preset_auto
            MixPreset.CUSTOM -> R.string.mix_preset_custom
            MixPreset.FADE -> R.string.mix_preset_fade
            MixPreset.RISE -> R.string.mix_preset_rise
            MixPreset.DROP -> R.string.mix_preset_drop
            MixPreset.ECHO -> R.string.mix_preset_echo
        },
    )

private fun volumeRes(v: MixVolume) =
    when (v) {
        MixVolume.CUSTOM -> R.string.mix_volume_custom
        MixVolume.SMOOTH_CROSSFADE -> R.string.mix_volume_smooth
        MixVolume.OVERLAP -> R.string.mix_volume_overlap
        MixVolume.FADE_IN_FADE_OUT -> R.string.mix_volume_fade_fade
        MixVolume.CUT_IN_FADE_OUT -> R.string.mix_volume_cut_fade
        MixVolume.FADE_IN_CUT_OUT -> R.string.mix_volume_fade_cut
        MixVolume.CUT_IN_CUT_OUT -> R.string.mix_volume_cut_cut
    }

private fun eqRes(v: MixEq) =
    when (v) {
        MixEq.NONE -> R.string.mix_none
        MixEq.START_BASS_SWAP -> R.string.mix_eq_start_swap
        MixEq.END_BASS_SWAP -> R.string.mix_eq_end_swap
    }

private fun filterRes(v: MixFilter) =
    when (v) {
        MixFilter.NONE -> R.string.mix_none
        MixFilter.LP_OUT -> R.string.mix_filter_lp_out
        MixFilter.LP_IN -> R.string.mix_filter_lp_in
        MixFilter.LP_IN_LP_OUT -> R.string.mix_filter_lp_in_lp_out
        MixFilter.LP_IN_HP_OUT -> R.string.mix_filter_lp_in_hp_out
        MixFilter.HP_OUT -> R.string.mix_filter_hp_out
        MixFilter.HP_IN -> R.string.mix_filter_hp_in
        MixFilter.HP_IN_HP_OUT -> R.string.mix_filter_hp_in_hp_out
        MixFilter.HP_IN_LP_OUT -> R.string.mix_filter_hp_in_lp_out
    }

private fun effectRes(v: MixEffect) =
    when (v) {
        MixEffect.NONE -> R.string.mix_none
        MixEffect.ECHO_OUT -> R.string.mix_effect_echo_out
        MixEffect.SPACE_OUT -> R.string.mix_effect_space_out
    }

private fun loopRes(v: MixLoop) =
    when (v) {
        MixLoop.NONE -> R.string.mix_none
        MixLoop.HALF_BAR -> R.string.mix_loop_half_bar
        MixLoop.ONE_BAR -> R.string.mix_loop_one_bar
        MixLoop.TWO_BARS -> R.string.mix_loop_two_bars
    }

private enum class Picker { VOLUME, EQ, FILTER, EFFECT, LOOP }

@OptIn(UnstableApi::class)
@Composable
private fun TransitionEditor(
    from: PlaylistSong,
    to: PlaylistSong,
    aFrom: MixTrackAnalysis?,
    aTo: MixTrackAnalysis?,
    initial: MixTransition?,
    onClose: () -> Unit,
    onSave: (MixTransition) -> Unit,
    onRemove: () -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current
    val isPlaying by (playerConnection?.isEffectivelyPlaying ?: return).collectAsStateWithLifecycle()
    var t by remember(from.map.id, to.map.id) {
        mutableStateOf(initial ?: MixCurves.auto(aFrom, aTo, from.song.id, to.song.id))
    }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var previewing by remember { mutableStateOf(false) }
    val canMatch =
        aFrom != null && aTo != null && aFrom.bpm > 0f && aTo.bpm > 0f && abs(aTo.bpm / aFrom.bpm - 1f) <= MixCurves.MAX_TEMPO_DRIFT

    fun custom(block: (MixTransition) -> MixTransition) {
        t = block(t).copy(preset = MixPreset.CUSTOM)
        MixStore.setPreview(null)
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                MixStore.setPreview(null)
                onClose()
            }) { Icon(painterResource(R.drawable.close), contentDescription = null) }
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.mix_beta), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = {
                MixStore.setPreview(null)
                onRemove()
            }) { Icon(painterResource(R.drawable.delete), contentDescription = stringResource(R.string.mix_remove_transition)) }
            Surface(
                onClick = {
                    MixStore.setPreview(null)
                    onSave(t)
                },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
            ) {
                Text(
                    stringResource(R.string.mix_save),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        TrackHeader(from, aFrom)
        WaveformEditor(
            t = t,
            aFrom = aFrom,
            aTo = aTo,
            onChange = { t = it.copy(preset = MixPreset.CUSTOM) },
        )
        TrackHeader(to, aTo)
        Text(
            stringResource(R.string.mix_drag_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2, 4, 8).forEach { bars ->
                Surface(
                    onClick = { custom { it.copy(bars = bars) } },
                    shape = RoundedCornerShape(14.dp),
                    color = if (t.bars == bars) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(R.string.mix_bars_count, bars),
                        color = if (t.bars == bars) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.mix_choose_length),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        Text(
            stringResource(R.string.mix_presets),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MixPreset.entries.forEach { preset ->
                val on = t.preset == preset
                Surface(
                    onClick = {
                        MixStore.setPreview(null)
                        t =
                            if (preset == MixPreset.CUSTOM) {
                                t.copy(preset = MixPreset.CUSTOM)
                            } else {
                                MixCurves.auto(aFrom, aTo, from.song.id, to.song.id, preset)
                            }
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        presetLabel(preset),
                        color = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        SettingRow(stringResource(R.string.mix_volume), stringResource(volumeRes(t.volume)), true) { picker = Picker.VOLUME }
        SettingRow(stringResource(R.string.mix_eq), stringResource(eqRes(t.eq)), true) { picker = Picker.EQ }
        SettingRow(stringResource(R.string.mix_filter), stringResource(filterRes(t.filter)), true) { picker = Picker.FILTER }
        SettingRow(stringResource(R.string.mix_effects), stringResource(effectRes(t.effect)), true) { picker = Picker.EFFECT }
        SettingRow(stringResource(R.string.mix_looping), stringResource(loopRes(t.loop)), aFrom != null && aFrom.bpm > 0f) { picker = Picker.LOOP }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.mix_beatmatching), fontWeight = FontWeight.SemiBold)
                if (!canMatch) {
                    Text(
                        stringResource(R.string.mix_beatmatching_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = t.beatmatch && canMatch, enabled = canMatch, onCheckedChange = { v -> custom { it.copy(beatmatch = v) } })
        }

        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
            Surface(
                onClick = {
                    if (previewing && isPlaying) {
                        playerConnection?.togglePlayPause()
                    } else {
                        MixStore.setPreview(t)
                        previewing = true
                        playerConnection?.playQueue(
                            ListQueue(
                                title = null,
                                items = listOf(from.song.toMediaItem(), to.song.toMediaItem()),
                                startIndex = 0,
                                position = max(0L, t.outStartMs - 4_000L),
                                mixPlaylistId = MixStore.PREVIEW_PLAYLIST_ID,
                            ),
                        )
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    painterResource(if (previewing && isPlaying) R.drawable.pause else R.drawable.play),
                    contentDescription = stringResource(if (previewing && isPlaying) R.string.mix_stop_preview else R.string.mix_preview),
                    modifier = Modifier.padding(18.dp).size(28.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    when (picker) {
        Picker.VOLUME ->
            OptionDialog(
                title = stringResource(R.string.mix_volume),
                options = MixVolume.entries.map { it to stringResource(volumeRes(it)) },
                selected = t.volume,
                onPick = { v ->
                    val (o, i) = MixCurves.volumePoints(t.copy(volume = v))
                    custom { it.copy(volume = v, customOut = o, customIn = i) }
                },
                onDismiss = { picker = null },
            )
        Picker.EQ ->
            OptionDialog(stringResource(R.string.mix_eq), MixEq.entries.map { it to stringResource(eqRes(it)) }, t.eq, { v -> custom { it.copy(eq = v) } }) { picker = null }
        Picker.FILTER ->
            OptionDialog(stringResource(R.string.mix_filter), MixFilter.entries.map { it to stringResource(filterRes(it)) }, t.filter, { v -> custom { it.copy(filter = v) } }) { picker = null }
        Picker.EFFECT ->
            OptionDialog(stringResource(R.string.mix_effects), MixEffect.entries.map { it to stringResource(effectRes(it)) }, t.effect, { v -> custom { it.copy(effect = v) } }) { picker = null }
        Picker.LOOP ->
            OptionDialog(stringResource(R.string.mix_looping), MixLoop.entries.map { it to stringResource(loopRes(it)) }, t.loop, { v -> custom { it.copy(loop = v) } }) { picker = null }
        null -> Unit
    }
}

@Composable
private fun TrackHeader(
    song: PlaylistSong,
    analysis: MixTrackAnalysis?,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = song.song.song.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.song.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(
                song.song.artists.joinToString { it.name },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (analysis != null && analysis.bpm > 0f) {
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.mix_bpm, analysis.bpm.roundToInt().toString()), style = MaterialTheme.typography.labelMedium)
                if (analysis.key.isNotEmpty()) KeyBadge(analysis.key)
            }
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.4f, fill = false))
        Icon(painterResource(R.drawable.expand_more), contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun <T> OptionDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassDialog(onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            options.forEach { (value, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onPick(value)
                                onDismiss()
                            }.padding(horizontal = 8.dp, vertical = 12.dp),
                ) {
                    Text(label, modifier = Modifier.weight(1f), fontWeight = if (value == selected) FontWeight.Bold else FontWeight.Normal)
                    if (value == selected) Icon(painterResource(R.drawable.check), contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/**
 * Two stacked waveforms (outgoing on top, incoming below) with the transition window in the middle.
 * Dragging a waveform moves where its song enters the window; dragging a curve node reshapes the volume.
 */
@Composable
private fun WaveformEditor(
    t: MixTransition,
    aFrom: MixTrackAnalysis?,
    aTo: MixTrackAnalysis?,
    onChange: (MixTransition) -> Unit,
) {
    val current by rememberUpdatedState(t)
    val latestOnChange by rememberUpdatedState(onChange)
    val outAnalysis by rememberUpdatedState(aFrom)
    val inAnalysis by rememberUpdatedState(aTo)
    val boundary = MaterialTheme.colorScheme.onSurface
    val windowFill = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val positionMs = (current.durationMs(aFrom?.bpm ?: 0f)).coerceAtLeast(1_000L)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(250.dp)
            .pointerInput(Unit) {
                var mode = 0 // 0 none, 1 out wave, 2 in wave, 3 out node, 4 in node
                var nodeIndex = -1
                detectDragGestures(
                    onDragStart = { start ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val boxLeft = w * BOX_LEFT
                        val boxW = w * BOX_WIDTH
                        val (outPts, inPts) = MixCurves.volumePoints(current)
                        fun hit(pts: List<MixPoint>): Int =
                            pts.indices.minByOrNull { i ->
                                val dx = boxLeft + pts[i].x * boxW - start.x
                                val dy = h * (0.1f + (1f - pts[i].y) * 0.8f) - start.y
                                dx * dx + dy * dy
                            }?.takeIf { i ->
                                val dx = boxLeft + pts[i].x * boxW - start.x
                                val dy = h * (0.1f + (1f - pts[i].y) * 0.8f) - start.y
                                dx * dx + dy * dy < 40f * 40f
                            } ?: -1
                        val o = hit(outPts)
                        val i = hit(inPts)
                        mode =
                            when {
                                o >= 0 -> 3.also { nodeIndex = o }
                                i >= 0 -> 4.also { nodeIndex = i }
                                start.y < h / 2f -> 1
                                else -> 2
                            }
                        if (mode >= 3) {
                            val (op, ip) = MixCurves.volumePoints(current)
                            latestOnChange(current.copy(volume = MixVolume.CUSTOM, customOut = op, customIn = ip))
                        }
                    },
                    onDragEnd = {
                        val c = current
                        val snapOut = outAnalysis
                        val snapIn = inAnalysis
                        if (mode == 1 && snapOut != null && snapOut.bpm > 0f) {
                            latestOnChange(c.copy(outStartMs = snapToBeat(c.outStartMs, snapOut)))
                        } else if (mode == 2 && snapIn != null && snapIn.bpm > 0f) {
                            latestOnChange(c.copy(inStartMs = snapToBeat(c.inStartMs, snapIn)))
                        }
                        mode = 0
                    },
                    onDragCancel = { mode = 0 },
                    onDrag = { pointer, delta ->
                        pointer.consume()
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val c = current
                        val dur = c.durationMs(outAnalysis?.bpm ?: 0f).coerceAtLeast(1_000L)
                        val pxPerMs = w * BOX_WIDTH / dur
                        when (mode) {
                            1 -> {
                                val max = (outAnalysis?.durationMs ?: Long.MAX_VALUE / 4)
                                latestOnChange(c.copy(outStartMs = (c.outStartMs - (delta.x / pxPerMs)).toLong().coerceIn(0L, max)))
                            }
                            2 -> {
                                val max = (inAnalysis?.durationMs ?: Long.MAX_VALUE / 4)
                                latestOnChange(c.copy(inStartMs = (c.inStartMs - (delta.x / pxPerMs)).toLong().coerceIn(0L, max)))
                            }
                            3, 4 -> {
                                val pts = (if (mode == 3) c.customOut else c.customIn).toMutableList()
                                if (nodeIndex in pts.indices) {
                                    val p = pts[nodeIndex]
                                    val edge = nodeIndex == 0 || nodeIndex == pts.lastIndex
                                    val nx = if (edge) p.x else (p.x + delta.x / (w * BOX_WIDTH)).coerceIn(0.02f, 0.98f)
                                    val ny = (p.y - delta.y / (h * 0.8f)).coerceIn(0f, 1f)
                                    pts[nodeIndex] = MixPoint(nx, ny)
                                    val moved = pts[nodeIndex]
                                    val sorted = pts.sortedBy { it.x }
                                    nodeIndex = sorted.indexOf(moved)
                                    latestOnChange(if (mode == 3) c.copy(customOut = sorted) else c.copy(customIn = sorted))
                                }
                            }
                        }
                    },
                )
            },
    ) {
        val w = size.width
        val h = size.height
        val boxLeft = w * BOX_LEFT
        val boxW = w * BOX_WIDTH
        val rowH = h / 2f
        val pxPerMs = boxW / positionMs

        drawRect(windowFill, Offset(boxLeft, 0f), Size(boxW, h))
        drawRect(boundary.copy(alpha = 0.35f), Offset(boxLeft, 0f), Size(boxW, h), style = Stroke(2f))

        fun wave(
            analysis: MixTrackAnalysis?,
            anchorMs: Long,
            top: Float,
            color: Color,
        ) {
            val mid = top + rowH / 2f
            if (analysis == null || analysis.peaks.isEmpty()) {
                drawLine(color.copy(alpha = 0.4f), Offset(0f, mid), Offset(w, mid), 2f)
                return
            }
            val total = max(1L, analysis.durationMs)
            var x = 0f
            while (x < w) {
                val ms = anchorMs + ((x - boxLeft) / pxPerMs).toLong()
                if (ms in 0..total) {
                    val idx = (ms * (analysis.peaks.size - 1) / total).toInt().coerceIn(0, analysis.peaks.size - 1)
                    val amp = (analysis.peaks[idx].toInt() and 0xFF) / 255f
                    val inside = x in boxLeft..(boxLeft + boxW)
                    drawLine(color.copy(alpha = if (inside) 0.95f else 0.45f), Offset(x, mid - amp * rowH * 0.45f), Offset(x, mid + amp * rowH * 0.45f), 2f)
                }
                x += 3f
            }
            if (analysis.bpm > 0f) {
                val beat = analysis.beatMs
                val firstVisible = anchorMs - (boxLeft / pxPerMs).toLong()
                var k = ((firstVisible - analysis.firstBeatMs) / beat).toLong() - 1
                while (true) {
                    val ms = analysis.firstBeatMs + k * beat
                    val px = boxLeft + ((ms - anchorMs) * pxPerMs)
                    if (px > boxLeft + boxW) break
                    if (px >= boxLeft && k >= 0) {
                        val bar = k % 4 == 0L
                        drawLine(boundary.copy(alpha = if (bar) 0.35f else 0.14f), Offset(px, top), Offset(px, top + rowH), if (bar) 2f else 1f)
                    }
                    k++
                }
            }
        }
        wave(aFrom, t.outStartMs, 0f, OutColor)
        wave(aTo, t.inStartMs, rowH, InColor)

        val (outPts, inPts) = MixCurves.volumePoints(t)
        fun curve(
            pts: List<MixPoint>,
            color: Color,
        ) {
            val path = Path()
            pts.forEachIndexed { i, p ->
                val px = boxLeft + p.x * boxW
                val py = h * (0.1f + (1f - p.y) * 0.8f)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, color, style = Stroke(5f))
            pts.forEach { p ->
                drawCircle(Color.White, 11f, Offset(boxLeft + p.x * boxW, h * (0.1f + (1f - p.y) * 0.8f)))
                drawCircle(color, 8f, Offset(boxLeft + p.x * boxW, h * (0.1f + (1f - p.y) * 0.8f)))
            }
        }
        curve(outPts, OutColor)
        curve(inPts, InColor)
    }
}

private const val BOX_LEFT = 0.2f
private const val BOX_WIDTH = 0.6f

private fun snapToBeat(
    ms: Long,
    a: MixTrackAnalysis,
): Long {
    val beat = a.beatMs
    if (beat <= 0f) return ms
    val k = ((ms - a.firstBeatMs) / beat).roundToInt()
    return (a.firstBeatMs + k * beat).toLong().coerceIn(0L, a.durationMs)
}
