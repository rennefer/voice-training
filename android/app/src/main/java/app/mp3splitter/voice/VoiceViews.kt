package app.mp3splitter.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import app.mp3splitter.Brass
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val FEMALE_FILL = 0x21C8A96C
private const val MALE_FILL = 0x2178A0C8
const val MALE_TEXT = 0xFF9DB7D0.toInt()
private const val BRASS_LIGHT = 0xFFEFE1BB.toInt()
private const val BRASS_MID = 0xFFC8A96C.toInt()
private const val BRASS_DARK = 0xFF9C7B43.toInt()

private fun View.dp(v: Float) = v * resources.displayMetrics.density

/**
 * Pitch over time on a linear Hz scale with a line every 10 Hz, male/female bands,
 * the personal target zone, and optionally a guide line to follow.
 * scroll mode: the last [window] seconds slide past. Round mode: x = 0..round seconds.
 */
class PitchChartView(ctx: Context) : View(ctx) {
    var lo = 120f; var hi = 280f
    var target = 200f; var tolerance = 10f
    var window = 6f
    var roundSeconds = 0f                      // > 0: fixed round mode
    var guide: ((Float) -> Float?)? = null     // t within round -> Hz
    var idleText: String? = null

    // Whole history in live mode (drag to scroll back, tap to read a point, "Live" to follow again)
    private val ts = ArrayList<Float>(); private val fs = ArrayList<Float>()
    private var now = 0f
    private var follow = true
    private var viewEnd = 0f
    private var cursor = -1f
    private val windows = floatArrayOf(6f, 20f, 60f)

