package btm.m.auraplayer.data

import android.content.Context
import androidx.compose.runtime.*

class SongSortSettings(context: Context) {
    private val prefs = context.getSharedPreferences("aura.song.sort", Context.MODE_PRIVATE)
    var order by mutableStateOf(SongSort.fromKey(prefs.getString("order", "default").orEmpty())); private set
    fun update(value: SongSort) { prefs.edit().putString("order", value.key).apply(); order = value }
}

class PlaylistSortSettings(context: Context) {
    private val prefs = context.getSharedPreferences("aura.playlist.sort", Context.MODE_PRIVATE)
    var revision by mutableIntStateOf(0); private set

    fun orderFor(collectionKey: String): SongSort =
        SongSort.fromKey(prefs.getString(collectionKey, SongSort.DEFAULT.key).orEmpty())

    fun update(collectionKey: String, value: SongSort) {
        if (value == SongSort.DEFAULT) {
            prefs.edit().remove(collectionKey).apply()
        } else {
            prefs.edit().putString(collectionKey, value.key).apply()
        }
        revision++
    }
}
