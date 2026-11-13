package btm.m.auraplayer.ui

import android.app.Activity
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import btm.m.auraplayer.AuraViewModel
import btm.m.auraplayer.data.Song
import btm.m.auraplayer.tags.*
import coil3.compose.AsyncImage
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The shell asks this guard before committing either toolbar or predictive back. */
internal class TagEditorExitGuard {
    var handler: Pair<String, () -> Boolean>? = null
    fun intercept(route: String?) = handler?.takeIf { it.first == route }?.second?.invoke() == true
}
internal val LocalTagEditorExitGuard = staticCompositionLocalOf { TagEditorExitGuard() }
internal fun tagEditorRoute(song: Song) = "tagEditor:${Uri.encode(song.uri)}"

@Composable
internal fun TagEditorPage(model: AuraViewModel, song: Song, back: () -> Unit, isActive: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exitGuard = LocalTagEditorExitGuard.current
    var session by remember(song.uri) { mutableStateOf<TagEditSession?>(null) }
    var original by remember(song.uri) { mutableStateOf<AudioTagDocument?>(null) }
    var draft by remember(song.uri) { mutableStateOf<AudioTagDocument?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var selectingCover by remember { mutableStateOf(false) }
    var coverOpen by remember { mutableStateOf(false) }
    var coverDraft by remember { mutableStateOf<TagCover?>(null) }
    var field by remember { mutableStateOf<AudioTagField?>(null) }
    var fieldText by remember { mutableStateOf("") }
    var saveConfirm by remember { mutableStateOf(false) }
    var discardConfirm by remember { mutableStateOf(false) }
    var allowExit by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PreparedTagEdit?>(null) }
    var retryPublish by remember { mutableStateOf<(() -> Unit)?>(null) }
    val lifetime = remember { object { var disposed = false; var publishing = false } }
    val dirty = draft != null && draft != original
    val latestDirty by rememberUpdatedState(dirty)
    PredictiveBackHandler(enabled = isActive && (dirty || busy || selectingCover) && field == null && !coverOpen && message == null && !saveConfirm && !discardConfirm) { events ->
        try {
            events.collect { }
            if (!busy && !selectingCover) discardConfirm = true
        } catch (_: CancellationException) { /* A cancelled gesture keeps the draft. */ }
    }
    fun releasePending() { pending?.close(); pending = null; retryPublish = null }

    LaunchedEffect(song.uri) {
        try {
            val loaded = LocalAudioTags.load(context, song)
            session = loaded; original = loaded.original; draft = loaded.original
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = tr("tag_read_failed", "无法读取此文件的标签，或暂不支持此音频格式") }
        finally { loading = false }
    }
    DisposableEffect(Unit) {
        onDispose {
            lifetime.disposed = true
            if (!lifetime.publishing) { pending?.close(); session?.close() }
        }
    }
    DisposableEffect(isActive, song.uri) {
        val handler: () -> Boolean = {
            when {
                busy || selectingCover -> true
                latestDirty && !allowExit -> { discardConfirm = true; true }
                else -> false
            }
        }
        if (isActive) exitGuard.handler = tagEditorRoute(song) to handler
        onDispose { if (exitGuard.handler?.second === handler) exitGuard.handler = null }
    }
    val writePermission = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) retryPublish?.invoke()
        else { releasePending(); busy = false; message = tr("tag_permission_denied", "未获得修改权限，标签尚未保存") }
    }
    suspend fun publish(edit: PreparedTagEdit, mayRequestPermission: Boolean) {
        lifetime.publishing = true
        try {
            LocalAudioTags.publish(context, edit)
            original = edit.document
            model.localTagsSaved(song, edit.document)
            releasePending(); busy = false
            if (!lifetime.disposed) android.widget.Toast.makeText(context, tr("tag_saved", "标签已保存"), android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: CancellationException) { throw e }
        catch (e: SecurityException) {
            if (mayRequestPermission && !lifetime.disposed) {
                try {
                    retryPublish = { scope.launch { publish(edit, false) } }
                    writePermission.launch(IntentSenderRequest.Builder(MediaStore.createWriteRequest(context.contentResolver, listOf(edit.session.uri)).intentSender).build())
                } catch (_: Exception) { releasePending(); busy = false; message = tr("tag_permission_denied", "未获得修改权限，标签尚未保存") }
            } else { releasePending(); busy = false; message = tr("tag_permission_denied", "未获得修改权限，标签尚未保存") }
        } catch (e: Exception) {
            android.util.Log.e("AuraTagEditor", "Could not save audio tags", e)
            releasePending(); busy = false
            message = tr("tag_save_failed", "保存失败，请检查文件是否已被修改、存储空间及音频格式支持情况")
        } finally {
            lifetime.publishing = false
            if (lifetime.disposed) { pending?.close(); session?.close() }
        }
    }
    fun save() {
        val current = session ?: return
        val values = draft ?: return
        busy = true
        lifetime.publishing = true
        scope.launch {
            try { val edit = LocalAudioTags.prepare(current, values); pending = edit; publish(edit, true) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.e("AuraTagEditor", "Could not prepare audio tags", e)
                busy = false; message = tr("tag_save_failed", "保存失败，请检查文件是否已被修改、存储空间及音频格式支持情况")
            }
            finally { lifetime.publishing = false; if (lifetime.disposed) { pending?.close(); session?.close() } }
        }
    }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectingCover = true
            scope.launch {
                try { coverDraft = LocalAudioTags.importCover(context, uri) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { message = tr("tag_image_failed", "无法读取图片，请选择不超过 32 MB 的有效图片") }
                finally { selectingCover = false }
            }
        }
    }

    AppPage(tr("tag_editor", "标签编辑器"), onBack = back,
        floatingToolbarPosition = ToolbarPosition.BottomCenter,
        floatingToolbar = {
            val playerClearance = if (model.current != null)
                btm.m.auraplayer.ui.player.PlayerNavigationGeometry.barHeight + 22.dp else 0.dp
            Box(Modifier.fillMaxWidth().padding(end = 20.dp, bottom = playerClearance), contentAlignment = Alignment.CenterEnd) {
                AnimatedVisibility(dirty, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                    InteractiveToolbarGlass(Modifier.size(60.dp).semantics { if (busy) disabled() },
                        LocalToolbarBackdrop.current, MiuixTheme.colorScheme.primary.copy(alpha = .80f),
                        onClick = { if (!busy) saveConfirm = true }) {
                        Image(MiuixIcons.Basic.Check, tr("save", "保存"), Modifier.size(24.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onPrimary))
                    }
                }
            }
        }) { padding, _ ->
        val pagePadding = PaddingValues(start = LocalAuraLayout.current.pageMargin, end = LocalAuraLayout.current.pageMargin,
            top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 100.dp)
        LazyColumn(pageContentModifier(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                TagCoverPreview(draft?.cover, song.artwork, Modifier.fillMaxWidth().clickable(enabled = !loading && !busy) {
                    coverDraft = draft?.cover; coverOpen = true
                })
            }
            if (loading || busy) item { Text(if (loading) tr("loading", "加载中…") else tr("saving", "正在保存…"), Modifier.padding(12.dp)) }
            listOf(
                tr("tag_basic_info", "基础信息") to listOf(AudioTagField.TITLE, AudioTagField.ARTIST, AudioTagField.ALBUM, AudioTagField.COMPOSER, AudioTagField.LYRICIST, AudioTagField.YEAR),
                tr("tag_album_info", "专辑信息") to listOf(AudioTagField.TRACK, AudioTagField.ALBUM_ARTIST, AudioTagField.RELEASE_DATE),
                tr("other", "其他") to listOf(AudioTagField.COMMENT, AudioTagField.LYRICS),
            ).forEach { (title, fields) -> item {
                Group(title) { fields.forEach { entry ->
                    val value = draft?.get(entry).orEmpty()
                    val summary = if (entry.multiline) value.replace('\n', ' ').replace('\r', ' ').take(120).let { if (value.length > 120) "$it…" else it } else value
                    Preference(tagFieldTitle(entry), summary.takeIf(String::isNotEmpty) ?: tr("tag_not_set", "未填写"), enabled = draft != null && !busy) {
                        fieldText = draft?.get(entry).orEmpty(); field = entry
                    }
                } }
            } }
        }
        WindowDialog(field != null, title = field?.let { tagFieldTitle(it) }, titleTextAlign = TextAlign.Start,
            summary = if (field == AudioTagField.TRACK) tr("tag_track_digits", "音轨号只接受数字") else null,
            summaryTextAlign = TextAlign.Start, onDismissRequest = { field = null }) {
            val editing = field
            TextField(fieldText, { value -> if (editing?.accepts(value) == true) fieldText = value },
                singleLine = editing?.multiline != true, label = editing?.let { tagFieldTitle(it) }.orEmpty(),
                keyboardOptions = KeyboardOptions(keyboardType = if (editing == AudioTagField.TRACK) KeyboardType.Number else KeyboardType.Text),
                modifier = Modifier.fillMaxWidth().then(if (editing?.multiline == true) Modifier.heightIn(min = 150.dp, max = 280.dp) else Modifier))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton({ field = null }, Modifier.weight(1f)) { Text(tr("cancel", "取消")) }
                GlassDialogButton({ if (editing != null) draft = draft?.edited(editing, fieldText); field = null }, Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("save", "保存")) }
            }
        }
        WindowDialog(coverOpen, onDismissRequest = { if (!selectingCover) coverOpen = false }) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TagCoverPreview(coverDraft, song.artwork, Modifier.widthIn(max = 180.dp).fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassDialogButton({ pickCover.launch(arrayOf("image/*")) }, Modifier.weight(1f), enabled = !selectingCover) { Text(tr("select_file", "选择文件")) }
                    GlassDialogButton({ coverDraft = original?.cover }, Modifier.weight(1f), enabled = !selectingCover) { Text(tr("restore", "恢复")) }
                }
                GlassDialogButton({ draft = draft?.copy(cover = coverDraft); coverOpen = false }, Modifier.fillMaxWidth(), enabled = !selectingCover,
                    colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("save", "保存")) }
                GlassDialogButton({ coverOpen = false }, Modifier.fillMaxWidth(), enabled = !selectingCover) { Text(tr("cancel", "取消")) }
            }
        }
        WindowDialog(saveConfirm, title = tr("tag_confirm_save", "保存标签修改？"), titleTextAlign = TextAlign.Start,
            summary = tr("tag_write_original", "修改将写入此音乐文件"), summaryTextAlign = TextAlign.Start, onDismissRequest = { saveConfirm = false }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton({ saveConfirm = false }, Modifier.weight(1f)) { Text(tr("cancel", "取消")) }
                GlassDialogButton({ saveConfirm = false; save() }, Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("save", "保存")) }
            }
        }
        WindowDialog(discardConfirm, title = tr("tag_unsaved", "有尚未保存的修改"), titleTextAlign = TextAlign.Start,
            summary = tr("tag_discard_hint", "是否放弃修改并返回？"), summaryTextAlign = TextAlign.Start, onDismissRequest = { discardConfirm = false }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton({ discardConfirm = false }, Modifier.weight(1f)) { Text(tr("tag_keep_editing", "继续编辑")) }
                GlassDialogButton({ allowExit = true; discardConfirm = false; back() }, Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("tag_discard", "放弃修改")) }
            }
        }
        WindowDialog(message != null, title = tr("notice", "提示"), titleTextAlign = TextAlign.Start,
            summary = message, summaryTextAlign = TextAlign.Start, onDismissRequest = { message = null }) {
            GlassDialogButton({ message = null }, Modifier.fillMaxWidth()) { Text(tr("ok", "确定")) }
        }
    }
}

