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
import androidx.compose.foundation.lazy.LazyListScope
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
import btm.m.auraplayer.data.CloudAccount
import btm.m.auraplayer.data.HomeBannerTarget
import btm.m.auraplayer.data.LibraryFilter
import btm.m.auraplayer.data.NAVIGATION_OFFSET_DP_RANGE
import btm.m.auraplayer.data.homeContentSources
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
fun SettingsPage(model: AuraViewModel, padding: PaddingValues, open: (String) -> Unit) {
    val value = model.settings.value
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Group(tr("appearance", "外观")) {
                Preference(tr("theme_color", "主题与颜色")) { open("theme") }
                val navOptions = listOf(
                    tr("nav_hyper", "HyperOS 悬浮导航栏"),
                    tr("nav_harmony", "HarmonyOS 悬浮导航栏"),
                    tr("nav_liquid", "液态玻璃导航栏"),
                    tr("nav_material", "Material 导航栏"),
                    tr("nav_one", "One UI 导航栏"),
                )
                val navValues = listOf("hyper_os_floating", "harmony_os_floating", "liquid_glass", "material", "one")
                GlassDropdownPreference(tr("navigation_style", "底部导航栏样式"),
                    navOptions,
                    navValues.indexOf(value.navigation).coerceAtLeast(0),
                    backdrop = LocalToolbarBackdrop.current) { index -> model.updateSettings { it.copy(navigation = navValues[index]) } }
                BlurAnimatedVisibility(visible = value.navigation !in setOf("material", "one"), label = "nav-material-setting") {
                    Preference(tr("nav_material_title", "底部导航栏材质参数调整")) { open("navigation_material") }
                }
                SliderPreference(tr("nav_vertical_offset", "底部导航栏垂直偏移"), value.navigationOffsetDp.toFloat(),
                    NAVIGATION_OFFSET_DP_RANGE.first.toFloat()..NAVIGATION_OFFSET_DP_RANGE.last.toFloat(),
                    "${value.navigationOffsetDp} dp", steps = NAVIGATION_OFFSET_DP_RANGE.last - NAVIGATION_OFFSET_DP_RANGE.first - 1,
                    defaultValue = 0f, centerValue = 0f) { offset ->
                    model.updateSettings { it.copy(navigationOffsetDp = offset.roundToInt()) }
                }
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
            Group(tr("features", "功能")) {
                SwitchPreference(tr("disable_cloud", "关闭所有云端功能"),
                    tr("disable_cloud_summary", "开启后隐藏并关闭网易云音乐、QQ音乐等所有云端功能，成为纯本地播放器"),
                    checked = value.cloudDisabled, onCheckedChange = { checked -> model.updateSettings { it.copy(cloudDisabled = checked) } })
                BlurAnimatedVisibility(visible = !value.cloudDisabled, label = "cloud-settings-entry") {
                    Preference(tr("cloud", "云端")) { open("cloud_settings") }
                }
                Preference(tr("local", "本地")) { open("local_settings") }
                Preference(tr("artist_and_album", "艺术家与专辑")) { open("artist_album_settings") }
                GlassDropdownPreference(tr("select_language", "选择语言"), listOf(tr("chinese", "简体中文"), tr("english", "English")),
                    if (value.language == "en") 1 else 0, backdrop = LocalToolbarBackdrop.current) { index ->
                    model.updateSettings { it.copy(language = if (index == 0) "zh" else "en") }; I18n.language = model.settings.value.language
                }
                val llmState by model.app.llmTranslation.state.collectAsState()
                Preference(
                    tr("llm_translation", "大模型翻译"),
                    summary = if (llmState.enabled) {
                        llmState.activeModel().ifBlank { tr("llm_translation_enabled_summary", "使用兼容大模型智能翻译歌词") }
                    } else null,
                ) { open("llm_translation") }
            }
        }
        item {
            Group(tr("playback", "播放")) {
                Preference(tr("audio_effects", "音效")) { open("audio_effects") }
                Preference(tr("now_playing_page", "正在播放界面")) { open("now_playing_settings") }
                Preference(tr("audio_settings", "音频")) { open("audio_settings") }
                if (btm.m.auraplayer.rear.RearScreenDevice.isSupported()) {
                    Preference(tr("rear_screen", "背屏")) { open("rear_screen") }
                }
            }
        }
        item {
            Group(tr("information", "信息")) {
                Preference(tr("about", "关于")) { open("about") }
                Preference(tr("software_update", "软件更新")) { open("software_update") }
                Preference(tr("donate", "捐赠")) { open("donate") }
            }
        }
    }
}

