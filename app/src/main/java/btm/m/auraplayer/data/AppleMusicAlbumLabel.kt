// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.data

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Resolves accountless, tokenless Apple Music album copyright / record label strings
 * (e.g. "℗ 2026 Sony Music Label Inc." or "A Polydor Records release; ℗ 2026 UNIVERSAL MUSIC LLC")
 * from public Apple Music CN/US storefront pages and the public iTunes Search/Lookup API.
 */
object AppleMusicAlbumLabelManager {
    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    private val STOREFRONTS = listOf("cn", "us")
    private const val MAX_CANDIDATES = 3
    private const val DURATION_SLACK_MS = 3000L
    private const val NONE_TTL_MS = 7L * 24 * 3600 * 1000
    private const val FAIL_TTL_MS = 30L * 60 * 1000
    private const val MAX_INDEX_ENTRIES = 2000
    private const val DIR_NAME = "am_album_label_v1"
    private const val INDEX_NAME = "index.jsonl"

    private val ALBUM_ID_URL_REGEX = Regex("/album/(?:[^/?]+/)?(\\d+)")

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    internal data class AlbumCandidate(
        val albumId: String,
        val title: String,
        val artist: String,
        val trackCount: Int = 0,
    )

    internal data class AlbumPageMeta(
        val title: String,
        val artist: String,
        val trackCount: Int = 0,
    )

    internal data class ItunesAlbumResult(
        val collectionId: String,
        val title: String,
        val artist: String,
        val trackCount: Int,
        val copyright: String,
    )

    internal data class ItunesSongResult(
        val collectionId: String,
        val trackName: String,
        val artistName: String,
        val collectionName: String,
        val durationMs: Long,
    )

    private data class CachedEntry(
        val status: String,
        val label: String?,
        val timestampMs: Long,
    )

    private val indexMutex = Mutex()
    private val indexCache = ConcurrentHashMap<String, CachedEntry>()
    @Volatile
    private var indexLoaded = false

    private val inflightMutex = Mutex()
    private val inflightLabels = HashMap<String, CompletableDeferred<String?>>()

    fun peekCachedRecordLabel(
        context: Context,
        albumTitle: String,
        artistName: String,
    ): String? {
        val key = albumLabelCacheKey(albumTitle, artistName) ?: return null
        val appContext = context.applicationContext
        ensureIndexLoadedSync(appContext)
        val entry = indexCache[key] ?: return null
        return if (entry.status == "OK") entry.label?.takeIf(String::isNotBlank) else null
    }

    suspend fun resolveAlbumRecordLabel(
        context: Context,
        albumTitle: String,
        artistName: String,
        tracks: List<Song> = emptyList(),
    ): String? {
        val key = albumLabelCacheKey(albumTitle, artistName) ?: return null
        val appContext = context.applicationContext
        ensureIndexLoaded(appContext)
        indexCache[key]?.let { entry ->
            val now = System.currentTimeMillis()
            when (entry.status) {
                "OK" -> return entry.label?.takeIf(String::isNotBlank)
                "NONE" -> if (now - entry.timestampMs < NONE_TTL_MS) return null
                "FAIL" -> if (now - entry.timestampMs < FAIL_TTL_MS) return null
            }
        }

        val (deferred, owner) = inflightMutex.withLock {
            val existing = inflightLabels[key]
            if (existing != null && existing.isActive) {
                existing to false
            } else {
                CompletableDeferred<String?>().also { inflightLabels[key] = it } to true
            }
        }
        if (!owner) return deferred.await()

        return try {
            val resolved = withContext(Dispatchers.IO) {
                fetchAndCacheLabel(appContext, key, albumTitle.trim(), artistName.trim(), tracks)
            }
            deferred.complete(resolved)
            resolved
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            if (t is kotlinx.coroutines.CancellationException) throw t
            null
        } finally {
            inflightMutex.withLock {
                if (inflightLabels[key] === deferred) inflightLabels.remove(key)
            }
        }
    }

