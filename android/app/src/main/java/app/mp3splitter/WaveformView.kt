package app.mp3splitter

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import app.mp3splitter.core.IN_RATE
import kotlin.math.abs

/** Waveform of the whole recording with two draggable trim handles and a playhead. */
class WaveformView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var peaks: FloatArray? = null
        set(v) { field = v; invalidate() }
    var totalSamples = 0L
        set(v) { field = v; invalidate() }
    var selStart = 0L
        private set
    var selEnd = 0L
        private set
    var playhead = -1L
        set(v) { field = v; invalidate() }
    /** Called whenever a handle moves. */
    var onSelectionChanged: ((start: Long, end: Long) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val barIn = Paint(Paint.ANTI_ALIAS_FLAG)          // brass, shader set in onSizeChanged
    private val barOut = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Brass.RING_OFF }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC8A96C.toInt() }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)            // polished brass knob
    private val knobShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val play = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Brass.TEXT; strokeWidth = 2 * dp }
    private val shade = Paint().apply { color = 0x660D141C }
    private val knobR = 9 * dp
    private val rect = RectF()
    private var dragging = 0 // 0 none, 1 start, 2 end
    private val pad = 12 * dp

    fun setSelection(start: Long, end: Long, notify: Boolean = true) {
        val minGap = IN_RATE.toLong()
        var s = start.coerceIn(0, maxOf(0, totalSamples - minGap))
        var e = end.coerceIn(minOf(totalSamples, s + minGap), totalSamples)
        if (e - s < minGap) { s = maxOf(0, e - minGap) }
        selStart = s; selEnd = e
        invalidate()
        if (notify) onSelectionChanged?.invoke(selStart, selEnd)
    }

    private fun xOf(sample: Long): Float {
        if (totalSamples <= 0) return pad
        return pad + (width - 2 * pad) * (sample.toFloat() / totalSamples)
    }

    private fun sampleAt(x: Float): Long {
        if (totalSamples <= 0) return 0
        val f = ((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        return (f * totalSamples).toLong()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        barIn.shader = LinearGradient(0f, h * 0.08f, 0f, h * 0.92f,
            intArrayOf(0xFFEFE1BB.toInt(), 0xFFC8A96C.toInt(), 0xFF8A6D3B.toInt(), 0xFFC8A96C.toInt(), 0xFFEFE1BB.toInt()),
            floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val p = peaks ?: return
        val h = height.toFloat()
        val mid = (h - knobR) / 2
        val step = 3 * dp
        val barW = 2 * dp
        val usable = width - 2 * pad
        var x = pad
        while (x < pad + usable) {
            val f0 = (x - pad) / usable
            val f1 = (x + step - pad) / usable
            val b0 = (f0 * p.size).toInt().coerceIn(0, p.size - 1)
            val b1 = (f1 * p.size).toInt().coerceIn(b0 + 1, p.size)
            var m = 0f
            for (i in b0 until b1) if (p[i] > m) m = p[i]
            // gentle curve so quiet speech is still visible
            val amp = Math.sqrt(m.toDouble()).toFloat()
            val bh = maxOf(1.5f * dp, amp * (mid * 0.92f))
            val sample = (f0 * totalSamples).toLong()
            val inside = sample in selStart until selEnd
            rect.set(x, mid - bh, x + barW, mid + bh)
            canvas.drawRoundRect(rect, barW / 2, barW / 2, if (inside) barIn else barOut)
            x += step
        }
        val xs = xOf(selStart)
        val xe = xOf(selEnd)
        canvas.drawRect(0f, 0f, xs, h, shade)
        canvas.drawRect(xe, 0f, width.toFloat(), h, shade)
        drawHandle(canvas, xs, h)
        drawHandle(canvas, xe, h)
        if (playhead >= 0) {
            val xp = xOf(playhead)
            canvas.drawLine(xp, 0f, xp, h, play)
        }
    }

    private fun drawHandle(c: Canvas, x: Float, h: Float) {
        c.drawRect(x - 1 * dp, 0f, x + 1 * dp, h - knobR, line)
        val cy = h - knobR - 1 * dp
        c.drawCircle(x, cy + 2 * dp, knobR, knobShadow)
        knob.shader = LinearGradient(x - knobR, cy - knobR, x + knobR, cy + knobR, Brass.METAL,
            floatArrayOf(0f, 0.3f, 0.58f, 0.8f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, cy, knobR, knob)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (totalSamples <= 0 || !isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val ds = abs(e.x - xOf(selStart))
                val de = abs(e.x - xOf(selEnd))
                dragging = if (ds <= de) 1 else 2
                parent?.requestDisallowInterceptTouchEvent(true)
                move(e.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> { move(e.x); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = 0
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun move(x: Float) {
        val s = sampleAt(x)
        if (dragging == 1) setSelection(minOf(s, selEnd - IN_RATE), selEnd)
        else if (dragging == 2) setSelection(selStart, maxOf(s, selStart + IN_RATE))
    }
}