@Composable
internal fun BlacklistDialog(model: AuraViewModel, close: () -> Unit) {
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
                val styleOptions = listOf(tr("style_hyper", "Hyper"), tr("style_material", "Material"), tr("style_one", "One"))
                val styleValues = listOf("hyper", "material", "one")
                GlassDropdownPreference(tr("interface_style", "界面风格"), styleOptions,
                    styleValues.indexOf(value.uiStyle).coerceAtLeast(0), backdrop = LocalToolbarBackdrop.current) { index ->
                    model.updateSettings { it.copy(uiStyle = styleValues[index]) }
                }
                GlassDropdownPreference(tr("theme_mode", "主题模式"), listOf(tr("follow_system", "跟随系统"), tr("light_mode", "浅色模式"), tr("dark_mode", "深色模式")),
                    listOf("system", "light", "dark").indexOf(value.theme).coerceAtLeast(0), backdrop = LocalToolbarBackdrop.current) { index ->
                    model.updateSettings { it.copy(theme = listOf("system", "light", "dark")[index]) }
                }
                SwitchPreference(
                    tr("disable_advanced_material", "关闭高级材质"),
                    tr("disable_advanced_material_summary", "开启后，玻璃、流光等高级材质（包括关于界面）自动回退为纯色"),
                    value.disableAdvancedMaterial,
                ) { checked -> model.updateSettings { it.copy(disableAdvancedMaterial = checked) } }
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

private class NeteaseCloudSectionState(
    endpointState: MutableState<String>,
    countryState: MutableState<String>,
    phoneState: MutableState<String>,
    captchaState: MutableState<String>,
    advancedState: MutableState<Boolean>,
    val launchOfficialLogin: () -> Unit,
) {
    var endpoint by endpointState
    var country by countryState
    var phone by phoneState
    var captcha by captchaState
    var advanced by advancedState
}

@Composable
private fun rememberNeteaseCloudSectionState(model: AuraViewModel): NeteaseCloudSectionState {
    val context = LocalContext.current
    val endpoint = rememberSaveable { mutableStateOf(model.app.endpoint) }
    val country = rememberSaveable { mutableStateOf("86") }
    val phone = rememberSaveable { mutableStateOf("") }
    // Verification codes are transient and never persisted.
    val captcha = remember { mutableStateOf("") }
    val advanced = rememberSaveable { mutableStateOf(false) }
    val officialLoginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val cookie = result.data?.getStringExtra(OfficialLoginActivity.EXTRA_COOKIE).orEmpty()
            if (cookie.isNotBlank()) model.completeOfficialLogin(cookie)
        }
    }
    return remember(model, context, officialLoginLauncher) {
        NeteaseCloudSectionState(
            endpointState = endpoint,
            countryState = country,
            phoneState = phone,
            captchaState = captcha,
            advancedState = advanced,
            launchOfficialLogin = {
                if (model.app.endpoint.isNotBlank()) model.useOfficialService()
                officialLoginLauncher.launch(OfficialLoginActivity.createIntent(context, clearCookies = false))
            },
        )
    }
}

private class QqCloudSectionState(
    endpointState: MutableState<String>,
    uinState: MutableState<String>,
    cookieState: MutableState<String>,
    advancedState: MutableState<Boolean>,
    val launchOfficialLogin: (clearCookies: Boolean) -> Unit,
) {
    var endpoint by endpointState
    var uin by uinState
    var cookie by cookieState
    var advanced by advancedState
}