    private suspend fun fetchAndCacheLabel(
        context: Context,
        key: String,
        albumTitle: String,
        artistName: String,
        tracks: List<Song>,
    ): String? {
        var sawRetryableFailure = false
        val firstTrack = tracks.firstOrNull()
        val firstTrackTitle = firstTrack?.title?.trim().orEmpty()
        val firstTrackDurationMs = firstTrack?.duration ?: 0L
        val expectedTrackCount = tracks.size

        val webFetcher: suspend (String, String) -> String? = { storefront, url ->
            try {
                getStorefrontHtml(url, storefront)
            } catch (_: IOException) {
                sawRetryableFailure = true
                null
            }
        }
        val itunesFetcher: suspend (String) -> String? = { url ->
            try {
                getItunesJson(url)
            } catch (_: IOException) {
                sawRetryableFailure = true
                null
            }
        }

        // 1. CN web storefront (works globally & in CN, handles Simplified Chinese queries directly).
        val cnLabel = findLabelFromWebStorefront(
            storefront = "cn",
            albumTitle = albumTitle,
            artistName = artistName,
            expectedTrackCount = expectedTrackCount,
            firstTrackTitle = firstTrackTitle,
            firstTrackDurationMs = firstTrackDurationMs,
            fetcher = { url -> webFetcher("cn", url) },
        )
        if (!cnLabel.isNullOrBlank()) {
            putIndexEntry(context, key, "OK", cnLabel)
            return cnLabel
        }

        // 2. US storefront via iTunes Search/Lookup API (does not geo-redirect on CN networks).
        val usItunesLabel = findLabelFromItunesUs(
            albumTitle = albumTitle,
            artistName = artistName,
            expectedTrackCount = expectedTrackCount,
            firstTrackTitle = firstTrackTitle,
            firstTrackDurationMs = firstTrackDurationMs,
            fetcher = itunesFetcher,
        )
        if (!usItunesLabel.isNullOrBlank()) {
            putIndexEntry(context, key, "OK", usItunesLabel)
            return usItunesLabel
        }

        // 3. US web storefront fallback (effective when not geo-redirected to /cn/).
        val usWebLabel = findLabelFromWebStorefront(
            storefront = "us",
            albumTitle = albumTitle,
            artistName = artistName,
            expectedTrackCount = expectedTrackCount,
            firstTrackTitle = firstTrackTitle,
            firstTrackDurationMs = firstTrackDurationMs,
            fetcher = { url -> webFetcher("us", url) },
        )
        if (!usWebLabel.isNullOrBlank()) {
            putIndexEntry(context, key, "OK", usWebLabel)
            return usWebLabel
        }

        putIndexEntry(context, key, if (sawRetryableFailure) "FAIL" else "NONE", null)
        return null
    }

