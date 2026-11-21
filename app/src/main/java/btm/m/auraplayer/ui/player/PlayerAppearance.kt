package btm.m.auraplayer.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import btm.m.auraplayer.playback.*
import coil3.compose.AsyncImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.toBitmap
import com.materialkolor.palettes.CorePalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.*
import kotlin.random.Random

@Composable
internal fun PlayerThemeBackground(
    artwork: String,
    palette: CorePalette,
    config: NowPlayingConfig,
    compactProgress: Float,
    animate: Boolean,
    queueBlend: Float = 0f,
    spatialTilt: Offset = Offset.Zero,
    expressive: PlayerExpressiveColors? = null,
    oneUi: PlayerOneUiColors? = null,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    prepare: Boolean = false,
    isDark: Boolean = true,
    useOklab: Boolean = false,
    oklabPaletteIndex: Int = 0,
    seed: Int? = null,
) {
    val advancedMaterial = btm.m.auraplayer.ui.LocalAdvancedMaterialEnabled.current
    // Keep decoded pixels outside the theme Crossfade and outside Coil's loading state. A new
    // model must never clear the last successful background while its artwork is being decoded.
    val context = LocalContext.current
    val usesArtwork = advancedMaterial && config.usesArtworkBackground
    var background by remember { mutableStateOf<ImageBitmap?>(null) }
    var previousBackground by remember { mutableStateOf<ImageBitmap?>(null) }
    var loadedArtwork by remember { mutableStateOf("") }
    val artworkBlend = remember { Animatable(1f) }
    val visibility = rememberUpdatedState(visible)
    val backgroundVisible by visibility
    val canLoadFullArtwork by rememberUpdatedState(visible || prepare)
    LaunchedEffect(artwork, usesArtwork, context) {
        artworkBlend.snapTo(1f)
        previousBackground = null
        if (!backgroundVisible && loadedArtwork != artwork) {
            background = null
            loadedArtwork = ""
        }
        if (!usesArtwork || artwork.isBlank()) {
            loadedArtwork = ""
            return@LaunchedEffect
        }
        val cached = withContext(Dispatchers.IO) {
            findCachedArtworkMemoryEntry(SingletonImageLoader.get(context), artwork)?.let { entry ->
                val bitmap = runCatching { entry.image.toBitmap().asImageBitmap() }.getOrNull()
                    ?: return@let null
                val largeEnough = entry.exactUriMatch && minOf(entry.image.width, entry.image.height) >= 512
                bitmap to largeEnough
            }
        }
        if (cached != null) {
            val (cachedBitmap, largeEnough) = cached
            if (loadedArtwork != artwork || background == null) {
                previousBackground = background
                background = cachedBitmap
                loadedArtwork = artwork
                if (backgroundVisible && previousBackground != null) {
                    artworkBlend.snapTo(0f)
                    artworkBlend.animateTo(1f, tween(400))
                }
                previousBackground = null
            }
            if (largeEnough) return@LaunchedEffect
        }
        snapshotFlow { canLoadFullArtwork }.first { it }
        val decoded = try {
            withContext(Dispatchers.IO) {
                val imageLoader = SingletonImageLoader.get(context)
                (imageLoader.execute(
                    ImageRequest.Builder(context).data(artwork).size(1024).allowHardware(false).build()
                ) as? SuccessResult)?.image?.toBitmap()?.asImageBitmap()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@LaunchedEffect
        if (loadedArtwork == artwork && background != null) {
            background = decoded
        } else {
            previousBackground = background
            background = decoded
            loadedArtwork = artwork
            if (backgroundVisible && previousBackground != null) {
                artworkBlend.snapTo(0f)
                artworkBlend.animateTo(1f, tween(400))
            }
            previousBackground = null
        }
    }
    if (!visible && !prepare) return
    val usesFlowingArt = advancedMaterial && (config.theme == PlayerTheme.AURORA_BLUR ||
        (config.theme == PlayerTheme.MATERIAL && config.materialBackground == MaterialPlayerBackground.AURORA_BLUR))
    val usesMaterialWash = advancedMaterial && config.theme == PlayerTheme.MATERIAL && config.materialBackground == MaterialPlayerBackground.MATERIAL
    val lyricOverlayBlend = if (!advancedMaterial || config.lyricTheme == LyricTheme.BLUR) {
        0f
    } else {
        (compactProgress * (1f - queueBlend)).coerceIn(0f, 1f)
    }
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(visible, animate, usesFlowingArt, usesMaterialWash, config.weather) {
        if (!visible) {
            phase = 0f
            return@LaunchedEffect
        }
        if (!animate || (!usesFlowingArt && !usesMaterialWash && config.weather == PlayerWeather.NONE)) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            phase = (phase + ((now - last) / 1e9f).coerceAtMost(.05f)) % 120f
            last = now
        }
    }
    val effectiveSeed = seed ?: rememberPlayerArtworkColorSeed(artwork, useOklab)
    val oklabAmbient = remember(useOklab, effectiveSeed, isDark, oklabPaletteIndex) {
        if (useOklab) btm.m.auraplayer.ui.OklabThemeEngine.generatePlayerAmbient(effectiveSeed, isDark, oklabPaletteIndex) else null
    }
    val backgroundSpec: FiniteAnimationSpec<Float> = if (visible) tween(220) else snap()
    val blur by animateFloatAsState(config.blurRadius, backgroundSpec, label = "playerBackgroundBlur")
    val materialMix by animateFloatAsState((config.materialColorMix / 100f).coerceIn(0f, 1f), backgroundSpec, label = "playerMaterialMix")
    val baseSpatialWeight = if (config.spatialLyrics) (compactProgress * (1f - queueBlend)).coerceIn(0f, 1f) else 0f
    PlayerBackgroundLayout(modifier, visibility) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (baseSpatialWeight > 0.001f) {
                        val overscan = 1f + 0.048f * baseSpatialWeight
                        scaleX = overscan
                        scaleY = overscan
                        translationX = -spatialTilt.x * 16.dp.toPx() * baseSpatialWeight
                        translationY = -spatialTilt.y * 12.dp.toPx() * baseSpatialWeight
                        rotationY = -spatialTilt.x * 3.6f * baseSpatialWeight
                        rotationX = spatialTilt.y * 2.8f * baseSpatialWeight
                        cameraDistance = 12f * density
                    }
                },
        ) {
        Crossfade(config.theme to config.materialBackground, animationSpec = if (visible) tween(380) else snap(), label = "playerTheme") { (theme, materialBg) ->
            val materialMode = theme == PlayerTheme.MATERIAL
            val oneUiMode = theme == PlayerTheme.ONE && oneUi != null
            val baseColor = when {
                materialMode && materialBg == MaterialPlayerBackground.MATERIAL && expressive != null -> expressive.surface
                oneUiMode -> oneUi.background
                oklabAmbient != null -> oklabAmbient.surface
                else -> Color(palette.a2.tone(if (isDark) 14 else 93))
            }
            Box(Modifier.fillMaxSize().background(baseColor)) {
                val showAurora = advancedMaterial && (theme == PlayerTheme.AURORA ||
                    theme == PlayerTheme.AURORA_BLUR ||
                    (materialMode && (materialBg == MaterialPlayerBackground.AURORA || materialBg == MaterialPlayerBackground.AURORA_BLUR)))
                val showArtwork = advancedMaterial && (theme == PlayerTheme.AURORA_BLUR ||
                    theme == PlayerTheme.BLUR ||
                    theme == PlayerTheme.FULLSCREEN ||
                    (materialMode && (materialBg == MaterialPlayerBackground.BLUR || materialBg == MaterialPlayerBackground.AURORA_BLUR)))
                if (advancedMaterial && materialMode && materialBg == MaterialPlayerBackground.MATERIAL && expressive != null) {
                    MaterialExpressiveSurfaceWash(expressive, { phase }, washStrength = 1f - materialMix * .92f, Modifier.fillMaxSize())
                }
                if (advancedMaterial && oneUiMode) {
                    OneUiSurfaceWash(oneUi, Modifier.fillMaxSize())
                }
                if (showAurora) {
                    PlayerAmbientBackground(
                        palette = palette,
                        modifier = Modifier.fillMaxSize(),
                        animate = animate,
                        visible = visible,
                        isDark = isDark,
                        oklabAmbient = oklabAmbient,
                    )
                }
                if (showArtwork) {
                    val flowing = theme == PlayerTheme.AURORA_BLUR ||
                        (materialMode && materialBg == MaterialPlayerBackground.AURORA_BLUR)
                    val full = theme == PlayerTheme.FULLSCREEN
                    val radius = if (full) 40f * compactProgress else blur
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        scaleX = if (full) 1.04f else 1.22f; scaleY = scaleX
                        if (flowing) {
                            translationX = sin(phase * .42f) * size.width * .07f
                            translationY = cos(phase * .34f) * size.height * .055f
                            rotationZ = sin(phase * .22f) * 4f
                            // Let the moving dynamic colors show through the blurred artwork.
                            alpha = if (isDark) .38f else .30f
                        }
                    }.blur(radius.dp)) {
                        // The outgoing image stays fully opaque beneath the incoming image;
                        // fading both layers out would darken the background during the blend.
                        previousBackground?.let {
                            Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        background?.let {
                            Image(it, null, Modifier.fillMaxSize().graphicsLayer { alpha = artworkBlend.value },
                                contentScale = ContentScale.Crop)
                        }
                    }
                    // Keep controls readable without turning clear negative-blur settings opaque.
                    val scrimScale = if (materialMode) 1f - materialMix * .6f else 1f
                    val scrimColor = if (isDark) {
                        Color.Black.copy(alpha = scrimScale * when {
                            full -> .22f + .16f * compactProgress
                            flowing -> .20f
                            else -> .28f
                        })
                    } else {
                        val lightVeilBase = when {
                            materialMode && expressive != null -> expressive.surface
                            oklabAmbient != null -> oklabAmbient.base
                            else -> Color(palette.n1.tone(96))
                        }
                        lightVeilBase.copy(alpha = scrimScale * when {
                            full -> .14f + .48f * compactProgress
                            flowing -> .56f
                            else -> .64f
                        })
                    }
                    Box(Modifier.fillMaxSize().background(scrimColor))
                }
                if (materialMode && materialBg != MaterialPlayerBackground.MATERIAL && expressive != null && materialMix > 0.001f) {
                    Box(Modifier.fillMaxSize().background(expressive.surface.copy(alpha = materialMix * .94f)))
                }
            }
        }
        }
        if (visible && lyricOverlayBlend > 0.001f) {
            AnimatedPlayerLyricBackdrop(
                config = config,
                blend = lyricOverlayBlend,
                animate = animate,
                palette = palette,
                spatialTilt = if (config.spatialLyrics) spatialTilt else Offset.Zero,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (config.weather != PlayerWeather.NONE) PlayerWeatherOverlay(config.weather, { phase }, isDark = isDark)
    }
}

