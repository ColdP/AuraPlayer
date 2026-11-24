// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import btm.m.auraplayer.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit

internal enum class UpdateChannel(val prefValue: String) {
    GITHUB("github"),
    CLOUD_DISK("cloud_disk"),
    ;

    companion object {
        fun fromPref(raw: String?): UpdateChannel = when (raw) {
            CLOUD_DISK.prefValue -> CLOUD_DISK
            else -> GITHUB
        }
    }
}

internal data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val releaseNoteUrl: String,
    val apkUrl: String,
    val apkSize: Long,
)

internal object AppUpdateManager {
    private const val PREFS_NAME = "software_update"
    private const val KEY_UPDATE_CHANNEL = "update_channel"
    private const val PRIMARY_UPDATE_URL = "https://coldp.github.io/auraplayer-updateinfo/update.json"
    private const val RAW_UPDATE_URL = "https://raw.githubusercontent.com/ColdP/auraplayer-updateinfo/main/update.json"
    private const val CLOUD_DISK_UPDATE_URL = "https://raw.giteeusercontent.com/btm_m/aura-update-info/raw/master/update-info.json"
    private const val PAGES_NOTES_PREFIX = "https://coldp.github.io/AuraPlayer/"
    private const val RAW_NOTES_PREFIX = "https://raw.githubusercontent.com/ColdP/AuraPlayer/main/"

    @Volatile
    var startupUpdateChecked: Boolean = false

    @Volatile
    var startupUpdatePending: UpdateInfo? = null

    fun getUpdateChannel(context: Context): UpdateChannel {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return UpdateChannel.fromPref(prefs.getString(KEY_UPDATE_CHANNEL, null))
    }

    fun setUpdateChannel(context: Context, channel: UpdateChannel) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_UPDATE_CHANNEL, channel.prefValue)
            .apply()
    }

    fun fetchUpdateInfo(channel: UpdateChannel = UpdateChannel.GITHUB): UpdateInfo {
        val urls = when (channel) {
            UpdateChannel.GITHUB -> listOf(PRIMARY_UPDATE_URL, RAW_UPDATE_URL)
            UpdateChannel.CLOUD_DISK -> listOf(CLOUD_DISK_UPDATE_URL)
        }
        var lastError: Throwable? = null
        for (url in urls) {
            val attempt = runCatching { parseUpdateInfo(httpGet(url, acceptJson = true)) }
            if (attempt.isSuccess) return attempt.getOrThrow()
            lastError = attempt.exceptionOrNull()
        }
        throw lastError ?: IllegalStateException("Unable to fetch update info")
    }

    fun parseUpdateInfo(raw: String): UpdateInfo {
        val json = JSONObject(raw)
        val versionCode = json.optString("VersionCode").toLongOrNull() ?: json.optLong("VersionCode", 0L)
        return UpdateInfo(
            versionCode = versionCode,
            versionName = json.getString("VersionName"),
            releaseNoteUrl = json.getString("ReleaseNoteURL"),
            apkUrl = json.getString("APKURL"),
            apkSize = json.optLong("APKSize", 0L),
        )
    }

    fun fetchReleaseNote(url: String): String {
        val primary = runCatching { httpGet(url, acceptJson = false).trim() }
        if (primary.isSuccess) return primary.getOrThrow()
        if (url.startsWith(PAGES_NOTES_PREFIX)) {
            val fallbackUrl = RAW_NOTES_PREFIX + url.removePrefix(PAGES_NOTES_PREFIX)
            val fallback = runCatching { httpGet(fallbackUrl, acceptJson = false).trim() }
            if (fallback.isSuccess) return fallback.getOrThrow()
        }
        throw primary.exceptionOrNull() ?: IllegalStateException("Unable to fetch release note")
    }

    fun stripMarkdown(value: String): String = value
        .replace(Regex("^\\s{0,3}#{1,6}\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("[*_~`]+"), "")
        .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("^\\s*[-+*]\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("^\\s*\\d+[.)]\\s+", RegexOption.MULTILINE), "")

    fun formatApkSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024f)
        else -> "$bytes B"
    }

    fun hasRootAccess(): Boolean = runCatching {
        Runtime.getRuntime().exec(arrayOf("su", "-c", "id")).let { process ->
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.errorStream.close()
            process.waitFor(2, TimeUnit.SECONDS) && output.contains("uid=0")
        }
    }.getOrDefault(false)

    fun installDownloadedApk(context: Context, downloadId: Long): Boolean = runCatching {
        val manager = context.getSystemService(DownloadManager::class.java) ?: return@runCatching false
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId)) ?: return@runCatching false
        val uri = cursor.use {
            if (!it.moveToFirst()) return@use null
            it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
        } ?: return@runCatching false
        val path = Uri.parse(uri).path ?: return@runCatching false
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm install -r $path"))
        process.inputStream.close()
        process.errorStream.close()
        process.waitFor() == 0
    }.getOrDefault(false)

    private fun httpGet(url: String, acceptJson: Boolean): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            if (acceptJson) setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AuraPlayer/${BuildConfig.VERSION_NAME}")
        }
        return try {
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
