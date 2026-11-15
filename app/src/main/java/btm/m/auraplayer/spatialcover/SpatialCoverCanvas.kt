// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.spatialcover

import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import kotlin.math.max
import kotlin.math.min

private const val COVER_SPATIAL_SHADER = """
uniform shader img;
uniform shader dep;
uniform float2 size;
uniform float2 parallax;

half4 main(float2 coord) {
    float2 uvClamped = clamp(coord, float2(0.5), size - float2(0.5));
    float d = clamp(float(dep.eval(uvClamped).r), 0.0, 1.0);
    float2 uv = clamp(coord - parallax * (0.5 - d), float2(0.0), size - float2(0.5));
    return img.eval(uv);
}
"""

private const val COVER_LAYERED_SHADER = """
uniform shader img;
uniform shader bg;
uniform shader dep;
uniform shader relief;
uniform shader msk;
uniform float2 size;
uniform float2 parallax;
uniform float dkTh;

half4 main(float2 coord) {
    float2 uvClamped = clamp(coord, float2(0.5), size - float2(0.5));
    float kb = clamp(float(dep.eval(uvClamped).r), 0.0, 1.0);
    float kf = clamp(float(relief.eval(uvClamped).r), 0.0, 1.0);

    // HyperExtreme selective blend: where bg & fg depth lie within dkTh,
    // move edge halos together so hair/borders never tear apart.
    float g = 1.0 - clamp(abs(kb - kf) / max(dkTh, 0.001), 0.0, 1.0);
    float kFgEff = mix(kf, kb, g);

    float2 bgUv = coord - parallax * (kb - 0.35);
    float2 fgUv = coord - parallax * (kFgEff - 0.35);

    float2 maxCoord = size - float2(0.5);
    half4 bgCol = bg.eval(clamp(bgUv, float2(0.0), maxCoord));
    half m = half(clamp(float(msk.eval(clamp(fgUv, float2(0.0), maxCoord)).r), 0.0, 1.0));
    if (fgUv.x < 0.0 || fgUv.y < 0.0 || fgUv.x >= size.x || fgUv.y >= size.y) m = half(0.0);

    float2 safeFgUv = clamp(fgUv, float2(0.0), maxCoord);
    half4 fgCol = img.eval(safeFgUv);
    // Color decontamination on semi-transparent boundary pixels so background halo never bleeds.
    half4 bgUnderFg = bg.eval(safeFgUv);
    half3 unmixed = clamp((fgCol.rgb - bgUnderFg.rgb * (half(1.0) - m)) / max(m, half(0.32)), half3(0.0), half3(1.0));
    half3 cleanFgRgb = mix(unmixed, fgCol.rgb, smoothstep(half(0.70), half(0.96), m));

    half4 composed = mix(bgCol, half4(cleanFgRgb, half(1.0)), m);
    half4 original = img.eval(coord);
    return mix(original, composed, half(smoothstep(0.0, 2.0, length(parallax))));
}
"""

private fun shaderSupported(source: String): Boolean = try {
    RuntimeShader(source)
    true
} catch (_: Throwable) {
    false
}

