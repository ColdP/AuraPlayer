package btm.m.auraplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.data.FilenameBlockRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun FilenameBlockRulesDialog(initial: FilenameBlockRules, onSave: (FilenameBlockRules) -> Unit, onDismiss: () -> Unit) {
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var keywords by rememberSaveable { mutableStateOf(initial.keywords) }
    var patterns by rememberSaveable { mutableStateOf(initial.patterns) }
    var sample by rememberSaveable { mutableStateOf("") }
    val matcher = remember(keywords, patterns) { FilenameBlockRules(keywords = keywords, patterns = patterns).compile() }
    val invalidLine = matcher.invalidPatternLine
    val match by produceState<Boolean?>(null, matcher, sample) {
        value = null
        if (invalidLine == null && sample.isNotBlank()) {
            delay(150)
            value = withContext(Dispatchers.Default) { matcher.blocks(sample.trim()) }
        }
    }
    val keyboardOptions = KeyboardOptions(autoCorrectEnabled = false)

    WindowDialog(true, title = tr("scan_filename_block", "文件名屏蔽"),
        titleTextAlign = TextAlign.Start, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SwitchPreference(tr("scan_filename_enable", "启用文件名屏蔽"), checked = enabled, onCheckedChange = { enabled = it })
                AuraTextField(keywords, { keywords = it }, Modifier.fillMaxWidth().heightIn(max = 112.dp),
                    label = tr("scan_filename_keywords", "关键词（每行一条）"), keyboardOptions = keyboardOptions)
                AuraTextField(patterns, { patterns = it }, Modifier.fillMaxWidth().heightIn(max = 112.dp),
                    label = tr("scan_filename_patterns", "正则表达式（每行一条）"), keyboardOptions = keyboardOptions)
                if (invalidLine != null) {
                    Text(tr("scan_filename_pattern_error", "第 %d 行不是有效的正则表达式").format(invalidLine),
                        color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                Column {
                    Text(tr("scan_filename_examples", "正则示例"), style = MiuixTheme.textStyles.body2)
                    val examples = listOf(
                        tr("scan_filename_example_recordings", "录音文件名前缀") to "^(录音|recording|voice[_ -])",
                        tr("scan_filename_example_extensions", "匹配语音文件扩展名") to "\\.(amr|m4r)$",
                    )
                    for ((title, example) in examples) {
                        Preference(title, example) {
                            if (patterns.lineSequence().none { it.trim() == example }) {
                                patterns = listOf(patterns.trimEnd(), example).filter(String::isNotEmpty).joinToString("\n")
                            }
                        }
                    }
                }
                AuraTextField(sample, { sample = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = tr("scan_filename_test", "试匹配文件名（含扩展名）"), keyboardOptions = keyboardOptions)
                match?.let { blocked ->
                    val result = when {
                        !blocked -> tr("scan_filename_test_no_match", "未命中文件名规则")
                        !enabled -> tr("scan_filename_test_paused", "已命中，但规则已暂停")
                        else -> tr("scan_filename_test_blocked", "已命中：文件会被屏蔽")
                    }
                    Text(result, style = MiuixTheme.textStyles.body2, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("cancel", "取消")) }
                GlassDialogButton({ onSave(FilenameBlockRules(enabled, keywords, patterns).normalized()) }, Modifier.weight(1f),
                    enabled = invalidLine == null, colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("save", "保存")) }
            }
        }
    }
}
