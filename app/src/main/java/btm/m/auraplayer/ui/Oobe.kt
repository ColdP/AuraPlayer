// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.MainActivity
import btm.m.auraplayer.ui.navigation.DampedDragAnimation
import btm.m.auraplayer.ui.navigation.InteractiveHighlight
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

private const val OOBE_PREDICTIVE_BACK_MAX_PROGRESS = .8f

private enum class OobePage { WELCOME, PREPARATION, AGREEMENTS, FEATURES, COMPLETE }
private enum class OobeLegalPage { AGREEMENT, DISCLAIMER, PRIVACY }
private data class OobeDestination(val page: OobePage, val legalPage: OobeLegalPage? = null)

private enum class OobeStatusState { OK, ACTION, ERROR }

private data class OobeFeatureItem(
    val artwork: OobeFeatureArtwork,
    val badge: String,
    val title: String,
    val description: String,
    val accent: Color,
)

@Composable
internal fun OobeFlow(
    model: AuraViewModel,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val isDark = MiuixTheme.colorScheme.background.luminance() < .5f
    val background = if (isDark) Color.Black else MiuixTheme.colorScheme.surface
    val advancedMaterial = LocalAdvancedMaterialEnabled.current

    SideEffect {
        val activity = context as? MainActivity ?: return@SideEffect
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        activity.window.statusBarColor = android.graphics.Color.TRANSPARENT
        activity.window.navigationBarColor = android.graphics.Color.TRANSPARENT
        activity.window.isStatusBarContrastEnforced = false
    }

    var page by rememberSaveable { mutableStateOf(OobePage.WELCOME) }
    var completionVisit by remember { mutableIntStateOf(0) }
    var legalPage by remember { mutableStateOf<OobeLegalPage?>(null) }
    var agreementsAccepted by rememberSaveable { mutableStateOf(false) }
    var navigatingForward by remember { mutableStateOf(true) }
    var predictiveHandoffDestination by remember { mutableStateOf<OobeDestination?>(null) }

    val go: (OobePage) -> Unit = { next ->
        if (next == OobePage.COMPLETE && page != OobePage.COMPLETE) completionVisit++
        navigatingForward = next.ordinal >= page.ordinal
        predictiveHandoffDestination = null
        page = next
    }
    val canNavigateBack = page != OobePage.WELCOME || legalPage != null
    val navigateBack: () -> Unit = {
        navigatingForward = false
        if (legalPage != null) legalPage = null else go(OobePage.entries[page.ordinal - 1])
    }

    val renderDestination: @Composable (OobeDestination) -> Unit = { destination ->
        destination.legalPage?.let { legal ->
            OobeLegalDocumentPage(legal) {
                navigatingForward = false
                predictiveHandoffDestination = null
                legalPage = null
            }
        } ?: when (destination.page) {
            OobePage.WELCOME -> OobeWelcomePage { go(OobePage.PREPARATION) }
            OobePage.PREPARATION -> OobePreparationPage(
                model = model,
                back = { go(OobePage.WELCOME) },
                next = { go(OobePage.AGREEMENTS) },
            )
            OobePage.AGREEMENTS -> OobeAgreementsPage(
                accepted = agreementsAccepted,
                back = { go(OobePage.PREPARATION) },
                open = {
                    navigatingForward = true
                    predictiveHandoffDestination = null
                    legalPage = it
                },
                toggleAcceptance = { agreementsAccepted = !agreementsAccepted },
                next = { go(OobePage.FEATURES) },
            )
            OobePage.FEATURES -> OobeFeaturesPage(
                back = { go(OobePage.AGREEMENTS) },
                next = { go(OobePage.COMPLETE) },
            )
            OobePage.COMPLETE -> OobeCompletePage(
                confettiKey = completionVisit,
                back = { go(OobePage.FEATURES) },
                finish = onFinished,
            )
        }
    }

    val currentDestination = OobeDestination(page, legalPage)
    val predictiveProgress = remember { Animatable(0f) }
    val predictivePreviewBlur = remember { Animatable(14f) }
    var predictivePreview by remember { mutableStateOf<OobeDestination?>(null) }
    var predictiveSwipeEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    var predictiveGestureActive by remember { mutableStateOf(false) }

    PredictiveBackHandler(enabled = canNavigateBack) { events ->
        predictivePreviewBlur.snapTo(14f)
        predictivePreview = if (legalPage != null) {
            OobeDestination(page)
        } else {
            OobeDestination(OobePage.entries[page.ordinal - 1])
        }
        predictiveGestureActive = true
        try {
            events.collect { event ->
                predictiveSwipeEdge = event.swipeEdge
                predictiveProgress.snapTo(
                    event.progress.coerceAtMost(OOBE_PREDICTIVE_BACK_MAX_PROGRESS),
                )
                predictivePreviewBlur.snapTo(
                    14f - 10f *
                        (predictiveProgress.value / OOBE_PREDICTIVE_BACK_MAX_PROGRESS).coerceIn(0f, 1f),
                )
            }
            val completionDuration = ((1f - predictiveProgress.value) * 240f).toInt().coerceAtLeast(1)
            coroutineScope {
                launch {
                    predictiveProgress.animateTo(
                        1f,
                        tween(completionDuration, easing = FastOutSlowInEasing),
                    )
                }
                launch {
                    predictivePreviewBlur.animateTo(
                        0f,
                        tween(300, easing = FastOutSlowInEasing),
                    )
                }
            }
            val handoffDestination = predictivePreview
            navigateBack()
            predictiveHandoffDestination = handoffDestination
            withFrameNanos { }
        } catch (_: CancellationException) {
            predictiveProgress.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
        } finally {
            predictiveGestureActive = false
            predictivePreview = null
            predictiveProgress.snapTo(0f)
            predictivePreviewBlur.snapTo(14f)
        }
    }

    Box(Modifier.fillMaxSize().background(background)) {
        val progress = predictiveProgress.value.coerceIn(0f, 1f)
        val swipeDirection = if (predictiveSwipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
        if (predictiveGestureActive) {
            predictivePreview?.let { preview ->
                val previewBlur = if (advancedMaterial) predictivePreviewBlur.value else 0f
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = -swipeDirection * size.width * (1f - progress) / 7f
                            scaleX = .975f + .025f * progress
                            scaleY = scaleX
                            alpha = progress
                        }
                        .then(if (previewBlur > 0.1f) Modifier.blur(previewBlur.dp) else Modifier),
                ) {
                    renderDestination(preview)
                }
            }
        }
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                translationX = swipeDirection * size.width * progress / 3f
                scaleX = 1f - .025f * progress
                scaleY = scaleX
                alpha = 1f - progress
            },
        ) {
            AnimatedContent(
                targetState = currentDestination,
                transitionSpec = {
                    if (predictiveHandoffDestination == targetState) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        val forward = when {
                            targetState.legalPage != null && initialState.legalPage == null -> true
                            targetState.legalPage == null && initialState.legalPage != null -> false
                            else -> targetState.page.ordinal >= initialState.page.ordinal
                        }
                        if (forward) {
                            (slideInHorizontally(
                                animationSpec = tween(560, easing = FastOutSlowInEasing),
                                initialOffsetX = { it / 7 },
                            ) + fadeIn(tween(420)) + scaleIn(
                                animationSpec = tween(560, easing = FastOutSlowInEasing),
                                initialScale = .985f,
                            )) togetherWith (slideOutHorizontally(
                                animationSpec = tween(460, easing = FastOutSlowInEasing),
                                targetOffsetX = { -it / 3 },
                            ) + fadeOut(tween(360)) + scaleOut(
                                animationSpec = tween(460, easing = FastOutSlowInEasing),
                                targetScale = .975f,
                            ))
                        } else {
                            (slideInHorizontally(
                                animationSpec = tween(520, easing = FastOutSlowInEasing),
                                initialOffsetX = { -it / 3 },
                            ) + fadeIn(tween(380)) + scaleIn(
                                animationSpec = tween(520, easing = FastOutSlowInEasing),
                                initialScale = .975f,
                            )) togetherWith (slideOutHorizontally(
                                animationSpec = tween(420, easing = FastOutSlowInEasing),
                                targetOffsetX = { it / 7 },
                            ) + fadeOut(tween(320)) + scaleOut(
                                animationSpec = tween(420, easing = FastOutSlowInEasing),
                                targetScale = .985f,
                            ))
                        }
                    }
                },
                label = "oobeNavigation",
                modifier = Modifier.fillMaxSize().background(background),
            ) { destination ->
                val navigationBlurRadius by transition.animateFloat(
                    transitionSpec = { tween(520, easing = FastOutSlowInEasing) },
                    label = "oobeNavigationBlur",
                ) { state ->
                    if (!advancedMaterial) 0f
                    else if (navigatingForward) {
                        if (state == EnterExitState.PostExit) 12f else 0f
                    } else {
                        if (state == EnterExitState.PreEnter) 12f else 0f
                    }
                }
                val appliedBlur = if (destination == predictiveHandoffDestination) 0f else navigationBlurRadius
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(if (appliedBlur > 0.1f) Modifier.blur(appliedBlur.dp) else Modifier),
                ) {
                    renderDestination(destination)
                }
            }
        }
    }
}

