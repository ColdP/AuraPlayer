// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.lyrics

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class LlmApiProvider(val defaultBaseUrl: String) {
    OPENAI_COMPATIBLE("https://api.openai.com/v1"),
    ANTHROPIC_COMPATIBLE("https://api.anthropic.com/v1"),
}

enum class LlmSourceLanguage(val code: String, val promptName: String) {
    AUTO("auto", "Auto-detect"),
    ZH_HANS("zh-Hans", "Simplified Chinese"),
    ZH_HANT("zh-Hant", "Traditional Chinese"),
    EN("en", "English"),
    JA("ja", "Japanese"),
    KO("ko", "Korean"),
    FR("fr", "French"),
    DE("de", "German"),
    ES("es", "Spanish"),
    RU("ru", "Russian"),
}

enum class LlmTargetLanguage(val code: String, val promptName: String) {
    ZH_HANS("zh-Hans", "Simplified Chinese"),
    ZH_HANT("zh-Hant", "Traditional Chinese"),
    EN("en", "English"),
    JA("ja", "Japanese"),
    KO("ko", "Korean"),
    FR("fr", "French"),
    DE("de", "German"),
    ES("es", "Spanish"),
    RU("ru", "Russian"),
}

data class LlmLanguageRule(
    val id: String = UUID.randomUUID().toString(),
    val source: LlmTargetLanguage = LlmTargetLanguage.ZH_HANS,
    val target: LlmTargetLanguage = LlmTargetLanguage.EN,
)

data class LlmTranslationConfig(
    val enabled: Boolean = false,
    val provider: LlmApiProvider = LlmApiProvider.OPENAI_COMPATIBLE,
    val openAiBaseUrl: String = LlmApiProvider.OPENAI_COMPATIBLE.defaultBaseUrl,
    val anthropicBaseUrl: String = LlmApiProvider.ANTHROPIC_COMPATIBLE.defaultBaseUrl,
    val openAiModel: String = "",
    val anthropicModel: String = "",
    val openAiModels: List<String> = emptyList(),
    val anthropicModels: List<String> = emptyList(),
    val sourceLanguage: LlmSourceLanguage = LlmSourceLanguage.AUTO,
    val targetLanguage: LlmTargetLanguage = LlmTargetLanguage.ZH_HANS,
    val customStylePrompt: String = "",
    val autoLanguageRulesEnabled: Boolean = false,
    val autoLanguageRules: List<LlmLanguageRule> = emptyList(),
) {
    fun baseUrlFor(targetProvider: LlmApiProvider = provider): String = when (targetProvider) {
        LlmApiProvider.OPENAI_COMPATIBLE -> openAiBaseUrl.trim().ifBlank { targetProvider.defaultBaseUrl }
        LlmApiProvider.ANTHROPIC_COMPATIBLE -> anthropicBaseUrl.trim().ifBlank { targetProvider.defaultBaseUrl }
    }

    fun activeBaseUrl(): String = baseUrlFor(provider)

    fun activeModel(): String = when (provider) {
        LlmApiProvider.OPENAI_COMPATIBLE -> openAiModel.trim()
        LlmApiProvider.ANTHROPIC_COMPATIBLE -> anthropicModel.trim()
    }

    fun activeModels(): List<String> {
        val raw = when (provider) {
            LlmApiProvider.OPENAI_COMPATIBLE -> openAiModels
            LlmApiProvider.ANTHROPIC_COMPATIBLE -> anthropicModels
        }
        val selected = activeModel()
        return if (selected.isNotBlank() && selected !in raw) {
            listOf(selected) + raw
        } else {
            raw
        }
    }

    fun withBaseUrl(value: String, targetProvider: LlmApiProvider = provider): LlmTranslationConfig {
        val cleaned = value.trim().ifBlank { targetProvider.defaultBaseUrl }
        return when (targetProvider) {
            LlmApiProvider.OPENAI_COMPATIBLE -> copy(openAiBaseUrl = cleaned)
            LlmApiProvider.ANTHROPIC_COMPATIBLE -> copy(anthropicBaseUrl = cleaned)
        }
    }

    fun withSelectedModel(model: String, targetProvider: LlmApiProvider = provider): LlmTranslationConfig {
        val trimmed = model.trim()
        return when (targetProvider) {
            LlmApiProvider.OPENAI_COMPATIBLE -> copy(openAiModel = trimmed)
            LlmApiProvider.ANTHROPIC_COMPATIBLE -> copy(anthropicModel = trimmed)
        }
    }

    fun withModels(models: List<String>, targetProvider: LlmApiProvider = provider): LlmTranslationConfig {
        val cleaned = models.map(String::trim).filter(String::isNotEmpty).distinct()
        val current = when (targetProvider) {
            LlmApiProvider.OPENAI_COMPATIBLE -> openAiModel.trim()
            LlmApiProvider.ANTHROPIC_COMPATIBLE -> anthropicModel.trim()
        }
        val nextSelected = when {
            current.isNotEmpty() && current in cleaned -> current
            cleaned.isNotEmpty() -> cleaned.first()
            else -> current
        }
        return when (targetProvider) {
            LlmApiProvider.OPENAI_COMPATIBLE -> copy(openAiModels = cleaned, openAiModel = nextSelected)
            LlmApiProvider.ANTHROPIC_COMPATIBLE -> copy(anthropicModels = cleaned, anthropicModel = nextSelected)
        }
    }

    fun hasExplicitLanguageConflict(): Boolean =
        resolveTargetLanguage(sourceLanguage).let { resolved ->
            sourceLanguage != LlmSourceLanguage.AUTO && sourceLanguage.code == resolved.code
        }

    fun resolveTargetLanguage(effectiveSource: LlmSourceLanguage?): LlmTargetLanguage {
        if (autoLanguageRulesEnabled && effectiveSource != null && effectiveSource != LlmSourceLanguage.AUTO) {
            val matched = autoLanguageRules.firstOrNull { it.source.code == effectiveSource.code }
            if (matched != null) return matched.target
        }
        return targetLanguage
    }
}

