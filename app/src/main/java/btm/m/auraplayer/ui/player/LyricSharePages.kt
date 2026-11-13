// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected as selectedSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.R
import btm.m.auraplayer.lyrics.LyricLine
import btm.m.auraplayer.lyrics.LyricSize
import btm.m.auraplayer.ui.*
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.materialkolor.palettes.CorePalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.ToolbarPosition
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

internal class LyricShareTransitionState {
    var active by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var sourceRowBounds by mutableStateOf<Rect?>(null)
    var sourceViewportBounds by mutableStateOf<Rect?>(null)
    var targetCardBounds by mutableStateOf<Rect?>(null)
    var targetRowBounds by mutableStateOf<Rect?>(null)
    var targetReady by mutableStateOf(false)
}

internal val LocalLyricShareTransition = compositionLocalOf<LyricShareTransitionState?> { null }

internal fun lyricShareSmoothStep(start: Float, end: Float, value: Float): Float {
    val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

@Composable
internal fun LyricShareRoute(model: AuraViewModel, onBack: () -> Unit, isActive: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transition = LocalLyricShareTransition.current
    val lines = model.lyrics
    var selected by rememberSaveable {
        mutableStateOf(model.lyricShareInitialIndex.takeIf { it in lines.indices }?.let { setOf(it) } ?: emptySet())
    }
    val sharePrefs = remember(context) { LyricSharePreferences(context) }
    val initialOptions = remember(sharePrefs) {
        sharePrefs.load(
            defaultShowTranslation = model.showTranslation,
            defaultShowPronunciation = model.showPronunciation,
            defaultLyricSize = model.lyricSize,
        )
    }
    var generated by rememberSaveable { mutableStateOf(false) }
    var templateId by rememberSaveable { mutableStateOf(LyricShareTemplateId.AURA_MESH) }
    var showTranslation by rememberSaveable { mutableStateOf(initialOptions.showTranslation) }
    var showPronunciation by rememberSaveable { mutableStateOf(initialOptions.showPronunciation) }
    var showSongInfo by rememberSaveable { mutableStateOf(initialOptions.showSongInfo) }
    var showBrand by rememberSaveable { mutableStateOf(initialOptions.showBrand) }
    var showRoundedCorners by rememberSaveable { mutableStateOf(initialOptions.showRoundedCorners) }
    var paperDarkMode by rememberSaveable { mutableStateOf(initialOptions.paperDarkMode) }
    var lyricSize by rememberSaveable { mutableStateOf(initialOptions.lyricSize) }
    var lineSpacing by rememberSaveable { mutableStateOf(initialOptions.lineSpacing) }
    var lyricFont by rememberSaveable { mutableStateOf(initialOptions.lyricFont) }
    var selectedSavedFontName by rememberSaveable { mutableStateOf(initialOptions.selectedSavedFontName) }
    var scannedFonts by remember { mutableStateOf<List<SavedShareFont>>(emptyList()) }
    var imageQuality by rememberSaveable { mutableStateOf(initialOptions.imageQuality) }
    var lyricFilter by rememberSaveable { mutableStateOf(initialOptions.lyricFilter) }
    var customFontFamily by remember { mutableStateOf<FontFamily?>(null) }
    var customFontName by rememberSaveable { mutableStateOf(initialOptions.customFontName) }
    var customFontTouched by remember { mutableStateOf(false) }
    var customFontDialogOpen by remember { mutableStateOf(false) }
    var draftFontFamily by remember { mutableStateOf<FontFamily?>(null) }
    var draftFontName by remember { mutableStateOf<String?>(null) }
    var draftFontFile by remember { mutableStateOf<File?>(null) }
    var pickingFont by remember { mutableStateOf(false) }
    var customBgBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var customBgName by rememberSaveable { mutableStateOf(initialOptions.customBgName) }
    var customBgTouched by remember { mutableStateOf(false) }
    var customBgBlur by rememberSaveable { mutableFloatStateOf(initialOptions.customBgBlur) }
    var customBgDialogOpen by remember { mutableStateOf(false) }
    var draftBgBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var draftBgName by remember { mutableStateOf<String?>(null) }
    var pickingBg by remember { mutableStateOf(false) }
    var exportDialogOpen by remember { mutableStateOf(false) }
    var exportPreviewBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf(false) }

    val pickCustomBg = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || pickingBg) return@rememberLauncherForActivityResult
        scope.launch {
            pickingBg = true
            try {
                val loaded = loadCustomBackgroundImage(context, uri)
                if (loaded != null) {
                    draftBgBitmap = loaded.first
                    draftBgName = loaded.second ?: tr("share_custom_background", "自定义背景")
                } else {
                    Toast.makeText(context, tr("lyric_share_save_failed", "保存失败，请重试"), Toast.LENGTH_SHORT).show()
                }
            } finally {
                pickingBg = false
            }
        }
    }
    val pickCustomFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || pickingFont) return@rememberLauncherForActivityResult
        scope.launch {
            pickingFont = true
            try {
                val loaded = loadCustomFontFamily(context, uri)
                if (loaded != null) {
                    draftFontFamily = loaded.fontFamily
                    draftFontName = loaded.fileName
                    draftFontFile = loaded.cachedFile
                } else {
                    Toast.makeText(context, tr("share_invalid_font", "无法读取该字体文件，请选择有效的 TTF/OTF 字体"), Toast.LENGTH_SHORT).show()
                }
            } finally {
                pickingFont = false
            }
        }
    }

    LaunchedEffect(context, sharePrefs) {
        scannedFonts = scanDownloadFonts(context)
        if (!customFontTouched && customFontFamily == null) {
            sharePrefs.loadCustomFont()?.let { (family, name) ->
                if (!customFontTouched) {
                    customFontFamily = family
                    customFontName = name
                }
            }
        }
        if (!customBgTouched && customBgBitmap == null) {
            sharePrefs.loadCustomBackground()?.let { (bitmap, name) ->
                if (!customBgTouched) {
                    customBgBitmap = bitmap
                    customBgName = name
                }
            }
        }
    }

    val song = model.current
    var didInitialCenter by rememberSaveable(song?.key, model.lyricShareInitialIndex) { mutableStateOf(false) }
    val lyricListState = rememberLazyListState()
    val artwork = song?.artwork.orEmpty()
    val coverBitmap by produceState<ImageBitmap?>(null, artwork, context) {
        value = if (artwork.isBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                val result = SingletonImageLoader.get(context).execute(
                    ImageRequest.Builder(context).data(artwork).size(960).allowHardware(false).build()
                )
                (result as? SuccessResult)?.image?.toBitmap()?.asImageBitmap()
            }.getOrNull()
        }
    }
    val coverPalette = rememberPlayerMonetPalette(artwork)
    val posterLayer = rememberGraphicsLayer()

    val modalDialogOpen = customBgDialogOpen || customFontDialogOpen || exportDialogOpen
    PredictiveBackHandler(
        enabled = isActive && generated && !modalDialogOpen && model.settings.value.predictive,
    ) { events ->
        try {
            events.collect { }
            generated = false
        } catch (_: CancellationException) { }
    }
    BackHandler(enabled = isActive && generated && !modalDialogOpen && !model.settings.value.predictive) {
        generated = false
    }

    fun exportCapturedPoster(shareAfterSave: Boolean) {
        val captured = exportPreviewBitmap ?: return
        if (busy) return
        scope.launch {
            busy = true
            try {
                val bitmap = withContext(Dispatchers.Default) {
                    scaleBitmapForQuality(captured.asAndroidBitmap(), imageQuality)
                }
                if (shareAfterSave) {
                    val result = runCatching { shareLyricBitmap(context, bitmap) }
                    if (result.isFailure) {
                        Toast.makeText(context, tr("lyric_share_failed", "无法打开分享面板，请尝试导出图片"), Toast.LENGTH_LONG).show()
                    } else {
                        exportDialogOpen = false
                    }
                } else {
                    val ok = saveLyricBitmapToGallery(context, bitmap, song?.title.orEmpty())
                    val message = if (ok) tr("lyric_share_saved", "已保存到相册 Pictures/Aura Player")
                    else tr("lyric_share_save_failed", "保存失败，请重试")
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    if (ok) exportDialogOpen = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(
                    context,
                    if (shareAfterSave) tr("lyric_share_failed", "无法打开分享面板，请尝试导出图片")
                    else tr("lyric_share_save_failed", "保存失败，请重试"),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                busy = false
            }
        }
    }

    val templates = remember(I18n.language) { lyricShareTemplates() }
    val activeTemplate = templates.firstOrNull { it.id == templateId } ?: templates.first()
    val enterActive = transition?.active == true
    val enterProgress = if (enterActive) transition.progress.coerceIn(0f, 1f) else 1f
    val chromeEnterAlpha = if (enterActive) lyricShareSmoothStep(.16f, .92f, enterProgress) else 1f

    AppPage(
        title = if (generated) tr("share_lyrics", "分享歌词") else tr("select_lyrics_to_share", "选择歌词"),
        onBack = { if (generated) generated = false else onBack() },
        actions = {
            if (generated) {
                GlassToolbarIconButton(MiuixIcons.Regular.Remove, tr("export", "导出")) {
                    if (busy || posterLayer.size.width <= 0 || posterLayer.size.height <= 0) return@GlassToolbarIconButton
                    scope.launch {
                        busy = true
                        try {
                            exportPreviewBitmap = posterLayer.toImageBitmap()
                            exportDialogOpen = true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            Toast.makeText(context, tr("lyric_share_save_failed", "保存失败，请重试"), Toast.LENGTH_LONG).show()
                        } finally {
                            busy = false
                        }
                    }
                }
            }
        },
        floatingToolbarPosition = ToolbarPosition.BottomCenter,
        floatingToolbar = {
            Box(
                Modifier.fillMaxWidth().padding(end = 20.dp, bottom = 12.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                AnimatedVisibility(!generated && selected.isNotEmpty(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                    InteractiveToolbarGlass(
                        modifier = Modifier.size(60.dp).graphicsLayer {
                            if (enterActive) {
                                alpha = lyricShareSmoothStep(.22f, 1f, enterProgress)
                                val scale = .86f + .14f * enterProgress
                                scaleX = scale
                                scaleY = scale
                            }
                        },
                        backdrop = LocalToolbarBackdrop.current,
                        tint = MiuixTheme.colorScheme.primary.copy(alpha = .85f),
                        onClick = { generated = true },
                    ) {
                        Image(
                            MiuixIcons.Basic.Check,
                            tr("generate_share_image", "生成分享图片"),
                            Modifier.size(24.dp),
                            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onPrimary),
                        )
                    }
                }
            }
        },
    ) { padding, _ ->
        val pagePadding = PaddingValues(
            start = LocalAuraLayout.current.pageMargin,
            end = LocalAuraLayout.current.pageMargin,
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + if (generated) 28.dp else 92.dp,
        )
        AnimatedContent(
            targetState = generated,
            transitionSpec = {
                (fadeIn(tween(220, easing = FastOutSlowInEasing)) + scaleIn(tween(220), initialScale = .98f))
                    .togetherWith(fadeOut(tween(170)))
            },
            modifier = Modifier.fillMaxSize(),
            label = "lyricShareSteps",
        ) { isStudio ->
            if (!isStudio) {
                SelectLyricsStep(
                    lines = lines,
                    selected = selected,
                    initialIndex = model.lyricShareInitialIndex,
                    listState = lyricListState,
                    didInitialCenter = didInitialCenter,
                    onInitialCentered = { didInitialCenter = true },
                    transition = transition,
                    enterProgress = enterProgress,
                    chromeEnterAlpha = chromeEnterAlpha,
                    songTitle = song?.title.orEmpty(),
                    songArtist = song?.artist.orEmpty(),
                    coverBitmap = coverBitmap,
                    showTranslation = showTranslation,
                    showPronunciation = showPronunciation,
                    padding = pagePadding,
                    onToggle = { index -> selected = toggleLyricShareIndex(selected, index) },
                )
            } else {
                val selectedLines = selected.sorted().mapNotNull { lines.getOrNull(it) }
                SharePosterStep(
                    title = song?.title.orEmpty(),
                    artist = song?.artist.orEmpty(),
                    coverBitmap = coverBitmap,
                    coverPalette = coverPalette,
                    lines = selectedLines,
                    templates = templates,
                    activeTemplate = activeTemplate,
                    showTranslation = showTranslation,
                    showPronunciation = showPronunciation,
                    showSongInfo = showSongInfo,
                    showBrand = showBrand,
                    showRoundedCorners = showRoundedCorners,
                    paperDarkMode = paperDarkMode,
                    lyricSize = lyricSize,
                    lineSpacing = lineSpacing,
                    lyricFont = lyricFont,
                    selectedSavedFontName = selectedSavedFontName,
                    scannedFonts = scannedFonts,
                    customFontFamily = customFontFamily,
                    customFontName = customFontName,
                    imageQuality = imageQuality,
                    lyricFilter = lyricFilter,
                    customBackground = customBgBitmap,
                    customBackgroundName = customBgName,
                    customBackgroundBlur = customBgBlur,
                    posterLayer = posterLayer,
                    padding = pagePadding,
                    onTemplateSelected = { templateId = it },
                    onTranslationChange = { showTranslation = it; sharePrefs.setShowTranslation(it) },
                    onPronunciationChange = { showPronunciation = it; sharePrefs.setShowPronunciation(it) },
                    onSongInfoChange = { showSongInfo = it; sharePrefs.setShowSongInfo(it) },
                    onBrandChange = { showBrand = it; sharePrefs.setShowBrand(it) },
                    onRoundedCornersChange = { showRoundedCorners = it; sharePrefs.setShowRoundedCorners(it) },
                    onPaperDarkModeChange = { paperDarkMode = it; sharePrefs.setPaperDarkMode(it) },
                    onSizeChange = { lyricSize = it; sharePrefs.setLyricSize(it) },
                    onLineSpacingChange = { lineSpacing = it; sharePrefs.setLineSpacing(it) },
                    onFontChange = { lyricFont = it; sharePrefs.setFontSelection(it, selectedSavedFontName) },
                    onSavedFontSelected = { selectedSavedFontName = it; sharePrefs.setFontSelection(lyricFont, it) },
                    onImageQualityChange = { imageQuality = it; sharePrefs.setImageQuality(it) },
                    onFilterChange = { lyricFilter = it; sharePrefs.setLyricFilter(it) },
                    onOpenCustomFont = {
                        draftFontFamily = customFontFamily
                        draftFontName = customFontName
                        draftFontFile = null
                        customFontDialogOpen = true
                    },
                    onCustomBgBlurChange = { customBgBlur = it; sharePrefs.setCustomBgBlur(it) },
                    onOpenCustomBg = {
                        draftBgBitmap = customBgBitmap
                        draftBgName = customBgName
                        customBgDialogOpen = true
                    },
                )
            }
        }
    }

    WindowDialog(
        show = customFontDialogOpen,
        onDismissRequest = { if (!pickingFont) customFontDialogOpen = false },
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                val previewFamily = draftFontFamily
                if (previewFamily != null) {
                    Text(
                        text = song?.title?.ifBlank { "Aura Player" } ?: "Aura Player",
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = previewFamily,
                        textAlign = TextAlign.Start,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = tr("share_no_font_imported", "未导入字体"),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Start,
                    )
                }
            }
            Text(
                text = draftFontName ?: tr("share_tap_select_file", "点击下方的按钮选择文件"),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = tr(
                    "share_custom_font_folder_hint",
                    "支持自动读取 Download/Aura Player/Fonts 目录下的字体并添加到字体菜单，使用过的自定义字体也会自动复制到该目录",
                ),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .85f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton(
                    onClick = { pickCustomFont.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f),
                    enabled = !pickingFont,
                ) {
                    Text(tr("select_file", "选择文件"))
                }
                GlassDialogButton(
                    onClick = {
                        draftFontFamily = null
                        draftFontName = null
                        draftFontFile = null
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !pickingFont && draftFontFamily != null,
                ) {
                    Text(tr("share_clear_image", "清除"))
                }
            }
            GlassDialogButton(
                onClick = {
                    val selectedFile = draftFontFile
                    val selectedName = draftFontName
                    val selectedFamily = draftFontFamily
                    customFontTouched = true
                    customFontFamily = selectedFamily
                    customFontName = selectedName
                    customFontDialogOpen = false
                    scope.launch {
                        if (selectedFile != null && selectedName != null) {
                            sharePrefs.saveCustomFont(selectedFile, selectedName)
                            copyFontToDownloadsFolder(context, selectedFile, selectedName)
                            scannedFonts = scanDownloadFonts(context)
                        } else if (selectedFamily == null) {
                            sharePrefs.clearCustomFont()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !pickingFont,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(tr("save", "保存"))
            }
            GlassDialogButton(
                onClick = { customFontDialogOpen = false },
                modifier = Modifier.fillMaxWidth(),
                enabled = !pickingFont,
            ) {
                Text(tr("cancel", "取消"))
            }
        }
    }

    WindowDialog(
        show = customBgDialogOpen,
        onDismissRequest = { if (!pickingBg) customBgDialogOpen = false },
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val preview = draftBgBitmap
            val previewRatio = if (preview != null && preview.height > 0) {
                (preview.width.toFloat() / preview.height.toFloat()).coerceIn(.6f, 1.5f)
            } else 1f
            Box(
                Modifier.widthIn(max = 180.dp)
                    .fillMaxWidth()
                    .aspectRatio(previewRatio)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        text = tr("share_no_image_imported", "未导入图片"),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            Text(
                text = draftBgName ?: tr("share_tap_select_file", "点击下方的按钮选择文件"),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton(
                    onClick = { pickCustomBg.launch(arrayOf("image/*")) },
                    modifier = Modifier.weight(1f),
                    enabled = !pickingBg,
                ) {
                    Text(tr("select_file", "选择文件"))
                }
                GlassDialogButton(
                    onClick = {
                        draftBgBitmap = null
                        draftBgName = null
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !pickingBg && draftBgBitmap != null,
                ) {
                    Text(tr("share_clear_image", "清除"))
                }
            }
            GlassDialogButton(
                onClick = {
                    val nextBitmap = draftBgBitmap
                    val nextName = draftBgName
                    customBgTouched = true
                    customBgBitmap = nextBitmap
                    customBgName = nextName
                    customBgDialogOpen = false
                    scope.launch {
                        if (nextBitmap != null && nextName != null) {
                            sharePrefs.saveCustomBackground(nextBitmap, nextName)
                        } else if (nextBitmap == null) {
                            sharePrefs.clearCustomBackground()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !pickingBg,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(tr("save", "保存"))
            }
            GlassDialogButton(
                onClick = { customBgDialogOpen = false },
                modifier = Modifier.fillMaxWidth(),
                enabled = !pickingBg,
            ) {
                Text(tr("cancel", "取消"))
            }
        }
    }

    WindowDialog(
        show = exportDialogOpen,
        onDismissRequest = { if (!busy) exportDialogOpen = false },
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            exportPreviewBitmap?.let { preview ->
                val ratio = if (preview.height > 0) (preview.width.toFloat() / preview.height.toFloat()).coerceAtLeast(.2f) else .75f
                val targetWidth = min(180f, 260f * ratio)
                val targetHeight = targetWidth / ratio
                Box(
                    Modifier.size(targetWidth.dp, targetHeight.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = preview,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton(
                    onClick = { exportCapturedPoster(shareAfterSave = false) },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                ) {
                    Text(tr("export", "导出"))
                }
                GlassDialogButton(
                    onClick = { exportCapturedPoster(shareAfterSave = true) },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(tr("share", "分享"))
                }
            }
            GlassDialogButton(
                onClick = { exportDialogOpen = false },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Text(tr("cancel", "取消"))
            }
        }
    }
}

@Composable
private fun SelectLyricsStep(
    lines: List<LyricLine>,
    selected: Set<Int>,
    initialIndex: Int,
    listState: LazyListState,
    didInitialCenter: Boolean,
    onInitialCentered: () -> Unit,
    transition: LyricShareTransitionState?,
    enterProgress: Float,
    chromeEnterAlpha: Float,
    songTitle: String,
    songArtist: String,
    coverBitmap: ImageBitmap?,
    showTranslation: Boolean,
    showPronunciation: Boolean,
    padding: PaddingValues,
    onToggle: (Int) -> Unit,
) {
    LaunchedEffect(initialIndex, lines.size) {
        if (!didInitialCenter && initialIndex in lines.indices) {
            snapshotFlow { listState.layoutInfo }
                .first { it.viewportSize.height > 0 && it.totalItemsCount > initialIndex }
            listState.scrollToItem(initialIndex)
            val infoAfter = snapshotFlow { listState.layoutInfo }
                .first { info ->
                    info.viewportSize.height > 0 && info.visibleItemsInfo.any { it.index == initialIndex }
                }
            val itemInfo = infoAfter.visibleItemsInfo.firstOrNull { it.index == initialIndex }
            if (itemInfo != null) {
                val viewportCenter = (infoAfter.viewportStartOffset + infoAfter.viewportEndOffset) / 2f
                val itemCenter = itemInfo.offset + itemInfo.size / 2f
                val delta = itemCenter - viewportCenter
                if (abs(delta) > 1f) {
                    listState.scrollBy(delta)
                }
            }
            onInitialCentered()
        } else if (!didInitialCenter) {
            onInitialCentered()
        }
        if (transition != null && transition.active && !transition.targetReady) {
            withFrameNanos { }
            transition.targetReady = true
        }
    }

    val scrollBehavior = LocalPageScroll.current
    val density = LocalDensity.current
    val enterActive = transition?.active == true
    val hasSongHeader = songTitle.isNotBlank() || songArtist.isNotBlank()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val availableHeight = (maxHeight - padding.calculateTopPadding() - padding.calculateBottomPadding()).coerceAtLeast(220.dp)
        val cardMaxHeight = (availableHeight - if (hasSongHeader) 124.dp else 36.dp).coerceIn(220.dp, 540.dp)
        val outerScroll = rememberScrollState()
        Column(
            Modifier.fillMaxSize()
                .then(if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier)
                .verticalScroll(outerScroll)
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (hasSongHeader) {
                SeamlessCard(
                    Modifier.fillMaxWidth().graphicsLayer {
                        if (enterActive) {
                            alpha = chromeEnterAlpha
                            val scale = .96f + .04f * enterProgress
                            scaleX = scale
                            scaleY = scale
                            translationY = (1f - enterProgress) * with(density) { 12.dp.toPx() }
                        }
                    },
                    cornerRadius = 22.5.dp,
                    insideMargin = PaddingValues(14.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(MiuixTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (coverBitmap != null) {
                                Image(coverBitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            } else {
                                Image(MiuixIcons.Regular.Music, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                songTitle,
                                style = MiuixTheme.textStyles.body1,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (songArtist.isNotBlank()) {
                                Text(
                                    songArtist,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.body2,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier.clip(CircleShape)
                                .background(MiuixTheme.colorScheme.primary.copy(alpha = .14f))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(
                                "${selected.size}/$MAX_LYRIC_SHARE_LINES",
                                color = MiuixTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            if (lines.isEmpty()) {
                SeamlessCard(
                    Modifier.fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            if (transition?.active == true) transition.targetCardBounds = coords.boundsInWindow()
                        }
                        .graphicsLayer {
                            if (enterActive) {
                                alpha = chromeEnterAlpha
                                val scale = .96f + .04f * enterProgress
                                scaleX = scale
                                scaleY = scale
                            }
                        },
                    cornerRadius = 22.5.dp,
                    insideMargin = PaddingValues(16.dp),
                ) {
                    EmptyState(tr("lyrics_empty", "暂无歌词"))
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    SmallTitle(
                        tr("select_lyrics_limit_hint", "最多选择 16 句歌词"),
                        modifier = Modifier.graphicsLayer { if (enterActive) alpha = chromeEnterAlpha },
                        insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp),
                    )
                    SeamlessCard(
                        Modifier.fillMaxWidth()
                            .onGloballyPositioned { coords ->
                                if (transition?.active == true) transition.targetCardBounds = coords.boundsInWindow()
                            }
                            .graphicsLayer {
                                if (transition != null && transition.active) {
                                    val p = enterProgress
                                    val cardBounds = transition.targetCardBounds
                                    val dstCenter = transition.targetRowBounds?.center ?: cardBounds?.center
                                    val srcCenter = transition.sourceRowBounds?.center ?: transition.sourceViewportBounds?.center
                                    if (cardBounds != null && dstCenter != null && srcCenter != null &&
                                        cardBounds.width > 0f && cardBounds.height > 0f
                                    ) {
                                        val srcH = transition.sourceRowBounds?.height?.takeIf { it > 0f }
                                        val dstH = transition.targetRowBounds?.height?.takeIf { it > 0f }
                                        val startScale = if (srcH != null && dstH != null) {
                                            (srcH / dstH).coerceIn(1.15f, 1.85f)
                                        } else 1.40f
                                        val scale = startScale + (1f - startScale) * p
                                        transformOrigin = TransformOrigin(
                                            ((dstCenter.x - cardBounds.left) / cardBounds.width).coerceIn(0f, 1f),
                                            ((dstCenter.y - cardBounds.top) / cardBounds.height).coerceIn(0f, 1f),
                                        )
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = (srcCenter.x - dstCenter.x) * (1f - p)
                                        translationY = (srcCenter.y - dstCenter.y) * (1f - p)
                                    }
                                    alpha = lyricShareSmoothStep(.10f, .72f, p)
                                }
                            },
                        cornerRadius = 22.5.dp,
                        insideMargin = PaddingValues(0.dp),
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(max = cardMaxHeight)
                                .clip(RoundedCornerShape(22.5.dp)),
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                                val checked = index in selected
                                val highlight by animateColorAsState(
                                    if (checked) MiuixTheme.colorScheme.primary.copy(alpha = .14f) else Color.Transparent,
                                    tween(200),
                                    label = "lyricRowBg",
                                )
                                val checkScale by animateFloatAsState(if (checked) 1f else .85f, tween(200), label = "lyricRowCheck")
                                val rowTracker = if (index == initialIndex) {
                                    Modifier.onGloballyPositioned { coords ->
                                        if (transition?.active == true) transition.targetRowBounds = coords.boundsInWindow()
                                    }
                                } else Modifier
                                Row(
                                    rowTracker.fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(highlight)
                                        .semantics { selectedSemantics = checked }
                                        .clickable(enabled = checked || selected.size < MAX_LYRIC_SHARE_LINES, role = Role.Checkbox) {
                                            onToggle(index)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(
                                            line.text,
                                            color = if (checked) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                                            fontSize = 16.sp,
                                            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                                        )
                                        if (showPronunciation && !line.roma.isNullOrBlank()) {
                                            Text(
                                                line.roma.orEmpty(),
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                fontSize = 12.5.sp,
                                            )
                                        }
                                        if (showTranslation && !line.translation.isNullOrBlank()) {
                                            Text(
                                                line.translation.orEmpty(),
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                fontSize = 13.sp,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Box(
                                        Modifier.size(22.dp)
                                            .clip(CircleShape)
                                            .then(
                                                if (checked) Modifier.background(MiuixTheme.colorScheme.primary)
                                                else Modifier.border(1.5.dp, MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .35f), CircleShape)
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (checked) {
                                            Image(
                                                MiuixIcons.Basic.Check,
                                                null,
                                                Modifier.size((14 * checkScale).dp),
                                                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onPrimary),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SharePosterStep(
    title: String,
    artist: String,
    coverBitmap: ImageBitmap?,
    coverPalette: CorePalette,
    lines: List<LyricLine>,
    templates: List<LyricShareTemplate>,
    activeTemplate: LyricShareTemplate,
    showTranslation: Boolean,
    showPronunciation: Boolean,
    showSongInfo: Boolean,
    showBrand: Boolean,
    showRoundedCorners: Boolean,
    paperDarkMode: Boolean,
    lyricSize: LyricSize,
    lineSpacing: LyricShareLineSpacing,
    lyricFont: LyricShareFont,
    selectedSavedFontName: String?,
    scannedFonts: List<SavedShareFont>,
    customFontFamily: FontFamily?,
    customFontName: String?,
    imageQuality: LyricShareImageQuality,
    lyricFilter: LyricShareFilter,
    customBackground: ImageBitmap?,
    customBackgroundName: String?,
    customBackgroundBlur: Float,
    posterLayer: GraphicsLayer,
    padding: PaddingValues,
    onTemplateSelected: (LyricShareTemplateId) -> Unit,
    onTranslationChange: (Boolean) -> Unit,
    onPronunciationChange: (Boolean) -> Unit,
    onSongInfoChange: (Boolean) -> Unit,
    onBrandChange: (Boolean) -> Unit,
    onRoundedCornersChange: (Boolean) -> Unit,
    onPaperDarkModeChange: (Boolean) -> Unit,
    onSizeChange: (LyricSize) -> Unit,
    onLineSpacingChange: (LyricShareLineSpacing) -> Unit,
    onFontChange: (LyricShareFont) -> Unit,
    onSavedFontSelected: (String?) -> Unit,
    onImageQualityChange: (LyricShareImageQuality) -> Unit,
    onFilterChange: (LyricShareFilter) -> Unit,
    onOpenCustomFont: () -> Unit,
    onCustomBgBlurChange: (Float) -> Unit,
    onOpenCustomBg: () -> Unit,
) {
    val sizes = LyricSize.entries
    val spacings = LyricShareLineSpacing.entries
    val builtInFonts = listOf(LyricShareFont.SYSTEM_DEFAULT, LyricShareFont.SERIF, LyricShareFont.ROUNDED)
    val fontMenuItems = builtInFonts.map { lyricShareFontLabel(it) } +
        scannedFonts.map { it.displayName } +
        tr("share_font_custom", "自定义字体")
    val selectedFontIndex = when (lyricFont) {
        LyricShareFont.PRESET_FOLDER -> {
            val matchIdx = scannedFonts.indexOfFirst { it.fileName == selectedSavedFontName }
            if (matchIdx >= 0) builtInFonts.size + matchIdx else 0
        }
        LyricShareFont.CUSTOM -> builtInFonts.size + scannedFonts.size
        else -> builtInFonts.indexOf(lyricFont).coerceAtLeast(0)
    }
    val activeSavedFontFamily = scannedFonts.firstOrNull { it.fileName == selectedSavedFontName }?.fontFamily
    val qualities = LyricShareImageQuality.entries
    val filters = LyricShareFilter.entries
    val previewBackdrop = rememberLayerBackdrop { drawContent() }
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "posterPreview") {
            SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(12.dp)) {
                Box(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier.fillMaxWidth()
                            .layerBackdrop(previewBackdrop)
                            .drawWithContent {
                                posterLayer.record { this@drawWithContent.drawContent() }
                                drawLayer(posterLayer)
                            }
                    ) {
                        SharePosterCard(
                            template = activeTemplate,
                            title = title,
                            artist = artist,
                            coverBitmap = coverBitmap,
                            coverPalette = coverPalette,
                            lines = lines,
                            showTranslation = showTranslation,
                            showPronunciation = showPronunciation,
                            showSongInfo = showSongInfo,
                            showBrand = showBrand,
                            lyricSize = lyricSize,
                            lineSpacing = lineSpacing,
                            roundedCorners = showRoundedCorners,
                            paperDarkMode = paperDarkMode,
                            lyricFont = lyricFont,
                            customFontFamily = customFontFamily,
                            savedFontFamily = activeSavedFontFamily,
                            filter = lyricFilter,
                            customBackground = customBackground,
                            customBackgroundBlur = customBackgroundBlur,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = activeTemplate.lightPaper,
                        enter = fadeIn(tween(240, easing = FastOutSlowInEasing)) +
                            scaleIn(tween(240, easing = FastOutSlowInEasing), initialScale = 0.85f),
                        exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.85f),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    ) {
                        InteractiveToolbarGlass(
                            modifier = Modifier.size(46.dp),
                            backdrop = previewBackdrop,
                            tint = MiuixTheme.colorScheme.surface.copy(alpha = .80f),
                            glassAlpha = 1f,
                            onClick = { onPaperDarkModeChange(!paperDarkMode) },
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                AnimatedContent(
                                    targetState = paperDarkMode,
                                    transitionSpec = {
                                        (fadeIn(tween(220)) + scaleIn(tween(240, easing = FastOutSlowInEasing), initialScale = 0.78f))
                                            .togetherWith(fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.78f))
                                    },
                                    label = "paperDarkModeIcon",
                                ) { isDark ->
                                    Image(
                                        painter = painterResource(if (isDark) R.drawable.ic_share_light_mode else R.drawable.ic_share_dark_mode),
                                        contentDescription = if (isDark) tr("share_light_mode", "浅色模式") else tr("share_dark_mode", "深色模式"),
                                        modifier = Modifier.size(22.dp),
                                        colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "templatePicker") {
            Group(tr("share_templates", "分享模板")) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(templates, key = { it.id.name }) { item ->
                        val selected = item.id == activeTemplate.id
                        Column(
                            Modifier.width(82.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onTemplateSelected(item.id) }
                                .padding(2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.size(78.dp, 98.dp)
                                    .then(
                                        if (selected) Modifier.border(2.dp, MiuixTheme.colorScheme.primary, RoundedCornerShape(16.dp)).padding(3.dp)
                                        else Modifier.border(.8.dp, MiuixTheme.colorScheme.outline.copy(alpha = .24f), RoundedCornerShape(15.dp))
                                    )
                            ) {
                                ShareTemplateThumbnail(item, coverBitmap, coverPalette, Modifier.fillMaxSize())
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                item.title,
                                color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }

        item(key = "posterOptions") {
            Group(tr("lyrics_options", "歌词选项")) {
                SwitchPreference(tr("show_translation", "显示翻译"), checked = showTranslation, onCheckedChange = onTranslationChange)
                SwitchPreference(tr("show_pronunciation", "显示发音"), checked = showPronunciation, onCheckedChange = onPronunciationChange)
                SwitchPreference(tr("share_show_song_info", "显示封面与曲目信息"), checked = showSongInfo, onCheckedChange = onSongInfoChange)
                SwitchPreference(tr("share_show_brand", "显示 AURA PLAYER 行"), checked = showBrand, onCheckedChange = onBrandChange)
                SwitchPreference(tr("share_export_rounded", "导出圆角图片"), checked = showRoundedCorners, onCheckedChange = onRoundedCornersChange)
                GlassDropdownPreference(
                    title = tr("lyrics_size", "歌词大小"),
                    items = sizes.map { lyricSizeLabel(it) },
                    selectedIndex = sizes.indexOf(lyricSize).coerceAtLeast(0),
                    backdrop = LocalToolbarBackdrop.current,
                ) { index ->
                    onSizeChange(sizes[index])
                }
                GlassDropdownPreference(
                    title = tr("share_line_spacing", "行距"),
                    items = spacings.map { lyricShareLineSpacingLabel(it) },
                    selectedIndex = spacings.indexOf(lineSpacing).coerceAtLeast(0),
                    backdrop = LocalToolbarBackdrop.current,
                ) { index ->
                    onLineSpacingChange(spacings[index])
                }
                GlassDropdownPreference(
                    title = tr("share_filter", "滤镜"),
                    items = filters.map { lyricShareFilterLabel(it) },
                    selectedIndex = filters.indexOf(lyricFilter).coerceAtLeast(0),
                    backdrop = LocalToolbarBackdrop.current,
                ) { index ->
                    onFilterChange(filters[index])
                }
                GlassDropdownPreference(
                    title = tr("share_image_quality", "图片质量"),
                    items = qualities.map { lyricShareImageQualityLabel(it) },
                    selectedIndex = qualities.indexOf(imageQuality).coerceAtLeast(0),
                    backdrop = LocalToolbarBackdrop.current,
                ) { index ->
                    onImageQualityChange(qualities[index])
                }
                GlassDropdownPreference(
                    title = tr("share_font", "字体"),
                    items = fontMenuItems,
                    selectedIndex = selectedFontIndex,
                    backdrop = LocalToolbarBackdrop.current,
                ) { index ->
                    when {
                        index < builtInFonts.size -> {
                            onFontChange(builtInFonts[index])
                            onSavedFontSelected(null)
                        }
                        index < builtInFonts.size + scannedFonts.size -> {
                            val picked = scannedFonts[index - builtInFonts.size]
                            onFontChange(LyricShareFont.PRESET_FOLDER)
                            onSavedFontSelected(picked.fileName)
                        }
                        else -> {
                            onFontChange(LyricShareFont.CUSTOM)
                            onSavedFontSelected(null)
                        }
                    }
                }
                BlurRevealPreference(visible = lyricFont == LyricShareFont.CUSTOM, label = "share-custom-font-row") {
                    Preference(
                        title = tr("share_select_custom_font", "选择自定义字体"),
                        summary = customFontName ?: tr("share_custom_bg_none", "未设置"),
                        onClick = onOpenCustomFont,
                    )
                }
                Preference(
                    title = tr("share_custom_background", "自定义背景"),
                    summary = customBackgroundName ?: tr("share_custom_bg_none", "未设置"),
                    onClick = onOpenCustomBg,
                )
                BlurRevealPreference(visible = customBackground != null, label = "share-custom-bg-blur") {
                    SliderPreference(
                        title = tr("share_custom_bg_blur", "背景模糊度"),
                        value = customBackgroundBlur,
                        range = 0f..40f,
                        valueText = customBackgroundBlur.roundToInt().toString(),
                        steps = 39,
                        defaultValue = 0f,
                        onChange = onCustomBgBlurChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun BlurRevealPreference(
    visible: Boolean,
    label: String,
    content: @Composable () -> Unit,
) {
    val transition = updateTransition(visible, label = label)
    val blur by transition.animateFloat(transitionSpec = { tween(220) }, label = "blur") { if (it) 0f else 8f }
    transition.AnimatedVisibility(
        visible = { it },
        enter = fadeIn(tween(220)) + expandVertically(tween(220)),
        exit = fadeOut(tween(220)) + shrinkVertically(tween(220)),
    ) {
        Box(Modifier.blur(blur.dp)) {
            content()
        }
    }
}

private suspend fun saveLyricBitmapToGallery(context: Context, bitmap: Bitmap, songTitle: String): Boolean =
    withContext(Dispatchers.IO) {
        val safeName = songTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Aura_Lyrics" }
        val fileName = "${safeName}_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Aura Player")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
        try {
            resolver.openOutputStream(uri)?.use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            } ?: error("Null output stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }

private suspend fun shareLyricBitmap(context: Context, bitmap: Bitmap) {
    val file = withContext(Dispatchers.IO) {
        val dir = context.cacheDir.resolve("lyric-share").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7L * 86400000 }?.forEach { it.delete() }
        dir.resolve("Aura-Lyrics-${System.currentTimeMillis()}.png").apply {
            outputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) }
        }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.preset-files", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri("Aura Lyrics", uri)
    }
    context.startActivity(Intent.createChooser(intent, tr("share_lyrics", "分享歌词")))
}

private fun queryDocumentDisplayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')

internal fun isSupportedFontHeader(header: ByteArray): Boolean {
    if (header.size < 4) return false
    val b0 = header[0].toInt() and 0xFF
    val b1 = header[1].toInt() and 0xFF
    val b2 = header[2].toInt() and 0xFF
    val b3 = header[3].toInt() and 0xFF
    val isTtf = b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00
    val tag = String(header, 0, 4, Charsets.US_ASCII)
    return isTtf || tag == "OTTO" || tag == "ttcf" || tag == "true"
}

private const val FONT_DOWNLOAD_SUBDIR = "Aura Player/Fonts"

private data class SavedShareFont(
    val fileName: String,
    val displayName: String,
    val fontFamily: FontFamily,
)

private data class LoadedCustomFont(
    val fontFamily: FontFamily,
    val fileName: String,
    val cachedFile: File,
)

internal fun isSupportedFontFileName(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in setOf("ttf", "otf", "ttc")
}

internal fun sanitizeFontFileName(name: String?, fallbackExt: String = "ttf"): String {
    val cleaned = (name ?: "")
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim()
        .ifBlank { "CustomFont.$fallbackExt" }
    return if (isSupportedFontFileName(cleaned)) cleaned else "$cleaned.$fallbackExt"
}

internal fun fontMenuDisplayName(fileName: String, allNames: List<String>): String {
    val stem = fileName.substringBeforeLast('.').ifBlank { fileName }
    val sameStemCount = allNames.count { it.substringBeforeLast('.').equals(stem, ignoreCase = true) }
    return if (sameStemCount > 1) fileName else stem
}

private fun decodeValidatedFontFile(file: File): FontFamily? {
    if (!file.isFile || file.length() < 4L) return null
    val header = ByteArray(4)
    val readCount = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(0)
    if (readCount < 4 || !isSupportedFontHeader(header)) return null
    val typeface = runCatching { Typeface.createFromFile(file) }.getOrNull() ?: return null
    return FontFamily(typeface)
}

private suspend fun loadCustomFontFamily(context: Context, uri: Uri): LoadedCustomFont? =
    withContext(Dispatchers.IO) {
        val rawName = queryDocumentDisplayName(context, uri)
        val safeName = sanitizeFontFileName(rawName)
        val dir = context.cacheDir.resolve("custom-share-fonts/imported").apply { mkdirs() }
        val targetFile = dir.resolve("${System.currentTimeMillis()}_$safeName")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            } != null
        }.getOrDefault(false)
        if (!copied) {
            targetFile.delete()
            return@withContext null
        }
        val family = decodeValidatedFontFile(targetFile)
        if (family == null) {
            targetFile.delete()
            return@withContext null
        }
        dir.listFiles()?.filter { it != targetFile }?.forEach { it.delete() }
        LoadedCustomFont(fontFamily = family, fileName = safeName, cachedFile = targetFile)
    }

private suspend fun copyFontToDownloadsFolder(context: Context, sourceFile: File, fileName: String) {
    withContext(Dispatchers.IO) {
        if (!sourceFile.isFile) return@withContext
        val safeName = sanitizeFontFileName(fileName)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$FONT_DOWNLOAD_SUBDIR"
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val alreadyInMediaStore = runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf("%$FONT_DOWNLOAD_SUBDIR%", safeName),
                null,
            )?.use { cursor -> cursor.moveToFirst() }
        }.getOrDefault(false) == true
        val publicFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$FONT_DOWNLOAD_SUBDIR/$safeName")
        if (alreadyInMediaStore || publicFile.isFile) return@withContext

        val mime = when (safeName.substringAfterLast('.', "").lowercase()) {
            "otf" -> "font/otf"
            "ttc" -> "font/collection"
            else -> "font/ttf"
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return@withContext
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                sourceFile.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Null output stream")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
        }
    }
}

private suspend fun scanDownloadFonts(context: Context): List<SavedShareFont> =
    withContext(Dispatchers.IO) {
        val cacheDir = context.cacheDir.resolve("custom-share-fonts/folder").apply { mkdirs() }
        val discovered = linkedMapOf<String, Pair<String, FontFamily>>()
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.DATE_MODIFIED),
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("%$FONT_DOWNLOAD_SUBDIR%"),
                "${MediaStore.Downloads.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val fileName = cursor.getString(nameCol) ?: continue
                    if (!isSupportedFontFileName(fileName)) continue
                    val key = fileName.lowercase()
                    if (key in discovered) continue
                    val id = cursor.getLong(idCol)
                    val stamp = cursor.getLong(dateCol)
                    val safeFile = sanitizeFontFileName(fileName)
                    val cached = cacheDir.resolve("${stamp}_$safeFile")
                    if (!cached.isFile) {
                        val contentUri = Uri.withAppendedPath(collection, id.toString())
                        val ok = runCatching {
                            resolver.openInputStream(contentUri)?.use { input ->
                                cached.outputStream().use { output -> input.copyTo(output) }
                            } != null
                        }.getOrDefault(false)
                        if (!ok) {
                            cached.delete()
                            continue
                        }
                    }
                    decodeValidatedFontFile(cached)?.let { family ->
                        discovered[key] = fileName to family
                    } ?: cached.delete()
                }
            }
        }
        runCatching {
            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FONT_DOWNLOAD_SUBDIR)
            publicDir.listFiles()
                ?.filter { it.isFile && isSupportedFontFileName(it.name) }
                ?.sortedBy { it.name.lowercase() }
                ?.forEach { file ->
                    val key = file.name.lowercase()
                    if (key !in discovered) {
                        decodeValidatedFontFile(file)?.let { family ->
                            discovered[key] = file.name to family
                        }
                    }
                }
        }
        val allNames = discovered.values.map { it.first }
        discovered.values.map { (fileName, family) ->
            SavedShareFont(
                fileName = fileName,
                displayName = fontMenuDisplayName(fileName, allNames),
                fontFamily = family,
            )
        }
    }

private fun scaleBitmapForQuality(bitmap: Bitmap, quality: LyricShareImageQuality): Bitmap {
    val (targetW, targetH) = computeExportDimensions(bitmap.width, bitmap.height, quality)
    return if (targetW == bitmap.width && targetH == bitmap.height) bitmap
    else Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
}

private suspend fun loadCustomBackgroundImage(context: Context, uri: Uri): Pair<ImageBitmap, String?>? =
    withContext(Dispatchers.IO) {
        val name = queryDocumentDisplayName(context, uri)
        val request = ImageRequest.Builder(context)
            .data(uri)
            .allowHardware(false)
            .size(1600)
            .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@withContext null
        result.image.toBitmap().asImageBitmap() to name
    }