    internal suspend fun findLabelFromWebStorefront(
        storefront: String,
        albumTitle: String,
        artistName: String,
        expectedTrackCount: Int = 0,
        firstTrackTitle: String = "",
        firstTrackDurationMs: Long = 0L,
        fetcher: suspend (String) -> String?,
    ): String? {
        val searchArtist = if (isVariousArtists(artistName)) "" else AppleMusicMotionCoverManager.firstArtist(artistName)
        val albumTerm = "$albumTitle $searchArtist".trim()
        if (albumTerm.isBlank()) return null

        currentCoroutineContext().ensureActive()
        val searchHtml = fetcher("https://music.apple.com/$storefront/search?term=${encodeParam(albumTerm)}")
        val searchSections = searchHtml?.let { AppleMusicMotionCoverManager.extractSectionsJson(it) }
        val albumCandidates = if (searchSections != null) {
            parseAlbumCandidates(searchSections, albumTitle, artistName, expectedTrackCount)
        } else {
            emptyList()
        }

        if (albumCandidates.isNotEmpty()) {
            val pages = coroutineScope {
                albumCandidates.map { cand ->
                    async { fetcher("https://music.apple.com/$storefront/album/${cand.albumId}") }
                }.awaitAll()
            }
            for (k in albumCandidates.indices) {
                val html = pages[k] ?: continue
                val sections = AppleMusicMotionCoverManager.extractSectionsJson(html) ?: continue
                val meta = extractAlbumPageMeta(sections)
                if (meta != null && !isAcceptableAlbumMatch(
                        targetAlbum = albumTitle,
                        targetArtist = artistName,
                        candidateAlbum = meta.title,
                        candidateArtist = meta.artist,
                    )
                ) {
                    continue
                }
                val label = extractTracklistFooterCopyright(sections)
                if (!label.isNullOrBlank()) return label
            }
        }

        if (firstTrackTitle.isNotBlank()) {
            val cleanTrack = AppleMusicMotionCoverManager.stripFeat(firstTrackTitle).trim()
            val songCandidatesFromAlbumSearch = searchSections?.let {
                AppleMusicMotionCoverManager.parseSongCandidates(it, firstTrackTitle, artistName)
            }.orEmpty()
            val songCandidates = if (songCandidatesFromAlbumSearch.isNotEmpty()) {
                songCandidatesFromAlbumSearch
            } else if (cleanTrack.isNotBlank()) {
                val songTerm = "$cleanTrack $searchArtist".trim()
                val songHtml = fetcher("https://music.apple.com/$storefront/search?term=${encodeParam(songTerm)}")
                val songSections = songHtml?.let { AppleMusicMotionCoverManager.extractSectionsJson(it) }
                songSections?.let {
                    AppleMusicMotionCoverManager.parseSongCandidates(it, firstTrackTitle, artistName)
                }.orEmpty()
            } else {
                emptyList()
            }.take(MAX_CANDIDATES)

            if (songCandidates.isNotEmpty()) {
                val pages = coroutineScope {
                    songCandidates.map { cand ->
                        async { fetcher("https://music.apple.com/$storefront/album/${cand.albumId}") }
                    }.awaitAll()
                }
                for (k in songCandidates.indices) {
                    val cand = songCandidates[k]
                    val html = pages[k] ?: continue
                    val sections = AppleMusicMotionCoverManager.extractSectionsJson(html) ?: continue
                    val storeMs = AppleMusicMotionCoverManager.extractTrackDurationMs(sections, cand.songId)
                    if (firstTrackDurationMs > 0L && storeMs > 0L && abs(storeMs - firstTrackDurationMs) > DURATION_SLACK_MS) {
                        continue
                    }
                    val meta = extractAlbumPageMeta(sections) ?: continue
                    val albumMatches = isAcceptableAlbumMatch(
                        targetAlbum = albumTitle,
                        targetArtist = artistName,
                        candidateAlbum = meta.title,
                        candidateArtist = meta.artist,
                    )
                    val trackCountMatches = expectedTrackCount > 0 && meta.trackCount > 0 &&
                        (expectedTrackCount <= 1 || abs(meta.trackCount - expectedTrackCount) <= 1)
                    if (!albumMatches && !trackCountMatches) continue
                    val label = extractTracklistFooterCopyright(sections)
                    if (!label.isNullOrBlank()) return label
                }
            }
        }

        return null
    }

    internal suspend fun findLabelFromItunesUs(
        albumTitle: String,
        artistName: String,
        expectedTrackCount: Int = 0,
        firstTrackTitle: String = "",
        firstTrackDurationMs: Long = 0L,
        fetcher: suspend (String) -> String?,
    ): String? {
        val searchArtist = if (isVariousArtists(artistName)) "" else AppleMusicMotionCoverManager.firstArtist(artistName)
        val term = "$albumTitle $searchArtist".trim()
        if (term.isBlank()) return null

        currentCoroutineContext().ensureActive()
        val albumJson = fetcher(
            "https://itunes.apple.com/search?term=${encodeParam(term)}&entity=album&country=us&limit=10",
        )
        if (!albumJson.isNullOrBlank()) {
            val candidates = parseItunesAlbumCandidates(albumJson, albumTitle, artistName, expectedTrackCount)
            val bestWithCopyright = candidates.firstOrNull { it.copyright.isNotBlank() }
            if (bestWithCopyright != null) return bestWithCopyright.copyright
        }

        if (firstTrackTitle.isNotBlank()) {
            val songTerm = "${AppleMusicMotionCoverManager.stripFeat(firstTrackTitle)} $searchArtist".trim()
            if (songTerm.isNotBlank()) {
                val songJson = fetcher(
                    "https://itunes.apple.com/search?term=${encodeParam(songTerm)}&entity=song&country=us&limit=10",
                )
                val songMatch = songJson?.let {
                    parseItunesSongCandidates(it, firstTrackTitle, artistName, albumTitle, firstTrackDurationMs)
                }?.firstOrNull()
                if (songMatch != null) {
                    val lookupJson = fetcher(
                        "https://itunes.apple.com/lookup?id=${songMatch.collectionId}&entity=album&country=us",
                    )
                    if (!lookupJson.isNullOrBlank()) {
                        val results = runCatching { JSONObject(lookupJson).optJSONArray("results") }.getOrNull()
                        if (results != null) {
                            for (i in 0 until results.length()) {
                                val item = results.optJSONObject(i) ?: continue
                                if (item.optString("wrapperType") != "collection") continue
                                val trackCount = item.optInt("trackCount", 0)
                                val title = item.optString("collectionName", "")
                                val artist = item.optString("artistName", "")
                                val albumMatches = isAcceptableAlbumMatch(albumTitle, artistName, title, artist)
                                val countMatches = expectedTrackCount > 0 && trackCount > 0 &&
                                    (expectedTrackCount <= 1 || abs(trackCount - expectedTrackCount) <= 1)
                                if (!albumMatches && !countMatches) continue
                                val copyright = item.optString("copyright", "").trim()
                                if (copyright.isNotBlank()) return copyright
                            }
                        }
                    }
                }
            }
        }

        return null
    }

