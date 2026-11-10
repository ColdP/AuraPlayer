package btm.m.auraplayer.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.*
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.ui.navigation.*
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.*

internal val colorPresets = listOf(
    Triple("blue", "澄空蓝", 0xFF6E92D8), Triple("cyan", "清泉青", 0xFF4B9BAA),
    Triple("teal", "薄荷绿", 0xFF4D9B86), Triple("moss", "苔庭绿", 0xFF71854C),
    Triple("amber", "暖琥珀", 0xFFB0833D), Triple("apricot", "杏霞", 0xFFC17C5B),
    Triple("rose", "玫瑰砂", 0xFFAD6E85), Triple("wisteria", "藤烟紫", 0xFF8973B0),
    Triple("indigo", "暮靛", 0xFF6376B5), Triple("slate", "岩雾", 0xFF73858A),
    Triple("mono", "黑白极简", 0xFF777777),
)
internal val paletteStyles = listOf(ThemePaletteStyle.TonalSpot, ThemePaletteStyle.Neutral, ThemePaletteStyle.Vibrant,
    ThemePaletteStyle.Expressive, ThemePaletteStyle.Rainbow, ThemePaletteStyle.FruitSalad, ThemePaletteStyle.Monochrome,
    ThemePaletteStyle.Fidelity, ThemePaletteStyle.Content)

@Composable
fun AuraRoot(model: AuraViewModel) {
    val settings = model.settings.value
    val context = LocalContext.current
    SideEffect { I18n.language = settings.language }
    val seed = when (settings.color) {
        "custom" -> model.settings.previewColor ?: settings.customColor
        "system" -> context.getColor(android.R.color.system_accent1_500)
        else -> colorPresets.firstOrNull { it.first == settings.color }?.third?.toInt()
    }
    val controller = remember(settings.theme, settings.monet, seed, settings.palette, settings.colorSpec) {
        val mode = if (settings.monet) when (settings.theme) {
            "light" -> ColorSchemeMode.MonetLight; "dark" -> ColorSchemeMode.MonetDark; else -> ColorSchemeMode.MonetSystem
        } else when (settings.theme) {
            "light" -> ColorSchemeMode.Light; "dark" -> ColorSchemeMode.Dark; else -> ColorSchemeMode.System
        }
        ThemeController(mode, keyColor = seed?.let(::Color), paletteStyle = paletteStyles[settings.palette.coerceIn(paletteStyles.indices)],
            colorSpec = if (settings.colorSpec == 0) ThemeColorSpec.Spec2021 else ThemeColorSpec.Spec2025)
    }
    MiuixTheme(controller = controller) {
        val activity = context as btm.m.auraplayer.MainActivity
        val dark = MiuixTheme.colorScheme.surface.luminance() < .5f
        SideEffect { WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
        } }
        AuraShell(model)
    }
}

