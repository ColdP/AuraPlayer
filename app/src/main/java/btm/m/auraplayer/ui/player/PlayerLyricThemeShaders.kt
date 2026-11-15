// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import btm.m.auraplayer.playback.LyricTheme

private const val AGSL_COMMON = """
float hash21(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(hash21(i), hash21(i + float2(1.0, 0.0)), u.x),
        mix(hash21(i + float2(0.0, 1.0)), hash21(i + float2(1.0, 1.0)), u.x),
        u.y
    );
}
float fbm3(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 3; i++) {
        v += a * vnoise(p);
        p = float2(p.x * 1.6 - p.y * 1.2, p.x * 1.2 + p.y * 1.6) + float2(13.7, 29.3);
        a *= 0.5;
    }
    return v;
}
float causticField(float2 p, float t) {
    float n1 = fbm3(p + float2(t * 0.18, -t * 0.14));
    float n2 = fbm3(p * 1.35 + float2(-t * 0.15, t * 0.21) + n1 * 1.2);
    float ridge = 1.0 - abs(n2 - 0.5) * 2.0;
    return pow(clamp(ridge, 0.0, 1.0), 3.0);
}
float filmDither(float2 fc) {
    return (fract(52.9829189 * fract(dot(fc, float2(0.06711056, 0.00583715)))) - 0.5) * 0.012;
}
"""

private const val SHADER_DEEP_STAR_SEA = AGSL_COMMON + """
uniform float2 uResolution;
uniform float uTime;
uniform float2 uTilt;
uniform float4 uTintA;
uniform float4 uTintB;

half4 main(float2 fragCoord) {
    float2 res = max(uResolution, float2(1.0));
    float2 uv = fragCoord / res;
    float aspect = res.x / res.y;
    float2 p = float2((uv.x - 0.5) * aspect, uv.y) + uTilt * 0.06;

    float3 abyss = float3(0.012, 0.036, 0.088);
    float3 midSea = float3(0.022, 0.125, 0.245);
    float3 upperWater = float3(0.048, 0.255, 0.395);
    float3 col = mix(upperWater, midSea, smoothstep(0.0, 0.58, uv.y));
    col = mix(col, abyss, smoothstep(0.45, 1.0, uv.y));

    float depthFade = smoothstep(0.92, 0.02, uv.y);
    float rayAngle = p.x - p.y * 0.22;
    float rayNoise = fbm3(float2(rayAngle * 3.8 + uTime * 0.06, p.y * 1.2 - uTime * 0.08));
    float rayNoise2 = fbm3(float2(rayAngle * 6.4 - uTime * 0.04, p.y * 0.9 + uTime * 0.05));
    float rays = smoothstep(0.38, 0.85, rayNoise) * smoothstep(0.30, 0.80, rayNoise2);
    rays *= exp(-uv.y * 1.85);
    col += mix(float3(0.16, 0.72, 0.92), uTintA.rgb, 0.22) * rays * 0.34;

    float c1 = causticField(p * 2.8 + uTilt * 0.12, uTime * 0.75);
    float c2 = causticField(p * 4.2 - float2(0.0, uTime * 0.10), -uTime * 0.55);
    float caustics = (c1 * 0.65 + c2 * 0.5) * depthFade;
    col += mix(float3(0.24, 0.86, 1.0), uTintB.rgb, 0.2) * caustics * 0.26;

    float bioMist = smoothstep(0.42, 0.86, fbm3(p * 2.1 + float2(uTime * 0.04, -uTime * 0.06)));
    col += float3(0.05, 0.42, 0.58) * bioMist * 0.16 * (1.0 - uv.y * 0.4);

    float vig = 1.0 - smoothstep(0.35, 1.05, length((uv - 0.5) * float2(1.1, 0.9)));
    col *= 0.66 + 0.34 * vig;
    col += filmDither(fragCoord);
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

private const val SHADER_AFTERGLOW_SHORE = AGSL_COMMON + """
uniform float2 uResolution;
uniform float uTime;
uniform float2 uTilt;
uniform float4 uTintA;
uniform float4 uTintB;

