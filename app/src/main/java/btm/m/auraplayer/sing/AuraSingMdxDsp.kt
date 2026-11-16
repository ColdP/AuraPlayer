package btm.m.auraplayer.sing

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure-Kotlin high-speed STFT / iSTFT engine for UVR MDX-Net models.
 * Supports non-power-of-two composite FFT sizes (e.g., 5120, 6144, 7680) via Stockham mixed-radix FFT
 * and packs stereo real channels into single complex FFT passes (2x speedup).
 */
class MixedRadixFft(val size: Int) {
    private val factors: IntArray
    private val twiddleCos: Array<DoubleArray>
    private val twiddleSin: Array<DoubleArray>
    private val workRe = DoubleArray(size)
    private val workIm = DoubleArray(size)
    private val outRe = DoubleArray(size)
    private val outIm = DoubleArray(size)

    init {
        require(size > 0) { "FFT size must be positive" }
        val f = mutableListOf<Int>()
        var rem = size
        while (rem % 4 == 0) { f += 4; rem /= 4 }
        while (rem % 2 == 0) { f += 2; rem /= 2 }
        while (rem % 3 == 0) { f += 3; rem /= 3 }
        while (rem % 5 == 0) { f += 5; rem /= 5 }
        require(rem == 1) { "Unsupported FFT factor for size $size" }
        factors = f.toIntArray()

        twiddleCos = Array(factors.size) { DoubleArray(0) }
        twiddleSin = Array(factors.size) { DoubleArray(0) }
        var s = 1
        for (stage in factors.indices) {
            val r = factors[stage]
            val rs = r * s
            val tableSize = r * s
            val cArr = DoubleArray(tableSize)
            val sArr = DoubleArray(tableSize)
            val stepAngle = 2.0 * PI / rs.toDouble()
            for (j in 0 until s) {
                for (u in 0 until r) {
                    val angle = stepAngle * (u * j).toDouble()
                    val idx = j * r + u
                    cArr[idx] = cos(angle)
                    sArr[idx] = sin(angle)
                }
            }
            twiddleCos[stage] = cArr
            twiddleSin[stage] = sArr
            s = rs
        }
    }

    /**
     * In-place forward (`inverse = false`) or normalized inverse (`inverse = true`, divides by [size])
     * Stockham mixed-radix complex FFT on [re] and [im].
     */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        var srcRe = re
        var srcIm = im
        var dstRe = workRe
        var dstIm = workIm
        val sign = if (inverse) 1.0 else -1.0

        // Ensure first stage always writes cleanly to dst != re if factors.size is odd/even cleanly.
        var s = 1
        val bRe = DoubleArray(5)
        val bIm = DoubleArray(5)

