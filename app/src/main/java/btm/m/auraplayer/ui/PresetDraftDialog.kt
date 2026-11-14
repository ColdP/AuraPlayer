package btm.m.auraplayer.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.audio.DspPreset
import btm.m.auraplayer.audio.EffectsStore
import top.yukonga.miuix.kmp.basic.*

@Composable internal fun PresetDraftDialog(store: EffectsStore, show: Boolean, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val state by store.state.collectAsState()
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var transfer by remember { mutableStateOf("") }
    var exported by remember { mutableStateOf<DspPreset?>(null) }
    LaunchedEffect(show) { if (show) name = "" }
    fun beginTransfer(mode: String) {
        exported = DspPreset("draft-export", name.trim().ifBlank { tr("unsaved_preset", "未保存的预设") }, "", state.custom)
        transfer = mode; onDismiss()
    }
    WindowDialog(show, title = tr("unsaved_preset", "未保存的预设"), titleTextAlign = TextAlign.Start, onDismissRequest = onDismiss) {
        AuraTextField(name, { name = it.take(80) }, label = tr("preset_name", "预设名称"), singleLine = true, modifier = Modifier.fillMaxWidth())
        GlassDialogButton({
            if (runCatching { store.saveDraft(name) }.isSuccess) onSaved()
            else Toast.makeText(context, tr("preset_library_full", "预设库已满，请先删除部分自存预设"), Toast.LENGTH_LONG).show()
        }, Modifier.fillMaxWidth().padding(top = 12.dp), enabled = name.isNotBlank(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("save", "保存")) }
        GlassDialogButton(onDismiss, Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(tr("cancel", "取消")) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton({ beginTransfer("export") }, Modifier.weight(1f)) { Text(tr("export", "导出")) }
            GlassDialogButton({ beginTransfer("share") }, Modifier.weight(1f)) { Text(tr("share", "分享")) }
        }
    }
    PresetTransfer(transfer, store, exported) { transfer = "" }
}

/** Owned once by AuraShell, including when an import requests a switch from another page. */
@Composable internal fun PresetChangeGuard(store: EffectsStore) {
    val state by store.state.collectAsState()
    var naming by remember { mutableStateOf(false) }
    WindowDialog(state.pendingSelection != null && !naming, title = tr("preset_has_changes", "当前预设有修改"),
        summary = tr("save_before_switch", "是否保存当前预设后再应用其他预设？"), titleTextAlign = TextAlign.Start, summaryTextAlign = TextAlign.Start,
        onDismissRequest = { store.cancelPending() }) {
        GlassDialogButton({ naming = true }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("save", "保存")) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton({ store.cancelPending() }, Modifier.weight(1f)) { Text(tr("cancel", "取消")) }
            GlassDialogButton({ store.finishPending() }, Modifier.weight(1f)) { Text(tr("dont_save", "不保存")) }
        }
    }
    PresetDraftDialog(store, naming, onDismiss = { naming = false; store.cancelPending() }, onSaved = { store.finishPending(); naming = false })
}
