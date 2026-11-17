/*
 * Adapted from decent-player (https://github.com/Ma145/decent-player)
 * Copyright (c) 2026 Marcelo Silva — MIT License
 * Modified for Aura Player (btm.m.auraplayer): UAC1/UAC2 Feature Unit (0x06) parsing,
 * playback topology tracing, and hardware volume metadata.
 */

package btm.m.auraplayer.audio.usb

import android.hardware.usb.UsbDeviceConnection

data class UsbAltFormat(
    val altSetting: Int,
    val channels: Int,
    val subslotBytes: Int,
    val bitResolution: Int,
) {
    /** Wire container bit depth sent over USB isochronous packets (16, 24, or 32). */
    val wireBitDepth: Int
        get() = (subslotBytes * 8).coerceIn(16, 32)
}

data class UsbFeatureUnitInfo(
    val unitId: Int,
    val sourceId: Int,
    val supportsMasterMute: Boolean,
    val supportsMasterVolume: Boolean,
    val channelVolumeCount: Int,
) {
    val supportsAnyVolume: Boolean
        get() = supportsMasterVolume || channelVolumeCount > 0
}

data class UsbVolumeRange(
    val minDb256: Int,
    val maxDb256: Int,
    val resDb256: Int,
) {
    /** Map normalized 0..1 slider over the upper 60 dB perceptual span for natural hearing response. */
    fun toDb256(normalized: Float): Short {
        val clamped = normalized.coerceIn(0f, 1f)
        if (clamped <= 0.001f) return minDb256.coerceIn(-32767, 32767).toShort()
        val effectiveMin = maxOf(minDb256, maxDb256 - 60 * 256)
        val raw = effectiveMin + ((maxDb256 - effectiveMin) * clamped).toInt()
        val step = resDb256.coerceAtLeast(1)
        val snapped = effectiveMin + ((raw - effectiveMin) / step) * step
        return snapped.coerceIn(minDb256, maxDb256).coerceIn(-32767, 32767).toShort()
    }
}

data class ParsedUsbAudioDescriptors(
    val isUac1: Boolean,
    val clockSourceId: Int,
    val altFormats: List<UsbAltFormat>,
    val playbackFeatureUnit: UsbFeatureUnitInfo? = null,
)

data class UsbAudioDeviceInfo(
    val connection: UsbDeviceConnection,
    val fd: Int,
    val deviceId: Int,
    val deviceName: String,
    val controlInterfaceId: Int,
    val interfaceId: Int,
    val endpointOutAddress: Int,
    val endpointFeedbackAddress: Int,
    val maxPacketSize: Int,
    val isUac1: Boolean,
    val clockSourceId: Int,
    val altFormats: List<UsbAltFormat>,
    val bestAltSetting: Int,
    val bestWireBitDepth: Int,
    val bestChannels: Int,
    val featureUnit: UsbFeatureUnitInfo? = null,
    val volumeRange: UsbVolumeRange? = null,
)

/**
 * Pure USB descriptor parser supporting UAC 1.0 and UAC 2.0 AudioControl / AudioStreaming
 * descriptors, including Feature Unit (0x06) discovery along the playback output path.
 */
internal object UsbDescriptorParser {
    private const val DESCRIPTOR_TYPE_INTERFACE = 0x04
    private const val DESCRIPTOR_TYPE_CS_INTERFACE = 0x24
    private const val USB_CLASS_AUDIO = 1
    private const val SUBCLASS_AUDIO_CONTROL = 1
    private const val SUBCLASS_AUDIO_STREAMING = 2

    private const val AC_SUBTYPE_HEADER = 0x01
    private const val AC_SUBTYPE_OUTPUT_TERMINAL = 0x03
    private const val AC_SUBTYPE_MIXER_UNIT = 0x04
    private const val AC_SUBTYPE_SELECTOR_UNIT = 0x05
    private const val AC_SUBTYPE_FEATURE_UNIT = 0x06
    private const val AC_SUBTYPE_CLOCK_SOURCE = 0x0A
    private const val AS_SUBTYPE_FORMAT_TYPE = 0x02

    private const val TERMINAL_USB_STREAMING = 0x0101

    private data class OutputTerminal(val terminalId: Int, val terminalType: Int, val sourceId: Int)

