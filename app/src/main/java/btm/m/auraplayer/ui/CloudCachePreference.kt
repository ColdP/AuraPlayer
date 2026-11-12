package btm.m.auraplayer.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.data.CloudCacheCategory
import btm.m.auraplayer.data.CloudCacheStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*

@Composable
internal fun CloudCachePreference(model: AuraViewModel) {
    val context = LocalContext.current
    val store = remember(model) { CloudCacheStore(model.app, model.app.api) }
    val scope = rememberCoroutineScope()
    var sizes by remember { mutableStateOf<Map<CloudCacheCategory, Long>?>(null) }
    var visible by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(CloudCacheCategory.entries.toSet()) }
    fun size(bytes: Long) = Formatter.formatFileSize(context, bytes)
    LaunchedEffect(store, visible) {
        while (true) { sizes = store.sizes(); delay(5000) }
    }
    Preference(tr("cache", "缓存"), sizes?.values?.sum()?.let(::size) ?: tr("cache_calculating", "正在计算…")) {
        selected = CloudCacheCategory.entries.toSet(); failed = false; visible = true
    }
    WindowDialog(visible, title = tr("clear_cache", "清理缓存"),
        summary = tr("cache_total", "当前缓存总大小") + "：" + (sizes?.values?.sum()?.let(::size) ?: tr("cache_calculating", "正在计算…")),
        titleTextAlign = TextAlign.Start, summaryTextAlign = TextAlign.Start,
        onDismissRequest = { if (!clearing) visible = false }) {
        Column {
            CloudCacheCategory.entries.forEach { category ->
                val label = when (category) {
                    CloudCacheCategory.ARTWORK -> tr("cache_artwork", "封面与图片")
                    CloudCacheCategory.METADATA -> tr("cache_metadata", "歌单与歌曲信息")
                    CloudCacheCategory.PLAYBACK_AUDIO -> tr("cache_playback_audio", "播放音频缓存")
                }
                fun toggle() { selected = if (category in selected) selected - category else selected + category }
                BasicComponent(title = label, summary = size(sizes?.get(category) ?: 0L),
                    enabled = !clearing, onClick = { toggle() }, endActions = {
                        Checkbox(state = androidx.compose.ui.state.ToggleableState(category in selected),
                            enabled = !clearing, onClick = { toggle() })
                    })
            }
        }
        if (failed) Text(tr("cache_clear_failed", "清理失败，请重试"))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton({ visible = false }, Modifier.weight(1f), enabled = !clearing) { Text(tr("cancel", "取消")) }
            GlassDialogButton({
                clearing = true; failed = false
                scope.launch {
                    try {
                        store.clear(selected)
                        model.forgetCloudMetadataCache(selected)
                        sizes = store.sizes()
                        visible = false
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failed = true }
                    finally { clearing = false }
                }
            }, Modifier.weight(1f), enabled = !clearing && selected.isNotEmpty(), colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(if (clearing) tr("cache_clearing", "正在清理…") else tr("cache_clean", "清理"))
            }
        }
    }
}