    private val fill = Paint()
    private val grid = Paint().apply { strokeWidth = 1f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = dp(9.5f) }
    private val bandLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(11f); textAlign = Paint.Align.RIGHT }
    private val trace = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.5f); color = BRASS_LIGHT; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(9f); color = 0xBFD6BB85.toInt(); strokeCap = Paint.Cap.ROUND; pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(9f)), 0f) }
    private val targetLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); color = BRASS_LIGHT; pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val idle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Brass.TEXT_SOFT; textSize = dp(15f); textAlign = Paint.Align.CENTER }
    private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(12.5f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gutter get() = dp(36f)
    private val liveRect = android.graphics.RectF()
    private val zoomRect = android.graphics.RectF()

    private val scrollable get() = roundSeconds <= 0f && ts.isNotEmpty()

    fun clear() { ts.clear(); fs.clear(); now = 0f; follow = true; cursor = -1f; invalidate() }

    /** Add a frame at time t (s since start, or since round start in round mode). */
    fun add(t: Float, f0: Float) {
        ts += t; fs += f0; now = t
        if (roundSeconds <= 0f && ts.size > 200_000) { ts.subList(0, 20_000).clear(); fs.subList(0, 20_000).clear() }   // ~2.5 h cap
        if (follow || ts.size == 1) invalidate()
    }

    fun centerOn(t: Float, span: Float = 160f) {
        lo = (t - span / 2).coerceAtLeast(60f); hi = lo + span; invalidate()
    }

    fun goLive() { follow = true; cursor = -1f; invalidate() }

    private fun end() = if (follow) max(now, window) else viewEnd
    private fun y(hz: Float) = height - (hz - lo) / (hi - lo) * height
    private fun x(t: Float): Float {
        val w = width - gutter
        return if (roundSeconds > 0f) gutter + t / roundSeconds * w
        else gutter + (t - (end() - window)) / window * w
    }
    private fun firstIndexAtOrAfter(t: Float): Int {
        var a = 0; var b = ts.size
        while (a < b) { val m = (a + b) ushr 1; if (ts[m] < t) a = m + 1 else b = m }
        return a
    }
    private fun clock(t: Float): String { val s = max(0f, t).toInt(); return "%d:%02d".format(s / 60, s % 60) }

    // ---- touch: drag to scroll, tap to read, pills for Live and zoom
    private var downX = 0f; private var downEnd = 0f; private var moved = false
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (!scrollable) return false
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = e.x; downEnd = end(); moved = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                if (abs(dx) > dp(6f)) moved = true
                if (moved) {
                    viewEnd = (downEnd - dx / (width - gutter) * window).coerceIn(window, max(window, now))
                    follow = dx < 0 && viewEnd >= now - 0.05f
                    cursor = -1f
                    invalidate()
                }
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!moved) {
                    when {
                        !follow && liveRect.contains(e.x, e.y) -> goLive()
                        zoomRect.contains(e.x, e.y) -> {
                            val keep = end()
                            val i = (windows.indexOfFirst { it == window } + 1) % windows.size
                            window = windows[i]
                            if (!follow) viewEnd = keep.coerceIn(window, max(window, now))
                            invalidate()
                        }
                        e.x > gutter -> {
                            if (follow) { viewEnd = end(); follow = false }
                            cursor = end() - window + (e.x - gutter) / (width - gutter) * window
                            invalidate()
                        }
                    }
                    performClick()
                }
                return true
            }
            android.view.MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false); return true }
        }
        return super.onTouchEvent(e)
    }
    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val t1 = end(); val t0 = t1 - window
        // bands
        fill.color = FEMALE_FILL
        c.drawRect(0f, y(Ranges.FEMALE_HI).coerceIn(0f, h), w, y(Ranges.FEMALE_LO).coerceIn(0f, h), fill)
        fill.color = MALE_FILL
        c.drawRect(0f, y(Ranges.MALE_HI).coerceIn(0f, h), w, y(Ranges.MALE_LO).coerceIn(0f, h), fill)
        bandLabel.color = BRASS_MID
        if (y(Ranges.FEMALE_HI) > -dp(4f)) c.drawText("female", w - dp(6f), y(Ranges.FEMALE_HI) + dp(13f), bandLabel)
        bandLabel.color = MALE_TEXT
        if (y(Ranges.MALE_HI) < h - dp(14f)) c.drawText("male", w - dp(6f), y(Ranges.MALE_HI) + dp(13f), bandLabel)
        // target zone
        fill.color = 0x29EFE1BB
        c.drawRect(gutter, y(target + tolerance), w, y(target - tolerance), fill)
        c.drawLine(gutter, y(target), w, y(target), targetLine)
        // grid every 10 Hz, labels every 20
        var v = (lo / 10f).toInt() * 10f + 10f
        while (v < hi) {
            val major = v.toInt() % 20 == 0
            val hundred = v.toInt() % 100 == 0
            grid.color = when { hundred -> 0x29FFFFFF; major -> 0x17FFFFFF; else -> 0x0AFFFFFF }
            c.drawLine(if (major) gutter - dp(4f) else gutter, y(v), w, y(v), grid)
            if (major) {
                label.color = if (hundred) 0xFFC9D2DB.toInt() else Brass.TEXT_SOFT
                label.textAlign = Paint.Align.LEFT
                c.drawText(v.toInt().toString(), dp(4f), y(v) + dp(3.5f), label)
            }
            v += 10f
        }
        // time ticks when looking back or zoomed out
        if (scrollable && (!follow || window > 6f)) {
            val step = if (window <= 6f) 1 else if (window <= 20f) 5 else 10
            var t = (ceil(max(0f, t0) / step) * step)
            label.textAlign = Paint.Align.CENTER; label.color = Brass.TEXT_SOFT
            while (t <= t1) {
                val px = x(t)
                if (px > gutter + dp(12f) && px < w - dp(12f)) {
                    grid.color = 0x0FFFFFFF; c.drawLine(px, 0f, px, h - dp(14f), grid)
                    c.drawText(clock(t), px, h - dp(3f), label)
                }
                t += step
            }
            label.textAlign = Paint.Align.LEFT
        }
        // guide
        guide?.let { g ->
            if (roundSeconds > 0f) {
                val p = Path(); var started = false; var t = 0f
                while (t <= roundSeconds) {
                    g(t)?.let { hz -> if (!started) { p.moveTo(x(t), y(hz)); started = true } else p.lineTo(x(t), y(hz)) }
                    t += roundSeconds / 120f
                }
                c.drawPath(p, guidePaint)
            }
        }
        // trace (visible part only)
        trace.strokeWidth = dp(if (window > 20f) 1.6f else 2.5f)
        val p = Path(); var pen = false; var lastT = -1f
        val i0 = if (roundSeconds > 0f) 0 else firstIndexAtOrAfter(t0 - 0.5f)
        for (i in i0 until ts.size) {
            val f = fs[i]; val t = ts[i]
            if (roundSeconds <= 0f && t > t1 + 0.5f) break
            if (f <= 0f || (lastT >= 0 && t - lastT > 0.12f)) { pen = false }
            if (f > 0f) {
                val px = x(t); val py = y(f.coerceIn(lo - 20, hi + 20))
                if (!pen) { p.moveTo(px, py); pen = true } else p.lineTo(px, py)
            }
            lastT = t
        }
        c.save(); c.clipRect(gutter, 0f, w, h)
        c.drawPath(p, trace)
        if (follow && fs.isNotEmpty() && fs.last() > 0f) c.drawCircle(x(ts.last()), y(fs.last().coerceIn(lo, hi)), dp(6f), dot)
        // cursor read-out
        if (!follow && cursor >= 0f && ts.isNotEmpty()) {
            val px = x(cursor)
            fill.color = 0xCCFFFFFF.toInt(); c.drawRect(px - dp(0.75f), 0f, px + dp(0.75f), h, fill)
            val k = firstIndexAtOrAfter(cursor).coerceAtMost(ts.size - 1)
            val f = if (abs(ts[k] - cursor) < 0.2f) fs[k] else 0f
            if (f > 0f) c.drawCircle(px, y(f.coerceIn(lo, hi)), dp(5f), dot)
            val text = "${clock(cursor)} · " + if (f > 0f) "${f.toInt()} Hz" else "silence"
            val tw = pillText.measureText(text) + dp(16f)
            val bx = (px - tw / 2).coerceIn(gutter + dp(2f), w - tw - dp(2f))
            pillFill.color = 0xE60D141C.toInt(); c.drawRoundRect(bx, dp(6f), bx + tw, dp(28f), dp(11f), dp(11f), pillFill)
            pillText.color = 0xFFEFE1BB.toInt(); c.drawText(text, bx + dp(8f), dp(21.5f), pillText)
        }
        c.restore()
        // pills: zoom (bottom left) and Live (bottom right)
        if (scrollable) {
            val zt = "↔ ${window.toInt()} s"
            val zw = pillText.measureText(zt) + dp(20f)
            zoomRect.set(gutter + dp(6f), h - dp(38f), gutter + dp(6f) + zw, h - dp(8f))
            pillFill.color = 0xBF0D141C.toInt(); c.drawRoundRect(zoomRect, dp(15f), dp(15f), pillFill)
            pillText.color = 0xFFD6BB85.toInt(); c.drawText(zt, zoomRect.left + dp(10f), zoomRect.bottom - dp(10f), pillText)
            if (!follow) {
                val lt = "Live ▸"; val lw = pillText.measureText(lt) + dp(24f)
                liveRect.set(w - dp(8f) - lw, h - dp(40f), w - dp(8f), h - dp(8f))
                pillFill.shader = android.graphics.LinearGradient(liveRect.left, liveRect.top, liveRect.right, liveRect.bottom,
                    intArrayOf(0xFFEFE1BB.toInt(), 0xFFC8A96C.toInt(), 0xFF9C7B43.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
                c.drawRoundRect(liveRect, dp(16f), dp(16f), pillFill); pillFill.shader = null
                pillText.color = Brass.ON_BRASS; c.drawText(lt, liveRect.left + dp(12f), liveRect.bottom - dp(11f), pillText)
            } else liveRect.setEmpty()
        } else { zoomRect.setEmpty(); liveRect.setEmpty() }
        if (ts.isEmpty()) idleText?.let { c.drawText(it, (gutter + w) / 2, h / 2, idle) }
    }
}

