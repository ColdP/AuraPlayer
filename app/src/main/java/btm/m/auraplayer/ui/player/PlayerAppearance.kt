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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.playback.*
import coil3.compose.AsyncImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.materialkolor.palettes.CorePalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    expressive: PlayerExpressiveColors? = null,
    oneUi: PlayerOneUiColors? = null,
    modifier: Modifier = Modifier,
) {
    val usesFlowingArt = config.theme == PlayerTheme.AURORA_BLUR ||
        (config.theme == PlayerTheme.MATERIAL && config.materialBackground == MaterialPlayerBackground.AURORA_BLUR)
    val usesMaterialWash = config.theme == PlayerTheme.MATERIAL && config.materialBackground == MaterialPlayerBackground.MATERIAL
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate, usesFlowingArt, usesMaterialWash, config.weather) {
        if (!animate || (!usesFlowingArt && !usesMaterialWash && config.weather == PlayerWeather.NONE)) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            phase = (phase + ((now - last) / 1e9f).coerceAtMost(.05f)) % 120f
            last = now
        }
    }
    val blur by animateFloatAsState(config.blurRadius, tween(220), label = "playerBackgroundBlur")
    val materialMix by animateFloatAsState((config.materialColorMix / 100f).coerceIn(0f, 1f), tween(220), label = "playerMaterialMix")
    // Keep decoded pixels outside the theme Crossfade and outside Coil's loading state. A new
    // model must never clear the last successful background while its artwork is being decoded.
    val context = LocalContext.current
    var background by remember { mutableStateOf<ImageBitmap?>(null) }
    var previousBackground by remember { mutableStateOf<ImageBitmap?>(null) }
    val artworkBlend = remember { Animatable(1f) }
    val usesArtwork = config.usesArtworkBackground
    LaunchedEffect(artwork, usesArtwork, context) {
        artworkBlend.snapTo(1f)
        previousBackground = null
        if (!usesArtwork || artwork.isBlank()) return@LaunchedEffect
        val decoded = try {
            withContext(Dispatchers.IO) {
                (SingletonImageLoader.get(context).execute(
                    ImageRequest.Builder(context).data(artwork).size(1024).allowHardware(false).build()
                ) as? SuccessResult)?.image?.toBitmap()?.asImageBitmap()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@LaunchedEffect
        previousBackground = background
        background = decoded
        if (previousBackground != null) {
            artworkBlend.snapTo(0f)
            artworkBlend.animateTo(1f, tween(400))
        }
        previousBackground = null
    }
    Box(modifier.clipToBounds()) {
        Crossfade(config.theme to config.materialBackground, animationSpec = tween(380), label = "playerTheme") { (theme, materialBg) ->
            val materialMode = theme == PlayerTheme.MATERIAL
            val oneUiMode = theme == PlayerTheme.ONE && oneUi != null
            val baseColor = when {
                materialMode && materialBg == MaterialPlayerBackground.MATERIAL && expressive != null -> expressive.surface
                oneUiMode -> oneUi.background
                else -> Color(palette.a2.tone(14))
            }
            Box(Modifier.fillMaxSize().background(baseColor)) {
                val showAurora = theme == PlayerTheme.AURORA ||
                    theme == PlayerTheme.AURORA_BLUR ||
                    (materialMode && (materialBg == MaterialPlayerBackground.AURORA || materialBg == MaterialPlayerBackground.AURORA_BLUR))
                val showArtwork = theme == PlayerTheme.AURORA_BLUR ||
                    theme == PlayerTheme.BLUR ||
                    theme == PlayerTheme.FULLSCREEN ||
                    (materialMode && (materialBg == MaterialPlayerBackground.BLUR || materialBg == MaterialPlayerBackground.AURORA_BLUR))
                if (materialMode && materialBg == MaterialPlayerBackground.MATERIAL && expressive != null) {
                    MaterialExpressiveSurfaceWash(expressive, phase, washStrength = 1f - materialMix * .92f, Modifier.fillMaxSize())
                }
                if (oneUiMode) {
                    OneUiSurfaceWash(oneUi, Modifier.fillMaxSize())
                }
                if (showAurora) {
                    PlayerAmbientBackground(palette, Modifier.fillMaxSize(), animate)
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
                            // Let the moving Monet colors show through the blurred artwork.
                            alpha = .38f
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
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimScale * when {
                        full -> .22f + .16f * compactProgress
                        flowing -> .20f
                        else -> .28f
                    })))
                }
                if (materialMode && materialBg != MaterialPlayerBackground.MATERIAL && expressive != null && materialMix > 0.001f) {
                    Box(Modifier.fillMaxSize().background(expressive.surface.copy(alpha = materialMix * .94f)))
                }
            }
        }
        if (config.weather != PlayerWeather.NONE) PlayerWeatherOverlay(config.weather, phase)
    }
}