    internal fun albumLabelCacheKey(albumTitle: String, artistName: String): String? {
        val normAlbum = AppleMusicMotionCoverManager.norm(albumTitle)
        if (normAlbum.isEmpty()) return null
        val normArtist = AppleMusicMotionCoverManager.norm(
            AppleMusicMotionCoverManager.firstArtist(artistName).ifBlank { artistName },
        )
        if (normArtist.isEmpty()) return null
        return "a:$normArtist|$normAlbum"
    }

    internal fun parseAlbumCandidates(
        sections: JSONArray,
        albumTitle: String,
        artistName: String,
        expectedTrackCount: Int = 0,
    ): List<AlbumCandidate> {
        data class ScoredCandidate(
            val candidate: AlbumCandidate,
            val score: Int,
            val order: Int,
        )

        val wantRaw = AppleMusicMotionCoverManager.norm(albumTitle)
        val wantStripped = AppleMusicMotionCoverManager.norm(AppleMusicMotionCoverManager.stripEdition(albumTitle))
        if (wantRaw.isEmpty() && wantStripped.isEmpty()) return emptyList()

        val byId = HashMap<String, ScoredCandidate>()
        var orderCounter = 0

        for (i in 0 until sections.length()) {
            val sec = sections.optJSONObject(i) ?: continue
            val items = sec.optJSONArray("items") ?: continue
            for (k in 0 until items.length()) {
                val item = items.optJSONObject(k) ?: continue
                val cd = item.optJSONObject("contentDescriptor") ?: continue
                if (cd.optString("kind") != "album") continue
                val albumId = extractAlbumId(cd) ?: continue
                val title = extractItemTitle(item)
                val artist = subtitleArtist(item)
                val trackCount = item.optInt("trackCount", 0)
                val score = scoreAlbumMatch(
                    wantRaw = wantRaw,
                    wantStripped = wantStripped,
                    wantArtist = artistName,
                    candTitle = title,
                    candArtist = artist,
                    candTrackCount = trackCount,
                    expectedTrackCount = expectedTrackCount,
                )
                if (score <= 0) continue

                val order = orderCounter++
                val existing = byId[albumId]
                if (existing == null || score > existing.score) {
                    byId[albumId] = ScoredCandidate(
                        candidate = AlbumCandidate(albumId, title, artist, trackCount),
                        score = score,
                        order = existing?.order ?: order,
                    )
                }
            }
        }

        return byId.values
            .sortedWith(compareByDescending<ScoredCandidate> { it.score }.thenBy { it.order })
            .take(MAX_CANDIDATES)
            .map { it.candidate }
    }

    internal fun extractAlbumPageMeta(sections: JSONArray): AlbumPageMeta? {
        for (i in 0 until sections.length()) {
            val sec = sections.optJSONObject(i) ?: continue
            if (!sec.optString("id").startsWith("album-detail-header")) continue
            val header = sec.optJSONArray("items")?.optJSONObject(0) ?: return null
            val title = header.optString("title", "").trim()
            val artist = subtitleArtist(header)
            val trackCount = header.optInt("trackCount", 0)
            return AlbumPageMeta(title = title, artist = artist, trackCount = trackCount)
        }
        return null
    }

