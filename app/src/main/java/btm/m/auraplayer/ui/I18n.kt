package btm.m.auraplayer.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/** All UI text goes through tr; packs retain HyperChanger's extensible JSON shape. */
object I18n {
    var language by mutableStateOf("zh")
    private var english = JSONObject()
    private var japanese = JSONObject()
    fun initialize(context: Context, selected: String) {
        language = selected
        english = runCatching { JSONObject(context.assets.open("languages/en.json").bufferedReader().use { it.readText() }).getJSONObject("strings") }.getOrDefault(JSONObject())
        japanese = runCatching { JSONObject(context.assets.open("languages/ja.json").bufferedReader().use { it.readText() }).getJSONObject("strings") }.getOrDefault(JSONObject())
    }
    fun text(key: String, fallback: String): String = when (language) {
        "en" -> english.optString(key, fallback)
        "ja" -> japanese.optString(key, fallback)
        else -> fallback
    }
}
fun tr(key: String, fallback: String): String = I18n.text(key, fallback)