/** A segmented meter: name and value on top, left/right words around 24 brass segments. */
class MeterView(ctx: Context, private val name: String, private val left: String, private val right: String) : View(ctx) {
    var value: Float? = null; set(v) { field = v; invalidate() }
    var valueText: String = "–"; set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seg = RectF()
    init { minimumHeight = dp(40f).toInt() }
    override fun onMeasure(wm: Int, hm: Int) = setMeasuredDimension(MeasureSpec.getSize(wm), dp(40f).toInt())
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        p.textSize = dp(13f); p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); p.color = Brass.TEXT; p.textAlign = Paint.Align.LEFT
        c.drawText(name, 0f, dp(14f), p)
        p.typeface = Typeface.DEFAULT; p.color = Brass.BRASS_TEXT; p.textAlign = Paint.Align.RIGHT
        c.drawText(valueText, w, dp(14f), p)
        p.textSize = dp(11f); p.color = Brass.TEXT_SOFT; p.textAlign = Paint.Align.LEFT
        val lw = dp(46f)
        c.drawText(left, 0f, dp(35f), p)
        p.textAlign = Paint.Align.RIGHT
        c.drawText(right, w, dp(35f), p)
        val n = 24; val gap = dp(2f); val x0 = lw; val x1 = w - lw
        val sw = (x1 - x0 - gap * (n - 1)) / n
        val lit = value?.let { Math.round(it.coerceIn(0f, 1f) * n) } ?: 0
        for (i in 0 until n) {
            p.color = when {
                value == null -> 0x14FFFFFF
                i < lit -> if (i < n / 2) BRASS_DARK else BRASS_MID
                else -> 0x21FFFFFF
            }
            seg.set(x0 + i * (sw + gap), dp(26f), x0 + i * (sw + gap) + sw, dp(36f))
            c.drawRoundRect(seg, dp(2f), dp(2f), p)
        }
    }
}

