// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.lyrics

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import btm.m.auraplayer.data.Song
import btm.m.auraplayer.playback.LyricTranslationSource
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.File

data class ResolvedLyrics(
    val primaryLines: List<LyricLine>,
    val llmLines: List<LyricLine>? = null,
    val networkLines: List<LyricLine>? = null,
) {
    val hasDualTranslationSources: Boolean
        get() = llmLines != null &&
            networkLines != null &&
            llmLines.any { !it.translation.isNullOrBlank() } &&
            networkLines.any { !it.translation.isNullOrBlank() }

    fun select(preferred: LyricTranslationSource): List<LyricLine> = when {
        hasDualTranslationSources && preferred == LyricTranslationSource.NETWORK -> networkLines!!
        llmLines != null -> llmLines
        networkLines != null -> networkLines
        else -> primaryLines
    }
}

/** Aura's lifecycle adapter around HyperChanger/HyperMusicCover's original providers and parser. */
class LyricsRepository(
    private val context: Context,
    private val llmConfigProvider: () -> LlmTranslationConfig = { LlmTranslationConfig() },
    private val llmApiKeyProvider: () -> String = { "" },
) {
    private val cache = object : LinkedHashMap<String, ResolvedLyrics>(24, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedLyrics>) = size > 24
    }
    private val diskCacheDir by lazy { context.cacheDir.resolve("cloud-lyrics") }
    private data class Answer(val rank: Int, val lines: List<LyricLine>)
    fun invalidate(songKey: String) {
        cache.remove(songKey)
        runCatching { diskCacheFile(songKey).delete() }
    }
    fun clear() {
        cache.clear()
        runCatching { diskCacheDir.listFiles().orEmpty().filter(File::isFile).forEach { it.delete() } }
    }

    fun lyrics(song: Song): Flow<List<LyricLine>> = resolveLyrics(song).map { it.select(LyricTranslationSource.LLM) }

    fun resolveLyrics(song: Song): Flow<ResolvedLyrics> = flow {
        cache[song.key]?.let { emit(it); return@flow }
        var own = withContext(Dispatchers.IO) {
            if (song.cloud) readDiskLyrics(song.key) else local(song)
        }
        val fromCloudDiskCache = song.cloud && own.isNotEmpty()
        var llmVersion: List<LyricLine>? = null
        var networkVersion: List<LyricLine>? = own.takeIf { lines -> lines.any { !it.translation.isNullOrBlank() } }

        suspend fun publish(persistCloud: Boolean = false) {
            val resolved = ResolvedLyrics(
                primaryLines = own,
                llmLines = llmVersion,
                networkLines = networkVersion ?: own.takeIf { lines -> lines.any { !it.translation.isNullOrBlank() } },
            )
            if (own.isNotEmpty()) {
                cache[song.key] = resolved
                if (persistCloud && song.cloud) {
                    withContext(Dispatchers.IO) { writeDiskLyrics(song.key, own) }
                }
            }
            emit(resolved)
        }
        if (own.isNotEmpty()) publish()
        if (fromCloudDiskCache) {
            val llmConfig = llmConfigProvider()
            val llmApiKey = llmApiKeyProvider()
            if (llmConfig.enabled && !LlmLyricLanguageSupport.isSameLanguage(llmConfig, own)) {
                val translated = LlmTranslator.translate(
                    songKey = song.key,
                    songTitle = song.title,
                    artist = song.artist,
                    albumTitle = song.album,
                    lines = own,
                    config = llmConfig,
                    apiKey = llmApiKey,
                    cacheRoot = context.cacheDir,
                )
                if (translated != null) {
                    llmVersion = translated
                    publish()
                }
            }
            return@flow
        }
        // Same race and 400ms database grace as LyricSource.race; rich ID sources do not
        // delay the catalogue fallback. The original five-provider matching stays intact.
        supervisorScope {
            val answers = Channel<Answer>(Channel.UNLIMITED)
            val jobs = mutableListOf<Job>()
            fun request(rank: Int, block: () -> List<LyricLine>) {
                jobs += launch(Dispatchers.IO) {
                    val rows = try { runInterruptible { block() } } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { emptyList() }
                    answers.trySend(Answer(rank, rows))
                }
            }
            val neteaseSong = song.cloud && song.provider != "qq"
            val pkg = if (neteaseSong) "com.netease.cloudmusic" else "btm.m.auraplayer"
            val query = NcmLyrics.build(song.title, song.artist, song.album, song.duration)
            val order = OnlineLyrics.order(pkg)
            if (neteaseSong) {
                request(1) { NcmLyrics.lyricsOf(song.id.toString(), android.os.SystemClock.uptimeMillis())?.let {
                    LyricParse.parse(it.body, it.translation, it.roma)
                }.orEmpty() }
                listOf(
                    "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/ncm-lyrics/${song.id}.ttml",
                    "https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/ncm-lyrics/${song.id}.ttml",
                ).forEach { url -> request(0) { Http.get(url, "AuraTtml").body?.let(LyricParse::parse).orEmpty() } }
                request(0) { TtmlHub.lookup(context, "ncm-lyrics", song.id.toString())?.let(LyricParse::parse).orEmpty() }
                request(2) { OnlineLyrics.ask(OnlineLyrics.Src.QQ, query)?.let { LyricParse.parse(it.body, it.translation, it.roma) }.orEmpty() }
            } else {
                order.take(2).forEachIndexed { index, source -> request(index + 1) {
                    OnlineLyrics.ask(source, query)?.let { LyricParse.parse(it.body, it.translation, it.roma) }.orEmpty()
                } }
            }
            var rank = if (own.isEmpty()) Int.MAX_VALUE else -1
            var grace: Answer? = null
            val overallDeadline = android.os.SystemClock.uptimeMillis() + 8000L
            var deadline = overallDeadline
            var pending = jobs.size
            try {
                while (pending > 0) {
                    val left = deadline - android.os.SystemClock.uptimeMillis()
                    if (left <= 0) break
                    val answer = withTimeoutOrNull(left) { answers.receive() }
                    if (answer == null) {
                        val ready = grace ?: break
                        val rows = LyricParse.splitInlineTranslations(ready.lines)
                        own = if (own.isEmpty()) rows else LyricParse.borrow(own, rows, true, true).lines
                        rank = minOf(rank, ready.rank); grace = null
                        networkVersion = own.takeIf { lines -> lines.any { !it.translation.isNullOrBlank() } }
                        publish()
                        deadline = overallDeadline
                        continue
                    }
                    pending--
                    if (answer.lines.isEmpty()) continue
                    if (own.isEmpty() && song.cloud && answer.rank > 0 && pending > 0) {
                        if (grace == null || answer.rank < grace.rank) grace = answer
                        deadline = minOf(deadline, android.os.SystemClock.uptimeMillis() + 400L)
                        continue
                    }
                    val rows = LyricParse.splitInlineTranslations(answer.lines)
                    own = if (answer.rank < rank) {
                        val merged = LyricParse.borrow(rows, own, true, true).lines
                        rank = answer.rank
                        merged
                    } else LyricParse.borrow(own, rows, true, true).lines
                    networkVersion = own.takeIf { lines -> lines.any { !it.translation.isNullOrBlank() } }
                    publish()
                }
                grace?.let { answer ->
                    val rows = LyricParse.splitInlineTranslations(answer.lines)
                    own = if (own.isEmpty()) rows else LyricParse.borrow(own, rows, true, true).lines
                    networkVersion = own.takeIf { lines -> lines.any { !it.translation.isNullOrBlank() } }
                    publish()
                }
                val missedPrimary = own.isEmpty()
                if (missedPrimary) {
                    // Same lower-priority sources, sequentially, only after the fast pair misses.
                    for (source in order.drop(2)) {
                        currentCoroutineContext().ensureActive()
                        val found = withContext(Dispatchers.IO) { runInterruptible { OnlineLyrics.ask(source, query) } } ?: continue
                        own = LyricParse.splitInlineTranslations(LyricParse.parse(found.body, found.translation, found.roma))
                        if (own.isNotEmpty()) {
                            networkVersion = own.takeIf { lines -> lines.any { !it.translation.isNullOrBlank() } }
                            publish()
                            break
                        }
                    }
                }
                if (own.isNotEmpty()) {
                    if (!missedPrimary) {
                        for (source in order.drop(2)) {
                            val translations = own.none { !it.translation.isNullOrBlank() } && LyricParse.foreign(own)
                            val romas = own.none { !it.roma.isNullOrBlank() } && LyricParse.romanisable(own)
                            if (!translations && !romas) break
                            currentCoroutineContext().ensureActive()
                            val found = withContext(Dispatchers.IO) { runInterruptible { OnlineLyrics.ask(source, query) } } ?: continue
                            val other = LyricParse.parse(found.body, found.translation, found.roma)
                            val loan = LyricParse.borrow(own, other, translations, romas)
                            if (loan.translations > 0 || loan.romas > 0) {
                                own = loan.lines
                                if (own.any { !it.translation.isNullOrBlank() }) {
                                    networkVersion = own
                                }
                                publish()
                            }
                        }
                    }
                    val llmConfig = llmConfigProvider()
                    val llmApiKey = llmApiKeyProvider()
                    val canRunLlm = llmConfig.enabled &&
                        !LlmLyricLanguageSupport.isSameLanguage(llmConfig, own)
                    if (canRunLlm) {
                        val translated = LlmTranslator.translate(
                            songKey = song.key,
                            songTitle = song.title,
                            artist = song.artist,
                            albumTitle = song.album,
                            lines = own,
                            config = llmConfig,
                            apiKey = llmApiKey,
                            cacheRoot = context.cacheDir,
                        )
                        if (translated != null) {
                            llmVersion = translated
                            publish()
                        }
                    }
                }
            } finally { jobs.forEach { it.cancel() }; answers.close() }
        }
        publish(persistCloud = own.isNotEmpty())
    }

    private fun diskCacheFile(songKey: String): File {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(songKey.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(diskCacheDir, "$digest.json")
    }

    private fun encodeLine(line: LyricLine): org.json.JSONObject = org.json.JSONObject().apply {
        put("t", line.text)
        line.translation?.let { put("tr", it) }
        line.roma?.let { put("r", it) }
        put("s", line.start)
        put("e", line.end)
        if (line.opposite) put("o", true)
        line.sylStart?.let { arr -> put("ss", org.json.JSONArray().apply { arr.forEach(::put) }) }
        line.sylEnd?.let { arr -> put("se", org.json.JSONArray().apply { arr.forEach(::put) }) }
        line.charEnd?.let { arr -> put("ce", org.json.JSONArray().apply { arr.forEach(::put) }) }
        line.bg?.let { put("bg", encodeLine(it)) }
    }

    private fun decodeLine(obj: org.json.JSONObject): LyricLine {
        fun intArray(key: String): IntArray? {
            val arr = obj.optJSONArray(key) ?: return null
            return IntArray(arr.length()) { arr.getInt(it) }
        }
        return LyricLine(
            obj.getString("t"),
            if (obj.has("tr")) obj.optString("tr").takeIf { it.isNotEmpty() } else null,
            if (obj.has("r")) obj.optString("r").takeIf { it.isNotEmpty() } else null,
            obj.getInt("s"),
            obj.getInt("e"),
            obj.optBoolean("o", false),
            intArray("ss"),
            intArray("se"),
            intArray("ce"),
        ).apply {
            obj.optJSONObject("bg")?.let { bg = decodeLine(it) }
        }
    }

    private fun readDiskLyrics(songKey: String): List<LyricLine> = runCatching {
        val file = diskCacheFile(songKey)
        if (!file.isFile) return@runCatching emptyList()
        val obj = org.json.JSONObject(file.readText())
        val age = System.currentTimeMillis() - obj.optLong("savedAt", 0L)
        if (age < 0L || age > 14L * 24 * 60 * 60 * 1000) {
            file.delete()
            return@runCatching emptyList()
        }
        val arr = obj.getJSONArray("lines")
        List(arr.length()) { decodeLine(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun writeDiskLyrics(songKey: String, lines: List<LyricLine>) {
        if (lines.isEmpty()) return
        runCatching {
            diskCacheDir.mkdirs()
            val target = diskCacheFile(songKey)
            val tmp = File(diskCacheDir, "${target.name}.tmp")
            val arr = org.json.JSONArray().apply { lines.forEach { put(encodeLine(it)) } }
            tmp.writeText(org.json.JSONObject().put("savedAt", System.currentTimeMillis()).put("lines", arr).toString())
            java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            val files = diskCacheDir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.lastModified() }
            var totalBytes = files.sumOf { it.length() }
            val maxBytes = 8L * 1024 * 1024
            for (old in files) {
                if (totalBytes <= maxBytes) break
                val len = old.length()
                if (old.delete()) totalBytes -= len
            }
        }
    }

    private fun local(song: Song): List<LyricLine> {
        if (song.cloud) return emptyList()
        val embedded = runCatching { btm.m.auraplayer.tags.LocalAudioTags.embeddedLyrics(context, song) }.getOrDefault("")
        if (embedded.isNotBlank()) {
            val parsed = LyricParse.splitInlineTranslations(LyricParse.parse(embedded))
            if (parsed.isNotEmpty()) return parsed
        }
        return runCatching {
            val uri = Uri.parse(song.uri)
            val resolver = context.contentResolver
            val columns = arrayOf(MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATA)
            resolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use emptyList<LyricLine>()
                val path = cursor.getString(1).orEmpty()
                for (extension in listOf("ttml", "yrc", "lrc")) {
                    val file = File(path.substringBeforeLast('.') + ".$extension")
                    // Scoped storage may not expose sidecars; then continue with the catalogue.
                    if (file.isFile && file.canRead() && file.length() <= 1024 * 1024) {
                        val parsed = LyricParse.splitInlineTranslations(LyricParse.parse(file.readText()))
                        if (parsed.isNotEmpty()) return@use parsed
                    }
                }
                emptyList()
            }.orEmpty()
        }.getOrDefault(emptyList())
    }
}