internal object LlmTranslationCodec {
    private const val MAX_PROMPT_LENGTH = 2000

    fun encode(config: LlmTranslationConfig): String = JSONObject()
        .put("enabled", config.enabled)
        .put("provider", config.provider.name)
        .put("openAiBaseUrl", config.openAiBaseUrl)
        .put("anthropicBaseUrl", config.anthropicBaseUrl)
        .put("openAiModel", config.openAiModel)
        .put("anthropicModel", config.anthropicModel)
        .put("openAiModels", JSONArray(config.openAiModels))
        .put("anthropicModels", JSONArray(config.anthropicModels))
        .put("sourceLanguage", config.sourceLanguage.name)
        .put("targetLanguage", config.targetLanguage.name)
        .put("customStylePrompt", config.customStylePrompt.take(MAX_PROMPT_LENGTH))
        .put("autoLanguageRulesEnabled", config.autoLanguageRulesEnabled)
        .put("autoLanguageRules", JSONArray().apply {
            for (rule in config.autoLanguageRules) {
                put(
                    JSONObject()
                        .put("id", rule.id)
                        .put("source", rule.source.name)
                        .put("target", rule.target.name),
                )
            }
        })
        .toString()

    fun decode(raw: String?): LlmTranslationConfig {
        if (raw.isNullOrBlank()) return LlmTranslationConfig()
        val defaults = LlmTranslationConfig()
        return runCatching {
            val json = JSONObject(raw)
            LlmTranslationConfig(
                enabled = json.optBoolean("enabled", defaults.enabled),
                provider = LlmApiProvider.entries.firstOrNull { it.name == json.optString("provider") }
                    ?: defaults.provider,
                openAiBaseUrl = json.optString("openAiBaseUrl").trim().ifBlank { defaults.openAiBaseUrl },
                anthropicBaseUrl = json.optString("anthropicBaseUrl").trim().ifBlank { defaults.anthropicBaseUrl },
                openAiModel = json.optString("openAiModel").trim(),
                anthropicModel = json.optString("anthropicModel").trim(),
                openAiModels = readStringList(json.optJSONArray("openAiModels")),
                anthropicModels = readStringList(json.optJSONArray("anthropicModels")),
                sourceLanguage = LlmSourceLanguage.entries.firstOrNull { it.name == json.optString("sourceLanguage") }
                    ?: defaults.sourceLanguage,
                targetLanguage = LlmTargetLanguage.entries.firstOrNull { it.name == json.optString("targetLanguage") }
                    ?: defaults.targetLanguage,
                customStylePrompt = json.optString("customStylePrompt").take(MAX_PROMPT_LENGTH),
                autoLanguageRulesEnabled = json.optBoolean("autoLanguageRulesEnabled", false),
                autoLanguageRules = readRules(json.optJSONArray("autoLanguageRules")),
            )
        }.getOrDefault(defaults)
    }

