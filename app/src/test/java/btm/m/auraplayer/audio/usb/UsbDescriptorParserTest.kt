package btm.m.auraplayer.audio.usb

import androidx.media3.common.C
import btm.m.auraplayer.audio.EffectsState
import btm.m.auraplayer.audio.EffectsStateCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbDescriptorParserTest {

    @Test
    fun parsesUac2ClockSourceFeatureUnitAndTargetStreamingAltSettings() {
        val raw = byteArrayOf(
            // Interface 0 (AudioControl: class=1, subclass=1)
            9, 0x04, 0, 0, 0, 1, 1, 0x20, 0,
            // AC Header (bcdADC=0x0200 -> UAC2)
            9, 0x24, 0x01, 0x00, 0x02, 0x01, 0x20, 0x00, 0x00,
            // AC Clock Source (bClockID=0x29)
            8, 0x24, 0x0A, 0x29, 0x03, 0x07, 0x00, 0x00,
            // AC Input Terminal 1 (USB Streaming)
            17, 0x24, 0x02, 0x01, 0x01, 0x01, 0x00, 0x29, 0x02, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            // AC Feature Unit 2 (playback path: source=1, master Mute+Vol R/W=0x0F)
            18, 0x24, 0x06, 0x02, 0x01,
            0x0F, 0x00, 0x00, 0x00, // bmaControls(0)
            0x00, 0x00, 0x00, 0x00, // bmaControls(1)
            0x00, 0x00, 0x00, 0x00, // bmaControls(2)
            0x00,
            // AC Output Terminal 3 (Headphones 0x0302, source=2)
            12, 0x24, 0x03, 0x03, 0x02, 0x03, 0x00, 0x02, 0x29, 0x00, 0x00, 0x00,
            // AC Feature Unit 9 (mic path: source=8, should NOT override playback FU 2)
            14, 0x24, 0x06, 0x09, 0x08,
            0x0F, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00,
            // AC Output Terminal 10 (USB Streaming 0x0101, source=9)
            12, 0x24, 0x03, 0x0A, 0x01, 0x01, 0x00, 0x09, 0x29, 0x00, 0x00, 0x00,
            // Interface 1 alt 0 (Playback AudioStreaming: class=1, subclass=2)
            9, 0x04, 1, 0, 0, 1, 2, 0x20, 0,
            // Interface 1 alt 1: 16-bit (subslot=2, bits=16)
            9, 0x04, 1, 1, 2, 1, 2, 0x20, 0,
            6, 0x24, 0x02, 0x01, 2, 16,
            // Interface 1 alt 2: 24-bit in 32-bit subslot (subslot=4, bits=24)
            9, 0x04, 1, 2, 2, 1, 2, 0x20, 0,
            6, 0x24, 0x02, 0x01, 4, 24,
            // Interface 2 alt 1 (Recording AudioStreaming: should be ignored!)
            9, 0x04, 2, 1, 1, 1, 2, 0x20, 0,
            6, 0x24, 0x02, 0x01, 2, 16,
        )

        val parsed = UsbDescriptorParser.parse(raw, targetStreamingInterfaceId = 1)
        assertFalse(parsed.isUac1)
        assertEquals(0x29, parsed.clockSourceId)
        assertNotNull(parsed.playbackFeatureUnit)
        assertEquals(0x02, parsed.playbackFeatureUnit?.unitId)
        assertTrue(parsed.playbackFeatureUnit?.supportsMasterVolume == true)
        assertTrue(parsed.playbackFeatureUnit?.supportsMasterMute == true)
        assertEquals(2, parsed.altFormats.size)
        assertEquals(32, parsed.altFormats[1].wireBitDepth)
    }

    @Test
    fun parsesUac1FeatureUnitAndVolumeRangeMapping() {
        val raw = byteArrayOf(
            // Interface 0 (AudioControl)
            9, 0x04, 0, 0, 0, 1, 1, 0x00, 0,
            // AC Header (bcdADC=0x0100 -> UAC1)
            9, 0x24, 0x01, 0x00, 0x01, 0x1E, 0x00, 0x01, 0x01,
            // UAC1 Feature Unit 5: source=1, controlSize=1, ch0=mute, ch1=vol, ch2=vol
            10, 0x24, 0x06, 0x05, 0x01, 0x01, 0x01, 0x02, 0x02, 0x00,
            // UAC1 Output Terminal 6: Speaker 0x0301, source=5
            9, 0x24, 0x03, 0x06, 0x01, 0x03, 0x00, 0x05, 0x00,
            // Interface 1 alt 1 (Playback AudioStreaming)
            9, 0x04, 1, 1, 1, 1, 2, 0x00, 0,
            11, 0x24, 0x02, 0x01, 2, 3, 24, 1, 0x44, 0xAC.toByte(), 0x00,
        )

        val parsed = UsbDescriptorParser.parse(raw, targetStreamingInterfaceId = 1)
        assertTrue(parsed.isUac1)
        assertEquals(0x05, parsed.playbackFeatureUnit?.unitId)
        assertFalse(parsed.playbackFeatureUnit?.supportsMasterVolume == true)
        assertEquals(2, parsed.playbackFeatureUnit?.channelVolumeCount)

        val range = UsbVolumeRange(minDb256 = -25600, maxDb256 = 0, resDb256 = 256)
        // Safe 30% default should be substantially attenuated (~ -42 dB = -10752), never 0 dB!
        val db30 = range.toDb256(0.30f).toInt()
        assertTrue(db30 < -8000 && db30 > -14000)
        assertEquals(0, range.toDb256(1.0f).toInt())
    }

    @Test
    fun effectsStateCodecPersistsUsbTuningAndVolume() {
        val defaultDecoded = EffectsStateCodec.decode("{}")
        assertFalse(defaultDecoded.usbExclusive)
        assertEquals(100, defaultDecoded.usbBufferMs)
        assertFalse(defaultDecoded.usbLockFixedFormat)
        assertEquals(0.30f, defaultDecoded.usbVolume, 0.001f)
        assertTrue(defaultDecoded.usbVolumeCollapseOnRepeat)
        assertEquals("right", defaultDecoded.usbVolumePosition)
        assertEquals(0, defaultDecoded.usbVolumeOffsetY)
        assertEquals(0, defaultDecoded.usbVolumeOffsetX)
        assertEquals(0, defaultDecoded.usbVolumeHeightScale)
        assertEquals(0, defaultDecoded.usbVolumeWidthScale)

        val custom = EffectsState(
            usbExclusive = true,
            usbBufferMs = 200,
            usbLockFixedFormat = true,
            usbVolume = 0.52f,
            usbVolumeCollapseOnRepeat = false,
            usbVolumePosition = "left",
            usbVolumeOffsetY = 48,
            usbVolumeOffsetX = -12,
            usbVolumeHeightScale = 25,
            usbVolumeWidthScale = -20,
        )
        val decoded = EffectsStateCodec.decode(EffectsStateCodec.encode(custom))
        assertTrue(decoded.usbExclusive)
        assertEquals(200, decoded.usbBufferMs)
        assertTrue(decoded.usbLockFixedFormat)
        assertEquals(0.52f, decoded.usbVolume, 0.001f)
        assertFalse(decoded.usbVolumeCollapseOnRepeat)
        assertEquals("left", decoded.usbVolumePosition)
        assertEquals(48, decoded.usbVolumeOffsetY)
        assertEquals(-12, decoded.usbVolumeOffsetX)
        assertEquals(25, decoded.usbVolumeHeightScale)
        assertEquals(-20, decoded.usbVolumeWidthScale)
    }

    @Test
    fun stereoResamplerConverts44100To48000AcrossBuffers() {
        val resampler = UsbStereoResampler()
        val frames = 441
        val buf = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val s = ((i % 40) * 500).toShort()
            buf.putShort(s)
            buf.putShort((-s).toShort())
        }
        buf.flip()
        val out = resampler.process(buf, C.ENCODING_PCM_16BIT, inputChannels = 2, inputRate = 44100, outputRate = 48000)
        val outFrames = out.size / 2
        assertTrue(outFrames in 470..485)
    }
}
