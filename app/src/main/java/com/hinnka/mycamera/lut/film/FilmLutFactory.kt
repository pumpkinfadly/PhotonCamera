package com.hinnka.mycamera.lut.film

import com.hinnka.mycamera.color.TransferCurve
import com.hinnka.mycamera.lut.LutConfig
import com.hinnka.mycamera.model.Film
import com.hinnka.mycamera.raw.ColorSpace
import kotlin.math.abs
import kotlin.math.min

/**
 * 将 Film Library 条目（出厂规格元数据）映射为可用的 3D LUT。
 *
 * 与 scripts/generate_film_luts.py 同一套参数化管线：
 * 每通道色调曲线（黑位抬升/高光肩部/对比度/gamma）、亮度感知饱和度、
 * 色相带偏移、分离色调；黑白胶片使用红权重亮度。
 *
 * 说明：颗粒与 ISO 无法用 3D LUT 表达，仅用于参数选择；这是对规格的
 * 参数化演绎，不是光谱级复刻（后者属于 Spektrafilm/Fotufilm 引擎）。
 */
object FilmLutFactory {

    private const val LUT_SIZE = 33
    private const val LUT_STEPS = 256

    private data class FilmLook(
        val blackLift: Float = 0.03f,
        val whiteShoulder: Float = 0.04f,
        val contrast: Float = 1.0f,
        val gamma: Float = 1.0f,
        val saturation: Float = 1.0f,
        val midSaturation: Float = 1.0f,
        val warmth: Float = 0f,          // >0 红升蓝降（暖）；<0 冷
        val greenShiftDegrees: Float = 0f,   // 绿→青/祖母绿
        val greenSatBoost: Float = 1f,
        val skinWarmthDegrees: Float = 0f,
        val shadowTint: FloatArray = floatArrayOf(0f, 0f, 0f),
        val shadowStrength: Float = 0f,
        val highlightTint: FloatArray = floatArrayOf(0f, 0f, 0f),
        val highlightStrength: Float = 0f,
        val monochrome: Boolean = false
    )

    fun filmToLutConfig(film: Film, size: Int = LUT_SIZE): LutConfig {
        val look = resolveFilmLook(film)
        val data = FloatArray(size * size * size * 3)
        val toneLuts = buildToneLuts(look, size, strength = 1f)
        var out = 0
        val denom = (size - 1).toFloat()
        for (bi in 0 until size) {
            val b = bi / denom
            for (gi in 0 until size) {
                val g = gi / denom
                for (ri in 0 until size) {
                    val r = ri / denom
                    val rgb = applyLook(r, g, b, look, toneLuts)
                    data[out++] = rgb[0].coerceIn(0f, 1f)
                    data[out++] = rgb[1].coerceIn(0f, 1f)
                    data[out++] = rgb[2].coerceIn(0f, 1f)
                }
            }
        }
        return LutConfig(
            size = size,
            data = data,
            title = film.name.orEmpty(),
            configDataType = LutConfig.CONFIG_DATA_TYPE_UINT16,
            curve = TransferCurve.SRGB,
            colorSpace = ColorSpace.SRGB
        )
    }

    // ----------------------------------------------------------------------
    // Metadata → look heuristics
    // ----------------------------------------------------------------------

