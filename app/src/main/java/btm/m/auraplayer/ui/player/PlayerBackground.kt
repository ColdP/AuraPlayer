// SPDX-License-Identifier: AGPL-3.0-or-later
// Monet extraction and 22-second ambient orbit copied from HyperChanger ContributorsGallery.kt.
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
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.materialkolor.palettes.CorePalette
import com.materialkolor.quantize.QuantizerCelebi
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
internal fun rememberPlayerMonetPalette(artwork: String): CorePalette {
    val context = LocalContext.current
    val seed by produceState(0xFF6750A4.toInt(), artwork) {
        value = withContext(Dispatchers.IO) {
            val bitmap = if (artwork.isBlank()) null else runCatching {
                (SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(artwork).size(128).allowHardware(false).build()) as? SuccessResult)?.image?.toBitmap()
            }.getOrNull()
            coverMonetSeed(bitmap)
        }
    }
    return remember(seed) { CorePalette.of(seed) }
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
            phase = (phase + ((now - last) / 1e9f).coerceAtMost(.05f) * (2 * PI / 22).toFloat()) % (2 * PI).toFloat()
            last = now
        }
    }
    Canvas(modifier) {
        drawRect(base)
        val centers = listOf(
            Offset(size.width * (.22f + .18f * sin(phase)), size.height * (.25f + .12f * cos(phase))),
            Offset(size.width * (.82f + .13f * cos(phase)), size.height * (.5f + .23f * sin(phase))),
            Offset(size.width * (.35f + .22f * cos(phase)), size.height * (.94f + .12f * sin(phase))),
        )
        val orbs = listOf(primary, secondary, tertiary)
        centers.forEachIndexed { index, center ->
            drawCircle(Brush.radialGradient(listOf(orbs[index].copy(alpha = .56f), Color.Transparent),
                center = center, radius = size.width * .95f), radius = size.width * .95f, center = center)
        }
    }
}
