// SPDX-License-Identifier: AGPL-3.0-or-later
// HyperChanger theme seed picker, retained without Xposed settings transport.
package btm.m.auraplayer.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ThemeSeedColorPreference(
    title: String,
    color: Int,
    onPreviewColor: (Int) -> Unit,
    onDismissPreview: () -> Unit,
    onColorChange: (Int) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { showPicker = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body1)
        Box(
            Modifier.size(28.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = .45f), CircleShape)
                .padding(2.dp)
                .background(Color(color), CircleShape),
        )
    }
    var draftColor by remember(showPicker, color) { mutableIntStateOf(color) }
    WindowDialog(
        show = showPicker,
        onDismissRequest = {
            showPicker = false
            onDismissPreview()
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            ColorPalette(
                color = Color(draftColor or 0xFF000000.toInt()),
                onColorChanged = { selected ->
                    draftColor = selected.toArgb() or 0xFF000000.toInt()
                    onPreviewColor(draftColor)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(
                    onClick = {
                        showPicker = false
                        onDismissPreview()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(tr("取消", "取消")) }
                GlassDialogButton(
                    onClick = {
                        onColorChange(draftColor)
                        showPicker = false
                        onDismissPreview()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("确定", "确定")) }
            }
        }
    }
}