    internal fun extractTracklistFooterCopyright(sections: JSONArray): String? {
        for (i in 0 until sections.length()) {
            val sec = sections.optJSONObject(i) ?: continue
            val secId = sec.optString("id", "")
            val itemKind = sec.optString("itemKind", "")
            val isFooterSection = itemKind == "containerDetailTracklistFooterLockup" ||
                secId.startsWith("track-list-section")
            if (!isFooterSection) continue
            val items = sec.optJSONArray("items") ?: continue
            for (k in 0 until items.length()) {
                val item = items.optJSONObject(k) ?: continue
                val itemId = item.optString("id", "")
                if (itemKind != "containerDetailTracklistFooterLockup" &&
                    !itemId.startsWith("tracklist-footer")
                ) {
                    continue
                }
                val fromDesc = extractCopyrightFromFooterDescription(item.optString("description", ""))
                if (!fromDesc.isNullOrBlank()) return fromDesc
                val fromLink = item.optJSONObject("linkSection")
                    ?.optJSONObject("link")
                    ?.optString("title", "")
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                if (fromLink != null) return fromLink
            }
        }
        return null
    }

    internal fun extractCopyrightFromFooterDescription(description: String): String? {
        val lines = description.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val markedStart = lines.indexOfFirst { line ->
            line.contains('℗') || line.contains('©') ||
                line.contains("(P)", ignoreCase = true) ||
                line.contains("(C)", ignoreCase = true)
        }
        if (markedStart >= 0) {
            return lines.subList(markedStart, lines.size).joinToString("\n").ifBlank { null }
        }
        if (lines.size >= 3) {
            return lines.drop(2).joinToString("\n").ifBlank { null }
        }
        return null
    }

    internal fun parseItunesAlbumCandidates(
        jsonBody: String,
        albumTitle: String,
        artistName: String,
        expectedTrackCount: Int = 0,
    ): List<ItunesAlbumResult> {
        val results = runCatching { JSONObject(jsonBody).optJSONArray("results") }.getOrNull() ?: return emptyList()
        val wantRaw = AppleMusicMotionCoverManager.norm(albumTitle)
        val wantStripped = AppleMusicMotionCoverManager.norm(AppleMusicMotionCoverManager.stripEdition(albumTitle))
        if (wantRaw.isEmpty() && wantStripped.isEmpty()) return emptyList()

        data class Scored(val result: ItunesAlbumResult, val score: Int, val order: Int)
        val ranked = mutableListOf<Scored>()

        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val collectionId = item.optLong("collectionId", 0L).takeIf { it > 0L }?.toString() ?: continue
            val title = item.optString("collectionName", "").trim()
            val artist = item.optString("artistName", "").trim()
            val trackCount = item.optInt("trackCount", 0)
            val copyright = item.optString("copyright", "").trim()
            val score = scoreAlbumMatch(
                wantRaw = wantRaw,
                wantStripped = wantStripped,
                wantArtist = artistName,
                candTitle = title,
                candArtist = artist,
                candTrackCount = trackCount,
                expectedTrackCount = expectedTrackCount,
            )
            if (score <= 0) continue
            ranked += Scored(
                result = ItunesAlbumResult(collectionId, title, artist, trackCount, copyright),
                score = score,
                order = i,
            )
        }

