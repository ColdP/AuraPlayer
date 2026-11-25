package btm.m.auraplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.R
import btm.m.auraplayer.data.*
import btm.m.auraplayer.ui.navigation.InteractiveHighlight
import btm.m.auraplayer.ui.player.findCachedArtworkMemoryEntry
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import com.itos.coui.search.CouiSearchBarDefaults
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedCornerStyle
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import btm.m.auraplayer.ui.navigation.appleMusicTabIcon
import btm.m.auraplayer.ui.player.LocalSongActions
import btm.m.auraplayer.ui.player.SongActionRequest
import java.util.Calendar
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun AuraTabIcon(index: Int, color: Color) {
    val icon = when (index) {
        0 -> appleMusicTabIcon(0)
        1 -> appleMusicTabIcon(1)
        else -> appleMusicTabIcon(3)
    }
    androidx.compose.foundation.Image(icon, null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(color))
}

// Slightly tighter than the mini player's 13.dp artwork corners.
internal val ListArtworkCornerRadius = 11.dp

@Composable
fun Artwork(
    uri: String,
    modifier: Modifier = Modifier,
    circular: Boolean = false,
    cornerRadius: Dp = 16.dp,
    adaptIrregular: Boolean = !circular,
    shape: Shape? = null,
) {
    val context = LocalContext.current
    val adapt = adaptIrregular && !circular && LocalAdaptIrregularCovers.current && uri.isNotBlank()
    val cachedRatio = rememberCoverAspectRatio(uri, enabled = adapt)
    var measuredRatio by remember(uri, adapt) { mutableStateOf<Float?>(null) }
    val aspectRatio = measuredRatio ?: cachedRatio
    val request = remember(uri, context) {
        if (uri.isBlank()) null
        else {
            val placeholderKey = findCachedArtworkMemoryEntry(SingletonImageLoader.get(context), uri)?.key
            ImageRequest.Builder(context)
                .data(uri)
                .apply { if (placeholderKey != null) placeholderMemoryCacheKey(placeholderKey) }
                .build()
        }
    }
    val effectiveShape = shape ?: if (circular) CircleShape else RoundedCornerShape(cornerRadius)
    val cardModifier = (if (adapt) Modifier.fitCoverAspectRatio(aspectRatio) else Modifier.fillMaxSize())
        .clip(effectiveShape)
        .background(Brush.linearGradient(listOf(MiuixTheme.colorScheme.primaryContainer, MiuixTheme.colorScheme.surfaceContainer)))
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(cardModifier, contentAlignment = Alignment.Center) {
            Text("♫", fontSize = 28.sp, color = MiuixTheme.colorScheme.primary.copy(alpha = .7f))
            if (request != null) AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { state ->
                    if (adapt) {
                        recordCoverAspectRatio(
                            uri,
                            state.result.image.width.toFloat(),
                            state.result.image.height.toFloat(),
                        )?.let { measuredRatio = it }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun durationText(ms: Long): String = "${ms / 60000}:${(ms / 1000 % 60).toString().padStart(2, '0')}"

internal fun Song.shouldShowVipBadge(settings: btm.m.auraplayer.data.Appearance): Boolean =
    cloud && vip && if (provider == btm.m.auraplayer.data.PROVIDER_QQ) settings.qqShowVipBadge else settings.neteaseShowVipBadge

@Composable
internal fun SongVipBadge(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(R.drawable.ic_vip_badge),
        contentDescription = "VIP",
        modifier = modifier.width(26.dp).height(13.5.dp),
        colorFilter = ColorFilter.tint(tint),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    model: AuraViewModel,
    onClick: () -> Unit,
    onLongAction: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    showDragHandle: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    dragHandleModifier: Modifier = Modifier,
    outerVerticalPadding: Dp = 2.dp,
) {
    val layout = LocalAuraLayout.current
    val artworkSize = if (layout.tablet) 48.dp else 44.dp
    val openActions = LocalSongActions.current
    val backdrop = LocalToolbarBackdrop.current
    val haptics = LocalHapticFeedback.current
    val isCurrent = model.current?.key == song.key
    val showVip = song.shouldShowVipBadge(model.settings.value)
    val rowInteractionModifier = if (onLongPress != null && !selectionMode) {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongPress()
            },
        )
    } else {
        Modifier.clickable(onClick = onClick)
    }

    @Composable
    fun SelectionLeading() {
        AnimatedVisibility(
            visible = selectionMode,
            enter = expandHorizontally(tween(240, easing = FastOutSlowInEasing), expandFrom = Alignment.Start) +
                fadeIn(tween(200)) +
                scaleIn(tween(240), initialScale = 0.78f),
            exit = shrinkHorizontally(tween(220, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Start) +
                fadeOut(tween(160)) +
                scaleOut(tween(220), targetScale = 0.78f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AuraCheckbox(
                    checked = selected,
                    onCheckedChange = onClick,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
        }
    }

    @Composable
    fun TrailingActions(buttonSize: Dp, iconSize: Dp, moreTint: Color, dragTint: Color) {
        AnimatedVisibility(
            visible = !selectionMode,
            enter = expandHorizontally(tween(240, easing = FastOutSlowInEasing), expandFrom = Alignment.End) +
                fadeIn(tween(200)) +
                scaleIn(tween(240), initialScale = 0.8f),
            exit = shrinkHorizontally(tween(200, easing = FastOutSlowInEasing), shrinkTowards = Alignment.End) +
                fadeOut(tween(160)) +
                scaleOut(tween(200), targetScale = 0.8f),
        ) {
            IconButton(onClick = { openActions(SongActionRequest(song, backdrop, onLongAction)) }, modifier = Modifier.size(buttonSize)) {
                Image(AuraMoreHorizontalIcon, tr("more", "更多"), Modifier.size(iconSize), colorFilter = ColorFilter.tint(moreTint))
            }
        }
        if (showDragHandle) {
            AnimatedVisibility(
                visible = selectionMode,
                enter = expandHorizontally(tween(240, easing = FastOutSlowInEasing), expandFrom = Alignment.End) +
                    fadeIn(tween(200)) +
                    scaleIn(tween(240), initialScale = 0.8f),
                exit = shrinkHorizontally(tween(200, easing = FastOutSlowInEasing), shrinkTowards = Alignment.End) +
                    fadeOut(tween(160)) +
                    scaleOut(tween(200), targetScale = 0.8f),
            ) {
                Box(
                    modifier = Modifier
                        .size(buttonSize)
                        .clip(CircleShape)
                        .then(dragHandleModifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        imageVector = AuraDragHandleIcon,
                        contentDescription = tr("drag_to_reorder", "拖动排序"),
                        modifier = Modifier.size(20.dp),
                        colorFilter = ColorFilter.tint(dragTint),
                    )
                }
            }
        }
    }

    when (LocalAppUiStyle.current) {
        AppUiStyle.ONE -> {
            val palette = rememberActiveOneUiPalette()
            val targetBg = when {
                selectionMode && selected -> Color(palette.chip).copy(alpha = 0.86f)
                isCurrent -> Color(palette.chip).copy(alpha = 0.72f)
                else -> Color.Transparent
            }
            val rowBg by animateColorAsState(targetBg, tween(180), label = "oneRowBg")
            val titleColor = if (isCurrent) Color(palette.accentDeep) else Color(palette.text)
            val subColor = Color(palette.subText)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = outerVerticalPadding)
                    .clip(RoundedCornerShape(16.dp))
                    .background(rowBg)
                    .then(rowInteractionModifier)
                    .padding(vertical = 7.dp, horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionLeading()
                Artwork(song.artwork, Modifier.size(artworkSize), cornerRadius = ListArtworkCornerRadius)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        song.title.ifBlank { tr("unknown_title", "未命名歌曲") },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 15.5.sp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        color = titleColor,
                    )
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (showVip) SongVipBadge(Color(palette.accentDeep))
                        Text(
                            "${song.artist} · ${song.album}",
                            fontSize = 13.sp,
                            color = subColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(durationText(song.duration), fontSize = 12.sp, color = subColor)
                    TrailingActions(
                        buttonSize = 40.dp,
                        iconSize = 20.dp,
                        moreTint = if (isCurrent) Color(palette.accentDeep) else subColor,
                        dragTint = subColor,
                    )
                }
            }
            return
        }
        AppUiStyle.MATERIAL -> {
            val scheme = rememberActiveExpressiveScheme()
            val targetBg = when {
                selectionMode && selected -> scheme.primaryContainer.copy(alpha = 0.52f)
                isCurrent -> scheme.secondaryContainer
                else -> Color.Transparent
            }
            val rowBg by animateColorAsState(targetBg, tween(180), label = "materialRowBg")
            val titleColor = if (isCurrent) scheme.onSecondaryContainer else scheme.onSurface
            val subColor = if (isCurrent) scheme.onSecondaryContainer.copy(alpha = 0.78f) else scheme.onSurfaceVariant
            val vipTint = if (isCurrent) scheme.onSecondaryContainer else scheme.primary
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = outerVerticalPadding)
                    .clip(RoundedCornerShape(18.dp))
                    .background(rowBg)
                    .then(rowInteractionModifier)
                    .padding(vertical = 6.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionLeading()
                Artwork(song.artwork, Modifier.size(artworkSize), cornerRadius = ListArtworkCornerRadius)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        song.title.ifBlank { tr("unknown_title", "未命名歌曲") },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 15.5.sp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        color = titleColor,
                    )
                    Row(
                        modifier = Modifier.padding(top = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (showVip) SongVipBadge(vipTint)
                        Text(
                            "${song.artist} · ${song.album}",
                            fontSize = 13.sp,
                            color = subColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(durationText(song.duration), fontSize = 12.sp, color = subColor)
                    TrailingActions(
                        buttonSize = 40.dp,
                        iconSize = 20.dp,
                        moreTint = if (isCurrent) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                        dragTint = scheme.onSurfaceVariant,
                    )
                }
            }
            return
        }
        AppUiStyle.HYPER -> Unit
    }
    val hyperTargetBg = if (selectionMode && selected) {
        MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)
    } else {
        Color.Transparent
    }
    val hyperRowBg by animateColorAsState(hyperTargetBg, tween(180), label = "hyperRowBg")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = outerVerticalPadding)
            .clip(RoundedCornerShape(12.dp))
            .background(hyperRowBg)
            .then(rowInteractionModifier)
            .padding(vertical = 5.5.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionLeading()
        Artwork(song.artwork, Modifier.size(artworkSize), cornerRadius = ListArtworkCornerRadius)
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text(song.title.ifBlank { tr("unknown_title", "未命名歌曲") }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color = if (isCurrent) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface)
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (showVip) SongVipBadge(MiuixTheme.colorScheme.primary)
                Text("${song.artist} · ${song.album}", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(durationText(song.duration), style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            TrailingActions(
                buttonSize = 44.dp,
                iconSize = 22.dp,
                moreTint = MiuixTheme.colorScheme.primary,
                dragTint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
fun SectionTitle(title: String) { Text(title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold) }

private fun LazyListScope.songRows(
    tracks: List<Song>,
    model: AuraViewModel,
    columns: Int,
    horizontalPadding: Dp = 0.dp,
    onRemove: ((Song) -> Unit)? = null,
    onPlay: ((Int) -> Unit)? = null,
    selectionMode: Boolean = false,
    selectedKeys: Set<String> = emptySet(),
    onToggleSelect: ((Song) -> Unit)? = null,
    onLongPress: ((Song) -> Unit)? = null,
) {
    items(tracks.chunked(columns), key = { it.first().key }) { row ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            row.forEach { song ->
                Box(Modifier.weight(1f)) {
                    SongRow(
                        song = song,
                        model = model,
                        onClick = {
                            if (selectionMode && onToggleSelect != null) onToggleSelect(song)
                            else onPlay?.invoke(tracks.indexOf(song)) ?: model.play(tracks, tracks.indexOf(song))
                        },
                        onLongAction = onRemove?.let { { it(song) } },
                        selectionMode = selectionMode,
                        selected = song.key in selectedKeys,
                        showDragHandle = false,
                        onLongPress = onLongPress?.let { { it(song) } },
                    )
                }
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
internal fun PermissionCard(onClick: () -> Unit) {
    SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(18.dp)) {
        Text(tr("permission_title", "让 Aura 找到你的音乐"), fontWeight = FontWeight.Bold)
        Text(tr("permission_summary", "授权读取音频后，歌曲、专辑和艺术家会自动出现在这里"), style = MiuixTheme.textStyles.body2,
            modifier = Modifier.padding(vertical = 10.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        GlassDialogButton(onClick, colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("grant_audio", "授权读取音乐")) }
    }
}

@Composable
fun SongsPage(
    model: AuraViewModel,
    padding: PaddingValues,
    requestPermission: () -> Unit,
    selectionMode: Boolean = false,
    selectedKeys: Set<String> = emptySet(),
    onEnterSelection: (Song) -> Unit = {},
    onToggleSelect: (Song) -> Unit = {},
    onOpenSecondary: (Int) -> Unit = {},
) {
    val songs = model.sortedSongs
    val columns = LocalAuraLayout.current.listColumns
    val pullToRefreshState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }
    val effectivePadding = if (selectionMode) {
        PaddingValues(
            start = padding.calculateStartPadding(androidx.compose.ui.platform.LocalLayoutDirection.current),
            top = padding.calculateTopPadding(),
            end = padding.calculateEndPadding(androidx.compose.ui.platform.LocalLayoutDirection.current),
            bottom = padding.calculateBottomPadding() + 72.dp,
        )
    } else {
        padding
    }

    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) return@LaunchedEffect
        val start = System.currentTimeMillis()
        try {
            model.refreshLibrary()
            snapshotFlow { model.scanning }.first { !it }
            val elapsed = System.currentTimeMillis() - start
            if (elapsed < 350L) delay(350L - elapsed)
        } finally {
            isRefreshing = false
        }
    }

    PullToRefresh(
        isRefreshing = isRefreshing,
        onRefresh = { isRefreshing = true },
        modifier = Modifier.fillMaxSize(),
        pullToRefreshState = pullToRefreshState,
        contentPadding = effectivePadding,
        topAppBarScrollBehavior = LocalPageScroll.current,
        color = MiuixTheme.colorScheme.primary,
        refreshTexts = listOf(
            tr("pull_to_refresh", "下拉刷新"),
            tr("release_to_refresh", "松开刷新"),
            tr("refreshing", "正在刷新…"),
            tr("refresh_completed", "刷新完成"),
        ),
    ) {
        LazyColumn(pageContentModifier(), contentPadding = effectivePadding) {
        item("song_categories") {
            if (LocalAppUiStyle.current == AppUiStyle.ONE) {
                val palette = rememberActiveOneUiPalette()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val items = listOf(
                        tr("albums", "专辑") to 0,
                        tr("artists", "艺术家") to 1,
                        tr("playback_lists", "播放列表") to 2,
                    )
                    items.forEach { (label, target) ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(22.dp))
                                .background(Color(palette.card))
                                .clickable { onOpenSecondary(target) }
                                .padding(horizontal = 10.dp, vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                color = Color(palette.accentDeep),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else if (LocalAppUiStyle.current == AppUiStyle.MATERIAL) {
                val scheme = rememberActiveExpressiveScheme()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val items = listOf(
                        tr("albums", "专辑") to 0,
                        tr("artists", "艺术家") to 1,
                        tr("playback_lists", "播放列表") to 2,
                    )
                    items.forEach { (label, target) ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(18.dp))
                                .background(scheme.secondaryContainer)
                                .clickable { onOpenSecondary(target) }
                                .padding(horizontal = 10.dp, vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                color = scheme.onSecondaryContainer,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else {
                SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                    Column {
                        Preference(tr("albums", "专辑"), onClick = { onOpenSecondary(0) })
                        Preference(tr("artists", "艺术家"), onClick = { onOpenSecondary(1) })
                        Preference(tr("playback_lists", "播放列表"), onClick = { onOpenSecondary(2) })
                    }
                }
            }
        }
        if (!model.hasPermission) item { PermissionCard(requestPermission) }
        else if (model.songs.isEmpty()) item { EmptyState(tr("no_songs", "没有找到歌曲"), tr("check_filters", "请检查音乐权限、文件夹与筛选设置"), tr("rescan", "重新扫描"), { model.refreshLibrary() }) }
        songRows(
            tracks = songs,
            model = model,
            columns = columns,
            selectionMode = selectionMode,
            selectedKeys = selectedKeys,
            onToggleSelect = onToggleSelect,
            onLongPress = onEnterSelection,
        )
        }
    }
}

@Composable
internal fun DownloadedMusicPage(model: AuraViewModel, padding: PaddingValues) {
    val songs = model.downloadedSongs
    val columns = LocalAuraLayout.current.listColumns
    LaunchedEffect(Unit) { model.refreshDownloadedSongs() }
    LazyColumn(pageContentModifier(), contentPadding = padding) {
        if (songs.isEmpty()) item { EmptyState(tr("no_downloaded_music", "暂无已下载的音乐"), tr("downloaded_music_hint", "下载完成的网易云音乐会出现在这里")) }
        songRows(songs, model, columns)
    }
}

@Composable
fun CollectionTile(title: String, subtitle: String, art: String, modifier: Modifier = Modifier, circular: Boolean = false, foreground: Color = MiuixTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(2.dp)) {
        Artwork(art, Modifier.fillMaxWidth().aspectRatio(1f), circular)
        Text(title, color = foreground, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Text(subtitle, style = MiuixTheme.textStyles.body2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = foreground.copy(alpha = .66f), modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
fun AlbumsArtistsPage(model: AuraViewModel, padding: PaddingValues, tab: Int) {
    val collections = remember(model.songs, tab) {
        if (tab == 0) model.songs.groupBy { "${it.volume}:${it.albumId}:${it.album}" }.map { (key, tracks) ->
            MusicCollection("album:$key", tracks.first().album, tracks.first().artist, tracks.sortedBy { it.track }, tracks.first().artwork)
        }.sortedBy { it.title }
        else model.songs.groupBy { "${it.volume}:${it.artistId}:${it.artist}" }.map { (key, tracks) ->
            MusicCollection("artist:$key", tracks.first().artist, "${tracks.size} ${tr("song_count", "首歌曲")}", tracks, tracks.first().artwork)
        }.sortedBy { it.title }
    }
    val layout = LocalAuraLayout.current
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (collections.isEmpty()) item { EmptyState(tr("no_collections", "暂无内容"), tr("library_content_hint", "本地音乐会自动归类到这里")) }
        items(collections.chunked(layout.gridColumns)) { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(if (layout.tablet) 12.dp else 14.dp)) {
                pair.forEach { collection -> CollectionTile(collection.title, collection.subtitle, collection.artwork, Modifier.weight(1f), tab == 1) { model.openCollection(collection) } }
                repeat(layout.gridColumns - pair.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

internal fun filterPlaylistSongs(songs: List<Song>, query: String): List<Song> {
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return songs
    return songs.filter { song ->
        terms.all { term ->
            song.title.contains(term, ignoreCase = true) ||
                song.artist.contains(term, ignoreCase = true) ||
                song.album.contains(term, ignoreCase = true) ||
                song.albumArtist.contains(term, ignoreCase = true)
        }
    }
}

internal fun shouldAutoLoadRemainingPlaylistTracks(
    searchActive: Boolean,
    hasMore: Boolean,
    loading: Boolean,
    failed: Boolean,
    accountRequiredMissing: Boolean = false,
): Boolean = searchActive && hasMore && !loading && !failed && !accountRequiredMissing

@Composable
internal fun PlaylistHeaderSearchBar(
    query: String,
    expanded: Boolean,
    onQueryChange: (String) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    onSearch: () -> Unit,
    placeholder: String = tr("playlist_search_hint", "搜索歌单内歌曲、歌手或专辑"),
) {
    if (LocalAppUiStyle.current == AppUiStyle.ONE) {
        val palette = rememberActiveOneUiPalette()
        val focusManager = LocalFocusManager.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalAuraLayout.current.pageMargin)
                .padding(top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(CircleShape)
                    .background(Color(palette.cardAlt), CircleShape)
                    .clickable { if (!expanded) onExpandedChange(true) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    MiuixIcons.Regular.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = Color(palette.subText),
                )
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = query,
                    onValueChange = {
                        if (!expanded) onExpandedChange(true)
                        onQueryChange(it)
                    },
                    singleLine = true,
                    textStyle = MiuixTheme.textStyles.body1.copy(color = Color(palette.text)),
                    cursorBrush = SolidColor(Color(palette.accentDeep)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                        onSearch()
                    }),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    placeholder,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MiuixTheme.textStyles.body1,
                                    color = Color(palette.subText),
                                )
                            }
                            inner()
                        }
                    },
                )
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(32.dp)) {
                        Icon(MiuixIcons.Regular.Close, tr("clear", "清空"), Modifier.size(18.dp), tint = Color(palette.subText))
                    }
                }
            }
            if (expanded || query.isNotEmpty()) {
                OneUiButton(
                    onClick = {
                        focusManager.clearFocus()
                        onQueryChange("")
                        onExpandedChange(false)
                    },
                ) {
                    Text(tr("cancel", "取消"), fontSize = 13.5.sp)
                }
            }
        }
        return
    }
    if (LocalAppUiStyle.current == AppUiStyle.MATERIAL) {
        val scheme = rememberActiveExpressiveScheme()
        val focusManager = LocalFocusManager.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalAuraLayout.current.pageMargin)
                .padding(top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(CircleShape)
                    .background(scheme.surfaceContainerHigh, CircleShape)
                    .clickable { if (!expanded) onExpandedChange(true) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    MiuixIcons.Regular.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = query,
                    onValueChange = {
                        if (!expanded) onExpandedChange(true)
                        onQueryChange(it)
                    },
                    singleLine = true,
                    textStyle = MiuixTheme.textStyles.body1.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                        onSearch()
                    }),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    placeholder,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MiuixTheme.textStyles.body1,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                )
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(32.dp)) {
                        Icon(MiuixIcons.Regular.Close, tr("clear", "清空"), Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                    }
                }
            }
            if (expanded || query.isNotEmpty()) {
                MaterialExpressiveButton(
                    onClick = {
                        focusManager.clearFocus()
                        onQueryChange("")
                        onExpandedChange(false)
                    },
                ) {
                    Text(tr("cancel", "取消"), fontSize = 13.5.sp)
                }
            }
        }
        return
    }
    val backdrop = LocalToolbarBackdrop.current
    val scheme = MiuixTheme.colorScheme
    val dark = scheme.surface.luminance() < .5f
    val colorStyle = LocalAppInterfaceStyle.current == AppInterfaceStyle.COLOR
    val advancedMaterial = LocalAdvancedMaterialEnabled.current
    val glassAvailable = advancedMaterial && backdrop != null && isRuntimeShaderSupported()
    val shape = remember { Capsule(RoundedCornerStyle.Continuous) }
    val surfaceTint = remember(scheme.surfaceContainer, scheme.primaryContainer, dark) {
        lerp(scheme.surfaceContainer, scheme.primaryContainer, if (dark) .22f else .16f)
    }
    val collapsed = LocalToolbarCollapsed.current
    val glassModifier: Modifier = if (glassAvailable) {
        val activeBackdrop = requireNotNull(backdrop)
        val drawTint = surfaceTint.copy(alpha = if (colorStyle) .62f else .58f)
        Modifier.drawBackdrop(
            backdrop = activeBackdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur((if (colorStyle) 14.dp else 4.dp).toPx())
                lens(
                    10.dp.toPx(),
                    (if (colorStyle) 0.dp else 24.dp).toPx(),
                    depthEffect = true,
                    chromaticAberration = !colorStyle,
                )
            },
            highlight = { Highlight.Default.copy(alpha = if (colorStyle) .82f else .65f) },
            shadow = {
                Shadow.Default.copy(
                    radius = 10.dp,
                    color = if (!dark) Color.Gray else Color.Black,
                    alpha = .14f + collapsed * .18f,
                )
            },
            innerShadow = null,
            onDrawSurface = { drawRect(drawTint) },
        )
    } else {
        Modifier.background(surfaceTint.copy(alpha = if (!advancedMaterial) 1f else .92f), shape)
    }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val animationScope = rememberCoroutineScope()
    val highlight = remember(animationScope) {
        InteractiveHighlight(animationScope) { size, offset ->
            Offset(offset.x.coerceIn(0f, size.width), offset.y.coerceIn(0f, size.height))
        }
    }
    val searchActive = expanded || query.isNotEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 8.dp)
            .height(CouiSearchBarDefaults.height)
            .padding(horizontal = CouiSearchBarDefaults.horizontalMargin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(CouiSearchBarDefaults.surfaceHeight)
                .clip(shape)
                .then(glassModifier)
                .then(highlight.gestureModifier)
                .then(highlight.modifier)
                .clickable(interactionSource = null, indication = null) {
                    if (!expanded) onExpandedChange(true)
                    focusRequester.requestFocus()
                    keyboard?.show()
                }
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MiuixIcons.Regular.Search,
                contentDescription = null,
                modifier = Modifier.size(CouiSearchBarDefaults.iconSize),
                tint = scheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = {
                    if (!expanded) onExpandedChange(true)
                    onQueryChange(it)
                },
                singleLine = true,
                textStyle = MiuixTheme.textStyles.body1.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    onSearch()
                }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = placeholder,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MiuixTheme.textStyles.body1,
                                color = scheme.onSurfaceVariantSummary,
                            )
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Close,
                        contentDescription = tr("clear", "清空"),
                        modifier = Modifier.size(18.dp),
                        tint = scheme.onSurfaceVariantSummary,
                    )
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
        AnimatedVisibility(
            visible = searchActive,
            enter = fadeIn(tween(220)) + expandHorizontally(expandFrom = Alignment.End),
            exit = fadeOut(tween(180)) + shrinkHorizontally(shrinkTowards = Alignment.End),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(CouiSearchBarDefaults.cancelGap))
                Box(
                    modifier = Modifier
                        .height(CouiSearchBarDefaults.surfaceHeight)
                        .clip(shape)
                        .then(glassModifier)
                        .clickable {
                            focusManager.clearFocus()
                            keyboard?.hide()
                            onQueryChange("")
                            onExpandedChange(false)
                        }
                        .padding(horizontal = CouiSearchBarDefaults.cancelPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tr("cancel", "取消"),
                        color = scheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

internal fun resolvePlaylistCoverUri(
    model: AuraViewModel,
    collection: MusicCollection,
    cloud: CloudPlaylistContent?,
): String {
    if (collection.artwork.isNotBlank()) return collection.artwork
    val localPlaylist = model.history.playlists().find { "playlist:${it.id}" == collection.key }
    val tracks = if (localPlaylist != null) {
        model.songs.filter { it.key in localPlaylist.songKeys }
    } else {
        cloud?.songs ?: collection.songs
    }
    return tracks.firstOrNull { it.artwork.isNotBlank() }?.artwork.orEmpty()
}

@Composable
private fun BlurredPlaylistCoverBackdrop(
    coverUri: String,
    bannerTheme: HomeBannerTheme,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val surface = MiuixTheme.colorScheme.surface
    val dark = surface.luminance() < 0.5f
    val advancedMaterial = LocalAdvancedMaterialEnabled.current
    if (coverUri.isBlank()) {
        Box(
            modifier.background(
                Brush.linearGradient(
                    listOf(
                        MiuixTheme.colorScheme.primaryContainer,
                        MiuixTheme.colorScheme.tertiaryContainer,
                        MiuixTheme.colorScheme.surfaceContainerHigh,
                    ),
                ),
            ),
        )
        return
    }
    val request = remember(coverUri, context) {
        val placeholderKey = findCachedArtworkMemoryEntry(SingletonImageLoader.get(context), coverUri)?.key
        ImageRequest.Builder(context)
            .data(coverUri)
            .size(384)
            .precision(Precision.INEXACT)
            .apply { if (placeholderKey != null) placeholderMemoryCacheKey(placeholderKey) }
            .build()
    }
    Box(modifier.clipToBounds().background(bannerTheme.surfaceColor)) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = 1.16f
                    scaleY = 1.16f
                }
                .then(if (advancedMaterial) Modifier.blur(24.dp) else Modifier),
        )
        val scrimAlpha = when {
            !advancedMaterial -> if (dark) 0.62f else 0.68f
            dark -> 0.22f
            else -> 0.26f
        }
        Box(Modifier.matchParentSize().background(bannerTheme.surfaceColor.copy(alpha = scrimAlpha)))
    }
}

