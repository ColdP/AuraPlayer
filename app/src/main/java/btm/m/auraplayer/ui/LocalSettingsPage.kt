package btm.m.auraplayer.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.data.BannerMotionPlayMode
import btm.m.auraplayer.data.HOME_BACKGROUND_BLUR_DP_RANGE
import btm.m.auraplayer.data.HomeBackgroundStyle
import btm.m.auraplayer.data.HomeBannerTarget
import btm.m.auraplayer.data.LOCAL_BANNER_HEIGHT_DP_RANGE
import btm.m.auraplayer.data.LocalBannerStyle
import btm.m.auraplayer.data.MotionPhotoInfo
import btm.m.auraplayer.data.MotionPhotoSupport
import btm.m.auraplayer.data.defaultLocalBannerHeightDp
import btm.m.auraplayer.tags.LocalAudioTags
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt

private enum class LocalProfileTextTarget {
    USERNAME,
    SIGNATURE,
}

private class PickedProfileImage(
    val bytes: ByteArray,
    val extension: String,
    val width: Int,
    val height: Int,
)

private const val MAX_BANNER_RAW_BYTES = 48 * 1024 * 1024

private object LocalProfileImages {
    private fun directory(context: Context): File =
        File(context.filesDir, "local-profile").apply { mkdirs() }

    suspend fun pick(context: Context, uri: Uri, preserveMotion: Boolean): PickedProfileImage =
        withContext(Dispatchers.IO) {
            if (!preserveMotion) {
                val cover = LocalAudioTags.importCover(context, uri)
                val ext = if (cover.mime.contains("png", ignoreCase = true)) "png" else "jpg"
                return@withContext PickedProfileImage(cover.bytes, ext, cover.width, cover.height)
            }
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_BANNER_RAW_BYTES + 1) }
                ?: error("IMAGE_UNREADABLE")
            require(bytes.size <= MAX_BANNER_RAW_BYTES) { "IMAGE_TOO_LARGE" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var width = bounds.outWidth
            var height = bounds.outHeight
            var mime = bounds.outMimeType
            if (width <= 0 || height <= 0) {
                val header = runCatching {
                    var resolvedW = 0
                    var resolvedH = 0
                    var resolvedMime = ""
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { _, info, _ ->
                        resolvedW = info.size.width
                        resolvedH = info.size.height
                        resolvedMime = info.mimeType
                    }
                    Triple(resolvedW, resolvedH, resolvedMime)
                }.getOrNull()
                if (header != null) {
                    width = header.first
                    height = header.second
                    if (header.third.isNotBlank()) mime = header.third
                }
            }
            require(width > 0 && height > 0 && width <= 20_000 && height <= 20_000) { "INVALID_IMAGE" }
            val ext = MotionPhotoSupport.inferExtensionFromBytes(bytes, mime)
            PickedProfileImage(bytes, ext, width, height)
        }

    suspend fun save(context: Context, prefix: String, image: PickedProfileImage): String = withContext(Dispatchers.IO) {
        val dir = directory(context)
        val ext = image.extension.ifBlank { "jpg" }
        val target = File(dir, "$prefix-${System.currentTimeMillis()}.$ext")
        target.writeBytes(image.bytes)
        dir.listFiles()?.forEach { file ->
            if (file.name.startsWith("$prefix-") && file.absolutePath != target.absolutePath) {
                file.delete()
            }
        }
        Uri.fromFile(target).toString()
    }

    suspend fun clear(context: Context, prefix: String) = withContext(Dispatchers.IO) {
        directory(context).listFiles()?.forEach { file ->
            if (file.name.startsWith("$prefix-")) file.delete()
        }
    }
}

