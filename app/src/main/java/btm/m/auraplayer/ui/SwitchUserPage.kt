package btm.m.auraplayer.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.data.MultiAccountStateCodec
import btm.m.auraplayer.data.StoredCloudAccount
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwitchUserPage(model: AuraViewModel, padding: PaddingValues, isQq: Boolean = false) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val active = if (isQq) model.qqAccount else model.account
    val saved = if (isQq) model.savedQqAccounts else model.savedAccounts
    val activeCookie = if (isQq) model.app.qqCookie else model.app.cookie
    val currentUserId = active?.userId ?: if (isQq) model.app.activeStoredQqAccount()?.userId else model.app.activeStoredAccount()?.userId
    val accounts = remember(saved, active, activeCookie) {
        if (saved.isNotEmpty()) {
            saved
        } else {
            val cookie = activeCookie
            if (active != null && cookie.isNotBlank()) {
                listOf(StoredCloudAccount(active.userId, active.nickname, active.avatar, cookie))
            } else {
                emptyList()
            }
        }
    }

    var showingAddPhone by rememberSaveable { mutableStateOf(false) }
    var country by rememberSaveable { mutableStateOf("86") }
    var phone by rememberSaveable { mutableStateOf("") }
    var captcha by rememberSaveable { mutableStateOf("") }
    val officialLoginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val sessionCookie = result.data?.getStringExtra(OfficialLoginActivity.EXTRA_COOKIE).orEmpty()
            if (sessionCookie.isNotBlank()) {
                if (isQq) model.completeQqOfficialLogin(sessionCookie) else model.completeOfficialLogin(sessionCookie)
            }
        }
    }

    var selectedAccount by remember { mutableStateOf<StoredCloudAccount?>(null) }
    var dialogAccount by remember { mutableStateOf<StoredCloudAccount?>(null) }
    selectedAccount?.let { dialogAccount = it }

    var confirmDeleteAccount by remember { mutableStateOf<StoredCloudAccount?>(null) }
    var deleteDialogAccount by remember { mutableStateOf<StoredCloudAccount?>(null) }
    confirmDeleteAccount?.let { deleteDialogAccount = it }

    var cookiesAccount by remember { mutableStateOf<StoredCloudAccount?>(null) }
    var cookiesDialogAccount by remember { mutableStateOf<StoredCloudAccount?>(null) }
    cookiesAccount?.let { cookiesDialogAccount = it }

    LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (accounts.isNotEmpty()) {
            item {
                SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                    accounts.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedAccount = item }
                                .padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(item.avatar, Modifier.size(60.dp), circular = true)
                            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                Text(item.nickname)
                                Text("${if (isQq) "QQ" else "UID"} ${item.userId}", style = MiuixTheme.textStyles.body2)
                            }
                            if (item.userId == currentUserId) {
                                Text(
                                    text = tr("current_account", "当前"),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            SeamlessCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                Preference(tr("add_account", "添加账号")) {
                    if (isQq) {
                        officialLoginLauncher.launch(OfficialLoginActivity.createIntent(context, clearCookies = true, provider = "qq"))
                    } else if (model.app.endpoint.isBlank()) {
                        officialLoginLauncher.launch(OfficialLoginActivity.createIntent(context, clearCookies = true))
                    } else {
                        showingAddPhone = true
                    }
                }
            }
        }
        val message = if (isQq) model.qqMessage else model.loginMessage
        if (message.isNotBlank()) item {
            Text(message, color = MiuixTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
        }
    }

    WindowDialog(
        show = selectedAccount != null,
        onDismissRequest = { selectedAccount = null },
    ) {
        val target = dialogAccount ?: return@WindowDialog
        val isCurrent = target.userId == currentUserId
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Artwork(target.avatar, Modifier.size(56.dp), circular = true)
            Text(
                text = "${target.nickname} · ${target.userId}",
                style = MiuixTheme.textStyles.title4,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton(
                    onClick = {
                        if (isQq) model.switchQqCloudAccount(target.userId) else model.switchCloudAccount(target.userId)
                        selectedAccount = null
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isCurrent && !(if (isQq) model.qqBusy else model.cloudBusy),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(tr("switch_action", "切换"))
                }
                GlassDialogButton(
                    onClick = {
                        confirmDeleteAccount = target
                        selectedAccount = null
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(tr("delete", "删除"), color = Color(0xFFE53935))
                }
            }
            GlassDialogButton(
                onClick = {
                    cookiesAccount = target
                    selectedAccount = null
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(tr("view_cookies", "查看Cookies"))
            }
            GlassDialogButton(
                onClick = { selectedAccount = null },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(tr("cancel", "取消"))
            }
        }
    }

    WindowDialog(
        show = confirmDeleteAccount != null,
        title = tr("delete_account_confirm", "删除该账号？"),
        summary = deleteDialogAccount?.let { "${it.nickname} · ${it.userId}" },
        titleTextAlign = TextAlign.Start,
        summaryTextAlign = TextAlign.Start,
        onDismissRequest = { confirmDeleteAccount = null },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton({ confirmDeleteAccount = null }, Modifier.weight(1f)) {
                Text(tr("cancel", "取消"))
            }
            GlassDialogButton(
                onClick = {
                    deleteDialogAccount?.let { if (isQq) model.deleteQqCloudAccount(it.userId) else model.deleteCloudAccount(it.userId) }
                    confirmDeleteAccount = null
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(tr("delete", "删除"), color = Color(0xFFE53935))
            }
        }
    }

    WindowDialog(
        show = cookiesAccount != null,
        title = tr("cookies_title", "Cookies"),
        titleTextAlign = TextAlign.Start,
        onDismissRequest = { cookiesAccount = null },
    ) {
        val cookieTarget = cookiesDialogAccount
        val rawCookie = cookieTarget?.cookie.orEmpty()
        val lines = remember(rawCookie) { MultiAccountStateCodec.splitCookieLines(rawCookie) }
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = tr("long_press_copy_line", "长按任意一行可复制该条 Cookie"),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (lines.isEmpty()) {
                    Text(
                        text = tr("no_cookies", "暂无 Cookies"),
                        style = MiuixTheme.textStyles.body2,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    lines.forEach { line ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        copyCookieText(context, "cookie", line)
                                        Toast.makeText(context, tr("copied_cookie_line", "已复制该行 Cookie"), Toast.LENGTH_SHORT).show()
                                    },
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = line,
                                style = MiuixTheme.textStyles.body2,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton(
                    onClick = { cookiesAccount = null },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(tr("cancel", "取消"))
                }
                GlassDialogButton(
                    onClick = {
                        if (rawCookie.isNotBlank()) {
                            copyCookieText(context, "cookies", rawCookie)
                            Toast.makeText(context, tr("copied_all_cookies", "已复制全部 Cookies"), Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = rawCookie.isNotBlank(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(tr("copy_all", "复制全部"))
                }
            }
        }
    }

    WindowDialog(
        show = showingAddPhone,
        title = tr("add_account", "添加账号"),
        titleTextAlign = TextAlign.Start,
        onDismissRequest = { showingAddPhone = false },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AuraTextField(
                country,
                { country = it.filter(Char::isDigit).take(4) },
                label = tr("country_code", "国际区号"),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            AuraTextField(
                phone,
                { phone = it.filter(Char::isDigit).take(20) },
                label = tr("phone", "手机号"),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            GlassDialogButton(
                onClick = { model.sendCaptcha(phone, country) },
                modifier = Modifier.fillMaxWidth(),
                enabled = model.smsRemaining == 0 && !model.cloudBusy && phone.length >= 5 && country.isNotBlank() && model.app.endpoint.isNotBlank(),
            ) {
                Text(if (model.smsRemaining > 0) "${model.smsRemaining}s" else tr("send_captcha", "获取验证码"))
            }
            AuraTextField(
                captcha,
                { captcha = it.filter(Char::isDigit).take(8) },
                label = tr("captcha", "验证码"),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton({ showingAddPhone = false }, Modifier.weight(1f)) {
                    Text(tr("cancel", "取消"))
                }
                GlassDialogButton(
                    onClick = {
                        model.login(phone, country, captcha) {
                            showingAddPhone = false
                        }
                        captcha = ""
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !model.cloudBusy && phone.length >= 5 && captcha.length >= 4 && country.isNotBlank() && model.app.endpoint.isNotBlank(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(if (model.cloudBusy) tr("connecting", "正在连接…") else tr("login", "登录"))
                }
            }
        }
    }
}

private fun copyCookieText(context: Context, label: String, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
}
