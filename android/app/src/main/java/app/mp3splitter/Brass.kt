package app.mp3splitter

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/** The look shared with FineVolume: Nile navy, frosted panels, polished brass. */
object Brass {
    const val BG = 0xFF121C26.toInt()
    const val BG_BOTTOM = 0xFF0D141C.toInt()
    const val BG_GLOW = 0xFF1C2B3A.toInt()
    const val TEXT = 0xFFEEF2F5.toInt()
    const val TEXT_SOFT = 0xFF8FA1B0.toInt()
    const val BRASS_TEXT = 0xFFD6BB85.toInt()
    const val ON_BRASS = 0xFF1A140A.toInt()
    const val PANEL_FILL = 0x0FFFFFFF
    const val PANEL_STROKE = 0x12FFFFFF
    const val RING_OFF = 0x21FFFFFF
    const val REC_RED = 0xFFA3241E.toInt()

    val METAL = intArrayOf(0xFFEFE1BB.toInt(), 0xFFC8A96C.toInt(), 0xFF9C7B43.toInt(), 0xFFC9AB70.toInt(), 0xFF7A5F30.toInt())

    /** Polished brass: light – dark – light – dark, like metal catching the light. */
    fun metal(radiusPx: Float, oval: Boolean = false): Drawable {
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, METAL)
        if (oval) g.shape = GradientDrawable.OVAL else g.cornerRadius = radiusPx
        return RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), g, null)
    }

    /** Frosted panel used for rows, tab bars and round buttons. */
    fun panel(radiusPx: Float, oval: Boolean = false): Drawable {
        val g = GradientDrawable()
        if (oval) g.shape = GradientDrawable.OVAL else g.cornerRadius = radiusPx
        g.setColor(PANEL_FILL)
        g.setStroke(1, PANEL_STROKE)
        return RippleDrawable(ColorStateList.valueOf(0x33C8A96C), g, null)
    }

    /** Brass colour at an angle (degrees, 0 = right, clockwise), lit as if light falls from the top left. */
    fun brassAt(angleDeg: Float): Int {
        val d = cos(Math.toRadians((angleDeg - 235).toDouble()))
        val t = (d + 1) / 2
        val dark = intArrayOf(92, 68, 32); val mid = intArrayOf(170, 136, 80)
        val light = intArrayOf(232, 212, 160); val spec = intArrayOf(250, 240, 214)
        var c = if (t < 0.7) lerp(dark, mid, minOf(1.0, t * 1.4)) else lerp(mid, light, (t - 0.7) / 0.3)
        val n = ((angleDeg % 360) + 360) % 360
        val sp = exp(-((n - 250) / 9.0).pow(2))
        c = lerp(c, spec, sp * 0.85)
        return (0xFF shl 24) or (c[0] shl 16) or (c[1] shl 8) or c[2]
    }

    private fun lerp(a: IntArray, b: IntArray, t: Double) = IntArray(3) { (a[it] + (b[it] - a[it]) * t).roundToInt() }

    /** Page background: navy gradient with a soft glow near the top. */
    class Background : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(c: Canvas) {
            val b = bounds
            val w = b.width().toFloat(); val h = b.height().toFloat()
            paint.shader = LinearGradient(0f, 0f, 0f, h, BG, BG_BOTTOM, Shader.TileMode.CLAMP)
            c.drawRect(b, paint)
            paint.shader = RadialGradient(w * 0.5f, h * 0.30f, maxOf(w, h) * 0.45f,
                BG_GLOW, BG_GLOW and 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawRect(b, paint)
            paint.shader = null
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }
}
