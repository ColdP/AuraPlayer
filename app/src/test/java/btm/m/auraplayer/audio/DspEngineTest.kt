package btm.m.auraplayer.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import kotlin.random.Random

class DspEngineTest {
    private fun rms(values: List<Double>) = sqrt(values.sumOf { it * it } / values.size)

    @Test fun referenceDoesNotChangeAnySampleEvenAboveFullScale() {
        val engine = DspEngine(BuiltInPresets.all.first().config, 96000, 2)
        val input = doubleArrayOf(-1.0, .123456789)
        val output = DoubleArray(2)
        engine.processFrame(input, output)
        assertArrayEquals(input, output, 0.0)
        input[0] = 1.25; input[1] = -2.0
        engine.processFrame(input, output)
        assertArrayEquals(input, output, 0.0)
        assertEquals(0.0, engine.headroomDb, 0.0)
        for (style in SoundStyle.entries) assertEquals(engine.config, BuiltInPresets.styled(engine.config, style))
    }

    @Test fun rbjPeakHasSpecifiedGainAtItsCenter() {
        val filter = Biquad(EqBand(1000.0, 3.0, 1.0), 48000, 1)
        val input = ArrayList<Double>(); val output = ArrayList<Double>()
        repeat(48000) { i ->
            val x = .1 * sin(2 * PI * 1000 * i / 48000)
            val y = filter.process(x, 0)
            if (i > 24000) { input += x; output += y }
        }
        assertEquals(3.0, 20 * log10(rms(output) / rms(input)), .01)
    }

    @Test fun shelvesHaveCorrectLowAndHighFrequencyLimits() {
        fun gain(type: FilterType, hz: Double): Double {
            val filter = Biquad(EqBand(1000.0, 3.0, .707, type), 48000, 1)
            val input = ArrayList<Double>(); val output = ArrayList<Double>()
            repeat(48000) { i ->
                val x = .05 * sin(2 * PI * hz * i / 48000)
                val y = filter.process(x, 0)
                if (i >= 24000) { input += x; output += y }
            }
            return 20 * log10(rms(output) / rms(input))
        }
        assertEquals(3.0, gain(FilterType.LOW_SHELF, 30.0), .03)
        assertEquals(0.0, gain(FilterType.LOW_SHELF, 15000.0), .03)
        assertEquals(0.0, gain(FilterType.HIGH_SHELF, 30.0), .03)
        assertEquals(3.0, gain(FilterType.HIGH_SHELF, 15000.0), .03)
    }

    @Test fun presetsRemainFiniteAndBoundedAcrossFormatsAndStyles() {
        val random = Random(71)
        for (preset in BuiltInPresets.all.filterNot { it.config.bypass }) {
            for (style in SoundStyle.entries) for (rate in listOf(8000, 44100, 48000, 96000, 192000)) for (channels in listOf(1, 2, 6)) {
                val config = BuiltInPresets.styled(preset.config, style)
                val engine = DspEngine(config, rate, channels)
                val input = DoubleArray(channels); val output = DoubleArray(channels)
                repeat(1200) {
                    for (c in input.indices) input[c] = random.nextDouble(-1.0, 1.0)
                    engine.processFrame(input, output)
                    assertTrue("${preset.id}/$style/$rate/$channels", output.all { it.isFinite() && abs(it) <= .980000001 })
                }
                assertTrue(engine.headroomDb < 0)
            }
        }
    }

    @Test fun compressorReducesLoudSustainedAudio() {
        val uncompressed = DspEngine(DspConfig(preampDb = 0.0), 48000, 2)
        val compressed = DspEngine(DspConfig(preampDb = 0.0, ratio = 4.0, thresholdDb = -18.0), 48000, 2)
        val input = DoubleArray(2); val a = DoubleArray(2); val b = DoubleArray(2)
        var energyA = 0.0; var energyB = 0.0
        repeat(96000) { i ->
            input.fill(.8 * sin(2 * PI * 997 * i / 48000))
            uncompressed.processFrame(input, a); compressed.processFrame(input, b)
            if (i >= 48000) { energyA += a[0] * a[0]; energyB += b[0] * b[0] }
        }
        assertTrue(energyB < energyA * .25)
    }