/** 70–300 Hz strip with the male and female ranges and a marker for the current pitch. */
class RangeBarView(ctx: Context) : View(ctx) {
    var hz: Float = 0f; set(v) { field = v; invalidate() }
    var target: Float = 0f; set(v) { field = v; invalidate() }
    private val lo = 70f; private val hi = 300f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onMeasure(wm: Int, hm: Int) = setMeasuredDimension(MeasureSpec.getSize(wm), dp(42f).toInt())
    private fun x(f: Float) = (f - lo) / (hi - lo) * width
    override fun onDraw(c: Canvas) {
        val h = dp(22f); val rad = h / 2
        r.set(0f, 0f, width.toFloat(), h)
        p.shader = null; p.color = 0x0FFFFFFF
        c.drawRoundRect(r, rad, rad, p)
        c.save(); c.clipRect(0f, 0f, width.toFloat(), h)
        p.color = 0x6178A0C8
        c.drawRect(x(Ranges.MALE_LO), 0f, x(Ranges.MALE_HI), h, p)
        p.shader = LinearGradient(x(Ranges.FEMALE_LO), 0f, x(Ranges.FEMALE_HI), 0f, 0xB39C7B43.toInt(), 0xB3EFE1BB.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(x(Ranges.FEMALE_LO), 0f, x(Ranges.FEMALE_HI), h, p)
        p.shader = null
        if (target > 0f) { p.color = 0xFF1A140A.toInt(); c.drawRect(x(target) - dp(1f), 0f, x(target) + dp(1f), h, p) }
        if (hz > 0f) { p.color = 0xFFFFFFFF.toInt(); val xx = x(hz.coerceIn(lo, hi)); c.drawRect(xx - dp(1.5f), 0f, xx + dp(1.5f), h, p) }
        c.restore()
        p.textSize = dp(11f); p.textAlign = Paint.Align.LEFT; p.color = MALE_TEXT
        c.drawText("Male 85–155", x(Ranges.MALE_LO), dp(38f), p)
        p.textAlign = Paint.Align.RIGHT; p.color = Brass.BRASS_TEXT
        c.drawText("Female 165–255", x(Ranges.FEMALE_HI), dp(38f), p)
    }
}

/** Share of voiced time per 10 Hz bin, rows labelled "200 G3", tinted by range. */
class HistogramView(ctx: Context, private val hist: IntArray) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val rows: List<Int>   // bin indices, high to low
    init {
        val used = hist.indices.filter { hist[it] > 0 }
        rows = if (used.isEmpty()) emptyList() else {
            val a = (used.first() - 1).coerceAtLeast(0); val b = (used.last() + 1).coerceAtMost(hist.size - 1)
            (b downTo a).toList()
        }
    }
    override fun onMeasure(wm: Int, hm: Int) = setMeasuredDimension(MeasureSpec.getSize(wm), (rows.size * dp(15f)).toInt().coerceAtLeast(dp(20f).toInt()))
    override fun onDraw(c: Canvas) {
        val max = (hist.maxOrNull() ?: 1).coerceAtLeast(1)
        val lw = dp(64f); val rowH = dp(15f)
        rows.forEachIndexed { i, b ->
            val hz = SessionStats.HIST_LO + b * 10 + 5
            val top = i * rowH
            val fem = hz >= Ranges.FEMALE_LO && hz <= Ranges.FEMALE_HI
            val male = hz <= Ranges.MALE_HI
            p.shader = null
            p.color = when { fem -> FEMALE_FILL; male -> MALE_FILL; else -> 0 }
            if (p.color != 0) { r.set(lw, top + dp(1f), width.toFloat(), top + rowH - dp(1f)); c.drawRoundRect(r, dp(3f), dp(3f), p) }
            p.textSize = dp(10f); p.typeface = Typeface.MONOSPACE; p.color = Brass.TEXT_SOFT; p.textAlign = Paint.Align.LEFT
            c.drawText("${hz - 5} ${VoiceScores.noteName(hz.toFloat())}", 0f, top + rowH - dp(4f), p)
            val bw = (width - lw) * hist[b] / max
            if (bw > 0) {
                r.set(lw, top + dp(3f), lw + bw, top + rowH - dp(3f))
                if (fem) p.shader = LinearGradient(lw, 0f, lw + bw, 0f, BRASS_DARK, BRASS_LIGHT, Shader.TileMode.CLAMP)
                else p.color = if (male) 0xFF7FA2C4.toInt() else 0x66EEF2F5
                c.drawRoundRect(r, dp(3f), dp(3f), p)
                p.shader = null
            }
        }
    }
}