    fun parse(raw: ByteArray, targetStreamingInterfaceId: Int): ParsedUsbAudioDescriptors {
        var isUac1 = false
        var clockSourceId = -1
        val formats = mutableListOf<UsbAltFormat>()
        val featureUnits = mutableListOf<UsbFeatureUnitInfo>()
        val outputTerminals = mutableListOf<OutputTerminal>()
        val unitUpstream = mutableMapOf<Int, List<Int>>()

        var i = 0
        var inAudioControl = false
        var inTargetStreaming = false
        var currentAlt = 0

        while (i + 1 < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2 || i + bLength > raw.size) break
            val bDescriptorType = raw[i + 1].toInt() and 0xFF

            if (bDescriptorType == DESCRIPTOR_TYPE_INTERFACE && bLength >= 9) {
                val ifaceId = raw[i + 2].toInt() and 0xFF
                val altSetting = raw[i + 3].toInt() and 0xFF
                val ifaceClass = raw[i + 5].toInt() and 0xFF
                val ifaceSubClass = raw[i + 6].toInt() and 0xFF
                inAudioControl = ifaceClass == USB_CLASS_AUDIO && ifaceSubClass == SUBCLASS_AUDIO_CONTROL
                inTargetStreaming = ifaceClass == USB_CLASS_AUDIO &&
                    ifaceSubClass == SUBCLASS_AUDIO_STREAMING &&
                    ifaceId == targetStreamingInterfaceId
                if (inTargetStreaming) {
                    currentAlt = altSetting
                }
            } else if (inAudioControl && bDescriptorType == DESCRIPTOR_TYPE_CS_INTERFACE && bLength >= 3) {
                val subtype = raw[i + 2].toInt() and 0xFF
                when (subtype) {
                    AC_SUBTYPE_HEADER -> if (bLength >= 5) {
                        val bcdAdc = (raw[i + 3].toInt() and 0xFF) or ((raw[i + 4].toInt() and 0xFF) shl 8)
                        isUac1 = bcdAdc < 0x0200
                    }
                    AC_SUBTYPE_CLOCK_SOURCE -> if (bLength >= 5 && clockSourceId < 0) {
                        clockSourceId = raw[i + 3].toInt() and 0xFF
                    }
                    AC_SUBTYPE_OUTPUT_TERMINAL -> {
                        val minLen = if (isUac1) 9 else 12
                        if (bLength >= minLen) {
                            val termId = raw[i + 3].toInt() and 0xFF
                            val termType = (raw[i + 4].toInt() and 0xFF) or ((raw[i + 5].toInt() and 0xFF) shl 8)
                            val srcId = raw[i + 7].toInt() and 0xFF
                            outputTerminals += OutputTerminal(termId, termType, srcId)
                            unitUpstream[termId] = listOf(srcId)
                        }
                    }
                    AC_SUBTYPE_MIXER_UNIT, AC_SUBTYPE_SELECTOR_UNIT -> if (bLength >= 6) {
                        val unitId = raw[i + 3].toInt() and 0xFF
                        val nrInPins = raw[i + 4].toInt() and 0xFF
                        if (nrInPins > 0 && 5 + nrInPins <= bLength) {
                            unitUpstream[unitId] = List(nrInPins) { pin -> raw[i + 5 + pin].toInt() and 0xFF }
                        }
                    }
                    AC_SUBTYPE_FEATURE_UNIT -> {
                        val parsedFu = parseFeatureUnit(raw, i, bLength, isUac1)
                        if (parsedFu != null) {
                            featureUnits += parsedFu
                            unitUpstream[parsedFu.unitId] = listOf(parsedFu.sourceId)
                        }
                    }
                }
            } else if (inTargetStreaming && currentAlt > 0 && bDescriptorType == DESCRIPTOR_TYPE_CS_INTERFACE && bLength >= 6) {
                val subtype = raw[i + 2].toInt() and 0xFF
                if (subtype == AS_SUBTYPE_FORMAT_TYPE) {
                    val formatType = raw[i + 3].toInt() and 0xFF
                    if (formatType == 1) {
                        if (!isUac1 && bLength == 6) {
                            val subslot = raw[i + 4].toInt() and 0xFF
                            val bits = raw[i + 5].toInt() and 0xFF
                            if (subslot in 2..4 && bits in 16..32) {
                                formats += UsbAltFormat(currentAlt, 2, subslot, bits)
                            }
                        } else if (bLength >= 8) {
                            val channels = raw[i + 4].toInt() and 0xFF
                            val subframe = raw[i + 5].toInt() and 0xFF
                            val bits = raw[i + 6].toInt() and 0xFF
                            if (channels in 1..8 && subframe in 2..4 && bits in 16..32) {
                                formats += UsbAltFormat(currentAlt, channels, subframe, bits)
                            }
                        }
                    }
                }
            }

            i += bLength
        }

        val playbackFeatureUnit = resolvePlaybackFeatureUnit(featureUnits, outputTerminals, unitUpstream)

