package app.mp3splitter

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import app.mp3splitter.core.IN_RATE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Records the microphone to a raw PCM file (44.1 kHz, mono, 16-bit LE).
 * Runs as a foreground service so recording keeps going with the screen off.
 * Android requires a notification while the microphone is in use; it disappears when you stop.
 */
class RecordService : Service() {

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 1

        private val _recording = MutableStateFlow(false)
        private val _samples = MutableStateFlow(0L)
        private val _level = MutableStateFlow(0f)
        private val _error = MutableStateFlow<String?>(null)
        val recording: StateFlow<Boolean> = _recording
        val samples: StateFlow<Long> = _samples
        val level: StateFlow<Float> = _level
        val error: StateFlow<String?> = _error

        fun pcmFile(ctx: Context) = File(ctx.filesDir, "recording.pcm")

        fun start(ctx: Context) {
            val i = Intent(ctx, RecordService::class.java).setAction(ACTION_START)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RecordService::class.java).setAction(ACTION_STOP))
        }

        fun clearError() { _error.value = null }
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!running) begin()
            ACTION_STOP -> end()
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mp3Splitter:rec").apply { acquire(6 * 60 * 60 * 1000L) }

        _error.value = null
        _samples.value = 0
        _level.value = 0f
        running = true
        _recording.value = true
        thread = Thread({ recordLoop() }, "recorder").also { it.start() }
    }

    private fun end() {
        running = false
        thread?.join(2000)
        thread = null
        finishUp()
    }

    private fun finishUp() {
        _recording.value = false
        _level.value = 0f
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("MissingPermission") // checked in MainActivity before starting
    private fun recordLoop() {
        val minBuf = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, IN_RATE) // ~0.5 s of audio in bytes
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, IN_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize)
        } catch (e: Exception) {
            fail("Could not open the microphone"); return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); fail("Could not open the microphone"); return
        }
        val shorts = ShortArray(IN_RATE / 20) // 50 ms
        val bytes = ByteArray(shorts.size * 2)
        try {
            BufferedOutputStream(FileOutputStream(pcmFile(this)), 1 shl 16).use { os ->
                rec.startRecording()
                var total = 0L
                while (running) {
                    val n = rec.read(shorts, 0, shorts.size)
                    if (n < 0) { fail("Microphone stopped (error $n)"); break }
                    var peak = 0
                    for (i in 0 until n) {
                        val v = shorts[i].toInt()
                        bytes[2 * i] = (v and 0xff).toByte()
                        bytes[2 * i + 1] = (v shr 8).toByte()
                        val a = abs(v)
                        if (a > peak) peak = a
                    }
                    os.write(bytes, 0, n * 2)
                    total += n
                    _samples.value = total
                    _level.value = peak / 32768f
                }
                rec.stop()
            }
        } catch (e: Exception) {
            fail("Recording failed: ${e.message}")
        } finally {
            rec.release()
        }
    }

    private fun fail(msg: String) {
        _error.value = msg
        if (running) {
            running = false
            android.os.Handler(mainLooper).post { finishUp() }
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, RecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Recording")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
