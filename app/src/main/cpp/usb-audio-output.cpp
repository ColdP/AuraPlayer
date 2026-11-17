/*
 * Adapted from decent-player (https://github.com/Ma145/decent-player)
 * Copyright (c) 2026 Marcelo Silva — MIT License
 * Modified for Aura Player (btm.m.auraplayer):
 *   - Configurable URB pipeline depth (40ms / 100ms / 200ms).
 *   - Supports both High-Speed UAC2 (8000 uframes/s, 4-byte Q16.16 feedback)
 *     and Full-Speed UAC1 (1000 frames/s, 3-byte Q10.14 feedback).
 *   - Supports full Media3 PCM integer conversion matrix (16/24/32-bit in -> 16/24/32-bit wire).
 *   - Optional software safety gain (bypassed bit-perfectly when gain >= 0.9999f).
 */

#include "usb-audio-output.h"

#include <jni.h>
#include <android/log.h>
#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <new>
#include <unistd.h>
#include <time.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

#ifndef USBDEVFS_URB_ISO_ASAP
#define USBDEVFS_URB_ISO_ASAP 0x02
#endif

#define TAG "AuraUsbAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Float -> PCM conversion ─────────────────────────────────────────

static inline float clampf(float v) { return v > 1.0f ? 1.0f : (v < -1.0f ? -1.0f : v); }

static void convertFloatToInt16(const float *src, uint8_t *dst, int n, float gain) {
    auto *out = reinterpret_cast<int16_t *>(dst);
    for (int i = 0; i < n; i++) {
        float s = clampf(src[i] * gain) * 32768.0f;
        if (s > 32767.0f) s = 32767.0f;
        if (s < -32768.0f) s = -32768.0f;
        out[i] = static_cast<int16_t>(s);
    }
}