@Composable
private fun rememberQqCloudSectionState(model: AuraViewModel): QqCloudSectionState {
    val context = LocalContext.current
    val endpoint = rememberSaveable { mutableStateOf(model.app.qqEndpoint) }
    val uin = rememberSaveable { mutableStateOf(model.app.qqUin) }
    val cookie = remember { mutableStateOf(model.app.qqCookie) }
    val advanced = rememberSaveable { mutableStateOf(model.app.qqEndpoint.isNotBlank()) }
    val qqAccount = model.qqAccount
    LaunchedEffect(qqAccount, model.app.qqCookie, model.app.qqUin) {
        cookie.value = model.app.qqCookie
        uin.value = model.app.qqUin
    }
    val officialLoginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val sessionCookie = result.data?.getStringExtra(OfficialLoginActivity.EXTRA_COOKIE).orEmpty()
            if (sessionCookie.isNotBlank()) {
                cookie.value = sessionCookie
                model.completeQqOfficialLogin(sessionCookie)
            }
        }
    }
    return remember(model, context, officialLoginLauncher) {
        QqCloudSectionState(
            endpointState = endpoint,
            uinState = uin,
            cookieState = cookie,
            advancedState = advanced,
            launchOfficialLogin = { clearCookies ->
                if (!clearCookies && model.app.qqEndpoint.isNotBlank()) {
                    model.useQqOfficialService()
                    endpoint.value = ""
                }
                officialLoginLauncher.launch(OfficialLoginActivity.createIntent(context, clearCookies = clearCookies, provider = "qq"))
            },
        )
    }
}

/** 云端设置二级页面，按「通用 / 网易云音乐 / QQ音乐」分页承载全部云端相关配置。 */
@Composable
fun CloudSettingsPage(
    model: AuraViewModel,
    padding: PaddingValues,
    selectedTab: Int,
    open: (String) -> Unit,
) {
    val context = LocalContext.current
    val neteaseState = rememberNeteaseCloudSectionState(model)
    val qqState = rememberQqCloudSectionState(model)
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (selectedTab) {
            0 -> cloudGeneralItems(model)
            1 -> neteaseCloudItems(model, context, neteaseState, open, includeCachePreference = false)
            2 -> qqCloudItems(model, context, qqState, open)
        }
    }
}

private fun LazyListScope.cloudGeneralItems(model: AuraViewModel) {
    item(key = "cloud_general_display") {
        val value = model.settings.value
        Group(tr("cloud_general_display", "内容与显示")) {
            val cloudSources = listOf("netease", "qq", "all")
            GlassDropdownPreference(
                tr("cloud_source", "云端内容显示"),
                listOf(tr("netease", "网易云音乐"), tr("qq_music", "QQ音乐"), tr("cloud_source_all", "全部显示")),
                cloudSources.indexOf(value.cloudSource).coerceAtLeast(0),
                backdrop = LocalToolbarBackdrop.current,
            ) { index ->
                model.updateSettings { it.copy(cloudSource = cloudSources[index]) }
            }
            val searchSources = listOf("netease", "qq")
            GlassDropdownPreference(
                tr("cloud_search_source", "云端搜索内容"),
                listOf(tr("netease", "网易云音乐"), tr("qq_music", "QQ音乐")),
                searchSources.indexOf(value.cloudSearchSource).coerceAtLeast(0),
                backdrop = LocalToolbarBackdrop.current,
            ) { index ->
                model.updateSettings { it.copy(cloudSearchSource = searchSources[index]) }
            }
            GlassDropdownPreference(
                tr("home_content", "首页内容"),
                listOf(tr("home_content_local", "本地"), tr("netease", "网易云音乐"), tr("qq_music", "QQ音乐")),
                homeContentSources.indexOf(value.homeContent).coerceAtLeast(0),
                backdrop = LocalToolbarBackdrop.current,
            ) { index ->
                model.updateSettings { it.copy(homeContent = homeContentSources[index]) }
            }
        }
    }
    item(key = "cloud_general_cache") {
        Group(tr("cache", "缓存")) {
            CloudCachePreference(model)
        }
    }
}

