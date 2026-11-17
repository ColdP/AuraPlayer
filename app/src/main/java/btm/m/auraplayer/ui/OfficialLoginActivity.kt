package btm.m.auraplayer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import btm.m.auraplayer.AuraApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController

/** Hosts NetEase's official HTTPS login WebView outside Compose Backdrop offscreen layers. */
class OfficialLoginActivity : ComponentActivity() {
    companion object {
        const val EXTRA_CLEAR_COOKIES = "clear_cookies"
        const val EXTRA_COOKIE = "cookie"
        const val EXTRA_PROVIDER = "provider"
        private const val LOGIN_URL = "https://music.163.com/m/login"
        private const val QQ_LOGIN_URL = "https://y.qq.com/"
        private val SESSION_COOKIE_KEYS = setOf("MUSIC_U", "__csrf", "MUSIC_A", "NMTID")
        private val QQ_SESSION_COOKIE_KEYS = setOf(
            "uin", "p_uin", "qqmusic_key", "qm_keyst", "skey", "p_skey",
            "wxuin", "euin", "musickey", "login_type", "tmeLoginType", "pgv_pvid",
        )

        fun createIntent(context: Context, clearCookies: Boolean, provider: String = "netease"): Intent =
            Intent(context, OfficialLoginActivity::class.java)
                .putExtra(EXTRA_CLEAR_COOKIES, clearCookies)
                .putExtra(EXTRA_PROVIDER, provider)
    }

    private var webView: WebView? = null
    private var submitted = false
    private var isQq = false
    private val failedState = mutableStateOf(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isQq = intent.getStringExtra(EXTRA_PROVIDER) == "qq"
        val loginUrl = if (isQq) QQ_LOGIN_URL else LOGIN_URL
        val app = application as AuraApplication
        val auraSettings = app.settings.value
        val seed = when (auraSettings.color) {
            "custom" -> app.settings.previewColor ?: auraSettings.customColor
            "system" -> getColor(android.R.color.system_accent1_500)
            else -> colorPresets.firstOrNull { it.first == auraSettings.color }?.third?.toInt()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }

        val header = ComposeView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setContent {
                val controller = remember(auraSettings.theme, auraSettings.monet, seed, auraSettings.palette, auraSettings.colorSpec) {
                    val mode = if (auraSettings.monet) when (auraSettings.theme) {
                        "light" -> ColorSchemeMode.MonetLight
                        "dark" -> ColorSchemeMode.MonetDark
                        else -> ColorSchemeMode.MonetSystem
                    } else when (auraSettings.theme) {
                        "light" -> ColorSchemeMode.Light
                        "dark" -> ColorSchemeMode.Dark
                        else -> ColorSchemeMode.System
                    }
                    ThemeController(
                        mode,
                        keyColor = seed?.let(::Color),
                        paletteStyle = paletteStyles[auraSettings.palette.coerceIn(paletteStyles.indices)],
                        colorSpec = if (auraSettings.colorSpec == 0) ThemeColorSpec.Spec2021 else ThemeColorSpec.Spec2025,
                    )
                }
                AuraUiTheme(
                    themeMode = auraSettings.theme,
                    uiStyle = auraSettings.uiStyle,
                    monet = auraSettings.monet,
                    seed = seed,
                    useSystemSeed = auraSettings.color == "system",
                    controller = controller,
                    textStyles = remember { auraTextStyles() },
                ) {
                    var failed by failedState
                    val surface = MiuixTheme.colorScheme.surface
                    val lightSurface = surface.luminance() >= 0.5f
                    SideEffect {
                        root.setBackgroundColor(surface.toArgb())
                        WindowCompat.getInsetsController(window, window.decorView).apply {
                            isAppearanceLightStatusBars = lightSurface
                            isAppearanceLightNavigationBars = lightSurface
                        }
                    }
                    Column(Modifier.fillMaxWidth().background(surface).statusBarsPadding()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { finish() }) {
                                Icon(
                                    imageVector = MiuixIcons.Regular.ChevronBackward,
                                    contentDescription = tr("back", "返回"),
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = if (isQq) tr("qq_official_login", "QQ音乐官方登录") else tr("official_login", "网易云音乐官方登录"),
                                style = MiuixTheme.textStyles.title4,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (failed) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = tr("official_page_failed", "登录页加载失败，请检查网络后重试"),
                                    style = MiuixTheme.textStyles.body2,
                                    modifier = Modifier.weight(1f),
                                )
                                GlassDialogButton({
                                    failed = false
                                    webView?.reload()
                                }) {
                                    Text(tr("retry", "重试"))
                                }
                            }
                        }
                    }
                }
            }
        }

        val view = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            this.settings.javaScriptEnabled = true
            this.settings.domStorageEnabled = true
            this.settings.allowFileAccess = false
            this.settings.allowContentAccess = false
            this.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, isQq)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.scheme != "https") return true
                    val host = url.host.orEmpty()
                    val allowed = if (isQq) {
                        host == "qq.com" || host.endsWith(".qq.com") || host == "open.weixin.qq.com"
                    } else {
                        host == "music.163.com" || host.endsWith(".163.com")
                    }
                    return !allowed
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) failedState.value = true
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    checkLoginCookie()
                }
            }
        }
        webView = view

        root.addView(header)
        root.addView(view)
        setContentView(root)

        val clearCookies = intent.getBooleanExtra(EXTRA_CLEAR_COOKIES, false)
        if (clearCookies) {
            CookieManager.getInstance().removeAllCookies {
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        view.loadUrl(loginUrl)
                    }
                }
            }
        } else {
            view.loadUrl(loginUrl)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (!submitted) {
                    checkLoginCookie()
                    delay(1200)
                }
            }
        }
    }

    private fun checkLoginCookie() {
        if (submitted) return
        if (isQq) {
            val merged = linkedMapOf<String, String>()
            listOf("https://y.qq.com", "https://u.y.qq.com", "https://c.y.qq.com").forEach { origin ->
                CookieManager.getInstance().getCookie(origin).orEmpty().split(';').forEach { part ->
                    val trimmed = part.trim()
                    val key = trimmed.substringBefore('=')
                    val value = trimmed.substringAfter('=', "")
                    if (key in QQ_SESSION_COOKIE_KEYS && value.isNotBlank()) merged[key] = value
                }
            }
            val hasKey = !merged["qqmusic_key"].isNullOrBlank() || !merged["qm_keyst"].isNullOrBlank()
            val hasUin = !merged["uin"].isNullOrBlank() || !merged["p_uin"].isNullOrBlank() ||
                !merged["wxuin"].isNullOrBlank() || !merged["euin"].isNullOrBlank()
            if (hasKey && hasUin) {
                submitted = true
                val cookie = merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
                setResult(RESULT_OK, Intent().putExtra(EXTRA_COOKIE, cookie))
                finish()
            }
            return
        }
        val cookie = CookieManager.getInstance().getCookie("https://music.163.com").orEmpty()
            .split(';')
            .map { it.trim() }
            .filter { it.substringBefore('=') in SESSION_COOKIE_KEYS && it.substringAfter('=', "").isNotBlank() }
            .joinToString("; ")
        if (cookie.split(';').any { it.trim().startsWith("MUSIC_U=") }) {
            submitted = true
            setResult(RESULT_OK, Intent().putExtra(EXTRA_COOKIE, cookie))
            finish()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.destroy()
        }
        webView = null
        CookieManager.getInstance().removeAllCookies(null)
        super.onDestroy()
    }
}