@Composable
private fun TagCoverPreview(cover: TagCover?, fallback: String, modifier: Modifier) {
    var fallbackRatio by remember(fallback) { mutableFloatStateOf(1f) }
    val ratio = remember(cover) {
        if (cover == null) null else {
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(cover.bytes, 0, cover.bytes.size, dimensions)
            if (dimensions.outWidth > 0 && dimensions.outHeight > 0) dimensions.outWidth.toFloat() / dimensions.outHeight else 1f
        }
    }
    Box(modifier.aspectRatio(ratio ?: fallbackRatio).clip(RoundedCornerShape(22.5.dp))
        .background(MiuixTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.CenterStart) {
        if (cover != null || fallback.isNotBlank()) AsyncImage(cover?.bytes ?: fallback, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            onSuccess = { if (cover == null) fallbackRatio = it.result.image.let { image -> if (image.width > 0 && image.height > 0) image.width.toFloat() / image.height else 1f } })
    }
}

private fun tagFieldTitle(field: AudioTagField): String = when (field) {
    AudioTagField.TITLE -> tr("song", "歌曲")
    AudioTagField.ARTIST -> tr("artist", "艺术家")
    AudioTagField.ALBUM -> tr("album", "专辑")
    AudioTagField.COMPOSER -> tr("tag_composer", "作曲家")
    AudioTagField.LYRICIST -> tr("tag_lyricist", "作词家")
    AudioTagField.YEAR -> tr("tag_year", "年")
    AudioTagField.TRACK -> tr("tag_track", "音轨号")
    AudioTagField.ALBUM_ARTIST -> tr("tag_album_artist", "专辑艺术家")
    AudioTagField.RELEASE_DATE -> tr("tag_release_date", "专辑发布时间")
    AudioTagField.COMMENT -> tr("tag_comment", "注释")
    AudioTagField.LYRICS -> tr("lyrics", "歌词")
}
