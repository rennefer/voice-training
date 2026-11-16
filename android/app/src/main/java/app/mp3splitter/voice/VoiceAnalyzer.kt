package app.mp3splitter.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One analysis frame (~46 ms). f0 = 0 when the frame is not voiced. */
data class VoiceFrame(
    val f0: Float,
    val db: Float,        // approximate sound level, dB
    val hnr: Float,       // harmonics-to-noise ratio, dB
    val f1: Float, val f2: Float, val f3: Float,   // formants, Hz (0 = not found)
    val h1h2: Float,      // first minus second harmonic, dB (NaN = not measured)
) {
    val voiced get() = f0 > 0f
}

/**
 * Voice analysis for one frame of 2048 samples at 44.1 kHz (mono, -1..1).
 * Pitch: YIN on a 4x decimated copy (11 025 Hz). Formants: LPC (order 12) roots.
 * Vocal weight: H1–H2 from an FFT of the full-rate frame. HNR from YIN aperiodicity.
 * Not thread safe: use one analyzer per thread.
 */
class VoiceAnalyzer(private val rate: Int = 44100) {
    companion object {
        const val FRAME = 2048
        const val DEC = 4
        const val MIN_F0 = 65f
        const val MAX_F0 = 500f
        const val SILENCE_DB = -52f      // dBFS gate
        const val SPL_OFFSET = 92f       // dBFS -> rough dB for display (phone mic, ~30 cm)
        private const val LPC_ORDER = 12
    }