        for (stage in factors.indices) {
            val r = factors[stage]
            val m = size / (r * s)
            val tCos = twiddleCos[stage]
            val tSin = twiddleSin[stage]

            for (j in 0 until s) {
                for (k in 0 until m) {
                    for (u in 0 until r) {
                        val srcIdx = (u * m + k) * s + j
                        if (u == 0) {
                            bRe[0] = srcRe[srcIdx]
                            bIm[0] = srcIm[srcIdx]
                        } else {
                            val twIdx = j * r + u
                            val wr = tCos[twIdx]
                            val wi = sign * tSin[twIdx]
                            val xr = srcRe[srcIdx]
                            val xi = srcIm[srcIdx]
                            bRe[u] = xr * wr - xi * wi
                            bIm[u] = xr * wi + xi * wr
                        }
                    }

                    val dstBase = k * (r * s) + j
                    when (r) {
                        2 -> {
                            val r0 = bRe[0] + bRe[1]
                            val i0 = bIm[0] + bIm[1]
                            val r1 = bRe[0] - bRe[1]
                            val i1 = bIm[0] - bIm[1]
                            dstRe[dstBase] = r0
                            dstIm[dstBase] = i0
                            dstRe[dstBase + s] = r1
                            dstIm[dstBase + s] = i1
                        }
                        4 -> {
                            val a0r = bRe[0] + bRe[2]
                            val a0i = bIm[0] + bIm[2]
                            val a1r = bRe[0] - bRe[2]
                            val a1i = bIm[0] - bIm[2]
                            val a2r = bRe[1] + bRe[3]
                            val a2i = bIm[1] + bIm[3]
                            val a3r = bRe[1] - bRe[3]
                            val a3i = bIm[1] - bIm[3]

                            dstRe[dstBase] = a0r + a2r
                            dstIm[dstBase] = a0i + a2i
                            dstRe[dstBase + 2 * s] = a0r - a2r
                            dstIm[dstBase + 2 * s] = a0i - a2i
                            // Multiply (a3r + i*a3i) by (sign * (-i)) => forward: (a3i - i*a3r), inverse: (-a3i + i*a3r)
                            if (inverse) {
                                dstRe[dstBase + s] = a1r - a3i
                                dstIm[dstBase + s] = a1i + a3r
                                dstRe[dstBase + 3 * s] = a1r + a3i
                                dstIm[dstBase + 3 * s] = a1i - a3r
                            } else {
                                dstRe[dstBase + s] = a1r + a3i
                                dstIm[dstBase + s] = a1i - a3r
                                dstRe[dstBase + 3 * s] = a1r - a3i
                                dstIm[dstBase + 3 * s] = a1i + a3r
                            }
                        }
                        3 -> {
                            val c1 = -0.5
                            val s1 = sign * 0.8660254037844386
                            val t1r = bRe[1] + bRe[2]
                            val t1i = bIm[1] + bIm[2]
                            val t2r = bRe[0] + c1 * t1r
                            val t2i = bIm[0] + c1 * t1i
                            val t3r = s1 * (bRe[1] - bRe[2])
                            val t3i = s1 * (bIm[1] - bIm[2])

                            dstRe[dstBase] = bRe[0] + t1r
                            dstIm[dstBase] = bIm[0] + t1i
                            dstRe[dstBase + s] = t2r - t3i
                            dstIm[dstBase + s] = t2i + t3r
                            dstRe[dstBase + 2 * s] = t2r + t3i
                            dstIm[dstBase + 2 * s] = t2i - t3r
                        }
                        5 -> {
                            val c1 = 0.30901699437494745
                            val c2 = -0.8090169943749475
                            val s1 = sign * 0.9510565162951535
                            val s2 = sign * 0.5877852522924731

                            val t1r = bRe[1] + bRe[4]
                            val t1i = bIm[1] + bIm[4]
                            val t2r = bRe[2] + bRe[3]
                            val t2i = bIm[2] + bIm[3]
                            val d1r = bRe[1] - bRe[4]
                            val d1i = bIm[1] - bIm[4]
                            val d2r = bRe[2] - bRe[3]
                            val d2i = bIm[2] - bIm[3]

                            dstRe[dstBase] = bRe[0] + t1r + t2r
                            dstIm[dstBase] = bIm[0] + t1i + t2i

                            val m1r = bRe[0] + c1 * t1r + c2 * t2r
                            val m1i = bIm[0] + c1 * t1i + c2 * t2i
                            val m2r = bRe[0] + c2 * t1r + c1 * t2r
                            val m2i = bIm[0] + c2 * t1i + c1 * t2i

                            val p1r = s1 * d1r + s2 * d2r
                            val p1i = s1 * d1i + s2 * d2i
                            val p2r = s2 * d1r - s1 * d2r
                            val p2i = s2 * d1i - s1 * d2i

                            dstRe[dstBase + s] = m1r - p1i
                            dstIm[dstBase + s] = m1i + p1r
                            dstRe[dstBase + 4 * s] = m1r + p1i
                            dstIm[dstBase + 4 * s] = m1i - p1r

                            dstRe[dstBase + 2 * s] = m2r - p2i
                            dstIm[dstBase + 2 * s] = m2i + p2r
                            dstRe[dstBase + 3 * s] = m2r + p2i
                            dstIm[dstBase + 3 * s] = m2i - p2r
                        }
                    }
                }
            }
            val tmpR = srcRe; srcRe = dstRe; dstRe = tmpR
            val tmpI = srcIm; srcIm = dstIm; dstIm = tmpI
            if (stage == 0) {
                // First swap set dst = original re/im; that's safe!
            }
            s *= r
        }

        if (srcRe !== re) {
            System.arraycopy(srcRe, 0, re, 0, size)
            System.arraycopy(srcIm, 0, im, 0, size)
        }
        if (inverse) {
            val inv = 1.0 / size.toDouble()
            for (i in 0 until size) {
                re[i] *= inv
                im[i] *= inv
            }
        }
    }
}

/**
 * Matches PyTorch/UVR MDX-Net STFT + iSTFT pipeline (`torch.stft(..., center=True)`,
 * periodic Hann window, `[L_re, L_im, R_re, R_im]` plane order cropped to `dimF` bins).
 */
