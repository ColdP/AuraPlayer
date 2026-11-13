// SPDX-License-Identifier: AGPL-3.0-or-later
// Monet extraction and ambient orbit adapted from HyperChanger ContributorsGallery.kt.
package btm.m.auraplayer.ui.player

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.CorePalette
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.score.Score
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

private fun coverMonetSeed(bitmap: Bitmap?): Int {
    val fallback = 0xFF6750A4.toInt()
    if (bitmap == null) return fallback
    val pixels = ArrayList<Int>(4096)
    for (y in 0 until 64) for (x in 0 until 64) {
        val pixel = bitmap.getPixel(x * bitmap.width / 64, y * bitmap.height / 64)
        if (android.graphics.Color.alpha(pixel) >= 128) pixels.add(pixel or 0xFF000000.toInt())
    }
    if (pixels.isEmpty()) return fallback
    return Score.score(QuantizerCelebi.quantize(pixels.toIntArray(), 128), 1, fallback, true).firstOrNull() ?: fallback
}

@Composable
internal fun rememberPlayerArtworkSeed(artwork: String): Int {
    val context = LocalContext.current
    val seed by produceState(0xFF6750A4.toInt(), artwork) {
        value = withContext(Dispatchers.IO) {
            val bitmap = if (artwork.isBlank()) null else runCatching {
                (SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(artwork).size(128).allowHardware(false).build()) as? SuccessResult)?.image?.toBitmap()
            }.getOrNull()
            coverMonetSeed(bitmap)
        }
    }
    return seed
}

@Composable
internal fun rememberPlayerMonetPalette(artwork: String): CorePalette {
    val seed = rememberPlayerArtworkSeed(artwork)
    return remember(seed) { CorePalette.of(seed) }
}

@Immutable
internal data class PlayerExpressiveColors(
    val dark: Boolean,
    val surface: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val outlineVariant: Color,
    val activeInk: Color,
    val secondaryInk: Color,
    val timeInk: Color,
    val iconInk: Color,
    val dragHandleColor: Color,
)