@Composable
private fun PlayerBackgroundLayout(modifier: Modifier, visibility: State<Boolean>, content: @Composable BoxScope.() -> Unit) {
    Layout(content = { Box(modifier.clipToBounds(), content = content) }) { measurables, constraints ->
        val background = measurables.single().measure(constraints)
        layout(background.width, background.height) {
            if (visibility.value) background.place(0, 0)
        }
    }
}

// Keep the lyric scene's frame reads in its own scope. They must not recompose
// the artwork/blur/capture layers behind it at the display refresh rate.
@Composable
private fun AnimatedPlayerLyricBackdrop(
    config: NowPlayingConfig,
    blend: Float,
    animate: Boolean,
    palette: CorePalette,
    spatialTilt: Offset,
    modifier: Modifier,
) {
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate, config.lyricTheme, blend > 0.005f) {
        if (!animate || config.lyricTheme == LyricTheme.BLUR || blend <= 0.005f) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            phase = (phase + ((now - last) / 1e9f).coerceAtMost(.05f)) % 600f
            last = now
        }
    }
    PlayerLyricThemeBackdrop(
        theme = config.lyricTheme,
        blend = blend,
        phase = phase,
        palette = palette,
        spatialTilt = spatialTilt,
        tokyoSceneTime = config.tokyoSceneTime,
        tokyoSceneLandmark = config.tokyoSceneLandmark,
        modifier = modifier,
    )
}