        return ranked
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.order })
            .map { it.result }
    }

    internal fun parseItunesSongCandidates(
        jsonBody: String,
        trackTitle: String,
        artistName: String,
        albumTitle: String,
        durationMs: Long = 0L,
    ): List<ItunesSongResult> {
        val results = runCatching { JSONObject(jsonBody).optJSONArray("results") }.getOrNull() ?: return emptyList()
        val wantTitle = AppleMusicMotionCoverManager.norm(AppleMusicMotionCoverManager.stripFeat(trackTitle))
        val wantAlbum = AppleMusicMotionCoverManager.norm(AppleMusicMotionCoverManager.stripEdition(albumTitle))
        if (wantTitle.isEmpty()) return emptyList()

        data class Scored(val result: ItunesSongResult, val score: Int, val order: Int)
        val ranked = mutableListOf<Scored>()

        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val collectionId = item.optLong("collectionId", 0L).takeIf { it > 0L }?.toString() ?: continue
            val trackName = item.optString("trackName", "").trim()
            val artist = item.optString("artistName", "").trim()
            val collectionName = item.optString("collectionName", "").trim()
            val storeMs = item.optLong("trackTimeMillis", 0L)

            val candTitle = AppleMusicMotionCoverManager.norm(AppleMusicMotionCoverManager.stripFeat(trackName))
            if (candTitle != wantTitle) continue
            if (!artistMatches(artistName, artist)) continue
            if (durationMs > 0L && storeMs > 0L && abs(storeMs - durationMs) > DURATION_SLACK_MS) continue

            val candAlbum = AppleMusicMotionCoverManager.norm(AppleMusicMotionCoverManager.stripEdition(collectionName))
            val albumScore = when {
                wantAlbum.isNotEmpty() && candAlbum == wantAlbum -> 20
                wantAlbum.isNotEmpty() && (candAlbum.contains(wantAlbum) || wantAlbum.contains(candAlbum)) -> 10
                else -> 1
            }
            ranked += Scored(
                result = ItunesSongResult(collectionId, trackName, artist, collectionName, storeMs),
                score = albumScore,
                order = i,
            )
        }

        return ranked
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.order })
            .map { it.result }
    }

    private fun scoreAlbumMatch(
        wantRaw: String,
        wantStripped: String,
        wantArtist: String,
        candTitle: String,
        candArtist: String,
        candTrackCount: Int,
        expectedTrackCount: Int,
    ): Int {
        if (!artistMatches(wantArtist, candArtist)) return 0
        val candRaw = AppleMusicMotionCoverManager.norm(candTitle)
        val candStripped = AppleMusicMotionCoverManager.norm(AppleMusicMotionCoverManager.stripEdition(candTitle))
        if (candRaw.isEmpty() && candStripped.isEmpty()) return 0

        val baseScore = when {
            wantRaw.isNotEmpty() && candRaw == wantRaw -> 40
            wantStripped.isNotEmpty() && candStripped == wantStripped -> 30
            wantStripped.isNotEmpty() && candStripped.isNotEmpty() &&
                minOf(wantStripped.length, candStripped.length) >= 2 &&
                (candStripped.contains(wantStripped) || wantStripped.contains(candStripped)) -> 15
            else -> return 0
        }

        var bonus = 0
        if (expectedTrackCount > 0 && candTrackCount > 0) {
            if (candTrackCount == expectedTrackCount) bonus += 10
            else if (abs(candTrackCount - expectedTrackCount) <= 1) bonus += 4
        }
        val normWantFirst = AppleMusicMotionCoverManager.norm(AppleMusicMotionCoverManager.firstArtist(wantArtist))
        val normCandFirst = AppleMusicMotionCoverManager.norm(AppleMusicMotionCoverManager.firstArtist(candArtist))
        if (normWantFirst.isNotEmpty() && normCandFirst == normWantFirst) {
            bonus += 5
        }
        return baseScore + bonus
    }

    private fun isAcceptableAlbumMatch(
        targetAlbum: String,
        targetArtist: String,
        candidateAlbum: String,
        candidateArtist: String,
    ): Boolean {
        if (!artistMatches(targetArtist, candidateArtist)) return false
        val wantRaw = AppleMusicMotionCoverManager.norm(targetAlbum)
        val wantStripped = AppleMusicMotionCoverManager.norm(AppleMusicMotionCoverManager.stripEdition(targetAlbum))
        val candRaw = AppleMusicMotionCoverManager.norm(candidateAlbum)
        val candStripped = AppleMusicMotionCoverManager.norm(AppleMusicMotionCoverManager.stripEdition(candidateAlbum))
        if (wantRaw.isNotEmpty() && candRaw == wantRaw) return true
        if (wantStripped.isNotEmpty() && candStripped == wantStripped) return true
        if (wantStripped.isNotEmpty() && candStripped.isNotEmpty() &&
            minOf(wantStripped.length, candStripped.length) >= 2 &&
            (candStripped.contains(wantStripped) || wantStripped.contains(candStripped))
        ) {
            return true
        }
        return false
    }

    private fun artistMatches(targetArtist: String, candidateArtist: String): Boolean {
        if (isVariousArtists(targetArtist)) return true
        val wantFirst = AppleMusicMotionCoverManager.norm(AppleMusicMotionCoverManager.firstArtist(targetArtist))
        val wantFull = AppleMusicMotionCoverManager.norm(targetArtist)
        val candFull = AppleMusicMotionCoverManager.norm(candidateArtist)
        val candFirst = AppleMusicMotionCoverManager.norm(AppleMusicMotionCoverManager.firstArtist(candidateArtist))
        if (wantFirst.isEmpty() || candFull.isEmpty()) return true
        return candFull == wantFull ||
            candFirst == wantFirst ||
            candFull.contains(wantFirst) ||
            wantFull.contains(candFirst)
    }

    private fun isVariousArtists(artist: String): Boolean {
        val norm = AppleMusicMotionCoverManager.norm(artist)
        return norm == "variousartists" || norm == "va" || norm == "群星"
    }

    private fun extractAlbumId(cd: JSONObject): String? {
        val adamId = cd.optJSONObject("identifiers")?.optString("storeAdamID", "").orEmpty().trim()
        if (adamId.isNotEmpty() && adamId.all { it.isDigit() }) return adamId
        val url = cd.optString("url", "")
        return ALBUM_ID_URL_REGEX.find(url)?.groupValues?.getOrNull(1)
    }

    private fun extractItemTitle(item: JSONObject): String {
        val direct = item.optString("title", "").trim()
        if (direct.isNotEmpty()) return direct
        return item.optJSONArray("titleLinks")?.optJSONObject(0)?.optString("title", "").orEmpty().trim()
    }

    private fun subtitleArtist(item: JSONObject): String {
        val links = item.optJSONArray("subtitleLinks")
        if (links != null && links.length() > 0) {
            val first = links.optJSONObject(0)
            if (first != null) {
                val title = first.optString("title", "").trim()
                if (title.isNotEmpty()) return title
            }
        }
        val sub = item.optString("subtitle", "")
        val dot = sub.indexOf('·')
        return if (dot >= 0) sub.substring(dot + 1).trim() else ""
    }

    private fun getStorefrontHtml(url: String, storefront: String): String? {
        val acceptLang = if (storefront == "cn") "zh-CN,zh;q=0.9,en;q=0.8" else "en-US,en;q=0.9"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", MOBILE_UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", acceptLang)
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                if (resp.code >= 500 || resp.code == 429) throw IOException("http ${resp.code}")
                return null
            }
            val finalPath = resp.request.url.encodedPath
            if (!finalPath.startsWith("/$storefront/")) {
                return null
            }
            return resp.body?.string()
        }
    }

    private fun getItunesJson(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", MOBILE_UA)
            .header("Accept", "application/json")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                if (resp.code >= 500 || resp.code == 429) throw IOException("http ${resp.code}")
                return null
            }
            return resp.body?.string()
        }
    }

    private fun encodeParam(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun cacheDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!isDirectory) mkdirs() }

    private fun ensureIndexLoadedSync(context: Context) {
        if (indexLoaded) return
        synchronized(this) {
            if (indexLoaded) return
            val indexFile = File(cacheDir(context), INDEX_NAME)
            if (indexFile.isFile) {
                runCatching {
                    indexFile.readLines().forEach { line ->
                        if (line.isBlank()) return@forEach
                        val j = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                        val k = j.optString("k")
                        val status = j.optString("s")
                        if (k.isBlank() || status.isBlank()) return@forEach
                        indexCache[k] = CachedEntry(
                            status = status,
                            label = j.optString("v").ifBlank { null },
                            timestampMs = j.optLong("t", 0L),
                        )
                    }
                }
            }
            indexLoaded = true
        }
    }

    private suspend fun ensureIndexLoaded(context: Context) {
        if (indexLoaded) return
        indexMutex.withLock { ensureIndexLoadedSync(context) }
    }

    private suspend fun putIndexEntry(
        context: Context,
        key: String,
        status: String,
        label: String?,
    ) {
        indexMutex.withLock {
            ensureIndexLoadedSync(context)
            indexCache[key] = CachedEntry(status, label, System.currentTimeMillis())
            if (indexCache.size > MAX_INDEX_ENTRIES) {
                val oldestKey = indexCache.entries.minByOrNull { it.value.timestampMs }?.key
                if (oldestKey != null) indexCache.remove(oldestKey)
            }
            val lines = indexCache.entries.map { (k, entry) ->
                JSONObject().apply {
                    put("k", k)
                    put("s", entry.status)
                    put("t", entry.timestampMs)
                    entry.label?.let { put("v", it) }
                }.toString()
            }
            val dir = cacheDir(context)
            val tmp = File(dir, "$INDEX_NAME.tmp")
            val dst = File(dir, INDEX_NAME)
            runCatching {
                tmp.writeText(lines.joinToString("\n"))
                if (!tmp.renameTo(dst)) {
                    dst.writeText(lines.joinToString("\n"))
                    tmp.delete()
                }
            }
        }
    }
}
