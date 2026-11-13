package btm.m.auraplayer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.audiofx.AudioEffect
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.AuraApplication
import btm.m.auraplayer.audio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

internal fun presetName(p: DspPreset): String = when (p.id) {
    "reference" -> tr("preset_reference", "原声本真")
    "warmth" -> tr("preset_warmth", "暖声入心")
    "clarity" -> tr("preset_clarity", "清透澄澈")
    "deep_bass" -> tr("preset_deep_bass", "深潜低鸣")
    "wide_stage" -> tr("preset_wide_stage", "开阔声场")
    "j_pop" -> tr("preset_j_pop", "日系跃动")
    "vocaloid" -> tr("preset_vocaloid", "电子歌姬")
    "anime" -> tr("preset_anime", "番剧原声")
    "electronic" -> tr("preset_electronic", "电音脉冲")
    "rock" -> tr("preset_rock", "摇滚狂潮")
    "acoustic" -> tr("preset_acoustic", "原木清音")
    "classical" -> tr("preset_classical", "古典交响")
    "jazz" -> tr("preset_jazz", "爵士微醺")
    "piano" -> tr("preset_piano", "琴键独白")
    "live" -> tr("preset_live", "现场临境")
    "custom" -> tr("custom", "自定义")
    else -> p.name
}

internal fun presetSummary(p: DspPreset): String = when (p.id) {
    "reference" -> tr("preset_reference_summary", "关闭内置处理，原始 PCM 直通")
    "warmth" -> tr("preset_warmth_summary", "柔和高频，增加适度的中低频厚度")
    "clarity" -> tr("preset_clarity_summary", "减少浑浊，轻提人声清晰度与空气感")
    "deep_bass" -> tr("preset_bass_summary", "低频下潜与冲击，温和控制动态")
    "wide_stage" -> tr("preset_wide_summary", "适度拓展立体声，保留中央人声")
    "j_pop" -> tr("preset_j_pop_summary", "轻盈低频与清晰人声，适合日系流行")
    "vocaloid" -> tr("preset_vocaloid_summary", "柔化合成歌声的锐度，保留电子质感")
    "anime" -> tr("preset_anime_summary", "兼顾对白、配乐层次与空间感")
    "electronic" -> tr("preset_electronic_summary", "饱满低频、开阔声场与温和动态控制")
    "rock" -> tr("preset_rock_summary", "增加乐器厚度，柔化高频刺激")
    "acoustic" -> tr("preset_acoustic_summary", "减少箱体浑浊，保留弦乐细节")
    "classical" -> tr("preset_classical_summary", "轻度音色修饰，保留原有动态")
    "jazz" -> tr("preset_jazz_summary", "温暖中低频与轻度耳机交叉馈送")
    "piano" -> tr("preset_piano_summary", "收紧低中频，保留琴键触感与泛音")
    "live" -> tr("preset_live_summary", "改善现场录音的清晰度与立体声宽度")
    "custom" -> tr("preset_custom_summary", "实时调整均衡、动态与空间参数")
    else -> p.description
}

@Composable private fun EffectsCard(content: @Composable ColumnScope.() -> Unit) =
    SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp), content = content)