/** HyperChanger Shell ownership: retained page stack, delayed pop, per-page gesture owner. */
@Composable
private fun AuraShell(model: AuraViewModel) {
    val context = LocalContext.current
    val settings = model.settings.value
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        model.refreshLibrary()
    }
    val requestPermission: () -> Unit = {
        if (permissionDenied) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        else permission.launch(Manifest.permission.READ_MEDIA_AUDIO)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refreshLibrary() }
    val selected = rememberSaveable { mutableIntStateOf(0) }
    var playlistsTab by rememberSaveable { mutableIntStateOf(0) }
    var albumTab by rememberSaveable { mutableIntStateOf(0) }
    val pages = remember { mutableStateListOf<String>() }
    val seamless = remember { SeamlessNavigationState() }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    var closingIndex by remember { mutableIntStateOf(-1) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchVisible by remember { mutableStateOf(false) }
    var searchProgress by remember { mutableFloatStateOf(0f) }
    var searchAnchor by remember { mutableStateOf(Rect.Zero) }
    val page = pages.lastOrNull()
    val open: (String) -> Unit = { target ->
        if (!closing && pages.lastOrNull() != target) {
            seamless.open(pages.size, enabled = false)
            pages += target
        }
    }
    LaunchedEffect(model.collection) {
        if (model.collection != null && "collection" !in pages) open("collection")
    }
    LaunchedEffect(model.directory) {
        if (model.directory != null && "directory" !in pages) open("directory")
    }
    val dismissPage: () -> Unit = {
        if (!closing && pages.isNotEmpty()) {
            val index = pages.lastIndex
            val leaving = pages.last()
            val entry = seamless.entries[index]
            closing = true
            if (entry == null) closingIndex = index
            scope.launch {
                try {
                    if (entry != null) entry.close(if (entry.gesturing) entry.releaseVelocity else 0f,
                        if (entry.gesturing) entry.progress.value else null)
                    else delay(207)
                    seamless.entries.remove(index)
                    if (pages.lastIndex == index) pages.removeAt(index)
                    if (leaving == "collection") model.closeCollection()
                    if (leaving == "directory") model.closeDirectory()
                } finally { closingIndex = -1; closing = false }
            }
        }
    }
    val backState = rememberMomentumPredictiveBack(
        enabled = page != null && settings.predictive && seamless.entries[pages.lastIndex] == null && !closing && !searchVisible,
        maxProgress = settings.maxBackProgress, onBack = dismissPage)
    val exitDirection = remember(closingIndex) { backState.exitDirection }
    val momentumOwner = remember(backState.isSwiping) { pages.lastIndex to page }
    LaunchedEffect(pages.size) { if (pages.isNotEmpty()) backState.reset() }
    BackHandler(enabled = page != null && (closing || !settings.predictive) && !searchVisible, onBack = dismissPage)
    SeamlessPredictiveBack(seamless.entries[pages.lastIndex], page != null && settings.predictive && !closing && !searchVisible,
        settings.maxBackProgress, dismissPage)
    val surface = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop { drawRect(surface); drawContent() }
    val topEntry = seamless.entries[pages.lastIndex]
    val gestureBlur = if (topEntry != null) 12f * topEntry.progress.value
        else 12f - 8f * (backState.gestureProgress / settings.maxBackProgress).coerceIn(0f, 1f)
    val revealingBase = page == null || closingIndex == 0
    val baseBlur by animateFloatAsState(when {
        revealingBase -> 0f
        (backState.isSwiping || topEntry != null) && pages.size == 1 -> gestureBlur
        else -> 12f
    }, animationSpec = if (!revealingBase && (backState.isSwiping || topEntry != null) && pages.size == 1) snap() else tween(252, easing = FastOutSlowInEasing))
    val rootLabels = listOf(tr("home", "首页"), tr("playlists", "歌单"), tr("songs", "歌曲"), tr("categories", "分类"))
    val saveableTabs = rememberSaveableStateHolder()
    CompositionLocalProvider(LocalSeamlessNavigation provides seamless, LocalDialogBackdrop provides backdrop) {
        // MIUIX PopupLayout(renderInRootScaffold=true) must be hosted outside recorded pages.
        Scaffold(containerColor = Color.Transparent) {
            Box(Modifier.fillMaxSize().background(surface)) {
                Box(Modifier.fillMaxSize().blur(baseBlur.dp).layerBackdrop(backdrop)) {
                    AnimatedContent(targetState = selected.intValue, transitionSpec = {
                        val direction = if (targetState >= initialState) 1 else -1
                        (slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { direction * it / 7 } + fadeIn(tween(210)) + scaleIn(tween(280), initialScale = .985f)) togetherWith
                            (slideOutHorizontally(tween(230, easing = FastOutSlowInEasing)) { -direction * it / 3 } + fadeOut(tween(180)) + scaleOut(tween(230), targetScale = .975f))
                    }, modifier = Modifier.fillMaxSize(), label = "mainTabNavigation") { tab ->
                        val tabBlur by transition.animateFloat(transitionSpec = { tween(280, easing = FastOutSlowInEasing) }) { state ->
                            if (state == EnterExitState.Visible) 0f else 12f
                        }
                        saveableTabs.SaveableStateProvider(tab) {
                            Box(Modifier.fillMaxSize().blur(tabBlur.dp)) {
                                AppPage(if (tab == 0) "Aura Player" else rootLabels[tab], actions = {
                                    if (tab == 0) GlassToolbarIconButton(MiuixIcons.Regular.Settings, tr("settings", "设置")) { open("settings") }
                                    else GlassToolbarIconButton(MiuixIcons.Regular.Refresh, tr("refresh", "刷新")) {
                                        model.refreshLibrary(); if (tab == 1 && playlistsTab == 1) model.refreshCloud()
                                    }
                                }, header = {
                                    if (tab == 1) SoftGlassHeaderTabs(listOf(tr("local", "本地"), tr("netease", "网易云音乐")), playlistsTab) { playlistsTab = it }
                                    if (tab == 3) SoftGlassHeaderTabs(listOf(tr("albums", "专辑"), tr("artists", "艺术家")), albumTab) { albumTab = it }
                                }) { padding, _ ->
                                    val contentPadding = auraContentPadding(padding, 112)
                                    when (tab) {
                                        0 -> HomePage(model, contentPadding, requestPermission)
                                        1 -> PlaylistsPage(model, contentPadding, playlistsTab) { open("login") }
                                        2 -> SongsPage(model, contentPadding, requestPermission)
                                        else -> AlbumsArtistsPage(model, contentPadding, albumTab)
                                    }
                                }
                            }
                        }
                    }
                }
                AuraBottomBar(selected, model, backdrop, Modifier.align(Alignment.BottomCenter).blur(baseBlur.dp),
                    searchVisible, searchProgress, { if (searchAnchor.width > 0f) { searchVisible = true; searchOpen = true } },
                    { if (!searchVisible) searchAnchor = it })
                pages.forEachIndexed { index, stackPage ->
                    key(index, stackPage) {
                        val entry = remember { seamless.entries[index] }
                        val isTop = index == pages.lastIndex
                        val revealing = isTop || closingIndex == index + 1
                        val coveredBlur by animateFloatAsState(when {
                            revealing -> 0f
                            index == pages.lastIndex - 1 && (backState.isSwiping || topEntry != null) -> gestureBlur
                            else -> 12f
                        }, animationSpec = if (!revealing && index == pages.lastIndex - 1 && (backState.isSwiping || topEntry != null)) snap() else tween(252, easing = FastOutSlowInEasing))
                        val visible = remember { MutableTransitionState(false).apply { targetState = true } }
                        if (closingIndex == index) visible.targetState = false
                        AnimatedVisibility(visibleState = visible,
                            enter = if (entry != null) EnterTransition.None else slideInHorizontally(tween(252, easing = FastOutSlowInEasing)) { it / 7 } + fadeIn(tween(189)) + scaleIn(tween(252), initialScale = .985f),
                            exit = if (entry != null) ExitTransition.None else slideOutHorizontally(tween(207, easing = FastOutSlowInEasing)) { exitDirection * it / 3 } + fadeOut(tween(162)) + scaleOut(tween(207), targetScale = .975f),
                            modifier = Modifier.fillMaxSize()) {
                            val transitionBlur by transition.animateFloat(transitionSpec = { tween(207, easing = FastOutSlowInEasing) }) { state ->
                                if (entry != null || state == EnterExitState.Visible) 0f else 12f
                            }
                            Box(Modifier.fillMaxSize().blur((coveredBlur + transitionBlur).dp).then(
                                if (isTop && entry == null && momentumOwner == (index to stackPage)) Modifier.momentumBackTransform(backState) else Modifier)) {
                                SeamlessPageFrame(entry) {
                                    DetailPage(stackPage, model, if (isTop) dismissPage else ({}), open)
                                }
                            }
                        }
                    }
                }
                if (searchVisible) {
                    CompositionLocalProvider(LocalToolbarBackdrop provides backdrop) {
                        val results = model.searchResults.mapIndexed { index, song -> HomeSearchResult(song.key, song.title,
                            "${song.artist} · ${song.album}", if (song.cloud) tr("netease", "网易云音乐") else tr("local", "本地"),
                            requiresService = false, onClick = { model.play(model.searchResults, index) }) }.toMutableList()
                        if (model.searchHasMore) results += HomeSearchResult("more", tr("load_more", "加载更多"), "", "", requiresService = false, onClick = model::loadMoreSearch)
                        HomeSearchOverlay(searchOpen, navigationStyle = settings.navigation, anchorInWindow = searchAnchor,
                            query = model.searchQuery, onQueryChange = model::search, results = results, searching = model.searching,
                            enabled = true, onDismiss = { searchOpen = false }, onClosed = { searchVisible = false; searchProgress = 0f }, onProgress = { searchProgress = it })
                    }
                }
                WindowDialog(model.error != null, title = tr("notice", "提示"), summary = model.error, onDismissRequest = model::dismissError) {
                    GlassDialogButton(model::dismissError, Modifier.fillMaxWidth()) { Text(tr("ok", "确定")) }
                }
            }
        }
    }
}

