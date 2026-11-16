package app.mp3splitter.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tanh

/**
 * "Even out volume": a simple feed-forward compressor with make-up gain and a soft ceiling.
 * Quiet speech is lifted, loud peaks are held down, nothing clips.
 * Works on 16-bit sample values stored in an IntArray (in place).
 */
class Compressor(
    sampleRate: Int = IN_RATE,
    private val thresholdDb: Float = -26f,
    private val ratio: Float = 3f,
    attackMs: Float = 5f,
    releaseMs: Float = 180f,
    makeupDb: Float = 9f,
    private val ceiling: Float = 0.89f,
) {
    private val attack = exp(-1.0 / (sampleRate * attackMs / 1000.0)).toFloat()
    private val release = exp(-1.0 / (sampleRate * releaseMs / 1000.0)).toFloat()
    private val makeup = 10f.pow(makeupDb / 20f)
    private var env = 0f
    private var gain = makeup

    fun process(buf: IntArray, n: Int) {
        val slope = 1f - 1f / ratio
        for (i in 0 until n) {
            val x = buf[i] / 32768f
            val a = abs(x)
            env = if (a > env) a + attack * (env - a) else a + release * (env - a)
            // gain is recomputed every 16 samples; the envelope moves slowly enough
            if (i and 15 == 0) {
                val envDb = 20f * log10(env + 1e-9f)
                val over = envDb - thresholdDb
                val reduceDb = if (over > 0f) -over * slope else 0f
                gain = 10f.pow(reduceDb / 20f) * makeup
            }
            var y = x * gain
            val ay = abs(y)
            if (ay > ceiling) {
                val soft = ceiling + (1f - ceiling) * tanh(((ay - ceiling) / (1f - ceiling)).toDouble()).toFloat()
                y = if (y > 0) soft else -soft
            }
            buf[i] = (y * 32767f).toInt().coerceIn(-32768, 32767)
        }
    }
}
