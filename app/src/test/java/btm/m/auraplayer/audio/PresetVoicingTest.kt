package btm.m.auraplayer.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Exercise actual samples, not just distinct parameter values or playback loudness. */
class PresetVoicingTest {
    private val frequencies = listOf(50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0)

    private fun toneGain(config: DspConfig, hz: Double, amplitude: Double = .02): Double {
        val engine = DspEngine(config, 48000, 2)
        val input = DoubleArray(2); val output = DoubleArray(2)
        var source = 0.0; var result = 0.0
        repeat(24000) { i ->
            val x = amplitude * sin(2 * PI * hz * i / 48000)
            input.fill(x)
            engine.processFrame(input, output)
            if (i >= 12000) { source += x * x; result += output[0] * output[0] }
        }
        return 10 * log10(result / source)
    }

    @Test fun everyPairHasDifferentSpectralShapeEvenAfterRemovingOverallGain() {
        val failures = mutableListOf<String>()
        for (style in SoundStyle.entries) {
            val curves = BuiltInPresets.all.associate { p ->
                p.id to frequencies.map { toneGain(BuiltInPresets.styled(p.config, style), it) }
            }
            var minimum = Double.POSITIVE_INFINITY
            var closest = ""
            for (a in BuiltInPresets.all.indices) for (b in a + 1 until BuiltInPresets.all.size) {
                val first = BuiltInPresets.all[a].id; val second = BuiltInPresets.all[b].id
                val delta = curves.getValue(first).zip(curves.getValue(second)) { x, y -> x - y }
                // An arbitrary volume offset cancels. Require a real tonal-shape difference.
                val contrast = delta.max() - delta.min()
                if (contrast < minimum) { minimum = contrast; closest = "$first/$second" }
                if (contrast < 1.25) failures += "$style $first/$second spectral contrast $contrast dB"
            }
            println("$style minimum gain-independent spectral contrast: $minimum dB ($closest)")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun coreVoicesHaveAudibleBassAndTrebleContrasts() {
        fun config(id: String) = BuiltInPresets.all.first { it.id == id }.config
        val warmthTilt = toneGain(config("warmth"), 80.0) - toneGain(config("warmth"), 8000.0)
        val clarityTilt = toneGain(config("clarity"), 80.0) - toneGain(config("clarity"), 8000.0)
        val bassLift = toneGain(config("deep_bass"), 45.0) - toneGain(config("deep_bass"), 1000.0)
        assertTrue("warmth tilt $warmthTilt", warmthTilt > 4.5)
        assertTrue("clarity tilt $clarityTilt", clarityTilt < -1.5)
        assertTrue("deep bass lift $bassLift", bassLift > 4.0)
        assertEquals(1.4, config("wide_stage").width, 0.0)
        println("Core tone contrasts: warmth=$warmthTilt dB, clarity=$clarityTilt dB, bass=$bassLift dB")
    }

    @Test fun everyDynamicPresetActuallyCompressesLoudAudio() {
        for (preset in BuiltInPresets.all.filter { it.config.ratio > 1.0 }) {
            val dry = toneGain(preset.config.copy(ratio = 1.0), 1000.0, .8)
            val wet = toneGain(preset.config, 1000.0, .8)
            assertTrue("${preset.id} reduction ${dry - wet} dB", dry - wet > 1.0)
            println("${preset.id} sustained-tone compression: ${dry - wet} dB")
        }
    }

    @Test fun safetyHeadroomDoesNotChangeCompressorOrSaturationDrive() {
        for (saturation in listOf(0.0, .18)) {
            val config = DspConfig(preampDb = -1.0, saturation = saturation, ratio = 2.0, thresholdDb = -18.0)
            val normal = DspEngine(config, 48000, 2)
            val wide = DspEngine(config.copy(width = 1.4), 48000, 2)
            val input = DoubleArray(2); val a = DoubleArray(2); val b = DoubleArray(2)
            val expectedScale = 10.0.pow((wide.headroomDb - normal.headroomDb) / 20)
            repeat(48000) { i ->
                input.fill(.7 * sin(2 * PI * 997 * i / 48000))
                normal.processFrame(input, a); wide.processFrame(input, b)
                assertEquals(a[0] * expectedScale, b[0], 1e-9)
            }
        }
    }

    @Test fun headroomUsesCombinedResponseAndStillReservesOverlappingBoosts() {
        val separate = DspConfig(preampDb = 0.0, bands = listOf(EqBand(80.0, 4.0, 2.0), EqBand(6000.0, 4.0, 2.0)))
        assertTrue(DspEngine(separate, 48000, 2).headroomDb > -5.2)
        val overlap = separate.copy(bands = List(3) { EqBand(1000.0, 6.0, 3.0) })
        assertTrue(DspEngine(overlap, 48000, 2).headroomDb <= -19.0)
        // No-EQ custom presets retain their existing gain and PCM scaling contract.
        assertEquals(-3.0, DspEngine(DspConfig(), 48000, 2).headroomDb, 0.0)
    }
}
