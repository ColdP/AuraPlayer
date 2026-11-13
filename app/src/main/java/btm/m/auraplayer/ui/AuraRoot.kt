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
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
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
import btm.m.auraplayer.ui.player.LocalSongActions
import btm.m.auraplayer.ui.player.LocalOpenTagEditor
import btm.m.auraplayer.ui.player.SongActionRequest
import btm.m.auraplayer.ui.player.SongActionsSheet
import btm.m.auraplayer.ui.player.LyricShareRoute
import btm.m.auraplayer.ui.player.LyricShareTransitionState
import btm.m.auraplayer.ui.player.LocalLyricShareTransition
import btm.m.auraplayer.ui.player.playerMiniBlurRadius
import btm.m.auraplayer.ui.player.playerTransitionBlurRadius
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
    MiuixTheme(controller = controller, textStyles = remember { auraTextStyles() }) {
        AuraAdaptiveLayout { AuraShell(model) }
    }
}

/** HyperChanger Shell ownership: retained page stack, delayed pop, per-page gesture owner. */
@Composable
private fun AuraShell(model: AuraViewModel) {
    val context = LocalContext.current
    val settings = model.settings.value
    val miniBlurRadius = playerMiniBlurRadius(settings.navigation,
        rememberModuleNavigationMaterial(settings.navigation).blur)
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
    val pages = remember { mutableStateListOf<String>() }
    val tagEditorExitGuard = remember { TagEditorExitGuard() }
    val seamless = remember { SeamlessNavigationState() }
    val lyricShareTransition = remember { LyricShareTransitionState() }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    var closingIndex by remember { mutableIntStateOf(-1) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var playerReturnIndex by rememberSaveable { mutableIntStateOf(-1) }
    var restoreLyricsRequest by remember { mutableIntStateOf(0) }
    var playerExpansion by remember { mutableFloatStateOf(0f) }
    val mainActivity = context as btm.m.auraplayer.MainActivity
    val mediaPlayerRequest = mainActivity.mediaPlayerRequest
    var handledMediaPlayerRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(mediaPlayerRequest, model.current) {
        if (mediaPlayerRequest > handledMediaPlayerRequest && model.current != null) {
            handledMediaPlayerRequest = mediaPlayerRequest
            if (model.app.nowPlaying.state.value.openFromNotification) playerOpen = true
        }
    }
    var songActions by remember { mutableStateOf<SongActionRequest?>(null) }
    var songActionsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(model.current) { if (model.current == null) playerOpen = false }
    val playerDark = MiuixTheme.colorScheme.surface.luminance() < .5f
    val visibleCollection = pages.lastOrNull()?.takeIf { it.startsWith("collection:") }?.substringAfter(':')?.toLongOrNull()
        ?.let(model::collectionDestination)?.collection
    val catalogDark = visibleCollection?.key?.let { it.startsWith("artist:") || it.startsWith("cloud:artist:") } == true
    val playerCoveringBars = playerOpen && (!lyricShareTransition.active || lyricShareTransition.progress < .45f)
    SideEffect {
        val activity = context as btm.m.auraplayer.MainActivity
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !playerDark && !playerCoveringBars && !catalogDark
            isAppearanceLightNavigationBars = !playerDark && !playerCoveringBars
        }
        activity.window.statusBarColor = android.graphics.Color.TRANSPARENT
        activity.window.navigationBarColor = android.graphics.Color.TRANSPARENT
        activity.window.isStatusBarContrastEnforced = false
    }
    LaunchedEffect(playerCoveringBars, playerDark, catalogDark) {
        val activity = context as btm.m.auraplayer.MainActivity
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = !playerCoveringBars && !playerDark && !catalogDark
        controller.isAppearanceLightNavigationBars = !playerCoveringBars && !playerDark
    }
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
    val openFromPlayer: (String) -> Unit = { target ->
        open(target)
        if (pages.lastOrNull() == target) {
            playerReturnIndex = pages.lastIndex
        }
    }
    LaunchedEffect(model.activeCollectionId) {
        val route = "collection:${model.activeCollectionId}"
        if (model.collection != null && route !in pages) open(route)
    }
    val collectionIds = model.collectionDestinationIds
    LaunchedEffect(collectionIds) {
        for (index in pages.lastIndex downTo 0) {
            val route = pages[index]
            if (route.substringBefore(':') in setOf("collection", "albumInfo", "artistSongs", "artistAlbums") && route.substringAfter(':').toLongOrNull() !in collectionIds) {
                pages.removeAt(index); seamless.entries.remove(index)
                if (index == playerReturnIndex) playerReturnIndex = -1
                else if (index < playerReturnIndex) playerReturnIndex--
            }
        }
    }
    LaunchedEffect(model.directory) {
        if (model.directory != null && "directory" !in pages) open("directory")
    }
    val dismissPage: () -> Unit = {
        if (!closing && pages.isNotEmpty() && !tagEditorExitGuard.intercept(pages.lastOrNull())) {
            val index = pages.lastIndex
            val leaving = pages.last()
            val entry = seamless.entries[index]
            val returnToPlayer = index == playerReturnIndex
            closing = true
            if (entry == null) closingIndex = index
            scope.launch {
                try {
                    if (entry != null) entry.close(if (entry.gesturing) entry.releaseVelocity else 0f,
                        if (entry.gesturing) entry.progress.value else null)
                    else delay(207)
                    seamless.entries.remove(index)
                    if (pages.lastIndex == index) pages.removeAt(index)
                    if (returnToPlayer) {
                        playerReturnIndex = -1
                        if (leaving == "lyricShare") restoreLyricsRequest++
                        if (model.current != null) playerOpen = true
                    }
                    if (leaving.startsWith("collection:")) leaving.substringAfter(':').toLongOrNull()?.let(model::closeCollection)
                    if (leaving == "directory") model.closeDirectory()
                } finally { closingIndex = -1; closing = false }
            }
        }
    }
    val backState = rememberMomentumPredictiveBack(
        enabled = page != null && settings.predictive && seamless.entries[pages.lastIndex] == null && !closing && !searchVisible && !playerOpen,
        maxProgress = settings.maxBackProgress, onBack = dismissPage)
    val exitDirection = remember(closingIndex) { backState.exitDirection }
    val momentumOwner = remember(backState.isSwiping) { pages.lastIndex to page }
    LaunchedEffect(pages.size) { if (pages.isNotEmpty()) backState.reset() }
    BackHandler(enabled = page != null && (closing || !settings.predictive) && !searchVisible && !playerOpen, onBack = dismissPage)
    SeamlessPredictiveBack(seamless.entries[pages.lastIndex], page != null && settings.predictive && !closing && !searchVisible && !playerOpen,
        settings.maxBackProgress, dismissPage)
    val surface = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop { drawRect(surface); drawContent() }
    val topEntry = seamless.entries[pages.lastIndex]
    val gestureBlur = if (topEntry != null) 12f * topEntry.progress.value
        else 12f - 8f * (backState.gestureProgress / settings.maxBackProgress).coerceIn(0f, 1f)
    val revealingBase = page == null || closingIndex == 0
    // A completed nested back gesture can outlive the page that owned it until the
    // stack-size effect resets backState. Never apply that gesture to the revealed page.
    val rootBackGesture = pages.size == 1 && backState.isSwiping && !closing &&
        momentumOwner == (pages.lastIndex to page)
    val secondaryProgress by animateFloatAsState(
        targetValue = when {
            revealingBase -> 0f
            rootBackGesture ->
                1f - (backState.gestureProgress / settings.maxBackProgress).coerceIn(0f, 1f)
            else -> 1f
        },
        animationSpec = if (rootBackGesture) snap()
            else tween(280, easing = FastOutSlowInEasing), label = "secondaryPlayerDock")
    val chromeHiddenIndex = pages.indexOfLast { it == "navigation_material" || it == "lyricShare" }
    val chromeHidden = chromeHiddenIndex >= 0 && closingIndex != chromeHiddenIndex
    val materialChromeExit by animateFloatAsState(
        if (chromeHidden) 1f else 0f,
        if (lyricShareTransition.active && chromeHidden) snap() else tween(280, easing = FastOutSlowInEasing),
        label = "materialPreviewChromeExit",
    )
    val baseBlur by animateFloatAsState(when {
        revealingBase -> 0f
        rootBackGesture || (topEntry != null && pages.size == 1) -> gestureBlur
        else -> 12f
    }, animationSpec = if (!revealingBase && (rootBackGesture || (topEntry != null && pages.size == 1))) snap() else tween(252, easing = FastOutSlowInEasing))
    val rootLabels = listOf(tr("home", "首页"), tr("cloud", "云端"), tr("local", "本地"))
    val saveableTabs = rememberSaveableStateHolder()
    CompositionLocalProvider(LocalSeamlessNavigation provides seamless, LocalDialogBackdrop provides backdrop,
        LocalLyricShareTransition provides lyricShareTransition,
        LocalTagEditorExitGuard provides tagEditorExitGuard,
        LocalOpenTagEditor provides { song -> playerOpen = false; searchOpen = false; open(tagEditorRoute(song)) },
        LocalSongActions provides { request -> songActions = request; songActionsOpen = true }) {
        // MIUIX PopupLayout(renderInRootScaffold=true) must be hosted outside recorded pages.
        Scaffold(containerColor = Color.Transparent) {
            Box(Modifier.fillMaxSize().background(surface)) {
                // Record page content only; floating controls sample the currently visible page
                // without recording themselves back into their own glass backgrounds.
                Box(Modifier.fillMaxSize().graphicsLayer {
                    // Read progress in the draw layer, without recomposing the whole page stack
                    // for each spring/gesture frame. Clamp keeps the screen edges filled.
                    val radius = ((searchProgress * 14f).dp + playerTransitionBlurRadius(miniBlurRadius, playerExpansion)).toPx()
                    renderEffect = if (radius > .01f) BlurEffect(radius, radius, TileMode.Clamp) else null
                }.layerBackdrop(backdrop)) {
                Box(Modifier.fillMaxSize().blur(baseBlur.dp)) {
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
                                    else if (tab == 2) SongsToolbarMenu(model)
                                    else GlassToolbarIconButton(MiuixIcons.Regular.Refresh, tr("refresh", "刷新")) {
                                        model.refreshLibrary(); if (tab == 1) model.refreshCloud()
                                    }
                                }) { padding, _ ->
                                    val contentPadding = auraContentPadding(padding, if (model.current != null) 184 else 112)
                                    when (tab) {
                                        0 -> if (settings.homeContent == "cloud") CloudHomePage(model, contentPadding,
                                            login = { open("login") }, onRecommended = { open("cloud_recommended") })
                                            else HomePage(model, contentPadding, requestPermission)
                                        1 -> CloudHubPage(model, contentPadding) { open("login") }
                                        2 -> SongsPage(model, contentPadding, requestPermission) { secondary ->
                                            open(when (secondary) { 0 -> "albums"; 1 -> "artists"; else -> "localPlaylists" })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
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
                        val seamlessLyricEntry = stackPage == "lyricShare" && lyricShareTransition.active
                        AnimatedVisibility(visibleState = visible,
                            enter = if (entry != null || seamlessLyricEntry) EnterTransition.None else slideInHorizontally(tween(252, easing = FastOutSlowInEasing)) { it / 7 } + fadeIn(tween(189)) + scaleIn(tween(252), initialScale = .985f),
                            exit = if (entry != null) ExitTransition.None else slideOutHorizontally(tween(207, easing = FastOutSlowInEasing)) { exitDirection * it / 3 } + fadeOut(tween(162)) + scaleOut(tween(207), targetScale = .975f),
                            modifier = Modifier.fillMaxSize()) {
                            val transitionBlur by transition.animateFloat(transitionSpec = { tween(207, easing = FastOutSlowInEasing) }) { state ->
                                if (entry != null || seamlessLyricEntry || state == EnterExitState.Visible) 0f else 12f
                            }
                            Box(Modifier.fillMaxSize().blur((coveredBlur + transitionBlur).dp).then(
                                if (isTop && entry == null && momentumOwner == (index to stackPage)) Modifier.momentumBackTransform(backState) else Modifier)) {
                                SeamlessPageFrame(entry) {
                                    DetailPage(stackPage, model, if (isTop) dismissPage else ({}), open, isActive = isTop && closingIndex != index)
                                }
                            }
                        }
                    }
                }
                }
                AuraBottomBar(selected, model, backdrop, Modifier.align(Alignment.BottomCenter).graphicsLayer {
                    val radius = ((searchProgress * 14f).dp + playerTransitionBlurRadius(miniBlurRadius, playerExpansion)).toPx()
                    renderEffect = if (radius > .01f) BlurEffect(radius, radius, TileMode.Clamp) else null
                },
                    searchVisible, searchProgress, secondaryProgress,
                    { if (searchAnchor.width > 0f) { searchVisible = true; searchOpen = true } },
                    { if (!searchVisible && materialChromeExit < .01f) searchAnchor = it },
                    searchExitProgress = materialChromeExit)
                btm.m.auraplayer.ui.player.PlayerHost(model, backdrop, miniVisible = !searchVisible,
                    secondaryProgress = secondaryProgress,
                    chromeExitProgress = materialChromeExit,
                    restoreLyricsRequest = restoreLyricsRequest,
                    onExpansionProgress = { playerExpansion = it },
                    open = playerOpen, onOpenChange = { playerOpen = it }, openPage = openFromPlayer)
                if (searchVisible) {
                    CompositionLocalProvider(LocalToolbarBackdrop provides backdrop) {
                        val results = model.searchHits.map { hit -> HomeSearchResult(hit.key, hit.title,
                            hit.subtitle, searchCategoryLabel(hit.kind), artwork = hit.artwork,
                            requiresService = false, onClick = {
                                model.openSearchHit(hit)
                                if (hit.kind in listOf(btm.m.auraplayer.data.SearchCategory.ALBUM, btm.m.auraplayer.data.SearchCategory.ARTIST, btm.m.auraplayer.data.SearchCategory.BLOG)) searchOpen = false
                            }) }.toMutableList()
                        if (model.searchHasMore) results += HomeSearchResult("more", tr("load_more", "加载更多"), "", "", requiresService = false, onClick = model::loadMoreSearch)
                        HomeSearchOverlay(searchOpen, navigationStyle = settings.navigation, anchorInWindow = searchAnchor,
                            query = model.searchQuery, onQueryChange = model::search, results = results, searching = model.searching,
                            filterContent = { SearchFilters(model) },
                            enabled = true, onDismiss = { searchOpen = false }, onClosed = { searchVisible = false; searchProgress = 0f }, onProgress = { searchProgress = it })
                    }
                }
                PresetChangeGuard(model.app.effects)
                WindowDialog(model.error != null, title = tr("notice", "提示"), summary = model.error, onDismissRequest = model::dismissError) {
                    GlassDialogButton(model::dismissError, Modifier.fillMaxWidth()) { Text(tr("ok", "确定")) }
                }
                songActions?.let { request -> key(request.song.key) {
                    CompositionLocalProvider(LocalDialogBackdrop provides (request.backdrop ?: backdrop)) {
                        SongActionsSheet(model, request, songActionsOpen, dismiss = { songActionsOpen = false },
                            onDismissFinished = { if (!songActionsOpen) songActions = null })
                    }
                } }
            }
        }
    }
}