class MdxStftProcessor(
    val nFft: Int,
    val hopLength: Int,
    val dimF: Int,
    val dimT: Int = 256,
) {
    val chunkSize: Int = hopLength * (dimT - 1)
    val trim: Int = nFft / 2
    val genSize: Int = chunkSize - 2 * trim
    private val fft = MixedRadixFft(nFft)
    private val window = DoubleArray(nFft) { n ->
        0.5 * (1.0 - cos(2.0 * PI * n.toDouble() / nFft.toDouble()))
    }
    private val fftRe = DoubleArray(nFft)
    private val fftIm = DoubleArray(nFft)
    private val paddedLen = chunkSize + nFft
    private val normWeights = DoubleArray(paddedLen).also { norm ->
        for (t in 0 until dimT) {
            val base = t * hopLength
            for (n in 0 until nFft) {
                val w = window[n]
                norm[base + n] += w * w
            }
        }
    }
    private val accumL = DoubleArray(paddedLen)
    private val accumR = DoubleArray(paddedLen)

    /**
     * Computes stereo STFT of [leftChunk] and [rightChunk] (length == [chunkSize]) into
     * flattened tensor `[1, 4, dimF, dimT]` = `[L_re, L_im, R_re, R_im]`.
     * Optionally zeroes the lowest 3 bins matching UVR `mdx_separator.py`.
     */
    fun forwardStereo(
        leftChunk: FloatArray,
        rightChunk: FloatArray,
        dstTensor: FloatArray,
        zeroLowBins: Boolean = true,
    ) {
        val planeStride = dimF * dimT
        val p0 = 0
        val p1 = planeStride
        val p2 = 2 * planeStride
        val p3 = 3 * planeStride

        for (t in 0 until dimT) {
            val start = t * hopLength - trim
            if (start >= 0 && start + nFft <= chunkSize) {
                for (n in 0 until nFft) {
                    val w = window[n]
                    val sIdx = start + n
                    fftRe[n] = leftChunk[sIdx].toDouble() * w
                    fftIm[n] = rightChunk[sIdx].toDouble() * w
                }
            } else {
                for (n in 0 until nFft) {
                    val w = window[n]
                    var sIdx = start + n
                    if (sIdx < 0) sIdx = -sIdx
                    if (sIdx >= chunkSize) sIdx = 2 * (chunkSize - 1) - sIdx
                    val clamped = sIdx.coerceIn(0, chunkSize - 1)
                    fftRe[n] = leftChunk[clamped].toDouble() * w
                    fftIm[n] = rightChunk[clamped].toDouble() * w
                }
            }

            fft.transform(fftRe, fftIm, inverse = false)

            // Unpack Z = DFT(L + i*R) into L and R for bins k = 0 .. dimF - 1
            for (k in 0 until dimF) {
                val idx = k * dimT + t
                if (zeroLowBins && k < 3) {
                    dstTensor[p0 + idx] = 0f
                    dstTensor[p1 + idx] = 0f
                    dstTensor[p2 + idx] = 0f
                    dstTensor[p3 + idx] = 0f
                } else if (k == 0) {
                    dstTensor[p0 + idx] = fftRe[0].toFloat()
                    dstTensor[p1 + idx] = 0f
                    dstTensor[p2 + idx] = fftIm[0].toFloat()
                    dstTensor[p3 + idx] = 0f
                } else {
                    val conjIdx = nFft - k
                    val zkr = fftRe[k]
                    val zki = fftIm[k]
                    val znr = fftRe[conjIdx]
                    val zni = fftIm[conjIdx]
                    // L[k] = 0.5 * (Z[k] + conj(Z[N-k]))
                    // R[k] = -0.5i * (Z[k] - conj(Z[N-k]))
                    dstTensor[p0 + idx] = (0.5 * (zkr + znr)).toFloat()
                    dstTensor[p1 + idx] = (0.5 * (zki - zni)).toFloat()
                    dstTensor[p2 + idx] = (0.5 * (zki + zni)).toFloat()
                    dstTensor[p3 + idx] = (0.5 * (znr - zkr)).toFloat()
                }
            }
        }
    }

    /**
     * Reconstructs time-domain stereo waveforms ([leftOut], [rightOut] of size [chunkSize])
     * from flattened predicted spectrum `[1, 4, dimF, dimT]`.
     */
    fun inverseStereo(
        srcTensor: FloatArray,
        leftOut: FloatArray,
        rightOut: FloatArray,
    ) {
        val planeStride = dimF * dimT
        val p0 = 0
        val p1 = planeStride
        val p2 = 2 * planeStride
        val p3 = 3 * planeStride

        accumL.fill(0.0)
        accumR.fill(0.0)
        val halfN = nFft / 2

        for (t in 0 until dimT) {
            fftRe.fill(0.0)
            fftIm.fill(0.0)

            // Pack Z[k] = L[k] + i * R[k] with Hermitian symmetry for both L and R
            run {
                val lr0 = srcTensor[p0 + t].toDouble()
                val rr0 = srcTensor[p2 + t].toDouble()
                fftRe[0] = lr0
                fftIm[0] = rr0
            }
            val limitK = minOf(dimF, halfN)
            for (k in 1 until limitK) {
                val idx = k * dimT + t
                val lr = srcTensor[p0 + idx].toDouble()
                val li = srcTensor[p1 + idx].toDouble()
                val rr = srcTensor[p2 + idx].toDouble()
                val ri = srcTensor[p3 + idx].toDouble()
                fftRe[k] = lr - ri
                fftIm[k] = li + rr
                val conjIdx = nFft - k
                fftRe[conjIdx] = lr + ri
                fftIm[conjIdx] = -li + rr
            }

            fft.transform(fftRe, fftIm, inverse = true)

            val base = t * hopLength
            for (n in 0 until nFft) {
                val w = window[n]
                accumL[base + n] += fftRe[n] * w
                accumR[base + n] += fftIm[n] * w
            }
        }

        for (i in 0 until chunkSize) {
            val idx = trim + i
            val norm = normWeights[idx].coerceAtLeast(1e-8)
            leftOut[i] = (accumL[idx] / norm).toFloat()
            rightOut[i] = (accumR[idx] / norm).toFloat()
        }
    }
}
