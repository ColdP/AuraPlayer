package btm.m.auraplayer.audio

import btm.m.auraplayer.ui.generateSoundPresetFileName
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

class PresetCodecTest {
    @Test fun everyBuiltinRoundTripsWithoutChangingAudioParameters() {
        BuiltInPresets.all.forEach { p ->
            val imported = PresetCodec.decode(PresetCodec.encode(p))
            assertEquals(p.config, imported.config)
            assertEquals(p.name, imported.name)
            assertNotEquals(p.id, imported.id)
            assertTrue(imported.id.startsWith("imported-"))
            assertFalse(imported.core)
        }
    }
    @Test fun incompatibleOrOutOfRangePresetsAreRejected() {
        fun rejected(change: (JSONObject) -> Unit) {
            val j = JSONObject(PresetCodec.encode(BuiltInPresets.all[1])); change(j)
            assertTrue(runCatching { PresetCodec.decode(j.toString()) }.isFailure)
        }
        rejected { it.put("version", 999) }
        rejected { it.put("format", "other-app") }
        rejected { it.put("name", " ") }
        rejected { it.put("name", "a".repeat(81)) }
        rejected { it.getJSONObject("dsp").put("width", 5.0) }
        rejected { it.getJSONObject("dsp").put("preampDb", 6.0) }
        rejected { it.getJSONObject("dsp").getJSONArray("bands").getJSONObject(0).put("q", 0.0) }
        rejected { it.getJSONObject("dsp").getJSONArray("bands").getJSONObject(0).put("frequency", -50) }
        rejected { it.getJSONObject("dsp").getJSONArray("bands").getJSONObject(0).put("gainDb", 30) }
        rejected { it.getJSONObject("dsp").getJSONArray("bands").getJSONObject(0).put("type", "EXECUTE") }
        assertTrue(runCatching { PresetCodec.decode("x".repeat(PresetCodec.MAX_BYTES + 1)) }.isFailure)
        assertTrue(runCatching { DspConfig(width = Double.NaN).validate() }.isFailure)
        assertTrue(runCatching { DspConfig(bands = List(9) { EqBand(1000.0, 0.0) }).validate() }.isFailure)
    }
    @Test fun selectedLibraryPresetDisplacesCoreButUsageControlsOtherSlots() {
        val all = BuiltInPresets.all
        assertEquals(all.take(5), featuredPresets(all, "reference", emptyMap(), emptyMap()))
        val featured = featuredPresets(all, "piano", mapOf("rock" to 8, "jazz" to 3), emptyMap())
        assertEquals(listOf("piano", "rock", "jazz", "reference", "warmth"), featured.map { it.id })
        assertEquals("jazz", featuredPresets(all, "jazz", mapOf("rock" to 100), emptyMap()).first().id)
    }
    @Test fun tiedUsageUsesMostRecentSelection() {
        val result = featuredPresets(BuiltInPresets.all, "reference", mapOf("jazz" to 3, "rock" to 3), mapOf("rock" to 5L, "jazz" to 10L))
        assertEquals(listOf("reference", "jazz", "rock"), result.take(3).map { it.id })
    }
    @Test fun qrEnvelopeRoundTripsUtf8AndFitsComfortablyInQr() {
        for (p in BuiltInPresets.all) {
            val json = PresetCodec.encode(p.copy(name = "清透澄澈 / 透明感"))
            val envelope = PresetQr.pack(json)
            assertEquals(json, PresetQr.unpack(envelope))
            assertTrue(envelope.length < 1800)
            val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(envelope, com.google.zxing.BarcodeFormat.QR_CODE, 768, 768)
            assertEquals(768, matrix.width)
        }
    }
    @Test fun compressedQrCannotExpandWithoutBound() {
        val bytes = ByteArrayOutputStream()
        DeflaterOutputStream(bytes).use { it.write(ByteArray(PresetCodec.MAX_BYTES + 1)) }
        val payload = "aura-dsp:1:" + Base64.getUrlEncoder().encodeToString(bytes.toByteArray())
        assertTrue(runCatching { PresetQr.unpack(payload) }.isFailure)
        assertTrue(runCatching { PresetQr.unpack("aura-dsp:1:not-valid!") }.isFailure)
    }
    @Test fun soundPresetExportFileNameFollowsTimestampAndRandomDigitsTemplate() {
        val defaultName = generateSoundPresetFileName()
        assertTrue(Regex("^AuraSoundPreset_\\d{14}_\\d{4}\\.json$").matches(defaultName))
        val customPng = generateSoundPresetFileName(extension = "png", randomDigits = 42)
        assertTrue(Regex("^AuraSoundPreset_\\d{14}_0042\\.png$").matches(customPng))
    }
}
