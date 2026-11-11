package btm.m.auraplayer.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Credentials are entered only into NetEase's HTTPS page. No Javascript bridge is installed. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun OfficialLoginView(padding: PaddingValues, busy: Boolean, onSession: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentBusy by rememberUpdatedState(busy)
    val currentSession by rememberUpdatedState(onSession)
    var failed by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf<String?>(null) }
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.scheme == "https" && (url.host == "music.163.com" || url.host?.endsWith(".163.com") == true)) return false
                    return true
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) failed = true
                }
            }
            loadUrl("https://music.163.com/m/login")
        }
    }
    DisposableEffect(webView) { onDispose { webView.stopLoading(); webView.destroy(); CookieManager.getInstance().removeAllCookies(null) } }
    LaunchedEffect(lifecycle, webView) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                if (!currentBusy) {
                    val cookie = CookieManager.getInstance().getCookie("https://music.163.com").orEmpty()
                        .split(';').map { it.trim() }
                        .filter { it.substringBefore('=') in setOf("MUSIC_U", "__csrf", "MUSIC_A", "NMTID") && it.substringAfter('=', "").isNotBlank() }
                        .joinToString("; ")
                    if (cookie.split(';').any { it.trim().startsWith("MUSIC_U=") } && submitted != cookie) {
                        submitted = cookie
                        currentSession(cookie)
                    }
                }
                delay(1500)
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(padding)) {
        if (busy) Text(tr("verifying_account", "正在验证账号…"), Modifier.padding(12.dp), style = MiuixTheme.textStyles.body2)
        if (failed) {
            Text(tr("official_page_failed", "登录页加载失败，请检查网络后重试"), Modifier.padding(12.dp))
            GlassDialogButton({ failed = false; webView.reload() }) { Text(tr("retry", "重试")) }
        }
        if (submitted != null && !busy) GlassDialogButton({ submitted = null }) { Text(tr("retry_verification", "重新验证登录")) }
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
    }
}
