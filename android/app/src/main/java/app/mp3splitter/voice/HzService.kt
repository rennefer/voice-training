package app.mp3splitter.voice

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import app.mp3splitter.R
import kotlin.math.abs

/**
 * "Live Hz": shows your pitch while you talk in other apps — a small floating brass bubble
 * (if "display over other apps" is allowed) plus a lock-screen notification.
 *
 * Battery: only pitch is measured (no resonance/weight), about 10 times a second; the bubble
 * redraws only when the number changes, the notification at most once a second; it stops by
 * itself after 5 minutes without voice and after 60 minutes in total.
 */
class HzService : Service() {
    companion object {
        private const val CHANNEL = "live_hz"
        private const val NOTIF_ID = 42
        private const val ACTION_STOP = "app.mp3splitter.voice.HZ_STOP"
        private const val RATE = 44100
        private const val SILENCE_STOP_MS = 5 * 60_000L
        private const val MAX_MS = 60 * 60_000L

        @Volatile var running = false
            private set

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, HzService::class.java))
        fun stop(ctx: Context) { if (running) ctx.startService(Intent(ctx, HzService::class.java).setAction(ACTION_STOP)) }
        fun canOverlay(ctx: Context) = Settings.canDrawOverlays(ctx)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    private var thread: Thread? = null
    private var bubble: Bubble? = null
    private var wm: WindowManager? = null
    private lateinit var settings: VoiceSettings
    private var lastNotifText = ""
    private var lastNotifAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopEverything(); return START_NOT_STICKY }
        if (active) return START_NOT_STICKY
        settings = VoiceSettings(this)
        val n = notification("Listening…", "Speak and your pitch appears here")
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        active = true; running = true
        if (canOverlay(this)) showBubble()
        thread = Thread({ loop() }, "live-hz").also { it.start() }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- audio

    @SuppressLint("MissingPermission")   // checked by VoiceActivity before starting
    private fun loop() {
        val frame = VoiceAnalyzer.FRAME
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, frame * 4))
        } catch (e: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { main.post { stopEverything() }; return }
        val analyzer = VoiceAnalyzer(RATE)
        val pcm = ShortArray(frame); val x = FloatArray(frame)
        val recent = ArrayDeque<Pair<Long, Float>>()   // (time, Hz) of voiced frames
        val started = SystemClock.elapsedRealtime(); var lastVoice = started
        var shown = -1
        rec.startRecording()
        try {
            while (active) {
                var got = 0
                while (got < frame && active) { val r = rec.read(pcm, got, frame - got); if (r <= 0) break; got += r }
                if (got < frame) break
                // analyse every other block (~10/s) to save battery; the other one is just read and dropped
                val now = SystemClock.elapsedRealtime()
                for (i in 0 until frame) x[i] = pcm[i] / 32768f
                val f0 = analyzer.pitch(x)
                if (f0 > 0f) { recent.addLast(now to f0); lastVoice = now }
                while (recent.isNotEmpty() && now - recent.first().first > 900) recent.removeFirst()
                val value = if (recent.isEmpty()) 0 else recent.map { it.second }.sorted()[recent.size / 2].toInt()
                if (value != shown) { shown = value; main.post { show(value) } }
                if (now - lastVoice > SILENCE_STOP_MS || now - started > MAX_MS) { main.post { stopEverything() }; break }
                var skip = 0
                while (skip < frame && active) { val r = rec.read(pcm, 0, frame - skip); if (r <= 0) break; skip += r }
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    // ---------------------------------------------------------------- display

    private fun show(hz: Int) {
        if (!active) return
        val tg = settings.target; val tol = settings.tolerance
        val state = when { hz <= 0 -> 0; hz < tg - tol -> -1; hz > tg + tol -> 1; else -> 2 }
        bubble?.set(hz, state)
        val title = if (hz <= 0) "– Hz" else "$hz Hz · " + when (state) { -1 -> "${(tg - hz).toInt()} under"; 1 -> "${(hz - tg).toInt()} over"; else -> "on target" }
        val now = SystemClock.elapsedRealtime()
        if (title != lastNotifText && now - lastNotifAt >= 1000) {
            lastNotifText = title; lastNotifAt = now
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID,
                notification(title, "Target ${tg.toInt()} ±${tol.toInt()} Hz" + if (bubble != null) " · long-press the bubble to stop" else ""))
        }
    }

    private fun notification(title: String, text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Live Hz", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        val open = PendingIntent.getActivity(this, 10, Intent(this, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 11, Intent(this, HzService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun showBubble() {
        val w = getSystemService(WindowManager::class.java); wm = w
        val b = Bubble(this); bubble = b
        val prefs = getSharedPreferences("voice", MODE_PRIVATE)
        val size = (68 * resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("bubbleX", resources.displayMetrics.widthPixels - size - 24)
            y = prefs.getInt("bubbleY", resources.displayMetrics.heightPixels / 3)
        }
        // drag to move; long-press (without moving) to stop
        var dx = 0f; var dy = 0f; var moved = false
        val longPress = Runnable { if (!moved) { b.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); stopEverything() } }
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = lp.x - e.rawX; dy = lp.y - e.rawY; moved = false; main.postDelayed(longPress, 600) }
                MotionEvent.ACTION_MOVE -> {
                    val nx = (e.rawX + dx).toInt(); val ny = (e.rawY + dy).toInt()
                    if (abs(nx - lp.x) > 6 || abs(ny - lp.y) > 6 || moved) {
                        moved = true; main.removeCallbacks(longPress)
                        lp.x = nx; lp.y = ny
                        try { w.updateViewLayout(b, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    if (moved) prefs.edit().putInt("bubbleX", lp.x).putInt("bubbleY", lp.y).apply()
                }
            }
            true
        }
        try { w.addView(b, lp) } catch (e: Exception) { bubble = null }
    }

    private fun stopEverything() {
        active = false; running = false
        thread?.join(500); thread = null
        bubble?.let { try { wm?.removeView(it) } catch (_: Exception) {} }; bubble = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (active) stopEverything()
        super.onDestroy()
    }

    /** The round brass bubble: Hz in the middle; ▲ = go higher, ▼ = go lower, bright ring = on target. */
    private class Bubble(ctx: Context) : View(ctx) {
        private var hz = 0; private var state = 0
        private val d = ctx.resources.displayMetrics.density
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEE121C26.toInt() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d }
        private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 10 * d; color = 0xFF8FA1B0.toInt() }
        init { contentDescription = "Live pitch. Long-press to stop." }

        fun set(hz: Int, state: Int) { if (hz != this.hz || state != this.state) { this.hz = hz; this.state = state; invalidate() } }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val r = w / 2 - 3 * d
            c.drawCircle(w / 2, w / 2, r, disc)
            if (state == 2) ring.shader = LinearGradient(0f, 0f, w, w, intArrayOf(0xFFEFE1BB.toInt(), 0xFFC8A96C.toInt(), 0xFF9C7B43.toInt()), null, Shader.TileMode.CLAMP)
            else { ring.shader = null; ring.color = if (state == 0) 0x40FFFFFF else 0xFF7FA2C4.toInt() }
            c.drawCircle(w / 2, w / 2, r - 2 * d, ring)
            num.textSize = (if (hz >= 100) 21 else 22) * d
            num.color = if (state == 2) 0xFFEFE1BB.toInt() else 0xFFEEF2F5.toInt()
            c.drawText(if (hz > 0) "$hz" else "–", w / 2, w / 2 + 6 * d, num)
            val label = when (state) { -1 -> "▲ Hz"; 1 -> "▼ Hz"; else -> "Hz" }
            c.drawText(label, w / 2, w / 2 + 19 * d, small)
        }
    }
}
