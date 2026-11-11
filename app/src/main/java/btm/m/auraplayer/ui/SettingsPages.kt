package btm.m.auraplayer.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.data.LibraryFilter
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
fun SettingsPage(model: AuraViewModel, padding: PaddingValues, open: (String) -> Unit) {
    val value = model.settings.value
    var blacklist by remember { mutableStateOf(false) }
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Group(tr("appearance", "外观")) {
                Preference(tr("theme_color", "主题与颜色")) { open("theme") }
                GlassDropdownPreference(tr("navigation_style", "底部导航栏样式"),
                    listOf(tr("nav_hyper", "HyperOS 悬浮导航栏"), tr("nav_harmony", "HarmonyOS 悬浮导航栏"), tr("nav_liquid", "液态玻璃导航栏")),
                    listOf("hyper_os_floating", "harmony_os_floating", "liquid_glass").indexOf(value.navigation).coerceAtLeast(0),
                    backdrop = LocalToolbarBackdrop.current) { index -> model.updateSettings { it.copy(navigation = listOf("hyper_os_floating", "harmony_os_floating", "liquid_glass")[index]) } }
                GlassDropdownPreference(tr("navigation_labels", "底部导航栏标签显示方式"),
                    listOf(tr("icons_and_text", "图标和文本"), tr("icons_only", "仅图标"), tr("text_only", "仅文本")),
                    listOf("icon_and_text", "icon_only", "text_only").indexOf(value.labels).coerceAtLeast(0), backdrop = LocalToolbarBackdrop.current) { index ->
                    model.updateSettings { it.copy(labels = listOf("icon_and_text", "icon_only", "text_only")[index]) }
                }
                SwitchPreference(tr("predictive_back", "预测性返回动画"), checked = value.predictive, onCheckedChange = { checked -> model.updateSettings { it.copy(predictive = checked) } })
                SliderPreference(tr("back_maximum", "预测性返回动画最大进度"), value.maxBackProgress, .1f..1f,
                    "${(value.maxBackProgress * 100).roundToInt()}%", value.predictive, steps = 89, defaultValue = .9f) { progress -> model.updateSettings { it.copy(maxBackProgress = progress) } }
            }
        }
        item {
            Group(tr("settings", "设置")) {
                Preference(tr("netease", "网易云音乐"), model.account?.nickname ?: tr("login_summary", "通过网易云官方页面登录")) { open("login") }
                Preference(tr("blacklist", "黑名单"), if (value.whitelist) tr("blacklist_disabled", "白名单已开启，黑名单暂不生效") else
                    "${value.blacklist.size} ${tr("folder_count", "个文件夹")}", enabled = !value.whitelist) { blacklist = true }
                SwitchPreference(tr("whitelist", "白名单"), tr("whitelist_summary", "仅显示 Music 目录下的音乐"), value.whitelist) { checked -> model.updateSettings { it.copy(whitelist = checked) } }
                SwitchPreference(tr("filter_duration", "筛选歌曲时长"), checked = value.durationFilter, onCheckedChange = { checked -> model.updateSettings { it.copy(durationFilter = checked) } })
                AnimatedVisibility(value.durationFilter) {
                    SliderPreference(tr("minimum_duration", "最短歌曲时长"), value.minimumSeconds.toFloat(), 0f..60f, "${value.minimumSeconds}s", steps = 59) { seconds ->
                        model.updateSettings { it.copy(minimumSeconds = seconds.roundToInt()) }
                    }
                }
                GlassDropdownPreference(tr("select_language", "选择语言"), listOf(tr("chinese", "简体中文"), tr("english", "English")),
                    if (value.language == "en") 1 else 0, backdrop = LocalToolbarBackdrop.current) { index ->
                    model.updateSettings { it.copy(language = if (index == 0) "zh" else "en") }; I18n.language = model.settings.value.language
                }
            }
        }
        item {
            Group(tr("information", "信息")) {
                Preference(tr("about", "关于")) { open("about") }
                Preference(tr("donate", "捐赠")) { open("donate") }
            }
        }
    }
    if (blacklist) BlacklistDialog(model) { blacklist = false }
}