@Composable
fun SpatialCoverRenderer(
    assets: SpatialCoverAssets,
    tiltProvider: () -> Offset,
    strength: Float = 0.9f,
    modifier: Modifier = Modifier,
) {
    val mask = assets.mask
    val bg = assets.background
    val bgDepth = assets.backgroundDepth
    val relief = assets.subjectRelief
    val layeredSupported = remember { shaderSupported(COVER_LAYERED_SHADER) }
    val depthSupported = remember { shaderSupported(COVER_SPATIAL_SHADER) }

    if (assets.hasLayers && mask != null && bg != null && bgDepth != null && relief != null && layeredSupported) {
        Box(
            modifier
                .fillMaxSize()
                .clipToBounds()
                .drawWithCache {
                    val imageShader = BitmapShader(assets.source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    val bgShader = BitmapShader(bg, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    val bgDepthShader = BitmapShader(bgDepth, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    val maskShader = BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    val reliefShader = BitmapShader(relief, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    val shader = RuntimeShader(COVER_LAYERED_SHADER)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    val matrix = Matrix()
                    onDrawBehind {
                        val width = size.width
                        val height = size.height
                        if (width <= 0f || height <= 0f) return@onDrawBehind
                        val iw = assets.source.width.toFloat().coerceAtLeast(1f)
                        val ih = assets.source.height.toFloat().coerceAtLeast(1f)
                        val cover = max(width / iw, height / ih)
                        val overscan = 1.14f
                        val drawWidth = iw * cover * overscan
                        val drawHeight = ih * cover * overscan
                        val left = (width - drawWidth) / 2f
                        val top = (height - drawHeight) / 2f
                        val tilt = tiltProvider()
                        val shift = min(width, height) * .058f * strength.coerceIn(0f, 1.2f)
                        matrix.reset()
                        matrix.setScale(drawWidth / iw, drawHeight / ih)
                        imageShader.setLocalMatrix(matrix)
                        matrix.setScale(drawWidth / bg.width.toFloat().coerceAtLeast(1f), drawHeight / bg.height.toFloat().coerceAtLeast(1f))
                        bgShader.setLocalMatrix(matrix)
                        matrix.setScale(drawWidth / bgDepth.width.toFloat().coerceAtLeast(1f), drawHeight / bgDepth.height.toFloat().coerceAtLeast(1f))
                        bgDepthShader.setLocalMatrix(matrix)
                        matrix.setScale(drawWidth / mask.width.toFloat().coerceAtLeast(1f), drawHeight / mask.height.toFloat().coerceAtLeast(1f))
                        maskShader.setLocalMatrix(matrix)
                        matrix.setScale(drawWidth / relief.width.toFloat().coerceAtLeast(1f), drawHeight / relief.height.toFloat().coerceAtLeast(1f))
                        reliefShader.setLocalMatrix(matrix)
                        shader.setFloatUniform("size", drawWidth, drawHeight)
                        shader.setFloatUniform("parallax", tilt.x.coerceIn(-1f, 1f) * shift, tilt.y.coerceIn(-1f, 1f) * shift)
                        shader.setFloatUniform("dkTh", 0.08f)
                        shader.setInputShader("img", imageShader)
                        shader.setInputShader("bg", bgShader)
                        shader.setInputShader("dep", bgDepthShader)
                        shader.setInputShader("msk", maskShader)
                        shader.setInputShader("relief", reliefShader)
                        paint.shader = shader
                        val canvas = drawContext.canvas.nativeCanvas
                        canvas.save()
                        canvas.clipRect(0f, 0f, width, height)
                        canvas.translate(left, top)
                        canvas.drawRect(0f, 0f, drawWidth, drawHeight, paint)
                        canvas.restore()
                    }
                },
        )
    } else if (depthSupported) {
        Box(
            modifier
                .fillMaxSize()
                .clipToBounds()
                .drawWithCache {
                    val imageShader = BitmapShader(assets.source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    val depthShader = BitmapShader(assets.depth, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    val shader = RuntimeShader(COVER_SPATIAL_SHADER)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    val matrix = Matrix()
                    onDrawBehind {
                        val width = size.width
                        val height = size.height
                        if (width <= 0f || height <= 0f) return@onDrawBehind
                        val iw = assets.source.width.toFloat().coerceAtLeast(1f)
                        val ih = assets.source.height.toFloat().coerceAtLeast(1f)
                        val cover = max(width / iw, height / ih)
                        val overscan = 1.14f
                        val drawWidth = iw * cover * overscan
                        val drawHeight = ih * cover * overscan
                        val left = (width - drawWidth) / 2f
                        val top = (height - drawHeight) / 2f
                        val tilt = tiltProvider()
                        val shift = min(width, height) * .058f * strength.coerceIn(0f, 1.2f)
                        matrix.reset()
                        matrix.setScale(drawWidth / iw, drawHeight / ih)
                        imageShader.setLocalMatrix(matrix)
                        matrix.setScale(
                            drawWidth / assets.depth.width.toFloat().coerceAtLeast(1f),
                            drawHeight / assets.depth.height.toFloat().coerceAtLeast(1f),
                        )
                        depthShader.setLocalMatrix(matrix)
                        shader.setFloatUniform("size", drawWidth, drawHeight)
                        shader.setFloatUniform("parallax", tilt.x.coerceIn(-1f, 1f) * shift, tilt.y.coerceIn(-1f, 1f) * shift)
                        shader.setInputShader("img", imageShader)
                        shader.setInputShader("dep", depthShader)
                        paint.shader = shader
                        val canvas = drawContext.canvas.nativeCanvas
                        canvas.save()
                        canvas.clipRect(0f, 0f, width, height)
                        canvas.translate(left, top)
                        canvas.drawRect(0f, 0f, drawWidth, drawHeight, paint)
                        canvas.restore()
                    }
                },
        )
    } else {
        Image(
            bitmap = assets.source.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}
