/*
 * Adapted from decent-player (https://github.com/Ma145/decent-player)
 * Copyright (c) 2026 Marcelo Silva — MIT License
 * Modified for Aura Player (btm.m.auraplayer): playback OUT endpoint filtering,
 * UAC1/UAC2 rate control, Feature Unit (0x06) hardware gain control, and detach invalidation.
 */

package btm.m.auraplayer.audio.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

class UsbAudioDevice private constructor(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var currentDevice: UsbDevice? = null
    private var controlInterface: UsbInterface? = null
    private var streamingInterface: UsbInterface? = null
    private var cachedDeviceInfo: UsbAudioDeviceInfo? = null

    @Volatile
    private var permissionRequestPending = false

    fun findUsbAudioDevice(): UsbDevice? {
        for (device in usbManager.deviceList.values) {
            if (hasPlaybackStreamingInterface(device)) {
                return device
            }
        }
        return null
    }

    fun hasPlaybackStreamingInterface(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 2 &&
                findIsoOutEndpoint(iface) != null
            ) {
                return true
            }
        }
        return false
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun requestPermission(device: UsbDevice, callback: (Boolean) -> Unit) {
        if (usbManager.hasPermission(device)) {
            callback(true)
            return
        }
        synchronized(this) {
            if (permissionRequestPending) return
            permissionRequestPending = true
        }

        val action = context.packageName + ACTION_USB_PERMISSION_SUFFIX
        val intent = Intent(action).setPackage(context.packageName)
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != action) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                synchronized(this@UsbAudioDevice) {
                    permissionRequestPending = false
                }
                runCatching { context.unregisterReceiver(this) }
                callback(granted)
            }
        }

        val filter = IntentFilter(action)
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        runCatching {
            usbManager.requestPermission(device, permissionIntent)
        }.onFailure {
            synchronized(this) { permissionRequestPending = false }
            runCatching { context.unregisterReceiver(receiver) }
            callback(false)
        }
    }

    @Synchronized
    fun openDevice(device: UsbDevice): UsbAudioDeviceInfo? {
        val cached = cachedDeviceInfo
        if (cached != null && connection != null && currentDevice?.deviceId == device.deviceId) {
            return cached
        }
        closeDevice()

        val conn = usbManager.openDevice(device) ?: run {
            Log.e(TAG, "Failed to open USB device: ${device.productName}")
            return null
        }

        var selectedStreamIface: UsbInterface? = null
        var endpointOut: UsbEndpoint? = null
        var endpointFeedback: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO && iface.interfaceSubclass == 2) {
                val outEp = findIsoOutEndpoint(iface)
                if (outEp != null) {
                    selectedStreamIface = iface
                    endpointOut = outEp
                    endpointFeedback = findIsoFeedbackEndpoint(iface)
                    break
                }
            }
        }

        if (selectedStreamIface == null || endpointOut == null) {
            Log.e(TAG, "No AudioStreaming playback interface with ISO OUT endpoint")
            conn.close()
            return null
        }

        val ctrlIface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 1 }

        if (ctrlIface != null && !conn.claimInterface(ctrlIface, true)) {
            Log.w(TAG, "Could not claim AudioControl interface ${ctrlIface.id}")
        }

        if (!conn.claimInterface(selectedStreamIface, true)) {
            Log.e(TAG, "Failed to claim AudioStreaming interface ${selectedStreamIface.id}")
            if (ctrlIface != null) runCatching { conn.releaseInterface(ctrlIface) }
            conn.close()
            return null
        }

        val zeroAlt = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    it.interfaceSubclass == 2 &&
                    it.id == selectedStreamIface.id &&
                    it.alternateSetting == 0
            }
        if (zeroAlt != null) {
            conn.setInterface(zeroAlt)
            Thread.sleep(60)
        }

        val parsed = UsbDescriptorParser.parse(conn.rawDescriptors ?: ByteArray(0), selectedStreamIface.id)
        val bestFormat = UsbDescriptorParser.selectBestFormat(parsed.altFormats, preferredBitDepth = 32)
        val bestAltIface = findAltInterface(device, selectedStreamIface.id, bestFormat.altSetting)
        val effectiveOutEp = bestAltIface?.let(::findIsoOutEndpoint) ?: endpointOut
        val effectiveFbEp = bestAltIface?.let(::findIsoFeedbackEndpoint) ?: endpointFeedback
        val ctrlId = ctrlIface?.id ?: 0
        val volRange = parsed.playbackFeatureUnit?.let { fu ->
            queryVolumeRange(conn, ctrlId, fu, parsed.isUac1)
        }

        connection = conn
        currentDevice = device
        controlInterface = ctrlIface
        streamingInterface = selectedStreamIface

        val info = UsbAudioDeviceInfo(
            connection = conn,
            fd = conn.fileDescriptor,
            deviceId = device.deviceId,
            deviceName = device.productName?.takeIf { it.isNotBlank() } ?: "USB DAC",
            controlInterfaceId = ctrlId,
            interfaceId = selectedStreamIface.id,
            endpointOutAddress = effectiveOutEp.address,
            endpointFeedbackAddress = effectiveFbEp?.address ?: -1,
            maxPacketSize = effectiveOutEp.maxPacketSize,
            isUac1 = parsed.isUac1,
            clockSourceId = parsed.clockSourceId,
            altFormats = parsed.altFormats,
            bestAltSetting = bestFormat.altSetting,
            bestWireBitDepth = bestFormat.wireBitDepth,
            bestChannels = bestFormat.channels,
            featureUnit = parsed.playbackFeatureUnit,
            volumeRange = volRange,
        )
        cachedDeviceInfo = info
        Log.i(
            TAG,
            "Opened USB DAC=${info.deviceName} uac1=${info.isUac1} clock=${info.clockSourceId} " +
                "fu=${info.featureUnit?.unitId ?: -1} volRange=$volRange iface=${info.interfaceId} " +
                "alt=${info.bestAltSetting} wireBits=${info.bestWireBitDepth} maxPkt=${info.maxPacketSize}",
        )
        return info
    }

    @Synchronized
    fun currentDeviceInfo(): UsbAudioDeviceInfo? = cachedDeviceInfo

    @Synchronized
    fun closeDevice() {
        val conn = connection
        if (conn != null) {
            streamingInterface?.let { runCatching { conn.setInterface(findZeroAlt(it.id) ?: it) } }
            streamingInterface?.let { runCatching { conn.releaseInterface(it) } }
            controlInterface?.let { runCatching { conn.releaseInterface(it) } }
            runCatching { conn.close() }
        }
        connection = null
        currentDevice = null
        controlInterface = null
        streamingInterface = null
        cachedDeviceInfo = null
    }

    @Synchronized
    fun invalidateIfDetached(detachedDevice: UsbDevice?): Boolean {
        val cur = currentDevice ?: return false
        if (detachedDevice == null || detachedDevice.deviceId == cur.deviceId) {
            closeDevice()
            return true
        }
        return false
    }

    @Synchronized
    fun setAltSetting(interfaceId: Int, altSetting: Int): Boolean {
        val conn = connection ?: return false
        val device = currentDevice ?: return false
        val iface = findAltInterface(device, interfaceId, altSetting) ?: return false
        return conn.setInterface(iface)
    }

    @Synchronized
    fun setSampleRate(info: UsbAudioDeviceInfo, sampleRateHz: Int): Boolean {
        val conn = connection ?: return false
        if (info.isUac1 || info.clockSourceId < 0) {
            val data3 = byteArrayOf(
                (sampleRateHz and 0xFF).toByte(),
                ((sampleRateHz shr 8) and 0xFF).toByte(),
                ((sampleRateHz shr 16) and 0xFF).toByte(),
            )
            val ret = conn.controlTransfer(
                0x22,
                0x01,
                0x0100,
                info.endpointOutAddress,
                data3,
                data3.size,
                1000,
            )
            if (ret >= 3) return true
            if (info.isUac1) return false
        }

        val data4 = byteArrayOf(
            (sampleRateHz and 0xFF).toByte(),
            ((sampleRateHz shr 8) and 0xFF).toByte(),
            ((sampleRateHz shr 16) and 0xFF).toByte(),
            ((sampleRateHz shr 24) and 0xFF).toByte(),
        )
        val candidates = if (info.clockSourceId > 0) intArrayOf(info.clockSourceId) else intArrayOf(0x05, 0x09, 0x0A, 0x0B, 0x0C, 0x28, 0x29)
        for (csId in candidates) {
            val wIndex = (csId shl 8) or (info.controlInterfaceId and 0xFF)
            val ret = conn.controlTransfer(
                0x21,
                0x01,
                0x0100,
                wIndex,
                data4,
                data4.size,
                1000,
            )
            if (ret >= 4) return true
        }
        return false
    }

    @Synchronized
    fun readClockValid(info: UsbAudioDeviceInfo): Boolean {
        if (info.isUac1 || info.clockSourceId <= 0) return true
        val conn = connection ?: return false
        val data = ByteArray(1)
        val wIndex = (info.clockSourceId shl 8) or (info.controlInterfaceId and 0xFF)
        val ret = conn.controlTransfer(0xA1, 0x01, 0x0200, wIndex, data, 1, 1000)
        return ret >= 1 && (data[0].toInt() and 0x01) == 1
    }

    /**
     * Adjusts the DAC's UAC Feature Unit (0x06) hardware gain via SET_CUR.
     * Returns true if at least one volume control transfer succeeded.
     */
    @Synchronized
    fun setHardwareVolume(info: UsbAudioDeviceInfo, normalizedVolume: Float): Boolean {
        val conn = connection ?: return false
        val fu = info.featureUnit ?: return false
        if (!fu.supportsAnyVolume) return false
        val range = info.volumeRange ?: UsbVolumeRange(-15360, 0, 256)
        val vol = normalizedVolume.coerceIn(0f, 1f)
        val wIndex = (fu.unitId shl 8) or (info.controlInterfaceId and 0xFF)

        if (fu.supportsMasterMute) {
            val muteBytes = byteArrayOf(if (vol <= 0.001f) 1 else 0)
            conn.controlTransfer(0x21, 0x01, 0x0100, wIndex, muteBytes, 1, 500)
        }

        val db256: Short = if (vol <= 0.001f) Short.MIN_VALUE else range.toDb256(vol)
        val volBytes = byteArrayOf(
            (db256.toInt() and 0xFF).toByte(),
            ((db256.toInt() shr 8) and 0xFF).toByte(),
        )

        var anySuccess = false
        if (fu.supportsMasterVolume) {
            val ret = conn.controlTransfer(0x21, 0x01, 0x0200, wIndex, volBytes, 2, 500)
            if (ret >= 2) anySuccess = true
        }
        if (!anySuccess && fu.channelVolumeCount > 0) {
            val channels = fu.channelVolumeCount.coerceIn(1, 2)
            for (cn in 1..channels) {
                val ret = conn.controlTransfer(0x21, 0x01, 0x0200 or cn, wIndex, volBytes, 2, 500)
                if (ret >= 2) anySuccess = true
            }
        }
        return anySuccess
    }

    private fun queryVolumeRange(
        conn: UsbDeviceConnection,
        ctrlInterfaceId: Int,
        fu: UsbFeatureUnitInfo,
        isUac1: Boolean,
    ): UsbVolumeRange {
        val wIndex = (fu.unitId shl 8) or (ctrlInterfaceId and 0xFF)
        val cn = if (fu.supportsMasterVolume) 0 else 1
        val wValue = 0x0200 or cn

        if (!isUac1) {
            val buf = ByteArray(64)
            val ret = conn.controlTransfer(0xA1, 0x02 /* RANGE */, wValue, wIndex, buf, buf.size, 800)
            if (ret >= 8) {
                val subRanges = (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)
                if (subRanges >= 1) {
                    val min = readSigned16(buf, 2)
                    val max = readSigned16(buf, 4)
                    val res = readSigned16(buf, 6).coerceAtLeast(1)
                    if (max > min) return UsbVolumeRange(min, max, res)
                }
            }
        } else {
            val minBuf = ByteArray(2)
            val maxBuf = ByteArray(2)
            val resBuf = ByteArray(2)
            val rMin = conn.controlTransfer(0xA1, 0x82 /* GET_MIN */, wValue, wIndex, minBuf, 2, 800)
            val rMax = conn.controlTransfer(0xA1, 0x83 /* GET_MAX */, wValue, wIndex, maxBuf, 2, 800)
            val rRes = conn.controlTransfer(0xA1, 0x84 /* GET_RES */, wValue, wIndex, resBuf, 2, 800)
            if (rMin >= 2 && rMax >= 2) {
                val min = readSigned16(minBuf, 0)
                val max = readSigned16(maxBuf, 0)
                val res = if (rRes >= 2) readSigned16(resBuf, 0).coerceAtLeast(1) else 256
                if (max > min) return UsbVolumeRange(min, max, res)
            }
        }
        return UsbVolumeRange(-15360, 0, 256)
    }

    private fun readSigned16(buf: ByteArray, offset: Int): Int {
        val low = buf[offset].toInt() and 0xFF
        val high = buf[offset + 1].toInt() and 0xFF
        return ((high shl 8) or low).toShort().toInt()
    }

    fun selectAltForFormat(info: UsbAudioDeviceInfo, preferredBitDepth: Int, preferredChannels: Int): UsbAltFormat =
        UsbDescriptorParser.selectBestFormat(info.altFormats, preferredBitDepth, preferredChannels)

    fun resolveEndpointsForAlt(altSetting: Int): Pair<Int, Int>? {
        val device = currentDevice ?: return null
        val streamId = streamingInterface?.id ?: return null
        val iface = findAltInterface(device, streamId, altSetting) ?: return null
        val outEp = findIsoOutEndpoint(iface) ?: return null
        val fbEp = findIsoFeedbackEndpoint(iface)
        return Pair(outEp.address, fbEp?.address ?: -1)
    }

    fun resolveMaxPacketForAlt(altSetting: Int): Int? {
        val device = currentDevice ?: return null
        val streamId = streamingInterface?.id ?: return null
        val iface = findAltInterface(device, streamId, altSetting) ?: return null
        return findIsoOutEndpoint(iface)?.maxPacketSize
    }

    private fun findZeroAlt(interfaceId: Int): UsbInterface? {
        val device = currentDevice ?: return null
        return findAltInterface(device, interfaceId, 0)
    }

    private fun findAltInterface(device: UsbDevice, interfaceId: Int, altSetting: Int): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 2 &&
                iface.id == interfaceId &&
                iface.alternateSetting == altSetting
            ) {
                return iface
            }
        }
        return null
    }

    private fun findIsoOutEndpoint(iface: UsbInterface): UsbEndpoint? {
        for (e in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(e)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && ep.direction == UsbConstants.USB_DIR_OUT) {
                return ep
            }
        }
        return null
    }

    private fun findIsoFeedbackEndpoint(iface: UsbInterface): UsbEndpoint? {
        for (e in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(e)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && ep.direction == UsbConstants.USB_DIR_IN) {
                return ep
            }
        }
        return null
    }

    companion object {
        private const val TAG = "UsbAudioDevice"
        private const val ACTION_USB_PERMISSION_SUFFIX = ".USB_AUDIO_PERMISSION"

        @Volatile
        private var instance: UsbAudioDevice? = null

        fun getInstance(context: Context): UsbAudioDevice {
            return instance ?: synchronized(this) {
                instance ?: UsbAudioDevice(context.applicationContext).also { instance = it }
            }
        }
    }
}