private fun auraContentPadding(padding: PaddingValues, bottom: Int) = PaddingValues(16.dp,
    padding.calculateTopPadding() + 4.dp, 16.dp, padding.calculateBottomPadding() + bottom.dp)

@Composable
private fun DetailPage(page: String, model: AuraViewModel, back: () -> Unit, open: (String) -> Unit) {
    val title = when (page) {
        "settings" -> tr("settings", "设置")
        "theme" -> tr("theme_color", "主题与颜色")
        "login" -> tr("netease_login", "网易云音乐登录")
        "about" -> tr("about", "关于")
        "donate" -> tr("donate", "捐赠")
        "collection" -> model.collection?.title.orEmpty()
        "directory" -> model.directory?.title.orEmpty()
        else -> legalTitle(page)
    }
    AppPage(title, back, showLargeTitle = page != "about", pageBackground = if (page == "about") ({ AboutMaterialBackground(Modifier.matchParentSize(), model.settings.value.monet) }) else null) { padding, _ ->
        val contentPadding = auraContentPadding(padding, 28)
        when (page) {
            "settings" -> SettingsPage(model, contentPadding, open)
            "theme" -> ThemePage(model, contentPadding)
            "login" -> LoginPage(model, contentPadding)
            "about" -> AboutPage(model, contentPadding, open)
            "donate" -> DonatePage(contentPadding)
            "collection" -> CollectionPage(model, contentPadding)
            "directory" -> DirectoryPage(model, contentPadding)
            else -> LegalPage(page, contentPadding)
        }
    }
}