private fun LazyListScope.neteaseCloudItems(
    model: AuraViewModel,
    context: android.content.Context,
    state: NeteaseCloudSectionState,
    open: (String) -> Unit,
    includeCachePreference: Boolean,
) {
    val account = model.account
    if (account == null) item(key = "netease_official_login") {
        SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
            Preference(tr("official_login", "网易云音乐官方登录"), tr("official_login_summary", "直接登录，无需配置服务器或 API 地址")) {
                state.launchOfficialLogin()
            }
            if (model.savedAccounts.isNotEmpty()) {
                Preference(tr("switch_user", "切换账号")) { open("switch_user") }
            }
        }
    }
    item(key = "netease_account_or_phone") {
        if (account != null) Group(tr("account", "账号")) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(account.avatar, Modifier.size(60.dp), circular = true)
                Column(Modifier.padding(start = 16.dp)) { Text(account.nickname); Text("UID ${account.userId}", style = MiuixTheme.textStyles.body2) }
            }
            Preference(tr("switch_user", "切换账号")) { open("switch_user") }
        } else if (model.app.endpoint.isNotBlank()) Group(tr("phone_login", "手机号验证码登录")) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                AuraTextField(state.country, { state.country = it.filter(Char::isDigit).take(4) }, label = tr("country_code", "国际区号"), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                AuraTextField(state.phone, { state.phone = it.filter(Char::isDigit).take(20) }, label = tr("phone", "手机号"), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                GlassDialogButton({ model.sendCaptcha(state.phone, state.country) }, Modifier.fillMaxWidth(),
                    enabled = model.smsRemaining == 0 && !model.cloudBusy && state.phone.length >= 5 && state.country.isNotBlank() && model.app.endpoint.isNotBlank()) {
                    Text(if (model.smsRemaining > 0) "${model.smsRemaining}s" else tr("send_captcha", "获取验证码"))
                }
                AuraTextField(state.captcha, { state.captcha = it.filter(Char::isDigit).take(8) }, label = tr("captcha", "验证码"), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                GlassDialogButton({ model.login(state.phone, state.country, state.captcha); state.captcha = "" }, Modifier.fillMaxWidth(),
                    enabled = !model.cloudBusy && state.phone.length >= 5 && state.captcha.length >= 4 && state.country.isNotBlank() && model.app.endpoint.isNotBlank(),
                    colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (model.cloudBusy) tr("connecting", "正在连接…") else tr("login", "登录")) }
            }
        }
    }
    item(key = "netease_features") {
        SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
            Preference(tr("downloaded_music", "已下载的音乐"), onClick = { open("downloads") })
            if (includeCachePreference) CloudCachePreference(model)
            SwitchPreference(tr("heartbeat_mode", "心动模式"), checked = model.settings.value.heartbeatEnabled,
                onCheckedChange = { enabled -> model.updateSettings { it.copy(heartbeatEnabled = enabled) } })
            SwitchPreference(
                title = tr("netease_scrobble", "同步听歌数据至网易云音乐"),
                summary = tr("netease_scrobble_summary", "播放网易云歌曲时自动上报官方听歌记录，计入最近播放、听歌排行与年度报告"),
                checked = model.settings.value.neteaseScrobbleEnabled,
                onCheckedChange = { enabled -> model.updateSettings { it.copy(neteaseScrobbleEnabled = enabled) } },
            )
        }
    }
    item(key = "netease_banner") {
        Group(tr("home_banner_settings", "首页横幅")) {
            HomeBannerSettings(model, HomeBannerTarget.NETEASE)
        }
    }
    item(key = "netease_advanced_toggle") {
        SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
            Preference(tr("advanced_connection", "高级连接设置"), tr("advanced_connection_summary", "可选：使用自建 API 服务")) { state.advanced = !state.advanced }
        }
    }
    if (state.advanced) item(key = "netease_connection") {
        Group(tr("connection", "连接")) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (model.app.endpoint.isNotBlank()) Preference(tr("use_official", "使用网易云官方连接")) {
                    model.useOfficialService(); state.endpoint = ""; state.advanced = false
                }
                AuraTextField(state.endpoint, { state.endpoint = it }, label = tr("api_address", "网易云 API 服务地址"), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                GlassDialogButton({ model.configureApi(state.endpoint) }, Modifier.fillMaxWidth(), enabled = state.endpoint.isNotBlank() && !model.cloudBusy) { Text(tr("save_address", "保存服务地址")) }
                Preference(tr("api_project", "查看 API 开源项目与部署说明")) { openUrl(context, "https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced") }
            }
        }
    }
    if (model.loginMessage.isNotBlank()) item(key = "netease_message") {
        Text(model.loginMessage, color = MiuixTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
    }
}

