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
import btm.m.auraplayer.data.LocalAudioArtwork
import coil3.Image
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
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

private const val DEFAULT_ARTWORK_SEED = 0xFF6750A4.toInt()
private const val ARTWORK_SEED_CACHE_LIMIT = 512
private const val ALBUM_SEED_CACHE_LIMIT = 256
private val artworkSeedCache = LinkedHashMap<String, Int>(64, 0.75f, true)
private val albumArtSeedCache = LinkedHashMap<String, Int>(64, 0.75f, true)
private val oklabArtworkSeedCache = LinkedHashMap<String, Int>(64, 0.75f, true)
private val oklabAlbumArtSeedCache = LinkedHashMap<String, Int>(64, 0.75f, true)

internal data class CachedArtworkMemoryEntry(
    val key: MemoryCache.Key,
    val image: Image,
    val exactUriMatch: Boolean,
)

internal fun findCachedArtworkMemoryEntry(
    imageLoader: ImageLoader,
    artwork: String,
    requireSoftwareBitmap: Boolean = false,
): CachedArtworkMemoryEntry? {
    if (artwork.isBlank()) return null
    val cache = imageLoader.memoryCache ?: return null
    val targetAlbumArt = LocalAudioArtwork.parse(artwork)?.albumArtUri?.takeIf { it.isNotBlank() }
    var bestExact: CachedArtworkMemoryEntry? = null
    var bestExactArea = -1
    var bestAlbum: CachedArtworkMemoryEntry? = null
    var bestAlbumArea = -1
    for (key in cache.keys) {
        val keyUri = key.key
        val exact = keyUri == artwork
        val albumMatch = !exact && targetAlbumArt != null &&
            LocalAudioArtwork.parse(keyUri)?.albumArtUri == targetAlbumArt
        if (!exact && !albumMatch) continue
        val image = cache[key]?.image ?: continue
        if (requireSoftwareBitmap) {
            val bitmap = runCatching { image.toBitmap() }.getOrNull() ?: continue
            if (bitmap.config == Bitmap.Config.HARDWARE) continue
        }
        val area = image.width * image.height
        if (exact) {
            if (area > bestExactArea) {
                bestExactArea = area
                bestExact = CachedArtworkMemoryEntry(key, image, exactUriMatch = true)
            }
        } else if (area > bestAlbumArea) {
            bestAlbumArea = area
            bestAlbum = CachedArtworkMemoryEntry(key, image, exactUriMatch = false)
        }
    }
    return bestExact ?: bestAlbum
}

private fun cachedInitialArtworkSeed(artwork: String, useOklab: Boolean = false): Int? {
    if (artwork.isBlank()) return DEFAULT_ARTWORK_SEED
    val primaryCache = if (useOklab) oklabArtworkSeedCache else artworkSeedCache
    val albumCache = if (useOklab) oklabAlbumArtSeedCache else albumArtSeedCache
    synchronized(primaryCache) {
        primaryCache[artwork]?.let { return it }
    }
    val albumArt = LocalAudioArtwork.parse(artwork)?.albumArtUri?.takeIf { it.isNotBlank() } ?: return null
    return synchronized(albumCache) { albumCache[albumArt] }
}

private fun recordArtworkSeed(artwork: String, seed: Int, useOklab: Boolean = false) {
    if (artwork.isBlank()) return
    val primaryCache = if (useOklab) oklabArtworkSeedCache else artworkSeedCache
    val albumCache = if (useOklab) oklabAlbumArtSeedCache else albumArtSeedCache
    synchronized(primaryCache) {
        primaryCache[artwork] = seed
        while (primaryCache.size > ARTWORK_SEED_CACHE_LIMIT) {
            val first = primaryCache.entries.firstOrNull()?.key ?: break
            primaryCache.remove(first)
        }
    }
    LocalAudioArtwork.parse(artwork)?.albumArtUri?.takeIf { it.isNotBlank() }?.let { albumArt ->
        synchronized(albumCache) {
            albumCache[albumArt] = seed
            while (albumCache.size > ALBUM_SEED_CACHE_LIMIT) {
                val first = albumCache.entries.firstOrNull()?.key ?: break
                albumCache.remove(first)
            }
        }
    }
}