@Composable
private fun MaterialExpressiveSurfaceWash(colors: PlayerExpressiveColors, phase: () -> Float, washStrength: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val phase = phase()
        drawRect(colors.surface)
        if (washStrength <= 0.005f) return@Canvas
        val primaryAlpha = (if (colors.dark) .36f else .48f) * washStrength
        val tertiaryAlpha = (if (colors.dark) .30f else .42f) * washStrength
        val secondaryAlpha = (if (colors.dark) .25f else .34f) * washStrength
        val driftX = sin(phase * .28f) * size.width * .06f
        val driftY = cos(phase * .22f) * size.height * .05f
        val topCenter = Offset(size.width * .24f + driftX, size.height * .18f + driftY)
        val bottomCenter = Offset(size.width * .78f - driftX, size.height * .76f - driftY)
        val midCenter = Offset(size.width * .72f + driftY, size.height * .38f - driftX)
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(colors.primaryContainer.copy(alpha = primaryAlpha), Color.Transparent),
                center = topCenter,
                radius = size.width * .95f,
            ),
            radius = size.width * .95f,
            center = topCenter,
        )
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(colors.tertiaryContainer.copy(alpha = tertiaryAlpha), Color.Transparent),
                center = bottomCenter,
                radius = size.width * .90f,
            ),
            radius = size.width * .90f,
            center = bottomCenter,
        )
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(colors.secondaryContainer.copy(alpha = secondaryAlpha), Color.Transparent),
                center = midCenter,
                radius = size.width * .68f,
            ),
            radius = size.width * .68f,
            center = midCenter,
        )
    }
}