@Composable
private fun BlacklistDialog(model: AuraViewModel, close: () -> Unit) {
    val context = LocalContext.current
    var folders by remember { mutableStateOf(model.settings.value.blacklist) }
    var confirmClear by remember { mutableStateOf(false) }
    var folderError by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrDefault("")
            val path = id.substringAfter(':', "")
            val volume = id.substringBefore(':').let { if (it == "primary") "external_primary" else it.lowercase() }
            if (path.isNotBlank() && uri.authority == "com.android.externalstorage.documents") {
                folders = folders + "$volume:${LibraryFilter.normalize(path)}"
                saved = false
                folderError = ""
            } else folderError = tr("unsupported_folder", "请选择手机或存储卡中的音乐文件夹")
        }
    }
    WindowDialog(true, title = tr("blacklist", "黑名单"), summary = tr("blacklist_summary", "媒体库中将会隐藏黑名单文件夹中的内容"),
        titleTextAlign = TextAlign.Start, summaryTextAlign = TextAlign.Start, onDismissRequest = close) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
            if (folders.isEmpty()) item { Text(tr("blacklist_empty", "尚未添加文件夹"), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(vertical = 18.dp)) }
            items(folders.sorted()) { folder ->
                val volume = folder.substringBefore(':', "")
                val path = folder.substringAfter(':', folder)
                val storage = if (volume == "external_primary") tr("internal_storage", "内部存储") else tr("storage_card", "存储卡")
                BasicComponent(title = path, summary = storage, onClick = {}, endActions = {
                    IconButton(onClick = { folders = folders - folder; saved = false }) { Text("×") }
                })
            }
        }
        if (folderError.isNotBlank()) Text(folderError, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton({ confirmClear = true }, Modifier.weight(1f), enabled = folders.isNotEmpty()) { Text(tr("clear", "清空")) }
            GlassDialogButton({ picker.launch(null) }, Modifier.weight(1f)) { Text(tr("add", "添加")) }
        }
        GlassDialogButton({ model.updateSettings { it.copy(blacklist = folders) }; saved = true }, Modifier.fillMaxWidth().padding(top = 12.dp), colors = ButtonDefaults.buttonColorsPrimary()) {
            Text(if (saved) tr("saved", "已保存") else tr("save", "保存"))
        }
        GlassDialogButton({ model.updateSettings { it.copy(blacklist = folders) }; close() }, Modifier.fillMaxWidth().padding(top = 10.dp)) { Text(tr("done", "完成")) }
    }
    WindowDialog(confirmClear, title = tr("clear_blacklist", "清空黑名单？"), summary = tr("clear_blacklist_summary", "保存后，这些文件夹中的音乐将重新出现在媒体库"), onDismissRequest = { confirmClear = false }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton({ confirmClear = false }, Modifier.weight(1f)) { Text(tr("cancel", "取消")) }
            GlassDialogButton({ folders = emptySet(); saved = false; confirmClear = false }, Modifier.weight(1f)) { Text(tr("clear", "清空")) }
        }
    }
}

@Composable
fun ThemePage(model: AuraViewModel, padding: PaddingValues) {
    val value = model.settings.value
    DisposableEffect(Unit) { onDispose { model.settings.previewColor = null } }
    val colorIds = listOf("system") + colorPresets.map { it.first } + "custom"
    val colorNames = listOf(tr("follow_system", "跟随系统")) + colorPresets.map { tr("color_${it.first}", it.second) } + tr("custom", "自定义")
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Group(tr("theme", "主题")) {
                GlassDropdownPreference(tr("theme_mode", "主题模式"), listOf(tr("follow_system", "跟随系统"), tr("light_mode", "浅色模式"), tr("dark_mode", "深色模式")),
                    listOf("system", "light", "dark").indexOf(value.theme).coerceAtLeast(0), backdrop = LocalToolbarBackdrop.current) { index ->
                    model.updateSettings { it.copy(theme = listOf("system", "light", "dark")[index]) }
                }
            }
        }
        item {
            Group(tr("colors", "颜色")) {
                SwitchPreference(tr("monet", "Monet 取色"), tr("monet_summary", "使用 Miuix 动态颜色生成主题色"), value.monet) { checked -> model.updateSettings { it.copy(monet = checked) } }
                AnimatedVisibility(value.monet,
                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f)) {
                    Column {
                        GlassDropdownPreference(tr("color_theme", "颜色主题"), colorNames, colorIds.indexOf(value.color).coerceAtLeast(0), backdrop = LocalToolbarBackdrop.current) { index ->
                            model.settings.previewColor = null
                            model.updateSettings { it.copy(color = colorIds[index]) }
                        }
                        GlassDropdownPreference(tr("palette", "调色盘"), listOf(tr("palette_tonal", "柔和彩调"), tr("palette_neutral", "中性"), tr("palette_vibrant", "鲜艳"),
                            tr("palette_expressive", "丰富表现"), tr("palette_rainbow", "彩虹"), tr("palette_fruit", "水果沙拉"), tr("palette_mono", "单色"), tr("palette_fidelity", "保真"), tr("palette_content", "内容")),
                            value.palette.coerceIn(0, 8), backdrop = LocalToolbarBackdrop.current) { index -> model.updateSettings { it.copy(palette = index) } }
                        GlassDropdownPreference(tr("color_spec", "色彩规范"), listOf(tr("spec_2021", "经典（2021）"), tr("spec_2025", "丰富表现（2025）")),
                            value.colorSpec.coerceIn(0, 1), backdrop = LocalToolbarBackdrop.current) { index -> model.updateSettings { it.copy(colorSpec = index) } }
                        AnimatedVisibility(value.color == "custom",
                            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f)) {
                            ThemeSeedColorPreference(tr("choose_color", "选择颜色"), model.settings.previewColor ?: value.customColor,
                                onPreviewColor = { model.settings.previewColor = it },
                                onDismissPreview = { model.settings.previewColor = null },
                                onColorChange = { color -> model.updateSettings { it.copy(customColor = color, color = "custom") } })
                        }
                    }
                }
            }
        }
        if (value.monet && value.colorSpec == 1) item {
            SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(16.dp)) {
                Text(tr("spec_summary", "2025 规范适用于柔和彩调、中性、鲜艳和丰富表现，其余调色盘使用 2021 规范。"), style = MiuixTheme.textStyles.body2)
            }
        }
    }

}

