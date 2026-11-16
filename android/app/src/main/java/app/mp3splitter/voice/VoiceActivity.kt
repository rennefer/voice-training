package app.mp3splitter.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import app.mp3splitter.Brass
import app.mp3splitter.TabBar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlin.math.abs

/** Collects frames for one session and turns them into a [Session]. */
class SessionBuilder(val target: Float, val tolerance: Float, val pcm: java.io.File? = null, val goal: Goal = Goal.MTF) {
    val start = System.currentTimeMillis()
    val frames = ArrayList<VoiceFrame>()
    val results = mutableListOf<ExerciseResult>()
    var activeSeconds = 0f

    fun add(f: VoiceFrame) { frames += f }

    fun build(routineSize: Int = 0): Session {
        val st = SessionStats.compute(frames, LiveAnalyzer.FRAME_SECONDS, target, tolerance)
        // trace: one value per 0.25 s (median of voiced frames, 0 = silence), capped to ~1200 points
        val per = (0.25f / LiveAnalyzer.FRAME_SECONDS).toInt().coerceAtLeast(1)
        val step = maxOf(per, frames.size / 1200 + 1)
        val trace = frames.chunked(step).map { ch ->
            val v = ch.filter { it.voiced }.map { it.f0 }.sorted()
            if (v.size * 2 < ch.size) 0f else v[v.size / 2]
        }.toFloatArray()
        val secs = if (activeSeconds > 0f) activeSeconds else frames.size * LiveAnalyzer.FRAME_SECONDS
        return Session(start, secs, st, results.toMutableList(), trace = trace, target = target, tolerance = tolerance,
            routineSize = routineSize, traceSec = step * LiveAnalyzer.FRAME_SECONDS, dir = if (goal == Goal.FTM) "ftm" else "mtf").also { s ->
            s.pcmPath = pcm?.takeIf { it.exists() && it.length() > 0 }?.absolutePath
        }
    }
}

/** Voice tab: live pitch on a detailed Hz scale, range indicator and meters. */
class VoiceActivity : BrassActivity() {
    private lateinit var settings: VoiceSettings
    private lateinit var hzText: TextView
    private lateinit var targetTitle: TextView
    private lateinit var offText: TextView
    private lateinit var statusText: TextView
    private lateinit var rangeBar: RangeBarView
    private lateinit var chart: PitchChartView
    private lateinit var mRes: MeterView
    private lateinit var mWeight: MeterView
    private lateinit var mVol: MeterView
    private lateinit var mBreath: MeterView
    private lateinit var recGlyph: View
    private lateinit var goalText: TextView

    private var analyzer: LiveAnalyzer? = null
    private var builder: SessionBuilder? = null
    private val smooth = LiveSmoother()
    private var t = 0f
    private var onTargetFrames = 0; private var voicedFrames = 0
    private var shownHz = 0f

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startLive() else statusText.text = "Microphone permission is needed."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = VoiceSettings(this)
        val c = page(scroll = true)

        // top row: title + Record | Voice
        val top = row()
        top.add(text("Recorder", 21f, Brass.BRASS_TEXT, "medium"), weight = 1f)
        val tabs = TabBar(this).apply { setOptions(listOf("Record", "Voice")); select(1) }
        tabs.onSelect = { if (it == 0) { finish(); overridePendingTransition(0, 0) } }
        top.addView(tabs, LinearLayout.LayoutParams(dp(180), LinearLayout.LayoutParams.WRAP_CONTENT))
        c.add(top)

