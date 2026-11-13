package btm.m.auraplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LocalAudioFileInfoTest {
    @Test
    fun resolvesFormatFromExtensionBeforeMimeAndCodec() {
        assertEquals("M4A", LocalAudioFileInfo.formatFromExtensionOrMime("song.m4a", "", "audio/mp4a-latm", "AAC"))
        assertEquals("FLAC", LocalAudioFileInfo.formatFromExtensionOrMime("track.flac", "", "audio/flac", "FLAC"))
        assertEquals("MP3", LocalAudioFileInfo.formatFromExtensionOrMime("", "/storage/emulated/0/Music/a.mp3", "", ""))
    }

    @Test
    fun fallsBackToMimeAndCodecWhenExtensionMissing() {
        assertEquals("WAV", LocalAudioFileInfo.formatFromExtensionOrMime("Audio Track", "", "audio/x-wav", "PCM"))
        assertEquals("FLAC", LocalAudioFileInfo.formatFromExtensionOrMime("Audio Track", "", "", "FLAC"))
        assertEquals("", LocalAudioFileInfo.formatFromExtensionOrMime("Audio Track", "", "", ""))
    }

    @Test
    fun formatsAudioPropertiesAndFallbacks() {
        assertEquals("—", LocalAudioFileInfo.formatTimestamp(0L, Locale.US))
        assertEquals("—", LocalAudioFileInfo.formatBitDepth(0, "bit"))
        assertEquals("24-bit", LocalAudioFileInfo.formatBitDepth(24, "bit"))
        assertEquals("—", LocalAudioFileInfo.formatBitrate(0, "kb/s"))
        assertEquals("987 kb/s", LocalAudioFileInfo.formatBitrate(987_000, "kb/s"))
        assertEquals("—", LocalAudioFileInfo.formatSampleRate(0, "kHz"))
        assertEquals("44.1 kHz", LocalAudioFileInfo.formatSampleRate(44100, "kHz"))
        assertEquals("96 kHz", LocalAudioFileInfo.formatSampleRate(96000, "kHz"))
    }
}