@Composable private fun PresetRow(p: DspPreset, selected: Boolean, select: () -> Unit) {
    val color = MiuixTheme.colorScheme.primary
    BasicComponent(title = presetName(p), summary = presetSummary(p), insideMargin = CompactPreferencePadding,
        onClick = select, endActions = {
            if (selected) Canvas(Modifier.size(22.dp)) {
                val path = Path().apply { moveTo(size.width * .17f, size.height * .5f); lineTo(size.width * .4f, size.height * .73f); lineTo(size.width * .85f, size.height * .24f) }
                drawPath(path, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            }
        })
}

@Composable fun AudioEffectsPage(padding: PaddingValues, isActive: Boolean, open: (String) -> Unit) {
    val context = LocalContext.current
    val store = (context.applicationContext as AuraApplication).effects
    val state by store.state.collectAsState()
    // Keep row positions stable while selecting. Re-rank only on entry or explicit refresh;
    // resolve IDs against live data so the check mark and edited parameters still update.
    var featuredIds by remember { mutableStateOf(store.state.value.featured.map { it.id }) }
    var isRefreshing by remember { mutableStateOf(false) }
    val pullToRefreshState = rememberPullToRefreshState()
    LaunchedEffect(isActive) {
        if (isActive) featuredIds = store.state.value.featured.map { it.id }
    }
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            featuredIds = store.state.value.featured.map { it.id }
            delay(350)
            isRefreshing = false
        }
    }
    PullToRefresh(
        isRefreshing = isRefreshing,
        onRefresh = { isRefreshing = true },
        modifier = Modifier.fillMaxSize(),
        pullToRefreshState = pullToRefreshState,
        contentPadding = padding,
        topAppBarScrollBehavior = LocalPageScroll.current,
        color = MiuixTheme.colorScheme.primary,
        refreshTexts = listOf(tr("pull_to_refresh", "下拉刷新"), tr("release_to_refresh", "松开刷新"),
            tr("refreshing", "正在刷新…"), tr("refresh_completed", "刷新完成")),
    ) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            EffectsCard { AudioOutputPreview(store.outputWaveform, isActive) }
        }
        item {
            Group(tr("effect_presets", "音效预设")) {
                featuredIds.mapNotNull { id -> state.presets.firstOrNull { it.id == id } }.forEach { p ->
                    key(p.id) { PresetRow(p, state.selected == p.id) { store.select(p.id) } }
                }
                Preference(tr("preset_library", "预设库")) { open("effect_library") }
            }
        }
        item {
            Group(tr("sound_style", "声音风格")) {
                Preference(tr("custom", "自定义"), tr("preset_custom_summary", "实时调整均衡、动态与空间参数")) { open("effect_custom") }
                GlassDropdownPreference(tr("overall_sound_style", "声音整体风格"),
                    listOf(tr("style_classic", "经典"), tr("style_balanced", "均衡"), tr("style_original", "原声")), state.style.ordinal,
                    backdrop = LocalToolbarBackdrop.current) { store.style(SoundStyle.entries[it]) }
                Preference(tr("system_equalizer", "系统均衡器"), tr("system_equalizer_summary", "打开系统提供的音频效果面板")) {
                    val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                        .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, store.audioSessionId)
                        .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                        .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                    val message = if (store.audioSessionId <= 0) tr("equalizer_play_first", "请先播放一首歌曲") else {
                        if (runCatching { context.startActivity(intent) }.isSuccess) null else tr("equalizer_unavailable", "此设备未提供系统均衡器")
                    }
                    if (message != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
        item { Text(tr("effects_hint", "预设是声音风格建议，并非音乐类型的固定频响。原声本真不受整体风格影响；其他预设会自动预留增益余量。"), style = MiuixTheme.textStyles.body2, modifier = Modifier.padding(horizontal = 12.dp)) }
    }
    }
}

