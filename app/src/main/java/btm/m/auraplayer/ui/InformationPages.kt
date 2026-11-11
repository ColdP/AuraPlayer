// SPDX-License-Identifier: AGPL-3.0-or-later
// HyperChanger About / Donate layouts and assets retained as requested.
package btm.m.auraplayer.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.BuildConfig
import btm.m.auraplayer.R
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Year

@Composable
private fun aboutCardColors() = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer.copy(
    alpha = if (MiuixTheme.colorScheme.surface.luminance() < .5f) .60f else .65f))

private fun systemProperty(name: String): String = runCatching {
    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
}.getOrDefault("")

@Composable
private fun DeviceInfoItem(title: String, summary: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MiuixTheme.textStyles.body1)
        Text(summary, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
fun AboutPage(model: AuraViewModel, padding: PaddingValues, open: (String) -> Unit) {
    val context = LocalContext.current
    val unavailable = tr("not_detected", "未检测到")
    val marketName = remember { systemProperty("ro.product.marketname").ifBlank { Build.MODEL.ifBlank { unavailable } } }
    val systemVersion = remember { systemProperty("ro.mi.os.version.incremental").removePrefix("OS").ifBlank { unavailable } }
    var taps by remember { mutableIntStateOf(0) }
    var confetti by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { AboutAdvancedMaterialHeader({}, { taps++; if (taps >= 5) { taps = 0; confetti++ } }) }
            item {
                SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, colors = aboutCardColors()) {
                    Row(Modifier.fillMaxWidth().clickable { openUrl(context, "https://coldp.github.io/OfficialSite") }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.btm_m_avatar), "btm_m", Modifier.size(52.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                        Column(Modifier.padding(start = 14.dp).weight(1f)) {
                            Text("btm_m", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
                            Text("coldp.github.io/OfficialSite", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 3.dp))
                        }
                        Image(MiuixIcons.Regular.ChevronForward, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary))
                    }
                }
            }
            item {
                SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, colors = aboutCardColors()) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        DeviceInfoItem(marketName, tr("device_model", "设备型号"))
                        DeviceInfoItem("${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})", tr("android_version", "Android 版本"))
                        DeviceInfoItem(systemVersion, tr("hyperos_version", "HyperOS 版本"))
                        DeviceInfoItem("Android 13+", tr("supported_version", "Aura Player 适配版本"))
                    }
                }
            }
            item {
                SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, colors = aboutCardColors()) {
                    Preference(tr("license", "LICENSE"), "AGPL-3.0-or-later") { open("license") }
                    Preference(tr("github_repository", "GitHub Repository"), "github.com/ColdP/HyperChanger") { openUrl(context, "https://github.com/ColdP/HyperChanger") }
                    Preference(tr("telegram_group", "Telegram 群组"), "t.me/HyperChanger") { openUrl(context, "https://t.me/HyperChanger") }
                    Preference(tr("qq_group", "QQ 群"), tr("qq_number", "群号: 429188055")) { openUrl(context, "https://qm.qq.com/q/rQhbQbiYLe") }
                }
            }
            item {
                SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, colors = aboutCardColors()) {
                    Preference(tr("user_agreement", "用户协议")) { open("agreement") }
                    Preference(tr("disclaimer", "免责声明")) { open("disclaimer") }
                    Preference(tr("privacy", "隐私政策")) { open("privacy") }
                    Preference(tr("open_source", "开源代码声明")) { open("opensource") }
                    Preference(tr("contributors", "贡献者")) { open("contributors") }
                }
            }
            item { Text("© ${Year.now().value} btm_m", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .56f), modifier = Modifier.padding(start = 12.dp)) }
        }
        if (confetti > 0) CompletionConfetti(confetti, Modifier.fillMaxSize(), pieceCount = 200, restingCountRange = 3..7, durationMillis = 7000)
    }
}

