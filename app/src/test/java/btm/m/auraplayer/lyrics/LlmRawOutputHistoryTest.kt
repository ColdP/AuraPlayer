// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.lyrics

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream

class LlmRawOutputHistoryTest {
    private fun line(text: String, start: Int): LyricLine =
        LyricLine(text, null, null, start, start + 1500, false, null, null, null)

    @Test
    fun translateRecordsRawSuccessAndErrorApiOutputsPerCall() = runBlocking {
        val cacheDir = Files.createTempDirectory("raw_history_test").toFile()
        val server = MockWebServer()
        try {
            val rawAssistantJson = """{"translations":[{"i":0,"t":"你好世界"},{"i":1,"t":"再见星空"}]}"""
            val okResponseBody = JSONObject().apply {
                put("id", "chatcmpl-test-1")
                put(
                    "choices",
                    JSONArray().put(
                        JSONObject().put(
                            "message",
                            JSONObject().put("content", rawAssistantJson),
                        ),
                    ),
                )
            }.toString()
            server.enqueue(MockResponse().setResponseCode(200).setBody(okResponseBody))
            server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":{"message":"upstream down"}}"""))
            server.start()

            val config = LlmTranslationConfig(
                enabled = true,
                provider = LlmApiProvider.OPENAI_COMPATIBLE,
                openAiBaseUrl = server.url("/v1").toString(),
                openAiModel = "gpt-4o-mini",
                openAiModels = listOf("gpt-4o-mini"),
                sourceLanguage = LlmSourceLanguage.EN,
                targetLanguage = LlmTargetLanguage.ZH_HANS,
            )
            val lines = listOf(line("Hello world", 1000), line("Goodbye starlight", 3000))
            val client = OkHttpClient()

            val firstResult = LlmTranslator.translate(
                songKey = "song-1",
                songTitle = "Starlight",
                artist = "Aura Artist",
                albumTitle = "Night Album",
                lines = lines,
                config = config,
                apiKey = "sk-test",
                cacheRoot = cacheDir,
                forceRefresh = true,
                httpClient = client,
            )
            assertNotNull(firstResult)

            val secondResult = LlmTranslator.translate(
                songKey = "song-2",
                songTitle = "Broken Track",
                artist = "Error Artist",
                albumTitle = "Test",
                lines = lines,
                config = config,
                apiKey = "sk-test",
                cacheRoot = cacheDir,
                forceRefresh = true,
                httpClient = client,
            )
            assertEquals(null, secondResult)

            val records = LlmRawOutputHistoryStore.list(cacheDir)
            assertEquals(2, records.size)
            val errRecord = records.first { !it.succeeded }
            val okRecord = records.first { it.succeeded }
            assertEquals(502, errRecord.httpStatus)
            assertTrue(errRecord.primaryOutput.contains("upstream down"))
            assertEquals(200, okRecord.httpStatus)
            assertEquals(rawAssistantJson, okRecord.rawContent)
            assertEquals(okResponseBody, okRecord.rawResponseBody)
            assertEquals("Starlight", okRecord.songTitle)
        } finally {
            server.shutdown()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun filterExportZipDeleteAndClearWorkAsExpected() {
        val cacheDir = Files.createTempDirectory("raw_history_ops").toFile()
        try {
            val r1 = LlmRawOutputRecord(
                id = "id-1",
                createdAtMs = 1_700_000_001_000L,
                songKey = "k1",
                songTitle = "Idol",
                artist = "YOASOBI",
                albumTitle = "The Book",
                provider = LlmApiProvider.OPENAI_COMPATIBLE,
                modelName = "deepseek-chat",
                sourceLanguage = "ja",
                targetLanguage = "zh-Hans",
                httpStatus = 200,
                succeeded = true,
                rawContent = """{"translations":[{"i":0,"t":"无人能及的笑容"}]}""",
                rawResponseBody = """{"id":"1","choices":[{"message":{"content":"..."}}]}""",
            )
            val r2 = LlmRawOutputRecord(
                id = "id-2",
                createdAtMs = 1_700_000_002_000L,
                songKey = "k2",
                songTitle = "Lemon",
                artist = "Kenshi Yonezu",
                albumTitle = "Stray Sheep",
                provider = LlmApiProvider.ANTHROPIC_COMPATIBLE,
                modelName = "claude-sonnet-4",
                sourceLanguage = "ja",
                targetLanguage = "en",
                httpStatus = 429,
                succeeded = false,
                rawContent = "",
                rawResponseBody = """{"error":{"type":"rate_limit_error"}}""",
            )
            assertTrue(LlmRawOutputHistoryStore.record(cacheDir, r1))
            assertTrue(LlmRawOutputHistoryStore.record(cacheDir, r2))

            val listed = LlmRawOutputHistoryStore.list(cacheDir)
            assertEquals(listOf("id-2", "id-1"), listed.map { it.id })

            assertEquals(1, LlmRawOutputHistoryStore.filter(listed, "yoasobi deepseek").size)
            assertEquals(1, LlmRawOutputHistoryStore.filter(listed, "429 rate_limit_error").size)
            assertEquals(2, LlmRawOutputHistoryStore.filter(listed, "  ").size)

            val txtExport = LlmRawOutputExporter.export(r1, LlmRawOutputExportFormat.TXT)
            assertTrue(txtExport.contains("Idol") && txtExport.contains("无人能及的笑容"))

            val zipOut = ByteArrayOutputStream()
            LlmRawOutputExporter.writeZip(listed, zipOut)
            val zipEntries = LinkedHashMap<String, String>()
            ZipInputStream(ByteArrayInputStream(zipOut.toByteArray())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    zipEntries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                    zip.closeEntry()
                }
            }
            assertTrue(LlmRawOutputExporter.ZIP_MANIFEST_NAME in zipEntries)
            assertEquals(3, zipEntries.size)
            val manifest = JSONArray(zipEntries.getValue(LlmRawOutputExporter.ZIP_MANIFEST_NAME))
            assertEquals(2, manifest.length())

            assertEquals(1, LlmRawOutputHistoryStore.deleteEntries(cacheDir, listOf("id-1")))
            assertEquals(listOf("id-2"), LlmRawOutputHistoryStore.list(cacheDir).map { it.id })
            assertTrue(LlmRawOutputHistoryStore.clear(cacheDir))
            assertTrue(LlmRawOutputHistoryStore.list(cacheDir).isEmpty())
            assertFalse(listed.first().succeeded)
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