        // big Hz + target block
        val big = row(Gravity.BOTTOM)
        val hzRow = row(Gravity.BOTTOM)
        hzText = text("–", 64f, Brass.TEXT, "light").apply { includeFontPadding = false; letterSpacing = -0.03f }
        hzRow.add(hzText)
        hzRow.add(text("Hz", 18f, Brass.TEXT_SOFT).apply { setPadding(dp(8), 0, 0, dp(8)) })
        big.add(hzRow, weight = 1f)
        val tb = col().apply { gravity = Gravity.END; isClickable = true; setOnClickListener { showSettings() } }
        targetTitle = caps("Target"); offText = text("", 15f, 0xFFEFE1BB.toInt(), "medium"); statusText = text("Tap start", 12f, Brass.TEXT_SOFT, "mono")
        tb.add(targetTitle, w = LinearLayout.LayoutParams.WRAP_CONTENT); tb.add(offText, 2, LinearLayout.LayoutParams.WRAP_CONTENT); tb.add(statusText, 2, LinearLayout.LayoutParams.WRAP_CONTENT)
        big.add(tb, w = LinearLayout.LayoutParams.WRAP_CONTENT)
        c.add(big, 14)

        rangeBar = RangeBarView(this)
        c.add(rangeBar, 12)

        chart = PitchChartView(this).apply { background = Brass.panel(16 * d); idleText = "Tap the brass button and speak" }
        c.addView(chart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(250)).apply { topMargin = dp(8) })

        val meters = panel(col(), 16, 10)
        mRes = MeterView(this, "Resonance", "Dark", "Bright")
        mWeight = MeterView(this, "Vocal weight", "Heavy", "Light")
        mVol = MeterView(this, "Volume", "Soft", "Loud")
        mBreath = MeterView(this, "Breathiness", "Clear", "Breathy")
        meters.add(mRes); meters.add(mWeight, 6); meters.add(mVol, 6); meters.add(mBreath, 6)
        goalText = text("", 11f, Brass.TEXT_SOFT).apply { gravity = Gravity.CENTER }
        meters.add(goalText, 6)
        c.add(meters, 12)

        val bottom = row()
        bottom.add(ghostButton("Exercises") { startActivity(Intent(this, ExercisesActivity::class.java)) }, weight = 1f)
        val (rb, glyph) = recordButton(68) { if (analyzer?.isRunning == true) stopLive() else requestStart() }
        recGlyph = glyph; setGlyph(glyph, false)
        bottom.addView(rb, LinearLayout.LayoutParams(dp(68), dp(68)).apply { leftMargin = dp(16); rightMargin = dp(16) })
        bottom.add(ghostButton("Today") { startActivity(Intent(this, ScoreActivity::class.java)) }, weight = 1f)
        c.add(View(this), 0).let { (it.layoutParams as LinearLayout.LayoutParams).apply { height = 0; weight = 1f } }
        c.add(bottom, 16)

        applyTarget()
    }

    private fun applyTarget() {
        val tg = settings.target
        val g = settings.goal
        targetTitle.text = "${g.short.uppercase()} · ${tg.toInt()} HZ ±${settings.tolerance.toInt()}"
        goalText.text = "${g.goalText} · ${g.goalName} range ${g.goalLo.toInt()}–${g.goalHi.toInt()} Hz"
        chart.target = tg; chart.tolerance = settings.tolerance
        chart.lo = (tg - 80f).coerceAtLeast(60f); chart.hi = chart.lo + 160f
        rangeBar.target = tg
        chart.invalidate()
    }

    private fun requestStart() {
        if (hasMic()) startLive() else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startLive() {
        val pcm = VoiceStore.pendingPcm(this).apply { delete() }
        builder = SessionBuilder(settings.target, settings.tolerance, pcm, settings.goal)
        smooth.clear(); chart.clear(); t = 0f; onTargetFrames = 0; voicedFrames = 0
        analyzer = LiveAnalyzer({ onFrame(it) }, { msg -> statusText.text = msg; stopLive(save = false) }, pcm)
        analyzer!!.start()
        setGlyph(recGlyph, true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun onFrame(f: VoiceFrame) {
        builder?.add(f)
        t += LiveAnalyzer.FRAME_SECONDS
        chart.add(t, f.f0)
        smooth.add(f)
        if (f.voiced) {
            voicedFrames++
            if (abs(f.f0 - settings.target) <= settings.tolerance) onTargetFrames++
            shownHz = if (shownHz <= 0f) f.f0 else shownHz * 0.6f + f.f0 * 0.4f
            hzText.text = shownHz.toInt().toString()
            rangeBar.hz = shownHz
            val diff = shownHz - settings.target
            offText.text = when {
                abs(diff) <= settings.tolerance -> "On target"
                diff < 0 -> "${(-diff).toInt()} Hz under"
                else -> "${diff.toInt()} Hz over"
            }
        }
        val pct = if (voicedFrames > 0) onTargetFrames * 100 / voicedFrames else 0
        statusText.text = "on target $pct % · ${fmtTime(t)}"
        smooth.resonance()?.let { mRes.value = it / 100f; mRes.valueText = "${it.toInt()}" }
        smooth.weight()?.let { mWeight.value = it / 100f; mWeight.valueText = if (it >= 50) "Light ${it.toInt()}" else if (settings.goal == Goal.FTM) "Full ${it.toInt()}" else "Heavy ${it.toInt()}" }
        mVol.value = VoiceScores.loudness(f.db) / 100f; mVol.valueText = "≈ ${f.db.toInt()} dB"
        smooth.breathiness()?.let { mBreath.value = it / 100f; mBreath.valueText = when { it < 35 -> "Clear"; it < 65 -> "Some air"; else -> "Breathy" } }
    }

    private fun stopLive(save: Boolean = true) {
        analyzer?.stop(); analyzer = null
        setGlyph(recGlyph, false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val b = builder ?: return
        builder = null
        if (!save) return
        val s = b.build()
        if (s.stats == null) { statusText.text = "Not enough voice to analyse"; return }
        VoiceStore.pending = s
        startActivity(Intent(this, ReportActivity::class.java))
    }

    private fun showSettings() {
        var goal = settings.goal
        val box = col().apply { setPadding(dp(24), dp(16), dp(24), 0) }
        box.add(text("Training goal", 14f))
        val dirBar = TabBar(this).apply { setOptions(listOf("Feminine voice", "Masculine voice")); select(if (goal == Goal.FTM) 1 else 0) }
        box.add(dirBar, 6)
        fun slider(label: String, from: Float, to: Float, step: Float, value: Float, fmt: (Float) -> String): Pair<Slider, TextView> {
            val tv = text("$label: ${fmt(value)}", 14f)
            val s = Slider(this).apply { valueFrom = from; valueTo = to; stepSize = step; this.value = value.coerceIn(from, to) }
            s.addOnChangeListener { _, v, _ -> tv.text = "$label: ${fmt(v)}" }
            box.add(tv, 8); box.add(s)
            return s to tv
        }
        val (target, _) = slider("Target", 90f, 280f, 5f, settings.target) { "${it.toInt()} Hz" }
        val (tol, _) = slider("On-target zone", 5f, 30f, 5f, settings.tolerance) { "± ${it.toInt()} Hz" }
        val (goalDays, _) = slider("Weekly goal", 1f, 7f, 1f, settings.weeklyGoal.toFloat()) { "${it.toInt()} days" }
        fun hint(g: Goal) = if (g == Goal.FTM)
            "Masculine goal: male range 85–155 Hz. Pick a target you can reach without pushing — testosterone does most of the pitch drop; training shapes resonance, weight and melody."
            else "Feminine goal: female range 165–255 Hz. Typical male speaking range is 85–155 Hz."
        val hintText = text(hint(goal), 12f, Brass.TEXT_SOFT)
        box.add(hintText, 8)
        dirBar.onSelect = { i ->
            goal = if (i == 1) Goal.FTM else Goal.MTF
            target.value = goal.defaultTarget
            hintText.text = hint(goal)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Training target")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                settings.goal = goal
                settings.target = target.value; settings.tolerance = tol.value; settings.weeklyGoal = goalDays.value.toInt()
                applyTarget()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        if (analyzer?.isRunning == true) stopLive()
    }

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }
}