private fun LazyListScope.qqCloudItems(
    model: AuraViewModel,
    context: android.content.Context,
    state: QqCloudSectionState,
    open: (String) -> Unit,
) {
    val qqAccount = model.qqAccount
    if (qqAccount == null) item(key = "qq_official_login") {
        SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
            Preference(tr("qq_official_login", "QQ音乐官方登录"), tr("qq_official_login_summary", "直接登录，无需配置服务器或 API 地址")) {
                state.launchOfficialLogin(false)
            }
            if (model.savedQqAccounts.isNotEmpty()) Preference(tr("switch_user", "切换账号")) { open("qq_switch_user") }
        }
    }
    if (qqAccount != null) item(key = "qq_account") {
        Group(tr("account", "账号")) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(qqAccountAvatar(qqAccount), Modifier.size(60.dp), circular = true)
                Column(Modifier.padding(start = 16.dp)) {
                    Text(qqAccount.nickname)
                    if (qqAccount.userId > 0L) Text("QQ ${qqAccount.userId}", style = MiuixTheme.textStyles.body2)
                }
            }
            Preference(tr("switch_user", "切换账号")) { open("qq_switch_user") }
            Preference(tr("qq_relogin", "重新登录")) {
                state.launchOfficialLogin(true)
            }
            Preference(tr("logout", "退出登录")) {
                state.cookie = ""
                model.logoutQq()
            }
        }
    }
    item(key = "qq_banner") {
        Group(tr("home_banner_settings", "首页横幅")) {
            HomeBannerSettings(model, HomeBannerTarget.QQ)
        }
    }
    item(key = "qq_advanced_toggle") {
        SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
            Preference(tr("advanced_connection", "高级连接设置"), tr("advanced_connection_summary", "可选：使用自建 API 服务")) { state.advanced = !state.advanced }
        }
    }
    if (state.advanced) item(key = "qq_connection") {
        Group(tr("connection", "连接")) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (model.app.qqEndpoint.isNotBlank()) Preference(tr("qq_use_official", "使用QQ音乐官方连接")) {
                    model.useQqOfficialService(); state.endpoint = ""; state.advanced = false
                }
                AuraTextField(state.endpoint, { state.endpoint = it }, label = tr("qq_api_address", "QQ音乐 API 服务地址（可选）"), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                AuraTextField(state.uin, { state.uin = it.filter(Char::isDigit).take(12) }, label = tr("qq_number", "QQ 号（用于读取歌单）"), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                AuraTextField(state.cookie, { state.cookie = it }, label = tr("qq_cookie", "y.qq.com Cookie（可选，用于 VIP 歌曲）"), singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                GlassDialogButton({ model.configureQq(state.endpoint, state.uin, state.cookie) }, Modifier.fillMaxWidth(),
                    enabled = state.endpoint.isNotBlank() && !model.qqBusy,
                    colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("save_address", "保存服务地址")) }
                Preference(tr("api_project", "查看 API 开源项目与部署说明")) { openUrl(context, "https://github.com/jsososo/QQMusicApi") }
            }
        }
    }
    if (model.qqMessage.isNotBlank()) item(key = "qq_message") {
        Text(model.qqMessage, color = MiuixTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
    }
}

@Composable
fun LoginPage(model: AuraViewModel, padding: PaddingValues, open: (String) -> Unit) {
    val context = LocalContext.current
    val state = rememberNeteaseCloudSectionState(model)
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        neteaseCloudItems(model, context, state, open, includeCachePreference = true)
    }
}

/** QQ Music secondary page: mirrors NetEase LoginPage with official WebView login and optional self-hosted QQMusicApi. */
@Composable
fun QqMusicPage(model: AuraViewModel, padding: PaddingValues, open: (String) -> Unit) {
    val context = LocalContext.current
    val state = rememberQqCloudSectionState(model)
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        qqCloudItems(model, context, state, open)
    }
}

internal fun resolveQqAvatar(userId: Long, avatar: String): String =
    avatar.ifBlank { if (userId > 0L) "https://q.qlogo.cn/g?b=qq&nk=$userId&s=640" else "" }

internal fun qqAccountAvatar(account: CloudAccount?): String =
    account?.let { resolveQqAvatar(it.userId, it.avatar) }.orEmpty()

fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
