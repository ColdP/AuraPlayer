package btm.m.auraplayer.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.AuraApplication
import btm.m.auraplayer.data.creditedArtists
import btm.m.auraplayer.playback.*
import btm.m.auraplayer.ui.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import java.util.Locale

@Composable
internal fun PlayerActionsSheet(model: AuraViewModel, show: Boolean, menuBackdrop: com.kyant.backdrop.Backdrop,
                                dismiss: () -> Unit, openPage: (String) -> Unit, closePlayer: () -> Unit,
                                onBusy: (Boolean) -> Unit, expressive: PlayerExpressiveColors? = null) {
    val effectiveExpressive = expressive ?: if (LocalAppUiStyle.current == AppUiStyle.MATERIAL) {
        LocalAuraExpressiveScheme.current?.toPlayerExpressiveColors()
    } else null
    val song = model.current ?: return
    val effects = (LocalContext.current.applicationContext as AuraApplication).effects
    val effectsState by effects.state.collectAsState()
    // Both menus include the actual stream when the catalogue has not arrived yet.
    val levels = model.qualityDetails.ifEmpty { streamQuality(model.streamInfo)?.let(::listOf).orEmpty() }
    var artists by remember(song.key) { mutableStateOf(song.creditedArtists()) }
    var audioInfoOpen by remember(song.key) { mutableStateOf(false) }
    LaunchedEffect(show, song.key) { if (show) artists = model.resolveSongArtists(song) }
    LaunchedEffect(show, song.key) { if (show && song.cloud) model.refreshQualities() }
    LaunchedEffect(show) { onBusy(show); if (!show) audioInfoOpen = false }
    DisposableEffect(Unit) { onDispose { onBusy(false) } }
    val foreground = sheetForeground()
    val titleColors = BasicComponentColors(foreground, foreground.copy(alpha = .35f))
    val summaryColors = BasicComponentColors(foreground.copy(alpha = .68f), foreground.copy(alpha = .3f))
    @Composable fun leading(icon: ImageVector) { Image(icon, null, Modifier.padding(end = 14.dp).size(24.dp), colorFilter = ColorFilter.tint(foreground)) }
    val isOneUi = effectiveExpressive == null && LocalAppUiStyle.current == AppUiStyle.ONE
    @Composable fun action(icon: ImageVector, title: String, summary: String? = null, enabled: Boolean = true,
                            position: SheetItemPosition = SheetItemPosition.SINGLE, click: () -> Unit) {
        if (effectiveExpressive != null || isOneUi) {
            SheetActionRow(icon, title, summary, enabled, expressive = effectiveExpressive, position = position, onClick = click)
        } else {
            ArrowPreference(title, summary = summary, titleColor = titleColors, summaryColor = summaryColors,
                startAction = { leading(icon) }, insideMargin = PaddingValues(horizontal = 20.dp, vertical = 12.dp), enabled = enabled, onClick = click)
        }
    }
    @Composable fun divider() {
        when {
            isOneUi -> HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 5.dp), thickness = 0.75.dp, color = Color(rememberActiveOneUiPalette().divider))
            effectiveExpressive == null -> HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 5.dp), color = foreground.copy(alpha = .13f))
            else -> Spacer(Modifier.height(10.dp))
        }
    }
    val sheetModifier = if (effectiveExpressive != null) {
        val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        Modifier.clip(sheetShape).background(effectiveExpressive.surfaceContainerLow, sheetShape)
    } else {
        Modifier.neutralSheetMaterial(menuBackdrop)
    }
    OverlayBottomSheet(show = show, onDismissRequest = dismiss, backgroundColor = Color.Transparent,
        modifier = sheetModifier,
        insideMargin = DpSize(0.dp, 0.dp),
        dragHandleColor = effectiveExpressive?.dragHandleColor ?: foreground.copy(alpha = .45f)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
            if (effectiveExpressive != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(effectiveExpressive.surfaceContainerHigh)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Artwork(song.artwork, Modifier.size(56.dp), cornerRadius = 16.dp)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(song.title, color = effectiveExpressive.onSurface, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "),
                            color = effectiveExpressive.secondaryInk, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(song.artwork, Modifier.size(58.dp), cornerRadius = ListArtworkCornerRadius)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(song.title, color = foreground, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "),
                            color = foreground.copy(alpha = .7f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            divider()
            val group1Modifier = if (effectiveExpressive != null) Modifier.fillMaxWidth().padding(horizontal = 16.dp) else Modifier.fillMaxWidth()
            Column(group1Modifier, verticalArrangement = Arrangement.spacedBy(if (effectiveExpressive != null) 2.dp else 0.dp)) {
                if (song.cloud) {
                    SongDownloadPreference(model, song, show, menuBackdrop, expressive = effectiveExpressive, position = SheetItemPosition.TOP)
                    val current = model.streamInfo?.let { stream -> levels.firstOrNull { it.quality.level == stream.level } }
                        ?: levels.firstOrNull { it.quality == model.quality }
                    val summary = current?.let { cloudQualityText(it, model.streamInfo?.takeIf { s -> s.level == it.quality.level }, model.audioFormat) }
                        ?: model.streamInfo?.let { stream -> StreamQuality.entries.firstOrNull { it.level == stream.level }?.let { cloudQualityText(CloudQuality(it, AudioDetails()), stream, model.audioFormat) } }
                        ?: if (model.qualityLoading) tr("loading", "加载中…") else qualityName(model.quality)
                    SheetDropdown(MiuixIcons.Regular.AppRecording, tr("select_quality", "选择音质"), summary, menuBackdrop,
                        DropdownEntry(buildList {
                            if (model.qualityError) add(DropdownItem(tr("quality_retry", "获取音质失败，点击重试"), onClick = model::refreshQualities))
                            addAll(qualityDropdownItems(levels, current?.quality ?: model.quality, { cloudQualityText(it) }) { model.selectQuality(it.quality) })
                            if (isEmpty()) add(DropdownItem(if (model.qualityLoading) tr("loading", "加载中…") else tr("quality_unavailable", "此歌曲暂无可用音质"), enabled = false))
                        }), expressive = effectiveExpressive, position = SheetItemPosition.MIDDLE)
                }
                action(MiuixIcons.Regular.VolumeUp, tr("audio_effects", "音效"), presetName(effectsState.current),
                    position = if (song.cloud) SheetItemPosition.BOTTOM else SheetItemPosition.SINGLE) { dismiss(); closePlayer(); openPage("audio_effects") }
            }
            fun openCollectionFromSheet(launch: () -> Unit) {
                dismiss()
                val beforeId = model.activeCollectionId
                launch()
                closePlayer()
                val afterId = model.activeCollectionId
                if (afterId > 0L && (afterId != beforeId || model.collection != null)) {
                    openPage("collection:$afterId")
                }
            }
            divider()
            Column(group1Modifier, verticalArrangement = Arrangement.spacedBy(if (effectiveExpressive != null) 2.dp else 0.dp)) {
                action(MiuixIcons.Regular.Album, tr("album", "专辑"), song.album, !song.cloud || song.albumId > 0, position = SheetItemPosition.TOP) {
                    openCollectionFromSheet { model.openSongAlbum(song) }
                }
                val artistPos = if (!song.cloud) SheetItemPosition.MIDDLE else SheetItemPosition.BOTTOM
                if (artists.size > 1) {
                    SheetDropdown(MiuixIcons.Regular.ContactsCircle, tr("artist", "艺术家"), song.artist, menuBackdrop,
                        DropdownEntry(artists.map { artist ->
                            DropdownItem(artist.name, enabled = !song.cloud || artist.id > 0,
                                icon = { modifier -> Artwork(artist.artwork, modifier.size(30.dp), circular = true) },
                                onClick = { openCollectionFromSheet { model.openSongArtist(song, artist) } })
                        }), expressive = effectiveExpressive, position = artistPos)
                } else action(MiuixIcons.Regular.ContactsCircle, tr("artist", "艺术家"), song.artist, !song.cloud || song.artistId > 0, position = artistPos) {
                    openCollectionFromSheet { model.openSongArtist(song) }
                }
                if (!song.cloud) {
                    action(MiuixIcons.Regular.File, tr("tag_editor", "标签编辑器"), position = SheetItemPosition.MIDDLE) {
                        dismiss(); closePlayer(); openPage(tagEditorRoute(song))
                    }
                    action(MiuixIcons.Regular.Info, tr("audio_info", "音频信息"), position = SheetItemPosition.BOTTOM) {
                        audioInfoOpen = true
                    }
                }
            }
            divider()
            Column(group1Modifier, verticalArrangement = Arrangement.spacedBy(if (effectiveExpressive != null) 2.dp else 0.dp)) {
                SheetDropdown(MiuixIcons.Regular.Timer, tr("sleep_timer", "定时关闭"),
                    if (model.sleepRemaining > 0) timerText(model.sleepRemaining) else tr("timer_off", "未开启"), menuBackdrop,
                    DropdownEntry(listOf(DropdownItem(tr("cancel_timer", "取消定时关闭"), selected = model.sleepRemaining == 0L, onClick = { model.setSleepTimer(0) })) +
                        listOf(5, 10, 15, 30, 45, 60, 90, 120).map { minutes ->
                            DropdownItem("$minutes ${tr("minutes", "分钟")}",
                                selected = model.sleepRemaining > 0 && model.playbackPreferences.sleepMinutes == minutes,
                                onClick = { model.setSleepTimer(minutes) })
                        }), expressive = effectiveExpressive, position = SheetItemPosition.TOP)
                val speeds = listOf(.5f, .75f, 1f, 1.5f, 2f, 3f)
                SheetDropdown(MiuixIcons.Regular.SearchDevice, tr("playback_speed", "倍速播放"), speedText(model.playbackSpeed), menuBackdrop,
                    DropdownEntry(speeds.map { speed -> DropdownItem(speedText(speed), selected = speed == model.playbackSpeed, onClick = { model.changePlaybackSpeed(speed) }) }),
                    expressive = effectiveExpressive, position = SheetItemPosition.BOTTOM)
            }
        }
    }
    if (!song.cloud) {
        AudioInfoDialog(
            song = song,
            show = audioInfoOpen,
            playingFormat = model.audioFormat.takeIf { model.current?.key == song.key },
            onDismissRequest = { audioInfoOpen = false },
        )
    }

}

internal fun qualityDropdownItems(
    levels: List<CloudQuality>,
    selected: StreamQuality,
    label: (CloudQuality) -> String,
    onSelected: (CloudQuality) -> Unit,
): List<DropdownItem> = levels.map { level ->
    DropdownItem(label(level), selected = level.quality == selected, onClick = { onSelected(level) })
}

internal fun cloudQualityText(value: CloudQuality, stream: StreamInfo? = null, format: androidx.media3.common.Format? = null): String {
    var audio = value.audio
    if (stream != null) audio = audio.copy(codec = stream.codec.ifBlank { audio.codec }, sampleRate = stream.sampleRate.takeIf { it > 0 } ?: audio.sampleRate,
        bitrate = stream.bitrate.takeIf { it > 0 } ?: audio.bitrate, bits = stream.bits.takeIf { it > 0 } ?: audio.bits).withFormat(format)
    return buildList {
        add(qualityName(value.quality)); if (audio.codec.isNotBlank()) add(audio.codec.uppercase())
        val specs = buildList {
            if (audio.sampleRate > 0) add("${java.text.DecimalFormat("0.###").format(audio.sampleRate / 1000.0)} ${tr("khz", "kHz")}")
            if (audio.bits > 0) add("${audio.bits}-${tr("bit", "bit")}")
            if (audio.bitrate > 0) add("${audio.bitrate / 1000}${tr("kbps", "kb/s")}")
        }
        if (specs.isNotEmpty()) add(specs.joinToString(" / "))
    }.joinToString(" · ")
}

internal fun cloudDownloadText(value: CloudQuality, song: btm.m.auraplayer.data.Song): String {
    val bytes = if (value.audio.bitrate > 0 && song.duration > 0)
        value.audio.bitrate.toLong() * song.duration / 1000L / 8L else 0L
    val size = if (bytes > 0) String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f)) else "—"
    return "${qualityName(value.quality)} · $size"
}
private fun timerText(ms: Long) = "${(ms + 999) / 60_000}:${((ms + 999) / 1000 % 60).toString().padStart(2, '0')}"
private fun speedText(speed: Float) = speed.toString().removeSuffix(".0") + "×"
