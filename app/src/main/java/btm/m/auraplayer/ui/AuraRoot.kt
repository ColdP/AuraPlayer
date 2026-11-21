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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import btm.m.auraplayer.AuraApplication
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.BuildConfig
import btm.m.auraplayer.update.AppUpdateManager
import btm.m.auraplayer.update.UpdateInfo
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
import com.itos.coui.search.CouiSearchBarDefaults
import com.itos.coui.search.SearchBar
import com.itos.coui.search.rememberCouiSearchBarState
import com.itos.coui.theme.CouiColorScheme
import com.itos.coui.theme.CouiTheme
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    SideEffect { I18n.language = settings.language }
    ProvideAuraAppTheme(settings = settings, previewColor = model.settings.previewColor) {
        AuraAdaptiveLayout { AuraShell(model) }
    }
}

@Composable
internal fun ProvideAuraCouiTheme(monet: Boolean, content: @Composable () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    val dark = scheme.surface.luminance() < .5f
    val couiColors = remember(scheme, dark) {
        CouiColorScheme(
            accent = scheme.primary,
            onAccent = scheme.onPrimary,
            surface = scheme.surface,
            container = scheme.surfaceContainer,
            onSurface = scheme.onSurface,
            onSurfaceVariant = scheme.onSurfaceVariantSummary,
            outlineVariant = scheme.outline,
            background = scheme.background,
            card = scheme.surfaceContainer,
        )
    }
    CouiTheme(
        darkTheme = dark,
        colorScheme = couiColors,
        materialTint = if (monet) scheme.surface else Color.Transparent,
        content = content,
    )
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
    // Pure local mode has no Cloud tab; never stay on it.
    if (!model.cloudEnabled && selected.intValue == 1) selected.intValue = 0
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
    var pendingUpdate by remember { mutableStateOf<UpdateInfo?>(AppUpdateManager.startupUpdatePending) }
    LaunchedEffect(Unit) {
        if (AppUpdateManager.startupUpdateChecked) {
            pendingUpdate = AppUpdateManager.startupUpdatePending
            return@LaunchedEffect
        }
        var resolved: UpdateInfo? = null
        for (attempt in 0 until 3) {
            if (attempt > 0) delay(3_000L)
            val result = withContext(Dispatchers.IO) {
                runCatching { AppUpdateManager.fetchUpdateInfo() }
            }
            if (result.isSuccess) {
                resolved = result.getOrNull()
                break
            }
        }
        val info = resolved ?: return@LaunchedEffect
        AppUpdateManager.startupUpdateChecked = true
        if (info.versionCode > BuildConfig.VERSION_CODE.toLong()) {
            AppUpdateManager.startupUpdatePending = info
            pendingUpdate = info
        }
    }
    LaunchedEffect(model.current) { if (model.current == null) playerOpen = false }
    val playerDark = MiuixTheme.colorScheme.surface.luminance() < .5f
    val nowPlayingConfig by (context.applicationContext as btm.m.auraplayer.AuraApplication).nowPlaying.state.collectAsState()
    val playerHasLightSurface = !nowPlayingConfig.resolveDarkTheme(appDark = playerDark)
    val topRoute = pages.lastOrNull()
    val visibleCollection = topRoute?.takeIf {
        it.substringBefore(':') in setOf("collection", "albumInfo", "artistInfo")
    }?.substringAfter(':')?.toLongOrNull()?.let(model::collectionDestination)?.collection
    val catalogDark = topRoute?.substringBefore(':') in setOf("albumInfo", "artistInfo") ||
        visibleCollection?.key?.let { it.startsWith("artist:") || it.startsWith("cloud:artist:") } == true
    val playerCoveringBars = playerOpen && (!lyricShareTransition.active || lyricShareTransition.progress < .45f)
    val configuration = LocalConfiguration.current
    val isLandscapeMode = LocalAuraLayout.current.landscape || configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val hideStatusBar = shouldHideStatusBar(
        mode = nowPlayingConfig.statusBarHideMode,
        playerCoveringBars = playerCoveringBars,
        isLandscape = isLandscapeMode,
    )
    val applySystemBars: () -> Unit = {
        val activity = context as btm.m.auraplayer.MainActivity
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = if (playerCoveringBars) playerHasLightSurface else !playerDark && !catalogDark
        controller.isAppearanceLightNavigationBars = if (playerCoveringBars) playerHasLightSurface else !playerDark
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hideStatusBar) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }
    SideEffect {
        val activity = context as btm.m.auraplayer.MainActivity
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = if (playerCoveringBars) playerHasLightSurface else !playerDark && !catalogDark
            isAppearanceLightNavigationBars = if (playerCoveringBars) playerHasLightSurface else !playerDark
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        activity.window.statusBarColor = android.graphics.Color.TRANSPARENT
        activity.window.navigationBarColor = android.graphics.Color.TRANSPARENT
        activity.window.isStatusBarContrastEnforced = false
    }
    LaunchedEffect(playerCoveringBars, playerDark, playerHasLightSurface, catalogDark, hideStatusBar) {
        applySystemBars()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (hideStatusBar) applySystemBars()
    }
    var searchOpen by remember { mutableStateOf(false) }
    var searchVisible by remember { mutableStateOf(false) }
    var searchProgress by remember { mutableFloatStateOf(0f) }
    var searchAnchor by remember { mutableStateOf(Rect.Zero) }
    var bannerOverviewRequest by remember { mutableStateOf<BannerOverviewRequest?>(null) }
    var bannerOverviewProgress by remember { mutableFloatStateOf(0f) }
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
            if (route.substringBefore(':') in setOf("collection", "albumInfo", "artistInfo", "artistSongs", "artistAlbums", "artistSingles") && route.substringAfter(':').toLongOrNull() !in collectionIds) {
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
    val chromeHidden = shouldHideBottomChrome(pages, closingIndex)
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
        LocalBannerOverviewOpener provides { bannerOverviewRequest = it },
        LocalOpenTagEditor provides { song -> playerOpen = false; searchOpen = false; open(tagEditorRoute(song)) },
        LocalSongActions provides { request -> songActions = request; songActionsOpen = true }) {
        // MIUIX PopupLayout(renderInRootScaffold=true) must be hosted outside recorded pages.
        Scaffold(containerColor = Color.Transparent) {
            val playerMorphVisible by remember { derivedStateOf { playerExpansion > .001f } }
            val bypassShellBackdropLayer = chromeHidden && searchProgress <= .001f && bannerOverviewProgress <= .001f && !playerMorphVisible
            val usbHudBackdrop = rememberLayerBackdrop { drawRect(surface); drawContent() }
            var usbHudMounted by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize().background(surface)) {
                Box(Modifier.fillMaxSize().then(if (usbHudMounted) Modifier.layerBackdrop(usbHudBackdrop) else Modifier)) {
                // Record page content only; floating controls sample the currently visible page
                // without recording themselves back into their own glass backgrounds.
                // Pages that hide bottom chrome without active search/player blur bypass the extra
                // RenderNodeLayer so HWUI platform blur/blend views (e.g. About) sample behind directly.
                // Keep the transition BlurEffect on an outer graphicsLayer so per-frame blur updates
                // do not invalidate the inner layerBackdrop for floating glass controls.
                val pageBlurModifier = if (bypassShellBackdropLayer) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxSize().graphicsLayer {
                        // Keep page state, but stop rendering its blur/captures while
                        // the fully opaque player covers the shell. Restore on drag.
                        val covered = playerExpansion >= 1f
                        alpha = if (covered) 0f else 1f
                        // Read progress in the draw layer, without recomposing the whole page stack
                        // for each spring/gesture frame. Clamp keeps the screen edges filled.
                        val radius = ((searchProgress * 14f + bannerOverviewProgress * 18f).dp + playerTransitionBlurRadius(miniBlurRadius, playerExpansion)).toPx()
                        renderEffect = if (!covered && radius > .01f) BlurEffect(radius, radius, TileMode.Clamp) else null
                    }
                }
                val pageBackdropModifier = if (bypassShellBackdropLayer) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxSize().layerBackdrop(backdrop)
                }
                Box(pageBlurModifier) {
                Box(pageBackdropModifier) {
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
                            val activeHomeTarget = when {
                                tab != 0 -> null
                                settings.cloudDisabled || settings.homeContent == "local" -> btm.m.auraplayer.data.HomeBannerTarget.LOCAL
                                settings.homeContent == "netease" -> btm.m.auraplayer.data.HomeBannerTarget.NETEASE
                                settings.homeContent == "qq" -> btm.m.auraplayer.data.HomeBannerTarget.QQ
                                else -> btm.m.auraplayer.data.HomeBannerTarget.LOCAL
                            }
                            val activeBannerConfig = activeHomeTarget?.let { settings.bannerConfig(it) }
                            val activeStyleConfig = activeBannerConfig?.let { it.styleConfig(it.style) }
                            val activeBannerStyle = activeBannerConfig?.style
                            val immersiveHomeBanner = activeBannerStyle == btm.m.auraplayer.data.LocalBannerStyle.IMMERSIVE ||
                                activeBannerStyle == btm.m.auraplayer.data.LocalBannerStyle.IMMERSIVE_GLASS
                            val coloredHomeBg = immersiveHomeBanner && activeStyleConfig != null &&
                                activeStyleConfig.backgroundStyle != btm.m.auraplayer.data.HomeBackgroundStyle.DEFAULT
                            val homeBannerTheme = if (coloredHomeBg && activeBannerConfig != null) {
                                rememberHomeBannerTheme(activeBannerConfig.imagePath)
                            } else {
                                null
                            }
                            Box(Modifier.fillMaxSize().blur(tabBlur.dp)) {
                                AppPage(
                                    title = if (immersiveHomeBanner) "" else if (tab == 0) "Aura Player" else rootLabels[tab],
                                    showLargeTitle = !immersiveHomeBanner,
                                    topBarTint = homeBannerTheme?.topBarTint,
                                    useLegacyTopBarBackdrop = false,
                                    pageBackground = if (coloredHomeBg && homeBannerTheme != null && activeBannerConfig != null && activeStyleConfig != null) {
                                        {
                                            HomeBannerPageBackground(
                                                bannerUri = activeBannerConfig.imagePath,
                                                styleConfig = activeStyleConfig,
                                                theme = homeBannerTheme,
                                                modifier = Modifier.matchParentSize(),
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    actions = {
                                        if (tab == 0) GlassToolbarIconButton(MiuixIcons.Regular.Settings, tr("settings", "设置")) { open("settings") }
                                        else if (tab == 2) SongsToolbarMenu(model)
                                        else GlassToolbarIconButton(MiuixIcons.Regular.Refresh, tr("refresh", "刷新")) {
                                            model.refreshLibrary(); if (tab == 1) model.refreshCloud()
                                        }
                                    },
                                ) { padding, _ ->
                                    val chromeSpace = if (model.current != null) 184 else 112
                                    val contentPadding = auraContentPadding(padding, chromeSpace + settings.navigationOffsetDp.coerceAtLeast(0))
                                    val homeContentPadding = if (immersiveHomeBanner) {
                                        PaddingValues(bottom = contentPadding.calculateBottomPadding())
                                    } else {
                                        contentPadding
                                    }
                                    val immersiveTop = if (immersiveHomeBanner) padding.calculateTopPadding() else 0.dp
                                    when (tab) {
                                        0 -> when (if (settings.cloudDisabled) "local" else settings.homeContent) {
                                            "netease" -> CloudHomePage(model, homeContentPadding, { open("login") }, { open("cloud_recommended") }, immersiveTop, { open("cloud_report") })
                                            "qq" -> QqHomePage(model, homeContentPadding, { open("qq_login") }, immersiveTop)
                                            else -> HomePage(
                                                model = model,
                                                padding = homeContentPadding,
                                                requestPermission = requestPermission,
                                                immersiveTopPadding = immersiveTop,
                                            )
                                        }
                                        1 -> CloudHubPage(model, contentPadding, { open("login") }, { open("qq_login") }, { open("cloud_follows") }, { open("cloud_report") })
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
                }
                AuraBottomBar(selected, model, backdrop, Modifier.align(Alignment.BottomCenter).graphicsLayer {
                    val playerHide = ((playerExpansion - 0.08f) / 0.42f).coerceIn(0f, 1f)
                    val covered = playerHide >= 1f && searchProgress <= .001f && bannerOverviewProgress <= .001f
                    alpha = if (covered) 0f else (1f - playerHide) * (1f - bannerOverviewProgress * 0.45f)
                    val radius = (searchProgress * 14f + bannerOverviewProgress * 18f).dp.toPx()
                    renderEffect = if (!covered && radius > .01f) BlurEffect(radius, radius, TileMode.Clamp) else null
                }.drawWithContent {
                    if (playerExpansion < 0.50f || searchProgress > .001f || bannerOverviewProgress > .001f) drawContent()
                },
                    searchVisible, searchProgress, secondaryProgress,
                    { if (searchAnchor.width > 0f) { searchVisible = true; searchOpen = true } },
                    { if (!searchVisible && materialChromeExit < .01f) searchAnchor = it },
                    searchExitProgress = materialChromeExit)
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        val radius = (bannerOverviewProgress * 18f).dp.toPx()
                        renderEffect = if (radius > .01f) BlurEffect(radius, radius, TileMode.Clamp) else null
                        alpha = 1f - bannerOverviewProgress * 0.35f
                    },
                ) {
                    btm.m.auraplayer.ui.player.PlayerHost(model, backdrop, miniVisible = !searchVisible,
                        secondaryProgress = secondaryProgress,
                        chromeExitProgress = materialChromeExit,
                        restoreLyricsRequest = restoreLyricsRequest,
                        onExpansionProgress = { playerExpansion = it },
                        open = playerOpen, onOpenChange = { playerOpen = it }, openPage = openFromPlayer)
                }
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
                bannerOverviewRequest?.let { request ->
                    BannerOverviewOverlay(
                        request = request,
                        onProgress = { bannerOverviewProgress = it },
                        onClosed = {
                            bannerOverviewRequest = null
                            bannerOverviewProgress = 0f
                        },
                    )
                }
                PresetChangeGuard(model.app.effects)
                UpdateAvailableDialog(
                    show = pendingUpdate != null,
                    currentVersion = BuildConfig.VERSION_NAME,
                    latestVersion = pendingUpdate?.versionName.orEmpty(),
                    onDismiss = {
                        pendingUpdate = null
                        AppUpdateManager.startupUpdatePending = null
                    },
                    onViewUpdate = {
                        pendingUpdate = null
                        AppUpdateManager.startupUpdatePending = null
                        open("software_update")
                    },
                )
                WindowDialog(model.error != null, title = tr("notice", "提示"), summary = model.error, onDismissRequest = model::dismissError) {
                    GlassDialogButton(model::dismissError, Modifier.fillMaxWidth()) { Text(tr("ok", "确定")) }
                }
                songActions?.let { request -> key(request.song.key) {
                    CompositionLocalProvider(LocalDialogBackdrop provides (request.backdrop ?: backdrop)) {
                        SongActionsSheet(model, request, songActionsOpen, dismiss = { songActionsOpen = false },
                            onDismissFinished = { if (!songActionsOpen) songActions = null },
                            openPage = open)
                    }
                } }
                }
                IosUsbVolumeHud(
                    effects = model.app.effects,
                    backdrop = usbHudBackdrop,
                    navigationStylePreference = settings.navigation,
                    onMountedChange = { usbHudMounted = it },
                )
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
    var cloudTab by rememberSaveable { mutableIntStateOf(0) }
    var reportTab by rememberSaveable { mutableIntStateOf(0) }
    var reportRefreshNonce by rememberSaveable { mutableIntStateOf(0) }
    val tagEditorExitGuard = LocalTagEditorExitGuard.current
    val destination = route.takeIf { it.startsWith("collection:") }?.substringAfter(':')?.toLongOrNull()?.let(model::collectionDestination)
    val albumInfoDestination = route.takeIf { it.substringBefore(':') in setOf("albumInfo", "artistInfo", "artistSongs", "artistAlbums", "artistSingles") }?.substringAfter(':')?.toLongOrNull()?.let(model::collectionDestination)
    val page = when {
        route.startsWith("tagEditor:") -> "tagEditor"
        route.startsWith("llm_translation_edit:") -> "llm_translation_edit"
        route.startsWith("song_credits:") -> "song_credits"
        route.startsWith("collection:") -> "collection"
        route.startsWith("spatial_cover_preview:") -> "spatial_cover_preview"
        route.substringBefore(':') in setOf("albumInfo", "artistInfo", "artistSongs", "artistAlbums", "artistSingles") -> route.substringBefore(':')
        else -> route
    }
    val collection = destination?.collection ?: albumInfoDestination?.collection
    val catalog = collection?.isCatalog() == true
    val app = LocalContext.current.applicationContext as AuraApplication
    val nowPlayingConfig by app.nowPlaying.state.collectAsState()
    val activeCatalogDestination = destination ?: albumInfoDestination
    val activeCatalogTracks = activeCatalogDestination?.cloud?.songs ?: collection?.songs.orEmpty()
    val catalogMotionPath = rememberCatalogMotionCoverPath(
        collection = if (page in setOf("collection", "albumInfo") && catalog && !collection.isArtist()) collection else null,
        tracks = activeCatalogTracks,
        enabled = nowPlayingConfig.albumAnimatedCover,
    )
    val catalogAlbumRecordLabel = rememberCatalogAlbumRecordLabel(
        collection = if (page in setOf("collection", "albumInfo") && catalog && !collection.isArtist()) collection else null,
        tracks = activeCatalogTracks,
        catalogArtist = activeCatalogDestination?.cloud?.catalog?.artist,
        cloudLoading = activeCatalogDestination?.cloud?.loading == true,
        enabled = nowPlayingConfig.appleMusicAlbumRecordLabel,
    )
    val artistCatalogMedia = rememberCatalogArtistMedia(
        collection = if (page in setOf("collection", "artistInfo") && catalog && collection.isArtist()) collection else null,
        needLogo = page == "collection" && nowPlayingConfig.artistWordmarkLogo,
        needArtwork = nowPlayingConfig.appleMusicArtistArtwork,
        needMotionVideo = page in setOf("collection", "artistInfo") && nowPlayingConfig.artistAnimatedCover,
    )
    val effectiveCollection = if (collection != null && page in setOf("collection", "artistInfo") && collection.isArtist() &&
        nowPlayingConfig.appleMusicArtistArtwork && !artistCatalogMedia.artworkUrl.isNullOrBlank()
    ) {
        collection.copy(artwork = artistCatalogMedia.artworkUrl)
    } else {
        collection
    }
    val immersiveAlbumCatalog = shouldShowImmersiveAlbumHero(
        isArtist = effectiveCollection?.isArtist() == true,
        albumAnimatedCoverEnabled = nowPlayingConfig.albumAnimatedCover,
        motionVideoPath = catalogMotionPath,
    )
    val catalogTopBarTint = if (effectiveCollection != null && (page in setOf("albumInfo", "artistInfo") || (page == "collection" && (effectiveCollection.isArtist() || immersiveAlbumCatalog)))) {
        btm.m.auraplayer.ui.player.rememberCatalogPlayerThemeTokens(
            artwork = effectiveCollection.artwork,
            config = nowPlayingConfig,
            fallbackDarkSurface = MiuixTheme.colorScheme.surface.luminance() < .5f,
            forceHeroSurface = effectiveCollection.isArtist() || immersiveAlbumCatalog,
        ).surface
    } else null
    val title = when (page) {
        "tagEditor" -> tr("tag_editor", "标签编辑器")
        "settings" -> tr("settings", "设置")
        "cloud_settings" -> tr("cloud", "云端")
        "local_settings" -> tr("local", "本地")
        "theme" -> tr("theme_color", "主题与颜色")
        "navigation_material" -> tr("nav_material_title", "底部导航栏材质参数调整")
        "login" -> tr("netease", "网易云音乐")
        "qq_login" -> tr("qq_music", "QQ音乐")
        "switch_user", "qq_switch_user" -> tr("switch_user", "切换账号")
        "cloud_recommended" -> tr("recommended_playlists", "推荐歌单")
        "cloud_follows" -> tr("my_follows", "我的关注")
        "cloud_report" -> tr("listening_report", "听歌报告")
        "downloads" -> tr("downloaded_music", "已下载的音乐")
        "audio_effects" -> tr("audio_effects", "音效")
        "effect_library" -> tr("preset_library", "预设库")
        "effect_custom" -> tr("custom", "自定义")
        "effect_online" -> tr("online_presets", "在线预设库")
        "audio_settings" -> tr("audio_settings", "音频")
        "blocked_songs" -> tr("blocked_songs", "已屏蔽的歌曲")
        "artist_album_settings" -> tr("artist_and_album", "艺术家与专辑")
        "llm_translation" -> tr("llm_translation", "大模型翻译")
        "llm_translation_cache" -> tr("llm_translation_cache", "翻译缓存")
        "llm_raw_output_history" -> tr("llm_raw_history_title", "历史输出")
        "llm_translation_edit" -> tr("llm_edit_lines", "编辑译文")
        "llm_auto_language_rules" -> tr("llm_auto_rules_title", "自动翻译语言规则")
        "song_credits" -> tr("production_team", "制作团队")
        "now_playing_settings" -> tr("now_playing_page", "正在播放界面")
        "aura_sing" -> tr("aura_sing_title", "Aura Sing")
        "aura_sing_downloads" -> tr("spatial_model_downloads", "模型下载")
        "aura_sing_cache" -> tr("aura_sing_cache_title", "伴奏与人声分离缓存")
        "spatial_cover_downloads" -> tr("spatial_model_downloads", "模型下载")
        "spatial_cover_cache" -> tr("spatial_cover_cache_title", "空间封面数据缓存")
        "rear_screen" -> tr("rear_screen", "背屏")
        "about" -> tr("about", "关于")
        "software_update" -> tr("software_update", "软件更新")
        "update_log" -> tr("update_log", "更新日志")
        "donate" -> tr("donate", "捐赠")
        "localPlaylists" -> tr("playback_lists", "播放列表")
        "albums" -> tr("albums", "专辑")
        "artists" -> tr("artists", "艺术家")
        "collection" -> collection?.title.orEmpty()
        "albumInfo", "artistInfo" -> collection?.title.orEmpty()
        "artistSongs" -> tr("top_songs", "热门歌曲")
        "artistAlbums" -> tr("albums", "专辑")
        "artistSingles" -> tr("singles_and_eps", "单曲与EP")
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
    if (page == "spatial_cover_preview") {
        val key = route.substringAfter(':')
        SpatialCoverPreviewPage(key, back)
        return
    }
    if (page == "llm_translation_cache") {
        LlmTranslationCachePage(
            model = model,
            back = back,
            open = open,
            isActive = isActive,
        )
        return
    }
    if (page == "llm_raw_output_history") {
        LlmRawOutputHistoryPage(
            model = model,
            back = back,
            isActive = isActive,
        )
        return
    }
    if (page == "llm_translation_edit") {
        val cacheKey = Uri.decode(route.substringAfter(':'))
        LlmTranslationLineEditorRoute(
            cacheKey = cacheKey,
            model = model,
            back = back,
        )
        return
    }
    val playlistSearchSupported = page == "collection" && !catalog && collection != null
    var playlistSearchQuery by rememberSaveable { mutableStateOf("") }
    var playlistSearchExpanded by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val closePlaylistSearch = {
        playlistSearchExpanded = false
        playlistSearchQuery = ""
        keyboard?.hide()
        Unit
    }
    BackHandler(
        enabled = playlistSearchSupported && (playlistSearchExpanded || playlistSearchQuery.isNotEmpty()),
        onBack = closePlaylistSearch,
    )
    AppPage(title, back, showLargeTitle = page != "about" && (!catalog || page in setOf("artistSongs", "artistAlbums", "artistSingles")),
        actions = {
            if (page == "cloud_report") {
                GlassToolbarIconButton(MiuixIcons.Regular.Refresh, tr("refresh", "刷新")) { reportRefreshNonce++ }
            }
        },
        header = {
            when (page) {
                "effect_library" -> SoftGlassHeaderTabs(listOf(tr("builtin_presets", "内置预设"), tr("user_presets", "用户预设")), presetTab) { presetTab = it }
                "navigation_material" -> SoftGlassHeaderTabs(listOf(tr("nav_material_hyper", "HyperOS"), tr("nav_material_harmony", "HarmonyOS"), tr("nav_material_liquid", "液态玻璃")), materialTab) { materialTab = it }
                "cloud_settings" -> SoftGlassHeaderTabs(
                    listOf(tr("cloud_tab_general", "通用"), tr("netease", "网易云音乐"), tr("qq_music", "QQ音乐")),
                    cloudTab,
                ) { cloudTab = it }
                "cloud_report" -> SoftGlassHeaderTabs(
                    listOf(tr("report_day", "日"), tr("report_week", "周"), tr("report_month", "月"), tr("report_year", "年")),
                    reportTab,
                ) { reportTab = it }
                "collection" -> if (playlistSearchSupported) {
                    Column(Modifier.fillMaxWidth()) {
                        if (destination?.cloud?.source == "rank") {
                            SoftGlassHeaderTabs(
                                listOf(tr("rank_week", "最近一周"), tr("rank_all", "所有时间")),
                                if (model.listeningRankWeek) 0 else 1,
                                onSelected = { model.changeListeningRankPeriod(it == 0) },
                            )
                        }
                        PlaylistHeaderSearchBar(
                            query = playlistSearchQuery,
                            expanded = playlistSearchExpanded,
                            onQueryChange = { playlistSearchQuery = it },
                            onExpandedChange = { expanded ->
                                playlistSearchExpanded = expanded
                                if (!expanded) playlistSearchQuery = ""
                            },
                            onSearch = { keyboard?.hide() },
                        )
                    }
                }
            }
        },
        useLegacyTopBarBackdrop = page == "about",
        topBarTint = catalogTopBarTint,
        pageBackground = when {
            page in setOf("albumInfo", "artistInfo") && effectiveCollection != null -> ({ AlbumInfoBackground(effectiveCollection, Modifier.matchParentSize()) })
            catalog && page !in setOf("artistSongs", "artistAlbums", "artistSingles") -> ({
                CatalogBackground(
                    collection = effectiveCollection,
                    modifier = Modifier.matchParentSize(),
                    nowPlayingConfig = nowPlayingConfig,
                    immersiveAlbum = immersiveAlbumCatalog,
                )
            })
            page == "about" -> ({ AboutMaterialBackground(Modifier.matchParentSize(), model.settings.value.dynamicColorEnabled) })
            else -> null
        }) { padding, _ ->
        val chromeSpace = if (model.current != null && page !in CHROME_HIDDEN_ROUTES) {
            104 + model.settings.value.navigationOffsetDp.coerceAtLeast(0)
        } else 28
        val contentPadding = auraContentPadding(padding, chromeSpace)
        when (page) {
            "settings" -> SettingsPage(model, contentPadding, open)
            "cloud_settings" -> CloudSettingsPage(model, contentPadding, cloudTab, open)
            "local_settings" -> LocalSettingsPage(model, contentPadding)
            "theme" -> ThemePage(model, contentPadding)
            "navigation_material" -> NavigationMaterialPage(model, contentPadding, NavigationMaterialSettings.styles[materialTab])
            "login" -> LoginPage(model, contentPadding, open)
            "qq_login" -> QqMusicPage(model, contentPadding, open)
            "switch_user" -> SwitchUserPage(model, contentPadding)
            "qq_switch_user" -> SwitchUserPage(model, contentPadding, isQq = true)
            "cloud_recommended" -> CloudRecommendedPage(model, contentPadding)
            "cloud_follows" -> CloudFollowsPage(model, contentPadding)
            "cloud_report" -> NeteaseListenReportPage(model, contentPadding, reportTab, reportRefreshNonce) { open("login") }
            "downloads" -> DownloadedMusicPage(model, contentPadding)
            "audio_effects" -> AudioEffectsPage(contentPadding, isActive, open)
            "effect_library" -> PresetLibraryPage(contentPadding, presetTab, open)
            "effect_custom" -> CustomEffectsPage(contentPadding)
            "audio_settings" -> AudioPreferencesPage(model, contentPadding, open)
            "blocked_songs" -> BlockedSongsPage(model, contentPadding)
            "aura_sing" -> AuraSingPage(contentPadding, isActive, open)
            "aura_sing_downloads" -> AuraSingDownloadsPage(contentPadding)
            "aura_sing_cache" -> AuraSingCachePage(contentPadding)
            "effect_online" -> Box(Modifier.fillMaxSize().padding(contentPadding))
            "artist_album_settings" -> ArtistAlbumSettingsPage(contentPadding)
            "llm_translation" -> LlmTranslationSettingsPage(model, contentPadding, open)
            "llm_auto_language_rules" -> LlmAutoLanguageRulesPage(contentPadding)
            "song_credits" -> route.substringAfter(':').toLongOrNull()?.let { songId ->
                SongCreditsPage(songId, model, contentPadding)
            }
            "now_playing_settings" -> NowPlayingSettingsPage(contentPadding, open)
            "spatial_cover_downloads" -> SpatialCoverDownloadsPage(contentPadding)
            "spatial_cover_cache" -> SpatialCoverCachePage(contentPadding, open)
            "rear_screen" -> RearScreenSettingsPage(contentPadding)
            "about" -> AboutPage(model, contentPadding, open)
            "software_update" -> SoftwareUpdatePage(contentPadding, open)
            "update_log" -> UpdateLogPage(contentPadding)
            "donate" -> DonatePage(contentPadding)
            "localPlaylists" -> LocalPlaylistsPage(model, contentPadding)
            "albums" -> AlbumsArtistsPage(model, contentPadding, 0)
            "artists" -> AlbumsArtistsPage(model, contentPadding, 1)
            "collection" -> if (effectiveCollection != null && destination != null) {
                if (catalog) CatalogDetailPage(
                    model = model,
                    padding = contentPadding,
                    collection = effectiveCollection,
                    cloud = destination.cloud,
                    destinationId = destination.id,
                    open = open,
                    nowPlayingConfig = nowPlayingConfig,
                    motionVideoPath = catalogMotionPath,
                    artistLogoUrl = artistCatalogMedia.logoUrl,
                    artistMotionVideoPath = artistCatalogMedia.motionVideoPath,
                    appleMusicRecordLabel = catalogAlbumRecordLabel,
                )
                else CollectionPage(
                    model,
                    contentPadding,
                    effectiveCollection,
                    destination.cloud,
                    searchQuery = playlistSearchQuery,
                    searchExpanded = playlistSearchExpanded,
                )
            }
            "artistSongs", "artistAlbums", "artistSingles" -> if (collection != null && albumInfoDestination != null) {
                ArtistContentsPage(
                    model = model,
                    padding = contentPadding,
                    collection = collection,
                    cloud = albumInfoDestination.cloud,
                    mode = when (page) {
                        "artistAlbums" -> ArtistContentMode.ALBUMS
                        "artistSingles" -> ArtistContentMode.SINGLES_EPS
                        else -> ArtistContentMode.SONGS
                    },
                )
            }
            "albumInfo" -> if (collection != null && albumInfoDestination != null) AlbumInfoPage(model, contentPadding, collection, albumInfoDestination.cloud, catalogAlbumRecordLabel, catalogMotionPath)
            "artistInfo" -> if (effectiveCollection != null && albumInfoDestination != null) ArtistInfoPage(contentPadding, effectiveCollection, albumInfoDestination.cloud, artistCatalogMedia.motionVideoPath)
            "directory" -> DirectoryPage(model, contentPadding)
            else -> LegalPage(page, contentPadding)
        }
    }
}

internal fun shouldHideStatusBar(
    mode: btm.m.auraplayer.playback.StatusBarHideMode,
    playerCoveringBars: Boolean,
    isLandscape: Boolean,
): Boolean = when (mode) {
    btm.m.auraplayer.playback.StatusBarHideMode.NEVER -> false
    btm.m.auraplayer.playback.StatusBarHideMode.ALWAYS -> true
    btm.m.auraplayer.playback.StatusBarHideMode.NOW_PLAYING_ONLY -> playerCoveringBars
    btm.m.auraplayer.playback.StatusBarHideMode.LANDSCAPE_ONLY -> isLandscape
}

private val CHROME_HIDDEN_ROUTES = setOf("navigation_material", "lyricShare", "about", "spatial_cover_preview")

internal fun shouldHideBottomChrome(pages: List<String>, closingIndex: Int): Boolean {
    val topIndex = pages.lastIndex
    if (topIndex < 0) return false
    val routeKey = { r: String -> r.substringBefore(':') }
    val topHidesChrome = routeKey(pages[topIndex]) in CHROME_HIDDEN_ROUTES && closingIndex != topIndex
    val revealingHiddenUnderlay = topIndex >= 1 && closingIndex == topIndex && routeKey(pages[topIndex - 1]) in CHROME_HIDDEN_ROUTES
    return topHidesChrome || revealingHiddenUnderlay
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
    // Tab ids stay 0 = home, 1 = cloud, 2 = local; pure local mode simply omits the cloud tab.
    val tabIds = if (model.cloudEnabled) listOf(0, 1, 2) else listOf(0, 2)
    val allLabels = listOf(tr("home", "首页"), tr("cloud", "云端"), tr("local", "本地"))
    val labels = tabIds.map { allLabels[it] }
    val navSelected = remember { mutableIntStateOf(0) }
    navSelected.intValue = tabIds.indexOf(selected.intValue).coerceAtLeast(0)
    BoxWithConstraints(Modifier.offset(y = -settings.navigationOffsetDp.dp).then(modifier).fillMaxWidth().height(100.dp + navInset)) {
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
        }.blur(if (navExit < .999f) (navExit * 10f).dp else 0.dp).drawWithContent {
            if (navExit < .999f) drawContent()
        }) {
            CustomNavigation(view, tabs = labels.mapIndexed { index, label -> HostTab(label, "aura.${tabIds[index]}") {
                if (!searchMounted && secondaryProgress < .01f) selected.intValue = tabIds[index]
            } }, selectedIndex = navSelected, blurRadius = if (liquid) 3 else 18, labelMode = settings.labels,
                navigationStyle = navigationStyle, advancedMaterial = LocalAdvancedMaterialEnabled.current, colorMode = settings.theme,
                accentColorOverride = accent, material = rememberModuleNavigationMaterial(navigationStyle),
                dimensionScale = dimensions, liquidBottomSpacingDp = 7, onHostPreDraw = {}, backdropOverride = backdrop,
                tabIconContent = { index, color -> AuraTabIcon(tabIds.getOrElse(index) { index }, color) })
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