static void convertFloatToInt24(const float *src, uint8_t *dst, int n, float gain) {
    for (int i = 0; i < n; i++) {
        float s = clampf(src[i] * gain) * 8388608.0f;
        if (s > 8388607.0f) s = 8388607.0f;
        if (s < -8388608.0f) s = -8388608.0f;
        auto v = static_cast<int32_t>(s);
        dst[i * 3] = static_cast<uint8_t>(v & 0xFF);
        dst[i * 3 + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
        dst[i * 3 + 2] = static_cast<uint8_t>((v >> 16) & 0xFF);
    }
}

static void convertFloatToInt32(const float *src, uint8_t *dst, int n, float gain) {
    auto *out = reinterpret_cast<int32_t *>(dst);
    const double dGain = static_cast<double>(gain);
    for (int i = 0; i < n; i++) {
        double s = static_cast<double>(clampf(src[i])) * dGain * 2147483648.0;
        if (s > 2147483647.0) s = 2147483647.0;
        if (s < -2147483648.0) s = -2147483648.0;
        out[i] = static_cast<int32_t>(s);
    }
}

// ── Integer PCM conversions ─────────────────────────────────────────

static void convertInt16ToInt24(const uint8_t *src, uint8_t *dst, int n) {
    const auto *in16 = reinterpret_cast<const int16_t *>(src);
    for (int i = 0; i < n; i++) {
        int32_t v = static_cast<int32_t>(in16[i]) << 8;
        dst[i * 3] = 0;
        dst[i * 3 + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
        dst[i * 3 + 2] = static_cast<uint8_t>((v >> 16) & 0xFF);
    }
}

static void convertInt16ToInt32(const uint8_t *src, uint8_t *dst, int n) {
    const auto *in16 = reinterpret_cast<const int16_t *>(src);
    auto *out32 = reinterpret_cast<int32_t *>(dst);
    for (int i = 0; i < n; i++) {
        out32[i] = static_cast<int32_t>(in16[i]) << 16;
    }
}

static void convertInt24ToInt16(const uint8_t *src, uint8_t *dst, int n) {
    auto *out16 = reinterpret_cast<int16_t *>(dst);
    for (int i = 0; i < n; i++) {
        uint16_t v = static_cast<uint16_t>(src[i * 3 + 1]) |
                     (static_cast<uint16_t>(src[i * 3 + 2]) << 8);
        out16[i] = static_cast<int16_t>(v);
    }
}

static void convertInt24ToInt32(const uint8_t *src, uint8_t *dst, int n) {
    auto *out32 = reinterpret_cast<int32_t *>(dst);
    for (int i = 0; i < n; i++) {
        int32_t s = static_cast<int32_t>(src[i * 3]) |
                    (static_cast<int32_t>(src[i * 3 + 1]) << 8) |
                    (static_cast<int32_t>(src[i * 3 + 2]) << 16);
        if (s & 0x800000) s |= 0xFF000000;
        out32[i] = s << 8;
    }
}

static void convertInt32ToInt16(const uint8_t *src, uint8_t *dst, int n) {
    const auto *in32 = reinterpret_cast<const int32_t *>(src);
    auto *out16 = reinterpret_cast<int16_t *>(dst);
    for (int i = 0; i < n; i++) {
        out16[i] = static_cast<int16_t>(in32[i] >> 16);
    }
}

static void convertInt32ToInt24(const uint8_t *src, uint8_t *dst, int n) {
    const auto *in32 = reinterpret_cast<const int32_t *>(src);
    for (int i = 0; i < n; i++) {
        int32_t v = in32[i] >> 8;
        dst[i * 3] = static_cast<uint8_t>(v & 0xFF);
        dst[i * 3 + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
        dst[i * 3 + 2] = static_cast<uint8_t>((v >> 16) & 0xFF);
    }
}

static void applySoftwareGainInPlace(uint8_t *buf, int n, int bitDepth, float gain) {
    if (gain >= 0.9999f) return;
    if (gain <= 0.0001f) {
        memset(buf, 0, static_cast<size_t>(n) * static_cast<size_t>(bitDepth / 8));
        return;
    }
    const double g = static_cast<double>(gain);
    switch (bitDepth) {
        case 16: {
            auto *p = reinterpret_cast<int16_t *>(buf);
            for (int i = 0; i < n; i++) {
                double v = static_cast<double>(p[i]) * g;
                if (v > 32767.0) v = 32767.0;
                if (v < -32768.0) v = -32768.0;
                p[i] = static_cast<int16_t>(llround(v));
            }
            break;
        }
        case 24: {
            for (int i = 0; i < n; i++) {
                int32_t s = static_cast<int32_t>(buf[i * 3]) |
                            (static_cast<int32_t>(buf[i * 3 + 1]) << 8) |
                            (static_cast<int32_t>(buf[i * 3 + 2]) << 16);
                if (s & 0x800000) s |= 0xFF000000;
                double v = static_cast<double>(s) * g;
                if (v > 8388607.0) v = 8388607.0;
                if (v < -8388608.0) v = -8388608.0;
                auto r = static_cast<int32_t>(llround(v));
                buf[i * 3] = static_cast<uint8_t>(r & 0xFF);
                buf[i * 3 + 1] = static_cast<uint8_t>((r >> 8) & 0xFF);
                buf[i * 3 + 2] = static_cast<uint8_t>((r >> 16) & 0xFF);
            }
            break;
        }
        case 32: {
            auto *p = reinterpret_cast<int32_t *>(buf);
            for (int i = 0; i < n; i++) {
                double v = static_cast<double>(p[i]) * g;
                if (v > 2147483647.0) v = 2147483647.0;
                if (v < -2147483648.0) v = -2147483648.0;
                p[i] = static_cast<int32_t>(llround(v));
            }
            break;
        }
        default:
            break;
    }
}

// ── Ring buffer management ──────────────────────────────────────────

static bool allocRing(UsbAudioContext *ctx) {
    size_t urbStructSize = sizeof(struct usbdevfs_urb) +
                           USB_AUDIO_PACKETS_PER_URB * sizeof(struct usbdevfs_iso_packet_desc);
    for (int i = 0; i < ctx->targetUrbs; i++) {
        ctx->ring[i].urb = static_cast<struct usbdevfs_urb *>(calloc(1, urbStructSize));
        ctx->ring[i].buffer = static_cast<uint8_t *>(malloc(USB_AUDIO_URB_BUFFER_SIZE));
        ctx->ring[i].dataLength = 0;
        if (!ctx->ring[i].urb || !ctx->ring[i].buffer) {
            LOGE("allocRing: OOM at slot %d", i);
            for (int j = 0; j <= i; j++) {
                free(ctx->ring[j].urb);
                free(ctx->ring[j].buffer);
                ctx->ring[j].urb = nullptr;
                ctx->ring[j].buffer = nullptr;
            }
            return false;
        }
    }
    ctx->ringAllocated = true;
    return true;
}

static void freeRing(UsbAudioContext *ctx) {
    if (!ctx->ringAllocated) return;
    for (int i = 0; i < ctx->targetUrbs; i++) {
        free(ctx->ring[i].urb);
        free(ctx->ring[i].buffer);
        ctx->ring[i].urb = nullptr;
        ctx->ring[i].buffer = nullptr;
    }
    ctx->ringAllocated = false;
}

// ── Async feedback helpers ──────────────────────────────────────────

static double parseFeedbackValue(const uint8_t *fb, int len, int packetsPerSec) {
    if (len >= 4) {
        uint32_t raw = static_cast<uint32_t>(fb[0]) |
                       (static_cast<uint32_t>(fb[1]) << 8) |
                       (static_cast<uint32_t>(fb[2]) << 16) |
                       (static_cast<uint32_t>(fb[3]) << 24);
        return raw / 65536.0;
    }
    if (len == 3) {
        uint32_t raw = static_cast<uint32_t>(fb[0]) |
                       (static_cast<uint32_t>(fb[1]) << 8) |
                       (static_cast<uint32_t>(fb[2]) << 16);
        return raw / (packetsPerSec <= 1000 ? 16384.0 : 65536.0);
    }
    return 0.0;
}

static double readFeedbackOnce(int fd, int ep, int packetsPerSec) {
    uint8_t fb[4] = {};
    size_t sz = sizeof(struct usbdevfs_urb) + sizeof(struct usbdevfs_iso_packet_desc);
    auto *u = static_cast<struct usbdevfs_urb *>(calloc(1, sz));
    if (!u) return 0.0;
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->endpoint = static_cast<unsigned char>(ep);
    u->buffer = fb;
    u->buffer_length = 4;
    u->number_of_packets = 1;
    u->iso_frame_desc[0].length = 4;
    if (ioctl(fd, USBDEVFS_SUBMITURB, u) < 0) {
        free(u);
        return 0.0;
    }
    struct usbdevfs_urb *c = nullptr;
    usleep(2000);
    if (ioctl(fd, USBDEVFS_REAPURBNDELAY, &c) < 0) {
        ioctl(fd, USBDEVFS_DISCARDURB, u);
        ioctl(fd, USBDEVFS_REAPURBNDELAY, &c);
        free(u);
        return 0.0;
    }
    double r = parseFeedbackValue(fb, static_cast<int>(u->iso_frame_desc[0].actual_length), packetsPerSec);
    free(u);
    return r;
}

static bool allocFeedbackUrb(UsbAudioContext *ctx) {
    size_t sz = sizeof(struct usbdevfs_urb) + sizeof(struct usbdevfs_iso_packet_desc);
    ctx->feedbackUrb = static_cast<struct usbdevfs_urb *>(calloc(1, sz));
    if (!ctx->feedbackUrb) return false;
    ctx->feedbackInFlight = false;
    memset(ctx->feedbackBuffer, 0, sizeof(ctx->feedbackBuffer));
    return true;
}

static bool submitFeedbackUrb(UsbAudioContext *ctx) {
    if (ctx->endpointFeedback <= 0 || !ctx->feedbackUrb || ctx->feedbackInFlight) {
        return false;
    }
    struct usbdevfs_urb *u = ctx->feedbackUrb;
    size_t sz = sizeof(struct usbdevfs_urb) + sizeof(struct usbdevfs_iso_packet_desc);
    memset(u, 0, sz);
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->endpoint = static_cast<unsigned char>(ctx->endpointFeedback);
    u->buffer = ctx->feedbackBuffer;
    u->buffer_length = 4;
    u->number_of_packets = 1;
    u->iso_frame_desc[0].length = 4;

    if (ioctl(ctx->fd, USBDEVFS_SUBMITURB, u) < 0) {
        LOGW("submitFeedbackUrb: failed errno=%d (%s)", errno, strerror(errno));
        return false;
    }
    ctx->feedbackInFlight = true;
    return true;
}

static void handleFeedbackCompletion(UsbAudioContext *ctx) {
    ctx->feedbackInFlight = false;
    int actual = static_cast<int>(ctx->feedbackUrb->iso_frame_desc[0].actual_length);
    double newFpmf = parseFeedbackValue(ctx->feedbackBuffer, actual, ctx->packetsPerSecond);
    double nominal = static_cast<double>(ctx->sampleRate) / static_cast<double>(ctx->packetsPerSecond);
    if (newFpmf > nominal * 0.98 && newFpmf < nominal * 1.02) {
        ctx->calibratedFpmf = newFpmf;
    }
    submitFeedbackUrb(ctx);
}

// ── ISO URB submission / reap ───────────────────────────────────────

static int submitRingUrb(UsbAudioContext *ctx, const int *pktSizes, int numPackets, int totalBytes) {
    UrbSlot *slot = &ctx->ring[ctx->submitIdx];
    struct usbdevfs_urb *u = slot->urb;

    size_t clearSize = sizeof(struct usbdevfs_urb) +
                       static_cast<size_t>(numPackets) * sizeof(struct usbdevfs_iso_packet_desc);
    memset(u, 0, clearSize);

    u->type = USBDEVFS_URB_TYPE_ISO;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->endpoint = static_cast<unsigned char>(ctx->endpointOut);
    u->buffer = slot->buffer;
    u->buffer_length = totalBytes;
    u->number_of_packets = numPackets;
    for (int i = 0; i < numPackets; i++) {
        u->iso_frame_desc[i].length = static_cast<unsigned int>(pktSizes[i]);
    }
    slot->dataLength = totalBytes;

    int ret = ioctl(ctx->fd, USBDEVFS_SUBMITURB, u);
    if (ret < 0) {
        LOGE("SUBMITURB failed slot=%d ep=0x%02x bytes=%d pkts=%d errno=%d (%s)",
             ctx->submitIdx, ctx->endpointOut, totalBytes, numPackets, errno, strerror(errno));
        return -1;
    }

    ctx->submitIdx = (ctx->submitIdx + 1) % ctx->targetUrbs;
    ctx->urbsInFlight++;
    return 0;
}

static int reapOldestUrb(UsbAudioContext *ctx, int timeoutMs) {
    struct usbdevfs_urb *c = nullptr;
    int stepUs = ctx->packetsPerSecond <= 1000 ? 500 : 125;
    int iterations = (timeoutMs * 1000) / stepUs;
    for (int i = 0; i < iterations; i++) {
        int ret = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c);
        if (ret == 0 && c != nullptr) {
            if (c == ctx->feedbackUrb) {
                handleFeedbackCompletion(ctx);
                c = nullptr;
                continue;
            }
            ctx->reapIdx = (ctx->reapIdx + 1) % ctx->targetUrbs;
            ctx->urbsInFlight--;
            return 0;
        }
        if (ret < 0 && errno != EAGAIN) {
            LOGE("reapOldestUrb: error errno=%d (%s)", errno, strerror(errno));
            return -1;
        }
        usleep(static_cast<useconds_t>(stepUs));
    }
    return -2;
}

static int drainAllUrbs(UsbAudioContext *ctx) {
    int drained = 0;
    int initialCount = ctx->urbsInFlight;

    if (ctx->feedbackInFlight && ctx->feedbackUrb) {
        ioctl(ctx->fd, USBDEVFS_DISCARDURB, ctx->feedbackUrb);
        struct usbdevfs_urb *c = nullptr;
        for (int i = 0; i < 50; i++) {
            if (ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c) == 0 && c == ctx->feedbackUrb) {
                break;
            }
            usleep(100);
        }
        ctx->feedbackInFlight = false;
    }

    while (ctx->urbsInFlight > 0) {
        int result = reapOldestUrb(ctx, 500);
        if (result == 0) {
            drained++;
        } else {
            break;
        }
    }

    if (ctx->urbsInFlight > 0) {
        int toDiscard = ctx->urbsInFlight;
        for (int i = 0; i < toDiscard; i++) {
            int slotIdx = (ctx->reapIdx + i) % ctx->targetUrbs;
            ioctl(ctx->fd, USBDEVFS_DISCARDURB, ctx->ring[slotIdx].urb);
        }

        int reaped = 0;
        for (int attempt = 0; attempt < 500 && reaped < toDiscard; attempt++) {
            struct usbdevfs_urb *c = nullptr;
            int ret = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c);
            if (ret == 0 && c != nullptr) {
                reaped++;
            } else if (ret < 0 && errno != EAGAIN) {
                break;
            } else {
                usleep(1000);
            }
        }
        drained += reaped;

        for (int i = 0; i < 10; i++) {
            struct usbdevfs_urb *c = nullptr;
            if (ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c) == 0 && c != nullptr) {
                drained++;
            } else {
                break;
            }
        }
    }

    ctx->urbsInFlight = 0;
    ctx->submitIdx = 0;
    ctx->reapIdx = 0;

    LOGI("drainAllUrbs: drained %d/%d", drained, initialCount);
    return drained;
}

