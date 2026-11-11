// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.lyrics

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import btm.m.auraplayer.data.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/** Aura's lifecycle adapter around HyperChanger/HyperMusicCover's original providers and parser. */
class LyricsRepository(private val context: Context) {
    private val cache = object : LinkedHashMap<String, List<LyricLine>>(24, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LyricLine>>) = size > 24
    }
    private data class Answer(val rank: Int, val lines: List<LyricLine>)

    fun lyrics(song: Song): Flow<List<LyricLine>> = flow {
        cache[song.key]?.let { emit(it); return@flow }
        var own = withContext(Dispatchers.IO) { local(song) }
        suspend fun publish() {
            if (own.isNotEmpty()) cache[song.key] = own
            emit(own)
        }
        if (own.isNotEmpty()) publish()
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
            val pkg = if (song.cloud) "com.netease.cloudmusic" else "btm.m.auraplayer"
            val query = NcmLyrics.build(song.title, song.artist, song.album, song.duration)
            val order = OnlineLyrics.order(pkg)
            if (song.cloud) {
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
                    publish()
                }
                grace?.let { answer ->
                    val rows = LyricParse.splitInlineTranslations(answer.lines)
                    own = if (own.isEmpty()) rows else LyricParse.borrow(own, rows, true, true).lines
                    publish()
                }
                if (own.isEmpty()) {
                    // Same lower-priority sources, sequentially, only after the fast pair misses.
                    for (source in order.drop(2)) {
                        currentCoroutineContext().ensureActive()
                        val found = withContext(Dispatchers.IO) { runInterruptible { OnlineLyrics.ask(source, query) } } ?: continue
                        own = LyricParse.splitInlineTranslations(LyricParse.parse(found.body, found.translation, found.roma))
                        if (own.isNotEmpty()) { publish(); break }
                    }
                } else {
                    // Keep the preferred timing and borrow only missing translated/phonetic lanes,
                    // exactly as LyricSource.borrow does after its fast first answer.
                    for (source in order.drop(2)) {
                        val translations = own.none { !it.translation.isNullOrBlank() } && LyricParse.foreign(own)
                        val romas = own.none { !it.roma.isNullOrBlank() } && LyricParse.romanisable(own)
                        if (!translations && !romas) break
                        currentCoroutineContext().ensureActive()
                        val found = withContext(Dispatchers.IO) { runInterruptible { OnlineLyrics.ask(source, query) } } ?: continue
                        val other = LyricParse.parse(found.body, found.translation, found.roma)
                        val loan = LyricParse.borrow(own, other, translations, romas)
                        if (loan.translations > 0 || loan.romas > 0) { own = loan.lines; publish() }
                    }
                }
            } finally { jobs.forEach { it.cancel() }; answers.close() }
        }
        publish()
    }

    private fun local(song: Song): List<LyricLine> {
        if (song.cloud) return emptyList()
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
