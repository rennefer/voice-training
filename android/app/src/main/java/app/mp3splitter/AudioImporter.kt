package app.mp3splitter

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import app.mp3splitter.core.Resampler
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/**
 * Decodes any audio file Android can play (m4a/AAC call recordings, mp3, wav, ogg, amr, flac…)
 * into the app's working file: 44.1 kHz mono 16-bit PCM.
 */
object AudioImporter {

    fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { null }

    /** Writes to [dest] via a temp file so a failed import never destroys the current recording. */
    fun import(
        ctx: Context,
        uri: Uri,
        dest: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val tmp = File(dest.parentFile, dest.name + ".importing")
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ctx.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("This file has no audio")
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L

            codec = try { MediaCodec.createDecoderByType(mime) }
            catch (e: Exception) { throw IllegalArgumentException("This phone can't decode $mime") }
            codec.configure(inFormat, null, null, 0)
            codec.start()

            BufferedOutputStream(FileOutputStream(tmp), 1 shl 16).use { os ->
                var resampler: Resampler? = null
                var channels = 1
                var isFloat = false
                var shorts = ShortArray(0)
                var floats = FloatArray(0)
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false

                fun setupFrom(f: MediaFormat) {
                    val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    resampler = Resampler(rate, channels, os)
                }

                while (!outputDone) {
                    if (isCancelled()) throw CancellationException()
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            resampler?.finish()
                            setupFrom(codec.outputFormat)
                        }
                        o >= 0 -> {
                            if (resampler == null) setupFrom(codec.getOutputFormat(o))
                            val r = resampler!!
                            val buf = codec.getOutputBuffer(o)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.nativeOrder())
                            if (isFloat) {
                                val fb = buf.asFloatBuffer()
                                val n = fb.remaining()
                                if (floats.size < n) floats = FloatArray(n)
                                fb.get(floats, 0, n)
                                r.writeFloat(floats, n / channels)
                            } else {
                                val sb = buf.asShortBuffer()
                                val n = sb.remaining()
                                if (shorts.size < n) shorts = ShortArray(n)
                                sb.get(shorts, 0, n)
                                r.write16(shorts, n / channels)
                            }
                            if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                resampler?.finish() ?: throw IllegalArgumentException("Could not read any audio from this file")
            }
            if (tmp.length() < 2) throw IllegalArgumentException("The file is empty")
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            extractor.release()
        }
    }
}