static void submitPcmToUrbs(UsbAudioContext *ctx, const uint8_t *pcmData, int totalBytes) {
    const uint8_t *data = pcmData;
    int dataLen = totalBytes;
    uint8_t *mergedBuf = nullptr;

    if (ctx->residualBytes > 0 && totalBytes > 0) {
        dataLen = ctx->residualBytes + totalBytes;
        mergedBuf = static_cast<uint8_t *>(malloc(static_cast<size_t>(dataLen)));
        if (mergedBuf) {
            memcpy(mergedBuf, ctx->residualBuffer, static_cast<size_t>(ctx->residualBytes));
            memcpy(mergedBuf + ctx->residualBytes, pcmData, static_cast<size_t>(totalBytes));
            data = mergedBuf;
        }
        ctx->residualBytes = 0;
    }

    int offset = 0;
    double fpmf = ctx->calibratedFpmf;

    while (offset < dataLen && ctx->running.load()) {
        int pktSizes[USB_AUDIO_PACKETS_PER_URB];
        int numPackets = 0;
        int urbBytes = 0;

        for (int p = 0; p < USB_AUDIO_PACKETS_PER_URB; p++) {
            int remaining = dataLen - offset - urbBytes;
            if (remaining <= 0) break;

            ctx->frameAccumulator += fpmf;
            int frames = static_cast<int>(ctx->frameAccumulator);
            ctx->frameAccumulator -= frames;
            int b = frames * ctx->bytesPerFrame;

            if (ctx->maxPacketSize > 0 && b > ctx->maxPacketSize) {
                int maxFrames = ctx->maxPacketSize / ctx->bytesPerFrame;
                if (maxFrames > 0) {
                    int excess = frames - maxFrames;
                    ctx->frameAccumulator += excess;
                    b = maxFrames * ctx->bytesPerFrame;
                }
            }

            if (b <= 0 || b > remaining || urbBytes + b > USB_AUDIO_URB_BUFFER_SIZE) {
                break;
            }

            pktSizes[p] = b;
            urbBytes += b;
            numPackets++;
        }
        if (numPackets <= 0 || urbBytes <= 0) break;

        if (numPackets < USB_AUDIO_PACKETS_PER_URB) {
            int leftover = dataLen - offset;
            if (leftover > 0 && leftover < static_cast<int>(sizeof(ctx->residualBuffer))) {
                memcpy(ctx->residualBuffer, data + offset, static_cast<size_t>(leftover));
                ctx->residualBytes = leftover;
            }
            break;
        }

        if (ctx->urbsInFlight >= ctx->targetUrbs) {
            int result = reapOldestUrb(ctx, 200);
            if (result == -2) {
                LOGE("submitPcmToUrbs: reap timeout, inflight=%d", ctx->urbsInFlight);
                drainAllUrbs(ctx);
                ctx->running.store(false);
                free(mergedBuf);
                return;
            } else if (result < 0) {
                ctx->running.store(false);
                free(mergedBuf);
                return;
            }
        }

        UrbSlot *slot = &ctx->ring[ctx->submitIdx];
        memcpy(slot->buffer, data + offset, static_cast<size_t>(urbBytes));

        if (submitRingUrb(ctx, pktSizes, numPackets, urbBytes) < 0) {
            LOGE("submitPcmToUrbs: submit failed, stopping stream");
            ctx->running.store(false);
            free(mergedBuf);
            return;
        }
        offset += urbBytes;
    }

    int leftover = dataLen - offset;
    if (leftover > 0 && leftover < static_cast<int>(sizeof(ctx->residualBuffer)) && ctx->residualBytes == 0) {
        memcpy(ctx->residualBuffer, data + offset, static_cast<size_t>(leftover));
        ctx->residualBytes = leftover;
    }

    free(mergedBuf);
}

