// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class MotionPhotoExporterTest {

    @Test
    fun detectVendorProtocolMapsMajorOemBrands() {
        assertEquals(MotionPhotoVendorProtocol.XIAOMI, MotionPhotoExporter.detectVendorProtocol("Xiaomi", "Redmi"))
        assertEquals(MotionPhotoVendorProtocol.XIAOMI, MotionPhotoExporter.detectVendorProtocol("Xiaomi", "POCO"))
        assertEquals(MotionPhotoVendorProtocol.OPLUS, MotionPhotoExporter.detectVendorProtocol("OPPO", "OPPO"))
        assertEquals(MotionPhotoVendorProtocol.OPLUS, MotionPhotoExporter.detectVendorProtocol("OnePlus", "OnePlus"))
        assertEquals(MotionPhotoVendorProtocol.OPLUS, MotionPhotoExporter.detectVendorProtocol("realme", "realme"))
        assertEquals(MotionPhotoVendorProtocol.VIVO, MotionPhotoExporter.detectVendorProtocol("vivo", "vivo"))
        assertEquals(MotionPhotoVendorProtocol.VIVO, MotionPhotoExporter.detectVendorProtocol("vivo", "iQOO"))
        assertEquals(MotionPhotoVendorProtocol.SAMSUNG, MotionPhotoExporter.detectVendorProtocol("samsung", "samsung"))
        assertEquals(MotionPhotoVendorProtocol.GOOGLE_STANDARD, MotionPhotoExporter.detectVendorProtocol("Google", "Pixel"))
        assertEquals(MotionPhotoVendorProtocol.GOOGLE_STANDARD, MotionPhotoExporter.detectVendorProtocol("HONOR", "HONOR"))
    }

    @Test
    fun motionPhotoDisplayNameUsesVendorConventions() {
        assertEquals(
            "MVIMG_Test_Album_100.jpg",
            MotionPhotoExporter.motionPhotoDisplayName("Test Album", MotionPhotoVendorProtocol.XIAOMI, 100L),
        )
        assertEquals(
            "IMG_Test_Album_100.jpg",
            MotionPhotoExporter.motionPhotoDisplayName("Test Album", MotionPhotoVendorProtocol.OPLUS, 100L),
        )
        assertEquals(
            "IMG_Test_Album_100.jpg",
            MotionPhotoExporter.motionPhotoDisplayName("Test Album", MotionPhotoVendorProtocol.VIVO, 100L),
        )
        assertEquals(
            "Test_Album_100.jpg",
            MotionPhotoExporter.motionPhotoDisplayName("Test Album", MotionPhotoVendorProtocol.SAMSUNG, 100L),
        )
        assertEquals(
            "Test_Album_100.MP.jpg",
            MotionPhotoExporter.motionPhotoDisplayName("Test Album", MotionPhotoVendorProtocol.GOOGLE_STANDARD, 100L),
        )
    }

    @Test
    fun buildVendorXmpIncludesProtocolSpecificNamespacesAndAttributes() {
        val xiaomi = MotionPhotoExporter.buildVendorXmp(MotionPhotoVendorProtocol.XIAOMI, 123456L)
        assertTrue(xiaomi.contains("xmlns:MiCamera=\"http://ns.xiaomi.com/photos/1.0/camera/\""))
        assertTrue(xiaomi.contains("<MiCamera:XMPMeta>Xiaomi/HyperOS LivePhoto</MiCamera:XMPMeta>"))
        assertTrue(xiaomi.contains("GCamera:MicroVideoOffset=\"123456\""))
        assertTrue(xiaomi.contains("Item:Length=\"123456\""))

        val oplus = MotionPhotoExporter.buildVendorXmp(MotionPhotoVendorProtocol.OPLUS, 234567L)
        assertTrue(oplus.contains("xmlns:OpCamera=\"http://ns.oplus.com/photos/1.0/camera/\""))
        assertTrue(oplus.contains("OpCamera:MotionPhotoOwner=\"oplus\""))
        assertTrue(oplus.contains("OpCamera:OLivePhotoVersion=\"2\""))
        assertTrue(oplus.contains("OpCamera:VideoLength=\"234567\""))

        val vivo = MotionPhotoExporter.buildVendorXmp(MotionPhotoVendorProtocol.VIVO, 345678L)
        assertTrue(vivo.contains("xmlns:VCamera=\"http://ns.vivo.com/photos/1.0/camera/\""))
        assertTrue(vivo.contains("<VCamera:VMotionPhotoVersion>1</VCamera:VMotionPhotoVersion>"))
        assertTrue(vivo.contains("GCamera:MicroVideoOffset=\"345678\""))

        val samsung = MotionPhotoExporter.buildVendorXmp(MotionPhotoVendorProtocol.SAMSUNG, 456789L)
        assertTrue(samsung.contains("Item:Length=\"456789\""))
        assertTrue(samsung.contains("Item:Padding=\"32\""))
    }

    @Test
    fun stripAndInjectXmpPreservesJpegStructureAndReplacesOldXmp() {
        val oldXmpHeader = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(StandardCharsets.US_ASCII)
        val oldXmpBody = "<x:xmpmeta>old</x:xmpmeta>".toByteArray(StandardCharsets.UTF_8)
        val oldSegLen = 2 + oldXmpHeader.size + oldXmpBody.size
        val minimalJpegWithOldXmp = ByteArrayOutputStream().apply {
            write(0xFF); write(0xD8) // SOI
            write(0xFF); write(0xE1) // XMP APP1
            write((oldSegLen ushr 8) and 0xFF); write(oldSegLen and 0xFF)
            write(oldXmpHeader); write(oldXmpBody)
            write(0xFF); write(0xDA) // SOS
            write(0x00); write(0x04); write(0x00); write(0x00)
            write(0x11); write(0x22)
            write(0xFF); write(0xD9) // EOI
            write("TRAILING_JUNK".toByteArray(StandardCharsets.US_ASCII))
        }.toByteArray()

        val cleaned = MotionPhotoExporter.stripXmpAndTrailingBytesFromJpeg(minimalJpegWithOldXmp)
        assertNotNull(cleaned)
        val cleanedText = String(cleaned!!, StandardCharsets.US_ASCII)
        assertTrue(!cleanedText.contains("http://ns.adobe.com/xap/1.0/"))
        assertTrue(!cleanedText.contains("TRAILING_JUNK"))
        assertEquals(0xFF.toByte(), cleaned[cleaned.size - 2])
        assertEquals(0xD9.toByte(), cleaned[cleaned.size - 1])

        val xmp = MotionPhotoExporter.buildVendorXmp(MotionPhotoVendorProtocol.GOOGLE_STANDARD, 500L)
        val injected = MotionPhotoExporter.injectXmpApp1IntoJpeg(cleaned, xmp)
        assertNotNull(injected)
        val injectedStr = String(injected!!, StandardCharsets.UTF_8)
        assertTrue(injectedStr.contains("GCamera:MotionPhoto=\"1\""))
        assertEquals(0xFF.toByte(), injected[0])
        assertEquals(0xD8.toByte(), injected[1])
        assertEquals(0xFF.toByte(), injected[injected.size - 2])
        assertEquals(0xD9.toByte(), injected[injected.size - 1])
    }

    @Test
    fun samsungSefBlocksEncodeMotionPhotoDataAndTrailerOffsets() {
        val header = MotionPhotoExporter.buildSamsungDataFieldHeader()
        assertEquals(24, header.size)
        val hdrBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0.toShort(), hdrBuf.short)
        assertEquals(0x0A30.toShort(), hdrBuf.short)
        assertEquals(16, hdrBuf.int)
        val name = ByteArray(16).also { hdrBuf.get(it) }
        assertArrayEquals("MotionPhoto_Data".toByteArray(StandardCharsets.US_ASCII), name)

        val trailer = MotionPhotoExporter.buildSamsungSefTrailer(dataBlockLength = 1024L)
        assertEquals(32, trailer.size)
        assertEquals("SEFH", String(trailer, 0, 4, StandardCharsets.US_ASCII))
        assertEquals("SEFT", String(trailer, 28, 4, StandardCharsets.US_ASCII))
        val trBuf = ByteBuffer.wrap(trailer, 4, 24).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(106, trBuf.int)
        assertEquals(1, trBuf.int)
        assertEquals(0.toShort(), trBuf.short)
        assertEquals(0x0A30.toShort(), trBuf.short)
        assertEquals(1024, trBuf.int)
        assertEquals(1024, trBuf.int)
        assertEquals(20, trBuf.int)
    }
}