@Composable
private fun OneUiSurfaceWash(colors: PlayerOneUiColors, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(colors.background)
        val topCenter = Offset(size.width * .50f, size.height * .26f)
        val bottomCenter = Offset(size.width * .52f, size.height * .82f)
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(colors.accent.copy(alpha = if (colors.dark) .18f else .12f), Color.Transparent),
                center = topCenter,
                radius = size.width * .92f,
            ),
            radius = size.width * .92f,
            center = topCenter,
        )
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(colors.chip.copy(alpha = if (colors.dark) .22f else .16f), Color.Transparent),
                center = bottomCenter,
                radius = size.width * .86f,
            ),
            radius = size.width * .86f,
            center = bottomCenter,
        )
    }
}

private data class WeatherParticle(val x: Float, val y: Float, val speed: Float, val size: Float)

@Composable
internal fun PlayerWeatherOverlay(
    weather: PlayerWeather,
    time: Float,
    modifier: Modifier = Modifier,
    isDark: Boolean = true,
) = PlayerWeatherOverlay(weather, { time }, modifier, isDark)

@Composable
private fun PlayerWeatherOverlay(
    weather: PlayerWeather,
    time: () -> Float,
    modifier: Modifier = Modifier,
    isDark: Boolean = true,
) {
    val particles = remember { Random(317).let { r -> List(64) { WeatherParticle(r.nextFloat(), r.nextFloat(), .08f + r.nextFloat() * .14f, .5f + r.nextFloat()) } } }
    val particleTint = if (isDark) Color.White else Color(0xFF3E4A5C)
    Canvas(modifier.fillMaxSize()) {
        val time = time()
        val rain = weather == PlayerWeather.RAIN
        for ((index, p) in particles.withIndex()) {
            val y = ((p.y + time * p.speed * if (rain) 3f else 1f) % 1f) * size.height
            val x = ((p.x + if (rain) -.04f * time else .035f * sin(time * .55f + index)) % 1f + 1f) % 1f * size.width
            if (rain) drawLine(particleTint.copy(alpha = if (isDark) .22f else .24f), Offset(x, y), Offset(x - 4.dp.toPx(), y + (10f + p.size * 8f).dp.toPx()), .8.dp.toPx())
            else drawCircle(particleTint.copy(alpha = if (isDark) .40f else .34f), (p.size * 1.5f).dp.toPx(), Offset(x, y))
        }
    }
}