@Composable fun PresetLibraryPage(padding: PaddingValues, selectedTab: Int, open: (String) -> Unit) {
    val store = (LocalContext.current.applicationContext as AuraApplication).effects
    val state by store.state.collectAsState()
    var transfer by remember { mutableStateOf("") }
    var actionPreset by remember { mutableStateOf<DspPreset?>(null) }
    var exportPreset by remember { mutableStateOf<DspPreset?>(null) }
    var deletePreset by remember { mutableStateOf<DspPreset?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            EffectsCard {
                Preference(tr("import_preset", "预设导入")) { transfer = "import" }
                Preference(tr("export_preset", "预设导出"), presetName(state.current)) { exportPreset = state.current; transfer = "export" }
                Preference(tr("online_presets", "在线预设库")) { open("effect_online") }
                Preference(tr("restore_default_parameters", "恢复默认参数")) { resetting = true }
            }
        }
        if (selectedTab == 0) {
            item { Group(tr("core_sound_styles", "核心声音风格")) { BuiltInPresets.all.filter { it.core }.forEach { p -> PresetRow(p, state.selected == p.id) { actionPreset = p } } } }
            item { Group(tr("genre_sound_styles", "音乐风格参考")) { BuiltInPresets.all.filterNot { it.core }.forEach { p -> PresetRow(p, state.selected == p.id) { actionPreset = p } } } }
        }
        if (selectedTab == 1 && state.imported.isNotEmpty()) item {
            Group(tr("my_presets", "我的预设")) {
                state.imported.forEach { p -> PresetRow(p, state.selected == p.id) { actionPreset = p } }
            }
        }
        if (selectedTab == 1 && state.imported.isEmpty()) item {
            EffectsCard { BasicComponent(title = tr("no_user_presets", "尚无用户预设"), summary = tr("user_presets_hint", "导入预设，或在自定义页面另存你的声音风格"), insideMargin = CompactPreferencePadding) }
        }
        if (selectedTab == 1 && state.draftDirty) item {
            EffectsCard { Preference(tr("unsaved_preset", "未保存的预设")) { draft = true } }
        }
    }
    WindowDialog(actionPreset != null, title = actionPreset?.let(::presetName), titleTextAlign = TextAlign.Start, onDismissRequest = { actionPreset = null }) {
        val p = actionPreset
        GlassDialogButton({ if (p != null) store.select(p.id); actionPreset = null }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("use_preset", "使用")) }
        if (p != null && state.imported.any { it.id == p.id }) GlassDialogButton({ deletePreset = p; actionPreset = null; deleting = true }, Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(tr("delete", "删除")) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton({ exportPreset = p; actionPreset = null; transfer = "export" }, Modifier.weight(1f)) { Text(tr("export", "导出")) }
            GlassDialogButton({ exportPreset = p; actionPreset = null; transfer = "share" }, Modifier.weight(1f)) { Text(tr("share", "分享")) }
        }
    }
    PresetTransfer(transfer, store, exportPreset) { transfer = "" }
    PresetDraftDialog(store, draft, onDismiss = { draft = false }, onSaved = { draft = false })
    WindowDialog(resetting, title = tr("restore_default_parameters", "恢复默认参数"), summary = tr("restore_effects_hint", "恢复原声本真与默认整体风格，保留用户预设及音频设置"), titleTextAlign = TextAlign.Start, summaryTextAlign = TextAlign.Start, onDismissRequest = { resetting = false }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton({ resetting = false }, Modifier.weight(1f)) { Text(tr("cancel", "取消")) }
            GlassDialogButton({ store.restoreDefaults(); resetting = false }, Modifier.weight(1f)) { Text(tr("restore", "恢复")) }
        }
    }
    WindowDialog(deleting, title = tr("delete_preset_confirm", "删除当前预设？"), summary = deletePreset?.let(::presetName), titleTextAlign = TextAlign.Start, summaryTextAlign = TextAlign.Start, onDismissRequest = { deleting = false }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton({ deleting = false }, Modifier.weight(1f)) { Text(tr("cancel", "取消")) }
            GlassDialogButton({ deletePreset?.let { store.delete(it.id) }; deleting = false; deletePreset = null }, Modifier.weight(1f)) { Text(tr("delete", "删除")) }
        }
    }
}

