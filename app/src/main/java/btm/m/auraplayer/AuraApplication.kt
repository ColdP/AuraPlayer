package btm.m.auraplayer

import android.app.Application
import btm.m.auraplayer.data.*
import btm.m.auraplayer.ui.I18n
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath

class AuraApplication : Application(), SingletonImageLoader.Factory {
    lateinit var nowPlaying: btm.m.auraplayer.playback.NowPlayingSettings; private set
    lateinit var effects: btm.m.auraplayer.audio.EffectsStore; private set
    lateinit var settings: AuraSettings; private set
    lateinit var history: LibraryHistory; private set
    lateinit var session: SecureSession; private set
    lateinit var api: NeteaseApi; private set
    @Volatile var cookie: String = ""; private set
    @Volatile var endpoint: String = ""; private set
    override fun onCreate() {
        super.onCreate()
        settings = AuraSettings(this)
        nowPlaying = btm.m.auraplayer.playback.NowPlayingSettings(this)
        effects = btm.m.auraplayer.audio.EffectsStore(this)
        history = LibraryHistory(this)
        session = SecureSession(this)
        cookie = session.read()
        endpoint = getSharedPreferences("aura.cloud", MODE_PRIVATE).getString("endpoint", "").orEmpty()
        api = NeteaseApi({ endpoint }, { cookie }, metadataCache = CloudMetadataCache(cacheDir.resolve("cloud-metadata")))
        I18n.initialize(this, settings.value.language)
        btm.m.auraplayer.download.MusicDownloads.resume(this)
    }
    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, .15).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("artwork").toOkioPath()).maxSizeBytes(128L * 1024 * 1024).build() }
        .build()
    fun saveCookie(value: String) { session.save(value); cookie = value }
    fun clearCookie() { session.clear(); cookie = "" }
    fun useOfficialService() {
        if (endpoint.isNotBlank()) clearCookie()
        endpoint = ""
        getSharedPreferences("aura.cloud", MODE_PRIVATE).edit().remove("endpoint").apply()
    }
    fun saveEndpoint(value: String) {
        val normalized = NeteaseApi.normalizeEndpoint(value)
        if (endpoint != normalized) clearCookie()
        endpoint = normalized
        getSharedPreferences("aura.cloud", MODE_PRIVATE).edit().putString("endpoint", normalized).apply()
    }
}
