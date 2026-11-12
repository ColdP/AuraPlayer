package btm.m.auraplayer.audio

import android.graphics.Bitmap
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** A compact single-preset envelope, independently implemented on ZXing's public API. */
object PresetQr {
    private const val PREFIX = "aura-dsp:1:"
    fun pack(json: String): String {
        require(json.toByteArray().size <= PresetCodec.MAX_BYTES)
        val bytes = ByteArrayOutputStream()
        DeflaterOutputStream(bytes).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
    }
    fun unpack(payload: String): String {
        require(payload.length <= PresetCodec.MAX_BYTES)
        if (!payload.startsWith(PREFIX)) return payload
        val packed = Base64.getUrlDecoder().decode(payload.removePrefix(PREFIX))
        return InflaterInputStream(packed.inputStream()).use { stream ->
            val bytes = stream.readNBytes(PresetCodec.MAX_BYTES + 1)
            require(bytes.size <= PresetCodec.MAX_BYTES)
            bytes.toString(Charsets.UTF_8)
        }
    }
    fun create(json: String): Bitmap {
        val matrix = QRCodeWriter().encode(pack(json), BarcodeFormat.QR_CODE, 768, 768,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))
        val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }
    fun read(bitmap: Bitmap): String {
        require(bitmap.width <= 2048 && bitmap.height <= 2048)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val binary = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))
        val result = MultiFormatReader().decode(binary, mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8"))
        return unpack(result.text)
    }
}