@Composable
private fun PlaylistHeroHeader(
    title: String,
    coverUri: String,
    metaText: String,
    bannerTheme: HomeBannerTheme,
    immersiveTopPadding: Dp,
    pageMargin: Dp,
    playEnabled: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    val layout = LocalAuraLayout.current
    val coverSize = if (layout.tablet) 132.dp else 116.dp
    val adaptiveTextColors = rememberAdaptiveBannerTextColors(
        bannerUri = coverUri,
        bannerStyle = LocalBannerStyle.IMMERSIVE,
        styleConfig = LocalBannerStyleConfig(backgroundStyle = HomeBackgroundStyle.COLOR),
        bannerTheme = bannerTheme,
    )
    val titleColor = if (bannerTheme.isCustom) adaptiveTextColors.titleColor else MiuixTheme.colorScheme.onSurface
    val subtitleColor = if (bannerTheme.isCustom) adaptiveTextColors.subtitleColor else MiuixTheme.colorScheme.onSurfaceVariantSummary
    val playAccent = if (bannerTheme.isCustom) bannerTheme.accent else MiuixTheme.colorScheme.primary
    val playOnAccent = if (bannerTheme.isCustom) bannerTheme.onAccent else MiuixTheme.colorScheme.onPrimary
    val shuffleBg = titleColor.copy(alpha = 0.14f)
    val topPad = immersiveTopPadding + 10.dp

    Box(Modifier.fillMaxWidth()) {
        ImmersiveBannerSurfaceBackdrop(
            bannerTheme = bannerTheme,
            backgroundStyle = HomeBackgroundStyle.COLOR,
            modifier = Modifier.matchParentSize(),
        ) {
            BlurredPlaylistCoverBackdrop(
                coverUri = coverUri,
                bannerTheme = bannerTheme,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topPad, start = pageMargin, end = pageMargin, bottom = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val coverModifier = if (LocalAdaptIrregularCovers.current && coverUri.isNotBlank()) {
                Modifier.width(coverSize).heightIn(max = coverSize)
            } else {
                Modifier.size(coverSize)
            }
            Artwork(coverUri, coverModifier, cornerRadius = 16.dp)
            Spacer(Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.Bold,
                    color = titleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = metaText,
                    style = MiuixTheme.textStyles.body2,
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .height(42.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .alpha(if (playEnabled) 1f else 0.45f)
                            .clickable(enabled = playEnabled, onClick = onPlay)
                            .background(playAccent)
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(R.drawable.ic_player_play),
                                contentDescription = tr("play", "播放"),
                                modifier = Modifier.size(18.dp),
                                colorFilter = ColorFilter.tint(playOnAccent),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = tr("play", "播放"),
                                color = playOnAccent,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .alpha(if (playEnabled) 1f else 0.45f)
                            .clickable(enabled = playEnabled, onClick = onShuffle)
                            .background(shuffleBg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_player_shuffle),
                            contentDescription = tr("shuffle", "随机播放"),
                            modifier = Modifier.size(20.dp),
                            colorFilter = ColorFilter.tint(titleColor),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CollectionPage(
    model: AuraViewModel,
    padding: PaddingValues,
    collection: MusicCollection,
    cloud: CloudPlaylistContent?,
    searchQuery: String = "",
    searchExpanded: Boolean = false,
    immersiveTopPadding: Dp = 0.dp,
    bannerTheme: HomeBannerTheme? = null,
    addToPlaylist: Boolean = false,
    onDismissAddToPlaylist: () -> Unit = {},
    sortOrder: SongSort = SongSort.DEFAULT,
    selectionMode: Boolean = false,
    selectedKeys: Set<String> = emptySet(),
    onEnterSelection: (Song) -> Unit = {},
    onToggleSelect: (Song) -> Unit = {},
    onResetSortToDefault: () -> Unit = {},
) {
    val revision = model.history.revision
    val localPlaylist = remember(collection.key, revision) { model.history.playlists().find { "playlist:${it.id}" == collection.key } }
    val visibleByKey = remember(model.songs) { model.songs.associateBy { it.key } }
    val rawTracks = remember(localPlaylist, model.songs, cloud?.songs, collection.songs, visibleByKey) {
        if (localPlaylist != null) {
            localPlaylist.songKeys.mapNotNull(visibleByKey::get).distinctBy { it.key }
        } else {
            (cloud?.songs ?: collection.songs.filter { it.cloud || it.key in visibleByKey }).distinctBy { it.key }
        }
    }
    val tracks = remember(rawTracks, sortOrder) { sortOrder.applyToPlaylist(rawTracks) }
    val filtering = searchQuery.isNotBlank()
    val searchActive = filtering || searchExpanded
    LaunchedEffect(cloud, searchActive, cloud?.songs?.size, cloud?.loading, cloud?.hasMore, cloud?.failed, model.account != null) {
        if (cloud != null && shouldAutoLoadRemainingPlaylistTracks(
                searchActive = searchActive,
                hasMore = cloud.hasMore,
                loading = cloud.loading,
                failed = cloud.failed,
                accountRequiredMissing = cloud.source == "playlist" && model.account == null,
            )
        ) {
            model.loadMoreCloudSongsFor(cloud)
        }
    }
    val filteredTracks = remember(tracks, searchQuery) { filterPlaylistSongs(tracks, searchQuery) }
    val isLocalUserPlaylist = localPlaylist != null
    val isOwnedCloudPlaylist = model.isOwnedNeteasePlaylist(cloud)
    val canReorder = !filtering && sortOrder == SongSort.DEFAULT && (isLocalUserPlaylist || isOwnedCloudPlaylist)

    val coverUri = remember(collection.artwork, rawTracks) {
        collection.artwork.ifBlank { rawTracks.firstOrNull { it.artwork.isNotBlank() }?.artwork.orEmpty() }
    }
    val resolvedTheme = bannerTheme ?: rememberHomeBannerTheme(coverUri)
    val listeningRank = cloud?.source == "rank"
    val rankByKey = remember(tracks, listeningRank) {
        if (listeningRank) tracks.mapIndexed { index, song -> song.key to (index + 1) }.toMap() else emptyMap()
    }
    val layout = LocalAuraLayout.current
    val pageMargin = layout.pageMargin
    val columns = layout.listColumns
    val listState = rememberLazyListState()
    LaunchedEffect(searchQuery) { listState.scrollToItem(0) }
    val activeTopPadding = if (immersiveTopPadding > 0.dp) immersiveTopPadding else padding.calculateTopPadding()
    val countText = if (filtering) "${filteredTracks.size} / ${cloud?.total ?: tracks.size} ${tr("song_count", "首歌曲")}"
        else "${cloud?.total ?: tracks.size} ${tr("song_count", "首歌曲")}"
    val durationText = formatAlbumTotalDuration(if (filtering) filteredTracks else tracks)
    val metaText = listOfNotNull(countText, durationText).joinToString(" · ")

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var orderedTracks by remember(collection.key) { mutableStateOf(filteredTracks) }
    var draggingKey by remember(collection.key) { mutableStateOf<String?>(null) }
    var dragSource by remember(collection.key) { mutableStateOf<String?>(null) }
    var draggingOffsetY by remember(collection.key) { mutableFloatStateOf(0f) }
    var settlingKey by remember(collection.key) { mutableStateOf<String?>(null) }
    val settlingOffset = remember(collection.key) { Animatable(0f) }

    LaunchedEffect(filteredTracks) {
        if (draggingKey == null && settlingKey != null && orderedTracks != filteredTracks) {
            settlingKey = null
            orderedTracks = filteredTracks
        }
    }
    LaunchedEffect(selectionMode, canReorder) {
        if (!selectionMode || !canReorder) {
            draggingKey = null
            dragSource = null
            draggingOffsetY = 0f
            settlingKey = null
            settlingOffset.snapTo(0f)
        }
    }

    val currentTracks by rememberUpdatedState(orderedTracks)
    val currentFilteredTracks by rememberUpdatedState(filteredTracks)
    val currentLocalPlaylist by rememberUpdatedState(localPlaylist)
    val currentCloud by rememberUpdatedState(cloud)
    val currentSortOrder by rememberUpdatedState(sortOrder)

    var containerWindowPos by remember { mutableStateOf(Offset.Zero) }
    var activeSlotWindowPos by remember { mutableStateOf(Offset.Zero) }
    var activeSlotWidthPx by remember { mutableIntStateOf(0) }

    LaunchedEffect(draggingKey) {
        val key = draggingKey ?: return@LaunchedEffect
        val topEdgePx = with(density) { 120.dp.toPx() }
        val bottomEdgePx = with(density) { 120.dp.toPx() }
        val maxStepPx = with(density) { 14.dp.toPx() }
        while (draggingKey == key) {
            val viewportHeight = listState.layoutInfo.viewportSize.height.toFloat()
            if (viewportHeight > 0f && activeSlotWidthPx > 0) {
                val relTop = activeSlotWindowPos.y - containerWindowPos.y + draggingOffsetY
                val relBottom = relTop + with(density) { 64.dp.toPx() }
                val scrollDelta = when {
                    relTop < topEdgePx -> -((topEdgePx - relTop) / topEdgePx).coerceIn(0f, 1f) * maxStepPx
                    relBottom > viewportHeight - bottomEdgePx -> ((relBottom - (viewportHeight - bottomEdgePx)) / bottomEdgePx).coerceIn(0f, 1f) * maxStepPx
                    else -> 0f
                }
                if (abs(scrollDelta) > 0.5f) {
                    val consumed = listState.scrollBy(scrollDelta)
                    if (abs(consumed) > 0.5f) {
                        draggingOffsetY += consumed
                    }
                }
            }
            delay(16)
        }
    }

    fun startDrag(key: String) {
        if (!canReorder || !selectionMode) return
        settlingKey = null
        orderedTracks = currentFilteredTracks
        scope.launch { settlingOffset.snapTo(0f) }
        draggingKey = key
        draggingOffsetY = 0f
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun updateDrag(key: String, deltaY: Float) {
        if (draggingKey != key) return
        draggingOffsetY += deltaY
        val activeTracks = currentTracks
        val currentIndex = activeTracks.indexOfFirst { it.key == key }
        if (currentIndex < 0) return

        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val draggedInfo = visibleItems.firstOrNull { it.key == key } ?: return
        val draggedCenterY = draggedInfo.offset + draggingOffsetY + draggedInfo.size / 2f

        if (draggingOffsetY > 0f && currentIndex < activeTracks.lastIndex) {
            val nextSong = activeTracks[currentIndex + 1]
            val nextInfo = visibleItems.firstOrNull { it.key == nextSong.key }
            if (nextInfo != null && nextInfo.index == draggedInfo.index + 1 &&
                draggedCenterY > nextInfo.offset + nextInfo.size / 2f
            ) {
                orderedTracks = activeTracks.toMutableList().apply {
                    add(currentIndex + 1, removeAt(currentIndex))
                }
                draggingOffsetY -= nextInfo.size
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                return
            }
        }

        if (draggingOffsetY < 0f && currentIndex > 0) {
            val prevSong = activeTracks[currentIndex - 1]
            val prevInfo = visibleItems.firstOrNull { it.key == prevSong.key }
            if (prevInfo != null && prevInfo.index == draggedInfo.index - 1 &&
                draggedCenterY < prevInfo.offset + prevInfo.size / 2f
            ) {
                orderedTracks = activeTracks.toMutableList().apply {
                    add(currentIndex - 1, removeAt(currentIndex))
                }
                draggingOffsetY += prevInfo.size
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    fun finishDrag(key: String) {
        if (draggingKey != key) return
        val settledFrom = draggingOffsetY
        val finalOrder = currentTracks
        draggingKey = null
        draggingOffsetY = 0f
        if (finalOrder != currentFilteredTracks) {
            if (currentSortOrder != SongSort.DEFAULT) onResetSortToDefault()
            val local = currentLocalPlaylist
            val activeCloud = currentCloud
            if (local != null) {
                model.reorderLocalPlaylist(local.id, finalOrder.map { it.key })
            } else if (activeCloud != null && isOwnedCloudPlaylist) {
                model.reorderCloudPlaylist(activeCloud, finalOrder)
            }
        }
        if (abs(settledFrom) > 0.5f) {
            settlingKey = key
            scope.launch {
                settlingOffset.snapTo(settledFrom)
                settlingOffset.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 420f))
                if (settlingKey == key) settlingKey = null
            }
        }
    }

    val surfaceBg = MiuixTheme.colorScheme.surface
    val cardContainerColor = MiuixTheme.colorScheme.surfaceContainerHigh
    val dark = surfaceBg.luminance() < 0.5f
    val bottomExtra = if (selectionMode) 76.dp else 0.dp
    val isDraggingOrSettling = canReorder && (draggingKey != null || settlingKey != null)
    val displayTracks = if (isDraggingOrSettling) orderedTracks else filteredTracks
    val pullToRefreshState = rememberPullToRefreshState()
    var isRefreshing by remember(collection.key) { mutableStateOf(false) }

    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) return@LaunchedEffect
        val start = System.currentTimeMillis()
        try {
            when {
                cloud != null -> {
                    model.refreshCloudSongs()
                    snapshotFlow { model.cloudCollection?.loading == true }.first { !it }
                }
                collection.key == "cloud:daily" -> {
                    model.refreshCloudDailyCollectionNow()
                }
                else -> {
                    model.refreshLibrary()
                    snapshotFlow { model.scanning }.first { !it }
                }
            }
            val elapsed = System.currentTimeMillis() - start
            if (elapsed < 350L) delay(350L - elapsed)
        } finally {
            isRefreshing = false
        }
    }

    PullToRefresh(
        isRefreshing = isRefreshing,
        onRefresh = { if (draggingKey == null) isRefreshing = true },
        modifier = Modifier.fillMaxSize(),
        pullToRefreshState = pullToRefreshState,
        contentPadding = PaddingValues(top = activeTopPadding, bottom = padding.calculateBottomPadding()),
        topAppBarScrollBehavior = LocalPageScroll.current,
        color = MiuixTheme.colorScheme.primary,
        refreshTexts = listOf(
            tr("pull_to_refresh", "下拉刷新"),
            tr("release_to_refresh", "松开刷新"),
            tr("refreshing", "正在刷新…"),
            tr("refresh_completed", "刷新完成"),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    if (selectionMode && canReorder) containerWindowPos = it.positionInWindow()
                },
        ) {
        LazyColumn(
            pageContentModifier().let { if (searchActive) it.imePadding() else it },
            state = listState,
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + bottomExtra),
            userScrollEnabled = draggingKey == null,
        ) {
            item("playlist_hero") {
                PlaylistHeroHeader(
                    title = collection.title,
                    coverUri = coverUri,
                    metaText = metaText,
                    bannerTheme = resolvedTheme,
                    immersiveTopPadding = activeTopPadding,
                    pageMargin = pageMargin,
                    playEnabled = displayTracks.isNotEmpty(),
                    onPlay = { model.playCollection(displayTracks, content = if (filtering) null else cloud) },
                    onShuffle = { model.playCollection(displayTracks, content = if (filtering) null else cloud, shuffle = true) },
                )
            }
            if (displayTracks.isEmpty() && (cloud == null || !cloud.loading && !cloud.failed && !cloud.hasMore)) item {
                Box(Modifier.padding(horizontal = pageMargin)) { EmptyState(tr("no_songs", "没有找到歌曲")) }
            }
            if (listeningRank) {
                itemsIndexed(displayTracks, key = { _, song -> "rank-${song.key}" }) { index, song ->
                    val rank = rankByKey[song.key] ?: (index + 1)
                    Box(Modifier.padding(horizontal = pageMargin)) {
                        RankSongRow(
                            rank = rank,
                            song = song,
                            model = model,
                            selectionMode = selectionMode,
                            selected = song.key in selectedKeys,
                            onClick = {
                                if (selectionMode) onToggleSelect(song)
                                else model.playCollection(displayTracks, index, if (filtering) null else cloud)
                            },
                            onLongPress = { onEnterSelection(song) },
                        )
                    }
                }
            } else if (canReorder && (selectionMode || columns == 1)) {
                itemsIndexed(displayTracks, key = { _, song -> song.key }) { index, song ->
                    val isDragging = draggingKey == song.key
                    val isSettling = settlingKey == song.key
                    val isFloating = isDragging || isSettling
                    val placementModifier = if (isDragging) {
                        Modifier
                    } else {
                        Modifier.animateItem(
                            fadeInSpec = tween(220),
                            fadeOutSpec = tween(200),
                            placementSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                    }
                    var rowCoords by remember(song.key) { mutableStateOf<LayoutCoordinates?>(null) }
                    var handleCoords by remember(song.key) { mutableStateOf<LayoutCoordinates?>(null) }
                    var lastRowWindowY by remember(song.key) { mutableFloatStateOf(0f) }
                    var lastHandleWindowY by remember(song.key) { mutableFloatStateOf(0f) }
                    val dragEnabled = selectionMode && canReorder
                    val slotPositionedModifier = if (dragEnabled) {
                        Modifier.onGloballyPositioned {
                            rowCoords = it
                            if (isDragging) {
                                val pos = it.positionInWindow()
                                if (pos != activeSlotWindowPos) activeSlotWindowPos = pos
                                if (it.size.width != activeSlotWidthPx) activeSlotWidthPx = it.size.width
                            }
                        }
                    } else {
                        Modifier
                    }
                    val rowDragModifier = if (dragEnabled) {
                        Modifier.pointerInput(song.key, selectionMode, canReorder) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { startOffset ->
                                    if (draggingKey != null) return@detectDragGesturesAfterLongPress
                                    val coords = rowCoords
                                    if (coords != null && coords.isAttached) {
                                        activeSlotWindowPos = coords.positionInWindow()
                                        activeSlotWidthPx = coords.size.width
                                        lastRowWindowY = coords.localToWindow(startOffset).y
                                    } else {
                                        lastRowWindowY = startOffset.y
                                    }
                                    dragSource = "row"
                                    startDrag(song.key)
                                },
                                onDrag = { change, dragAmount ->
                                    if (dragSource != "row") return@detectDragGesturesAfterLongPress
                                    change.consume()
                                    val coords = rowCoords
                                    val deltaY = if (coords != null && coords.isAttached) {
                                        val windowY = coords.localToWindow(change.position).y
                                        val d = windowY - lastRowWindowY
                                        lastRowWindowY = windowY
                                        d
                                    } else {
                                        dragAmount.y
                                    }
                                    updateDrag(song.key, deltaY)
                                },
                                onDragEnd = {
                                    if (dragSource == "row") {
                                        dragSource = null
                                        finishDrag(song.key)
                                    }
                                },
                                onDragCancel = {
                                    if (dragSource == "row") {
                                        dragSource = null
                                        finishDrag(song.key)
                                    }
                                },
                            )
                        }
                    } else {
                        Modifier
                    }
                    val handleDragModifier = if (dragEnabled) {
                        Modifier
                            .onGloballyPositioned { handleCoords = it }
                            .pointerInput(song.key, selectionMode, canReorder) {
                                detectDragGestures(
                                    onDragStart = { startOffset ->
                                        if (draggingKey != null) return@detectDragGestures
                                        val slotCoords = rowCoords
                                        if (slotCoords != null && slotCoords.isAttached) {
                                            activeSlotWindowPos = slotCoords.positionInWindow()
                                            activeSlotWidthPx = slotCoords.size.width
                                        }
                                        val coords = handleCoords
                                        lastHandleWindowY = if (coords != null && coords.isAttached) {
                                            coords.localToWindow(startOffset).y
                                        } else {
                                            startOffset.y
                                        }
                                        dragSource = "handle"
                                        startDrag(song.key)
                                    },
                                    onDrag = { change, dragAmount ->
                                        if (dragSource != "handle") return@detectDragGestures
                                        change.consume()
                                        val coords = handleCoords
                                        val deltaY = if (coords != null && coords.isAttached) {
                                            val windowY = coords.localToWindow(change.position).y
                                            val d = windowY - lastHandleWindowY
                                            lastHandleWindowY = windowY
                                            d
                                        } else {
                                            dragAmount.y
                                        }
                                        updateDrag(song.key, deltaY)
                                    },
                                    onDragEnd = {
                                        if (dragSource == "handle") {
                                            dragSource = null
                                            finishDrag(song.key)
                                        }
                                    },
                                    onDragCancel = {
                                        if (dragSource == "handle") {
                                            dragSource = null
                                            finishDrag(song.key)
                                        }
                                    },
                                )
                            }
                    } else {
                        Modifier
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = pageMargin)
                            .then(placementModifier)
                            .graphicsLayer { alpha = if (isFloating) 0f else 1f }
                            .then(slotPositionedModifier),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(rowDragModifier),
                        ) {
                        SongRow(
                            song = song,
                            model = model,
                            onClick = {
                                if (selectionMode) onToggleSelect(song)
                                else model.playCollection(displayTracks, index, if (filtering) null else cloud)
                            },
                            onLongAction = localPlaylist?.let { playlist ->
                                {
                                    model.removeSongsFromLocalPlaylist(playlist.id, setOf(song.key))
                                }
                            },
                            selectionMode = selectionMode,
                            selected = song.key in selectedKeys,
                            showDragHandle = true,
                            onLongPress = { onEnterSelection(song) },
                            dragHandleModifier = handleDragModifier,
                        )
                        }
                    }
                }
            } else {
                songRows(
                    tracks = displayTracks,
                    model = model,
                    columns = columns,
                    horizontalPadding = pageMargin,
                    onRemove = localPlaylist?.let { playlist ->
                        { song ->
                            model.removeSongsFromLocalPlaylist(playlist.id, setOf(song.key))
                        }
                    },
                    onPlay = { model.playCollection(displayTracks, it, if (filtering) null else cloud) },
                    selectionMode = selectionMode,
                    selectedKeys = selectedKeys,
                    onToggleSelect = onToggleSelect,
                    onLongPress = onEnterSelection,
                )
            }
            if (cloud != null && (cloud.hasMore || cloud.loading || cloud.failed)) item("cloud_song_paging") {
                val detail = if (searchActive && cloud.total > 0) "(${cloud.songs.size}/${cloud.total})" else ""
                Box(Modifier.padding(horizontal = pageMargin)) {
                    CloudPagingFooter(cloud.loading, cloud.failed, detail) { model.loadMoreCloudSongsFor(cloud) }
                }
            }
        }

        val activeKey = draggingKey ?: settlingKey
        val activeSong = activeKey?.let { key -> displayTracks.firstOrNull { it.key == key } }
        if (activeSong != null && activeSlotWidthPx > 0) {
            val isDragging = draggingKey == activeSong.key
            val dragScale by animateFloatAsState(
                targetValue = if (isDragging) 1.025f else 1f,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 460f),
                label = "playlist_floating_scale",
            )
            val dragElevation by animateDpAsState(
                targetValue = if (isDragging) 12.dp else 0.dp,
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 420f),
                label = "playlist_floating_elevation",
            )
            val slotWidthDp = with(density) { activeSlotWidthPx.toDp() }
            val rowCorner = when (LocalAppUiStyle.current) {
                AppUiStyle.ONE -> 16.dp
                AppUiStyle.MATERIAL -> 18.dp
                AppUiStyle.HYPER -> 12.dp
            }
            val rowShape = RoundedCornerShape(rowCorner)
            val floatingTint = cardContainerColor.copy(alpha = if (dark) 0.94f else 0.96f)
            Box(
                modifier = Modifier
                    .offset {
                        val activeOffsetY = if (draggingKey == activeSong.key) draggingOffsetY else settlingOffset.value
                        IntOffset(
                            (activeSlotWindowPos.x - containerWindowPos.x).roundToInt(),
                            (activeSlotWindowPos.y - containerWindowPos.y + activeOffsetY).roundToInt(),
                        )
                    }
                    .width(slotWidthDp)
                    .zIndex(10f)
                    .graphicsLayer {
                        scaleX = dragScale
                        scaleY = dragScale
                        shape = rowShape
                        clip = true
                        shadowElevation = dragElevation.toPx()
                    }
                    .clip(rowShape)
                    .background(floatingTint, rowShape)
                    .border(0.75.dp, MiuixTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.14f else 0.08f), rowShape),
            ) {
                SongRow(
                    song = activeSong,
                    model = model,
                    onClick = {},
                    selectionMode = true,
                    selected = activeSong.key in selectedKeys,
                    showDragHandle = true,
                    outerVerticalPadding = 0.dp,
                )
            }
        }
        }
    }
    if (addToPlaylist && localPlaylist != null) PlaylistSongPicker(model, localPlaylist, onDismissAddToPlaylist)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RankSongRow(
    rank: Int,
    song: Song,
    model: AuraViewModel,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    val openActions = LocalSongActions.current
    val backdrop = LocalToolbarBackdrop.current
    val haptics = LocalHapticFeedback.current
    val rowBg by animateColorAsState(
        targetValue = if (selectionMode && selected) MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.38f) else Color.Transparent,
        animationSpec = tween(180),
        label = "rankRowBg",
    )
    val interactionModifier = if (onLongPress != null) {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = {
                if (!selectionMode) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                }
            },
        )
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(rowBg)
            .then(interactionModifier)
            .padding(vertical = 5.5.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = selectionMode,
            enter = expandHorizontally(tween(240, easing = FastOutSlowInEasing), expandFrom = Alignment.Start) +
                fadeIn(tween(200)) +
                scaleIn(tween(240), initialScale = 0.78f),
            exit = shrinkHorizontally(tween(220, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Start) +
                fadeOut(tween(160)) +
                scaleOut(tween(220), targetScale = 0.78f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AuraCheckbox(
                    checked = selected,
                    onCheckedChange = onClick,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
        }
        Text(rank.toString(), Modifier.width(30.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontWeight = FontWeight.Bold)
        Artwork(song.artwork, Modifier.size(44.dp), cornerRadius = ListArtworkCornerRadius)
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text(song.title.ifBlank { tr("unknown_title", "未命名歌曲") }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp,
                fontWeight = if (model.current?.key == song.key) FontWeight.Bold else FontWeight.Medium)
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (song.shouldShowVipBadge(model.settings.value)) SongVipBadge(MiuixTheme.colorScheme.primary)
                Text("${song.artist} · ${song.album}", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("${song.playCount} ${tr("play_count", "次")}", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        AnimatedVisibility(
            visible = !selectionMode,
            enter = expandHorizontally(tween(240, easing = FastOutSlowInEasing), expandFrom = Alignment.End) +
                fadeIn(tween(200)) +
                scaleIn(tween(240), initialScale = 0.8f),
            exit = shrinkHorizontally(tween(200, easing = FastOutSlowInEasing), shrinkTowards = Alignment.End) +
                fadeOut(tween(160)) +
                scaleOut(tween(200), targetScale = 0.8f),
        ) {
            IconButton(onClick = { openActions(SongActionRequest(song, backdrop)) }, modifier = Modifier.size(44.dp)) {
                Image(AuraMoreHorizontalIcon, tr("more", "更多"), Modifier.size(22.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary))
            }
        }
    }
}

@Composable
fun LocalPlaylistsPage(model: AuraViewModel, padding: PaddingValues) {
    val revision = model.history.revision
    val playlists = remember(revision) { model.history.playlists() }
    val songByKey = remember(model.songs) { model.songs.associateBy { it.key } }
    var creating by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<LocalPlaylist?>(null) }
    var deletion by remember { mutableStateOf<LocalPlaylist?>(null) }
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        run {
            items(playlists, key = { it.id }) { playlist ->
                val tracks = playlist.songKeys.mapNotNull(songByKey::get)
                SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    Row(Modifier.fillMaxWidth().clickable { model.openCollection(MusicCollection("playlist:${playlist.id}", playlist.name, "", tracks)) }
                        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(tracks.firstOrNull()?.artwork.orEmpty(), Modifier.size(50.dp), cornerRadius = ListArtworkCornerRadius)
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(playlist.name, fontWeight = FontWeight.SemiBold)
                            Text("${tracks.size} ${tr("song_count", "首歌曲")}", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        GlassIconDropdownMenu(DropdownEntry(listOf(
                            DropdownItem(tr("add_songs", "添加歌曲"), onClick = { editing = playlist }),
                            DropdownItem(tr("delete", "删除"), onClick = { deletion = playlist }),
                        )), LocalToolbarBackdrop.current) { Text("···", fontSize = 24.sp) }
                    }
                }
            }
            item("create_playlist") {
                SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                    Preference(tr("new_playlist", "新建歌单")) { creating = true; name = "" }
                }
            }
        }
    }
    WindowDialog(creating, title = tr("new_playlist", "新建歌单"), onDismissRequest = { creating = false }) {
        AuraTextField(name, { name = it }, label = tr("playlist_name", "歌单名称"), singleLine = true, modifier = Modifier.fillMaxWidth())
        GlassDialogButton({
            model.history.savePlaylists(playlists + LocalPlaylist(UUID.randomUUID().toString(), name.trim(), emptyList()))
            creating = false
        }, Modifier.fillMaxWidth().padding(top = 16.dp), enabled = name.isNotBlank(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("create", "创建")) }
    }
    WindowDialog(deletion != null, title = tr("delete_playlist", "删除歌单？"), summary = tr("delete_playlist_summary", "只会删除歌单，不会删除音乐文件"), onDismissRequest = { deletion = null }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton({ deletion = null }, Modifier.weight(1f)) { Text(tr("cancel", "取消")) }
            GlassDialogButton({ model.history.savePlaylists(playlists.filterNot { it.id == deletion?.id }); deletion = null }, Modifier.weight(1f)) { Text(tr("delete", "删除")) }
        }
    }
    editing?.let { PlaylistSongPicker(model, it) { editing = null } }
}

