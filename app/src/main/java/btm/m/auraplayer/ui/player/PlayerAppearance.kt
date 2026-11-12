package btm.m.auraplayer.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import btm.m.auraplayer.playback.*
import coil3.compose.AsyncImage
import com.materialkolor.palettes.CorePalette
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
    Box(modifier.clipToBounds()) {
        Crossfade(config.theme, animationSpec = tween(380), label = "playerTheme") { theme ->
            Box(Modifier.fillMaxSize().background(Color(palette.a2.tone(14)))) {
                if (theme == PlayerTheme.AURORA || theme == PlayerTheme.AURORA_BLUR)
                    PlayerAmbientBackground(palette, Modifier.fillMaxSize(), animate)
                if (theme == PlayerTheme.AURORA_BLUR || theme == PlayerTheme.BLUR || theme == PlayerTheme.FULLSCREEN) {
                    val flowing = theme == PlayerTheme.AURORA_BLUR
                    val full = theme == PlayerTheme.FULLSCREEN
                    val radius = if (full) 40f * compactProgress else blur
                    AsyncImage(artwork, null, Modifier.fillMaxSize().graphicsLayer {
                        scaleX = if (full) 1.04f else 1.22f; scaleY = scaleX
                        if (flowing) {
                            translationX = sin(phase * .24f) * size.width * .055f
                            translationY = cos(phase * .19f) * size.height * .045f
                            rotationZ = sin(phase * .12f) * 4f
                            alpha = .58f
                        }
                    }.blur(radius.dp), contentScale = ContentScale.Crop)
                    // Keep controls readable without turning clear negative-blur settings opaque.
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (full) .22f + .16f * compactProgress else .28f)))
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
