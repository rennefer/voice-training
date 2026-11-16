package app.mp3splitter.core

import java.io.OutputStream

/**
 * Streaming converter from any decoded PCM (any rate, any channel count, 16-bit or float)
 * to the app's recording format: 44.1 kHz mono 16-bit little-endian.
 * Channels are averaged. Rate is converted with linear interpolation, after a short
 * box filter when going down so it doesn't alias (call recordings are mostly 8-48 kHz speech).
 */
class Resampler(private val inRate: Int, private val channels: Int, private val out: OutputStream) {
    private val step = inRate.toDouble() / IN_RATE // input samples per output sample
    private var pos = 0.0                          // position of next output sample, in input samples, relative to `prev`
    private var prev = 0f
    private var havePrev = false
    private val buf = ByteArray(1 shl 15)
    private var bufLen = 0
    var written = 0L
        private set

    // a 2-tap average is enough for 48k -> 44.1k; nothing needed when upsampling
    private val smooth = inRate > IN_RATE
    private var last = 0f

    fun write16(data: ShortArray, frames: Int) {
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += data[f * channels + c]
            push(sum / channels / 32768f)
        }
    }

    fun writeFloat(data: FloatArray, frames: Int) {
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += data[f * channels + c]
            push(sum / channels)
        }
    }

    private fun push(x0: Float) {
        var x = x0
        if (smooth) { val y = (x + last) * 0.5f; last = x; x = y }
        if (!havePrev) { prev = x; havePrev = true; return }
        // emit every output sample whose position falls in [prev, x)
        while (pos < 1.0) {
            val v = prev + (x - prev) * pos.toFloat()
            emit(v)
            pos += step
        }
        pos -= 1.0
        prev = x
    }

    private fun emit(v: Float) {
        val s = (v * 32767f).toInt().coerceIn(-32768, 32767)
        buf[bufLen++] = (s and 0xff).toByte()
        buf[bufLen++] = (s shr 8).toByte()
        written++
        if (bufLen == buf.size) { out.write(buf, 0, bufLen); bufLen = 0 }
    }

    fun finish() {
        if (havePrev) emit(prev)
        if (bufLen > 0) { out.write(buf, 0, bufLen); bufLen = 0 }
        out.flush()
    }
}
