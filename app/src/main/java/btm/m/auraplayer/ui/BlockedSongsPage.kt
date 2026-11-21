// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.data.BlockedSong
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun BlockedSongsPage(model: AuraViewModel, padding: PaddingValues) {
    val context = LocalContext.current
    val blockedSongs = model.blockedSongs
    var selected by remember { mutableStateOf<BlockedSong?>(null) }
    var displayed by remember { mutableStateOf<BlockedSong?>(null) }
    var auditionPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var auditioning by remember { mutableStateOf(false) }

    fun stopAudition() {
        val current = auditionPlayer
        auditionPlayer = null
        auditioning = false
        if (current != null) {
            runCatching { current.stop() }
            runCatching { current.release() }
        }
    }

    fun startAudition(item: BlockedSong) {
        stopAudition()
        if (model.playing) {
            model.togglePlayback()
        }
        val player = MediaPlayer()
        runCatching {
            val uri = Uri.parse(item.uri)
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                player.setDataSource(uri.path!!)
            } else {
                player.setDataSource(context, uri)
            }
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            player.setOnPreparedListener { mp ->
                if (auditionPlayer === mp) {
                    mp.start()
                    auditioning = true
                } else {
                    runCatching { mp.release() }
                }
            }
            player.setOnCompletionListener { mp ->
                if (auditionPlayer === mp) {
                    stopAudition()
                }
            }
            player.setOnErrorListener { mp, _, _ ->
                if (auditionPlayer === mp) {
                    stopAudition()
                    Toast.makeText(
                        context,
                        tr("audition_failed", "无法试听该音频文件"),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                true
            }
            auditionPlayer = player
            player.prepareAsync()
        }.onFailure {
            runCatching { player.release() }
            auditionPlayer = null
            auditioning = false
            Toast.makeText(
                context,
                tr("audition_failed", "无法试听该音频文件"),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    LaunchedEffect(selected) {
        if (selected != null) {
            displayed = selected
        } else {
            stopAudition()
        }
    }
    DisposableEffect(Unit) {
        onDispose { stopAudition() }
    }

    LazyColumn(
        modifier = pageContentModifier(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (blockedSongs.isEmpty()) {
            item {
                SeamlessCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 22.5.dp,
                    insideMargin = PaddingValues(24.dp),
                ) {
                    Text(
                        text = tr(
                            "blocked_songs_empty",
                            "暂无已屏蔽的歌曲。在本地音乐的更多菜单中选择“屏蔽该歌曲”后，歌曲会出现在这里。",
                        ),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            items(blockedSongs, key = { it.uri }) { item ->
                LaunchedEffect(item.uri, item.fileName) {
                    if (item.fileName.isBlank()) {
                        model.resolveBlockedSongFileName(item.uri)
                    }
                }
                val artistAlbum = formatBlockedArtistAlbum(item)
                SeamlessCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 22.5.dp,
                    insideMargin = PaddingValues(14.dp),
                    showIndication = true,
                    onClick = { selected = item },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Artwork(
                            uri = item.artwork,
                            modifier = Modifier.size(56.dp),
                            cornerRadius = 12.dp,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = item.title.ifBlank { tr("unknown_title", "未命名歌曲") },
                                style = MiuixTheme.textStyles.body1,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = artistAlbum,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = item.fileName.ifBlank { "—" },
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    WindowDialog(
        show = selected != null,
        onDismissRequest = {
            stopAudition()
            selected = null
        },
        onDismissFinished = {
            if (selected == null) displayed = null
        },
    ) {
        val item = displayed ?: return@WindowDialog
        val latestItem = blockedSongs.firstOrNull { it.uri == item.uri } ?: item
        val artistAlbum = formatBlockedArtistAlbum(latestItem)
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            Artwork(
                uri = latestItem.artwork,
                modifier = Modifier.size(96.dp),
                cornerRadius = 18.dp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = latestItem.title.ifBlank { tr("unknown_title", "未命名歌曲") },
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
                style = MiuixTheme.textStyles.title4,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = artistAlbum,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = latestItem.fileName.ifBlank { "—" },
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassDialogButton(
                    onClick = {
                        stopAudition()
                        model.unblockSong(latestItem.uri)
                        selected = null
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(tr("unblock_song", "取消屏蔽"))
                }
                GlassDialogButton(
                    onClick = {
                        if (auditioning) stopAudition() else startAudition(latestItem)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (auditioning) tr("stop_audition", "停止试听")
                        else tr("audition_song", "试听"),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            GlassDialogButton(
                onClick = {
                    stopAudition()
                    selected = null
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(tr("cancel", "取消"))
            }
        }
    }
}

private fun formatBlockedArtistAlbum(item: BlockedSong): String =
    listOf(item.artist, item.album)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .ifBlank { "—" }