    @Test fun stereoWidthChangesSideToMidRatioAndMonoRemainsMono() {
        val normal = DspEngine(DspConfig(width = 1.0), 48000, 2)
        val wide = DspEngine(DspConfig(width = 1.3), 48000, 2)
        val a = DoubleArray(2); val b = DoubleArray(2)
        normal.processFrame(doubleArrayOf(.5, .1), a); wide.processFrame(doubleArrayOf(.5, .1), b)
        fun sideMid(x: DoubleArray) = (x[0] - x[1]) / (x[0] + x[1])
        assertEquals(1.3, sideMid(b) / sideMid(a), 1e-9)
        wide.processFrame(doubleArrayOf(.25, .25), b)
        assertEquals(b[0], b[1], 0.0)
        val monoA = DspEngine(DspConfig(), 48000, 1)
        val monoB = DspEngine(DspConfig(width = 1.4, crossfeed = .3), 48000, 1)
        repeat(1000) {
            val x = doubleArrayOf(sin(it.toDouble()) * .4)
            monoA.processFrame(x, a); monoB.processFrame(x, b)
            assertEquals(a[0], b[0], 0.0)
        }
    }

    @Test fun crossfeedSendsLowFrequencyEnergyToOppositeChannel() {
        val engine = DspEngine(DspConfig(crossfeed = .2), 48000, 2)
        val output = DoubleArray(2); val input = DoubleArray(2)
        var rightEnergy = 0.0
        repeat(4800) { i ->
            input[0] = .5 * sin(2 * PI * 200 * i / 48000)
            engine.processFrame(input, output); rightEnergy += output[1] * output[1]
        }
        assertTrue(rightEnergy > 1.0)
    }

    @Test fun saturationAddsOddHarmonicsWithoutDcOffset() {
        val engine = DspEngine(DspConfig(preampDb = 0.0, saturation = .25), 48000, 1)
        val input = DoubleArray(1); val output = DoubleArray(1)
        var dc = 0.0; var third = 0.0
        repeat(48000) { i ->
            input[0] = .9 * sin(2 * PI * 1000 * i / 48000)
            engine.processFrame(input, output)
            dc += output[0]; third += output[0] * sin(2 * PI * 3000 * i / 48000)
        }
        assertEquals(0.0, dc / 48000, 1e-6)
        assertTrue(abs(third / 24000) > .001)
    }

    @Test fun maximumAllowedCustomSettingsDoNotClip() {
        val config = DspConfig(bands = List(8) { EqBand(1000.0, 6.0, 3.0) }, preampDb = 0.0, width = 1.4, crossfeed = .3, saturation = .25, ratio = 4.0)
        val engine = DspEngine(config, 8000, 2)
        val output = DoubleArray(2)
        repeat(16000) { i ->
            val x = if (i % 4 < 2) 1.0 else -1.0
            engine.processFrame(doubleArrayOf(x, -x), output)
            assertTrue(output.all { it.isFinite() && abs(it) <= .98 })
        }
    }

    @Test fun everyPresetHasDistinctRealParameters() {
        assertEquals(15, BuiltInPresets.all.size)
        assertEquals(15, BuiltInPresets.all.map { it.config }.toSet().size)
        assertEquals(5, BuiltInPresets.all.count { it.core })
        assertTrue(BuiltInPresets.all.drop(1).none { it.config.bypass })
        val eight = DspConfig(bands = List(8) { EqBand(1000.0, 0.0) })
        SoundStyle.entries.forEach { BuiltInPresets.styled(eight, it).validate() }
    }
}