/** Line of values (e.g. median Hz per session) over the male/female bands. */
class TrendChartView(ctx: Context) : View(ctx) {
    var values: List<Float> = emptyList(); set(v) { field = v; invalidate() }
    var target = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onMeasure(wm: Int, hm: Int) = setMeasuredDimension(MeasureSpec.getSize(wm), dp(160f).toInt())
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val g = dp(32f)
        val vs = values.filter { it > 0 }
        var lo = min(vs.minOrNull() ?: 150f, if (target > 0) target else 200f) - 15f
        var hi = max(vs.maxOrNull() ?: 220f, if (target > 0) target else 200f) + 15f
        lo = (lo / 10).toInt() * 10f; hi = ((hi / 10).toInt() + 1) * 10f
        fun y(v: Float) = dp(6f) + (1 - (v - lo) / (hi - lo)) * (h - dp(12f))
        p.style = Paint.Style.FILL; p.color = FEMALE_FILL
        c.drawRect(g, y(Ranges.FEMALE_HI).coerceIn(0f, h), w, y(Ranges.FEMALE_LO).coerceIn(0f, h), p)
        p.color = MALE_FILL
        c.drawRect(g, y(Ranges.MALE_HI).coerceIn(0f, h), w, y(Ranges.MALE_LO).coerceIn(0f, h), p)
        val step = if (hi - lo > 120) 40 else 20
        var v = (lo / step).toInt() * step.toFloat()
        p.textSize = dp(10f); p.typeface = Typeface.MONOSPACE
        while (v <= hi) {
            if (v >= lo) {
                p.color = 0x14FFFFFF; c.drawRect(g, y(v), w, y(v) + 1, p)
                p.color = Brass.TEXT_SOFT; c.drawText(v.toInt().toString(), 0f, y(v) + dp(3.5f), p)
            }
            v += step
        }
        if (target > 0) {
            p.color = 0x80EFE1BB.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.5f)
            p.pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
            c.drawLine(g, y(target), w, y(target), p); p.pathEffect = null
        }
        if (values.isEmpty()) return
        val n = values.size
        fun x(i: Int) = if (n == 1) (g + w) / 2 else g + dp(6f) + i * (w - g - dp(12f)) / (n - 1)
        val path = Path(); var started = false
        values.forEachIndexed { i, f -> if (f > 0) { if (!started) { path.moveTo(x(i), y(f)); started = true } else path.lineTo(x(i), y(f)) } }
        p.style = Paint.Style.STROKE; p.color = BRASS_MID; p.strokeWidth = dp(2f)
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        values.forEachIndexed { i, f -> if (f > 0) { p.color = if (i == n - 1) 0xFFFFFFFF.toInt() else BRASS_LIGHT; c.drawCircle(x(i), y(f), dp(3.5f), p) } }
    }
}