    private fun readRules(array: JSONArray?): List<LlmLanguageRule> {
        if (array == null) return emptyList()
        val list = ArrayList<LlmLanguageRule>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val source = LlmTargetLanguage.entries.firstOrNull { it.name == item.optString("source") } ?: continue
            val target = LlmTargetLanguage.entries.firstOrNull { it.name == item.optString("target") } ?: continue
            val id = item.optString("id").trim().ifBlank { UUID.randomUUID().toString() }
            list.add(LlmLanguageRule(id = id, source = source, target = target))
        }
        return list
    }

    private fun readStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val list = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optString(i).trim()
            if (item.isNotEmpty() && item !in list) list.add(item)
        }
        return list
    }
}

/** Stores API keys encrypted via Android Keystore AES/GCM per provider. */
class LlmApiKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("aura.llm.secret", Context.MODE_PRIVATE)

    private fun aliasFor(provider: LlmApiProvider): String = when (provider) {
        LlmApiProvider.OPENAI_COMPATIBLE -> "aura.llm.apikey.openai"
        LlmApiProvider.ANTHROPIC_COMPATIBLE -> "aura.llm.apikey.anthropic"
    }

    private fun prefKeyFor(provider: LlmApiProvider): String = when (provider) {
        LlmApiProvider.OPENAI_COMPATIBLE -> "openai_key"
        LlmApiProvider.ANTHROPIC_COMPATIBLE -> "anthropic_key"
    }

    private fun secretKey(alias: String): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    fun read(provider: LlmApiProvider): String = runCatching {
        val payload = prefs.getString(prefKeyFor(provider), null) ?: return ""
        val parts = payload.split(':')
        if (parts.size != 2) return ""
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, secretKey(aliasFor(provider)), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }
    }.getOrDefault("")

    fun save(provider: LlmApiProvider, apiKey: String) {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            clear(provider)
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, secretKey(aliasFor(provider)))
        }
        val encrypted = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        val encoded = "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(encrypted, Base64.NO_WRAP)}"
        prefs.edit().putString(prefKeyFor(provider), encoded).apply()
    }

    fun clear(provider: LlmApiProvider) {
        prefs.edit().remove(prefKeyFor(provider)).apply()
    }
}

internal fun maskLlmApiKey(key: String): String {
    val trimmed = key.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.length <= 8) return "••••${trimmed.takeLast(minOf(2, trimmed.length))}"
    return "${trimmed.take(3)}••••${trimmed.takeLast(4)}"
}

class LlmTranslationSettings(context: Context) {
    private val prefs = context.getSharedPreferences("aura.llm.translation", Context.MODE_PRIVATE)
    private val keyStore = LlmApiKeyStore(context)
    private val mutable = MutableStateFlow(LlmTranslationCodec.decode(prefs.getString(KEY_CONFIG, null)))
    private val activeKeyMutable = MutableStateFlow(keyStore.read(mutable.value.provider))
    private val revisionMutable = MutableStateFlow(0L)

    val state: StateFlow<LlmTranslationConfig> = mutable.asStateFlow()
    val activeApiKey: StateFlow<String> = activeKeyMutable.asStateFlow()
    val revision: StateFlow<Long> = revisionMutable.asStateFlow()

    val current: LlmTranslationConfig get() = mutable.value

    fun apiKeyFor(provider: LlmApiProvider = mutable.value.provider): String =
        if (provider == mutable.value.provider) activeKeyMutable.value else keyStore.read(provider)

    fun saveApiKey(apiKey: String, provider: LlmApiProvider = mutable.value.provider) {
        keyStore.save(provider, apiKey)
        if (provider == mutable.value.provider) {
            activeKeyMutable.value = keyStore.read(provider)
        }
        revisionMutable.value++
    }

    @Synchronized
    fun update(transform: (LlmTranslationConfig) -> LlmTranslationConfig) {
        val before = mutable.value
        val next = transform(before)
        mutable.value = next
        prefs.edit().putString(KEY_CONFIG, LlmTranslationCodec.encode(next)).apply()
        if (next.provider != before.provider) {
            activeKeyMutable.value = keyStore.read(next.provider)
        }
        if (behaviorChanged(before, next)) {
            revisionMutable.value++
        }
    }

    private fun behaviorChanged(before: LlmTranslationConfig, after: LlmTranslationConfig): Boolean =
        before.enabled != after.enabled ||
            before.provider != after.provider ||
            before.activeBaseUrl() != after.activeBaseUrl() ||
            before.activeModel() != after.activeModel() ||
            before.sourceLanguage != after.sourceLanguage ||
            before.targetLanguage != after.targetLanguage ||
            before.customStylePrompt != after.customStylePrompt ||
            before.autoLanguageRulesEnabled != after.autoLanguageRulesEnabled ||
            before.autoLanguageRules != after.autoLanguageRules

    private companion object {
        const val KEY_CONFIG = "config"
    }
}