@Composable
private fun OobeFrame(
    back: (() -> Unit)? = null,
    title: String? = null,
    bottom: (@Composable () -> Unit)? = null,
    backgroundBackdrop: LayerBackdrop? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val background = if (MiuixTheme.colorScheme.background.luminance() < .5f) Color.Black else MiuixTheme.colorScheme.surface
    Scaffold(containerColor = background) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(background)
                    .then(if (backgroundBackdrop != null) Modifier.layerBackdrop(backgroundBackdrop) else Modifier),
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 28.dp),
            ) {
                Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (back != null) {
                        Image(
                            MiuixIcons.Regular.ChevronBackward,
                            tr("返回", "返回"),
                            Modifier.size(38.dp).clickable(onClick = back),
                            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onBackground),
                        )
                    }
                }
                if (title != null) {
                    Text(
                        title,
                        modifier = Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 42.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(Modifier.weight(1f).fillMaxWidth(), content = content)
                bottom?.let { Box(Modifier.fillMaxWidth().padding(bottom = 18.dp)) { it() } }
            }
        }
    }
}

@Composable
private fun OobeWelcomePage(next: () -> Unit) {
    val brandAccent = MiuixTheme.colorScheme.primary
    val onBrandAccent = MiuixTheme.colorScheme.onPrimary
    val backdrop = rememberLayerBackdrop()
    val animationScope = rememberCoroutineScope()
    val advancedMaterial = LocalAdvancedMaterialEnabled.current && isRuntimeShaderSupported()
    val drag = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = 0f,
            valueRange = -1f..1f,
            visibilityThreshold = .001f,
            initialScale = 1f,
            pressedScale = 1.08f,
            onDragStopped = { animateToValue(0f) },
            onDrag = { size, amount ->
                updateValue((targetValue + amount.x / size.width.coerceAtLeast(1)).coerceIn(-1f, 1f))
            },
        )
    }
    val highlight = remember(animationScope) {
        InteractiveHighlight(animationScope) { size, offset ->
            Offset(offset.x.coerceIn(0f, size.width), offset.y.coerceIn(0f, size.height))
        }
    }
    OobeFrame(backgroundBackdrop = backdrop) {
        Spacer(Modifier.weight(.37f))
        Text(tr("oobe_welcome", "欢迎使用"), fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text("Aura Player", color = brandAccent, fontSize = 39.sp, fontWeight = FontWeight.Bold)
        Text(
            tr("oobe_intro", "一款开源的\n本地与云端一体化\n高解析度音乐播放器"),
            modifier = Modifier.padding(top = 25.dp),
            fontSize = 27.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Box(
            Modifier
                .padding(top = 40.dp)
                .size(65.dp)
                .then(highlight.gestureModifier)
                .then(drag.modifier)
                .clip(CircleShape)
                .then(
                    if (advancedMaterial) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { CircleShape },
                            effects = {
                                vibrancy()
                                blur(TOOLBAR_GLASS_BLUR_RADIUS.toPx())
                                toolbarGlassLens(drag.pressProgress)
                            },
                            highlight = { toolbarGlassHighlight(drag.pressProgress) },
                            shadow = { Shadow.Default.copy(radius = 5.dp, color = Color.Black, alpha = .24f) },
                            innerShadow = null,
                            layerBlock = {
                                scaleX = drag.scaleX
                                scaleY = drag.scaleY
                                val velocity = abs(drag.velocity.coerceIn(-1f, 1f))
                                scaleX *= 1f + velocity * .12f
                                scaleY /= 1f + velocity * .08f
                                translationX = drag.value * 5.dp.toPx()
                            },
                            onDrawSurface = { drawCircle(brandAccent.copy(alpha = .92f)) },
                        )
                    } else {
                        Modifier
                            .graphicsLayer {
                                scaleX = drag.scaleX
                                scaleY = drag.scaleY
                                translationX = drag.value * 5.dp.toPx()
                            }
                            .background(brandAccent, CircleShape)
                    },
                )
                .then(highlight.modifier)
                .clickable(interactionSource = null, indication = null, onClick = next),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                MiuixIcons.Regular.ChevronForward,
                tr("oobe_continue", "继续"),
                Modifier.size(34.dp),
                colorFilter = ColorFilter.tint(onBrandAccent),
            )
        }
        Spacer(Modifier.weight(.63f))
    }
}

