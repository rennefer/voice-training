package app.mp3splitter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * Loudness meter: a ring of 40 brass segments (same as FineVolume's dial) that light up
 * with how loud the microphone hears you. Display only.
 */
class LevelDial @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private companion object {
        const val SEGMENTS = 40
        const val START = 135f   // bottom-left
        const val SWEEP = 270f   // to bottom-right, through the top
        const val FLOOR_DB = -50f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val density = resources.displayMetrics.density
    private var shown = 0f   // 0..1, what is drawn (falls back slowly)
    private var lastFrame = 0L

    /** Peak of the last ~50 ms, 0..1 linear. 0 turns the dial off. */
    fun setLevel(peak: Float) {
        val target = if (peak <= 0f) 0f else
            ((20f * log10(peak) - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
        if (target >= shown) shown = target   // rise at once, fall in onDraw
        invalidate()
    }

    fun reset() { shown = 0f; invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        val size = MeasureSpec.getSize(w)
        val s = minOf(size, (290 * density).toInt())
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        if (lastFrame != 0L && shown > 0f) {
            val dt = (now - lastFrame) / 1e9f
            shown = (shown - dt * 0.9f).coerceAtLeast(0f) // ~1 s from full to empty
        }
        lastFrame = now

        val cx = width / 2f; val cy = height / 2f
        val r = minOf(cx, cy)
        val lit = if (shown <= 0f) 0 else maxOf(1, Math.round(SEGMENTS * shown))
        paint.strokeWidth = 4.5f * density
        for (i in 0 until SEGMENTS) {
            val a = START + SWEEP * i / (SEGMENTS - 1)
            val rad = Math.toRadians(a.toDouble())
            val co = cos(rad).toFloat(); val si = sin(rad).toFloat()
            val on = i < lit
            val r1 = if (on) r * 0.80f else r * 0.82f
            val r2 = if (on) r * 0.98f else r * 0.95f
            paint.color = if (on) Brass.brassAt(a) else Brass.RING_OFF
            c.drawLine(cx + r1 * co, cy + r1 * si, cx + r2 * co, cy + r2 * si, paint)
        }
        if (shown > 0f) postInvalidateOnAnimation()
    }
}
