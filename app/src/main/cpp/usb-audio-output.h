/*
 * Adapted from decent-player (https://github.com/Ma145/decent-player)
 * Copyright (c) 2026 Marcelo Silva — MIT License
 * Modified for Aura Player (btm.m.auraplayer): configurable URB ring depth (40/100/200ms),
 * UAC1/UAC2 rate scaling, full PCM bit-depth conversion matrix, and safety gain fallback.
 */

#pragma once

#include <atomic>
#include <cstdint>
#include <linux/usbdevice_fs.h>

/**
 * Number of isochronous packets per URB submission.
 * At USB high-speed (125us microframes), 8 packets = 1ms of audio.
 */
#define USB_AUDIO_PACKETS_PER_URB 8

/**
 * Maximum number of pre-allocated URBs in the ring buffer (up to 200 ms at high-speed).
 */
#define USB_AUDIO_MAX_URBS 200

/**
 * Max bytes per URB data buffer.
 */
#define USB_AUDIO_URB_BUFFER_SIZE 8192

struct UrbSlot {
    struct usbdevfs_urb *urb;
    uint8_t *buffer;
    int dataLength;
};

struct UsbAudioContext {
    int fd;
    int interfaceId;
    int endpointOut;
    int endpointFeedback;
    int32_t sampleRate;
    int32_t channelCount;
    int32_t bitDepth;
    int32_t bytesPerSample;
    int32_t bytesPerFrame;
    int32_t maxPacketSize;
    int32_t packetsPerSecond;
    int32_t targetUrbs;

    std::atomic<bool> running;
    std::atomic<float> softwareGain;

    uint8_t *transferBuffer;
    int32_t transferBufferCapacity;

    int64_t framesWritten;
    bool interfaceClaimed;

    UrbSlot ring[USB_AUDIO_MAX_URBS];
    int submitIdx;
    int reapIdx;
    int urbsInFlight;
    bool ringAllocated;

    double frameAccumulator;
    double calibratedFpmf;

    uint8_t residualBuffer[USB_AUDIO_URB_BUFFER_SIZE];
    int residualBytes;

    struct usbdevfs_urb *feedbackUrb;
    uint8_t feedbackBuffer[4];
    bool feedbackInFlight;
};