/** Vertical bars 0..100, last one highlighted; optional labels under and values over. */
class BarsView(ctx: Context, private val heightDp: Float, private val labels: List<String>? = null, private val highlight: Int = -1) : View(ctx) {
    var values: List<Float?> = emptyList(); set(v) { field = v; invalidate() }
    var showValues = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onMeasure(wm: Int, hm: Int) = setMeasuredDimension(MeasureSpec.getSize(wm), dp(heightDp).toInt())
    override fun onDraw(c: Canvas) {
        val n = values.size; if (n == 0) return
        val gap = dp(if (n > 10) 3f else 6f)
        val bw = (width - gap * (n - 1)) / n
        val top = if (showValues) dp(14f) else 0f
        val bottom = height - if (labels != null) dp(16f) else 0f
        val hl = if (highlight >= 0) highlight else n - 1
        values.forEachIndexed { i, v ->
            val x = i * (bw + gap)
            val hgt = if (v == null || v <= 0f) dp(3f) else max(dp(3f), (bottom - top) * (v / 100f).coerceIn(0f, 1f))
            r.set(x, bottom - hgt, x + bw, bottom)
            p.shader = null
            if (v == null || v <= 0f) p.color = 0x1FFFFFFF
            else if (i == hl) p.shader = LinearGradient(0f, bottom - hgt, 0f, bottom, BRASS_LIGHT, BRASS_MID, Shader.TileMode.CLAMP)
            else p.color = BRASS_DARK
            c.drawRoundRect(r, dp(2.5f), dp(2.5f), p)
            p.shader = null
            p.textAlign = Paint.Align.CENTER
            if (showValues && v != null && v > 0f) { p.textSize = dp(10f); p.color = 0xFFC9D2DB.toInt(); c.drawText(v.toInt().toString(), x + bw / 2, bottom - hgt - dp(3f), p) }
            labels?.getOrNull(i)?.let { p.textSize = dp(11f); p.color = if (i == hl) BRASS_LIGHT else Brass.TEXT_SOFT; c.drawText(it, x + bw / 2, height - dp(3f), p) }
        }
    }
}

/** FineVolume-style 40-segment brass dial showing a fraction 0..1. */
class ScoreDialView(ctx: Context) : View(ctx) {
    var fraction = 0f; set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    override fun onMeasure(wm: Int, hm: Int) { val s = min(MeasureSpec.getSize(wm), dp(240f).toInt()); setMeasuredDimension(s, s) }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = min(cx, cy)
        val n = 40; val lit = Math.round(n * fraction)
        p.strokeWidth = dp(4.5f)
        for (i in 0 until n) {
            val a = 135f + 270f * i / (n - 1)
            val rad = Math.toRadians(a.toDouble()); val co = cos(rad).toFloat(); val si = sin(rad).toFloat()
            val on = i < lit
            val r1 = if (on) r * 0.80f else r * 0.82f; val r2 = if (on) r * 0.98f else r * 0.95f
            p.color = if (on) Brass.brassAt(a) else Brass.RING_OFF
            c.drawLine(cx + r1 * co, cy + r1 * si, cx + r2 * co, cy + r2 * si, p)
        }
    }
}

