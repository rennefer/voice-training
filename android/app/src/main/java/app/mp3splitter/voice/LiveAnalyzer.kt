package app.mp3splitter.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper

/**
 * Captures the microphone (44.1 kHz mono) and analyses a 2048-sample frame every 1024 samples
 * (~23 ms). Frames are delivered on the main thread. Call start()/stop() from the main thread.
 */
class LiveAnalyzer(
    private val onFrame: (VoiceFrame) -> Unit,
    private val onError: (String) -> Unit,
    private val pcmOut: java.io.File? = null,   // raw 16-bit audio is appended here (for playback)
) {
    companion object {
        const val RATE = 44100
        const val HOP = 1024
        const val FRAME_SECONDS = HOP.toFloat() / RATE
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())
    val isRunning get() = running

    @SuppressLint("MissingPermission") // checked by the activity before start()
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "voice-analyzer").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE / 2))
        } catch (e: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release(); running = false
            main.post { onError("Could not open the microphone") }
            return
        }
        val analyzer = VoiceAnalyzer(RATE)
        val window = FloatArray(VoiceAnalyzer.FRAME)
        val shorts = ShortArray(HOP)
        val bytes = ByteArray(HOP * 2)
        val out = pcmOut?.let { try { java.io.BufferedOutputStream(java.io.FileOutputStream(it, true), 1 shl 16) } catch (e: Exception) { null } }
        try {
            rec.startRecording()
            var filled = 0
            while (running) {
                var got = 0
                while (got < HOP && running) {
                    val n = rec.read(shorts, got, HOP - got)
                    if (n < 0) { running = false; main.post { onError("Microphone stopped") }; break }
                    got += n
                }
                if (!running) break
                out?.let { o ->
                    for (i in 0 until HOP) { val v = shorts[i].toInt(); bytes[2 * i] = (v and 0xff).toByte(); bytes[2 * i + 1] = (v shr 8).toByte() }
                    o.write(bytes)
                }
                // slide the window by one hop
                System.arraycopy(window, HOP, window, 0, VoiceAnalyzer.FRAME - HOP)
                for (i in 0 until HOP) window[VoiceAnalyzer.FRAME - HOP + i] = shorts[i] / 32768f
                filled += HOP
                if (filled < VoiceAnalyzer.FRAME) continue
                val fr = analyzer.analyze(window)
                main.post { if (running) onFrame(fr) }
            }
            rec.stop()
        } catch (e: Exception) {
            main.post { onError("Microphone error: ${e.message}") }
        } finally {
            try { out?.close() } catch (_: Exception) {}
            rec.release()
            running = false
        }
    }
}
