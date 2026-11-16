package app.mp3splitter.voice

import kotlin.math.sqrt

/** Standard speaking ranges used in the app (typical adult averages). */
object Ranges {
    const val MALE_LO = 85f
    const val MALE_HI = 155f
    const val FEMALE_LO = 165f
    const val FEMALE_HI = 255f
}

/** Summary of a stretch of voice (a session or one exercise). */
data class SessionStats(
    val voicedSeconds: Float,
    val medianF0: Float,
    val lowF0: Float,          // 5th percentile
    val highF0: Float,         // 95th percentile
    val pctFemale: Float,      // 0..100 share of voiced time
    val pctMale: Float,
    val pctOnTarget: Float,
    val melody: Float,         // standard deviation in semitones
    val f1: Float, val f2: Float, val f3: Float,
    val resonance: Float,      // 0..100
    val h1h2: Float,
    val weight: Float,         // 0..100
    val volumeDb: Float,
    val hnr: Float,
    val breathiness: Float,    // 0..100
    val hist: IntArray,        // voiced frames per 10 Hz bin from HIST_LO
) {
    companion object {
        const val HIST_LO = 60
        const val HIST_BINS = 25   // 60..310 Hz

        fun compute(frames: List<VoiceFrame>, frameSeconds: Float, target: Float, tolerance: Float): SessionStats? {
            val v = frames.filter { it.voiced }
            if (v.size < 3) return null
            val f0s = v.map { it.f0 }.sorted()
            val med = pct(f0s, 0.5f)
            fun share(pred: (Float) -> Boolean) = v.count { pred(it.f0) } * 100f / v.size
            val semis = v.map { VoiceScores.semitones(it.f0, med) }
            val mean = semis.average().toFloat()
            val sd = sqrt(semis.map { (it - mean) * (it - mean) }.average()).toFloat()
            val withF = v.filter { it.f1 > 0 && it.f2 > 0 && it.f3 > 0 }
            val f1 = medianOf(withF.map { it.f1 })
            val f2 = medianOf(withF.map { it.f2 })
            val f3 = medianOf(withF.map { it.f3 })
            val spacing = medianOf(withF.map { VoiceScores.spacing(it.f1, it.f2, it.f3) })
            val h = medianOf(v.map { it.h1h2 }.filter { !it.isNaN() })
            val hnr = medianOf(v.map { it.hnr })
            val hist = IntArray(HIST_BINS)
            for (fr in v) {
                val b = ((fr.f0 - HIST_LO) / 10f).toInt()
                if (b in 0 until HIST_BINS) hist[b]++
            }
            return SessionStats(
                voicedSeconds = v.size * frameSeconds,
                medianF0 = med, lowF0 = pct(f0s, 0.05f), highF0 = pct(f0s, 0.95f),
                pctFemale = share { it >= Ranges.FEMALE_LO && it <= Ranges.FEMALE_HI },
                pctMale = share { it >= Ranges.MALE_LO && it <= Ranges.MALE_HI },
                pctOnTarget = share { it >= target - tolerance && it <= target + tolerance },
                melody = sd,
                f1 = f1, f2 = f2, f3 = f3,
                resonance = if (spacing > 0) VoiceScores.resonance(spacing) else 0f,
                h1h2 = h, weight = VoiceScores.weight(h),
                volumeDb = v.map { it.db }.average().toFloat(),
                hnr = hnr, breathiness = VoiceScores.breathiness(hnr),
                hist = hist,
            )
        }

        private fun pct(sorted: List<Float>, p: Float): Float {
            if (sorted.isEmpty()) return 0f
            val i = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
            return sorted[i]
        }

        fun medianOf(xs: List<Float>): Float = if (xs.isEmpty()) 0f else pct(xs.sorted(), 0.5f)
    }
}

/** Live smoothing for the meters: median of the last few voiced frames. */
class LiveSmoother(private val size: Int = 20) {
    private val f1 = ArrayDeque<Float>(); private val f2 = ArrayDeque<Float>(); private val f3 = ArrayDeque<Float>()
    private val h = ArrayDeque<Float>(); private val hnr = ArrayDeque<Float>()
    private fun ArrayDeque<Float>.push(x: Float) { addLast(x); if (size > this@LiveSmoother.size) removeFirst() }

    fun add(fr: VoiceFrame) {
        if (!fr.voiced) return
        if (fr.f1 > 0 && fr.f2 > 0 && fr.f3 > 0) { f1.push(fr.f1); f2.push(fr.f2); f3.push(fr.f3) }
        if (!fr.h1h2.isNaN()) h.push(fr.h1h2)
        hnr.push(fr.hnr)
    }

    fun resonance(): Float? = if (f1.size < 4) null else
        VoiceScores.resonance(VoiceScores.spacing(SessionStats.medianOf(f1.toList()), SessionStats.medianOf(f2.toList()), SessionStats.medianOf(f3.toList())))
    fun weight(): Float? = if (h.size < 4) null else VoiceScores.weight(SessionStats.medianOf(h.toList()))
    fun breathiness(): Float? = if (hnr.size < 4) null else VoiceScores.breathiness(SessionStats.medianOf(hnr.toList()))
    fun clear() { f1.clear(); f2.clear(); f3.clear(); h.clear(); hnr.clear() }
}
