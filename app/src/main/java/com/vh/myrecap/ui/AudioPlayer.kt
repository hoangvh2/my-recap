package com.vh.myrecap.ui

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vh.myrecap.core.TimeFormat
import kotlinx.coroutines.delay
import java.io.File

/**
 * Plays one or more audio files back to back (a capture or a clip), with a seek bar over their
 * total length. Holds a MediaPlayer only while on screen.
 */
private class PartsPlayer(private val parts: List<Pair<File, Long>>) {
    private var player: MediaPlayer? = null
    private var part = 0
    var onEnded: () -> Unit = {}

    val totalMs: Long = parts.sumOf { it.second }.coerceAtLeast(1)

    private fun offsetOf(index: Int) = parts.take(index).sumOf { it.second }

    val positionMs: Long get() = offsetOf(part) + (player?.let { runCatching { it.currentPosition.toLong() }.getOrNull() } ?: 0L)

    val isPlaying: Boolean get() = player?.let { runCatching { it.isPlaying }.getOrNull() } == true

    fun play(fromMs: Long = positionMs) {
        var index = 0
        var offset = fromMs.coerceIn(0, totalMs)
        while (index < parts.size - 1 && offset >= parts[index].second) {
            offset -= parts[index].second
            index++
        }
        open(index)
        player?.seekTo(offset.toInt())
        player?.start()
    }

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
    }

    fun seek(toMs: Long) {
        val playing = isPlaying
        if (playing) play(toMs) else {
            play(toMs)
            pause()
        }
    }

    private fun open(index: Int) {
        if (player != null && index == part) return
        release()
        part = index
        player = MediaPlayer().apply {
            setDataSource(parts[index].first.path)
            prepare()
            setOnCompletionListener {
                if (part < parts.size - 1) {
                    open(part + 1)
                    player?.start()
                } else {
                    onEnded()
                }
            }
        }
    }

    fun release() {
        player?.release()
        player = null
    }
}

@Composable
fun AudioPlayerCard(
    parts: List<Pair<File, Long>>,
    sizeLabel: String,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val usable = remember(parts) { parts.filter { it.first.exists() } }
    if (usable.isEmpty()) return
    val player = remember(usable) { PartsPlayer(usable) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    player.onEnded = {
        playing = false
        position = 0
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(playing) {
        while (playing) {
            if (dragging == null) position = player.positionMs
            delay(200)
        }
    }

    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(
                    onClick = {
                        try {
                            if (playing) {
                                player.pause()
                                playing = false
                            } else {
                                player.play(if (position >= player.totalMs) 0 else position)
                                playing = true
                            }
                            error = null
                        } catch (e: Exception) {
                            playing = false
                            error = "Không phát được file: ${e.message ?: e.javaClass.simpleName}"
                        }
                    },
                    shape = CircleShape,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "Tạm dừng nghe" else "Nghe lại",
                    )
                }
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Slider(
                        value = dragging ?: (position.toFloat() / player.totalMs).coerceIn(0f, 1f),
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            val target = ((dragging ?: 0f) * player.totalMs).toLong()
                            dragging = null
                            position = target
                            runCatching { player.seek(target) }
                        },
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    Row(Modifier.padding(horizontal = 10.dp)) {
                        Text(
                            "${TimeFormat.clock(position)} / ${TimeFormat.clock(player.totalMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(sizeLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (onDelete != null) {
                    IconButton(onClick = {
                        player.release()
                        playing = false
                        onDelete()
                    }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Xoá file ghi âm", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp)) }
        }
    }
}