@Composable
fun LocalSettingsPage(model: AuraViewModel, padding: PaddingValues) {
    val value = model.settings.value
    var avatarDialogOpen by remember { mutableStateOf(false) }
    var textTarget by remember { mutableStateOf<LocalProfileTextTarget?>(null) }
    var textDraft by remember { mutableStateOf("") }

    LazyColumn(
        pageContentModifier(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Group(tr("local_home_profile", "首页展示")) {
                Preference(
                    title = tr("local_avatar", "头像"),
                    summary = if (value.localAvatarPath.isNotBlank()) {
                        tr("local_image_selected", "已自定义")
                    } else {
                        tr("default", "默认")
                    },
                ) {
                    avatarDialogOpen = true
                }
                Preference(
                    title = tr("local_username", "用户名"),
                    summary = value.localUserName.ifBlank { tr("local_not_set_default", "未设置（使用默认）") },
                ) {
                    textDraft = value.localUserName
                    textTarget = LocalProfileTextTarget.USERNAME
                }
                Preference(
                    title = tr("local_home_signature", "首页签名"),
                    summary = value.localHomeSignature.ifBlank { tr("local_not_set", "未设置") },
                ) {
                    textDraft = value.localHomeSignature
                    textTarget = LocalProfileTextTarget.SIGNATURE
                }
                HomeBannerSettings(model, HomeBannerTarget.LOCAL)
            }
        }
    }

    ProfileImagePickerDialog(
        show = avatarDialogOpen,
        savedPath = value.localAvatarPath,
        prefix = "avatar",
        defaultRatio = 1f,
        onDismiss = { avatarDialogOpen = false },
        onSaved = { nextPath -> model.updateSettings { it.copy(localAvatarPath = nextPath) } },
    )

    val editingText = textTarget
    WindowDialog(
        show = editingText != null,
        title = when (editingText) {
            LocalProfileTextTarget.USERNAME -> tr("local_username", "用户名")
            LocalProfileTextTarget.SIGNATURE -> tr("local_home_signature", "首页签名")
            null -> null
        },
        titleTextAlign = TextAlign.Start,
        onDismissRequest = { textTarget = null },
    ) {
        val label = when (editingText) {
            LocalProfileTextTarget.USERNAME -> tr("local_username", "用户名")
            LocalProfileTextTarget.SIGNATURE -> tr("local_home_signature", "首页签名")
            null -> ""
        }
        AuraTextField(
            value = textDraft,
            onValueChange = { textDraft = it },
            singleLine = editingText == LocalProfileTextTarget.USERNAME,
            label = label,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (editingText == LocalProfileTextTarget.SIGNATURE) Modifier.heightIn(min = 100.dp, max = 180.dp) else Modifier),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassDialogButton({ textTarget = null }, Modifier.weight(1f)) { Text(tr("cancel", "取消")) }
            GlassDialogButton(
                onClick = {
                    val trimmed = textDraft.trim()
                    when (editingText) {
                        LocalProfileTextTarget.USERNAME -> model.updateSettings { it.copy(localUserName = trimmed) }
                        LocalProfileTextTarget.SIGNATURE -> model.updateSettings { it.copy(localHomeSignature = trimmed) }
                        null -> Unit
                    }
                    textTarget = null
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(tr("save", "保存")) }
        }
    }
}

