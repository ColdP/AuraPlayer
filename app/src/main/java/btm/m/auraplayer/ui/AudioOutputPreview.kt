package btm.m.auraplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import btm.m.auraplayer.audio.OutputWaveform
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AudioOutputPreview(waveform: OutputWaveform, isActive: Boolean) {
    var current by remember { mutableStateOf<OutputWaveform.Snapshot?>(null) }
    var visibility by remember { mutableFloatStateOf(0f) }
    LifecycleResumeEffect(isActive, waveform) {
        waveform.enabled = isActive
        onPauseOrDispose { waveform.enabled = false }
    }
    LaunchedEffect(waveform, isActive) {
        if (!isActive) { current = null; visibility = 0f; return@LaunchedEffect }
        while (true) {
            withFrameNanos {
                val snapshot = waveform.snapshot
                val fresh = waveform.enabled && snapshot != null && System.nanoTime() - snapshot.updatedNanos < 200_000_000L
                if (fresh) current = snapshot
                visibility += ((if (fresh) 1f else 0f) - visibility) * .18f
            }
        }
    }
    val color = MiuixTheme.colorScheme.primary
    val description = tr("audio_live_output", "实时音频输出")
    // Concentric corners: outer card 22.5 dp, inset 12 dp, inner radius 10.5 dp.
    Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(10.5.dp))
        .background(color.copy(alpha = .065f)).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(description, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Canvas(Modifier.fillMaxWidth().padding(top = 16.dp).height(100.dp).semantics { contentDescription = description }) {
            val center = size.height / 2
            drawLine(color.copy(alpha = .16f), Offset(0f, center), Offset(size.width, center), 1.dp.toPx())
            val peaks = current?.peaks ?: return@Canvas
            val count = peaks.size / 2
            val step = size.width / count
            val stroke = (step * .56f).coerceAtLeast(1f)
            for (i in 0 until count) {
                val x = (i + .5f) * step
                val scale = size.height * .46f * visibility
                drawLine(color.copy(alpha = .9f), Offset(x, center - peaks[i * 2 + 1] * scale),
                    Offset(x, center - peaks[i * 2] * scale), strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
    }
}
