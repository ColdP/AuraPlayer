package btm.m.auraplayer.sing

import android.content.Context
import btm.m.auraplayer.spatialcover.SpatialModelResource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class AuraSingArch {
    MDX_NET,
    HTDEMUCS,
}

enum class AuraSingPrimaryStem {
    VOCALS,
    INSTRUMENTAL,
}

enum class AuraSingModel(
    val id: String,
    val labelKey: String,
    val fallbackLabel: String,
    val summaryKey: String,
    val fallbackSummary: String,
    val arch: AuraSingArch,
    val primaryStem: AuraSingPrimaryStem = AuraSingPrimaryStem.VOCALS,
    val nFft: Int = 0,
    val hopLength: Int = 1024,
    val dimF: Int = 0,
    val dimT: Int = 256,
    val compensate: Float = 1f,
    val resource: SpatialModelResource,
) {
    KUIELAB_B_VOCALS(
        id = "kuielab_b_vocals",
        labelKey = "aura_sing_model_kuielab_b",
        fallbackLabel = "KUIELab B Vocals (极速 · 28 MB)",
        summaryKey = "aura_sing_model_kuielab_b_summary",
        fallbackSummary = "推荐首选。极速轻量级 MDX-Net 人声分离模型，分离速度约快 6~10 倍，仅约 28 MB",
        arch = AuraSingArch.MDX_NET,
        primaryStem = AuraSingPrimaryStem.VOCALS,
        nFft = 6144,
        hopLength = 1024,
        dimF = 2048,
        dimT = 256,
        compensate = 1.035f,
        resource = SpatialModelResource(
            id = "aura_sing_kuielab_b_vocals",
            fileName = "kuielab_b_vocals.onnx",
            url = "https://coldp.github.io/hyperchanger-updateinfo/models/kuielab_b_vocals.onnx",
            sizeBytes = 29_703_204L,
            sha256 = "9b7dcb9d878acb0f3e64ff3fd27750faae96577013f6d50f5996875bf4250713",
            subDir = "aura_sing_models",
            isArchive = false,
        ),
    ),
    UVR_MDX_INST_HQ_4(
        id = "uvr_mdx_inst_hq_4",
        labelKey = "aura_sing_model_inst_hq4",
        fallbackLabel = "UVR Inst HQ 4 (快速高清 · 56 MB)",
        summaryKey = "aura_sing_model_inst_hq4_summary",
        fallbackSummary = "轻量伴奏优先 MDX-Net 模型，速度大幅快于 HTDemucs，伴奏纯净自然（约 56 MB）",
        arch = AuraSingArch.MDX_NET,
        primaryStem = AuraSingPrimaryStem.INSTRUMENTAL,
        nFft = 5120,
        hopLength = 1024,
        dimF = 2560,
        dimT = 256,
        compensate = 1.019f,
        resource = SpatialModelResource(
            id = "aura_sing_uvr_inst_hq4",
            fileName = "UVR-MDX-NET-Inst_HQ_4.onnx",
            url = "https://coldp.github.io/hyperchanger-updateinfo/models/UVR-MDX-NET-Inst_HQ_4.onnx",
            sizeBytes = 59_074_342L,
            sha256 = "3c4b5b9b05090fdf238f38ba5046813982d50e2a652e9cb3324ea79720c3c9c8",
            subDir = "aura_sing_models",
            isArchive = false,
        ),
    ),
    KIM_VOCAL_2(
        id = "kim_vocal_2",
        labelKey = "aura_sing_model_kim_vocal2",
        fallbackLabel = "Kim Vocal 2 (均衡高质 · 64 MB)",
        summaryKey = "aura_sing_model_kim_vocal2_summary",
        fallbackSummary = "高人声还原度 MDX-Net 模型，兼顾分离细腻度与较快处理速度（约 64 MB）",
        arch = AuraSingArch.MDX_NET,
        primaryStem = AuraSingPrimaryStem.VOCALS,
        nFft = 7680,
        hopLength = 1024,
        dimF = 3072,
        dimT = 256,
        compensate = 1.009f,
        resource = SpatialModelResource(
            id = "aura_sing_kim_vocal_2",
            fileName = "Kim_Vocal_2.onnx",
            url = "https://coldp.github.io/hyperchanger-updateinfo/models/Kim_Vocal_2.onnx",
            sizeBytes = 66_759_214L,
            sha256 = "ce74ef3b6a6024ce44211a07be9cf8bc6d87728cc852a68ab34eb8e58cde9c8b",
            subDir = "aura_sing_models",
            isArchive = false,
        ),
    ),
    HTDEMUCS_INT8(
        id = "htdemucs_int8",
        labelKey = "aura_sing_model_int8",
        fallbackLabel = "HTDemucs INT8 (低内存 · 94 MB)",
        summaryKey = "aura_sing_model_int8_summary",
        fallbackSummary = "内存优化量化版四轨 Transformer 模型，耗时较长，适合高复杂编曲（约 94 MB）",
        arch = AuraSingArch.HTDEMUCS,
        resource = SpatialModelResource(
            id = "aura_sing_htdemucs_int8",
            fileName = "htdemucs-dft-int8-fp16-chunked.onnx",
            url = "https://coldp.github.io/hyperchanger-updateinfo/models/htdemucs-dft-int8-fp16-chunked.onnx",
            sizeBytes = 98_511_195L,
            sha256 = "4a5fb5eb9a80250cf5b943c49e7b934631729abdc91915b02200f732dcb372e9",
            subDir = "aura_sing_models",
            isArchive = false,
        ),
    ),
    HTDEMUCS_FP16(
        id = "htdemucs_fp16",
        labelKey = "aura_sing_model_fp16",
        fallbackLabel = "HTDemucs FP16 (高精 · 124 MB)",
        summaryKey = "aura_sing_model_fp16_summary",
        fallbackSummary = "半精度四轨 Transformer 模型，音轨细节最高但耗时最久（下载 ZIP 约 78 MB，解压后约 124 MB）",
        arch = AuraSingArch.HTDEMUCS,
        resource = SpatialModelResource(
            id = "aura_sing_htdemucs_fp16",
            fileName = "htdemucs-dft-fp16-transparent.onnx",
            url = "https://coldp.github.io/hyperchanger-updateinfo/models/htdemucs-dft-fp16-transparent.zip",
            sizeBytes = 81_691_224L,
            sha256 = "b708bc7da8ec740890a9aa80a5ab9e7a8d89c586d1569d9a63be1c28bc3a8c8a",
            subDir = "aura_sing_models",
            isArchive = true,
            archiveEntryName = "htdemucs-dft-fp16-transparent.onnx",
            extractedSizeBytes = 129_981_572L,
            extractedSha256 = "10d0d51c78f5ed1bc8e2c7b47f816169e810d78e4ebd510369c4a6a2b362221e",
        ),
    ),
    ;

    companion object {
        val DEFAULT: AuraSingModel = KUIELAB_B_VOCALS
        fun byId(id: String?): AuraSingModel = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

data class AuraSingConfig(
    val enabled: Boolean = false,
    val model: AuraSingModel = AuraSingModel.DEFAULT,
    val hardwareAcceleration: Boolean = true,
)

object AuraSingConfigCodec {
    fun encode(config: AuraSingConfig): String = JSONObject().apply {
        put("enabled", config.enabled)
        put("model", config.model.id)
        put("hardwareAcceleration", config.hardwareAcceleration)
    }.toString()

    fun decode(raw: String?): AuraSingConfig {
        if (raw.isNullOrBlank()) return AuraSingConfig()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return AuraSingConfig()
        return AuraSingConfig(
            enabled = obj.optBoolean("enabled", false),
            model = AuraSingModel.byId(obj.optString("model")),
            hardwareAcceleration = obj.optBoolean("hardwareAcceleration", true),
        )
    }
}

class AuraSingSettings(context: Context) {
    private val prefs = context.getSharedPreferences("aura.sing.v1", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(AuraSingConfigCodec.decode(prefs.getString("config", null)))
    val state = mutable.asStateFlow()

    val value: AuraSingConfig
        get() = mutable.value

    fun setEnabled(enabled: Boolean) {
        update { it.copy(enabled = enabled) }
    }

    fun setModel(model: AuraSingModel) {
        update { it.copy(model = model) }
    }

    fun setHardwareAcceleration(enabled: Boolean) {
        update { it.copy(hardwareAcceleration = enabled) }
    }

    @Synchronized
    private fun update(block: (AuraSingConfig) -> AuraSingConfig) {
        val next = block(mutable.value)
        if (next == mutable.value) return
        mutable.value = next
        prefs.edit().putString("config", AuraSingConfigCodec.encode(next)).apply()
    }
}