        return ParsedUsbAudioDescriptors(
            isUac1 = isUac1,
            clockSourceId = clockSourceId,
            altFormats = formats.distinctBy { it.altSetting },
            playbackFeatureUnit = playbackFeatureUnit,
        )
    }

    private fun parseFeatureUnit(raw: ByteArray, offset: Int, bLength: Int, isUac1: Boolean): UsbFeatureUnitInfo? {
        if (!isUac1) {
            // UAC 2.0 Feature Unit: [3]=bUnitID, [4]=bSourceID, [5..8]=bmaControls(0) (4 bytes/ch), ..., [len-1]=iFeature
            if (bLength < 10) return null
            val unitId = raw[offset + 3].toInt() and 0xFF
            val sourceId = raw[offset + 4].toInt() and 0xFF
            val ctrl0 = raw[offset + 5].toInt() and 0xFF
            val masterMute = (ctrl0 and 0x03) == 0x03
            val masterVol = (ctrl0 and 0x0C) == 0x0C
            val chSlots = ((bLength - 6) / 4 - 1).coerceAtLeast(0)
            var chVolCount = 0
            for (ch in 1..chSlots) {
                val pos = offset + 5 + ch * 4
                if (pos < offset + bLength) {
                    val chCtrl = raw[pos].toInt() and 0xFF
                    if ((chCtrl and 0x0C) == 0x0C) chVolCount++
                }
            }
            return UsbFeatureUnitInfo(unitId, sourceId, masterMute, masterVol, chVolCount)
        } else {
            // UAC 1.0 Feature Unit: [3]=bUnitID, [4]=bSourceID, [5]=bControlSize, [6..]=bmaControls(0..)
            if (bLength < 8) return null
            val unitId = raw[offset + 3].toInt() and 0xFF
            val sourceId = raw[offset + 4].toInt() and 0xFF
            val ctrlSize = (raw[offset + 5].toInt() and 0xFF).coerceAtLeast(1)
            val ctrl0 = raw[offset + 6].toInt() and 0xFF
            val masterMute = (ctrl0 and 0x01) != 0
            val masterVol = (ctrl0 and 0x02) != 0
            val chSlots = ((bLength - 7) / ctrlSize - 1).coerceAtLeast(0)
            var chVolCount = 0
            for (ch in 1..chSlots) {
                val pos = offset + 6 + ch * ctrlSize
                if (pos < offset + bLength) {
                    val chCtrl = raw[pos].toInt() and 0xFF
                    if ((chCtrl and 0x02) != 0) chVolCount++
                }
            }
            return UsbFeatureUnitInfo(unitId, sourceId, masterMute, masterVol, chVolCount)
        }
    }

    private fun resolvePlaybackFeatureUnit(
        featureUnits: List<UsbFeatureUnitInfo>,
        outputTerminals: List<OutputTerminal>,
        unitUpstream: Map<Int, List<Int>>,
    ): UsbFeatureUnitInfo? {
        if (featureUnits.isEmpty()) return null
        val fuById = featureUnits.associateBy { it.unitId }
        val playbackTerminals = outputTerminals.filter { it.terminalType != TERMINAL_USB_STREAMING }
        for (ot in playbackTerminals) {
            val visited = HashSet<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(ot.sourceId)
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                if (!visited.add(id)) continue
                val fu = fuById[id]
                if (fu != null && fu.supportsAnyVolume) return fu
                unitUpstream[id]?.forEach(queue::addLast)
            }
        }
        return featureUnits.firstOrNull { it.supportsAnyVolume }
    }

    fun selectBestFormat(
        formats: List<UsbAltFormat>,
        preferredBitDepth: Int,
        preferredChannels: Int = 2,
    ): UsbAltFormat {
        val channelCandidates = formats.filter { it.channels == preferredChannels }
            .ifEmpty { formats.filter { it.channels == 2 } }
            .ifEmpty { formats }
        if (channelCandidates.isEmpty()) {
            return UsbAltFormat(altSetting = 1, channels = 2, subslotBytes = 2, bitResolution = 16)
        }
        return channelCandidates
            .filter { it.bitResolution == preferredBitDepth }
            .maxByOrNull { it.subslotBytes }
            ?: channelCandidates
                .filter { it.bitResolution > preferredBitDepth }
                .minWithOrNull(compareBy<UsbAltFormat> { it.bitResolution }.thenBy { it.subslotBytes })
            ?: channelCandidates.maxWithOrNull(compareBy<UsbAltFormat> { it.bitResolution }.thenBy { it.subslotBytes })!!
    }
}

sealed interface UsbExclusiveStatus {
    data object Disabled : UsbExclusiveStatus
    data object NoDevice : UsbExclusiveStatus
    data class NeedsPermission(val deviceName: String) : UsbExclusiveStatus
    data class Ready(val deviceName: String) : UsbExclusiveStatus
    data class Active(
        val deviceName: String,
        val sampleRate: Int,
        val bitDepth: Int,
        val hardwareVolume: Boolean = false,
    ) : UsbExclusiveStatus
    data class Fallback(val deviceName: String, val reason: String) : UsbExclusiveStatus
}
