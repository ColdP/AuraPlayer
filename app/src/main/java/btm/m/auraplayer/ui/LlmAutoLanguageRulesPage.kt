// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.AuraApplication
import btm.m.auraplayer.lyrics.LlmLanguageRule
import btm.m.auraplayer.lyrics.LlmTargetLanguage
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun LlmAutoLanguageRulesPage(padding: PaddingValues) {
    val context = LocalContext.current
    val store = (context.applicationContext as AuraApplication).llmTranslation
    val state by store.state.collectAsState()
    val backdrop = LocalToolbarBackdrop.current

    var pendingDeleteRule by remember { mutableStateOf<LlmLanguageRule?>(null) }
    val languageLabels = LlmTargetLanguage.entries.map(::targetLanguageLabel)

    LazyColumn(
        pageContentModifier(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Card 1: Master switch without SmallTitle.
        item("llm_auto_rules_master_card") {
            SeamlessCard(
                Modifier.fillMaxWidth(),
                cornerRadius = 22.5.dp,
                insideMargin = PaddingValues(0.dp),
            ) {
                SwitchPreference(
                    title = tr("llm_auto_rules_enable", "启用自动翻译语言规则"),
                    summary = tr("llm_auto_rules_enable_summary", "检测到指定源语言时，优先按对应规则选择目标语言"),
                    checked = state.autoLanguageRulesEnabled,
                ) { checked ->
                    store.update { it.copy(autoLanguageRulesEnabled = checked) }
                }
            }
        }

        // One card per language pair rule: source dropdown, target dropdown, and delete preference.
        items(state.autoLanguageRules, key = LlmLanguageRule::id) { rule ->
            SeamlessCard(
                Modifier.fillMaxWidth(),
                cornerRadius = 22.5.dp,
                insideMargin = PaddingValues(0.dp),
            ) {
                GlassDropdownPreference(
                    title = tr("llm_auto_rule_source", "自动翻译源语言"),
                    items = languageLabels,
                    selectedIndex = rule.source.ordinal,
                    enabled = state.autoLanguageRulesEnabled,
                    disabledIndices = setOf(rule.target.ordinal),
                    backdrop = backdrop,
                ) { index ->
                    val picked = LlmTargetLanguage.entries[index]
                    if (picked == rule.target) {
                        Toast.makeText(
                            context,
                            tr("llm_auto_rule_same_language_error", "源语言和目标语言不能相同"),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        store.update { cfg ->
                            cfg.copy(
                                autoLanguageRules = cfg.autoLanguageRules.map { item ->
                                    if (item.id == rule.id) item.copy(source = picked) else item
                                },
                            )
                        }
                    }
                }
                GlassDropdownPreference(
                    title = tr("llm_auto_rule_target", "自动翻译目标语言"),
                    items = languageLabels,
                    selectedIndex = rule.target.ordinal,
                    enabled = state.autoLanguageRulesEnabled,
                    disabledIndices = setOf(rule.source.ordinal),
                    backdrop = backdrop,
                ) { index ->
                    val picked = LlmTargetLanguage.entries[index]
                    if (picked == rule.source) {
                        Toast.makeText(
                            context,
                            tr("llm_auto_rule_same_language_error", "源语言和目标语言不能相同"),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        store.update { cfg ->
                            cfg.copy(
                                autoLanguageRules = cfg.autoLanguageRules.map { item ->
                                    if (item.id == rule.id) item.copy(target = picked) else item
                                },
                            )
                        }
                    }
                }
                Preference(
                    title = tr("delete", "删除"),
                    enabled = state.autoLanguageRulesEnabled,
                ) {
                    pendingDeleteRule = rule
                }
            }
        }

        // Trailing standalone card without SmallTitle, always following the last rule group.
        item("llm_auto_rules_add_card") {
            SeamlessCard(
                Modifier.fillMaxWidth(),
                cornerRadius = 22.5.dp,
                insideMargin = PaddingValues(0.dp),
            ) {
                Preference(
                    title = tr("llm_auto_rule_add", "添加组"),
                    enabled = state.autoLanguageRulesEnabled,
                ) {
                    val usedSources = state.autoLanguageRules.map { it.source }.toSet()
                    val nextSource = LlmTargetLanguage.entries.firstOrNull { it !in usedSources }
                        ?: LlmTargetLanguage.ZH_HANS
                    val nextTarget = if (nextSource == LlmTargetLanguage.EN) {
                        LlmTargetLanguage.ZH_HANS
                    } else {
                        LlmTargetLanguage.EN
                    }
                    val created = LlmLanguageRule(source = nextSource, target = nextTarget)
                    store.update { cfg -> cfg.copy(autoLanguageRules = cfg.autoLanguageRules + created) }
                }
            }
        }
    }

    pendingDeleteRule?.let { targetRule ->
        val pairLabel = "${targetLanguageLabel(targetRule.source)} → ${targetLanguageLabel(targetRule.target)}"
        WindowDialog(
            show = true,
            title = tr("llm_auto_rule_delete_title", "删除语言规则"),
            summary = tr("llm_auto_rule_delete_confirm", "确定删除「%s」这条自动翻译语言规则吗？").format(pairLabel),
            titleTextAlign = TextAlign.Start,
            summaryTextAlign = TextAlign.Start,
            onDismissRequest = { pendingDeleteRule = null },
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(
                    onClick = { pendingDeleteRule = null },
                    modifier = Modifier.weight(1f),
                ) { Text(tr("cancel", "取消")) }
                GlassDialogButton(
                    onClick = {
                        val removeId = targetRule.id
                        pendingDeleteRule = null
                        store.update { cfg ->
                            cfg.copy(autoLanguageRules = cfg.autoLanguageRules.filterNot { it.id == removeId })
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("delete", "删除")) }
            }
        }
    }
}