@Composable
private fun AboutAdvancedMaterialHeader(onLogoClick: () -> Unit, onVersionClick: () -> Unit) {
    val density = LocalDensity.current
    val dark = MiuixTheme.colorScheme.surface.luminance() < .5f
    val versionColor = MiuixTheme.colorScheme.onSurface.copy(alpha = .78f).toArgb()
    BoxWithConstraints(Modifier.fillMaxWidth().height(250.dp).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        val width = maxWidth - 18.dp
        val logoSize = width * (.94f / 3.91f) * 2.78f * .8f * .95f
        val textWidth = width * (2.97f / 3.91f)
        AndroidView(factory = { HyperCeilerBrandView(it) }, update = { view ->
            view.configure(with(density) { logoSize.roundToPx() }, with(density) { textWidth.roundToPx() },
                "Aura Player | ${BuildConfig.VERSION_NAME}-${BuildConfig.BUILD_DATE}", versionColor, dark, onLogoClick, onVersionClick)
        }, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun DonatePage(padding: PaddingValues) {
    val context = LocalContext.current
    val isDark = MiuixTheme.colorScheme.surface.luminance() < .5f
    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.btm_m_avatar), "btm_m", Modifier.size(72.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            Text("btm_m", style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        } }
        item { Group(tr("afdian", "爱发电")) { Preference(tr("support_afdian", "通过爱发电支持我"), tr("afdian_author", "爱发电：btm_m")) { openUrl(context, "https://afdian.com/a/btm_m") } } }
        item { Group(tr("wechat_reward", "微信赞赏码")) {
            Image(painterResource(if (isDark) R.drawable.mm_reward_darkmode else R.drawable.mm_reward_lightmode), tr("wechat_reward", "微信赞赏码"),
                Modifier.fillMaxWidth().aspectRatio(1f).padding(12.dp), contentScale = ContentScale.Fit)
        } }
    }
}

fun legalTitle(page: String): String = when (page) {
    "license" -> tr("license", "LICENSE")
    "agreement" -> tr("user_agreement", "用户协议")
    "privacy" -> tr("privacy", "隐私政策")
    "disclaimer" -> tr("disclaimer", "免责声明")
    "contributors" -> tr("contributors", "贡献者")
    else -> tr("open_source", "开源代码声明")
}

@Composable
fun LegalPage(page: String, padding: PaddingValues) {
    val context = LocalContext.current
    val text = when (page) {
        "license" -> remember { context.assets.open("licenses/AGPL-3.0-or-later.txt").bufferedReader().use { it.readText() } }
        "privacy" -> tr("privacy_body", "Aura Player 在本机读取音频媒体库，不上传本地音乐文件。默认通过网易云官方 HTTPS 页面登录，并直接连接网易云服务；选择自建 API 时，请求会发送到你配置的服务。登录 Cookie 使用 Android Keystore 加密保存。更改服务地址或退出登录会清除本地登录状态。歌曲封面与在线播放会请求相应的网络服务。在线歌词检索会向网易云、QQ、酷我、酷狗或 LrcLib 发送歌名、艺术家、专辑与时长，并使用在线歌曲 ID 检索 AMLL / TTML Hub。不会上传本地音频内容。请仅使用你信任的服务地址。")
        "agreement" -> tr("agreement_body", "请仅播放你有权访问的音乐内容。网易云音乐服务由第三方提供，其账号、地区与会员权限仍然适用。本应用不提供付费内容解锁与音乐下载功能。")
        "disclaimer" -> tr("disclaimer_body", "本软件按现状提供，不对第三方 API 的持续可用性作保证。音乐版权归相应权利人所有。请勿将本软件用于侵犯他人权利的行为。")
        "contributors" -> tr("contributors_body", "Aura Player：btm_m\n\n界面与玻璃组件：HyperChanger / btm_m\n首页结构：Retro Music / Hemanth Savarala 与贡献者\nMIUIX：Yukonga 与贡献者\nBackdrop：Kyant 与贡献者\n网易云 API：NeteaseCloudMusicApiEnhanced 与贡献者\n锁屏歌词获取、解析与动画：HyperMusicCover / zyl6932、HyperChanger / btm_m\n歌词匹配与解密：HyperLyrics Enhanced / juren233\n歌词解析依赖：Accompanist Lyrics Core\n封面取色：MaterialKolor\n播放器图标：Apple Music / Apple Inc.（队列选中态与无底收藏图标已适配）")
        else -> remember { context.assets.open("NOTICE").bufferedReader().use { it.readText() } + "\n\n" +
            context.assets.open("licenses/HyperMusicCover-NOTICE.txt").bufferedReader().use { it.readText() } }
    }
    LazyColumn(pageContentModifier(), contentPadding = padding) {
        item { SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(20.dp)) { Text(text, style = MiuixTheme.textStyles.body2) } }
    }
}