@Composable
private fun auraContentPadding(padding: PaddingValues, bottom: Int) = PaddingValues(LocalAuraLayout.current.pageMargin,
    padding.calculateTopPadding() + 4.dp, LocalAuraLayout.current.pageMargin, padding.calculateBottomPadding() + bottom.dp)

@Composable
private fun DetailPage(route: String, model: AuraViewModel, back: () -> Unit, open: (String) -> Unit, isActive: Boolean) {
    var presetTab by rememberSaveable { mutableIntStateOf(0) }
    var materialTab by rememberSaveable {
        mutableIntStateOf(NavigationMaterialSettings.styles.indexOf(NavigationStyle.fromPreference(model.settings.value.navigation)).coerceAtLeast(0))
    }
    val tagEditorExitGuard = LocalTagEditorExitGuard.current
    val destination = route.takeIf { it.startsWith("collection:") }?.substringAfter(':')?.toLongOrNull()?.let(model::collectionDestination)
    val albumInfoDestination = route.takeIf { it.substringBefore(':') in setOf("albumInfo", "artistSongs", "artistAlbums") }?.substringAfter(':')?.toLongOrNull()?.let(model::collectionDestination)
    val page = when {
        route.startsWith("tagEditor:") -> "tagEditor"
        route.startsWith("collection:") -> "collection"
        route.substringBefore(':') in setOf("albumInfo", "artistSongs", "artistAlbums") -> route.substringBefore(':')
        else -> route
    }
    val collection = destination?.collection ?: albumInfoDestination?.collection
    val catalog = collection?.isCatalog() == true
    val catalogTopBarTint = if (collection != null && (page == "albumInfo" || (page == "collection" && collection.isArtist()))) {
        val palette = btm.m.auraplayer.ui.player.rememberPlayerMonetPalette(collection.artwork)
        Color(palette.a2.tone(if (MiuixTheme.colorScheme.surface.luminance() < .5f) 16 else 90))
    } else null
    val title = when (page) {
        "tagEditor" -> tr("tag_editor", "标签编辑器")
        "settings" -> tr("settings", "设置")
        "theme" -> tr("theme_color", "主题与颜色")
        "navigation_material" -> tr("nav_material_title", "底部导航栏材质参数调整")
        "login" -> tr("netease", "网易云音乐")
        "cloud_recommended" -> tr("recommended_playlists", "推荐歌单")
        "downloads" -> tr("downloaded_music", "已下载的音乐")
        "audio_effects" -> tr("audio_effects", "音效")
        "effect_library" -> tr("preset_library", "预设库")
        "effect_custom" -> tr("custom", "自定义")
        "effect_online" -> tr("online_presets", "在线预设库")
        "audio_settings" -> tr("audio_settings", "音频")
        "now_playing_settings" -> tr("now_playing_page", "正在播放页面")
        "about" -> tr("about", "关于")
        "donate" -> tr("donate", "捐赠")
        "localPlaylists" -> tr("playback_lists", "播放列表")
        "albums" -> tr("albums", "专辑")
        "artists" -> tr("artists", "艺术家")
        "collection" -> collection?.title.orEmpty()
        "albumInfo" -> collection?.title.orEmpty()
        "artistSongs" -> tr("top_songs", "热门歌曲")
        "artistAlbums" -> tr("albums", "专辑")
        "directory" -> model.directory?.title.orEmpty()
        else -> legalTitle(page)
    }
    if (page == "tagEditor") {
        val uri = Uri.decode(route.substringAfter(':'))
        TagEditorPage(model, model.localSong(uri), { if (!tagEditorExitGuard.intercept(route)) back() }, isActive)
        return
    }
    if (page == "lyricShare") {
        LyricShareRoute(model, back, isActive)
        return
    }
    AppPage(title, back, showLargeTitle = page != "about" && (!catalog || page in setOf("artistSongs", "artistAlbums")),
        header = {
            when (page) {
                "effect_library" -> SoftGlassHeaderTabs(listOf(tr("builtin_presets", "内置预设"), tr("user_presets", "用户预设")), presetTab) { presetTab = it }
                "navigation_material" -> SoftGlassHeaderTabs(listOf(tr("nav_material_hyper", "HyperOS"), tr("nav_material_harmony", "HarmonyOS"), tr("nav_material_liquid", "液态玻璃")), materialTab) { materialTab = it }
                "collection" -> if (destination?.cloud?.source == "rank") SoftGlassHeaderTabs(
                    listOf(tr("rank_week", "最近一周"), tr("rank_all", "所有时间")),
                    if (model.listeningRankWeek) 0 else 1,
                    onSelected = { model.changeListeningRankPeriod(it == 0) })
            }
        },
        useLegacyTopBarBackdrop = page == "about",
        topBarTint = catalogTopBarTint,
        pageBackground = when {
            page == "albumInfo" && collection != null -> ({ AlbumInfoBackground(collection, Modifier.matchParentSize()) })
            catalog && page !in setOf("artistSongs", "artistAlbums") -> ({ CatalogBackground(collection, Modifier.matchParentSize()) })
            page == "about" -> ({ AboutMaterialBackground(Modifier.matchParentSize(), model.settings.value.monet) })
            else -> null
        }) { padding, _ ->
        val contentPadding = auraContentPadding(padding, if (model.current != null && page != "navigation_material") 104 else 28)
        when (page) {
            "settings" -> SettingsPage(model, contentPadding, open)
            "theme" -> ThemePage(model, contentPadding)
            "navigation_material" -> NavigationMaterialPage(model, contentPadding, NavigationMaterialSettings.styles[materialTab])
            "login" -> LoginPage(model, contentPadding, open)
            "cloud_recommended" -> CloudRecommendedPage(model, contentPadding)
            "downloads" -> DownloadedMusicPage(model, contentPadding)
            "audio_effects" -> AudioEffectsPage(contentPadding, isActive, open)
            "effect_library" -> PresetLibraryPage(contentPadding, presetTab, open)
            "effect_custom" -> CustomEffectsPage(contentPadding)
            "audio_settings" -> AudioPreferencesPage(contentPadding)
            "effect_online" -> Box(Modifier.fillMaxSize().padding(contentPadding))
            "now_playing_settings" -> NowPlayingSettingsPage(contentPadding)
            "about" -> AboutPage(model, contentPadding, open)
            "donate" -> DonatePage(contentPadding)
            "localPlaylists" -> LocalPlaylistsPage(model, contentPadding)
            "albums" -> AlbumsArtistsPage(model, contentPadding, 0)
            "artists" -> AlbumsArtistsPage(model, contentPadding, 1)
            "collection" -> if (collection != null && destination != null) {
                if (catalog) CatalogDetailPage(model, contentPadding, collection, destination.cloud, destination.id, open)
                else CollectionPage(model, contentPadding, collection, destination.cloud)
            }
            "artistSongs", "artistAlbums" -> if (collection != null && albumInfoDestination != null)
                ArtistContentsPage(model, contentPadding, collection, albumInfoDestination.cloud, page == "artistAlbums")
            "albumInfo" -> if (collection != null && albumInfoDestination != null) AlbumInfoPage(model, contentPadding, collection, albumInfoDestination.cloud)
            "directory" -> DirectoryPage(model, contentPadding)
            else -> LegalPage(page, contentPadding)
        }
    }
}