@Composable
private fun MaterialExpressiveSurfaceWash(colors: PlayerExpressiveColors, phase: Float, washStrength: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
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
private fun PlayerWeatherOverlay(weather: PlayerWeather, time: Float) {
    val particles = remember { Random(317).let { r -> List(64) { WeatherParticle(r.nextFloat(), r.nextFloat(), .08f + r.nextFloat() * .14f, .5f + r.nextFloat()) } } }
    Canvas(Modifier.fillMaxSize()) {
        val rain = weather == PlayerWeather.RAIN
        for ((index, p) in particles.withIndex()) {
            val y = ((p.y + time * p.speed * if (rain) 3f else 1f) % 1f) * size.height
            val x = ((p.x + if (rain) -.04f * time else .035f * sin(time * .55f + index)) % 1f + 1f) % 1f * size.width
            if (rain) drawLine(Color.White.copy(alpha = .22f), Offset(x, y), Offset(x - 4.dp.toPx(), y + (10f + p.size * 8f).dp.toPx()), .8.dp.toPx())
            else drawCircle(Color.White.copy(alpha = .40f), (p.size * 1.5f).dp.toPx(), Offset(x, y))
        }
    }
}

@Composable
internal fun AnimatedPlayerArtwork(artwork: String, transitionKey: String, animation: CoverAnimation, enabled: Boolean) {
    var current by remember { mutableStateOf(artwork) }
    var currentKey by remember { mutableStateOf(transitionKey) }
    var previous by remember { mutableStateOf<String?>(null) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(artwork, transitionKey, enabled, animation) {
        if (artwork == current && transitionKey == currentKey && previous == null) return@LaunchedEffect
        if (artwork != current || transitionKey != currentKey) { previous = current; current = artwork; currentKey = transitionKey }
        if (enabled) { progress.snapTo(0f); progress.animateTo(1f, tween(if (animation == CoverAnimation.DEFAULT) 300 else 480, easing = FastOutSlowInEasing)) }
        else progress.snapTo(1f)
        previous = null
    }
    val t = progress.value
    fun Modifier.frame(incoming: Boolean): Modifier = graphicsLayer {
        val at = if (incoming) 1f - t else t
        cameraDistance = 14f * density
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
                translationY = size.height * at * if (incoming) .18f else -.08f
                scaleX = 1f - .18f * at; scaleY = scaleX; alpha = 1f - at
            }
            CoverAnimation.VERTICAL_FLIP, CoverAnimation.HORIZONTAL_FLIP -> {
                val angle = if (incoming) 180f * (1f - t) else -180f * t
                if (animation == CoverAnimation.VERTICAL_FLIP) rotationX = angle else rotationY = angle
                alpha = if (abs(angle) < 90f) 1f else 0f
            }
        }
    }.then(if (animation == CoverAnimation.BLUR) Modifier.blur((18f * if (incoming) 1f - t else t).dp) else Modifier)
    Box(Modifier.fillMaxSize()) {
        previous?.let { AsyncImage(it, null, Modifier.fillMaxSize().frame(false), contentScale = ContentScale.Crop) }
        AsyncImage(current, null, Modifier.fillMaxSize().frame(true), contentScale = ContentScale.Crop)
    }
}
