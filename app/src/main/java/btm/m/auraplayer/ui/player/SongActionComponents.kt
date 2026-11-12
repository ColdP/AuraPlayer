package btm.m.auraplayer.ui.player

import android.content.SharedPreferences
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.data.Song
import btm.m.auraplayer.download.MusicDownloads
import btm.m.auraplayer.playback.*
import btm.m.auraplayer.ui.*
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.*
import com.kyant.backdrop.highlight.Highlight
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun sheetForeground(): Color = if (MiuixTheme.colorScheme.surface.luminance() > .5f) Color(0xFF191919) else Color.White

/** Same sampling and edge light as glass dialogs, with a neutral surface in either theme. */
@Composable
internal fun Modifier.neutralSheetMaterial(backdrop: Backdrop?): Modifier {
    val light = MiuixTheme.colorScheme.surface.luminance() > .5f
    val tint = (if (light) Color(0xFFF8F8F8) else Color(0xFF202022)).copy(alpha = .80f)
    val shape = RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp)
    return if (backdrop == null) background(tint, shape) else drawBackdrop(
        backdrop = backdrop, shape = { shape },
        effects = { vibrancy(); blur(8.dp.toPx()); lens(14.dp.toPx(), 22.dp.toPx(), chromaticAberration = true) },
        highlight = { Highlight.Default.copy(alpha = .55f) },
        onDrawSurface = { drawRect(tint) },
    )
}

internal fun streamQuality(stream: StreamInfo?): CloudQuality? {
    stream ?: return null
    val quality = StreamQuality.entries.firstOrNull { it.level == stream.level } ?: return null
    return CloudQuality(quality, AudioDetails(stream.codec, stream.sampleRate, stream.bits, stream.bitrate))
}

@Composable
internal fun SheetDropdown(icon: ImageVector, title: String, summary: String, backdrop: Backdrop?, entry: DropdownEntry) {
    val foreground = sheetForeground()
    GlassIconDropdownMenu(entry, backdrop, trigger = { toggle, enabled ->
        BasicComponent(title = title, summary = summary,
            titleColor = BasicComponentColors(foreground, foreground.copy(alpha = .35f)),
            summaryColor = BasicComponentColors(foreground.copy(alpha = .68f), foreground.copy(alpha = .3f)),
            startAction = { Image(icon, null, Modifier.padding(end = 14.dp).size(24.dp), colorFilter = ColorFilter.tint(foreground)) },
            endActions = { DropdownArrowEndAction(foreground.copy(alpha = .7f)) },
            insideMargin = PaddingValues(horizontal = 20.dp, vertical = 12.dp), onClick = toggle, enabled = enabled)
    })
}

/** The clicked song owns its request and confirmation, independently of playback. */
@Composable
internal fun SongDownloadPreference(model: AuraViewModel, song: Song, active: Boolean, backdrop: Backdrop?) {
    val context = LocalContext.current
    var levels by remember(song.key) { mutableStateOf<List<CloudQuality>>(emptyList()) }
    var loading by remember(song.key) { mutableStateOf(true) }
    var failed by remember(song.key) { mutableStateOf(false) }
    var retry by remember(song.key) { mutableIntStateOf(0) }
    var selected by remember(song.key) { mutableStateOf<CloudQuality?>(null) }
    var downloadFailed by remember(song.key) { mutableStateOf(false) }
    // Reading completed-download preferences invalidates both the menu and confirmation.
    val downloadRevision = remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val prefs = MusicDownloads.completedPreferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> downloadRevision.intValue++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(active, song.key, retry) {
        if (!active || !song.cloud) return@LaunchedEffect
        loading = true; failed = false
        try { levels = model.app.api.qualityDetails(song.id) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    val actual = if (model.current?.key == song.key) streamQuality(model.streamInfo) else streamQuality(PlaybackStreams.records[song.id])
    val options = (levels + listOfNotNull(actual)).distinctBy { it.quality }
    val revision = downloadRevision.intValue
    fun downloaded(quality: StreamQuality) = MusicDownloads.isDownloaded(context, song, quality)
    val current = options.firstOrNull { it.quality == model.quality } ?: options.lastOrNull()
    fun label(value: CloudQuality) = (if (downloaded(value.quality)) tr("downloaded", "已下载") + " · " else "") + cloudDownloadText(value, song)
    SheetDropdown(MiuixIcons.Regular.Download, tr("download", "下载"),
        current?.let(::label) ?: if (loading) tr("loading", "加载中…") else tr("select_quality", "选择音质"), backdrop,
        DropdownEntry(buildList {
            addAll(qualityDropdownItems(options, current?.quality ?: model.quality, ::label) {
                selected = it; downloadFailed = false
            })
            if (loading && isEmpty()) add(DropdownItem(tr("loading", "加载中…"), enabled = false))
            if (failed || !loading && options.isEmpty()) add(DropdownItem(tr("quality_retry", "获取音质失败，点击重试"), onClick = { retry++ }))
        }))
    val choice = selected
    WindowDialog(choice != null,
        title = (if (choice != null && downloaded(choice.quality)) tr("download_again", "是否再次下载") else tr("download_confirm", "即将下载")) + " ${song.title}",
        titleTextAlign = TextAlign.Start, summaryTextAlign = TextAlign.Start,
        summary = if (downloadFailed) tr("download_unavailable", "下载失败，此音质可能需要会员或购买，请选择其他音质重试") else choice?.let { cloudDownloadText(it, song) },
        onDismissRequest = { if (!model.downloadBusy) selected = null }) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton({ selected = null }, Modifier.weight(1f), enabled = !model.downloadBusy) { Text(tr("cancel", "取消")) }
            GlassDialogButton({ if (choice != null) model.download(song, choice.quality) {
                if (it) selected = null else downloadFailed = true
            } }, Modifier.weight(1f), enabled = !model.downloadBusy && choice != null, colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(if (model.downloadBusy) tr("preparing_download", "正在准备…") else tr("download", "下载"))
            }
        }
    }
}