@Composable
internal fun AnimatedPlayerArtwork(
    artwork: String,
    transitionKey: String,
    animation: CoverAnimation,
    enabled: Boolean,
    shape: Shape = RectangleShape,
    placeholderBackgroundColor: Color = Color.Transparent,
) {
    var current by remember { mutableStateOf(artwork) }
    var currentKey by remember { mutableStateOf(transitionKey) }
    var previous by remember { mutableStateOf<String?>(null) }
    val progress = remember { Animatable(1f) }
    if (!enabled && (artwork != current || transitionKey != currentKey)) {
        current = artwork
        currentKey = transitionKey
        previous = null
    }
    LaunchedEffect(artwork, transitionKey, enabled, animation) {
        if (artwork == current && transitionKey == currentKey && previous == null && progress.value >= 0.999f) return@LaunchedEffect
        if (artwork != current || transitionKey != currentKey) { previous = current; current = artwork; currentKey = transitionKey }
        if (enabled) { progress.snapTo(0f); progress.animateTo(1f, tween(if (animation == CoverAnimation.DEFAULT) 300 else 480, easing = FastOutSlowInEasing)) }
        else progress.snapTo(1f)
        previous = null
    }
    val t = progress.value
    fun Modifier.frame(incoming: Boolean): Modifier = graphicsLayer {
        val at = if (incoming) 1f - t else t
        cameraDistance = 14f * density
        this.shape = shape
        clip = true
        when (animation) {
            CoverAnimation.DEFAULT -> alpha = if (incoming) t else 1f - t
            CoverAnimation.CARD -> {
                translationX = size.width * at * if (incoming) 1f else -.35f
                rotationZ = at * if (incoming) 8f else -6f
                scaleX = 1f - .08f * at; scaleY = scaleX; alpha = 1f - at
            }
            CoverAnimation.BLUR -> alpha = 1f - at
            CoverAnimation.COVER -> { if (incoming) translationX = size.width * (1f - t) }
            CoverAnimation.PAGE -> {
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, .5f)
                rotationY = if (incoming) 90f * (1f - t) else -90f * t
                alpha = if (at < .999f) 1f else 0f
            }
            CoverAnimation.STACK -> {
                if (incoming) {
                    val stackScale = 0.86f + 0.14f * t
                    scaleX = stackScale
                    scaleY = stackScale
                    translationY = size.height * 0.065f * (1f - t)
                    alpha = 0.90f + 0.10f * t
                } else {
                    val exitFade = ((t - 0.30f) / 0.65f).coerceIn(0f, 1f)
                    translationY = -size.height * 0.38f * t
                    alpha = 1f - exitFade * exitFade
                    shadowElevation = 10.dp.toPx() * sin(PI.toFloat() * t)
                }
            }
            CoverAnimation.VERTICAL_FLIP, CoverAnimation.HORIZONTAL_FLIP -> {
                val angle = if (incoming) 180f * (1f - t) else -180f * t
                if (animation == CoverAnimation.VERTICAL_FLIP) rotationX = angle else rotationY = angle
                alpha = if (abs(angle) < 90f) 1f else 0f
            }
        }
    }.then(if (placeholderBackgroundColor.alpha > 0f) Modifier.background(placeholderBackgroundColor) else Modifier)
        .then(if (animation == CoverAnimation.BLUR) Modifier.blur((18f * if (incoming) 1f - t else t).dp) else Modifier)
    val context = LocalContext.current
    val previousRequest = remember(previous, context) {
        previous?.let {
            val placeholderKey = findCachedArtworkMemoryEntry(SingletonImageLoader.get(context), it)?.key
            ImageRequest.Builder(context)
                .data(it)
                .size(1600)
                .precision(Precision.INEXACT)
                .apply { if (placeholderKey != null) placeholderMemoryCacheKey(placeholderKey) }
                .build()
        }
    }
    val currentRequest = remember(current, context) {
        val placeholderKey = findCachedArtworkMemoryEntry(SingletonImageLoader.get(context), current)?.key
        ImageRequest.Builder(context)
            .data(current)
            .size(1600)
            .precision(Precision.INEXACT)
            .apply { if (placeholderKey != null) placeholderMemoryCacheKey(placeholderKey) }
            .build()
    }
    val previousOnTop = animation == CoverAnimation.STACK
    Box(Modifier.fillMaxSize()) {
        previousRequest?.let {
            AsyncImage(
                it,
                null,
                Modifier
                    .fillMaxSize()
                    .zIndex(if (previousOnTop) 1f else 0f)
                    .frame(false),
                contentScale = ContentScale.Crop,
            )
        }
        AsyncImage(
            currentRequest,
            null,
            Modifier
                .fillMaxSize()
                .zIndex(if (previousOnTop) 0f else 1f)
                .frame(true),
            contentScale = ContentScale.Crop,
        )
    }
}