@Composable
fun LoginPage(model: AuraViewModel, padding: PaddingValues) {
    val context = LocalContext.current
    var endpoint by rememberSaveable { mutableStateOf(model.app.endpoint) }
    var country by rememberSaveable { mutableStateOf("86") }
    var phone by rememberSaveable { mutableStateOf("") }
    // Verification codes are transient and never persisted.
    var captcha by remember { mutableStateOf("") }
    var confirmLogout by remember { mutableStateOf(false) }
    val account = model.account
    var advanced by rememberSaveable { mutableStateOf(false) }
    var showingWeb by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(account) { if (account != null) showingWeb = false }
    androidx.activity.compose.BackHandler(showingWeb) { showingWeb = false }
    if (showingWeb) {
        OfficialLoginView(padding, model.cloudBusy, model::completeOfficialLogin)
        return
    }
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (account == null) item {
            SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                Preference(tr("official_login", "网易云音乐官方登录"), tr("official_login_summary", "直接登录，无需配置服务器或 API 地址")) {
                    if (model.app.endpoint.isNotBlank()) model.useOfficialService()
                    showingWeb = true
                }
            }
        }
        item {
            if (account != null) Group(tr("account", "账号")) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(account.avatar, Modifier.size(60.dp), circular = true)
                    Column(Modifier.padding(start = 16.dp)) { Text(account.nickname); Text("UID ${account.userId}", style = MiuixTheme.textStyles.body2) }
                }
                Preference(tr("logout", "退出登录")) { confirmLogout = true }
            } else if (model.app.endpoint.isNotBlank()) Group(tr("phone_login", "手机号验证码登录")) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TextField(country, { country = it.filter(Char::isDigit).take(4) }, label = tr("country_code", "国际区号"), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                    TextField(phone, { phone = it.filter(Char::isDigit).take(20) }, label = tr("phone", "手机号"), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                    GlassDialogButton({ model.sendCaptcha(phone, country) }, Modifier.fillMaxWidth(),
                        enabled = model.smsRemaining == 0 && !model.cloudBusy && phone.length >= 5 && country.isNotBlank() && model.app.endpoint.isNotBlank()) {
                        Text(if (model.smsRemaining > 0) "${model.smsRemaining}s" else tr("send_captcha", "获取验证码"))
                    }
                    TextField(captcha, { captcha = it.filter(Char::isDigit).take(8) }, label = tr("captcha", "验证码"), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                    GlassDialogButton({ model.login(phone, country, captcha); captcha = "" }, Modifier.fillMaxWidth(),
                        enabled = !model.cloudBusy && phone.length >= 5 && captcha.length >= 4 && country.isNotBlank() && model.app.endpoint.isNotBlank(),
                        colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (model.cloudBusy) tr("connecting", "正在连接…") else tr("login", "登录")) }
                }
            }
        }
        item {
            SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                CloudCachePreference(model)
            }
        }
        item {
            SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                Preference(tr("advanced_connection", "高级连接设置"), tr("advanced_connection_summary", "可选：使用自建 API 服务")) { advanced = !advanced }
            }
        }
        if (advanced) item {
            Group(tr("connection", "连接")) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (model.app.endpoint.isNotBlank()) Preference(tr("use_official", "使用网易云官方连接")) {
                        model.useOfficialService(); endpoint = ""; advanced = false
                    }
                    TextField(endpoint, { endpoint = it }, label = tr("api_address", "网易云 API 服务地址"), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                    GlassDialogButton({ model.configureApi(endpoint) }, Modifier.fillMaxWidth(), enabled = endpoint.isNotBlank() && !model.cloudBusy) { Text(tr("save_address", "保存服务地址")) }
                    Preference(tr("api_project", "查看 API 开源项目与部署说明")) { openUrl(context, "https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced") }
                }
            }
        }
        if (model.loginMessage.isNotBlank()) item { Text(model.loginMessage, color = MiuixTheme.colorScheme.primary, modifier = Modifier.padding(16.dp)) }
    }
    WindowDialog(confirmLogout, title = tr("logout_confirm", "退出网易云音乐？"), onDismissRequest = { confirmLogout = false }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton({ confirmLogout = false }, Modifier.weight(1f)) { Text(tr("cancel", "取消")) }
            GlassDialogButton({ model.logout(); confirmLogout = false }, Modifier.weight(1f)) { Text(tr("logout", "退出登录")) }
        }
    }
}

fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