    private val dRate = rate / DEC
    private val dLen = FRAME / DEC                    // 512
    private val dec = FloatArray(dLen)
    private val firTaps = makeLowpass(41, 4800.0 / rate)
    private val diff = FloatArray(dLen / 2 + 1)
    private val hann = FloatArray(FRAME) { (0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1))).toFloat() }
    private val hamming = FloatArray(dLen) { (0.54 - 0.46 * cos(2 * PI * it / (dLen - 1))).toFloat() }
    private val re = DoubleArray(FRAME)
    private val im = DoubleArray(FRAME)

    fun analyze(x: FloatArray, off: Int = 0): VoiceFrame {
        // level
        var sum = 0.0
        for (i in 0 until FRAME) { val v = x[off + i]; sum += v * v }
        val rms = sqrt(sum / FRAME)
        val dbfs = (20 * log10(rms + 1e-9)).toFloat()
        val db = dbfs + SPL_OFFSET
        if (dbfs < SILENCE_DB) return VoiceFrame(0f, db, 0f, 0f, 0f, 0f, Float.NaN)

        decimate(x, off)
        val (f0, aper) = yin()
        if (f0 <= 0f) return VoiceFrame(0f, db, 0f, 0f, 0f, 0f, Float.NaN)
        val hnr = (10 * log10((1 - aper).coerceAtLeast(1e-3f) / aper.coerceAtLeast(1e-3f))).coerceIn(-5f, 40f)
        // formants only when the voice clearly dominates the frame (noise skews LPC)
        val fm = if (hnr >= 6f && dbfs > SILENCE_DB + 10) formants() else floatArrayOf(0f, 0f, 0f)
        val h = h1h2(x, off, f0, fm[0], fm[1])
        return VoiceFrame(f0, db, hnr, fm[0], fm[1], fm[2], h)
    }

    // ---------------------------------------------------------------- decimation

    private fun makeLowpass(n: Int, fc: Double): FloatArray {
        val h = FloatArray(n); val m = (n - 1) / 2.0; var s = 0.0
        for (i in 0 until n) {
            val t = i - m
            val sinc = if (t == 0.0) 2 * fc else sin(2 * PI * fc * t) / (PI * t)
            val w = 0.54 - 0.46 * cos(2 * PI * i / (n - 1))
            h[i] = (sinc * w).toFloat(); s += h[i]
        }
        for (i in 0 until n) h[i] = (h[i] / s).toFloat()
        return h
    }

    private fun decimate(x: FloatArray, off: Int) {
        val n = firTaps.size; val half = n / 2
        for (j in 0 until dLen) {
            val c = j * DEC
            var acc = 0f
            for (k in 0 until n) {
                val idx = (c + k - half).coerceIn(0, FRAME - 1)
                acc += firTaps[k] * x[off + idx]
            }
            dec[j] = acc
        }
    }

    // ---------------------------------------------------------------- pitch (YIN)

    /** Returns (f0, aperiodicity 0..1). f0 = 0 if unvoiced. */
    private fun yin(): Pair<Float, Float> {
        val tauMin = (dRate / MAX_F0).toInt()
        val tauMax = min((dRate / MIN_F0).toInt(), dLen / 2 - 1)
        val w = dLen - tauMax
        diff[0] = 0f
        for (tau in 1..tauMax) {
            var s = 0f
            for (j in 0 until w) { val d = dec[j] - dec[j + tau]; s += d * d }
            diff[tau] = s
        }
        // cumulative mean normalised difference
        var running = 0f
        val cmnd = FloatArray(tauMax + 1)
        cmnd[0] = 1f
        for (tau in 1..tauMax) {
            running += diff[tau]
            cmnd[tau] = if (running > 0) diff[tau] * tau / running else 1f
        }
        var best = -1
        for (tau in tauMin..tauMax) {
            if (cmnd[tau] < 0.15f) {
                var t = tau
                while (t + 1 <= tauMax && cmnd[t + 1] < cmnd[t]) t++
                best = t; break
            }
        }
        if (best < 0) {
            var mv = Float.MAX_VALUE
            for (tau in tauMin..tauMax) if (cmnd[tau] < mv) { mv = cmnd[tau]; best = tau }
            if (mv > 0.35f) return 0f to 1f
        }
        val ap = cmnd[best]
        // parabolic interpolation on the raw difference
        var tauF = best.toFloat()
        if (best in 1 until tauMax) {
            val a = diff[best - 1]; val b = diff[best]; val c = diff[best + 1]
            val den = a - 2 * b + c
            if (den != 0f) tauF = best + 0.5f * (a - c) / den
        }
        val f0 = dRate / tauF
        if (f0 < MIN_F0 || f0 > MAX_F0) return 0f to 1f
        return f0 to ap
    }

    // ---------------------------------------------------------------- formants (LPC)

    private val lpcBuf = DoubleArray(dLen)
    private val r = DoubleArray(LPC_ORDER + 1)

    private fun formants(): FloatArray {
        // pre-emphasis + window
        lpcBuf[0] = dec[0] * hamming[0].toDouble()
        for (i in 1 until dLen) lpcBuf[i] = (dec[i] - 0.97 * dec[i - 1]) * hamming[i]
        for (k in 0..LPC_ORDER) {
            var s = 0.0
            for (i in k until dLen) s += lpcBuf[i] * lpcBuf[i - k]
            r[k] = s
        }
        if (r[0] <= 0) return floatArrayOf(0f, 0f, 0f)
        r[0] *= 1.0001  // tiny white-noise floor for stability
        val a = levinson(r, LPC_ORDER) ?: return floatArrayOf(0f, 0f, 0f)
        val roots = polyRoots(a) ?: return floatArrayOf(0f, 0f, 0f)
        val cands = ArrayList<Pair<Double, Double>>()
        for ((zr, zi) in roots) {
            if (zi <= 0) continue
            val f = atan2(zi, zr) * dRate / (2 * PI)
            val bw = -ln(hypot(zr, zi)) * dRate / PI
            if (f > 150 && f < dRate / 2.0 - 100 && bw < 500) cands += f to bw
        }
        cands.sortBy { it.first }
        val out = FloatArray(3)
        var idx = 0
        val lo = doubleArrayOf(200.0, 600.0, 1500.0)
        val hi = doubleArrayOf(1100.0, 3200.0, 4200.0)
        for ((f, _) in cands) {
            if (idx >= 3) break
            if (f < lo[idx]) continue
            if (idx > 0 && f - out[idx - 1] < 150) continue
            if (f > hi[idx]) {
                // a formant was skipped (merged); move on to the next slot if it fits there
                while (idx < 3 && f > hi[idx]) idx++
                if (idx >= 3 || f < lo[idx]) continue
            }
            out[idx] = f.toFloat(); idx++
        }
        return out
    }

    /** Levinson–Durbin. Returns a[0..p] with a[0] = 1, or null if unstable. */
    private fun levinson(r: DoubleArray, p: Int): DoubleArray? {
        val a = DoubleArray(p + 1); a[0] = 1.0
        val tmp = DoubleArray(p + 1)
        var e = r[0]
        for (i in 1..p) {
            var acc = r[i]
            for (j in 1 until i) acc += a[j] * r[i - j]
            val k = -acc / e
            if (abs(k) >= 1) return null
            for (j in 0..i) tmp[j] = a[j]
            for (j in 1 until i) a[j] = tmp[j] + k * tmp[i - j]
            a[i] = k
            e *= (1 - k * k)
            if (e <= 0) return null
        }
        return a
    }

    /** Roots of a[0] z^p + a[1] z^(p-1) + ... + a[p] (Durand–Kerner). */
    private fun polyRoots(a: DoubleArray): List<Pair<Double, Double>>? {
        val n = a.size - 1
        val cr = DoubleArray(n); val ci = DoubleArray(n)
        for (i in 0 until n) { // initial guesses on a circle of radius 0.9
            val ang = 2 * PI * i / n + 0.4
            cr[i] = 0.9 * cos(ang); ci[i] = 0.9 * sin(ang)
        }
        for (iter in 0 until 400) {
            var change = 0.0
            for (i in 0 until n) {
                // p(z_i)
                var pr = a[0]; var pi = 0.0
                for (k in 1..n) {
                    val nr = pr * cr[i] - pi * ci[i] + a[k]
                    val ni = pr * ci[i] + pi * cr[i]
                    pr = nr; pi = ni
                }
                // prod (z_i - z_j)
                var qr = a[0]; var qi = 0.0
                for (j in 0 until n) if (j != i) {
                    val dr = cr[i] - cr[j]; val di = ci[i] - ci[j]
                    val nr = qr * dr - qi * di; val ni = qr * di + qi * dr
                    qr = nr; qi = ni
                }
                val den = qr * qr + qi * qi
                if (den < 1e-300) continue
                val sr = (pr * qr + pi * qi) / den
                val si = (pi * qr - pr * qi) / den
                cr[i] -= sr; ci[i] -= si
                change = max(change, abs(sr) + abs(si))
            }
            if (change < 1e-10) break
        }
        if (cr.any { it.isNaN() } || ci.any { it.isNaN() }) return null
        return (0 until n).map { cr[it] to ci[it] }
    }

    // ---------------------------------------------------------------- vocal weight (H1–H2)

    /**
     * H1*–H2*: harmonic difference corrected for the boost the first two formants give
     * each harmonic (Iseli & Alwan 2004), so the value reflects the voice source, not the vowel.
     */
    private fun h1h2(x: FloatArray, off: Int, f0: Float, f1: Float, f2: Float): Float {
        for (i in 0 until FRAME) { re[i] = (x[off + i] * hann[i]).toDouble(); im[i] = 0.0 }
        fft(re, im)
        val binHz = rate.toDouble() / FRAME
        fun peakDb(f: Double): Double {
            val c = f / binHz
            val lo = max(1, (c - 0.15 * f / binHz - 1).toInt())
            val hi = min(FRAME / 2 - 1, (c + 0.15 * f / binHz + 1).toInt())
            var best = 0.0
            for (k in lo..hi) { val m = re[k] * re[k] + im[k] * im[k]; if (m > best) best = m }
            return 10 * log10(best + 1e-20)
        }
        if (2 * f0 > rate / 2 - 200) return Float.NaN
        var h1 = peakDb(f0.toDouble()); var h2 = peakDb(2.0 * f0)
        if (f1 > 0) { h1 -= formantGainDb(f0.toDouble(), f1.toDouble(), 80.0); h2 -= formantGainDb(2.0 * f0, f1.toDouble(), 80.0) }
        if (f2 > 0) { h1 -= formantGainDb(f0.toDouble(), f2.toDouble(), 120.0); h2 -= formantGainDb(2.0 * f0, f2.toDouble(), 120.0) }
        return (h1 - h2).toFloat()
    }

    /** Gain (dB, relative to DC) of a two-pole formant resonance at frequency f. */
    private fun formantGainDb(f: Double, fc: Double, bw: Double): Double {
        val r = kotlin.math.exp(-PI * bw / rate)
        val th = 2 * PI * fc / rate; val ph = 2 * PI * f / rate
        val num = 1 - 2 * r * cos(th) + r * r
        val den = sqrt((1 - 2 * r * cos(th - ph) + r * r) * (1 - 2 * r * cos(th + ph) + r * r))
        return 20 * log10(num / den)
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/** Scores 0–100 used by the meters and reports. */
object VoiceScores {
    /** Average formant spacing (Hz) from F1–F3, a stand-in for vocal tract length. */
    fun spacing(f1: Float, f2: Float, f3: Float): Float {
        if (f1 <= 0 || f2 <= 0 || f3 <= 0) return 0f
        return (0.5f * f1 + 1.5f * f2 + 2.5f * f3) / 8.75f
    }
    /**
     * Formant spacing ~1000 Hz (vocal tract ~17.5 cm, typical adult male) -> ~0–15,
     * ~1190 Hz (~14.7 cm, typical adult female) -> ~75–80.
     */
    fun resonance(spacing: Float): Float = ((spacing - 1010f) / 240f * 100f).coerceIn(0f, 100f)
    /** H1*–H2*: pressed/heavy ~0 dB -> 0, typical male ~4 dB -> 33, typical female ~10 dB -> 83. */
    fun weight(h1h2: Float): Float = (h1h2 / 12f * 100f).coerceIn(0f, 100f)
    /** HNR 22 dB+ -> 0 (clear), 10 dB -> 100 (breathy). */
    fun breathiness(hnr: Float): Float = ((22f - hnr) / 12f * 100f).coerceIn(0f, 100f)
    /** Display mapping for volume: 40 dB -> 0, 85 dB -> 100. */
    fun loudness(db: Float): Float = ((db - 40f) / 45f * 100f).coerceIn(0f, 100f)
    fun semitones(f: Float, ref: Float) = (12.0 * ln(f / ref.toDouble()) / ln(2.0)).toFloat()
    fun noteName(f: Float): String {
        if (f <= 0f) return ""
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val m = Math.round(69 + 12 * ln(f / 440.0) / ln(2.0)).toInt()
        return names[((m % 12) + 12) % 12] + (m / 12 - 1)
    }
}
