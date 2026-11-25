package btm.m.auraplayer.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory

/**
 * Combines Media3's default container extractors (with CBR seeking enabled) and custom
 * extractors for formats missing from Media3 (AIFF/AIFC, Apple CAF, and Sony DSF/DSD).
 */
@UnstableApi
class AuraExtractorsFactory : ExtractorsFactory {
    private val defaultFactory = DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(true)

    override fun createExtractors(): Array<Extractor> =
        defaultFactory.createExtractors() + arrayOf(
            AiffExtractor(),
            CafExtractor(),
            DsfExtractor(),
        )

    override fun createExtractors(
        uri: Uri,
        responseHeaders: MutableMap<String, MutableList<String>>,
    ): Array<Extractor> =
        defaultFactory.createExtractors(uri, responseHeaders) + arrayOf(
            AiffExtractor(),
            CafExtractor(),
            DsfExtractor(),
        )
}
