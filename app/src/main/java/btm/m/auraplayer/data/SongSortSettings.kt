package btm.m.auraplayer.data

import android.content.Context
import androidx.compose.runtime.*

class SongSortSettings(context: Context) {
    private val prefs = context.getSharedPreferences("aura.song.sort", Context.MODE_PRIVATE)
    var order by mutableStateOf(SongSort.fromKey(prefs.getString("order", "default").orEmpty())); private set
    fun update(value: SongSort) { prefs.edit().putString("order", value.key).apply(); order = value }
}
