package btm.m.auraplayer.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The account cookie is encrypted with a key that never leaves Android Keystore. */
class SecureSession(context: Context) {
    private val prefs = context.getSharedPreferences("aura.session", Context.MODE_PRIVATE)
    private val alias = "aura.netease.session"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): String = runCatching {
        val payload = prefs.getString("cookie", null) ?: return ""
        val parts = payload.split(':')
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }
    }.getOrDefault("")
    fun save(cookie: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(cookie.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("cookie", "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(encrypted, Base64.NO_WRAP)}").apply()
    }
    fun clear() { prefs.edit().clear().apply() }
}