/** 首页横幅样式、图片、高度及液态玻璃材质参数设置项，本地与云端页面共用。 */
@Composable
internal fun HomeBannerSettings(model: AuraViewModel, target: HomeBannerTarget) {
    val settings = model.settings.value
    val bannerConfig = settings.bannerConfig(target)
    val activeStyle = bannerConfig.style
    val styleConfig = bannerConfig.styleConfig(activeStyle)
    var bannerDialogOpen by remember { mutableStateOf(false) }
    var glassExpanded by rememberSaveable(target) { mutableStateOf(false) }
    val motionInfo by produceState(MotionPhotoInfo(), bannerConfig.imagePath) {
        value = MotionPhotoSupport.inspectAsync(bannerConfig.imagePath)
    }

    val bannerStyles = LocalBannerStyle.entries
    val bannerLabels = listOf(
        tr("local_banner_none", "不显示"),
        tr("local_banner_rounded_card", "圆角卡片"),
        tr("local_banner_immersive", "沉浸式"),
        tr("local_banner_immersive_glass", "沉浸玻璃"),
        tr("local_banner_glass", "毛玻璃海报"),
        tr("local_banner_aurora", "流光氛围"),
    )
    val motionModes = BannerMotionPlayMode.entries
    val motionModeLabels = listOf(
        tr("banner_motion_off", "关闭动态"),
        tr("banner_motion_once", "仅播一次"),
        tr("banner_motion_reverse", "倒放"),
        tr("banner_motion_loop", "循环"),
    )
    val backgroundStyles = HomeBackgroundStyle.entries
    val backgroundStyleLabels = listOf(
        tr("home_background_default", "默认"),
        tr("home_background_color", "颜色"),
        tr("home_background_color_blur", "颜色模糊"),
    )

    GlassDropdownPreference(
        title = tr("local_banner_style", "横幅样式"),
        items = bannerLabels,
        selectedIndex = bannerStyles.indexOf(activeStyle).coerceAtLeast(0),
        backdrop = LocalToolbarBackdrop.current,
    ) { index ->
        model.updateSettings { current ->
            current.withBannerConfig(target) { it.copy(style = bannerStyles[index]) }
        }
    }
    BlurAnimatedVisibility(
        visible = activeStyle != LocalBannerStyle.NONE,
        label = "${target.name}-banner-image-setting",
    ) {
        Column {
            Preference(
                title = tr("local_banner_image", "横幅图片"),
                summary = when {
                    bannerConfig.imagePath.isNotBlank() && motionInfo.isDynamic ->
                        "${tr("local_image_selected", "已自定义")} · ${tr("local_banner_motion_badge", "动态照片")}"
                    bannerConfig.imagePath.isNotBlank() -> tr("local_image_selected", "已自定义")
                    else -> tr("local_banner_image_default", "未选择（使用默认渐变）")
                },
            ) {
                bannerDialogOpen = true
            }
            BlurAnimatedVisibility(
                visible = motionInfo.isDynamic,
                label = "${target.name}-banner-motion-mode",
            ) {
                GlassDropdownPreference(
                    title = tr("banner_motion_play_mode", "动态播放模式"),
                    items = motionModeLabels,
                    selectedIndex = motionModes.indexOf(bannerConfig.motionPlayMode).coerceAtLeast(0),
                    backdrop = LocalToolbarBackdrop.current,
                ) { index ->
                    model.updateSettings { current ->
                        current.withBannerConfig(target) { it.copy(motionPlayMode = motionModes[index]) }
                    }
                }
            }
            EditableSliderPreference(
                title = tr("local_banner_height", "横幅高度"),
                value = styleConfig.heightDp.toFloat(),
                valueText = "${styleConfig.heightDp} dp",
                valueRange = LOCAL_BANNER_HEIGHT_DP_RANGE.first.toFloat()..LOCAL_BANNER_HEIGHT_DP_RANGE.last.toFloat(),
                steps = LOCAL_BANNER_HEIGHT_DP_RANGE.last - LOCAL_BANNER_HEIGHT_DP_RANGE.first - 1,
                defaultValue = defaultLocalBannerHeightDp(activeStyle).toFloat(),
                onValueChange = { v ->
                    model.updateSettings { current ->
                        current.withBannerConfig(target) { cfg ->
                            cfg.withStyleConfig(activeStyle) { it.copy(heightDp = v.roundToInt()) }
                        }
                    }
                },
                onValueChangeFinished = {},
            )
        }
    }
    BlurAnimatedVisibility(
        visible = activeStyle == LocalBannerStyle.IMMERSIVE ||
            activeStyle == LocalBannerStyle.IMMERSIVE_GLASS,
        label = "${target.name}-immersive-bg-style",
    ) {
        Column {
            GlassDropdownPreference(
                title = tr("home_background_style", "首页背景样式"),
                items = backgroundStyleLabels,
                selectedIndex = backgroundStyles.indexOf(styleConfig.backgroundStyle).coerceAtLeast(0),
                backdrop = LocalToolbarBackdrop.current,
            ) { index ->
                model.updateSettings { current ->
                    current.withBannerConfig(target) { cfg ->
                        cfg.withStyleConfig(activeStyle) { it.copy(backgroundStyle = backgroundStyles[index]) }
                    }
                }
            }
            BlurAnimatedVisibility(
                visible = styleConfig.backgroundStyle == HomeBackgroundStyle.COLOR_BLUR,
                label = "${target.name}-immersive-bg-blur",
            ) {
                EditableSliderPreference(
                    title = tr("nav_material_blur", "模糊度"),
                    value = styleConfig.backgroundBlurDp.toFloat(),
                    valueText = "${styleConfig.backgroundBlurDp} dp",
                    valueRange = HOME_BACKGROUND_BLUR_DP_RANGE.first.toFloat()..HOME_BACKGROUND_BLUR_DP_RANGE.last.toFloat(),
                    steps = HOME_BACKGROUND_BLUR_DP_RANGE.last - HOME_BACKGROUND_BLUR_DP_RANGE.first - 1,
                    defaultValue = 36f,
                    onValueChange = { v ->
                        model.updateSettings { current ->
                            current.withBannerConfig(target) { cfg ->
                                cfg.withStyleConfig(activeStyle) { it.copy(backgroundBlurDp = v.roundToInt()) }
                            }
                        }
                    },
                    onValueChangeFinished = {},
                )
            }
        }
    }
    BlurAnimatedVisibility(
        visible = activeStyle == LocalBannerStyle.GLASS_POSTER ||
            activeStyle == LocalBannerStyle.IMMERSIVE_GLASS,
        label = "${target.name}-banner-glass-entry",
    ) {
        Column {
            Preference(
                title = tr("local_banner_glass_material", "玻璃材质参数调整"),
                summary = if (glassExpanded) {
                    tr("local_banner_glass_collapse", "点击收起参数")
                } else {
                    tr("local_banner_glass_expand", "调整折射强度、模糊度、不透明度与色散")
                },
            ) {
                glassExpanded = !glassExpanded
            }
            BlurAnimatedVisibility(
                visible = glassExpanded,
                label = "${target.name}-banner-glass-params",
            ) {
                Column {
                    BannerGlassPercentSlider(
                        title = tr("nav_material_refraction", "折射强度"),
                        value = styleConfig.glassRefraction,
                        max = 200,
                    ) { v ->
                        model.updateSettings { current ->
                            current.withBannerConfig(target) { cfg ->
                                cfg.withStyleConfig(activeStyle) { it.copy(glassRefraction = v) }
                            }
                        }
                    }
                    BannerGlassPercentSlider(
                        title = tr("nav_material_blur", "模糊度"),
                        value = styleConfig.glassBlur,
                        max = 300,
                    ) { v ->
                        model.updateSettings { current ->
                            current.withBannerConfig(target) { cfg ->
                                cfg.withStyleConfig(activeStyle) { it.copy(glassBlur = v) }
                            }
                        }
                    }
                    BannerGlassPercentSlider(
                        title = tr("nav_material_opacity", "背景不透明度"),
                        value = styleConfig.glassOpacity,
                        max = 250,
                    ) { v ->
                        model.updateSettings { current ->
                            current.withBannerConfig(target) { cfg ->
                                cfg.withStyleConfig(activeStyle) { it.copy(glassOpacity = v) }
                            }
                        }
                    }
                    BannerGlassPercentSlider(
                        title = tr("nav_material_highlight", "高光强度"),
                        value = styleConfig.glassHighlight,
                        max = 200,
                    ) { v ->
                        model.updateSettings { current ->
                            current.withBannerConfig(target) { cfg ->
                                cfg.withStyleConfig(activeStyle) { it.copy(glassHighlight = v) }
                            }
                        }
                    }
                    EditableSliderPreference(
                        title = tr("local_banner_glass_corner", "圆角大小"),
                        value = styleConfig.glassCorner.toFloat(),
                        valueText = styleConfig.glassCorner.toString(),
                        valueRange = -64f..96f,
                        steps = 159,
                        defaultValue = 0f,
                        centerValue = 0f,
                        onValueChange = { v ->
                            model.updateSettings { current ->
                                current.withBannerConfig(target) { cfg ->
                                    cfg.withStyleConfig(activeStyle) { it.copy(glassCorner = v.roundToInt()) }
                                }
                            }
                        },
                        onValueChangeFinished = {},
                    )
                    SwitchPreference(
                        title = tr("nav_material_dispersion", "色散"),
                        checked = styleConfig.glassDispersion,
                    ) { checked ->
                        model.updateSettings { current ->
                            current.withBannerConfig(target) { cfg ->
                                cfg.withStyleConfig(activeStyle) { it.copy(glassDispersion = checked) }
                            }
                        }
                    }
                }
            }
        }
    }

    ProfileImagePickerDialog(
        show = bannerDialogOpen,
        savedPath = bannerConfig.imagePath,
        prefix = target.imagePrefix,
        defaultRatio = 16f / 9f,
        preserveMotion = true,
        onDismiss = { bannerDialogOpen = false },
        onSaved = { nextPath ->
            model.updateSettings { current ->
                current.withBannerConfig(target) { it.copy(imagePath = nextPath) }
            }
        },
    )
}

