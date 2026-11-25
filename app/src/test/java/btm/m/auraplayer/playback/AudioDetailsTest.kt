package btm.m.auraplayer.playback

import org.junit.Assert.*
import org.junit.Test

class AudioDetailsTest {
    @Test fun readsFlacStreamInfoBitDepthWithoutGuessingFromRate() {
        val streamInfo = ByteArray(34)
        streamInfo[12] = 1
        streamInfo[13] = 0x70
        assertEquals(24, AudioDetails.flacBits(streamInfo))
        val header = byteArrayOf(102, 76, 97, 67, 0, 0, 0, 34) + streamInfo
        assertEquals(24, AudioDetails.flacBits(header))
        assertEquals(0, AudioDetails.flacBits(ByteArray(12)))
    }
    @Test fun unknownEncodingNeverBecomes16Bit() {
        assertEquals(0, AudioDetails.pcmBits(-1))
        assertEquals(0, AudioDetails.pcmBits(0))
        assertEquals(16, AudioDetails.pcmBits(androidx.media3.common.C.ENCODING_PCM_16BIT))
    }
    @Test fun readsAlacCookieBitDepthAndExtendedCodecNames() {
        val rawAlac = ByteArray(24)
        rawAlac[5] = 24
        assertEquals(24, AudioDetails.alacBits(rawAlac))
        val fullAtomAlac = ByteArray(36)
        fullAtomAlac[4] = 'a'.code.toByte()
        fullAtomAlac[5] = 'l'.code.toByte()
        fullAtomAlac[6] = 'a'.code.toByte()
        fullAtomAlac[7] = 'c'.code.toByte()
        fullAtomAlac[17] = 16
        assertEquals(16, AudioDetails.alacBits(fullAtomAlac))
        assertEquals("ALAC", AudioDetails.codecFor("audio/alac"))
        assertEquals("TrueHD", AudioDetails.codecFor("audio/true-hd"))
        assertEquals("DTS", AudioDetails.codecFor("audio/vnd.dts"))
    }
}