@Composable
internal fun CloudPagingFooter(loading: Boolean, failed: Boolean, load: () -> Unit) =
    CloudPagingFooter(loading, failed, "", load)

@Composable
internal fun CloudPagingFooter(loading: Boolean, failed: Boolean, detail: String, load: () -> Unit) {
    if (!loading && !failed) LaunchedEffect(Unit) { load() }
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        if (failed) GlassDialogButton(load) { Text(tr("cloud_load_retry", "加载失败，点击重试")) }
        else Text(
            if (detail.isBlank()) tr("loading", "正在加载…") else "${tr("loading", "正在加载…")} $detail",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun PlaylistSongPicker(model: AuraViewModel, playlist: LocalPlaylist, dismiss: () -> Unit) {
    var selected by remember(playlist.id) { mutableStateOf(playlist.songKeys.toSet()) }
    WindowDialog(true, title = tr("add_songs", "添加歌曲"), onDismissRequest = dismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(model.songs, key = { it.key }) { song ->
                BasicComponent(title = song.title, summary = song.artist, onClick = { selected = if (song.key in selected) selected - song.key else selected + song.key },
                    endActions = { AuraCheckbox(checked = song.key in selected, onCheckedChange = { selected = if (song.key in selected) selected - song.key else selected + song.key }) })
            }
        }
        GlassDialogButton({
            model.history.savePlaylists(model.history.playlists().map {
                if (it.id == playlist.id) {
                    val retained = it.songKeys.filter { key -> key in selected }
                    val added = selected.filterNot { key -> key in retained }
                    it.copy(songKeys = retained + added)
                } else it
            })
            dismiss()
        }, Modifier.fillMaxWidth().padding(top = 12.dp), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("save", "保存")) }
    }
}
