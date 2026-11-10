package btm.m.auraplayer

import android.app.Application
import btm.m.auraplayer.data.*
import btm.m.auraplayer.ui.I18n

class AuraApplication : Application() {
    lateinit var settings: AuraSettings; private set
    lateinit var history: LibraryHistory; private set
    lateinit var session: SecureSession; private set
    lateinit var api: NeteaseApi; private set
    @Volatile var cookie: String = ""; private set
    @Volatile var endpoint: String = ""; private set
    override fun onCreate() {
        super.onCreate()
        settings = AuraSettings(this)
        history = LibraryHistory(this)
        session = SecureSession(this)
        cookie = session.read()
        endpoint = getSharedPreferences("aura.cloud", MODE_PRIVATE).getString("endpoint", "").orEmpty()
        api = NeteaseApi({ endpoint }, { cookie })
        I18n.initialize(this, settings.value.language)
    }
    fun saveCookie(value: String) { session.save(value); cookie = value }
    fun clearCookie() { session.clear(); cookie = "" }
    fun saveEndpoint(value: String) {
        val normalized = NeteaseApi.normalizeEndpoint(value)
        if (endpoint != normalized) clearCookie()
        endpoint = normalized
        getSharedPreferences("aura.cloud", MODE_PRIVATE).edit().putString("endpoint", normalized).apply()
    }
}
