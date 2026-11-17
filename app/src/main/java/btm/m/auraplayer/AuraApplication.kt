package btm.m.auraplayer

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import btm.m.auraplayer.data.*
import btm.m.auraplayer.ui.I18n
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath

class AuraApplication : Application(), SingletonImageLoader.Factory {
    lateinit var nowPlaying: btm.m.auraplayer.playback.NowPlayingSettings; private set
    lateinit var rearScreen: btm.m.auraplayer.rear.RearScreenSettings; private set
    lateinit var llmTranslation: btm.m.auraplayer.lyrics.LlmTranslationSettings; private set
    lateinit var rearPrivilege: btm.m.auraplayer.rear.RearScreenPrivilegeManager; private set
    lateinit var effects: btm.m.auraplayer.audio.EffectsStore; private set
    lateinit var auraSing: btm.m.auraplayer.sing.AuraSingEngine; private set
    lateinit var auraMix: btm.m.auraplayer.auramix.AuraMixSettings; private set
    lateinit var settings: AuraSettings; private set
    internal lateinit var navigationMaterial: btm.m.auraplayer.ui.navigation.NavigationMaterialStore; private set
    lateinit var history: LibraryHistory; private set
    lateinit var session: SecureSession; private set
    lateinit var api: NeteaseApi; private set
    lateinit var qq: QqMusicApi; private set
    @Volatile var qqEndpoint: String = ""; private set
    @Volatile var qqUin: String = ""; private set
    @Volatile var qqCookie: String = ""; private set
    @Volatile var accountsState: MultiAccountState = MultiAccountState(); private set
    @Volatile var cookie: String = ""; private set
    @Volatile var endpoint: String = ""; private set
    override fun onCreate() {
        super.onCreate()
        settings = AuraSettings(this)
        navigationMaterial = btm.m.auraplayer.ui.navigation.NavigationMaterialStore(this)
        nowPlaying = btm.m.auraplayer.playback.NowPlayingSettings(this)
        rearScreen = btm.m.auraplayer.rear.RearScreenSettings(this)
        llmTranslation = btm.m.auraplayer.lyrics.LlmTranslationSettings(this)
        rearPrivilege = btm.m.auraplayer.rear.RearScreenPrivilegeManager(this)
        effects = btm.m.auraplayer.audio.EffectsStore(this)
        effects.registerProcessUsbMonitor()
        auraSing = btm.m.auraplayer.sing.AuraSingEngine(this)
        auraMix = btm.m.auraplayer.auramix.AuraMixSettings(this)
        history = LibraryHistory(this)
        session = SecureSession(this)
        accountsState = MultiAccountStateCodec.decode(session.read())
        cookie = accountsState.activeCookie
        endpoint = getSharedPreferences("aura.cloud", MODE_PRIVATE).getString("endpoint", "").orEmpty()
        api = NeteaseApi({ endpoint }, { cookie }, metadataCache = CloudMetadataCache(cacheDir.resolve("cloud-metadata")))
        getSharedPreferences("aura.cloud", MODE_PRIVATE).let {
            qqEndpoint = it.getString("qqEndpoint", "").orEmpty(); qqUin = it.getString("qqUin", "").orEmpty()
        }
        qqCookie = session.readQq()
        qq = QqMusicApi({ qqEndpoint }, { qqUin }, { qqCookie })
        I18n.initialize(this, settings.value.language)
        btm.m.auraplayer.download.MusicDownloads.resume(this)
        if (btm.m.auraplayer.widget.WidgetUpdateCoordinator.hasActiveWidgets(this)) {
            btm.m.auraplayer.widget.WidgetUpdateCoordinator.requestUpdate(this)
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (btm.m.auraplayer.widget.WidgetUpdateCoordinator.hasActiveWidgets(this)) {
            btm.m.auraplayer.widget.WidgetUpdateCoordinator.requestUpdate(this)
        }
    }
    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(LocalAudioArtworkFetcher.Factory(context))
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, .15).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("artwork").toOkioPath()).maxSizeBytes(128L * 1024 * 1024).build() }
        .build()
    private fun persistAccounts(next: MultiAccountState) {
        accountsState = next
        cookie = next.activeCookie
        val encoded = MultiAccountStateCodec.encode(next)
        if (encoded.isEmpty()) session.clear() else session.save(encoded)
    }
    fun savedAccounts(): List<StoredCloudAccount> = accountsState.accounts
    fun activeStoredAccount(): StoredCloudAccount? = accountsState.activeAccount
    fun saveAccountSession(user: CloudAccount, cookieValue: String) =
        persistAccounts(MultiAccountStateCodec.upsertAndActivate(accountsState, user, cookieValue))
    fun switchAccount(userId: Long): StoredCloudAccount? {
        persistAccounts(MultiAccountStateCodec.switchActive(accountsState, userId))
        return accountsState.activeAccount
    }
    fun removeAccount(userId: Long): StoredCloudAccount? {
        persistAccounts(MultiAccountStateCodec.removeAccount(accountsState, userId))
        return accountsState.activeAccount
    }
    fun saveCookie(value: String) {
        persistAccounts(MultiAccountState(legacyCookie = value.trim()))
    }
    fun clearCookie() {
        persistAccounts(MultiAccountStateCodec.deactivateActive(accountsState))
    }
    fun useOfficialService() {
        if (endpoint.isNotBlank()) clearCookie()
        endpoint = ""
        getSharedPreferences("aura.cloud", MODE_PRIVATE).edit().remove("endpoint").apply()
    }
    /** Saves the self-hosted QQMusicApi address, QQ number and optional y.qq.com cookie. A blank endpoint uses official QQ endpoints. */
    fun saveQq(endpointValue: String, uin: String, cookieValue: String) {
        val normalized = endpointValue.trim().takeIf { it.isNotEmpty() }?.let { NeteaseApi.normalizeEndpoint(it) }.orEmpty()
        val changed = qqEndpoint != normalized
        qqEndpoint = normalized; qqUin = uin.trim().filter(Char::isDigit); qqCookie = cookieValue.trim()
        getSharedPreferences("aura.cloud", MODE_PRIVATE).edit().apply {
            putString("qqEndpoint", qqEndpoint)
            putString("qqUin", qqUin)
            if (changed) { remove("qqUserId"); remove("qqNickname"); remove("qqAvatar") }
        }.apply()
        session.saveQq(qqCookie)
    }
    fun useQqOfficialService() {
        if (qqEndpoint.isNotBlank()) clearQqSession()
        qqEndpoint = ""
        getSharedPreferences("aura.cloud", MODE_PRIVATE).edit().remove("qqEndpoint").apply()
    }
    fun saveQqAccount(user: CloudAccount, cookieValue: String) {
        qqCookie = cookieValue.trim()
        if (user.userId > 0L) qqUin = user.userId.toString()
        session.saveQq(qqCookie)
        getSharedPreferences("aura.cloud", MODE_PRIVATE).edit()
            .putString("qqUin", qqUin)
            .putLong("qqUserId", user.userId)
            .putString("qqNickname", user.nickname)
            .putString("qqAvatar", user.avatar)
            .apply()
    }
    fun savedQqAccount(): CloudAccount? {
        if (qqCookie.isBlank()) return null
        val prefs = getSharedPreferences("aura.cloud", MODE_PRIVATE)
        val uid = prefs.getLong("qqUserId", 0L)
        val nick = prefs.getString("qqNickname", "").orEmpty()
        if (uid <= 0L && nick.isBlank()) return null
        return CloudAccount(uid, nick.ifBlank { "QQ $uid" }, prefs.getString("qqAvatar", "").orEmpty())
    }
    fun clearQqSession() {
        qqCookie = ""
        session.saveQq("")
        getSharedPreferences("aura.cloud", MODE_PRIVATE).edit()
            .remove("qqUserId").remove("qqNickname").remove("qqAvatar").apply()
    }
    fun saveEndpoint(value: String) {
        val normalized = NeteaseApi.normalizeEndpoint(value)
        if (endpoint != normalized) clearCookie()
        endpoint = normalized
        getSharedPreferences("aura.cloud", MODE_PRIVATE).edit().putString("endpoint", normalized).apply()
    }
}
