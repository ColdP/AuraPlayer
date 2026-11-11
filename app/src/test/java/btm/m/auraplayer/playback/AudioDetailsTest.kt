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
}