@Composable
private fun ProfileImagePickerDialog(
    show: Boolean,
    savedPath: String,
    prefix: String,
    defaultRatio: Float,
    preserveMotion: Boolean = false,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draftImage by remember(show, savedPath) { mutableStateOf<PickedProfileImage?>(null) }
    var draftCleared by remember(show, savedPath) { mutableStateOf(false) }
    var selectingImage by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<String?>(null) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        selectingImage = true
        scope.launch {
            try {
                draftImage = LocalProfileImages.pick(context, uri, preserveMotion = preserveMotion)
                draftCleared = false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                imageError = tr("tag_image_failed", "无法读取图片，请选择不超过 32 MB 的有效图片")
            } finally {
                selectingImage = false
            }
        }
    }

    WindowDialog(
        show = show,
        onDismissRequest = { if (!selectingImage) onDismiss() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LocalProfileImagePreview(
                picked = draftImage,
                savedPath = if (draftCleared) "" else savedPath,
                defaultRatio = defaultRatio,
                modifier = Modifier.widthIn(max = 180.dp).fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton(
                    onClick = { pickImage.launch(arrayOf("image/*")) },
                    modifier = Modifier.weight(1f),
                    enabled = !selectingImage,
                ) { Text(tr("select_file", "选择文件")) }
                GlassDialogButton(
                    onClick = {
                        draftImage = null
                        draftCleared = true
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !selectingImage,
                ) { Text(tr("restore", "恢复")) }
            }
            GlassDialogButton(
                onClick = {
                    val picked = draftImage
                    val cleared = draftCleared
                    if (picked == null && !cleared) {
                        onDismiss()
                        return@GlassDialogButton
                    }
                    selectingImage = true
                    scope.launch {
                        try {
                            val nextPath = if (picked != null) {
                                LocalProfileImages.save(context, prefix, picked)
                            } else {
                                LocalProfileImages.clear(context, prefix)
                                ""
                            }
                            onSaved(nextPath)
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            imageError = tr("tag_image_failed", "无法读取图片，请选择不超过 32 MB 的有效图片")
                        } finally {
                            selectingImage = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !selectingImage,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(tr("save", "保存")) }
            GlassDialogButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                enabled = !selectingImage,
            ) { Text(tr("cancel", "取消")) }
        }
    }

    WindowDialog(
        show = imageError != null,
        title = tr("notice", "提示"),
        titleTextAlign = TextAlign.Start,
        summary = imageError,
        summaryTextAlign = TextAlign.Start,
        onDismissRequest = { imageError = null },
    ) {
        GlassDialogButton({ imageError = null }, Modifier.fillMaxWidth()) { Text(tr("ok", "确定")) }
    }
}

@Composable
private fun BannerGlassPercentSlider(
    title: String,
    value: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    EditableSliderPreference(
        title = title,
        value = value.toFloat(),
        valueText = tr("nav_material_percent", "%d%%").format(value),
        valueRange = 0f..max.toFloat(),
        steps = max - 1,
        defaultValue = 100f,
        onValueChange = { onChange(it.roundToInt()) },
        onValueChangeFinished = {},
    )
}

@Composable
private fun LocalProfileImagePreview(
    picked: PickedProfileImage?,
    savedPath: String,
    defaultRatio: Float,
    modifier: Modifier,
) {
    val context = LocalContext.current
    var savedRatio by remember(savedPath, defaultRatio) { mutableFloatStateOf(defaultRatio) }
    val ratio = remember(picked) {
        if (picked != null && picked.width > 0 && picked.height > 0) {
            (picked.width.toFloat() / picked.height.toFloat()).coerceIn(0.6f, 2.4f)
        } else {
            null
        }
    }
    Box(
        modifier = modifier
            .aspectRatio((ratio ?: savedRatio).coerceIn(0.6f, 2.4f))
            .clip(RoundedCornerShape(22.5.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (picked != null || savedPath.isNotBlank()) {
            val previewRequest = remember(picked, savedPath, context) {
                ImageRequest.Builder(context)
                    .data(picked?.bytes ?: savedPath)
                    .size(512)
                    .build()
            }
            AsyncImage(
                model = previewRequest,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onSuccess = {
                    if (picked == null) {
                        val image = it.result.image
                        if (image.width > 0 && image.height > 0) {
                            savedRatio = (image.width.toFloat() / image.height).coerceIn(0.6f, 2.4f)
                        }
                    }
                },
            )
        } else {
            Image(
                imageVector = MiuixIcons.Regular.Music,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary),
            )
        }
    }
}