// ── JNI entry points ────────────────────────────────────────────────

extern "C" {

JNIEXPORT jlong JNICALL
Java_btm_m_auraplayer_audio_usb_UsbAudioStream_nativeUsbAudioCreate(
        JNIEnv *, jobject, jint fd, jint ifId, jint epOut, jint epFb,
        jint rate, jint ch, jint bits, jint maxPkt, jint packetsPerSec, jint bufferMs) {
    if (fd < 0 || rate <= 0 || ch <= 0 || (bits != 16 && bits != 24 && bits != 32)) {
        return 0;
    }
    int pps = packetsPerSec > 0 ? packetsPerSec : 8000;
    int desiredUrbs = pps <= 1000 ? (bufferMs / 8) : bufferMs;
    if (desiredUrbs < 20) desiredUrbs = 20;
    if (desiredUrbs > USB_AUDIO_MAX_URBS) desiredUrbs = USB_AUDIO_MAX_URBS;

    LOGI("Create: fd=%d ep=0x%02x fb=0x%02x rate=%d ch=%d bits=%d maxPkt=%d pps=%d urbs=%d",
         fd, epOut, epFb, rate, ch, bits, maxPkt, pps, desiredUrbs);
    auto *ctx = new(std::nothrow) UsbAudioContext();
    if (!ctx) return 0;
    ctx->fd = fd;
    ctx->interfaceId = ifId;
    ctx->endpointOut = epOut;
    ctx->endpointFeedback = epFb;
    ctx->sampleRate = rate;
    ctx->channelCount = ch;
    ctx->bitDepth = bits;
    ctx->bytesPerSample = bits / 8;
    ctx->bytesPerFrame = (bits / 8) * ch;
    ctx->maxPacketSize = maxPkt;
    ctx->packetsPerSecond = pps;
    ctx->targetUrbs = desiredUrbs;
    ctx->running.store(false);
    ctx->softwareGain.store(1.0f);
    ctx->transferBuffer = nullptr;
    ctx->transferBufferCapacity = 0;
    ctx->framesWritten = 0;
    ctx->interfaceClaimed = true;
    ctx->submitIdx = 0;
    ctx->reapIdx = 0;
    ctx->urbsInFlight = 0;
    ctx->ringAllocated = false;
    ctx->frameAccumulator = 0.0;
    ctx->calibratedFpmf = static_cast<double>(rate) / static_cast<double>(pps);
    ctx->residualBytes = 0;
    memset(ctx->residualBuffer, 0, sizeof(ctx->residualBuffer));
    memset(ctx->ring, 0, sizeof(ctx->ring));
    ctx->feedbackUrb = nullptr;
    ctx->feedbackInFlight = false;
    memset(ctx->feedbackBuffer, 0, sizeof(ctx->feedbackBuffer));

    if (!allocRing(ctx)) {
        delete ctx;
        return 0;
    }

    if (!allocFeedbackUrb(ctx)) {
        freeRing(ctx);
        delete ctx;
        return 0;
    }

    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_btm_m_auraplayer_audio_usb_UsbAudioStream_nativeSetSoftwareGain(
        JNIEnv *, jobject, jlong h, jfloat gain) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return;
    float clamped = gain < 0.0f ? 0.0f : (gain > 1.0f ? 1.0f : gain);
    ctx->softwareGain.store(clamped);
}

JNIEXPORT jboolean JNICALL
Java_btm_m_auraplayer_audio_usb_UsbAudioStream_nativeUsbAudioStart(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx || !ctx->ringAllocated) return JNI_FALSE;

    ctx->running.store(true);
    ctx->framesWritten = 0;
    ctx->frameAccumulator = 0.0;
    ctx->residualBytes = 0;

    double nominalFpmf = static_cast<double>(ctx->sampleRate) / static_cast<double>(ctx->packetsPerSecond);
    ctx->calibratedFpmf = nominalFpmf;

    if (ctx->endpointFeedback > 0) {
        double fb = readFeedbackOnce(ctx->fd, ctx->endpointFeedback, ctx->packetsPerSecond);
        if (fb > nominalFpmf * 0.98 && fb < nominalFpmf * 1.02) {
            ctx->calibratedFpmf = fb;
        }
        submitFeedbackUrb(ctx);
    }

    LOGI("Start: rate=%d ch=%d bits=%d urbs=%d fpmf=%.4f feedback=%s",
         ctx->sampleRate, ctx->channelCount, ctx->bitDepth, ctx->targetUrbs,
         ctx->calibratedFpmf, ctx->feedbackInFlight ? "continuous" : "none");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_btm_m_auraplayer_audio_usb_UsbAudioStream_nativeUsbAudioWrite(
        JNIEnv *env, jobject, jlong h, jfloatArray pcm) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx || !ctx->running.load()) return;

    jint totalSamples = env->GetArrayLength(pcm);
    if (totalSamples <= 0) return;
    int totalFrames = totalSamples / ctx->channelCount;
    int totalBytes = totalSamples * ctx->bytesPerSample;

    if (!ctx->transferBuffer || ctx->transferBufferCapacity < totalBytes) {
        free(ctx->transferBuffer);
        ctx->transferBuffer = static_cast<uint8_t *>(malloc(static_cast<size_t>(totalBytes)));
        if (!ctx->transferBuffer) {
            ctx->transferBufferCapacity = 0;
            return;
        }
        ctx->transferBufferCapacity = totalBytes;
    }

    float gain = ctx->softwareGain.load();
    jfloat *f = env->GetFloatArrayElements(pcm, nullptr);
    if (!f) return;
    switch (ctx->bitDepth) {
        case 16: convertFloatToInt16(f, ctx->transferBuffer, totalSamples, gain); break;
        case 24: convertFloatToInt24(f, ctx->transferBuffer, totalSamples, gain); break;
        case 32: convertFloatToInt32(f, ctx->transferBuffer, totalSamples, gain); break;
        default: env->ReleaseFloatArrayElements(pcm, f, JNI_ABORT); return;
    }
    env->ReleaseFloatArrayElements(pcm, f, JNI_ABORT);

    submitPcmToUrbs(ctx, ctx->transferBuffer, totalBytes);
    ctx->framesWritten += totalFrames;
}