@Composable
private fun OobePreparationPage(
    model: AuraViewModel,
    back: () -> Unit,
    next: () -> Unit,
) {
    val context = LocalContext.current
    val androidSupported = Build.VERSION.SDK_INT >= 33
    var audioGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.READ_MEDIA_AUDIO)) }
    var audioDeniedOnce by rememberSaveable { mutableStateOf(false) }
    var notifGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) }
    var notifDeniedOnce by rememberSaveable { mutableStateOf(false) }

    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        audioGranted = granted
        if (!granted) audioDeniedOnce = true
        if (granted) model.refreshLibrary()
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifGranted = granted
        if (!granted) notifDeniedOnce = true
    }
    val neteaseLoginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val cookie = result.data?.getStringExtra(OfficialLoginActivity.EXTRA_COOKIE).orEmpty()
            if (cookie.isNotBlank()) model.completeOfficialLogin(cookie)
        }
    }
    val qqLoginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val sessionCookie = result.data?.getStringExtra(OfficialLoginActivity.EXTRA_COOKIE).orEmpty()
            if (sessionCookie.isNotBlank()) model.completeQqOfficialLogin(sessionCookie)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val nextAudio = hasPermission(context, Manifest.permission.READ_MEDIA_AUDIO)
        if (nextAudio && !audioGranted) model.refreshLibrary()
        audioGranted = nextAudio
        notifGranted = hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
    }

    val openAppSettings: () -> Unit = {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
            )
        }
    }

    AppPage(
        title = "",
        onBack = back,
        navigationIcon = { OobeBackButton(back) },
        compactTopBar = true,
        floatingToolbar = {
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                OobeButton(tr("oobe_continue", "继续"), androidSupported, next)
            }
        },
    ) { padding, _ ->
        LazyColumn(
            pageContentModifier(),
            contentPadding = oobeListPadding(padding, 104),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { OobeSectionTitle(tr("oobe_preparation_title", "应用预备工作")) }
            item {
                OobeStatusCard(
                    title = tr("android_version", "Android 版本"),
                    summary = "${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}",
                    status = if (androidSupported) OobeStatusState.OK else OobeStatusState.ERROR,
                )
            }
            item {
                OobeStatusCard(
                    title = tr("oobe_audio_permission_title", "本地音频读取权限"),
                    summary = if (audioGranted) {
                        tr("oobe_permission_granted", "已授权")
                    } else {
                        tr("oobe_audio_permission_summary", "点击授权以扫描并播放本地无损音乐（可稍后开启）")
                    },
                    status = if (audioGranted) OobeStatusState.OK else OobeStatusState.ACTION,
                    onClick = if (!audioGranted) {
                        {
                            if (audioDeniedOnce) openAppSettings()
                            else audioLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
                        }
                    } else {
                        null
                    },
                )
            }
            item {
                OobeStatusCard(
                    title = tr("oobe_notification_permission_title", "媒体通知权限"),
                    summary = if (notifGranted) {
                        tr("oobe_permission_granted", "已授权")
                    } else {
                        tr("oobe_notification_permission_summary", "点击授权以显示播放控制与下载通知（可稍后开启）")
                    },
                    status = if (notifGranted) OobeStatusState.OK else OobeStatusState.ACTION,
                    onClick = if (!notifGranted) {
                        {
                            if (notifDeniedOnce) openAppSettings()
                            else notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    } else {
                        null
                    },
                )
            }
            item {
                val neteaseAccount = model.account
                val qqAccount = model.qqAccount
                SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                    Preference(
                        title = tr("oobe_netease_login", "网易云音乐登录"),
                        summary = when {
                            model.cloudBusy -> tr("verifying_account", "正在验证账号…")
                            neteaseAccount != null -> tr("oobe_logged_in_as", "已登录：%s").format(neteaseAccount.nickname)
                            else -> tr("oobe_optional_login_summary", "可选，点击直接登录")
                        },
                    ) {
                        if (model.app.endpoint.isNotBlank()) model.useOfficialService()
                        neteaseLoginLauncher.launch(OfficialLoginActivity.createIntent(context, clearCookies = false))
                    }
                    Preference(
                        title = tr("oobe_qq_login", "QQ音乐登录"),
                        summary = when {
                            model.qqBusy -> tr("verifying_account", "正在验证账号…")
                            qqAccount != null -> tr("oobe_logged_in_as", "已登录：%s").format(qqAccount.nickname)
                            else -> tr("oobe_optional_login_summary", "可选，点击直接登录")
                        },
                    ) {
                        if (model.app.qqEndpoint.isNotBlank()) model.useQqOfficialService()
                        qqLoginLauncher.launch(OfficialLoginActivity.createIntent(context, clearCookies = false, provider = "qq"))
                    }
                }
            }
            item { OobeLanguageCard(model) }
        }
    }
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun OobeStatusCard(
    title: String,
    summary: String?,
    status: OobeStatusState,
    onClick: (() -> Unit)? = null,
) {
    val accent = MiuixTheme.colorScheme.primary
    SeamlessCard(
        Modifier
            .fillMaxWidth()
            .then(if (summary == null) Modifier.height(64.dp) else Modifier),
        cornerRadius = 22.5.dp,
        insideMargin = PaddingValues(0.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .then(if (summary == null) Modifier.height(64.dp).padding(horizontal = 18.dp) else Modifier.padding(18.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    title,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Bold,
                )
                summary?.let {
                    Text(
                        it,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            when (status) {
                OobeStatusState.OK -> Image(
                    OobeCheckIcon,
                    null,
                    Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(accent),
                )
                OobeStatusState.ACTION -> Image(
                    MiuixIcons.Regular.ChevronForward,
                    null,
                    Modifier.size(22.dp),
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
                )
                OobeStatusState.ERROR -> Image(
                    MiuixIcons.Regular.Close,
                    null,
                    Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(Color(0xFFE53935)),
                )
            }
        }
    }
}

@Composable
private fun OobeLanguageCard(model: AuraViewModel) {
    val current = model.settings.value.language
    val values = listOf("zh", "en", "ja")
    val labels = listOf(tr("chinese", "简体中文"), tr("english", "English"), "日本語")
    val selected = values.indexOf(current).coerceAtLeast(0)
    SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
        GlassDropdownPreference(
            title = tr("select_language", "选择语言"),
            items = labels,
            selectedIndex = selected,
            backdrop = LocalToolbarBackdrop.current,
        ) { index ->
            val nextLang = values[index]
            model.updateSettings { it.copy(language = nextLang) }
            I18n.language = nextLang
        }
    }
}

@Composable
private fun OobeAgreementsPage(
    accepted: Boolean,
    back: () -> Unit,
    open: (OobeLegalPage) -> Unit,
    toggleAcceptance: () -> Unit,
    next: () -> Unit,
) {
    AppPage(
        title = "",
        onBack = back,
        navigationIcon = { OobeBackButton(back) },
        compactTopBar = true,
        floatingToolbar = {
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                OobeButton(tr("oobe_continue", "继续"), accepted, next)
            }
        },
    ) { padding, _ ->
        LazyColumn(
            pageContentModifier(),
            contentPadding = oobeListPadding(padding, 104),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { OobeSectionTitle(tr("oobe_agreements_title", "协议与声明")) }
            item {
                SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                    Preference(tr("user_agreement", "用户协议")) { open(OobeLegalPage.AGREEMENT) }
                    Preference(tr("disclaimer", "免责声明")) { open(OobeLegalPage.DISCLAIMER) }
                    Preference(tr("privacy", "隐私政策")) { open(OobeLegalPage.PRIVACY) }
                }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClick = toggleAcceptance)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    AuraCheckbox(
                        checked = accepted,
                        onCheckedChange = { toggleAcceptance() },
                    )
                    Text(
                        tr(
                            "oobe_agreement_checkbox",
                            "我已阅读并同意《用户协议》《免责声明》及《隐私政策》，了解 Aura Player 作为本地与第三方音乐播放器的使用范围与相关说明，自愿按上述条款使用本应用。",
                        ),
                        Modifier.padding(start = 12.dp),
                        style = MiuixTheme.textStyles.body2,
                        fontSize = 14.7.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun OobeLegalDocumentPage(
    page: OobeLegalPage,
    back: () -> Unit,
) {
    val context = LocalContext.current
    val lang = when (I18n.language) {
        "en" -> "en"
        "ja" -> "ja"
        else -> "zh"
    }
    val title = when (page) {
        OobeLegalPage.AGREEMENT -> tr("user_agreement", "用户协议")
        OobeLegalPage.DISCLAIMER -> tr("disclaimer", "免责声明")
        OobeLegalPage.PRIVACY -> tr("privacy", "隐私政策")
    }
    val assetName = when (page) {
        OobeLegalPage.AGREEMENT -> "user_agreement.txt"
        OobeLegalPage.DISCLAIMER -> "disclaimer.txt"
        OobeLegalPage.PRIVACY -> "privacy.txt"
    }
    val content = remember(assetName, lang) {
        runCatching {
            context.assets.open("legal/$lang/$assetName").bufferedReader().use { it.readText() }
        }.getOrElse {
            context.assets.open("legal/zh/$assetName").bufferedReader().use { it.readText() }
        }
    }
    AppPage(title, back) { padding, _ ->
        LazyColumn(
            pageContentModifier(),
            contentPadding = oobeListPadding(padding, 28),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            legalDocumentItems(content)
        }
    }
}

@Composable
private fun OobeFeaturesPage(
    back: () -> Unit,
    next: () -> Unit,
) {
    val isDark = MiuixTheme.colorScheme.surface.luminance() < .5f
    val features = listOf(
        OobeFeatureItem(
            artwork = OobeFeatureArtwork.SPATIAL_COVER,
            badge = tr("oobe_feat_spatial_badge", "沉浸视觉"),
            title = tr("oobe_feat_spatial_title", "空间封面与动态视觉"),
            description = tr(
                "oobe_feat_spatial_desc",
                "基于深度分层与陀螺仪视差呈现立体专辑封面，让播放界面随光影呼吸流转。",
            ),
            accent = if (isDark) Color(0xFFC97884) else Color(0xFFB86874),
        ),
        OobeFeatureItem(
            artwork = OobeFeatureArtwork.AURA_SING,
            badge = tr("oobe_feat_sing_badge", "端侧 AI"),
            title = tr("oobe_feat_sing_title", "Aura Sing 人声伴奏分离"),
            description = tr(
                "oobe_feat_sing_desc",
                "本地端侧模型实时分离人声与伴奏，无需上传云端，随时调节人声音量开启伴唱。",
            ),
            accent = if (isDark) Color(0xFF7096C6) else Color(0xFF567CB0),
        ),
        OobeFeatureItem(
            artwork = OobeFeatureArtwork.LIVE_LYRICS_AI,
            badge = tr("oobe_feat_lyrics_badge", "智能歌词"),
            title = tr("oobe_feat_lyrics_title", "逐字歌词与大模型翻译"),
            description = tr(
                "oobe_feat_lyrics_desc",
                "丝滑的逐字高亮动效搭配兼容大模型智能翻译，精准传达外语歌词的语境与韵味。",
            ),
            accent = if (isDark) Color(0xFF8B88BD) else Color(0xFF736FA8),
        ),
        OobeFeatureItem(
            artwork = OobeFeatureArtwork.PRO_DSP_USB,
            badge = tr("oobe_feat_dsp_badge", "高解析音频"),
            title = tr("oobe_feat_dsp_title", "专业 DSP 与独占 USB DAC"),
            description = tr(
                "oobe_feat_dsp_desc",
                "内置高精度参量均衡器、丰富音效预设与 USB DAC 硬件独占输出，释放无损母带细节。",
            ),
            accent = if (isDark) Color(0xFF6EA386) else Color(0xFF568B6E),
        ),
        OobeFeatureItem(
            artwork = OobeFeatureArtwork.LOCAL_CLOUD,
            badge = tr("oobe_feat_library_badge", "融合曲库"),
            title = tr("oobe_feat_library_title", "本地与云端一体化曲库"),
            description = tr(
                "oobe_feat_library_desc",
                "无缝整合本地无损音频与网易云、QQ 音乐云端歌单，也可一键切换纯净本地模式。",
            ),
            accent = if (isDark) Color(0xFFC49A6C) else Color(0xFFB08454),
        ),
        OobeFeatureItem(
            artwork = OobeFeatureArtwork.LIQUID_GLASS_UI,
            badge = tr("oobe_feat_ui_badge", "个性外观"),
            title = tr("oobe_feat_ui_title", "多风格界面与液态玻璃"),
            description = tr(
                "oobe_feat_ui_desc",
                "自由切换 Hyper、Material 与 One UI 界面风格，支持液态玻璃导航栏与动态取色。",
            ),
            accent = if (isDark) Color(0xFF6B9FA6) else Color(0xFF52878E),
        ),
        OobeFeatureItem(
            artwork = OobeFeatureArtwork.WIDGETS_REAR,
            badge = tr("oobe_feat_widget_badge", "多端触达"),
            title = tr("oobe_feat_widget_title", "背屏协同与桌面小组件"),
            description = tr(
                "oobe_feat_widget_desc",
                "提供多尺寸桌面灵动小组件，并适配背屏沉浸播放控制，息屏或桌面均可随手切歌。",
            ),
            accent = if (isDark) Color(0xFF959DA8) else Color(0xFF6B727D),
        ),
        OobeFeatureItem(
            artwork = OobeFeatureArtwork.TAG_LYRIC_SHARE,
            badge = tr("oobe_feat_share_badge", "精雕细节"),
            title = tr("oobe_feat_share_title", "歌词分享卡片与标签编辑"),
            description = tr(
                "oobe_feat_share_desc",
                "内置多套极简排版模板一键生成歌词分享图，并支持本地无损音频元数据与封面编辑。",
            ),
            accent = if (isDark) Color(0xFFA8839D) else Color(0xFF8F6B84),
        ),
    )

    AppPage(
        title = "",
        onBack = back,
        navigationIcon = { OobeBackButton(back) },
        compactTopBar = true,
        floatingToolbar = {
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                OobeButton(tr("oobe_continue", "继续"), true, next)
            }
        },
    ) { padding, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = LocalAuraLayout.current.pageMargin + 4.dp,
                    end = LocalAuraLayout.current.pageMargin + 4.dp,
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 104.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                tr("oobe_features_title", "探索特色功能"),
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
                textAlign = TextAlign.Center,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                tr("oobe_features_subtitle", "为高解析度聆听体验精心打磨"),
                modifier = Modifier.fillMaxWidth().padding(bottom = 26.dp),
                textAlign = TextAlign.Center,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            OobeFeatureCardStack(
                items = features,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Stacked card deck inspired by CloudHomeBannerStack in CloudHomePage.kt,
 * showcasing Aura Player's Apple-style animated vector feature cards.
 */
@Composable
private fun OobeFeatureCardStack(
    items: List<OobeFeatureItem>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val isDark = MiuixTheme.colorScheme.surface.luminance() < .5f
    val advancedMaterial = LocalAdvancedMaterialEnabled.current

    val drag = remember(items) { Animatable(0f) }
    val promote = remember(items) { Animatable(0f) }
    var index by remember(items) { mutableIntStateOf(0) }
    var isDragging by remember(items) { mutableStateOf(false) }
    var isAnimatingStep by remember(items) { mutableStateOf(false) }
    var animatingStep by remember(items) { mutableIntStateOf(1) }

    val dragFan by animateFloatAsState(
        targetValue = if (items.size > 1 && isDragging) 1f else 0f,
        animationSpec = tween(durationMillis = if (isDragging) 160 else 280, easing = FastOutSlowInEasing),
        label = "oobe_feature_drag_fan",
    )

    val safeIndex = ((index % items.size) + items.size) % items.size
    val cardHeight = 368.dp
    val cardRadius = 28.dp
    val topBuffer = 8.dp + 8.dp * dragFan
    val bottomBuffer = 22.dp + 14.dp * dragFan

    Column(
        modifier = modifier.padding(top = topBuffer),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp)
                .padding(bottom = bottomBuffer),
        ) {
            val cardWidthPx = with(density) { maxWidth.coerceAtLeast(160.dp).toPx() }
            val swipeThresholdPx = with(density) { 56.dp.toPx() }
            val exitDistancePx = cardWidthPx * 1.15f

            val dragPromote = if (items.size > 1 && isDragging) {
                (abs(drag.value) / (cardWidthPx * 0.85f)).coerceIn(0f, 0.85f)
            } else {
                0f
            }
            val effectivePromote = maxOf(promote.value, dragPromote).coerceIn(0f, 1f)
            // Swipe left  (drag < 0): next A->B->C->D->E (+1).
            // Swipe right (drag > 0): prev E->D->C->B->A (-1).
            val stackStep = when {
                isAnimatingStep -> animatingStep
                drag.value > 1f -> -1
                else -> 1
            }

            fun offsetIndex(stepOffset: Int): Int =
                ((safeIndex + stackStep * stepOffset) % items.size + items.size) % items.size

            fun advanceCard(step: Int) {
                if (isAnimatingStep || items.size <= 1) return
                val normalizedStep = if (step < 0) -1 else 1
                // Next (+1) exits left (-1f); previous (-1) exits right (+1f).
                val exitSign = -normalizedStep.toFloat()
                animatingStep = normalizedStep
                isAnimatingStep = true
                scope.launch {
                    try {
                        coroutineScope {
                            launch {
                                drag.animateTo(
                                    exitSign * exitDistancePx,
                                    tween(230, easing = FastOutSlowInEasing),
                                )
                            }
                            launch {
                                promote.animateTo(
                                    1f,
                                    tween(230, easing = FastOutSlowInEasing),
                                )
                            }
                        }
                        // WYSIWYG: promote the exact card revealed directly beneath for this direction.
                        index = ((index + normalizedStep) % items.size + items.size) % items.size
                        drag.snapTo(0f)
                        promote.snapTo(0f)
                    } finally {
                        isAnimatingStep = false
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(cardHeight)
                    .pointerInput(items, cardWidthPx) {
                        if (items.size <= 1) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragStart = {
                                if (isAnimatingStep) return@detectHorizontalDragGestures
                                isDragging = true
                            },
                            onHorizontalDrag = { _, delta ->
                                if (isAnimatingStep) return@detectHorizontalDragGestures
                                val nextDrag = (drag.value + delta).coerceIn(-exitDistancePx, exitDistancePx)
                                scope.launch { drag.snapTo(nextDrag) }
                            },
                            onDragCancel = {
                                isDragging = false
                                if (isAnimatingStep) return@detectHorizontalDragGestures
                                scope.launch {
                                    coroutineScope {
                                        launch { drag.animateTo(0f, spring(dampingRatio = 0.84f, stiffness = 420f)) }
                                        launch { promote.animateTo(0f, tween(200)) }
                                    }
                                }
                            },
                            onDragEnd = {
                                isDragging = false
                                if (isAnimatingStep) return@detectHorizontalDragGestures
                                val deltaX = drag.value
                                when {
                                    deltaX < -swipeThresholdPx -> advanceCard(1)
                                    deltaX > swipeThresholdPx -> advanceCard(-1)
                                    else -> scope.launch {
                                        coroutineScope {
                                            launch { drag.animateTo(0f, spring(dampingRatio = 0.84f, stiffness = 420f)) }
                                            launch { promote.animateTo(0f, tween(200)) }
                                        }
                                    }
                                }
                            },
                        )
                    },
            ) {
                val current = items[safeIndex]
                val cardSlotModifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(cardHeight)

                // Rest poses for 2nd and 3rd cards (interpolated between permanent stack peek and drag-fan)
                val rot1Base = oobeLerpFloat(2.6f, 4.5f, dragFan)
                val tx1Base = oobeLerpFloat(6f, 12f, dragFan)
                val ty1Base = oobeLerpFloat(6f, 15f, dragFan)
                val scale1Base = oobeLerpFloat(0.955f, 0.95f, dragFan)
                val alpha1Base = oobeLerpFloat(0.90f, 0.94f, dragFan)
                val blur1Base = oobeLerpFloat(2.5f, 2.0f, dragFan)

                val rot2Base = oobeLerpFloat(-3.2f, -5.5f, dragFan)
                val tx2Base = oobeLerpFloat(-7f, -12f, dragFan)
                val ty2Base = oobeLerpFloat(11f, 21f, dragFan)
                val scale2Base = oobeLerpFloat(0.91f, 0.905f, dragFan)
                val alpha2Base = oobeLerpFloat(0.76f, 0.84f, dragFan)
                val blur2Base = oobeLerpFloat(7.0f, 6.2f, dragFan)

                // 4th card fades in at 3rd-card pose during seamless promotion
                if (items.size >= 4 && effectivePromote > 0.01f) {
                    val back3 = items[offsetIndex(3)]
                    val alpha3 = alpha2Base * effectivePromote
                    OobeFeatureCard(
                        item = back3,
                        isDark = isDark,
                        animateArt = false,
                        modifier = cardSlotModifier
                            .zIndex(0f)
                            .graphicsLayer {
                                translationX = with(density) { tx2Base.dp.toPx() }
                                translationY = with(density) { ty2Base.dp.toPx() }
                                rotationZ = rot2Base
                                scaleX = scale2Base
                                scaleY = scale2Base
                                alpha = alpha3
                                shadowElevation = with(density) { 4.dp.toPx() }
                                shape = RoundedCornerShape(cardRadius)
                                clip = true
                            }
                            .then(if (advancedMaterial) Modifier.blur(blur2Base.dp) else Modifier),
                    )
                }

                // 3rd card (stronger blur -> smoothly unblurs to 2nd-card pose during promotion)
                if (items.size >= 3) {
                    val back2 = items[offsetIndex(2)]
                    val rot2 = oobeLerpFloat(rot2Base, rot1Base, effectivePromote)
                    val tx2 = with(density) { oobeLerpFloat(tx2Base, tx1Base, effectivePromote).dp.toPx() }
                    val ty2 = with(density) { oobeLerpFloat(ty2Base, ty1Base, effectivePromote).dp.toPx() }
                    val scale2 = oobeLerpFloat(scale2Base, scale1Base, effectivePromote)
                    val alpha2 = oobeLerpFloat(alpha2Base, alpha1Base, effectivePromote)
                    val blur2 = oobeLerpFloat(blur2Base, blur1Base, effectivePromote).dp
                    OobeFeatureCard(
                        item = back2,
                        isDark = isDark,
                        animateArt = false,
                        modifier = cardSlotModifier
                            .zIndex(1f)
                            .graphicsLayer {
                                translationX = tx2
                                translationY = ty2
                                rotationZ = rot2
                                scaleX = scale2
                                scaleY = scale2
                                alpha = alpha2
                                shadowElevation = with(density) { oobeLerpFloat(5f, 8f, effectivePromote).dp.toPx() }
                                shape = RoundedCornerShape(cardRadius)
                                clip = true
                            }
                            .then(if (advancedMaterial && blur2 > 0.1.dp) Modifier.blur(blur2) else Modifier),
                    )
                }

                // 2nd card (lighter blur -> smoothly unblurs to 0dp as it promotes to front)
                if (items.size >= 2) {
                    val back1 = items[offsetIndex(1)]
                    val rot1 = oobeLerpFloat(rot1Base, 0f, effectivePromote)
                    val tx1 = with(density) { oobeLerpFloat(tx1Base, 0f, effectivePromote).dp.toPx() }
                    val ty1 = with(density) { oobeLerpFloat(ty1Base, 0f, effectivePromote).dp.toPx() }
                    val scale1 = oobeLerpFloat(scale1Base, 1f, effectivePromote)
                    val alpha1 = oobeLerpFloat(alpha1Base, 1f, effectivePromote)
                    val blur1 = oobeLerpFloat(blur1Base, 0f, effectivePromote).dp
                    OobeFeatureCard(
                        item = back1,
                        isDark = isDark,
                        animateArt = false,
                        modifier = cardSlotModifier
                            .zIndex(2f)
                            .graphicsLayer {
                                translationX = tx1
                                translationY = ty1
                                rotationZ = rot1
                                scaleX = scale1
                                scaleY = scale1
                                alpha = alpha1
                                shadowElevation = with(density) { oobeLerpFloat(8f, 12f, effectivePromote).dp.toPx() }
                                shape = RoundedCornerShape(cardRadius)
                                clip = true
                            }
                            .then(if (advancedMaterial && blur1 > 0.1.dp) Modifier.blur(blur1) else Modifier),
                    )
                }

                // Front card (#1)
                val dragRatio = (drag.value / cardWidthPx).coerceIn(-1f, 1f)
                OobeFeatureCard(
                    item = current,
                    isDark = isDark,
                    animateArt = !isDragging && !isAnimatingStep,
                    modifier = cardSlotModifier
                        .zIndex(3f)
                        .graphicsLayer {
                            translationX = drag.value
                            translationY = with(density) { (-7.dp * dragFan).toPx() }
                            rotationZ = dragRatio * 5.5f
                            val activeScale = 1f + 0.012f * dragFan
                            scaleX = activeScale
                            scaleY = activeScale
                            alpha = 1f - abs(dragRatio) * 0.14f
                            shadowElevation = with(density) { (10.dp + 6.dp * dragFan).toPx() }
                            shape = RoundedCornerShape(cardRadius)
                            clip = true
                        }
                        .pointerInput(items) {
                            detectTapGestures { offset ->
                                if (!isDragging && !isAnimatingStep) {
                                    advanceCard(if (offset.x < size.width * 0.5f) -1 else 1)
                                }
                            }
                        },
                )
            }
        }

        // Interactive capsule dot indicator
        val activeDotColor = items[safeIndex].accent
        val inactiveDotColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.20f)
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            items.indices.forEach { dotIndex ->
                val active = dotIndex == safeIndex
                val dotWidth by animateDpAsState(
                    targetValue = if (active) 22.dp else 7.dp,
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                    label = "oobe_feature_dot_width",
                )
                Box(
                    Modifier
                        .height(7.dp)
                        .width(dotWidth)
                        .clip(CircleShape)
                        .background(if (active) activeDotColor else inactiveDotColor)
                        .clickable {
                            if (!isAnimatingStep && dotIndex != safeIndex) {
                                index = dotIndex
                            }
                        },
                )
            }
        }

        Text(
            text = tr("oobe_features_swipe_hint", "左右滑动或轻点卡片浏览全部功能"),
            modifier = Modifier.padding(top = 14.dp),
            style = MiuixTheme.textStyles.body2,
            fontSize = 12.5.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.78f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun OobeFeatureCard(
    item: OobeFeatureItem,
    isDark: Boolean,
    animateArt: Boolean,
    modifier: Modifier = Modifier,
) {
    val cardShape = RoundedCornerShape(28.dp)
    val cardSurface = if (isDark) Color(0xFF1A1A1D) else Color.White
    val borderBrush = Brush.verticalGradient(
        colors = if (isDark) {
            listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.05f))
        } else {
            listOf(Color.White.copy(alpha = 0.92f), Color.White.copy(alpha = 0.35f))
        },
    )

    Column(
        modifier = modifier
            .clip(cardShape)
            .background(cardSurface)
            .border(BorderStroke(1.dp, borderBrush), cardShape)
            .padding(horizontal = 22.dp, vertical = 20.dp),
    ) {
        // Apple-style animated vector canvas stage
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            OobeFeatureAnimatedSvg(
                artwork = item.artwork,
                isDark = isDark,
                animate = animateArt,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Spacer(Modifier.height(10.dp))

        // Feature category capsule badge
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(item.accent.copy(alpha = if (isDark) 0.22f else 0.12f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = item.badge,
                color = item.accent,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = item.title,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = item.description,
            style = MiuixTheme.textStyles.body2,
            fontSize = 14.sp,
            lineHeight = 20.5.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun oobeLerpFloat(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction

private val OobeCheckIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "OobeCheck",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.4f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(5.2f, 12.6f)
            lineTo(9.8f, 17.2f)
            lineTo(19.0f, 7.6f)
        }
    }.build()
}

@Composable
private fun OobeCompletePage(
    confettiKey: Int,
    back: () -> Unit,
    finish: () -> Unit,
) {
    val brandAccent = MiuixTheme.colorScheme.primary
    Box(Modifier.fillMaxSize()) {
        AppPage(
            title = "",
            onBack = back,
            navigationIcon = { OobeBackButton(back) },
            compactTopBar = true,
            floatingToolbar = {
                Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                    OobeButton(tr("oobe_start", "开始使用"), true, finish)
                }
            },
        ) { padding, _ ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 28.dp)) {
                Spacer(Modifier.weight(.32f))
                Text(tr("oobe_complete_title", "设置完成！"), fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(54.dp))
                Text(tr("oobe_welcome", "欢迎使用"), fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Text("Aura Player", color = brandAccent, fontSize = 39.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(.68f))
            }
        }
        CompletionConfetti(confettiKey, Modifier.fillMaxSize())
    }
}

@Composable
private fun OobeSectionTitle(title: String) {
    Text(
        title,
        modifier = Modifier.fillMaxWidth().padding(top = 42.dp, bottom = 36.dp),
        textAlign = TextAlign.Center,
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun oobeListPadding(padding: PaddingValues, bottom: Int) = PaddingValues(
    start = LocalAuraLayout.current.pageMargin,
    top = padding.calculateTopPadding() + 4.dp,
    end = LocalAuraLayout.current.pageMargin,
    bottom = padding.calculateBottomPadding() + bottom.dp,
)

@Composable
private fun OobeBackButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            MiuixIcons.Regular.ChevronBackward,
            tr("返回", "返回"),
            Modifier.size(27.dp),
            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface),
        )
    }
}

@Composable
private fun OobeButton(
    text: String,
    enabled: Boolean,
    click: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) = UpdateToolbarButton(
    onClick = click,
    text = text,
    modifier = modifier.height(58.dp),
    primary = true,
    enabled = enabled,
    surfaceColor = MiuixTheme.colorScheme.primary.copy(alpha = .92f),
    primaryContentColor = MiuixTheme.colorScheme.onPrimary,
)
