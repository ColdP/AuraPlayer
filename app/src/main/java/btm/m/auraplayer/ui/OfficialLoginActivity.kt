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
import btm.m.auraplayer.data.QqSession
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
        private const val LOGIN_URL = "https://music.163.com/login"
        private const val QQ_LOGIN_URL = "https://y.qq.com/n/ryqq/profile"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private val DESKTOP_HEADERS = mapOf(
            "Sec-CH-UA" to "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
            "Sec-CH-UA-Mobile" to "?0",
            "Sec-CH-UA-Platform" to "\"Windows\"",
        )
        private const val FORCE_DESKTOP_JS = """
            (function() {
              try {
                var def = function(obj, prop, val) {
                  try { Object.defineProperty(obj, prop, { get: function(){ return val; }, configurable: true }); } catch(e) {}
                };
                var ua = '$DESKTOP_UA';
                def(Navigator.prototype, 'userAgent', ua);
                def(Navigator.prototype, 'appVersion', ua.replace('Mozilla/', ''));
                def(Navigator.prototype, 'platform', 'Win32');
                def(Navigator.prototype, 'maxTouchPoints', 0);
                def(Navigator.prototype, 'userAgentData', {
                  brands: [{brand:'Chromium',version:'131'},{brand:'Not_A Brand',version:'24'}],
                  mobile: false,
                  platform: 'Windows',
                  getHighEntropyValues: function(){ return Promise.resolve({platform:'Windows',mobile:false,architecture:'x86',bitness:'64'}); }
                });
                def(Screen.prototype, 'width', 1920);
                def(Screen.prototype, 'height', 1080);
                def(Screen.prototype, 'availWidth', 1920);
                def(Screen.prototype, 'availHeight', 1040);
                if ('ontouchstart' in window) { try { delete window.ontouchstart; } catch(e) {} }
                var origMM = window.matchMedia;
                if (origMM) {
                  window.matchMedia = function(q) {
                    if (/pointer\s*:\s*coarse|hover\s*:\s*none/i.test(q)) {
                      return { matches: false, media: q, onchange: null, addListener: function(){}, removeListener: function(){}, addEventListener: function(){}, removeEventListener: function(){}, dispatchEvent: function(){ return false; } };
                    }
                    return origMM.call(window, q);
                  };
                }
                if (location.hostname === 'y.qq.com' || location.hostname === 'music.163.com') {
                  var applyViewport = function() {
                    var head = document.head || document.documentElement;
                    if (!head) return;
                    var vp = document.querySelector('meta[name="viewport"]');
                    if (!vp) { vp = document.createElement('meta'); vp.name = 'viewport'; head.appendChild(vp); }
                    vp.content = 'width=1280, initial-scale=0.32, minimum-scale=0.2, maximum-scale=3.0, user-scalable=yes';
                    if (!document.getElementById('aura-force-desktop-style')) {
                      var s = document.createElement('style');
                      s.id = 'aura-force-desktop-style';
                      s.textContent = 'html,body{min-width:1280px !important;overflow-x:auto !important;}';
                      head.appendChild(s);
                    }
                  };
                  applyViewport();
                  document.addEventListener('DOMContentLoaded', applyViewport, { once: true });
                }
              } catch(e) {}
            })();
        """
        private val SESSION_COOKIE_KEYS = setOf("MUSIC_U", "__csrf", "MUSIC_A", "NMTID")

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
                ProvideAuraAppTheme(
                    settings = auraSettings,
                    previewColor = app.settings.previewColor,
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
            this.settings.userAgentString = DESKTOP_UA
            this.settings.useWideViewPort = true
            this.settings.loadWithOverviewMode = true
            this.settings.setSupportZoom(true)
            this.settings.builtInZoomControls = true
            this.settings.displayZoomControls = false
            setInitialScale(0)
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, isQq)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.scheme != "https") return true
                    val host = url.host.orEmpty()
                    val path = url.path.orEmpty()
                    if (isQq && request.isForMainFrame &&
                        (host == "i.y.qq.com" || (host == "y.qq.com" && (path.startsWith("/m/") || path.startsWith("/n2/m/"))))
                    ) {
                        view.loadUrl(QQ_LOGIN_URL, DESKTOP_HEADERS)
                        return true
                    }
                    if (!isQq && request.isForMainFrame &&
                        ((host == "music.163.com" && (path == "/m" || path.startsWith("/m/"))) || host == "y.music.163.com")
                    ) {
                        view.loadUrl(LOGIN_URL, DESKTOP_HEADERS)
                        return true
                    }
                    val allowed = if (isQq) {
                        host == "qq.com" || host.endsWith(".qq.com") || host == "open.weixin.qq.com"
                    } else {
                        host == "music.163.com" || host.endsWith(".163.com")
                    }
                    return !allowed
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    view.evaluateJavascript(FORCE_DESKTOP_JS, null)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) failedState.value = true
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    view.evaluateJavascript(FORCE_DESKTOP_JS, null)
                    checkLoginCookie()
                }
            }
        }
        webView = view

        root.addView(header)
        root.addView(view)
        setContentView(root)

        fun loadLogin() {
            view.loadUrl(loginUrl, DESKTOP_HEADERS)
        }
        val clearCookies = intent.getBooleanExtra(EXTRA_CLEAR_COOKIES, false)
        if (clearCookies) {
            CookieManager.getInstance().removeAllCookies {
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) loadLogin()
                }
            }
        } else {
            loadLogin()
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
            // Let the OAuth redirect finish writing the music session before returning to the app.
            val page = webView?.url?.let(android.net.Uri::parse)
            if (page?.host != "y.qq.com" || page.path?.startsWith("/n/ryqq") != true) return
            val cookie = QqSession.loginCookie(
                listOf("https://y.qq.com", "https://u.y.qq.com", "https://c.y.qq.com").map { origin ->
                    CookieManager.getInstance().getCookie(origin).orEmpty()
                },
            )
            if (QqSession(cookie).hasCredentials) {
                submitted = true
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