@Composable internal fun PresetTransfer(mode: String, store: EffectsStore, preset: DspPreset? = null, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pasted by remember { mutableStateOf("") }
    var pasteDialog by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var exportJson by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun notify(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    fun exportText(): String = (preset ?: store.state.value.current).let { PresetCodec.encode(it.copy(name = presetName(it), description = presetSummary(it))) }
    fun share(bytes: ByteArray?, mime: String, extension: String, text: String? = null) {
        scope.launch {
            busy = true
            val result = runCatching {
                val intent = Intent(Intent.ACTION_SEND).setType(mime)
                if (bytes != null) {
                    val file = withContext(Dispatchers.IO) {
                        val dir = context.cacheDir.resolve("preset-share").apply { mkdirs() }
                        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7L * 86400000 }?.forEach { it.delete() }
                        dir.resolve("Aura-${UUID.randomUUID()}.$extension").apply { writeBytes(bytes) }
                    }
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.preset-files", file)
                    intent.putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.clipData = ClipData.newRawUri("Aura", uri)
                } else intent.putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(intent, tr("share_preset", "分享预设")))
            }
            if (result.isFailure) notify(tr("preset_share_failed", "无法打开分享面板，请尝试导出"))
            busy = false; close()
        }
    }
    fun importing(read: suspend () -> String) {
        scope.launch {
            busy = true
            val result = runCatching { withContext(Dispatchers.IO) { PresetCodec.decode(read()) } }
            result.onSuccess { p ->
                if (runCatching { store.importPreset(p) }.isSuccess) { pasteDialog = false; close(); notify(tr("preset_imported", "预设已导入")) }
                else notify(tr("preset_library_full", "预设库已满，请先删除部分自存预设"))
            }.onFailure { notify(tr("preset_import_failed", "无法导入：请检查预设格式、版本和参数范围")) }
            busy = false
        }
    }
    val jsonPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) importing {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readNBytes(PresetCodec.MAX_BYTES + 1) }
        require(bytes.size <= PresetCodec.MAX_BYTES); bytes.toString(Charsets.UTF_8)
    } }
    val qrPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) importing {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val max = maxOf(info.size.width, info.size.height)
            decoder.setTargetSampleSize(((max + 2047) / 2048).coerceAtLeast(1))
        }
        try { PresetQr.read(bitmap) } finally { bitmap.recycle() }
    } }
    val jsonWriter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val ok = runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { it.write(exportJson.toByteArray(Charsets.UTF_8)) } } }.isSuccess
            notify(if (ok) tr("preset_exported", "预设已导出") else tr("preset_export_failed", "导出失败，请重新选择保存位置"))
        }
    }
    val qrWriter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bitmap = qr
        if (uri != null && bitmap != null) scope.launch {
            val ok = runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } } }.isSuccess
            notify(if (ok) tr("preset_exported", "预设已导出") else tr("preset_export_failed", "导出失败，请重新选择保存位置"))
        }
    }
    WindowDialog(mode.isNotEmpty(), title = when (mode) { "import" -> tr("import_preset", "预设导入"); "share" -> tr("share_preset", "分享预设"); else -> tr("export_preset", "预设导出") },
        summary = if (mode != "import") presetName(preset ?: store.state.value.current) else tr("preset_transfer_hint", "支持 Aura JSON 文件、二维码图片或 JSON 文本"), titleTextAlign = TextAlign.Start, summaryTextAlign = TextAlign.Start, onDismissRequest = close) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassDialogButton(onClick = {
            if (mode == "import") jsonPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            else if (mode == "share") share(exportText().toByteArray(Charsets.UTF_8), "application/json", "json")
            else { exportJson = exportText(); jsonWriter.launch("Aura-preset.json") }
        }, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text(tr("preset_json_file", "JSON 文件")) }
        GlassDialogButton(onClick = {
            if (mode == "import") qrPicker.launch(arrayOf("image/*"))
            else scope.launch {
                busy = true
                runCatching { withContext(Dispatchers.Default) { PresetQr.create(exportText()) } }
                    .onSuccess { bitmap ->
                        if (mode == "share") {
                            val bytes = withContext(Dispatchers.IO) { java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
                            bitmap.recycle(); share(bytes, "image/png", "png")
                        } else { qr = bitmap; close() }
                    }.onFailure { notify(tr("preset_export_failed", "导出失败，请重新选择保存位置")) }
                busy = false
            }
        }, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text(tr("preset_qr_image", "二维码图片")) }
        GlassDialogButton(onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (mode == "import") { pasted = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().take(PresetCodec.MAX_BYTES); pasteDialog = true; close() }
            else if (mode == "share") share(null, "text/plain", "txt", exportText())
            else { clipboard.setPrimaryClip(ClipData.newPlainText(tr("audio_effects", "音效"), exportText())); close(); notify(tr("copied", "已复制")) }
        }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
            Text(when (mode) { "import" -> tr("paste_json", "粘贴 JSON 文本"); "share" -> tr("preset_text", "文本"); else -> tr("copy_json", "复制 JSON 文本") })
        }
        }
    }
    WindowDialog(pasteDialog, title = tr("paste_json", "粘贴 JSON 文本"), titleTextAlign = TextAlign.Start, onDismissRequest = { pasteDialog = false }) {
        TextField(pasted, { pasted = it.take(PresetCodec.MAX_BYTES) }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 260.dp), label = tr("preset_json_text", "预设 JSON 文本"))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton({ pasteDialog = false }, Modifier.weight(1f)) { Text(tr("cancel", "取消")) }
            GlassDialogButton({ importing { pasted } }, Modifier.weight(1f), enabled = pasted.isNotBlank() && !busy) { Text(tr("import_apply", "导入并应用")) }
        }
    }
    WindowDialog(qr != null, title = tr("preset_qr_image", "二维码图片"), onDismissRequest = { qr = null }) {
        qr?.let { Image(it.asImageBitmap(), contentDescription = tr("preset_qr_image", "二维码图片"), modifier = Modifier.fillMaxWidth().aspectRatio(1f)) }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton({ qr = null }, Modifier.weight(1f)) { Text(tr("done", "完成")) }
            GlassDialogButton({ qrWriter.launch("Aura-preset.png") }, Modifier.weight(1f)) { Text(tr("save", "保存")) }
        }
    }
}