    private fun resolveFilmLook(film: Film): FilmLook {
        val name = film.name.orEmpty().lowercase()
        val manufacturer = film.manufacturer.orEmpty().lowercase()
        val type = film.type.orEmpty().lowercase()
        val contrastLabel = film.contrast.orEmpty().lowercase()

        var look = FilmLook()

        // Type baseline
        look = when {
            type.contains("black & white") || type.contains("b&w") ->
                look.copy(monochrome = true, contrast = 1.25f, blackLift = 0.02f)
            type.contains("slide") ->
                look.copy(saturation = 1.28f, midSaturation = 1.28f, contrast = 1.22f, whiteShoulder = 0.02f)
            type.contains("cinema") ->
                look.copy(shadowTint = floatArrayOf(-0.006f, 0f, 0.012f), shadowStrength = 0.6f)
            else ->
                look.copy(blackLift = 0.045f, contrast = 1.0f)
        }

        // Contrast label
        look = when {
            contrastLabel.contains("very high") -> look.copy(contrast = look.contrast * 1.30f)
            contrastLabel.contains("high") -> look.copy(contrast = look.contrast * 1.15f)
            contrastLabel.contains("low") -> look.copy(contrast = look.contrast * 0.90f, blackLift = look.blackLift + 0.02f)
            else -> look
        }

        // Name/manufacturer keyword signatures
        fun matches(vararg keys: String) = keys.any { name.contains(it) || manufacturer.contains(it) }

        if (!look.monochrome) {
            when {
                matches("portra") -> look = look.copy(
                    warmth = 0.012f, saturation = look.saturation * 0.94f,
                    skinWarmthDegrees = 3f,
                    highlightTint = floatArrayOf(0.018f, 0.010f, -0.008f), highlightStrength = 0.5f
                )
                matches("gold") -> look = look.copy(
                    warmth = 0.030f, midSaturation = look.saturation * 1.08f,
                    shadowTint = floatArrayOf(0.018f, 0.008f, -0.005f), shadowStrength = 0.7f,
                    highlightTint = floatArrayOf(0.028f, 0.018f, -0.012f), highlightStrength = 0.75f
                )
                matches("ektar") -> look = look.copy(
                    saturation = look.saturation * 1.18f, contrast = look.contrast * 1.08f
                )
                matches("ultramax") -> look = look.copy(
                    midSaturation = look.saturation * 1.05f,
                    highlightTint = floatArrayOf(0.014f, 0.006f, -0.004f), highlightStrength = 0.4f
                )
                matches("superia", "natura") -> look = look.copy(
                    greenShiftDegrees = 10f, greenSatBoost = 1.12f,
                    shadowTint = floatArrayOf(-0.004f, 0f, 0.009f), shadowStrength = 0.45f
                )
                matches("velvia") -> look = look.copy(
                    saturation = 1.34f, midSaturation = 1.32f, contrast = 1.28f,
                    greenShiftDegrees = -8f, greenSatBoost = 1.1f
                )
                matches("provia", "astia") -> look = look.copy(
                    saturation = 1.15f, midSaturation = 1.15f, contrast = 1.08f,
                    shadowTint = floatArrayOf(-0.005f, 0f, 0.005f), shadowStrength = 0.3f
                )
                matches("ektachrome", "e100", "e200") -> look = look.copy(
                    saturation = 1.2f, midSaturation = 1.2f, contrast = 1.12f
                )
                matches("cinestill", "vision", "tungsten") -> look = look.copy(
                    warmth = -0.018f,
                    shadowTint = floatArrayOf(-0.010f, -0.002f, 0.018f), shadowStrength = 0.7f,
                    highlightTint = floatArrayOf(0.008f, 0.002f, -0.002f), highlightStrength = 0.35f
                )
                matches("rollei", "cn200") -> look = look.copy(
                    contrast = look.contrast * 1.1f, saturation = look.saturation * 0.98f
                )
                matches("fuji", "fujifilm") -> look = look.copy(
                    greenSatBoost = 1.06f, greenShiftDegrees = 4f
                )
                matches("kodak") -> look = look.copy(warmth = look.warmth + 0.008f)
            }
        }

        // Grain proxy: coarser grain gets slightly stronger mid contrast
        val grainLabel = film.grain.orEmpty().lowercase()
        if (grainLabel.contains("coarse") || grainLabel.contains("medium")) {
            look = look.copy(contrast = look.contrast * 1.04f)
        }

        return look
    }

    // ----------------------------------------------------------------------
    // Rendering pipeline (mirrors generate_film_luts.py)
    // ----------------------------------------------------------------------

    private class ToneLuts(val r: FloatArray, val g: FloatArray, val b: FloatArray)

    private fun buildToneLuts(look: FilmLook, size: Int, strength: Float): ToneLuts {
        fun channelLut(tint: Float): FloatArray {
            val lut = FloatArray(LUT_STEPS)
            for (i in 0 until LUT_STEPS) {
                val v = i / (LUT_STEPS - 1).toFloat()
                var c = ((v - 0.5f) / look.gamma) + 0.5f
                val pivot = 0.45f
                c = pivot + (c - pivot) * look.contrast
                c += tint * strength
                c = look.blackLift + c * (1f - look.blackLift)
                if (look.whiteShoulder > 0f) {
                    val knee = 1f - 2.5f * look.whiteShoulder
                    val over = (c - knee).coerceAtLeast(0f)
                    c -= over * over / (over + look.whiteShoulder)
                }
                lut[i] = c.coerceIn(0f, 1f)
            }
            return lut
        }
        val tintScale = look.warmth
        return ToneLuts(
            r = channelLut(tintScale),
            g = channelLut(0f),
            b = channelLut(-tintScale)
        )
    }

