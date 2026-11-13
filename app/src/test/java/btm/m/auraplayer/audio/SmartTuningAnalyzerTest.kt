package btm.m.auraplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import kotlin.random.Random

class SmartTuningAnalyzerTest {

    private fun feedSines(analyzer: SmartTuningAnalyzer, sampleRate: Int, channels: Int, seconds: Double, components: List<Pair<Double, Double>>, sideScale: Double = 0.3) {
        val totalFrames = (sampleRate * seconds).toInt()
        val frame = DoubleArray(channels)
        for (n in 0 until totalFrames) {
            var mid = 0.0
            for ((hz, amp) in components) {
                mid += amp * sin(2.0 * PI * hz * n / sampleRate)
            }
            if (channels == 2) {
                val side = mid * sideScale * cos(0.7 * n / sampleRate * 2 * PI * 3.0)
                frame[0] = (mid + side).coerceIn(-0.95, 0.95)
                frame[1] = (mid - side).coerceIn(-0.95, 0.95)
            } else {
                frame.fill(mid.coerceIn(-0.95, 0.95))
            }
            analyzer.offerFrame(frame)
        }
    }

    @Test fun muddyLowMidTrackGetsMidCutAndHighLift() {
        val analyzer = SmartTuningAnalyzer(48000, 2)
        feedSines(analyzer, 48000, 2, 1.5, listOf(180.0 to 0.35, 420.0 to 0.30, 3200.0 to 0.02))
        val cfg = analyzer.currentConfig.validate()
        val lowMid = cfg.bands[1].gainDb // 175 Hz
        val highMid = cfg.bands[4].gainDb // 2800 Hz
        assertTrue("Expected low-mid cut < 0, got $lowMid", lowMid < -0.5)
        assertTrue("Expected high-mid lift > low-mid, got highMid=$highMid lowMid=$lowMid", highMid > lowMid + 1.5)
    }

    @Test fun brightThinTrackGetsBassWarmthAndTrebleTaming() {
        val analyzer = SmartTuningAnalyzer(48000, 2)
        feedSines(analyzer, 48000, 2, 1.5, listOf(70.0 to 0.01, 2800.0 to 0.30, 7500.0 to 0.30))
        val cfg = analyzer.currentConfig.validate()
        val sub = cfg.bands[0].gainDb // 65 Hz
        val high = cfg.bands[5].gainDb // 7500 Hz
        assertTrue("Expected bass boost > 0.5, got $sub", sub > 0.5)
        assertTrue("Expected treble cut < bass boost, got high=$high sub=$sub", high < sub - 1.5)
    }

    @Test fun silenceDoesNotSkewFromNeutralConfig() {
        val analyzer = SmartTuningAnalyzer(48000, 2)
        val frame = DoubleArray(2)
        repeat(48000 * 2) { i ->
            frame[0] = 1e-4 * sin(2.0 * PI * 200.0 * i / 48000)
            frame[1] = frame[0]
            assertFalse(analyzer.offerFrame(frame))
        }
        assertEquals(SmartTuningAnalyzer.neutralConfig(), analyzer.currentConfig)
    }

    @Test fun dynamicCrestFactorControlsCompressionRatio() {
        val flatCompressed = SmartTuningAnalyzer(48000, 2)
        val frame = DoubleArray(2)
        // Low crest factor (~3 dB): soft-clipped dense tone
        repeat(48000) { i ->
            val v = tanh(3.5 * sin(2.0 * PI * 400.0 * i / 48000)) * 0.5
            frame.fill(v)
            flatCompressed.offerFrame(frame)
        }
        assertEquals(1.0, flatCompressed.currentConfig.ratio, 0.0)

        val wideDynamic = SmartTuningAnalyzer(48000, 2)
        // High crest factor (>15 dB): short loud transients over quiet sustain
        repeat(48000) { i ->
            val pos = i % 960
            val env = if (pos < 40) 0.9 else 0.06
            val v = env * sin(2.0 * PI * 1000.0 * i / 48000)
            frame.fill(v)
            wideDynamic.offerFrame(frame)
        }
        assertTrue("Expected gentle compression on high-crest audio, got ratio=${wideDynamic.currentConfig.ratio}",
            wideDynamic.currentConfig.ratio > 1.0)
    }

    @Test fun stereoWidthAdaptsToSideRatioAndMonoRemainsUnity() {
        val narrow = SmartTuningAnalyzer(48000, 2)
        feedSines(narrow, 48000, 2, 1.0, listOf(220.0 to 0.3, 1200.0 to 0.2), sideScale = 0.02)
        assertTrue("Expected widening for narrow stereo, got ${narrow.currentConfig.width}",
            narrow.currentConfig.width > 1.08)

        val wide = SmartTuningAnalyzer(48000, 2)
        val frame = DoubleArray(2)
        repeat(48000) { i ->
            val s = 0.4 * sin(2.0 * PI * 800.0 * i / 48000)
            frame[0] = s
            frame[1] = -s * 0.9
            wide.offerFrame(frame)
        }
        assertTrue("Expected controlled width for ultra-wide audio, got ${wide.currentConfig.width}",
            wide.currentConfig.width <= 1.02)

        val mono = SmartTuningAnalyzer(48000, 1)
        feedSines(mono, 48000, 1, 1.0, listOf(220.0 to 0.3, 1200.0 to 0.2))
        assertEquals(1.0, mono.currentConfig.width, 0.0)
    }

    @Test fun processorStreamsSmartTuningSafelyAcrossRatesAndChannels() {
        val random = Random(99)
        for (style in SoundStyle.entries) {
            for (rate in listOf(8000, 44100, 48000, 96000, 192000)) {
                for (channels in listOf(1, 2, 6)) {
                    var updates = 0
                    var lastConfig: DspConfig? = null
                    val processor = AuraAudioProcessor(
                        config = { BuiltInPresets.smart.config },
                        smartStyle = { style },
                        trackGeneration = { 1L },
                        onSmartConfig = { updates++; lastConfig = it },
                    )
                    processor.configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_FLOAT))
                    processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
                    val frames = rate / 2 // 0.5 seconds
                    val buf = ByteBuffer.allocate(frames * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
                    for (n in 0 until frames) {
                        val s = (0.3 * sin(2.0 * PI * 220.0 * n / rate) + random.nextDouble(-0.1, 0.1)).toFloat()
                        repeat(channels) { buf.putFloat(s) }
                    }
                    buf.flip()
                    processor.queueInput(buf)
                    val out = processor.output
                    assertEquals(frames * channels * 4, out.remaining())
                    while (out.hasRemaining()) {
                        val sample = out.float
                        assertTrue("$style/$rate/$channels sample=$sample", sample.isFinite() && abs(sample) <= 0.981f)
                    }
                    assertTrue("Expected smart tuning callback at $rate/$channels", updates >= 1)
                    lastConfig?.validate()
                }
            }
        }
    }

    @Test fun smartPresetPersistsAndAppearsInFeaturedWhenSelected() {
        val selected = EffectsState().requestPreset(BuiltInPresets.SMART_ID, 123L)
        assertEquals(BuiltInPresets.SMART_ID, selected.selected)
        assertEquals(BuiltInPresets.SMART_ID, selected.featured.first().id)

        val restored = EffectsStateCodec.decode(EffectsStateCodec.encode(selected))
        assertEquals(BuiltInPresets.SMART_ID, restored.selected)
        assertEquals(BuiltInPresets.SMART_ID, restored.current.id)

        // Default selection continues to surface the 5 core presets first.
        assertEquals(BuiltInPresets.all.take(5).map { it.id }, EffectsState().featured.map { it.id })
    }
}