JNIEXPORT void JNICALL
Java_btm_m_auraplayer_audio_usb_UsbAudioStream_nativeUsbAudioWriteRaw(
        JNIEnv *env, jobject, jlong h, jbyteArray pcm, jint inputBitDepth) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx || !ctx->running.load()) return;
    if (inputBitDepth != 16 && inputBitDepth != 24 && inputBitDepth != 32) return;

    jint inputBytes = env->GetArrayLength(pcm);
    if (inputBytes <= 0) return;

    int inputBps = inputBitDepth / 8;
    int totalSamples = inputBytes / inputBps;
    if (totalSamples <= 0) return;
    int totalFrames = totalSamples / ctx->channelCount;
    int outputBytes = totalSamples * ctx->bytesPerSample;

    if (!ctx->transferBuffer || ctx->transferBufferCapacity < outputBytes) {
        free(ctx->transferBuffer);
        ctx->transferBuffer = static_cast<uint8_t *>(malloc(static_cast<size_t>(outputBytes)));
        if (!ctx->transferBuffer) {
            ctx->transferBufferCapacity = 0;
            return;
        }
        ctx->transferBufferCapacity = outputBytes;
    }

    jbyte *rawData = env->GetByteArrayElements(pcm, nullptr);
    if (!rawData) return;
    const auto *src = reinterpret_cast<const uint8_t *>(rawData);

    if (inputBitDepth == ctx->bitDepth) {
        memcpy(ctx->transferBuffer, src, static_cast<size_t>(inputBytes));
    } else if (inputBitDepth == 16 && ctx->bitDepth == 24) {
        convertInt16ToInt24(src, ctx->transferBuffer, totalSamples);
    } else if (inputBitDepth == 16 && ctx->bitDepth == 32) {
        convertInt16ToInt32(src, ctx->transferBuffer, totalSamples);
    } else if (inputBitDepth == 24 && ctx->bitDepth == 16) {
        convertInt24ToInt16(src, ctx->transferBuffer, totalSamples);
    } else if (inputBitDepth == 24 && ctx->bitDepth == 32) {
        convertInt24ToInt32(src, ctx->transferBuffer, totalSamples);
    } else if (inputBitDepth == 32 && ctx->bitDepth == 16) {
        convertInt32ToInt16(src, ctx->transferBuffer, totalSamples);
    } else if (inputBitDepth == 32 && ctx->bitDepth == 24) {
        convertInt32ToInt24(src, ctx->transferBuffer, totalSamples);
    }

    env->ReleaseByteArrayElements(pcm, rawData, JNI_ABORT);

    float gain = ctx->softwareGain.load();
    applySoftwareGainInPlace(ctx->transferBuffer, totalSamples, ctx->bitDepth, gain);

    submitPcmToUrbs(ctx, ctx->transferBuffer, outputBytes);
    ctx->framesWritten += totalFrames;
}

