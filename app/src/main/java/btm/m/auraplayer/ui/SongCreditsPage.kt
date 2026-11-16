// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.data.Song
import btm.m.auraplayer.data.SongArtist
import btm.m.auraplayer.data.SongCreditPerson
import btm.m.auraplayer.data.SongCreditRole
import btm.m.auraplayer.data.SongCreditsData
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun songCreditsRoute(song: Song): String = "song_credits:${song.id}"

@Composable
fun SongCreditsPage(
    songId: Long,
    model: AuraViewModel,
    padding: PaddingValues,
) {
    val seedSong = remember(songId) { model.findCloudSongForCredits(songId) }
    var credits by remember(songId) { mutableStateOf<SongCreditsData?>(null) }
    var loading by remember(songId) { mutableStateOf(true) }
    var failed by remember(songId) { mutableStateOf(false) }
    var reloadTick by remember(songId) { mutableIntStateOf(0) }

    LaunchedEffect(songId, reloadTick) {
        loading = true
        failed = false
        try {
            credits = model.loadSongCredits(songId, forceRefresh = reloadTick > 0)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    val activeData = credits
    val displayArtwork = activeData?.artwork?.takeIf { it.isNotBlank() } ?: seedSong?.artwork.orEmpty()
    val displayTitle = activeData?.title?.takeIf { it.isNotBlank() } ?: seedSong?.title.orEmpty()
    val displayArtist = activeData?.artist?.takeIf { it.isNotBlank() } ?: seedSong?.artist.orEmpty()
    val displayAlbum = activeData?.album?.takeIf { it.isNotBlank() } ?: seedSong?.album.orEmpty()
    val displayYear = activeData?.releaseYear.orEmpty()
    val metaSubtitle = listOf(displayArtist, displayAlbum, displayYear)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

    val navigationSong = remember(songId, seedSong, activeData) {
        seedSong ?: Song(
            id = songId,
            title = displayTitle,
            artist = displayArtist,
            album = displayAlbum,
            uri = "aura://netease/$songId",
            artwork = displayArtwork,
            cloud = true,
        )
    }

    LazyColumn(
        pageContentModifier(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("credits_header") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Artwork(
                    uri = displayArtwork,
                    modifier = Modifier.size(196.dp),
                    cornerRadius = 24.dp,
                )
                if (displayTitle.isNotBlank()) {
                    Text(
                        text = displayTitle,
                        style = MiuixTheme.textStyles.title2,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp),
                    )
                }
                if (metaSubtitle.isNotBlank()) {
                    Text(
                        text = metaSubtitle,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp, start = 20.dp, end = 20.dp),
                    )
                }
            }
        }

        if (loading && activeData == null) {
            item("credits_loading") {
                SeamlessCard(
                    Modifier.fillMaxWidth(),
                    cornerRadius = 22.5.dp,
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Text(
                        text = tr("loading", "正在加载…"),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        } else if (failed && activeData == null) {
            item("credits_error") {
                SeamlessCard(
                    Modifier.fillMaxWidth(),
                    cornerRadius = 22.5.dp,
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        GlassDialogButton(onClick = { reloadTick++ }) {
                            Text(tr("cloud_load_retry", "加载失败，点击重试"))
                        }
                    }
                }
            }
        } else if (activeData != null) {
            item("credits_songwriting") {
                Group(tr("credits_songwriting", "词曲")) {
                    if (activeData.songwriting.isEmpty()) {
                        Text(
                            text = tr("credits_empty_songwriting", "暂无词曲信息"),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
                        )
                    } else {
                        activeData.songwriting.forEach { person ->
                            CreatorCreditRow(
                                person = person,
                                onClick = if (person.artistId > 0L) {
                                    {
                                        model.openSongArtist(
                                            navigationSong,
                                            SongArtist(person.artistId, person.name, person.avatarUrl),
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }

            item("credits_production") {
                Group(tr("credits_production", "制作")) {
                    if (activeData.production.isEmpty()) {
                        Text(
                            text = tr("credits_empty_production", "暂无编曲或制作人信息"),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
                        )
                    } else {
                        activeData.production.forEach { person ->
                            CreatorCreditRow(
                                person = person,
                                onClick = if (person.artistId > 0L) {
                                    {
                                        model.openSongArtist(
                                            navigationSong,
                                            SongArtist(person.artistId, person.name, person.avatarUrl),
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreatorCreditRow(
    person: SongCreditPerson,
    onClick: (() -> Unit)?,
) {
    val rolesSummary = person.roles.joinToString(", ") { creditRoleLabel(it) }
    BasicComponent(
        title = person.name,
        summary = rolesSummary,
        startAction = {
            Artwork(
                uri = person.avatarUrl,
                modifier = Modifier.padding(end = 14.dp).size(44.dp),
                circular = true,
            )
        },
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        onClick = onClick,
    )
}

private fun creditRoleLabel(role: SongCreditRole): String = when (role) {
    SongCreditRole.LYRICIST -> tr("credit_role_lyricist", "作词")
    SongCreditRole.COMPOSER -> tr("credit_role_composer", "作曲")
    SongCreditRole.ARRANGER -> tr("credit_role_arranger", "编曲")
    SongCreditRole.PRODUCER -> tr("credit_role_producer", "制作人")
}
