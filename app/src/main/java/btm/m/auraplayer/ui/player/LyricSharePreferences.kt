// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import btm.m.auraplayer.lyrics.LyricSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal data class LyricShareOptionsState(
    val showTranslation: Boolean,
    val showPronunciation: Boolean,
    val showSongInfo: Boolean,
    val showBrand: Boolean,
    val showRoundedCorners: Boolean,
    val paperDarkMode: Boolean,
    val lyricSize: LyricSize,
    val lineSpacing: LyricShareLineSpacing,
    val lyricFont: LyricShareFont,
    val selectedSavedFontName: String?,
    val customFontName: String?,
    val imageQuality: LyricShareImageQuality,
    val lyricFilter: LyricShareFilter,
    val customBgName: String?,
    val customBgBlur: Float,
)

internal inline fun <reified T : Enum<T>> parseShareEnumOrDefault(raw: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == raw } ?: default

/** Stores lyric-share options independently; share template selection is intentionally not persisted. */
internal class LyricSharePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("aura.lyric-share.v1", Context.MODE_PRIVATE)
    private val storageDir = context.filesDir.resolve("lyric-share-memory")

    fun load(
        defaultShowTranslation: Boolean,
        defaultShowPronunciation: Boolean,
        defaultLyricSize: LyricSize,
    ): LyricShareOptionsState = LyricShareOptionsState(
        showTranslation = if (prefs.contains(KEY_SHOW_TRANSLATION)) prefs.getBoolean(KEY_SHOW_TRANSLATION, defaultShowTranslation) else defaultShowTranslation,
        showPronunciation = if (prefs.contains(KEY_SHOW_PRONUNCIATION)) prefs.getBoolean(KEY_SHOW_PRONUNCIATION, defaultShowPronunciation) else defaultShowPronunciation,
        showSongInfo = prefs.getBoolean(KEY_SHOW_SONG_INFO, true),
        showBrand = prefs.getBoolean(KEY_SHOW_BRAND, true),
        showRoundedCorners = prefs.getBoolean(KEY_SHOW_ROUNDED, false),
        paperDarkMode = prefs.getBoolean(KEY_PAPER_DARK_MODE, false),
        lyricSize = parseShareEnumOrDefault(prefs.getString(KEY_LYRIC_SIZE, null), defaultLyricSize),
        lineSpacing = parseShareEnumOrDefault(prefs.getString(KEY_LINE_SPACING, null), LyricShareLineSpacing.REGULAR),
        lyricFont = parseShareEnumOrDefault(prefs.getString(KEY_LYRIC_FONT, null), LyricShareFont.SYSTEM_DEFAULT),
        selectedSavedFontName = prefs.getString(KEY_SAVED_FONT_NAME, null),
        customFontName = prefs.getString(KEY_CUSTOM_FONT_NAME, null),
        imageQuality = parseShareEnumOrDefault(prefs.getString(KEY_IMAGE_QUALITY, null), LyricShareImageQuality.NORMAL),
        lyricFilter = parseShareEnumOrDefault(prefs.getString(KEY_LYRIC_FILTER, null), LyricShareFilter.DEFAULT),
        customBgName = prefs.getString(KEY_CUSTOM_BG_NAME, null),
        customBgBlur = prefs.getFloat(KEY_CUSTOM_BG_BLUR, 0f).coerceIn(0f, 40f),
    )

    fun setShowTranslation(value: Boolean) = prefs.edit().putBoolean(KEY_SHOW_TRANSLATION, value).apply()
    fun setShowPronunciation(value: Boolean) = prefs.edit().putBoolean(KEY_SHOW_PRONUNCIATION, value).apply()
    fun setShowSongInfo(value: Boolean) = prefs.edit().putBoolean(KEY_SHOW_SONG_INFO, value).apply()
    fun setShowBrand(value: Boolean) = prefs.edit().putBoolean(KEY_SHOW_BRAND, value).apply()
    fun setShowRoundedCorners(value: Boolean) = prefs.edit().putBoolean(KEY_SHOW_ROUNDED, value).apply()
    fun setPaperDarkMode(value: Boolean) = prefs.edit().putBoolean(KEY_PAPER_DARK_MODE, value).apply()
    fun setLyricSize(value: LyricSize) = prefs.edit().putString(KEY_LYRIC_SIZE, value.name).apply()
    fun setLineSpacing(value: LyricShareLineSpacing) = prefs.edit().putString(KEY_LINE_SPACING, value.name).apply()
    fun setImageQuality(value: LyricShareImageQuality) = prefs.edit().putString(KEY_IMAGE_QUALITY, value.name).apply()
    fun setLyricFilter(value: LyricShareFilter) = prefs.edit().putString(KEY_LYRIC_FILTER, value.name).apply()
    fun setCustomBgBlur(value: Float) = prefs.edit().putFloat(KEY_CUSTOM_BG_BLUR, value.coerceIn(0f, 40f)).apply()

    fun setFontSelection(font: LyricShareFont, savedFontName: String?) {
        prefs.edit().apply {
            putString(KEY_LYRIC_FONT, font.name)
            if (savedFontName.isNullOrBlank()) remove(KEY_SAVED_FONT_NAME)
            else putString(KEY_SAVED_FONT_NAME, savedFontName)
        }.apply()
    }

    suspend fun saveCustomBackground(bitmap: ImageBitmap, name: String) {
        withContext(Dispatchers.IO) {
            storageDir.mkdirs()
            val target = storageDir.resolve(CUSTOM_BG_FILE)
            val tmp = storageDir.resolve("$CUSTOM_BG_FILE.tmp")
            val ok = runCatching {
                tmp.outputStream().use { out ->
                    check(bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out))
                }
                if (target.exists()) target.delete()
                check(tmp.renameTo(target))
                true
            }.getOrDefault(false)
            if (ok) {
                prefs.edit().putString(KEY_CUSTOM_BG_NAME, name).apply()
            } else {
                tmp.delete()
            }
        }
    }

    suspend fun clearCustomBackground() {
        prefs.edit().remove(KEY_CUSTOM_BG_NAME).apply()
        withContext(Dispatchers.IO) {
            storageDir.resolve(CUSTOM_BG_FILE).delete()
        }
    }

    suspend fun loadCustomBackground(): Pair<ImageBitmap, String>? =
        withContext(Dispatchers.IO) {
            val name = prefs.getString(KEY_CUSTOM_BG_NAME, null) ?: return@withContext null
            val file = storageDir.resolve(CUSTOM_BG_FILE)
            if (!file.isFile) {
                prefs.edit().remove(KEY_CUSTOM_BG_NAME).apply()
                return@withContext null
            }
            val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
                ?: return@withContext null
            bitmap to name
        }

    suspend fun saveCustomFont(sourceFile: File, name: String) {
        withContext(Dispatchers.IO) {
            storageDir.mkdirs()
            val ext = name.substringAfterLast('.', "ttf").lowercase().takeIf { it in setOf("ttf", "otf", "ttc") } ?: "ttf"
            val fileName = "$CUSTOM_FONT_PREFIX.$ext"
            val target = storageDir.resolve(fileName)
            val tmp = storageDir.resolve("$fileName.tmp")
            val ok = runCatching {
                sourceFile.inputStream().use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                storageDir.listFiles()?.filter { it.name.startsWith("$CUSTOM_FONT_PREFIX.") && it != tmp }?.forEach { it.delete() }
                check(tmp.renameTo(target))
                true
            }.getOrDefault(false)
            if (ok) {
                prefs.edit()
                    .putString(KEY_CUSTOM_FONT_FILE, fileName)
                    .putString(KEY_CUSTOM_FONT_NAME, name)
                    .apply()
            } else {
                tmp.delete()
            }
        }
    }

    suspend fun clearCustomFont() {
        prefs.edit().remove(KEY_CUSTOM_FONT_FILE).remove(KEY_CUSTOM_FONT_NAME).apply()
        withContext(Dispatchers.IO) {
            storageDir.listFiles()?.filter { it.name.startsWith("$CUSTOM_FONT_PREFIX.") }?.forEach { it.delete() }
        }
    }

    suspend fun loadCustomFont(): Pair<FontFamily, String>? =
        withContext(Dispatchers.IO) {
            val fileName = prefs.getString(KEY_CUSTOM_FONT_FILE, null) ?: return@withContext null
            val name = prefs.getString(KEY_CUSTOM_FONT_NAME, null) ?: return@withContext null
            val file = storageDir.resolve(fileName)
            if (!file.isFile) {
                prefs.edit().remove(KEY_CUSTOM_FONT_FILE).remove(KEY_CUSTOM_FONT_NAME).apply()
                return@withContext null
            }
            val typeface = runCatching { Typeface.createFromFile(file) }.getOrNull() ?: return@withContext null
            FontFamily(typeface) to name
        }

    private companion object {
        const val KEY_SHOW_TRANSLATION = "show_translation"
        const val KEY_SHOW_PRONUNCIATION = "show_pronunciation"
        const val KEY_SHOW_SONG_INFO = "show_song_info"
        const val KEY_SHOW_BRAND = "show_brand"
        const val KEY_SHOW_ROUNDED = "show_rounded_corners"
        const val KEY_PAPER_DARK_MODE = "paper_dark_mode"
        const val KEY_LYRIC_SIZE = "lyric_size"
        const val KEY_LINE_SPACING = "line_spacing"
        const val KEY_LYRIC_FONT = "lyric_font"
        const val KEY_SAVED_FONT_NAME = "saved_font_name"
        const val KEY_CUSTOM_FONT_FILE = "custom_font_file"
        const val KEY_CUSTOM_FONT_NAME = "custom_font_name"
        const val KEY_IMAGE_QUALITY = "image_quality"
        const val KEY_LYRIC_FILTER = "lyric_filter"
        const val KEY_CUSTOM_BG_NAME = "custom_bg_name"
        const val KEY_CUSTOM_BG_BLUR = "custom_bg_blur"
        const val CUSTOM_BG_FILE = "custom_bg.png"
        const val CUSTOM_FONT_PREFIX = "custom_font"
    }
}