JNIEXPORT void JNICALL
Java_btm_m_auraplayer_audio_usb_UsbAudioStream_nativeUsbAudioStop(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return;
    ctx->running.store(false);
}

JNIEXPORT void JNICALL
Java_btm_m_auraplayer_audio_usb_UsbAudioStream_nativeFlush(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return;
    ctx->frameAccumulator = 0.0;
    ctx->residualBytes = 0;
    ctx->framesWritten = 0;
}

JNIEXPORT jint JNICALL
Java_btm_m_auraplayer_audio_usb_UsbAudioStream_nativeDrainUrbs(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return 0;
    ctx->running.store(false);
    return drainAllUrbs(ctx);
}

JNIEXPORT void JNICALL
Java_btm_m_auraplayer_audio_usb_UsbAudioStream_nativeUsbAudioDestroy(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return;
    ctx->running.store(false);

    if (ctx->urbsInFlight > 0) {
        drainAllUrbs(ctx);
    }

    freeRing(ctx);
    free(ctx->feedbackUrb);
    ctx->feedbackUrb = nullptr;
    free(ctx->transferBuffer);
    ctx->transferBuffer = nullptr;
    delete ctx;
}

JNIEXPORT jboolean JNICALL
Java_btm_m_auraplayer_audio_usb_UsbAudioStream_nativeIsRunning(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return JNI_FALSE;
    return ctx->running.load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_btm_m_auraplayer_audio_usb_UsbAudioStream_nativeGetFramesWritten(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return 0;
    return static_cast<jlong>(ctx->framesWritten);
}

} // extern "C"
