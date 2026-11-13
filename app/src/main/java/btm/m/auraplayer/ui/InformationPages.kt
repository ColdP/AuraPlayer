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
                    Preference(tr("github_repository", "GitHub Repository"), "github.com/ColdP/AuraPlayer") { openUrl(context, "https://github.com/ColdP/AuraPlayer") }
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
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        val width = (maxWidth - 18.dp).coerceAtMost(380.dp)
        val logoSize = width * (.94f / 3.91f) * 2.78f * .8f * .95f
        val textWidth = width * (2.97f / 3.91f) * .9f
        val textHeight = textWidth * (105f / 623f)
        val headerHeight = (logoSize + textHeight + 90.dp).coerceAtLeast(250.dp)
        AndroidView(factory = { HyperCeilerBrandView(it) }, update = { view ->
            view.configure(with(density) { logoSize.roundToPx() }, with(density) { textWidth.roundToPx() },
                "Aura Player | ${BuildConfig.VERSION_NAME}-${BuildConfig.BUILD_DATE}", versionColor, dark, onLogoClick, onVersionClick)
        }, modifier = Modifier.fillMaxWidth().height(headerHeight))
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
    else -> tr("open_source", "开源代码声明")
}

@Composable
fun LegalPage(page: String, padding: PaddingValues) {
    val context = LocalContext.current
    val lang = when (I18n.language) {
        "en" -> "en"
        "ja" -> "ja"
        else -> "zh"
    }
    if (page in setOf("agreement", "disclaimer", "privacy")) {
        val assetName = when (page) {
            "agreement" -> "user_agreement.txt"
            "disclaimer" -> "disclaimer.txt"
            else -> "privacy.txt"
        }
        val content = remember(assetName, lang) {
            runCatching {
                context.assets.open("legal/$lang/$assetName").bufferedReader().use { it.readText() }
            }.getOrElse {
                context.assets.open("legal/zh/$assetName").bufferedReader().use { it.readText() }
            }
        }
        LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            legalDocumentItems(content)
        }
        return
    }
    val text = when (page) {
        "license" -> remember { context.assets.open("licenses/AGPL-3.0-or-later.txt").bufferedReader().use { it.readText() } }
        else -> remember { context.assets.open("NOTICE").bufferedReader().use { it.readText() } + "\n\n" +
            context.assets.open("licenses/HyperMusicCover-NOTICE.txt").bufferedReader().use { it.readText() } }
    }
    LazyColumn(pageContentModifier(), contentPadding = padding) {
        item { LegalReadingCard { Text(text, style = MiuixTheme.textStyles.body2) } }
    }
}
