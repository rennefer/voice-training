package app.mp3splitter.core

import mp3.Mp3Engine
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException

/** Reads the raw recording (44.1 kHz mono 16-bit LE) and writes one MP3 file per part. */
class PartEncoder(private val pcm: File) {

    val totalSamples: Long get() = pcm.length() / 2

    /**
     * Encode one part. [onProgress] gets the number of newly encoded input samples.
     * [isCancelled] is polled between chunks.
     */
    fun encode(
        part: Part,
        quality: Quality,
        evenOut: Boolean,
        out: File,
        onProgress: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Long {
        val chunk = 8192
        val bytes = ByteArray(chunk * 2)
        val samples = IntArray(chunk)
        val mp3 = ByteArray(Mp3Engine.outBufferSize(chunk))
        val comp = if (evenOut) Compressor() else null
        val engine = Mp3Engine(1, IN_RATE, quality.outRate, quality.kbps, true, 5)
        try {
            RandomAccessFile(pcm, "r").use { raf ->
                // Let the compressor settle on up to 1 s of audio before the part starts,
                // so a part doesn't begin with a jump in volume.
                if (comp != null) {
                    var pos = maxOf(0L, part.start - IN_RATE)
                    raf.seek(pos * 2)
                    while (pos < part.start) {
                        val n = minOf(chunk.toLong(), part.start - pos).toInt()
                        readSamples(raf, bytes, samples, n)
                        comp.process(samples, n)
                        pos += n
                    }
                }
                raf.seek(part.start * 2)
                BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { os ->
                    var pos = part.start
                    while (pos < part.end) {
                        if (isCancelled()) throw CancellationException()
                        val n = minOf(chunk.toLong(), part.end - pos).toInt()
                        readSamples(raf, bytes, samples, n)
                        comp?.process(samples, n)
                        val w = engine.encode(samples, null, n, mp3)
                        os.write(mp3, 0, w)
                        pos += n
                        onProgress(n.toLong())
                    }
                    val w = engine.flush(mp3)
                    os.write(mp3, 0, w)
                }
            }
        } finally {
            engine.close()
        }
        return out.length()
    }

    private fun readSamples(raf: RandomAccessFile, bytes: ByteArray, samples: IntArray, n: Int) {
        raf.readFully(bytes, 0, n * 2)
        for (i in 0 until n) {
            val lo = bytes[2 * i].toInt() and 0xff
            val hi = bytes[2 * i + 1].toInt()
            samples[i] = (hi shl 8) or lo
        }
    }

    /** Max-abs peaks (0..1) over [buckets] equal slices of the whole recording. */
    fun peaks(buckets: Int): FloatArray {
        val total = totalSamples
        val result = FloatArray(buckets)
        if (total == 0L) return result
        val buf = ByteArray(1 shl 16)
        RandomAccessFile(pcm, "r").use { raf ->
            var idx = 0L
            while (true) {
                val read = raf.read(buf)
                if (read <= 0) break
                var i = 0
                while (i + 1 < read) {
                    val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff))
                    val a = if (v < 0) -v else v
                    val b = (idx * buckets / total).toInt().coerceAtMost(buckets - 1)
                    val f = a / 32768f
                    if (f > result[b]) result[b] = f
                    idx++
                    i += 2
                }
            }
        }
        return result
    }
}