private fun decimal(v: Double) = String.format(Locale.ROOT, "%.1f", v)

@Composable fun CustomEffectsPage(padding: PaddingValues) {
    val store = (LocalContext.current.applicationContext as AuraApplication).effects
    val state by store.state.collectAsState()
    val c = state.editableConfig
    var saving by remember { mutableStateOf(false) }
    fun change(block: (DspConfig) -> DspConfig) = store.custom(block(store.state.value.editableConfig))
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            EffectsCard {
                Preference(tr("edit_from_current", "从当前预设开始编辑"), presetName(state.current)) { store.custom(state.current.config.copy(bypass = false)) }
                Preference(tr("save_as_preset", "保存为新预设")) { store.custom(c); saving = true }
                SwitchPreference(tr("enable_custom_processing", "启用自定义处理"), checked = !c.bypass) { enabled -> change { it.copy(bypass = !enabled) } }
            }
        }
        item { Group(tr("effect_headroom", "增益余量")) {
            SliderPreference(tr("effect_preamp", "前置增益"), c.preampDb.toFloat(), -18f..0f, "${decimal(c.preampDb)} dB", !c.bypass, steps = 35, defaultValue = -2f) { value -> change { it.copy(preampDb = value.toDouble()) } }
            BasicComponent(title = tr("automatic_headroom", "自动增益保护"), summary = tr("automatic_headroom_summary", "在前置增益基础上，为 EQ 和声场增强额外预留余量，并限制输出峰值"), insideMargin = CompactPreferencePadding)
        } }
        items(c.bands.indices.toList(), key = { "band-$it" }) { index ->
            val b = c.bands[index]
            fun band(block: (EqBand) -> EqBand) = change { current -> current.copy(bands = current.bands.mapIndexed { i, v -> if (i == index) block(v) else v }) }
            Group("${tr("eq_band", "均衡频段")} ${index + 1}") {
                GlassDropdownPreference(tr("filter_type", "滤波器类型"), listOf(tr("filter_peak", "峰值"), tr("filter_low", "低频搁架"), tr("filter_high", "高频搁架")), b.type.ordinal, enabled = !c.bypass, backdrop = LocalToolbarBackdrop.current) { type -> band { it.copy(type = FilterType.entries[type]) } }
                SliderPreference(tr("eq_frequency", "频率"), b.frequency.toFloat(), 20f..20000f, "${b.frequency.roundToInt()} Hz", !c.bypass, defaultValue = 1000f) { value -> band { it.copy(frequency = value.toDouble()) } }
                SliderPreference(tr("eq_gain", "增益"), b.gainDb.toFloat(), -9f..6f, "${decimal(b.gainDb)} dB", !c.bypass, steps = 29) { value -> band { it.copy(gainDb = value.toDouble()) } }
                SliderPreference(tr("eq_q", "Q 值"), b.q.toFloat(), .3f..3f, decimal(b.q), !c.bypass, steps = 26, defaultValue = .707f) { value -> band { it.copy(q = value.toDouble().coerceIn(.3, 3.0)) } }
                if (c.bands.size > 1) Preference(tr("remove_eq_band", "移除此频段")) { change { it.copy(bands = it.bands.filterIndexed { i, _ -> i != index }) } }
            }
        }
        if (c.bands.size < 8) item { EffectsCard { Preference(tr("add_eq_band", "添加均衡频段")) { change { it.copy(bands = it.bands + EqBand(1000.0, 0.0)) } } } }
        item { Group(tr("effect_spatial", "空间与音色")) {
            SliderPreference(tr("effect_width", "立体声宽度"), c.width.toFloat(), .5f..1.4f, "${(c.width * 100).roundToInt()}%", !c.bypass, steps = 17, defaultValue = 1f) { value -> change { it.copy(width = value.toDouble()) } }
            SliderPreference(tr("effect_crossfeed", "低频交叉馈送"), c.crossfeed.toFloat(), 0f..0.3f, "${(c.crossfeed * 100).roundToInt()}%", !c.bypass, steps = 29) { value -> change { it.copy(crossfeed = value.toDouble().coerceIn(0.0, .3)) } }
            SliderPreference(tr("effect_saturation", "谐波染色"), c.saturation.toFloat(), 0f..0.25f, "${(c.saturation * 100).roundToInt()}%", !c.bypass, steps = 24) { value -> change { it.copy(saturation = value.toDouble()) } }
        } }
        item { Group(tr("effect_dynamics", "动态控制")) {
            SliderPreference(tr("compressor_ratio", "压缩比"), c.ratio.toFloat(), 1f..4f, "${decimal(c.ratio)}:1", !c.bypass, steps = 29, defaultValue = 1f) { value -> change { it.copy(ratio = value.toDouble()) } }
            SliderPreference(tr("compressor_threshold", "阈值"), c.thresholdDb.toFloat(), -30f..-3f, "${decimal(c.thresholdDb)} dB", !c.bypass, steps = 26, defaultValue = -12f) { value -> change { it.copy(thresholdDb = value.toDouble()) } }
            SliderPreference(tr("compressor_attack", "启动时间"), c.attackMs.toFloat(), 1f..100f, "${c.attackMs.roundToInt()} ms", !c.bypass, defaultValue = 20f) { value -> change { it.copy(attackMs = value.toDouble()) } }
            SliderPreference(tr("compressor_release", "释放时间"), c.releaseMs.toFloat(), 30f..1000f, "${c.releaseMs.roundToInt()} ms", !c.bypass, defaultValue = 160f) { value -> change { it.copy(releaseMs = value.toDouble()) } }
        } }
    }
    PresetDraftDialog(store, saving, onDismiss = { saving = false }, onSaved = { saving = false })
}