/** Original HyperChanger BottomBar geometry and search-exit animation. */
@Composable
internal fun AuraBottomBar(selected: MutableIntState, model: AuraViewModel, backdrop: com.kyant.backdrop.Backdrop,
    modifier: Modifier, searchMounted: Boolean, searchProgress: Float, secondaryProgress: Float,
    onSearch: () -> Unit, onPositioned: (Rect) -> Unit,
    navigationStyle: String = model.settings.value.navigation, searchExitProgress: Float = 0f) {
    val settings = model.settings.value
    val liquid = navigationStyle == "liquid_glass"
    val view = LocalView.current
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val barHeight = btm.m.auraplayer.ui.player.PlayerNavigationGeometry.barHeight
    val navExit = maxOf(searchPhase(searchProgress, 0f, .65f), secondaryProgress)
    val labels = listOf(tr("home", "首页"), tr("cloud", "云端"), tr("local", "本地"))
    BoxWithConstraints(modifier.fillMaxWidth().height(100.dp + navInset)) {
        val buttonSize = barHeight
        val gap = btm.m.auraplayer.ui.player.PlayerNavigationGeometry.searchGap
        // Reserve the same outer insets and gap as the original; measure the real pill width.
        val externalButtons = buttonSize + gap
        val barWidth = btm.m.auraplayer.ui.player.PlayerNavigationGeometry.bodyWidth(maxWidth)
        val shift = -externalButtons / 2
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
                if (!searchMounted && secondaryProgress < .01f) selected.intValue = index
            } }, selectedIndex = selected, blurRadius = if (liquid) 3 else 18, labelMode = settings.labels,
                navigationStyle = navigationStyle, advancedMaterial = true, colorMode = settings.theme,
                accentColorOverride = accent, material = rememberModuleNavigationMaterial(navigationStyle),
                dimensionScale = dimensions, liquidBottomSpacingDp = 7, onHostPreDraw = {}, backdropOverride = backdrop,
                tabIconContent = { index, color -> AuraTabIcon(index, color) })
        }
        if (!searchMounted) SearchRoundButton(backdrop, buttonSize, false, 0f, navigationStyle,
            icon = appleMusicTabIcon(4),
            // The button's padded motion layer leaves room for glass deformation and
            // shadows. A blur on this fixed-size anchor clips them even at radius zero.
            motionAlpha = 1f - searchExitProgress,
            motionBlur = (searchExitProgress * 10f).dp,
            modifier = Modifier.offset(right + gap, centerY - buttonSize / 2).graphicsLayer {
                translationY = searchExitProgress * (barHeight + navInset + 24.dp).toPx()
            }.onGloballyPositioned { onPositioned(it.boundsInWindow()) },
            enabled = searchExitProgress < .01f, onClick = onSearch)
    }
}