    private fun sample(lut: FloatArray, v: Float): Float {
        if (v <= 0f) return lut[0]
        if (v >= 1f) return lut[lut.size - 1]
        val x = v * (lut.size - 1)
        val i = x.toInt()
        val f = x - i
        val j = min(i + 1, lut.size - 1)
        return lut[i] * (1f - f) + lut[j] * f
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun applyLook(r: Float, g: Float, b: Float, look: FilmLook, tone: ToneLuts): FloatArray {
        if (look.monochrome) {
            val lum = r * 0.34f + g * 0.44f + b * 0.22f
            val v = sample(tone.g, lum)
            return floatArrayOf(v, v, v)
        }

        var rr = sample(tone.r, r)
        var gg = sample(tone.g, g)
        var bb = sample(tone.b, b)

        val lum = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb
        val midBand = 1f - abs(lum - 0.5f) * 2f
        val sat = look.saturation + (look.midSaturation - look.saturation) * midBand
        rr = lum + (rr - lum) * sat
        gg = lum + (gg - lum) * sat
        bb = lum + (bb - lum) * sat

        if (look.greenShiftDegrees != 0f || look.greenSatBoost != 1f) {
            val shifted = hueShift(rr, gg, bb, greenCenter = 0.30f, width = 0.16f,
                shiftDegrees = look.greenShiftDegrees, satBoost = look.greenSatBoost)
            rr = shifted[0]; gg = shifted[1]; bb = shifted[2]
        }
        if (look.skinWarmthDegrees != 0f) {
            val shifted = hueShift(rr, gg, bb, greenCenter = 0.06f, width = 0.16f,
                shiftDegrees = look.skinWarmthDegrees, satBoost = 1.03f)
            rr = shifted[0]; gg = shifted[1]; bb = shifted[2]
        }

        val sw = (1f - lum) * (1f - lum) * look.shadowStrength
        val hw = lum * lum * look.highlightStrength
        rr += look.shadowTint[0] * sw + look.highlightTint[0] * hw
        gg += look.shadowTint[1] * sw + look.highlightTint[1] * hw
        bb += look.shadowTint[2] * sw + look.highlightTint[2] * hw

        return floatArrayOf(rr.coerceIn(0f, 1f), gg.coerceIn(0f, 1f), bb.coerceIn(0f, 1f))
    }

    /** RGB → HLS hue rotation within a band (weighted blend, wrap-aware). */
    private fun hueShift(
        r: Float, g: Float, b: Float,
        greenCenter: Float, width: Float,
        shiftDegrees: Float, satBoost: Float
    ): FloatArray {
        val (hue, light, sat) = rgbToHls(r, g, b)
        if (sat <= 0.003f) return floatArrayOf(r, g, b)
        var d = abs(hue - greenCenter)
        d = min(d, 1f - d)
        val weight = 1f - smoothstep(width * 0.5f, width, d)
        if (weight <= 0f) return floatArrayOf(r, g, b)

        val newHue = (hue + (shiftDegrees / 360f) * weight + 1f) % 1f
        val newSat = min(1f, sat * (1f + (satBoost - 1f) * weight))
        val shifted = hlsToRgb(newHue, light, newSat)
        return floatArrayOf(
            r + (shifted[0] - r) * weight,
            g + (shifted[1] - g) * weight,
            b + (shifted[2] - b) * weight
        )
    }

    /** Equivalent of colorsys.rgb_to_hls for [0,1] floats. */
    private fun rgbToHls(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val light = (maxC + minC) / 2f
        if (maxC == minC) return Triple(0f, light, 0f)
        val delta = maxC - minC
        val sat = if (light <= 0.5f) delta / (maxC + minC) else delta / (2f - maxC - minC)
        val hue = when (maxC) {
            r -> ((g - b) / delta + if (g < b) 6f else 0f) / 6f
            g -> ((b - r) / delta + 2f) / 6f
            else -> ((r - g) / delta + 4f) / 6f
        }
        return Triple(hue, light, sat)
    }

    /** Equivalent of colorsys.hls_to_rgb. */
    private fun hlsToRgb(hue: Float, light: Float, sat: Float): FloatArray {
        if (sat <= 0f) return floatArrayOf(light, light, light)
        fun hueToRgb(m1: Float, m2: Float, h: Float): Float {
            var hh = h
            if (hh < 0f) hh += 1f
            if (hh > 1f) hh -= 1f
            return when {
                hh < 1f / 6f -> m1 + (m2 - m1) * hh * 6f
                hh < 1f / 2f -> m2
                hh < 2f / 3f -> m1 + (m2 - m1) * (2f / 3f - hh) * 6f
                else -> m1
            }
        }
        val m2 = if (light <= 0.5f) light * (1f + sat) else light + sat - light * sat
        val m1 = 2f * light - m2
        return floatArrayOf(
            hueToRgb(m1, m2, hue + 1f / 3f),
            hueToRgb(m1, m2, hue),
            hueToRgb(m1, m2, hue - 1f / 3f)
        )
    }
}
