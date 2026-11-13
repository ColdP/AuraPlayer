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
internal fun PlayerThemeBackground(artwork: String, palette: CorePalette, config: NowPlayingConfig,
    compactProgress: Float, animate: Boolean, modifier: Modifier = Modifier) {
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate, config.theme, config.weather) {
        if (!animate || (config.theme != PlayerTheme.AURORA_BLUR && config.weather == PlayerWeather.NONE)) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            phase = (phase + ((now - last) / 1e9f).coerceAtMost(.05f)) % 120f
            last = now
        }
    }
    val blur by animateFloatAsState(config.blurRadius, tween(220), label = "playerBackgroundBlur")
    // Keep decoded pixels outside the theme Crossfade and outside Coil's loading state. A new
    // model must never clear the last successful background while its artwork is being decoded.
    val context = LocalContext.current
    var background by remember { mutableStateOf<ImageBitmap?>(null) }
    var previousBackground by remember { mutableStateOf<ImageBitmap?>(null) }
    val artworkBlend = remember { Animatable(1f) }
    val usesArtwork = config.theme in setOf(PlayerTheme.AURORA_BLUR, PlayerTheme.BLUR, PlayerTheme.FULLSCREEN)
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
        Crossfade(config.theme, animationSpec = tween(380), label = "playerTheme") { theme ->
            Box(Modifier.fillMaxSize().background(Color(palette.a2.tone(14)))) {
                if (theme == PlayerTheme.AURORA || theme == PlayerTheme.AURORA_BLUR)
                    PlayerAmbientBackground(palette, Modifier.fillMaxSize(), animate)
                if (theme == PlayerTheme.AURORA_BLUR || theme == PlayerTheme.BLUR || theme == PlayerTheme.FULLSCREEN) {
                    val flowing = theme == PlayerTheme.AURORA_BLUR
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
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = when {
                        full -> .22f + .16f * compactProgress
                        flowing -> .20f
                        else -> .28f
                    })))
                }
            }
        }
        if (config.weather != PlayerWeather.NONE) PlayerWeatherOverlay(config.weather, phase)
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