@Composable fun AudioPreferencesPage(padding: PaddingValues) {
    val app = LocalContext.current.applicationContext as AuraApplication
    val store = app.effects
    val auraMix by app.auraMix.state.collectAsState()
    val state by store.state.collectAsState()
    LaunchedEffect(state.fade) {
        app.auraMix.update { current -> current.copy(enabled = state.fade, mode = if (current.autoCalculate) btm.m.auraplayer.auramix.AuraMixMode.SMART else btm.m.auraplayer.auramix.AuraMixMode.TRADITIONAL) }
    }
    val transition = updateTransition(state.fade, label = "audio-fade-setting")
    val blur by transition.animateFloat(transitionSpec = { tween(220) }, label = "blur") { if (it) 0f else 8f }
    LazyColumn(pageContentModifier(), contentPadding = padding) {
        item { EffectsCard {
            SwitchPreference(tr("audio_fade", "音频淡入淡出"), tr("audio_fade_summary", "播放与暂停时平滑调整音量"), state.fade) {
                store.audio(fade = it)
                app.auraMix.update { current -> current.copy(enabled = it, mode = if (current.autoCalculate) btm.m.auraplayer.auramix.AuraMixMode.SMART else btm.m.auraplayer.auramix.AuraMixMode.TRADITIONAL) }
            }
            transition.AnimatedVisibility(visible = { it }, enter = fadeIn(tween(220)) + expandVertically(tween(220)), exit = fadeOut(tween(220)) + shrinkVertically(tween(220))) {
                Column {
                    SwitchPreference(tr("aura_mix_auto", "自动计算衔接时长"), tr("aura_mix_auto_summary", "根据歌曲开头、结尾和音量包络自动决定淡化方式"), auraMix.autoCalculate) {
                        app.auraMix.update { current -> current.copy(enabled = state.fade, autoCalculate = it, mode = if (it) btm.m.auraplayer.auramix.AuraMixMode.SMART else btm.m.auraplayer.auramix.AuraMixMode.TRADITIONAL) }
                    }
                    AnimatedVisibility(visible = !auraMix.autoCalculate, enter = fadeIn(tween(220)) + expandVertically(tween(220)), exit = fadeOut(tween(220)) + shrinkVertically(tween(220))) {
                        Box(Modifier.blur(blur.dp)) {
                            SliderPreference(tr("audio_fade_duration", "音频淡入淡出时长"), state.fadeMs.toFloat(), 100f..3000f, "${state.fadeMs} ms", steps = 28, defaultValue = 600f) { value ->
                                store.audio(ms = value.roundToInt())
                                app.auraMix.update { current -> current.copy(durationMs = value.toLong(), enabled = state.fade) }
                            }
                        }
                    }
                }
            }
            SwitchPreference(tr("audio_always_play", "始终播放"), tr("audio_always_play_summary", "当有其他内容播放时，在后台保持播放"), state.alwaysPlay) { store.audio(always = it) }
        } }
    }
}