/** Original HyperChanger BottomBar geometry and search-exit animation; adapted only for four tabs. */
@Composable
private fun AuraBottomBar(selected: MutableIntState, model: AuraViewModel, backdrop: com.kyant.backdrop.Backdrop,
    modifier: Modifier, searchMounted: Boolean, searchProgress: Float, onSearch: () -> Unit, onPositioned: (Rect) -> Unit) {
    val settings = model.settings.value
    val liquid = settings.navigation == "liquid_glass"
    val view = LocalView.current
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val barHeight = (72f * .82f * .95f).dp
    val navExit = searchPhase(searchProgress, 0f, .65f)
    val labels = listOf(tr("home", "首页"), tr("playlists", "歌单"), tr("songs", "歌曲"), tr("categories", "分类"))
    BoxWithConstraints(modifier.fillMaxWidth().height(100.dp + navInset)) {
        val buttonSize = barHeight
        val gap = 8.dp
        // Reserve the same outer insets and gap as the original; measure the real pill width.
        val barWidth = maxWidth - 28.dp - buttonSize - gap
        val shift = -(buttonSize + gap) / 2
        val right = maxWidth / 2 + barWidth / 2 + shift
        val centerY = maxHeight - navInset - (8f * .82f).dp - barHeight / 2
        val widthFactor = if (liquid) barWidth.value / ((maxWidth.value - 28f) * .53f * .82f * 1.10f)
            else barWidth.value / (maxWidth.value * .53f * .82f)
        val heightFactor = if (liquid) barHeight.value / 64f else .95f
        val dimensions = NavigationScaleSettings.Settings(widthEnabled = true, widthHundredths = ((widthFactor - 1f) * 10000f).roundToInt(),
            heightEnabled = true, heightHundredths = ((heightFactor - 1f) * 10000f).roundToInt())
        val accent by animateColorAsState(MiuixTheme.colorScheme.primary, tween(420, easing = FastOutSlowInEasing))
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationX = shift.toPx()
            translationY = navExit * (barHeight + navInset + 24.dp).toPx() + if (liquid) .44.dp.toPx() else 0f
            alpha = 1f - navExit
        }.blur((navExit * 10f).dp)) {
            CustomNavigation(view, tabs = labels.mapIndexed { index, label -> HostTab(label, "aura.$index") {
                if (!searchMounted) selected.intValue = index
            } }, selectedIndex = selected, blurRadius = if (liquid) 3 else 18, labelMode = settings.labels,
                navigationStyle = settings.navigation, advancedMaterial = true, colorMode = settings.theme,
                accentColorOverride = accent, material = rememberModuleNavigationMaterial(settings.navigation),
                dimensionScale = dimensions, liquidBottomSpacingDp = 7, onHostPreDraw = {}, backdropOverride = backdrop,
                tabIconContent = { index, color -> AuraTabIcon(index, color) })
        }
        if (!searchMounted) SearchRoundButton(backdrop, buttonSize, false, 0f, settings.navigation,
            modifier = Modifier.offset(right + gap, centerY - buttonSize / 2).onGloballyPositioned { onPositioned(it.boundsInWindow()) },
            enabled = true, onClick = onSearch)
    }
}
