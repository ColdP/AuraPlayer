package btm.m.auraplayer.sing

import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class AuraSingExportStem(val fileSuffix: String) {
    VOCALS("Vocal"),
    INSTRUMENTAL("Instrumental"),
}

data class AuraSingExportTarget(
    val entry: AuraSingCacheEntry,
    val stem: AuraSingExportStem,
)

object AuraSingExport {
    const val WAV_HEADER_SIZE = 44

    fun sanitizeFileName(raw: String, fallback: String = "Track"): String {
        val cleaned = raw
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_")
            .trim()
            .trim('.')
        return cleaned.ifBlank { fallback }.take(80)
    }

    fun suggestedFileName(
        targets: List<AuraSingExportTarget>,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        if (targets.size == 1) {
            val single = targets[0]
            val title = sanitizeFileName(single.entry.title.ifBlank { single.entry.songKey })
            return "${title}_${single.stem.fileSuffix}.wav"
        }
        val uniqueSongs = targets.map { it.entry.id }.distinct()
        if (uniqueSongs.size == 1 && targets.isNotEmpty()) {
            val title = sanitizeFileName(targets[0].entry.title.ifBlank { targets[0].entry.songKey })
            return "${title}_AuraSing_Stems.zip"
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(nowMs))
        return "AuraSing_Export_$stamp.zip"
    }

    fun buildWavHeader(
        pcmDataBytes: Long,
        sampleRate: Int = AuraSingMath.HTDEMUCS_SAMPLE_RATE,
        channels: Int = AuraSingMath.HTDEMUCS_CHANNELS,
        bitsPerSample: Int = 16,
    ): ByteArray {
        val safeDataBytes = pcmDataBytes.coerceIn(0L, 0xFFFF_FFD0L)
        val riffChunkSize = safeDataBytes + 36L
        val byteRate = sampleRate.toLong() * channels.toLong() * (bitsPerSample / 8L)
        val blockAlign = (channels * (bitsPerSample / 8)).toShort()

        return ByteArray(WAV_HEADER_SIZE).also { header ->
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            buf.put('R'.code.toByte()); buf.put('I'.code.toByte()); buf.put('F'.code.toByte()); buf.put('F'.code.toByte())
            buf.putInt(riffChunkSize.toInt())
            buf.put('W'.code.toByte()); buf.put('A'.code.toByte()); buf.put('V'.code.toByte()); buf.put('E'.code.toByte())
            buf.put('f'.code.toByte()); buf.put('m'.code.toByte()); buf.put('t'.code.toByte()); buf.put(' '.code.toByte())
            buf.putInt(16) // PCM subchunk1 size
            buf.putShort(1.toShort()) // AudioFormat = 1 (PCM)
            buf.putShort(channels.toShort())
            buf.putInt(sampleRate)
            buf.putInt(byteRate.toInt())
            buf.putShort(blockAlign)
            buf.putShort(bitsPerSample.toShort())
            buf.put('d'.code.toByte()); buf.put('a'.code.toByte()); buf.put('t'.code.toByte()); buf.put('a'.code.toByte())
            buf.putInt(safeDataBytes.toInt())
        }
    }

    fun exportTargetsToStream(
        cache: AuraSingCache,
        targets: List<AuraSingExportTarget>,
        output: OutputStream,
    ) {
        require(targets.isNotEmpty()) { "未选择导出音轨" }
        if (targets.size == 1) {
            val target = targets[0]
            val pcmFile = stemFile(cache, target.entry, target.stem)
            require(pcmFile.isFile) { "缓存音轨文件不存在" }
            writeWavStream(pcmFile, target.entry, output)
            output.flush()
            return
        }

        val usedNames = mutableSetOf<String>()
        ZipOutputStream(output.buffered()).use { zos ->
            for (target in targets) {
                val pcmFile = stemFile(cache, target.entry, target.stem)
                if (!pcmFile.isFile) continue
                val baseTitle = sanitizeFileName(target.entry.title.ifBlank { target.entry.songKey })
                var entryName = "${baseTitle}_${target.stem.fileSuffix}.wav"
                var suffix = 2
                while (!usedNames.add(entryName)) {
                    entryName = "${baseTitle}_${target.stem.fileSuffix}_$suffix.wav"
                    suffix++
                }
                zos.putNextEntry(ZipEntry(entryName))
                writeWavStream(pcmFile, target.entry, zos)
                zos.closeEntry()
            }
            zos.finish()
        }
    }

    fun buildTargets(
        entries: List<AuraSingCacheEntry>,
        selectedEntryIds: Set<String>,
        selectedStems: Set<AuraSingExportStem>,
    ): List<AuraSingExportTarget> {
        val result = mutableListOf<AuraSingExportTarget>()
        for (entry in entries) {
            if (entry.id !in selectedEntryIds) continue
            for (stem in AuraSingExportStem.entries) {
                if (stem in selectedStems) {
                    result += AuraSingExportTarget(entry, stem)
                }
            }
        }
        return result
    }

    private fun stemFile(cache: AuraSingCache, entry: AuraSingCacheEntry, stem: AuraSingExportStem): File =
        when (stem) {
            AuraSingExportStem.VOCALS -> cache.vocalsFile(entry.id)
            AuraSingExportStem.INSTRUMENTAL -> cache.instrumentalFile(entry.id)
        }

    private fun writeWavStream(pcmFile: File, entry: AuraSingCacheEntry, out: OutputStream) {
        val dataBytes = pcmFile.length()
        val header = buildWavHeader(
            pcmDataBytes = dataBytes,
            sampleRate = entry.sampleRate.coerceAtLeast(8000),
            channels = entry.channels.coerceIn(1, 2),
            bitsPerSample = 16,
        )
        out.write(header)
        pcmFile.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
    }
}