internal data class CatalogPlayerThemeTokens(
    val dark: Boolean,
    val surface: Color,
    val foreground: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val cardFill: Color,
    val heroMixTint: Color,
)

@Composable
internal fun rememberCatalogPlayerThemeTokens(
    artwork: String,
    config: NowPlayingConfig,
    fallbackDarkSurface: Boolean,
    forceHeroSurface: Boolean,
): CatalogPlayerThemeTokens {
    val colorStyle = btm.m.auraplayer.ui.LocalAuraColorStyle.current
    val useOklab = colorStyle.isOklab
    val palette = rememberPlayerMonetPalette(artwork)
    val seed = rememberPlayerArtworkColorSeed(artwork, useOklab)
    val effectiveDark = if (forceHeroSurface) true else config.resolveDarkTheme(fallbackDarkSurface)
    val expressive = rememberPlayerExpressiveColors(seed, isDark = effectiveDark, useOklab = useOklab, paletteIndex = colorStyle.palette)
    val oneUi = rememberPlayerOneUiColors(seed, isDark = effectiveDark, useOklab = useOklab, paletteIndex = colorStyle.palette)
    val oklabAmbient = remember(useOklab, seed, effectiveDark, colorStyle.palette) {
        if (useOklab) btm.m.auraplayer.ui.OklabThemeEngine.generatePlayerAmbient(seed, effectiveDark, colorStyle.palette) else null
    }
    val mixFraction = (config.materialColorMix / 100f).coerceIn(0f, 1f)
    return remember(palette, config.theme, config.materialBackground, mixFraction, effectiveDark, forceHeroSurface, expressive, oneUi, oklabAmbient) {
        when {
            config.theme == PlayerTheme.MATERIAL -> CatalogPlayerThemeTokens(
                dark = effectiveDark,
                surface = expressive.surface,
                foreground = expressive.activeInk,
                muted = expressive.secondaryInk,
                accent = expressive.primary,
                onAccent = expressive.onPrimary,
                cardFill = expressive.surfaceContainerHigh.copy(alpha = if (effectiveDark) .58f else .74f),
                heroMixTint = lerp(expressive.primaryContainer, expressive.surface, mixFraction * .65f),
            )
            config.theme == PlayerTheme.ONE -> CatalogPlayerThemeTokens(
                dark = effectiveDark,
                surface = oneUi.background,
                foreground = oneUi.text,
                muted = oneUi.subText,
                accent = oneUi.accent,
                onAccent = oneUi.onAccent,
                cardFill = oneUi.card.copy(alpha = if (effectiveDark) .66f else .80f),
                heroMixTint = lerp(oneUi.chip, oneUi.background, .35f),
            )
            oklabAmbient != null -> CatalogPlayerThemeTokens(
                dark = effectiveDark,
                surface = oklabAmbient.surface,
                foreground = oklabAmbient.activeInk,
                muted = oklabAmbient.secondaryInk,
                accent = oklabAmbient.accent,
                onAccent = oklabAmbient.onAccent,
                cardFill = oklabAmbient.activeInk.copy(alpha = if (effectiveDark) .11f else .08f),
                heroMixTint = oklabAmbient.heroMixTint,
            )
            !effectiveDark -> {
                val base = Color(palette.a2.tone(90))
                val fg = Color(palette.a1.tone(10))
                val accent = Color(palette.a1.tone(25))
                CatalogPlayerThemeTokens(
                    dark = false,
                    surface = base,
                    foreground = fg,
                    muted = fg.copy(alpha = .66f),
                    accent = accent,
                    onAccent = Color.White,
                    cardFill = fg.copy(alpha = .10f),
                    heroMixTint = Color(palette.a1.tone(82)),
                )
            }
            else -> {
                val accent = Color(palette.a1.tone(80))
                CatalogPlayerThemeTokens(
                    dark = true,
                    surface = Color(palette.a2.tone(14)),
                    foreground = Color.White,
                    muted = Color.White.copy(alpha = .70f),
                    accent = accent,
                    onAccent = Color(palette.n1.tone(12)),
                    cardFill = Color.White.copy(alpha = .11f),
                    heroMixTint = lerp(Color(palette.a1.tone(30)), Color(palette.a3.tone(24)), .35f),
                )
            }
        }
    }
}

