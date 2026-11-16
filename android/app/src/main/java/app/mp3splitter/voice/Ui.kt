package app.mp3splitter.voice

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.mp3splitter.Brass
import app.mp3splitter.R

/** Base for the voice screens: navy background, edge-to-edge, small UI builders. */
open class BrassActivity : AppCompatActivity() {
    val d by lazy { resources.displayMetrics.density }
    fun dp(v: Number) = (v.toFloat() * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(Brass.Background())
    }

    /** Sets a vertical page; scrollable unless [scroll] is false. Returns the column to fill. */
    fun page(scroll: Boolean = true): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(24))
        }
        val root: View = if (scroll) ScrollView(this).apply { isFillViewport = true; addView(col) } else col
        val l = col.paddingLeft; val t = col.paddingTop; val r = col.paddingRight; val b = col.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val bars = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            col.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
            ins
        }
        setContentView(root)
        return col
    }

    fun text(s: CharSequence, sp: Float = 15f, color: Int = Brass.TEXT, style: String = "regular"): TextView =
        TextView(this).apply {
            text = s; textSize = sp; setTextColor(color)
            typeface = when (style) {
                "medium" -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
                "light" -> Typeface.create("sans-serif-light", Typeface.NORMAL)
                "mono" -> Typeface.MONOSPACE
                else -> Typeface.DEFAULT
            }
        }

    fun caps(s: String, color: Int = Brass.BRASS_TEXT) = text(s.uppercase(), 11f, color, "medium").apply { letterSpacing = 0.14f }

    fun row(gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; this.gravity = gravity }
    fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    fun panel(v: LinearLayout, padH: Int = 16, padV: Int = 12): LinearLayout = v.apply {
        background = Brass.panel(16 * d); setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    }

    fun brassButton(label: String, heightDp: Int = 52, onClick: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 16f; setTextColor(Brass.ON_BRASS)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = Brass.metal(16 * d); minHeight = dp(heightDp); setPadding(dp(18), 0, dp(18), 0)
        isClickable = true; isFocusable = true; setOnClickListener { onClick() }
    }

    fun ghostButton(label: String, heightDp: Int = 46, onClick: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 15f; setTextColor(Brass.BRASS_TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = Brass.panel(heightDp / 2f * d); minHeight = dp(heightDp); setPadding(dp(16), 0, dp(16), 0)
        isClickable = true; isFocusable = true; setOnClickListener { onClick() }
    }

    fun chip(label: String, on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 13f
        setTextColor(if (on) Brass.ON_BRASS else 0xFFC9D2DB.toInt())
        typeface = Typeface.create(if (on) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        background = if (on) Brass.metal(16 * d) else Brass.panel(16 * d)
        minHeight = dp(34); setPadding(dp(14), 0, dp(14), 0)
        isClickable = true; setOnClickListener { onClick() }
    }

    /** Header with a round back button, title and optional right-hand text. */
    fun header(title: String, right: String? = null): LinearLayout = row().apply {
        val back = ImageButton(this@BrassActivity).apply {
            setImageResource(R.drawable.ic_back); setColorFilter(Brass.BRASS_TEXT)
            background = Brass.panel(0f, oval = true); contentDescription = "Back"
            setOnClickListener { finish() }
        }
        addView(back, LinearLayout.LayoutParams(dp(42), dp(42)))
        addView(text(title, 21f, Brass.TEXT, "medium").apply { setPadding(dp(12), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        right?.let { addView(text(it, 13f, Brass.BRASS_TEXT)) }
    }

    fun gap(parent: ViewGroup, dpH: Int) = parent.addView(View(this), LinearLayout.LayoutParams(1, dp(dpH)))

    fun LinearLayout.add(v: View, topDp: Int = 0, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, weight: Float = 0f): View {
        val lp = if (orientation == LinearLayout.VERTICAL) LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        else LinearLayout.LayoutParams(if (weight > 0) 0 else w, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        if (orientation == LinearLayout.VERTICAL) lp.topMargin = dp(topDp) else lp.leftMargin = dp(topDp)
        addView(v, lp)
        return v
    }

    /** Round brass record/stop button. */
    fun recordButton(sizeDp: Int, onClick: () -> Unit): Pair<FrameLayout, View> {
        val f = FrameLayout(this).apply {
            background = Brass.metal(0f, oval = true); elevation = 6 * d
            isClickable = true; isFocusable = true; contentDescription = "Start"; setOnClickListener { onClick() }
        }
        val glyph = View(this)
        f.addView(glyph, FrameLayout.LayoutParams(dp(sizeDp * 0.32f), dp(sizeDp * 0.32f), Gravity.CENTER))
        return f to glyph
    }

    fun setGlyph(glyph: View, recording: Boolean) {
        glyph.background = GradientDrawable().apply {
            if (recording) { cornerRadius = 5 * d; setColor(Brass.ON_BRASS) } else { shape = GradientDrawable.OVAL; setColor(Brass.REC_RED) }
        }
        (glyph.parent as? View)?.contentDescription = if (recording) "Stop" else "Start"
    }

    fun fmtTime(sec: Float): String { val s = sec.toInt(); return "%d:%02d".format(s / 60, s % 60) }
}

fun Context.hasMic() = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
    android.content.pm.PackageManager.PERMISSION_GRANTED
