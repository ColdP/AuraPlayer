// SPDX-License-Identifier: AGPL-3.0-or-later
// HyperChanger EditableSliderPreference and its numeric glass dialog.
package btm.m.auraplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun EditableSliderPreference(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    title: String?,
    valueText: String?,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    defaultValue: Float = valueRange.start,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    centerValue: Float? = null,
) {
    var showValueDialog by remember { mutableStateOf(false) }
    // Asymmetric signed ranges still place their neutral value at the physical midpoint.
    val center = centerValue?.takeIf { it > valueRange.start && it < valueRange.endInclusive }
    val sliderValue = if (center == null) value else if (value <= center) {
        .5f * (value - valueRange.start) / (center - valueRange.start)
    } else {
        .5f + .5f * (value - center) / (valueRange.endInclusive - center)
    }
    val mapSliderPosition: (Float) -> Float = { position ->
        val adjusted = if (center == null) position else if (position <= .5f) {
            valueRange.start + position * 2f * (center - valueRange.start)
        } else {
            center + (position - .5f) * 2f * (valueRange.endInclusive - center)
        }
        if (center == null) adjusted else normalizeSliderValue(adjusted, valueRange, steps)
    }
    when (LocalAppUiStyle.current) {
        AppUiStyle.MATERIAL -> {
            val scheme = rememberActiveExpressiveScheme()
            val alpha = if (enabled) 1f else .38f
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (!title.isNullOrBlank()) {
                        Text(
                            text = title,
                            color = scheme.onSurface.copy(alpha = alpha),
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (!valueText.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(scheme.secondaryContainer.copy(alpha = alpha))
                                .clickable(enabled = enabled) { showValueDialog = true }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = valueText,
                                color = scheme.onSecondaryContainer.copy(alpha = alpha),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                MaterialExpressiveSlider(
                    value = sliderValue,
                    onValueChange = { position -> onValueChange(mapSliderPosition(position)) },
                    onValueChangeFinished = onValueChangeFinished,
                    valueRange = if (center == null) valueRange else 0f..1f,
                    steps = if (center == null) steps else 0,
                    enabled = enabled,
                )
            }
        }
        AppUiStyle.ONE -> {
            val palette = rememberActiveOneUiPalette()
            val alpha = if (enabled) 1f else .42f
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (!title.isNullOrBlank()) {
                        Text(
                            text = title,
                            color = androidx.compose.ui.graphics.Color(palette.text).copy(alpha = alpha),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (!valueText.isNullOrBlank()) {
                        Text(
                            text = valueText,
                            color = androidx.compose.ui.graphics.Color(palette.accentDeep).copy(alpha = alpha),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(enabled = enabled) { showValueDialog = true }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                OneUiSlider(
                    value = sliderValue,
                    onValueChange = { position -> onValueChange(mapSliderPosition(position)) },
                    onValueChangeFinished = onValueChangeFinished,
                    valueRange = if (center == null) valueRange else 0f..1f,
                    steps = if (center == null) steps else 0,
                    enabled = enabled,
                )
            }
        }
        AppUiStyle.HYPER -> {
            top.yukonga.miuix.kmp.preference.SliderPreference(
                value = sliderValue,
                onValueChange = { position -> onValueChange(mapSliderPosition(position)) },
                onValueChangeFinished = onValueChangeFinished,
                title = title,
                valueText = valueText,
                valueRange = if (center == null) valueRange else 0f..1f,
                steps = if (center == null) steps else 0,
                enabled = enabled,
                modifier = modifier,
                insideMargin = CompactPreferencePadding,
                onClick = if (enabled) ({ showValueDialog = true }) else null,
            )
        }
    }
    if (showValueDialog) {
        SliderValueDialog(
            sliderTitle = title.orEmpty(),
            value = value,
            defaultValue = defaultValue,
            valueRange = valueRange,
            steps = steps,
            onDismiss = { showValueDialog = false },
            onSave = { adjustedValue ->
                onValueChange(adjustedValue)
                onValueChangeFinished()
                showValueDialog = false
            },
        )
    }
}

@Composable
private fun SliderValueDialog(
    sliderTitle: String,
    value: Float,
    defaultValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onDismiss: () -> Unit,
    onSave: (Float) -> Unit,
) {
    var draft by remember(value) { mutableStateOf(formatSliderInput(value)) }
    val enteredValue = draft.trim().toFloatOrNull()
    val canSave = enteredValue != null &&
        enteredValue.isFinite() &&
        enteredValue >= valueRange.start &&
        enteredValue <= valueRange.endInclusive

    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        imeBottomSafetyFraction = .175f,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("数值调整", "数值调整"), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            Text(sliderTitle, style = MiuixTheme.textStyles.body2)
            AuraTextField(
                value = draft,
                onValueChange = { draft = it },
                label = sliderTitle,
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        val valid = enteredValue?.takeIf { canSave } ?: return@KeyboardActions
                        onSave(normalizeSliderValue(valid, valueRange, steps))
                        onDismiss()
                    },
                ),
                cornerRadius = 999.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            GlassDialogButton(
                onClick = { draft = formatSliderInput(defaultValue.coerceIn(valueRange.start, valueRange.endInclusive)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("恢复默认数值", "恢复默认数值")) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("取消", "取消")) }
                GlassDialogButton(
                    onClick = {
                        val valid = enteredValue?.takeIf { canSave } ?: return@GlassDialogButton
                        onSave(normalizeSliderValue(valid, valueRange, steps))
                    },
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("保存", "保存")) }
            }
        }
    }
}

private fun normalizeSliderValue(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
): Float {
    if (steps <= 0) return value.coerceIn(valueRange.start, valueRange.endInclusive)
    val interval = (valueRange.endInclusive - valueRange.start) / (steps + 1)
    if (interval <= 0f) return valueRange.start
    return (valueRange.start + ((value - valueRange.start) / interval).roundToInt() * interval)
        .coerceIn(valueRange.start, valueRange.endInclusive)
}

private fun formatSliderInput(value: Float): String {
    val rounded = (value * 100_000f).roundToInt() / 100_000f
    return if (rounded == rounded.roundToInt().toFloat()) rounded.roundToInt().toString()
    else "%.5f".format(Locale.US, rounded).trimEnd('0').trimEnd('.')
}