@Composable
internal fun CatalogNowPlayingBackground(
    artwork: String,
    config: NowPlayingConfig,
    fallbackDarkSurface: Boolean,
    forceHeroSurface: Boolean,
    modifier: Modifier = Modifier,
) {
    val colorStyle = btm.m.auraplayer.ui.LocalAuraColorStyle.current
    val useOklab = colorStyle.isOklab
    val palette = rememberPlayerMonetPalette(artwork)
    val seed = rememberPlayerArtworkColorSeed(artwork, useOklab)
    val tokens = rememberCatalogPlayerThemeTokens(artwork, config, fallbackDarkSurface, forceHeroSurface)
    if (!btm.m.auraplayer.ui.LocalAdvancedMaterialEnabled.current || (!forceHeroSurface && !tokens.dark)) {
        Box(modifier.background(tokens.surface))
        return
    }
    val expressive = rememberPlayerExpressiveColors(seed, isDark = tokens.dark, useOklab = useOklab, paletteIndex = colorStyle.palette)
    val oneUi = rememberPlayerOneUiColors(seed, isDark = tokens.dark, useOklab = useOklab, paletteIndex = colorStyle.palette)
    val resolvedConfig = remember(config) {
        config.copy(lyricTheme = LyricTheme.BLUR, weather = PlayerWeather.NONE)
    }
    Box(modifier) {
        PlayerThemeBackground(
            artwork = artwork,
            palette = palette,
            config = resolvedConfig,
            compactProgress = 0f,
            animate = true,
            expressive = if (resolvedConfig.theme == PlayerTheme.MATERIAL) expressive else null,
            oneUi = if (resolvedConfig.theme == PlayerTheme.ONE) oneUi else null,
            isDark = tokens.dark,
            useOklab = useOklab,
            oklabPaletteIndex = colorStyle.palette,
            seed = seed,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        .55f to Color.Transparent,
                        1f to tokens.surface.copy(alpha = if (tokens.dark) .46f else .34f),
                    ),
                ),
        )
    }
}
