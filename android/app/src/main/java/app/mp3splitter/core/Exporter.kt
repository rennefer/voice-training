package app.mp3splitter.core

import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class ExportedPart(val file: File, val bytes: Long, val samples: Long)

/** Plans the parts, encodes them in parallel and checks every file is under the limit. */
class Exporter(pcm: File, private val outDir: File) {
    private val encoder = PartEncoder(pcm)

    fun run(
        start: Long,
        end: Long,
        quality: Quality,
        evenOut: Boolean,
        limitBytes: Long?,
        baseName: String,
        threads: Int,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): List<ExportedPart> {
        outDir.mkdirs()
        outDir.listFiles()?.forEach { it.delete() }
        var fill = SplitPlan.FILL
        repeat(4) {
            val parts = SplitPlan.plan(start, end, quality.kbps, limitBytes, fill)
            val result = encodeAll(parts, quality, evenOut, baseName, threads, onProgress, isCancelled)
            if (limitBytes == null || result.all { it.bytes <= limitBytes }) return result
            // Should not happen with constant bitrate, but never hand over a file that's too big.
            result.forEach { it.file.delete() }
            fill -= 0.02
        }
        throw IllegalStateException("Could not fit the parts under the size limit")
    }

    private fun encodeAll(
        parts: List<Part>,
        quality: Quality,
        evenOut: Boolean,
        baseName: String,
        threads: Int,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): List<ExportedPart> {
        val total = parts.sumOf { it.samples }.toFloat()
        val done = AtomicLong()
        onProgress(0f)
        val pool = Executors.newFixedThreadPool(threads.coerceIn(1, parts.size))
        try {
            val futures = parts.map { part ->
                pool.submit<ExportedPart> {
                    val name = if (parts.size == 1) "$baseName.mp3"
                    else "${baseName}_part${part.index + 1}of${parts.size}.mp3"
                    val f = File(outDir, name)
                    val bytes = encoder.encode(part, quality, evenOut, f,
                        onProgress = { n -> onProgress(done.addAndGet(n) / total) },
                        isCancelled = isCancelled)
                    ExportedPart(f, bytes, part.samples)
                }
            }
            return futures.map {
                try {
                    it.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