private fun coverMonetSeed(bitmap: Bitmap?): Int {
    val fallback = DEFAULT_ARTWORK_SEED
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
internal fun rememberPlayerArtworkSeed(artwork: String): Int =
    rememberPlayerArtworkColorSeed(artwork, useOklab = false)

@Composable
internal fun rememberPlayerArtworkColorSeed(artwork: String, useOklab: Boolean): Int {
    val context = LocalContext.current
    val initialSeed = remember(artwork, useOklab) { cachedInitialArtworkSeed(artwork, useOklab) }
    val seed by produceState(initialSeed ?: DEFAULT_ARTWORK_SEED, artwork, useOklab) {
        if (artwork.isBlank()) {
            value = DEFAULT_ARTWORK_SEED
            return@produceState
        }
        val primaryCache = if (useOklab) oklabArtworkSeedCache else artworkSeedCache
        synchronized(primaryCache) { primaryCache[artwork] }?.let {
            value = it
            return@produceState
        }
        val imageLoader = SingletonImageLoader.get(context)
        val cachedSoftware = findCachedArtworkMemoryEntry(
            imageLoader = imageLoader,
            artwork = artwork,
            requireSoftwareBitmap = true,
        )
        val computed = withContext(Dispatchers.IO) {
            val bitmap = if (cachedSoftware != null && cachedSoftware.exactUriMatch) {
                runCatching { cachedSoftware.image.toBitmap() }.getOrNull()
            } else {
                runCatching {
                    (imageLoader.execute(
                        ImageRequest.Builder(context)
                            .data(artwork)
                            .size(128)
                            .allowHardware(false)
                            .build(),
                    ) as? SuccessResult)?.image?.toBitmap()
                }.getOrNull()
            }
            if (useOklab) {
                btm.m.auraplayer.ui.OklabThemeEngine.extractOklabSeed(bitmap, DEFAULT_ARTWORK_SEED)
            } else {
                coverMonetSeed(bitmap)
            }
        }
        recordArtworkSeed(artwork, computed, useOklab)
        value = computed
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
internal fun rememberPlayerExpressiveColors(
    seed: Int,
    isDark: Boolean,
    useOklab: Boolean = false,
    paletteIndex: Int = 0,
): PlayerExpressiveColors {
    val target = remember(seed, isDark, useOklab, paletteIndex) {
        if (useOklab) {
            with(btm.m.auraplayer.ui.OklabThemeEngine) {
                generateScheme(seed, isDark, paletteIndex)
            }.let { scheme ->
                btm.m.auraplayer.ui.AuraExpressiveScheme(
                    dark = scheme.dark,
                    surface = scheme.surface,
                    surfaceContainerLowest = scheme.surfaceContainerLowest,
                    surfaceContainerLow = scheme.surfaceContainerLow,
                    surfaceContainer = scheme.surfaceContainer,
                    surfaceContainerHigh = scheme.surfaceContainerHigh,
                    surfaceContainerHighest = scheme.surfaceContainerHighest,
                    onSurface = scheme.onSurface,
                    onSurfaceVariant = scheme.onSurfaceVariant,
                    primary = scheme.primary,
                    onPrimary = scheme.onPrimary,
                    primaryContainer = scheme.primaryContainer,
                    onPrimaryContainer = scheme.onPrimaryContainer,
                    secondary = scheme.secondary,
                    onSecondary = scheme.onSecondary,
                    secondaryContainer = scheme.secondaryContainer,
                    onSecondaryContainer = scheme.onSecondaryContainer,
                    tertiary = scheme.tertiary,
                    onTertiary = scheme.onTertiary,
                    tertiaryContainer = scheme.tertiaryContainer,
                    onTertiaryContainer = scheme.onTertiaryContainer,
                    error = scheme.error,
                    onError = scheme.onError,
                    errorContainer = scheme.errorContainer,
                    onErrorContainer = scheme.onErrorContainer,
                    outline = scheme.outline,
                    outlineVariant = scheme.outlineVariant,
                ).toPlayerExpressiveColors()
            }
        } else {
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
internal fun PlayerAmbientBackground(
    palette: CorePalette,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    visible: Boolean = true,
    isDark: Boolean = true,
    oklabAmbient: btm.m.auraplayer.ui.OklabPlayerAmbientPalette? = null,
) {
    val colorSpec: FiniteAnimationSpec<Color> = if (visible) tween(1000) else snap()
    val targetPrimary = oklabAmbient?.orbPrimary ?: Color(palette.a1.tone(if (isDark) 70 else 84))
    val targetSecondary = oklabAmbient?.orbSecondary ?: Color(palette.a2.tone(if (isDark) 70 else 86))
    val targetTertiary = oklabAmbient?.orbTertiary ?: Color(palette.a3.tone(if (isDark) 70 else 84))
    val targetBase = oklabAmbient?.base ?: Color(palette.n1.tone(if (isDark) 8 else 96))
    val primary by animateColorAsState(targetPrimary, colorSpec, label = "ambientMonetPrimary")
    val secondary by animateColorAsState(targetSecondary, colorSpec, label = "ambientMonetSecondary")
    val tertiary by animateColorAsState(targetTertiary, colorSpec, label = "ambientMonetTertiary")
    val base by animateColorAsState(targetBase, colorSpec, label = "ambientMonetBase")
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate, visible) {
        if (!visible) {
            phase = 0f
            return@LaunchedEffect
        }
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
