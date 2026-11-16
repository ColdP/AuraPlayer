// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import btm.m.auraplayer.AuraApplication
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.lyrics.LlmApiProvider
import btm.m.auraplayer.lyrics.LlmEndpointSupport
import btm.m.auraplayer.lyrics.LlmRawOutputHistoryStore
import btm.m.auraplayer.lyrics.LlmSourceLanguage
import btm.m.auraplayer.lyrics.LlmSystemPrompt
import btm.m.auraplayer.lyrics.LlmTargetLanguage
import btm.m.auraplayer.lyrics.LlmTranslationCache
import btm.m.auraplayer.lyrics.LlmTranslator
import btm.m.auraplayer.lyrics.formatLlmCacheBytes
import btm.m.auraplayer.lyrics.maskLlmApiKey
import btm.m.auraplayer.playback.LyricTranslationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun LlmTranslationSettingsPage(model: AuraViewModel, padding: PaddingValues, open: (String) -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as AuraApplication
    val store = app.llmTranslation
    val state by store.state.collectAsState()
    val apiKey by store.activeApiKey.collectAsState()
    val backdrop = LocalToolbarBackdrop.current
    val scope = rememberCoroutineScope()

    var baseUrlDialogOpen by remember { mutableStateOf(false) }
    var apiKeyDialogOpen by remember { mutableStateOf(false) }
    var stylePromptDialogOpen by remember { mutableStateOf(false) }
    var systemPromptDialogOpen by remember { mutableStateOf(false) }

    var baseUrlDraft by remember { mutableStateOf("") }
    var baseUrlError by remember { mutableStateOf<String?>(null) }
    var apiKeyDraft by remember { mutableStateOf("") }
    var stylePromptDraft by remember { mutableStateOf("") }

    var refreshingModels by remember { mutableStateOf(false) }
    var refreshSummary by remember { mutableStateOf<String?>(null) }
    var checkingBalance by remember { mutableStateOf(false) }
    var balanceSummary by remember { mutableStateOf<String?>(null) }
    var balanceRequestId by remember { mutableIntStateOf(0) }
    var cacheRefreshTick by remember { mutableIntStateOf(0) }

    fun queryBalance(showToastOnUnsupported: Boolean) {
        val currentProvider = state.provider
        val currentUrl = state.baseUrlFor(currentProvider)
        val currentKey = store.apiKeyFor(currentProvider)
        if (currentKey.isBlank()) {
            balanceSummary = null
            if (showToastOnUnsupported) {
                Toast.makeText(context, tr("llm_api_key_required", "请先输入 API Key"), Toast.LENGTH_SHORT).show()
            }
            return
        }
        val reqId = ++balanceRequestId
        checkingBalance = true
        scope.launch {
            val result = LlmTranslator.fetchBalance(
                provider = currentProvider,
                baseUrl = currentUrl,
                apiKey = currentKey,
            )
            if (reqId != balanceRequestId) return@launch
            checkingBalance = false
            result.onSuccess { info ->
                if (info.supported) {
                    balanceSummary = info.displayText
                } else {
                    val unsupportedText = tr("llm_balance_unsupported", "当前接口不支持余额查询")
                    balanceSummary = unsupportedText
                    if (showToastOnUnsupported) {
                        Toast.makeText(context, unsupportedText, Toast.LENGTH_SHORT).show()
                    }
                }
            }.onFailure { err ->
                val detail = err.message?.takeIf { it.isNotBlank() }
                    ?: tr("llm_balance_failed", "余额查询失败")
                balanceSummary = detail
                if (showToastOnUnsupported) {
                    Toast.makeText(context, detail, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(state.enabled, state.provider, state.activeBaseUrl(), apiKey) {
        balanceSummary = null
        if (state.enabled && apiKey.isNotBlank()) {
            queryBalance(showToastOnUnsupported = false)
        }
    }
    LifecycleResumeEffect(Unit) {
        cacheRefreshTick++
        onPauseOrDispose {}
    }
    val cacheSummaryStats by produceState(0 to 0L, cacheRefreshTick) {
        value = withContext(Dispatchers.IO) {
            val entries = LlmTranslationCache.listEntries(context.cacheDir)
            entries.size to entries.sumOf { it.sizeBytes }
        }
    }
    val historySummaryStats by produceState(0 to 0L, cacheRefreshTick) {
        value = withContext(Dispatchers.IO) {
            val records = LlmRawOutputHistoryStore.list(context.cacheDir)
            records.size to records.sumOf { it.sizeBytes }
        }
    }

    val providerLabels = listOf(
        tr("llm_provider_openai", "OpenAI 兼容"),
        tr("llm_provider_anthropic", "Anthropic 兼容"),
    )
    val sourceLabels = LlmSourceLanguage.entries.map(::sourceLanguageLabel)
    val targetLabels = LlmTargetLanguage.entries.map(::targetLanguageLabel)
    val models = state.activeModels()
    val selectedModelIndex = if (models.isEmpty()) 0 else models.indexOf(state.activeModel()).coerceAtLeast(0)

    val disabledTargetIndices = if (state.sourceLanguage != LlmSourceLanguage.AUTO) {
        LlmTargetLanguage.entries.indexOfFirst { it.code == state.sourceLanguage.code }
            .takeIf { it >= 0 }
            ?.let(::setOf)
            ?: emptySet()
    } else {
        emptySet()
    }
    val disabledSourceIndices = LlmSourceLanguage.entries.indexOfFirst {
        it != LlmSourceLanguage.AUTO && it.code == state.targetLanguage.code
    }.takeIf { it >= 0 }?.let(::setOf) ?: emptySet()

    LazyColumn(
        pageContentModifier(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Top card deliberately omits SmallTitle per design requirement.
        item("llm_master_toggle") {
            SeamlessCard(
                Modifier.fillMaxWidth(),
                cornerRadius = 22.5.dp,
                insideMargin = PaddingValues(0.dp),
            ) {
                SwitchPreference(
                    title = tr("llm_translation_enable", "启用大模型翻译"),
                    summary = tr("llm_translation_enable_summary", "关闭后禁用该部分的所有功能，并使用默认网络歌词翻译"),
                    checked = state.enabled,
                ) { checked ->
                    store.update { it.copy(enabled = checked) }
                }
            }
        }

        item("llm_settings_body") {
            BlurAnimatedVisibility(visible = state.enabled, label = "llm-translation-options") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Group(tr("llm_api_group", "接口与模型")) {
                        GlassDropdownPreference(
                            title = tr("llm_api_provider", "API 兼容类型"),
                            items = providerLabels,
                            selectedIndex = state.provider.ordinal,
                            backdrop = backdrop,
                        ) { index ->
                            val picked = LlmApiProvider.entries[index]
                            if (picked != state.provider) {
                                refreshSummary = null
                                store.update { it.copy(provider = picked) }
                            }
                        }
                        Preference(
                            title = tr("llm_base_url", "兼容地址"),
                            summary = state.activeBaseUrl(),
                        ) {
                            baseUrlDraft = state.activeBaseUrl()
                            baseUrlError = null
                            baseUrlDialogOpen = true
                        }
                        Preference(
                            title = tr("llm_api_key", "API Key"),
                            summary = maskLlmApiKey(apiKey).ifEmpty { tr("llm_api_key_unset", "未设置") },
                        ) {
                            apiKeyDraft = apiKey
                            apiKeyDialogOpen = true
                        }
                        Preference(
                            title = tr("llm_api_balance", "API Key 余额"),
                            summary = when {
                                checkingBalance -> tr("llm_balance_checking", "正在查询余额…")
                                apiKey.isBlank() -> tr("llm_api_key_required", "请先输入 API Key")
                                !balanceSummary.isNullOrBlank() -> balanceSummary
                                else -> tr("llm_balance_tap_check", "点击查询当前兼容接口余额")
                            },
                            enabled = !checkingBalance,
                        ) {
                            queryBalance(showToastOnUnsupported = true)
                        }
                        GlassDropdownPreference(
                            title = tr("llm_select_model", "选择模型"),
                            items = if (models.isEmpty()) {
                                listOf(tr("llm_models_empty", "尚未获取模型列表"))
                            } else {
                                models
                            },
                            selectedIndex = selectedModelIndex,
                            enabled = models.isNotEmpty(),
                            disabledIndices = if (models.isEmpty()) setOf(0) else emptySet(),
                            backdrop = backdrop,
                        ) { index ->
                            models.getOrNull(index)?.let { chosen ->
                                store.update { it.withSelectedModel(chosen) }
                            }
                        }
                        Preference(
                            title = tr("llm_refresh_models", "刷新模型列表"),
                            summary = when {
                                refreshingModels -> tr("llm_refreshing_models", "正在从接口获取模型列表…")
                                !refreshSummary.isNullOrBlank() -> refreshSummary
                                models.isNotEmpty() -> tr("llm_models_count_summary", "已加载 %d 个模型").format(models.size)
                                else -> tr("llm_refresh_models_summary", "从兼容接口拉取可用模型")
                            },
                            enabled = !refreshingModels,
                        ) {
                            val currentProvider = state.provider
                            val currentUrl = state.baseUrlFor(currentProvider)
                            val currentKey = store.apiKeyFor(currentProvider)
                            if (currentKey.isBlank()) {
                                val msg = tr("llm_api_key_required", "请先输入 API Key")
                                refreshSummary = msg
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                return@Preference
                            }
                            refreshingModels = true
                            refreshSummary = null
                            scope.launch {
                                val result = LlmTranslator.fetchModels(
                                    provider = currentProvider,
                                    baseUrl = currentUrl,
                                    apiKey = currentKey,
                                )
                                refreshingModels = false
                                result.onSuccess { fetched ->
                                    store.update { it.withModels(fetched, currentProvider) }
                                    val msg = tr("llm_models_refreshed", "已加载 %d 个模型").format(fetched.size)
                                    refreshSummary = msg
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }.onFailure { err ->
                                    val detail = err.message?.takeIf { it.isNotBlank() }
                                        ?: tr("llm_refresh_failed", "获取模型列表失败")
                                    refreshSummary = detail
                                    Toast.makeText(context, detail, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }

                    Group(tr("llm_language_group", "语言")) {
                        GlassDropdownPreference(
                            title = tr("llm_source_language", "源语言"),
                            items = sourceLabels,
                            selectedIndex = state.sourceLanguage.ordinal,
                            disabledIndices = disabledSourceIndices,
                            backdrop = backdrop,
                        ) { index ->
                            val picked = LlmSourceLanguage.entries[index]
                            if (picked != LlmSourceLanguage.AUTO && picked.code == state.targetLanguage.code) {
                                Toast.makeText(
                                    context,
                                    tr("llm_same_language_fallback_notice", "源语言与目标语言相同，将自动退回从网络搜索翻译"),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                store.update { it.copy(sourceLanguage = picked) }
                            }
                        }
                        GlassDropdownPreference(
                            title = tr("llm_target_language", "目标语言"),
                            items = targetLabels,
                            selectedIndex = state.targetLanguage.ordinal,
                            disabledIndices = disabledTargetIndices,
                            backdrop = backdrop,
                        ) { index ->
                            val picked = LlmTargetLanguage.entries[index]
                            if (state.sourceLanguage != LlmSourceLanguage.AUTO &&
                                picked.code == state.sourceLanguage.code
                            ) {
                                Toast.makeText(
                                    context,
                                    tr("llm_same_language_fallback_notice", "源语言与目标语言相同，将自动退回从网络搜索翻译"),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                store.update { it.copy(targetLanguage = picked) }
                            }
                        }
                        val sourcePrefItems = listOf(
                            tr("translation_source_network", "网络检索"),
                            tr("translation_source_llm", "大模型翻译"),
                        )
                        GlassDropdownPreference(
                            title = tr("llm_preferred_translation_source", "首选翻译来源"),
                            items = sourcePrefItems,
                            selectedIndex = if (model.lyricTranslationSource == LyricTranslationSource.NETWORK) 0 else 1,
                            backdrop = backdrop,
                        ) { index ->
                            val picked = if (index == 0) {
                                LyricTranslationSource.NETWORK
                            } else {
                                LyricTranslationSource.LLM
                            }
                            model.changeLyricTranslationSource(picked)
                        }
                        Preference(
                            title = tr("llm_auto_rules_title", "自动翻译语言规则"),
                            summary = if (state.autoLanguageRulesEnabled) {
                                tr("llm_auto_rules_enabled_summary", "已启用 · %d 组自定义规则").format(state.autoLanguageRules.size)
                            } else {
                                tr("llm_auto_rules_disabled_summary", "未启用 · 针对指定源语言自动匹配目标语言")
                            },
                        ) {
                            open("llm_auto_language_rules")
                        }
                        Preference(
                            title = tr("llm_same_language_rule_title", "相同语言回退"),
                            summary = if (state.hasExplicitLanguageConflict()) {
                                tr("llm_same_language_fallback_notice", "源语言与目标语言相同，将自动退回从网络搜索翻译")
                            } else {
                                tr("llm_same_language_rule_summary", "自动检测或手动选择与目标语言一致时，自动退回从网络搜寻翻译")
                            },
                            enabled = false,
                        ) {}
                    }

                    Group(tr("llm_prompt_group", "提示词")) {
                        Preference(
                            title = tr("llm_custom_style_prompt", "自定义风格提示词"),
                            summary = state.customStylePrompt.trim().takeIf { it.isNotEmpty() }
                                ?: tr("llm_custom_style_empty", "未设置，使用自然信达雅歌词翻译风格"),
                        ) {
                            stylePromptDraft = state.customStylePrompt
                            stylePromptDialogOpen = true
                        }
                        Preference(
                            title = tr("llm_system_prompt", "系统提示词（已锁定）"),
                            summary = tr("llm_system_prompt_summary", "强制锁定：输出格式与源歌词逐行对齐，并启用安全防注入与兼容约束"),
                        ) {
                            systemPromptDialogOpen = true
                        }
                    }

                    Group(tr("llm_cache_group", "缓存")) {
                        Preference(
                            title = tr("llm_translation_cache", "翻译缓存"),
                            summary = tr("llm_translation_cache_entry_summary", "已缓存 %d 首 · %s")
                                .format(cacheSummaryStats.first, formatLlmCacheBytes(cacheSummaryStats.second)),
                        ) {
                            open("llm_translation_cache")
                        }
                        Preference(
                            title = tr("llm_raw_history_title", "历史输出"),
                            summary = tr("llm_raw_history_summary", "已记录 %d 次 · %s")
                                .format(historySummaryStats.first, formatLlmCacheBytes(historySummaryStats.second)),
                        ) {
                            open("llm_raw_output_history")
                        }
                    }
                }
            }
        }
    }

    WindowDialog(
        show = baseUrlDialogOpen,
        title = tr("llm_base_url", "兼容地址"),
        summary = tr("llm_base_url_hint", "输入 OpenAI 或 Anthropic 兼容 API 基础地址"),
        titleTextAlign = TextAlign.Start,
        summaryTextAlign = TextAlign.Start,
        onDismissRequest = { baseUrlDialogOpen = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AuraTextField(
                value = baseUrlDraft,
                onValueChange = {
                    baseUrlDraft = it
                    baseUrlError = null
                },
                label = tr("llm_base_url_placeholder", "例如 https://api.openai.com/v1"),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            baseUrlError?.let { err ->
                Text(err, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(
                    onClick = {
                        baseUrlDraft = state.provider.defaultBaseUrl
                        baseUrlError = null
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(tr("reset_defaults", "恢复默认")) }
                GlassDialogButton(
                    onClick = { baseUrlDialogOpen = false },
                    modifier = Modifier.weight(1f),
                ) { Text(tr("cancel", "取消")) }
            }
            GlassDialogButton(
                onClick = {
                    val trimmed = baseUrlDraft.trim().ifBlank { state.provider.defaultBaseUrl }
                    val valid = runCatching { LlmEndpointSupport.normalizeBaseUrl(trimmed, state.provider) }
                    valid.onSuccess {
                        store.update { cfg -> cfg.withBaseUrl(trimmed) }
                        baseUrlDialogOpen = false
                    }.onFailure {
                        baseUrlError = tr("llm_base_url_invalid", "请输入以 http:// 或 https:// 开头的有效地址")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(tr("save", "保存")) }
        }
    }

    WindowDialog(
        show = apiKeyDialogOpen,
        title = tr("llm_api_key", "API Key"),
        summary = tr("llm_api_key_dialog_summary", "凭证将使用 Android Keystore 加密存储于本机"),
        titleTextAlign = TextAlign.Start,
        summaryTextAlign = TextAlign.Start,
        onDismissRequest = { apiKeyDialogOpen = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AuraTextField(
                value = apiKeyDraft,
                onValueChange = { apiKeyDraft = it },
                label = tr("llm_api_key_placeholder", "输入 API Key"),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(
                    onClick = {
                        store.saveApiKey("")
                        apiKeyDraft = ""
                        apiKeyDialogOpen = false
                    },
                    modifier = Modifier.weight(1f),
                    enabled = apiKey.isNotBlank() || apiKeyDraft.isNotBlank(),
                ) { Text(tr("clear", "清空")) }
                GlassDialogButton(
                    onClick = { apiKeyDialogOpen = false },
                    modifier = Modifier.weight(1f),
                ) { Text(tr("cancel", "取消")) }
            }
            GlassDialogButton(
                onClick = {
                    store.saveApiKey(apiKeyDraft)
                    apiKeyDialogOpen = false
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(tr("save", "保存")) }
        }
    }

    WindowDialog(
        show = stylePromptDialogOpen,
        title = tr("llm_custom_style_prompt", "自定义风格提示词"),
        summary = tr("llm_custom_style_dialog_summary", "可指定润色风格、术语保留或语境倾向；行对齐与安全系统规则不可覆盖"),
        titleTextAlign = TextAlign.Start,
        summaryTextAlign = TextAlign.Start,
        onDismissRequest = { stylePromptDialogOpen = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AuraTextField(
                value = stylePromptDraft,
                onValueChange = { stylePromptDraft = it.take(2000) },
                label = tr("llm_custom_style_placeholder", "例如：诗意简洁、保持英文专有名词不翻译"),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 220.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(
                    onClick = { stylePromptDraft = "" },
                    modifier = Modifier.weight(1f),
                    enabled = stylePromptDraft.isNotEmpty(),
                ) { Text(tr("clear", "清空")) }
                GlassDialogButton(
                    onClick = { stylePromptDialogOpen = false },
                    modifier = Modifier.weight(1f),
                ) { Text(tr("cancel", "取消")) }
            }
            GlassDialogButton(
                onClick = {
                    store.update { it.copy(customStylePrompt = stylePromptDraft.trim()) }
                    stylePromptDialogOpen = false
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(tr("save", "保存")) }
        }
    }

    val lockedSystemPrompt = remember(state.targetLanguage, state.sourceLanguage) {
        LlmSystemPrompt.build(state.targetLanguage, state.sourceLanguage)
    }
    WindowDialog(
        show = systemPromptDialogOpen,
        title = tr("llm_system_prompt", "系统提示词（已锁定）"),
        summary = tr("llm_system_prompt_readonly_note", "以下系统指令已写死在客户端内，确保逐行对齐、防提示词注入与跨接口兼容。"),
        titleTextAlign = TextAlign.Start,
        summaryTextAlign = TextAlign.Start,
        onDismissRequest = { systemPromptDialogOpen = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    lockedSystemPrompt,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(
                    onClick = {
                        copyToClipboard(
                            context,
                            tr("llm_system_prompt", "系统提示词（已锁定）"),
                            lockedSystemPrompt,
                        )
                        Toast.makeText(context, tr("copied", "已复制"), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(tr("copy", "复制")) }
                GlassDialogButton(
                    onClick = { systemPromptDialogOpen = false },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("llm_system_prompt_understand", "了解")) }
            }
        }
    }
}

private fun sourceLanguageLabel(language: LlmSourceLanguage): String = when (language) {
    LlmSourceLanguage.AUTO -> tr("llm_lang_auto", "自动检测")
    LlmSourceLanguage.ZH_HANS -> tr("llm_lang_zh_hans", "简体中文")
    LlmSourceLanguage.ZH_HANT -> tr("llm_lang_zh_hant", "繁體中文")
    LlmSourceLanguage.EN -> "English"
    LlmSourceLanguage.JA -> "日本語"
    LlmSourceLanguage.KO -> "한국어"
    LlmSourceLanguage.FR -> "Français"
    LlmSourceLanguage.DE -> "Deutsch"
    LlmSourceLanguage.ES -> "Español"
    LlmSourceLanguage.RU -> "Русский"
}

internal fun targetLanguageLabel(language: LlmTargetLanguage): String = when (language) {
    LlmTargetLanguage.ZH_HANS -> tr("llm_lang_zh_hans", "简体中文")
    LlmTargetLanguage.ZH_HANT -> tr("llm_lang_zh_hant", "繁體中文")
    LlmTargetLanguage.EN -> "English"
    LlmTargetLanguage.JA -> "日本語"
    LlmTargetLanguage.KO -> "한국어"
    LlmTargetLanguage.FR -> "Français"
    LlmTargetLanguage.DE -> "Deutsch"
    LlmTargetLanguage.ES -> "Español"
    LlmTargetLanguage.RU -> "Русский"
}
