package app.mp3splitter.core

import kotlin.math.ceil
import kotlin.math.floor

/** Recording format: 44.1 kHz, mono, 16-bit little-endian PCM. */
const val IN_RATE = 44100

enum class Quality(val label: String, val kbps: Int, val outRate: Int) {
    SMALL("Small", 32, 16000),
    VOICE("Voice", 64, 22050),
    GOOD("Good", 96, 44100),
    BEST("Best", 128, 44100);
}

/** One output file: samples [start, end) of the recording. */
data class Part(val index: Int, val start: Long, val end: Long) {
    val samples get() = end - start
}

object SplitPlan {
    /** Largest possible MP3 frame (320 kbps @ 32 kHz) — used as a safety margin. */
    private const val MAX_FRAME_BYTES = 1441.0

    /** Parts are filled to this share of the limit; the rest is headroom. */
    const val FILL = 0.985

    fun bytesPerSecond(kbps: Int) = kbps * 1000.0 / 8.0

    fun estimateBytes(samples: Long, kbps: Int): Long =
        (samples.toDouble() / IN_RATE * bytesPerSecond(kbps) + 2 * MAX_FRAME_BYTES).toLong()

    /** Samples that fit in one part of [limitBytes]. */
    fun samplesPerPart(kbps: Int, limitBytes: Long, fill: Double = FILL): Long {
        val budget = limitBytes * fill - 2 * MAX_FRAME_BYTES
        return floor(budget / bytesPerSecond(kbps) * IN_RATE).toLong()
    }

    /**
     * Split [start, end) into parts no bigger than [limitBytes] (null = one file).
     * Parts are filled up to the limit; the last part holds the remainder.
     */
    fun plan(start: Long, end: Long, kbps: Int, limitBytes: Long?, fill: Double = FILL): List<Part> {
        require(end > start) { "Empty selection" }
        if (limitBytes == null) return listOf(Part(0, start, end))
        val per = samplesPerPart(kbps, limitBytes, fill)
        require(per >= IN_RATE) { "Part size too small for this quality" }
        val total = end - start
        val count = ceil(total.toDouble() / per).toInt()
        return (0 until count).map { i ->
            Part(i, start + i * per, minOf(end, start + (i + 1) * per))
        }
    }
}