half4 main(float2 fragCoord) {
    float2 res = max(uResolution, float2(1.0));
    float2 uv = fragCoord / res;
    float hy = 0.67 + uTilt.y * 0.025;
    float sunX = 0.5 + sin(uTime * 0.16) * 0.045 - uTilt.x * 0.05;
    float2 sunPos = float2(sunX, hy - 0.065);

    float3 skyTop = float3(0.085, 0.048, 0.145);
    float3 skyMid = float3(0.44, 0.145, 0.265);
    float3 skyLow = float3(0.86, 0.42, 0.24);
    float skyT = clamp(uv.y / max(hy, 0.1), 0.0, 1.0);
    float3 skyCol = mix(skyTop, skyMid, smoothstep(0.05, 0.65, skyT));
    skyCol = mix(skyCol, skyLow, smoothstep(0.58, 1.0, skyT));

    float2 aspectUv = float2((uv.x - sunPos.x) * (res.x / res.y), uv.y - sunPos.y);
    float sunDist = length(aspectUv);
    float sunHalo = exp(-sunDist * 3.8) * 0.55 + exp(-sunDist * 14.0) * 0.45;
    skyCol += float3(1.0, 0.72, 0.42) * sunHalo * smoothstep(hy + 0.08, hy - 0.04, uv.y);

    float cloudWarp = fbm3(float2(uv.x * 2.4 + uTime * 0.022, uv.y * 5.2));
    float cloudBand = fbm3(float2(uv.x * 3.8 - uTime * 0.016, uv.y * 8.5) + cloudWarp * 0.85);
    float cloudMask = smoothstep(0.42, 0.78, cloudBand) * smoothstep(0.05, 0.35, uv.y) * smoothstep(hy, hy - 0.18, uv.y);
    float3 cloudLit = mix(float3(0.28, 0.14, 0.24), float3(1.0, 0.58, 0.38), exp(-abs(uv.x - sunX) * 2.8) * 0.85);
    skyCol = mix(skyCol, cloudLit, cloudMask * 0.52);

    float seaDepth = clamp((uv.y - hy) / max(1.0 - hy, 0.1), 0.0, 1.0);
    float3 seaDeep = mix(float3(0.18, 0.09, 0.17), float3(0.045, 0.032, 0.082), smoothstep(0.0, 0.85, seaDepth));
    float perspY = 1.0 / (seaDepth + 0.055);
    float2 waveCoord = float2((uv.x - sunX) * 4.5 + sin(perspY * 0.4 + uTime * 0.5) * 0.2, perspY * 0.55 - uTime * 0.28);
    float wNoise = vnoise(waveCoord * float2(2.2, 4.0));
    float ripple = smoothstep(0.56, 0.92, wNoise);
    float glitterWidth = mix(0.06, 0.28, seaDepth);
    float sunColumn = exp(-pow((uv.x - sunX) / glitterWidth, 2.0));
    float3 seaCol = seaDeep + float3(1.0, 0.76, 0.46) * ripple * sunColumn * mix(0.75, 0.22, seaDepth);
    seaCol += float3(0.95, 0.45, 0.36) * exp(-seaDepth * 5.5) * 0.22;

    float horizonBlend = smoothstep(hy - 0.012, hy + 0.014, uv.y);
    float3 col = mix(skyCol, seaCol, horizonBlend);
    col += mix(float3(1.0, 0.62, 0.42), uTintA.rgb, 0.18) * exp(-abs(uv.y - hy) * 18.0) * 0.18;

    float vig = 1.0 - smoothstep(0.35, 1.08, length((uv - 0.5) * float2(1.05, 0.92)));
    col *= 0.68 + 0.32 * vig;
    col += filmDither(fragCoord);
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

private const val SHADER_STREAMING_SHADOWS = AGSL_COMMON + """
uniform float2 uResolution;
uniform float uTime;
uniform float2 uTilt;
uniform float4 uTintA;
uniform float4 uTintB;

float ribbonIntensity(float2 p, float t, float freq, float offset) {
    float warp = fbm3(p * 1.45 + float2(t * 0.11, -t * 0.08) + offset);
    float wave = sin(p.x * freq + p.y * 1.6 + warp * 4.2 + t * 0.35 + offset);
    return pow(clamp(0.5 + 0.5 * wave, 0.0, 1.0), 3.6);
}

half4 main(float2 fragCoord) {
    float2 res = max(uResolution, float2(1.0));
    float2 uv = fragCoord / res;
    float aspect = res.x / res.y;
    float2 centered = (uv - 0.5) * float2(aspect, 1.0) + uTilt * 0.07;

    float c = 0.866;
    float s = -0.5;
    float2 p = float2(centered.x * c - centered.y * s, centered.x * s + centered.y * c);

    float3 colA = mix(float3(0.32, 0.78, 1.0), uTintA.rgb, 0.45);
    float3 colB = mix(float3(0.76, 0.46, 1.0), uTintB.rgb, 0.45);

    float rCh = ribbonIntensity(p + float2(0.022, 0.0), uTime, 3.8, 0.0);
    float gCh = ribbonIntensity(p, uTime, 3.8, 0.0);
    float bCh = ribbonIntensity(p - float2(0.022, 0.0), uTime, 3.8, 0.0);
    float3 prism = float3(rCh, gCh, bCh) * mix(colA, colB, clamp(uv.y * 0.8 + 0.1, 0.0, 1.0));

    float silk2 = ribbonIntensity(p * 1.25, -uTime * 0.8, 2.7, 2.4);
    float veil = smoothstep(0.35, 0.85, fbm3(p * 1.9 + float2(-uTime * 0.07, uTime * 0.05)));

    float3 base = mix(float3(0.028, 0.032, 0.068), float3(0.055, 0.045, 0.105), veil);
    float3 col = base + prism * 0.40 + mix(colB, colA, veil) * silk2 * 0.24;
    col += colA * exp(-abs(centered.y - 0.08 * sin(uTime * 0.3 + centered.x)) * 5.2) * 0.12;

    float vig = 1.0 - smoothstep(0.35, 1.02, length((uv - 0.5) * float2(1.08, 0.92)));
    col *= 0.66 + 0.34 * vig;
    col += filmDither(fragCoord);
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

private const val SHADER_LIGHT_YEAR_DRIFT = AGSL_COMMON + """
uniform float2 uResolution;
uniform float uTime;
uniform float2 uTilt;
uniform float4 uTintA;
uniform float4 uTintB;

half4 main(float2 fragCoord) {
    float2 res = max(uResolution, float2(1.0));
    float2 uv = fragCoord / res;
    float aspect = res.x / res.y;
    float2 center = float2(0.5, 0.43) + uTilt * 0.05;
    float2 d = (uv - center) * float2(aspect, 1.0);
    float dist = max(length(d), 0.001);
    float angle = atan(d.y, d.x);

    float logR = log(dist + 0.12) * 1.6 - uTime * 0.16;
    float2 polar = float2(cos(angle) * 1.8 + logR * 0.35, sin(angle) * 1.8 - logR * 0.35);
    float neb1 = fbm3(polar + float2(uTime * 0.03, -uTime * 0.02));
    float neb2 = fbm3(polar * 1.9 - float2(uTime * 0.04, 1.7) + neb1 * 0.9);
    float rift = smoothstep(0.35, 0.68, fbm3(polar * 2.4 + 4.1));

    float3 deepVoid = float3(0.012, 0.016, 0.048);
    float3 sapphireGas = mix(float3(0.15, 0.30, 0.72), uTintA.rgb, 0.25);
    float3 violetGas = mix(float3(0.45, 0.24, 0.72), uTintB.rgb, 0.25);
    float nebMask = smoothstep(0.32, 0.84, neb1 * 0.6 + neb2 * 0.4) * (1.0 - rift * 0.55);

    float3 col = deepVoid + mix(sapphireGas, violetGas, neb2) * nebMask * 0.42;
    col += float3(0.55, 0.72, 1.0) * exp(-dist * 3.8) * 0.26;
    col *= 0.75 + 0.25 * (1.0 - smoothstep(0.2, 1.0, dist));

    float starSeed = vnoise(fragCoord * 0.16);
    float microStar = pow(smoothstep(0.84, 0.99, starSeed), 3.0) * (0.5 + 0.5 * sin(uTime * 2.4 + starSeed * 31.0));
    col += float3(0.82, 0.90, 1.0) * microStar * 0.35;
    col += filmDither(fragCoord);
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

private const val SHADER_NEON_CITY = AGSL_COMMON + """
uniform float2 uResolution;
uniform float uTime;
uniform float2 uTilt;
uniform float4 uTintA;
uniform float4 uTintB;

half4 main(float2 fragCoord) {
    float2 res = max(uResolution, float2(1.0));
    float2 uv = fragCoord / res;
    float hy = 0.56 + uTilt.y * 0.025;

    float3 zenith = float3(0.032, 0.016, 0.088);
    float3 smog = float3(0.14, 0.038, 0.24);
    float skyT = clamp(uv.y / max(hy, 0.1), 0.0, 1.0);
    float3 skyCol = mix(zenith, smog, smoothstep(0.1, 1.0, skyT));

    float fog = fbm3(float2(uv.x * 2.8 + uTime * 0.035 + uTilt.x * 0.08, uv.y * 3.6 - uTime * 0.02));
    float cyanZone = smoothstep(0.32, 0.80, vnoise(float2(uv.x * 3.5 + uTime * 0.04, 1.2)));
    float magentaZone = smoothstep(0.32, 0.80, vnoise(float2(uv.x * 3.1 - uTime * 0.03, 5.7)));
    float3 cyan = float3(0.0, 0.86, 1.0);
    float3 magenta = float3(1.0, 0.24, 0.78);
    float horizonGlow = exp(-abs(uv.y - hy) * 7.0);
    skyCol += (cyan * cyanZone + magenta * magentaZone) * horizonGlow * (0.28 + 0.24 * fog);

    float floorT = clamp((uv.y - hy) / max(1.0 - hy, 0.1), 0.0, 1.0);
    float rippleX = vnoise(float2(uv.x * 26.0, uv.y * 12.0 - uTime * 0.25)) - 0.5;
    float2 reflUv = float2(uv.x + rippleX * 0.035, hy - (uv.y - hy) * 0.65);
    float reflCyan = smoothstep(0.42, 0.88, vnoise(float2(reflUv.x * 5.5 + uTime * 0.05, 1.2)));
    float reflMag = smoothstep(0.42, 0.88, vnoise(float2(reflUv.x * 4.8 - uTime * 0.04, 5.7)));
    float wetGloss = smoothstep(0.25, 0.78, fbm3(float2(uv.x * 6.0, uv.y * 22.0)));
    float3 roadCol = float3(0.026, 0.018, 0.058);
    roadCol += (cyan * reflCyan * 0.85 + magenta * reflMag) * (0.14 + 0.28 * wetGloss) * (1.0 - floorT * 0.65);

    float3 col = mix(skyCol, roadCol, smoothstep(hy - 0.025, hy + 0.025, uv.y));
    float rainSheen = pow(smoothstep(0.55, 0.92, vnoise(float2(uv.x * 42.0 - uv.y * 18.0, uv.y * 16.0 - uTime * 1.4))), 2.0);
    col += mix(cyan, magenta, uv.x) * rainSheen * 0.07;

    float vig = 1.0 - smoothstep(0.35, 1.04, length((uv - 0.5) * float2(1.08, 0.9)));
    col *= 0.66 + 0.34 * vig;
    col += filmDither(fragCoord);
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

private const val SHADER_WIND_LETTER = AGSL_COMMON + """
uniform float2 uResolution;
uniform float uTime;
uniform float2 uTilt;
uniform float4 uTintA;
uniform float4 uTintB;

half4 main(float2 fragCoord) {
    float2 res = max(uResolution, float2(1.0));
    float2 uv = fragCoord / res;
    float aspect = res.x / res.y;

    float3 shadowPaper = float3(0.115, 0.078, 0.062);
    float3 warmPaper = float3(0.245, 0.172, 0.132);
    float2 lampPos = float2(0.80 + sin(uTime * 0.24) * 0.04 - uTilt.x * 0.06, 0.20 + uTilt.y * 0.05);
    float lampDist = length((uv - lampPos) * float2(aspect, 1.0));
    float lampPool = exp(-lampDist * 1.65) * (0.94 + 0.06 * sin(uTime * 2.1));

    float fiberFine = vnoise(fragCoord * 0.24);
    float fiberCoarse = fbm3(float2(uv.x * 7.0, uv.y * 11.0));
    float teaStain = fbm3(uv * 3.2 + float2(uTime * 0.015, -uTime * 0.01) + uTilt * 0.05);

    float3 col = mix(shadowPaper, warmPaper, clamp(lampPool * 0.78 + 0.22, 0.0, 1.0));
    col += float3(0.95, 0.74, 0.50) * exp(-lampDist * 3.2) * 0.16;
    col *= 0.88 + 0.14 * fiberCoarse + 0.08 * (fiberFine - 0.5);
    col = mix(col, float3(0.145, 0.095, 0.078), smoothstep(0.50, 0.88, teaStain) * 0.28);

    float vig = smoothstep(0.32, 0.98, length((uv - 0.5) * float2(1.06, 0.94)));
    col *= 1.0 - vig * 0.42;
    col += filmDither(fragCoord);
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

private const val SHADER_STAR_MOON = AGSL_COMMON + """
uniform float2 uResolution;
uniform float uTime;
uniform float2 uTilt;
uniform float4 uTintA;
uniform float4 uTintB;

half4 main(float2 fragCoord) {
    float2 res = max(uResolution, float2(1.0));
    float2 uv = fragCoord / res;
    float aspect = res.x / res.y;
    float2 p = float2(uv.x * aspect, uv.y) + uTilt * 0.04;

    float3 skyTop = float3(0.025, 0.036, 0.115);
    float3 skyMid = float3(0.065, 0.092, 0.225);
    float3 skyBot = float3(0.028, 0.038, 0.110);
    float3 col = mix(skyTop, skyMid, smoothstep(0.0, 0.55, uv.y));
    col = mix(col, skyBot, smoothstep(0.55, 1.0, uv.y));

    float bandCoord = (uv.x * 0.65 + uv.y * 0.75) - 0.72;
    float milkyCore = exp(-bandCoord * bandCoord * 16.0);
    float dust = fbm3(p * 3.1 + float2(uTime * 0.012, -uTime * 0.01));
    float darkLane = smoothstep(0.42, 0.72, fbm3(p * 5.2 + 2.3));
    float3 milkyCol = mix(float3(0.34, 0.45, 0.82), float3(0.95, 0.82, 0.58), dust);
    col += milkyCol * milkyCore * dust * (1.0 - darkLane * 0.55) * 0.34;

    float2 moonCenter = float2(0.80, 0.19) - uTilt * 0.035;
    float moonDist = length((uv - moonCenter) * float2(aspect, 1.0));
    float cloudWisp = fbm3(p * 4.0 + float2(uTime * 0.02, 0.0));
    col += float3(1.0, 0.90, 0.66) * exp(-moonDist * 4.2) * (0.18 + 0.12 * cloudWisp);
    col += float3(0.45, 0.58, 0.92) * exp(-moonDist * 2.0) * 0.14;

    float vig = 1.0 - smoothstep(0.35, 1.06, length((uv - 0.5) * float2(1.06, 0.92)));
    col *= 0.72 + 0.28 * vig;
    col += filmDither(fragCoord);
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

private const val SHADER_LIME_SODA = AGSL_COMMON + """
uniform float2 uResolution;
uniform float uTime;
uniform float2 uTilt;
uniform float4 uTintA;
uniform float4 uTintB;

half4 main(float2 fragCoord) {
    float2 res = max(uResolution, float2(1.0));
    float2 uv = fragCoord / res;
    float aspect = res.x / res.y;
    float2 p = float2(uv.x * aspect, uv.y) + uTilt * 0.06;

    float3 deepMint = float3(0.024, 0.135, 0.098);
    float3 emerald = float3(0.055, 0.275, 0.175);
    float3 sunLime = float3(0.42, 0.82, 0.22);

    float sunSweep = exp(-length((uv - float2(0.28 + sin(uTime * 0.22) * 0.08, 0.22)) * float2(aspect, 1.0)) * 1.8);
    float3 col = mix(deepMint, emerald, smoothstep(0.95, 0.15, uv.y) * 0.75 + 0.25 * sunSweep);

    float c1 = causticField(p * 3.1 + float2(0.0, uTime * 0.08), uTime * 0.9);
    float c2 = causticField(p * 4.6 - float2(uTime * 0.05, 0.0), -uTime * 0.65);
    float liquidGlow = smoothstep(0.30, 0.86, fbm3(p * 2.4 + float2(uTime * 0.05, -uTime * 0.09)));

    col += sunLime * (c1 * 0.26 + c2 * 0.18) * (0.45 + 0.55 * sunSweep);
    col += float3(0.14, 0.72, 0.52) * liquidGlow * 0.16;

    float vig = 1.0 - smoothstep(0.35, 1.05, length((uv - 0.5) * float2(1.08, 0.92)));
    col *= 0.68 + 0.32 * vig;
    col += filmDither(fragCoord);
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

private const val SHADER_MONET_GARDEN = AGSL_COMMON + """
uniform float2 uResolution;
uniform float uTime;
uniform float2 uTilt;
uniform float4 uTintA;
uniform float4 uTintB;

half4 main(float2 fragCoord) {
    float2 res = max(uResolution, float2(1.0));
    float2 uv = fragCoord / res;
    float aspect = res.x / res.y;
    float2 p = float2(uv.x * aspect, uv.y) + uTilt * 0.055;

    float ripple = fbm3(float2(p.x * 2.2 + uTime * 0.04, p.y * 5.2 - uTime * 0.03));
    float2 brushUv = float2(p.x * 16.0 + ripple * 3.4, p.y * 34.0 + ripple * 1.8);
    float dab = vnoise(brushUv) * 0.65 + vnoise(brushUv * 2.1 + 7.4) * 0.35;

    float3 pondNavy = float3(0.068, 0.098, 0.175);
    float3 lilySage = float3(0.16, 0.34, 0.28);
    float3 wisteria = float3(0.29, 0.24, 0.45);
    float3 waterRose = mix(float3(0.46, 0.25, 0.36), uTintA.rgb * 0.58, 0.45);

    float w1 = smoothstep(0.32, 0.78, fbm3(p * 1.9 + float2(0.0, uTime * 0.025)));
    float w2 = smoothstep(0.35, 0.80, fbm3(p * 2.5 + float2(3.1, -uTime * 0.02)));
    float3 col = mix(pondNavy, lilySage, w1 * 0.68);
    col = mix(col, wisteria, w2 * 0.56);
    float rosePool = exp(-length((uv - float2(0.55 + sin(uTime * 0.18) * 0.06, 0.42)) * float2(aspect, 1.0)) * 2.3);
    col += waterRose * rosePool * 0.42;

    float impasto = smoothstep(0.38, 0.86, dab);
    col *= 0.82 + 0.32 * impasto;
    col += mix(float3(0.56, 0.84, 0.72), uTintB.rgb * 0.7, 0.35) * pow(impasto, 2.5) * 0.14;

    float vig = 1.0 - smoothstep(0.35, 1.05, length((uv - 0.5) * float2(1.06, 0.92)));
    col *= 0.68 + 0.32 * vig;
    col += filmDither(fragCoord);
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

private const val SHADER_FADED_FILM = AGSL_COMMON + """
uniform float2 uResolution;
uniform float uTime;
uniform float2 uTilt;
uniform float4 uTintA;
uniform float4 uTintB;

half4 main(float2 fragCoord) {
    float2 res = max(uResolution, float2(1.0));
    float2 uv = fragCoord / res;
    float aspect = res.x / res.y;

    float cadenceTime = floor(uTime * 18.0) * 0.055;
    float flicker = 0.95 + 0.05 * sin(uTime * 9.5 + vnoise(float2(cadenceTime, 1.0)) * 6.28);

    float3 darkStock = float3(0.075, 0.062, 0.048);
    float3 warmMid = float3(0.168, 0.132, 0.095);
    float cloud = fbm3(float2(uv.x * 2.2 * aspect, uv.y * 2.2) + float2(uTime * 0.015, 0.0) + uTilt * 0.04);
    float3 col = mix(darkStock, warmMid, clamp(cloud * 0.85 + 0.15, 0.0, 1.0)) * flicker;

    float2 leakPos = float2(0.08 + 0.06 * sin(uTime * 0.25), 0.32 + 0.18 * cos(uTime * 0.19));
    float leakDist = length((uv - leakPos) * float2(aspect * 0.8, 1.0));
    float leak = exp(-leakDist * 2.4) * (0.55 + 0.45 * sin(uTime * 0.42));
    col += float3(0.95, 0.48, 0.18) * leak * 0.26;

    float2 leakPos2 = float2(0.94, 0.72 + 0.12 * sin(uTime * 0.21));
    float leak2 = exp(-length((uv - leakPos2) * float2(aspect, 1.0)) * 3.0);
    col += float3(0.98, 0.74, 0.36) * leak2 * 0.18;

    float emulsionGrain = hash21(fragCoord * 0.75 + float2(cadenceTime * 47.0, cadenceTime * 23.0)) - 0.5;
    col += emulsionGrain * 0.042;

    float vig = 1.0 - smoothstep(0.32, 0.98, length((uv - 0.5) * float2(1.06, 0.92)));
    col *= 0.58 + 0.42 * vig;
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

private const val SHADER_TOKYO_SKY = AGSL_COMMON + """
uniform float2 uResolution;
uniform float uTime;
uniform float2 uTilt;
uniform float4 uTintA;
uniform float4 uTintB;

half4 main(float2 fragCoord) {
    float2 res = max(uResolution, float2(1.0));
    float2 uv = fragCoord / res;
    float aspect = res.x / res.y;

    float daylight = clamp(uTintA.a, 0.0, 1.0);
    float nightness = clamp(uTintB.a, 0.0, 1.0);
    float twilight = clamp(1.0 - abs(daylight - 0.32) * 2.1, 0.0, 1.0) * (1.0 - nightness * 0.55);

    float horizonY = 0.49 + uTilt.y * 0.04;
    float skyT = clamp(1.0 - uv.y / max(horizonY, 0.08), 0.0, 1.0);

    float3 zenith = uTintA.rgb;
    float3 horizon = uTintB.rgb;
    // Soft anime pastel three-stop sky transition
    float3 midBand = mix(horizon, zenith, 0.42) + float3(0.03, 0.02, 0.06) * twilight;
    float3 sky = mix(horizon, mix(midBand, zenith, smoothstep(0.28, 0.96, skyT)), smoothstep(0.0, 0.52, skyT));

    float2 sunPos = float2(
        0.24 - uTilt.x * 0.05,
        mix(horizonY - 0.03, 0.16, daylight) + uTilt.y * 0.03
    );
    float2 sunDelta = (uv - sunPos) * float2(aspect, 1.0);
    float sunDist = length(sunDelta);
    float sunGlow = exp(-sunDist * mix(3.2, 5.0, daylight)) * mix(0.20, 0.48, max(daylight, twilight));
    float sunDisc = smoothstep(0.038, 0.024, sunDist) * mix(0.18, 0.62, max(daylight, twilight));
    float3 sunTint = mix(float3(1.0, 0.64, 0.40), float3(1.0, 0.94, 0.80), daylight);
    sky += sunTint * (sunGlow + sunDisc);

    // Subtle crepuscular anime light shafts angling down through the sky
    float rayAngle = atan(sunDelta.y, sunDelta.x);
    float shafts = pow(max(0.0, sin(rayAngle * 7.0 + uTime * 0.08) * 0.5 + 0.5), 3.0);
    float shaftFade = exp(-sunDist * 2.2) * (1.0 - nightness * 0.85) * smoothstep(horizonY + 0.05, 0.05, uv.y);
    sky += sunTint * shafts * shaftFade * 0.09;

    // Crescent-lit moon with soft pastel halo at night
    float2 moonPos = float2(0.78 - uTilt.x * 0.04, 0.15 + uTilt.y * 0.03);
    float moonDist = length((uv - moonPos) * float2(aspect, 1.0));
    float moonCut = length((uv - (moonPos + float2(0.010 / aspect, -0.006))) * float2(aspect, 1.0));
    float crescent = (smoothstep(0.026, 0.020, moonDist) - smoothstep(0.024, 0.017, moonCut)) * nightness;
    float moonHalo = exp(-moonDist * 7.5) * 0.22 * nightness;
    sky += float3(0.84, 0.92, 1.0) * (max(0.0, crescent) * 0.85 + moonHalo);

    // Anime diamond sparkle stars at night / twilight
    float starFade = smoothstep(0.28, 0.85, nightness) * smoothstep(0.08, 0.55, skyT);
    if (starFade > 0.005) {
        float2 starCoord = uv * float2(105.0 * aspect, 105.0) + uTilt * 2.2;
        float2 grid = floor(starCoord);
        float2 cell = fract(starCoord) - 0.5;
        float h = hash21(grid);
        if (h > 0.964) {
            float twinkle = 0.5 + 0.5 * sin(uTime * (1.8 + h * 2.6) + h * 50.0);
            float diamond = max(abs(cell.x) + abs(cell.y) * 3.0, abs(cell.y) + abs(cell.x) * 3.0);
            float core = smoothstep(0.22, 0.02, length(cell));
            float flare = smoothstep(0.45, 0.04, diamond) * 0.55;
            sky += mix(float3(0.76, 0.88, 1.0), float3(1.0, 0.88, 0.78), fract(h * 17.0)) * (core + flare) * twinkle * starFade * 0.82;
        }
    }

    // Cel-shaded Makoto Shinkai style flat-bottom billowing cumulonimbus clouds!
    float cloudFloor = smoothstep(horizonY - 0.02, horizonY - 0.14, uv.y) * smoothstep(0.02, 0.26, uv.y);
    float2 cUv = float2(uv.x * 1.9 * aspect + uTime * 0.010 + uTilt.x * 0.04, uv.y * 4.4 + uTilt.y * 0.02);
    float cShape = fbm3(cUv) * 0.65 + fbm3(cUv * 2.3 + float2(-uTime * 0.006, 3.1)) * 0.35;
    float cShifted = fbm3(cUv + float2(0.04, 0.09)) * 0.65 + fbm3((cUv + float2(0.04, 0.09)) * 2.3) * 0.35;
    float cloudAlpha = smoothstep(0.48, 0.54, cShape * cloudFloor + 0.14 * (1.0 - uv.y / max(horizonY, 0.1)) * cloudFloor);
    // Crisp quantized 2-tone cel lighting across cloud puffs (sunlit cream rim vs cool periwinkle shadow).
    float cloudLit = smoothstep(-0.04, 0.06, cShape - cShifted + 0.025);
    float3 cloudShadowCol = mix(
        mix(float3(0.46, 0.56, 0.78), float3(0.54, 0.38, 0.58), twilight),
        float3(0.14, 0.17, 0.30),
        nightness * 0.88
    );
    float3 cloudLitCol = mix(
        mix(float3(0.95, 0.96, 1.0), float3(1.0, 0.76, 0.62), twilight),
        float3(0.24, 0.28, 0.44),
        nightness * 0.86
    );
    float3 celCloud = mix(cloudShadowCol, cloudLitCol, cloudLit);
    sky = mix(sky, celCloud, cloudAlpha * mix(0.45, 0.74, max(daylight, twilight)));

    // Crisp Kanto mountain ridge & snow-capped Mt. Fuji silhouette
    float fujiX = (uv.x - (0.24 - uTilt.x * 0.02)) * aspect;
    float ridge = 0.018 * sin(uv.x * 9.5 + 1.2) + 0.010 * cos(uv.x * 20.0);
    float fujiCone = max(0.0, 0.056 - abs(fujiX) * 0.46);
    float mtnHeight = horizonY - max(ridge * smoothstep(0.15, 0.55, uv.y), fujiCone) * 0.70;
    float inMountain = smoothstep(mtnHeight + 0.0025, mtnHeight - 0.0025, uv.y) * step(uv.y, horizonY + 0.012);
    float snowMask = smoothstep(0.024, 0.030, fujiCone) * inMountain;
    float3 mtnBase = mix(horizon * 0.72, float3(0.10, 0.13, 0.26), 0.42 + 0.46 * nightness);
    float3 snowCol = mix(float3(0.92, 0.95, 1.0), float3(1.0, 0.78, 0.70), twilight);
    mtnBase = mix(mtnBase, snowCol, snowMask * (1.0 - nightness * 0.72));
    sky = mix(sky, mtnBase, inMountain * 0.78);

    // Lower urban base bed under 3D anime streetscapes
    float belowHorizon = smoothstep(horizonY - 0.006, horizonY + 0.04, uv.y);
    float3 groundBase = mix(
        mix(horizon * 0.36, float3(0.06, 0.08, 0.14), 0.65),
        float3(0.03, 0.04, 0.08),
        nightness
    );
    float3 col = mix(sky, groundBase, belowHorizon);

    // Subtle soft readability vignette while keeping anime pastel clarity
    float vig = 1.0 - smoothstep(0.32, 1.04, length((uv - 0.5) * float2(1.04, 0.92)));
    col *= 0.72 + 0.28 * vig;
    float centerDamp = exp(-length((uv - float2(0.5, 0.52)) * float2(1.18, 0.85)) * 1.9);
    col = mix(col, col * 0.82 + float3(0.02, 0.03, 0.06), centerDamp * mix(0.24, 0.08, nightness));
    col += filmDither(fragCoord);
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

internal fun agslShaderSourceForLyricTheme(theme: LyricTheme): String? = when (theme) {
    LyricTheme.BLUR -> null
    LyricTheme.DEEP_STAR_SEA -> SHADER_DEEP_STAR_SEA
    LyricTheme.AFTERGLOW_SHORE -> SHADER_AFTERGLOW_SHORE
    LyricTheme.STREAMING_SHADOWS -> SHADER_STREAMING_SHADOWS
    LyricTheme.LIGHT_YEAR_DRIFT -> SHADER_LIGHT_YEAR_DRIFT
    LyricTheme.NEON_CITY -> SHADER_NEON_CITY
    LyricTheme.WIND_LETTER -> SHADER_WIND_LETTER
    LyricTheme.STAR_MOON -> SHADER_STAR_MOON
    LyricTheme.LIME_SODA -> SHADER_LIME_SODA
    LyricTheme.MONET_GARDEN -> SHADER_MONET_GARDEN
    LyricTheme.FADED_FILM -> SHADER_FADED_FILM
    LyricTheme.TOKYO_CITYSCAPE -> SHADER_TOKYO_SKY
}

internal class LyricThemeShaderRenderer(source: String) {
    private val shader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching { RuntimeShader(source) }.getOrNull()
    } else {
        null
    }
    private val paint = shader?.let {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = it }
    }

    val isAvailable: Boolean get() = shader != null && paint != null

    fun draw(
        scope: DrawScope,
        time: Float,
        tilt: Offset,
        tintA: Color,
        tintB: Color,
    ): Boolean {
        val activeShader = shader ?: return false
        val activePaint = paint ?: return false
        activeShader.setFloatUniform("uResolution", scope.size.width.coerceAtLeast(1f), scope.size.height.coerceAtLeast(1f))
        activeShader.setFloatUniform("uTime", time)
        activeShader.setFloatUniform("uTilt", tilt.x, tilt.y)
        activeShader.setFloatUniform("uTintA", tintA.red, tintA.green, tintA.blue, tintA.alpha)
        activeShader.setFloatUniform("uTintB", tintB.red, tintB.green, tintB.blue, tintB.alpha)
        scope.drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawRect(0f, 0f, scope.size.width, scope.size.height, activePaint)
        }
        return true
    }
}
