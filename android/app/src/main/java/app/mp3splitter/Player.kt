package app.mp3splitter

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import app.mp3splitter.core.IN_RATE
import java.io.File
import java.io.RandomAccessFile

/** Plays a range of the raw recording. Callbacks arrive on the player thread. */
class Player(private val pcm: File) {
    @Volatile private var playing = false
    private var thread: Thread? = null

    val isPlaying get() = playing

    fun play(from: Long, to: Long, onPosition: (Long) -> Unit, onDone: () -> Unit) {
        stop()
        playing = true
        thread = Thread({
            val minBuf = AudioTrack.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(IN_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBuf, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            try {
                RandomAccessFile(pcm, "r").use { raf ->
                    raf.seek(from * 2)
                    val buf = ByteArray(4096)
                    var pos = from
                    track.play()
                    while (playing && pos < to) {
                        val n = minOf(buf.size.toLong(), (to - pos) * 2).toInt()
                        raf.readFully(buf, 0, n)
                        track.write(buf, 0, n)
                        pos += n / 2
                        // position of what is audible, not of what was queued
                        onPosition(from + track.playbackHeadPosition.toLong())
                    }
                    if (playing) {
                        // let the last buffer drain
                        val end = to - from
                        var guard = 0
                        while (playing && track.playbackHeadPosition < end && guard++ < 100) {
                            Thread.sleep(10)
                            onPosition(from + track.playbackHeadPosition.toLong())
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { track.stop() } catch (_: Exception) {}
                track.release()
                playing = false
                onDone()
            }
        }, "player").also { it.start() }
    }

    fun stop() {
        playing = false
        thread?.join(500)
        thread = null
    }
}
