package btm.m.auraplayer.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.Format
import btm.m.auraplayer.data.Song
import btm.m.auraplayer.playback.LocalAudioFileInfo
import btm.m.auraplayer.ui.GlassDialogButton
import btm.m.auraplayer.ui.WindowDialog
import btm.m.auraplayer.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class AudioInfoRowItem(val label: String, val value: String) {
    fun formattedLine(): String = "$label: $value"
}

@Composable
internal fun AudioInfoDialog(
    song: Song,
    show: Boolean,
    playingFormat: Format?,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var info by remember(song.key) { mutableStateOf<LocalAudioFileInfo?>(null) }

    LaunchedEffect(show, song.key, playingFormat) {
        if (!show || song.cloud) return@LaunchedEffect
        info = withContext(Dispatchers.IO) {
            LocalAudioFileInfo.inspect(context, song, playingFormat)
        }
    }

    val currentInfo = info
    val titleText = song.title.ifBlank { currentInfo?.fileName ?: tr("audio_info", "音频信息") }
    val rows = buildAudioInfoRows(context, currentInfo)

    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, tr("copied", "已复制"), Toast.LENGTH_SHORT).show()
    }

    WindowDialog(
        show = show,
        title = titleText,
        titleTextAlign = TextAlign.Start,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            rows.forEach { row ->
                AudioInfoRow(
                    row = row,
                    onLongPress = { line ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        copyToClipboard(row.label, line)
                    },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassDialogButton(
                onClick = {
                    val all = rows.joinToString("\n") { it.formattedLine() }
                    copyToClipboard(tr("audio_info", "音频信息"), all)
                },
                modifier = Modifier.weight(1f),
                enabled = currentInfo != null,
            ) {
                Text(tr("copy_all", "全部复制"))
            }
            GlassDialogButton(
                onClick = onDismissRequest,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(tr("ok", "确定"))
            }
        }
    }
}

@Composable
private fun AudioInfoRow(
    row: AudioInfoRowItem,
    onLongPress: (String) -> Unit,
) {
    val line = row.formattedLine()
    val interactionSource = remember { MutableInteractionSource() }
    Text(
        text = line,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onLongClick = { onLongPress(line) },
                onClick = {},
            )
            .padding(vertical = 4.dp),
        color = DialogDefaults.summaryColor(),
        fontSize = MiuixTheme.textStyles.body2.fontSize,
        textAlign = TextAlign.Start,
    )
}

@Composable
private fun buildAudioInfoRows(context: Context, info: LocalAudioFileInfo?): List<AudioInfoRowItem> {
    val sizeText = info?.fileSizeBytes?.takeIf { it > 0L }?.let { Formatter.formatFileSize(context, it) } ?: "—"
    val modifiedText = info?.lastModifiedMs?.let { LocalAudioFileInfo.formatTimestamp(it) } ?: "—"
    val bitDepthText = info?.bits?.let { LocalAudioFileInfo.formatBitDepth(it, tr("bit", "bit")) } ?: "—"
    val bitrateText = info?.bitrate?.let { LocalAudioFileInfo.formatBitrate(it, tr("kbps", "kb/s")) } ?: "—"
    val sampleRateText = info?.sampleRate?.let { LocalAudioFileInfo.formatSampleRate(it, tr("khz", "kHz")) } ?: "—"
    return listOf(
        AudioInfoRowItem(tr("audio_info_file_path", "文件路径"), info?.filePath?.ifBlank { "—" } ?: "—"),
        AudioInfoRowItem(tr("audio_info_file_name", "文件名"), info?.fileName?.ifBlank { "—" } ?: "—"),
        AudioInfoRowItem(tr("audio_info_file_size", "文件大小"), sizeText),
        AudioInfoRowItem(tr("audio_info_last_modified", "最后修改时间"), modifiedText),
        AudioInfoRowItem(tr("audio_info_format", "格式"), info?.format?.ifBlank { "—" } ?: "—"),
        AudioInfoRowItem(tr("audio_info_bit_depth", "位深度"), bitDepthText),
        AudioInfoRowItem(tr("audio_info_bitrate", "比特率"), bitrateText),
        AudioInfoRowItem(tr("audio_info_sample_rate", "采样率"), sampleRateText),
        AudioInfoRowItem(tr("audio_info_codec", "编码格式"), info?.codec?.ifBlank { "—" } ?: "—"),
    )
}