@Composable
internal fun rememberPlayerExpressiveColors(seed: Int, isDark: Boolean): PlayerExpressiveColors {
    val target = remember(seed, isDark) {
        val scheme = SchemeExpressive(Hct.fromInt(seed), isDark, 0.0, ColorSpec.SpecVersion.SPEC_2025)
        val onSurface = Color(scheme.onSurface)
        val onSurfaceVariant = Color(scheme.onSurfaceVariant)
        val primary = Color(scheme.primary)
        val secondary = Color(scheme.secondary)
        PlayerExpressiveColors(
            dark = isDark,
            surface = Color(scheme.surface),
            surfaceContainerLow = Color(scheme.surfaceContainerLow),
            surfaceContainer = Color(scheme.surfaceContainer),
            surfaceContainerHigh = Color(scheme.surfaceContainerHigh),
            surfaceContainerHighest = Color(scheme.surfaceContainerHighest),
            onSurface = onSurface,
            onSurfaceVariant = onSurfaceVariant,
            primary = primary,
            onPrimary = Color(scheme.onPrimary),
            primaryContainer = Color(scheme.primaryContainer),
            onPrimaryContainer = Color(scheme.onPrimaryContainer),
            secondary = secondary,
            secondaryContainer = Color(scheme.secondaryContainer),
            onSecondaryContainer = Color(scheme.onSecondaryContainer),
            tertiaryContainer = Color(scheme.tertiaryContainer),
            onTertiaryContainer = Color(scheme.onTertiaryContainer),
            outlineVariant = Color(scheme.outlineVariant),
            activeInk = onSurface,
            secondaryInk = lerp(onSurfaceVariant, primary, if (isDark) .40f else .32f),
            timeInk = lerp(onSurfaceVariant, primary, .24f),
            iconInk = lerp(onSurfaceVariant, secondary, .28f),
            dragHandleColor = lerp(onSurfaceVariant, primary, .35f).copy(alpha = if (isDark) .70f else .56f),
        )
    }
    val animSpec = tween<Color>(700, easing = FastOutSlowInEasing)
    return PlayerExpressiveColors(
        dark = target.dark,
        surface = animateColorAsState(target.surface, animSpec, label = "expSurface").value,
        surfaceContainerLow = animateColorAsState(target.surfaceContainerLow, animSpec, label = "expSurfaceLow").value,
        surfaceContainer = animateColorAsState(target.surfaceContainer, animSpec, label = "expSurfaceCont").value,
        surfaceContainerHigh = animateColorAsState(target.surfaceContainerHigh, animSpec, label = "expSurfaceHigh").value,
        surfaceContainerHighest = animateColorAsState(target.surfaceContainerHighest, animSpec, label = "expSurfaceHighest").value,
        onSurface = animateColorAsState(target.onSurface, animSpec, label = "expOnSurface").value,
        onSurfaceVariant = animateColorAsState(target.onSurfaceVariant, animSpec, label = "expOnSurfaceVar").value,
        primary = animateColorAsState(target.primary, animSpec, label = "expPrimary").value,
        onPrimary = animateColorAsState(target.onPrimary, animSpec, label = "expOnPrimary").value,
        primaryContainer = animateColorAsState(target.primaryContainer, animSpec, label = "expPrimaryCont").value,
        onPrimaryContainer = animateColorAsState(target.onPrimaryContainer, animSpec, label = "expOnPrimaryCont").value,
        secondary = animateColorAsState(target.secondary, animSpec, label = "expSecondary").value,
        secondaryContainer = animateColorAsState(target.secondaryContainer, animSpec, label = "expSecondaryCont").value,
        onSecondaryContainer = animateColorAsState(target.onSecondaryContainer, animSpec, label = "expOnSecondaryCont").value,
        tertiaryContainer = animateColorAsState(target.tertiaryContainer, animSpec, label = "expTertiaryCont").value,
        onTertiaryContainer = animateColorAsState(target.onTertiaryContainer, animSpec, label = "expOnTertiaryCont").value,
        outlineVariant = animateColorAsState(target.outlineVariant, animSpec, label = "expOutlineVar").value,
        activeInk = animateColorAsState(target.activeInk, animSpec, label = "expActiveInk").value,
        secondaryInk = animateColorAsState(target.secondaryInk, animSpec, label = "expSecondaryInk").value,
        timeInk = animateColorAsState(target.timeInk, animSpec, label = "expTimeInk").value,
        iconInk = animateColorAsState(target.iconInk, animSpec, label = "expIconInk").value,
        dragHandleColor = animateColorAsState(target.dragHandleColor, animSpec, label = "expDragHandle").value,
    )
}

@Composable
internal fun PlayerAmbientBackground(palette: CorePalette, modifier: Modifier = Modifier, animate: Boolean = true) {
    val primary by animateColorAsState(Color(palette.a1.tone(70)), tween(1000), label = "ambientMonetPrimary")
    val secondary by animateColorAsState(Color(palette.a2.tone(70)), tween(1000), label = "ambientMonetSecondary")
    val tertiary by animateColorAsState(Color(palette.a3.tone(70)), tween(1000), label = "ambientMonetTertiary")
    val base by animateColorAsState(Color(palette.n1.tone(8)), tween(1000), label = "ambientMonetBase")
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            phase = (phase + ((now - last) / 1e9f).coerceAtMost(.05f) * (2 * PI / 12).toFloat()) % (2 * PI).toFloat()
            last = now
        }
    }
    Canvas(modifier) {
        drawRect(base)
        val centers = listOf(
            Offset(size.width * (.22f + .25f * sin(phase)), size.height * (.25f + .18f * cos(phase))),
            Offset(size.width * (.78f + .20f * cos(phase)), size.height * (.5f + .30f * sin(phase))),
            Offset(size.width * (.38f + .28f * cos(phase)), size.height * (.85f + .18f * sin(phase))),
        )
        val orbs = listOf(primary, secondary, tertiary)
        centers.forEachIndexed { index, center ->
            drawCircle(Brush.radialGradient(listOf(orbs[index].copy(alpha = .72f), Color.Transparent),
                center = center, radius = size.width * .85f), radius = size.width * .85f, center = center)
        }
    }
}