/** Small pitch graph for one exercise: bands, target zone, the exercise's guide line (if any) and your pitch. */
class ExerciseChartView(ctx: Context, private val trace: FloatArray, private val traceSec: Float,
                        private val target: Float, private val tolerance: Float, private val guide: ((Float) -> Float?)?) : View(ctx) {
    private val total = maxOf(traceSec, trace.size * traceSec)
    private var lo: Float; private var hi: Float
    init {
        val v = trace.filter { it > 0f }
        var l = minOf(v.minOrNull() ?: target, target) - 20f; var h = maxOf(v.maxOrNull() ?: target, target) + 20f
        if (guide != null) { var t = 0f; while (t <= total) { guide.invoke(t)?.let { l = minOf(l, it - 15f); h = maxOf(h, it + 15f) }; t += total / 60f } }
        lo = maxOf(60f, (l / 10f).toInt() * 10f); hi = kotlin.math.ceil(h / 10f) * 10f
        if (hi - lo < 60f) hi = lo + 60f
    }
    private val fill = Paint()
    private val grid = Paint().apply { color = 0x12FFFFFF }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = dp(9.5f); color = Brass.TEXT_SOFT }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); color = BRASS_LIGHT; strokeJoin = Paint.Join.ROUND }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(5f); color = 0xB3D6BB85.toInt(); strokeCap = Paint.Cap.ROUND; pathEffect = DashPathEffect(floatArrayOf(dp(1.5f), dp(6f)), 0f) }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val gx = dp(30f); val bottom = h - dp(14f)
        fun y(f: Float) = dp(4f) + (1 - (f - lo) / (hi - lo)) * (bottom - dp(4f))
        fun x(t: Float) = gx + t / total * (w - gx - dp(4f))
        fill.color = FEMALE_FILL; c.drawRect(gx, y(Ranges.FEMALE_HI).coerceIn(0f, bottom), w, y(Ranges.FEMALE_LO).coerceIn(0f, bottom), fill)
        fill.color = MALE_FILL; c.drawRect(gx, y(Ranges.MALE_HI).coerceIn(0f, bottom), w, y(Ranges.MALE_LO).coerceIn(0f, bottom), fill)
        fill.color = 0x24EFE1BB; c.drawRect(gx, y(target + tolerance), w, y(target - tolerance), fill)
        val step = if (hi - lo > 120f) 40 else 20
        var f = kotlin.math.ceil(lo / step) * step
        label.textAlign = Paint.Align.LEFT
        while (f <= hi) { c.drawRect(gx, y(f), w, y(f) + 1f, grid); c.drawText(f.toInt().toString(), 0f, y(f) + dp(3.5f), label); f += step }
        c.drawText("0:00", gx + dp(4f), h - dp(3f), label)
        val te = "%d:%02d".format(total.toInt() / 60, total.toInt() % 60)
        label.textAlign = Paint.Align.RIGHT; c.drawText(te, w - dp(12f), h - dp(3f), label)
        guide?.let { g ->
            val p = Path(); var started = false; var t = 0f
            while (t <= total) { val gz = g(t); if (gz == null) started = false else if (!started) { p.moveTo(x(t), y(gz)); started = true } else p.lineTo(x(t), y(gz)); t += minOf(0.1f, total / 200f) }
            c.drawPath(p, guidePaint)
        }
        val p = Path(); var pen = false
        trace.forEachIndexed { i, v -> if (v <= 0f) pen = false else { val px = x((i + 0.5f) * traceSec); val py = y(v); if (!pen) { p.moveTo(px, py); pen = true } else p.lineTo(px, py) } }
        c.drawPath(p, line)
    }
}


/** Whole-session pitch line with a playhead; tap or drag to jump. */
class TraceView(ctx: Context, private val trace: FloatArray, private val traceSec: Float, private val target: Float) : View(ctx) {
    var position = 0f; set(v) { field = v; invalidate() }   // seconds
    var onSeek: ((Float) -> Unit)? = null
    private val total = trace.size * traceSec
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lo: Float; private val hi: Float
    init {
        val v = trace.filter { it > 0 }
        lo = ((v.minOrNull() ?: 120f).coerceAtMost(target) - 20f).coerceAtLeast(60f)
        hi = (v.maxOrNull() ?: 260f).coerceAtLeast(target) + 20f
    }
    override fun onMeasure(wm: Int, hm: Int) = setMeasuredDimension(MeasureSpec.getSize(wm), dp(120f).toInt())
    private fun y(f: Float) = height - (f - lo) / (hi - lo) * height
    private fun x(t: Float) = if (total <= 0f) 0f else t / total * width
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.color = FEMALE_FILL; c.drawRect(0f, y(Ranges.FEMALE_HI).coerceIn(0f, h), w, y(Ranges.FEMALE_LO).coerceIn(0f, h), p)
        p.color = MALE_FILL; c.drawRect(0f, y(Ranges.MALE_HI).coerceIn(0f, h), w, y(Ranges.MALE_LO).coerceIn(0f, h), p)
        p.color = 0x55EFE1BB; c.drawRect(0f, y(target) - 1f, w, y(target) + 1f, p)
        val path = Path(); var pen = false
        trace.forEachIndexed { i, f ->
            if (f <= 0f) { pen = false; return@forEachIndexed }
            val px = x(i * traceSec); val py = y(f)
            if (!pen) { path.moveTo(px, py); pen = true } else path.lineTo(px, py)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.8f); p.color = BRASS_MID
        c.drawPath(path, p)
        // played part brighter
        c.save(); c.clipRect(0f, 0f, x(position), h)
        p.color = BRASS_LIGHT; p.strokeWidth = dp(2.2f); c.drawPath(path, p)
        c.restore()
        p.style = Paint.Style.FILL; p.color = 0xFFFFFFFF.toInt()
        c.drawRect(x(position) - dp(1f), 0f, x(position) + dp(1f), h, p)
    }
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (total <= 0f) return false
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                position = (e.x / width).coerceIn(0f, 1f) * total
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) onSeek?.invoke(position)
            }
            android.view.MotionEvent.ACTION_UP -> { onSeek?.invoke(position); parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }
}
