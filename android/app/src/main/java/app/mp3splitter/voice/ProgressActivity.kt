package app.mp3splitter.voice

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import app.mp3splitter.Brass
import app.mp3splitter.TabBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Median pitch and other measures over time, plus the list of saved sessions. */
class ProgressActivity : BrassActivity() {
    private lateinit var body: LinearLayout
    private var range = 1 // 0 week, 1 month, 2 all

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = page()
        val streak = Scoring(this).streak()
        c.add(header("Progress", if (streak > 1) "$streak days in a row" else null))
        val tabs = TabBar(this).apply { setOptions(listOf("Week", "Month", "All")); select(range) }
        tabs.onSelect = { range = it; render() }
        c.add(tabs, 14)
        body = col()
        c.add(body)
    }

    override fun onResume() { super.onResume(); if (::body.isInitialized) render() }

    private fun render() {
        body.removeAllViews()
        val settings = VoiceSettings(this)
        val days = when (range) { 0 -> 7; 1 -> 31; else -> 100000 }
        val since = System.currentTimeMillis() - days * 86_400_000L
        val g = settings.goal
        val sessions = VoiceStore.all(this).filter { it.id >= since && it.stats != null && it.goal == g }
        if (sessions.isEmpty()) {
            body.add(text("No saved ${g.short.lowercase()} training sessions in this period yet. Train on the Voice screen or run an exercise, then save the report.", 14f, Brass.TEXT_SOFT), 20)
            return
        }
        val st = sessions.map { it.stats!! }

        val cp = panel(col(), 14, 12)
        val h = row()
        h.add(text("Median pitch", 13f, Brass.TEXT, "medium"), weight = 1f)
        h.add(text("${st.first().medianF0.toInt()} → ${st.last().medianF0.toInt()} Hz", 13f, Brass.BRASS_TEXT), w = LinearLayout.LayoutParams.WRAP_CONTENT)
        cp.add(h)
        cp.add(TrendChartView(this).apply { target = settings.target; values = st.map { it.medianF0 } }, 8)
        cp.add(text("Dashed line = your target ${settings.target.toInt()} Hz · shaded = female and male ranges", 11f, Brass.TEXT_SOFT), 4)
        body.add(cp, 12)

        val minis = row()
        fun mini(label: String, values: List<Float>, fmt: (Float) -> String) {
            val m = col().apply { background = Brass.panel(14 * d); setPadding(dp(12), dp(10), dp(12), dp(10)) }
            m.add(text(label, 12f, Brass.TEXT_SOFT))
            m.add(text(fmt(values.last()), 22f, Brass.TEXT, "light"), 2)
            m.add(BarsView(this, 40f).apply { this.values = values.takeLast(14) }, 6)
            minis.add(m, if (minis.childCount == 0) 0 else 8, weight = 1f)
        }
        mini("In ${g.goalName} range", st.map { g.pct(it) }) { "${it.toInt()} %" }
        mini("Resonance", st.map { it.resonance }) { "${it.toInt()}" }
        body.add(minis, 8)
        val minis2 = row()
        run {
            val m1 = col().apply { background = Brass.panel(14 * d); setPadding(dp(12), dp(10), dp(12), dp(10)) }
            m1.add(text("Vocal weight", 12f, Brass.TEXT_SOFT)); m1.add(text("${st.last().weight.toInt()}", 22f, Brass.TEXT, "light"), 2)
            m1.add(BarsView(this, 40f).apply { values = st.map { it.weight }.takeLast(14) }, 6)
            minis2.add(m1, weight = 1f)
            val m2 = col().apply { background = Brass.panel(14 * d); setPadding(dp(12), dp(10), dp(12), dp(10)) }
            m2.add(text("On target", 12f, Brass.TEXT_SOFT)); m2.add(text("${st.last().pctOnTarget.toInt()} %", 22f, Brass.TEXT, "light"), 2)
            m2.add(BarsView(this, 40f).apply { values = st.map { it.pctOnTarget }.takeLast(14) }, 6)
            minis2.add(m2, 8, weight = 1f)
        }
        body.add(minis2, 8)

        body.add(caps("Sessions"), 18)
        val fmt = SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault())
        for (s in sessions.reversed().take(40)) {
            val r = row().apply {
                background = Brass.panel(14 * d); setPadding(dp(14), dp(11), dp(14), dp(11)); isClickable = true
                setOnClickListener { startActivity(Intent(this@ProgressActivity, ReportActivity::class.java).putExtra(ReportActivity.EXTRA_ID, s.id)) }
            }
            val info = col()
            info.add(text(fmt.format(Date(s.id)), 14f, Brass.TEXT, "medium"))
            info.add(text("${s.label} · ${fmtTime(s.seconds)}" + if (s.notes.isNotBlank()) " · notes" else "", 12f, Brass.TEXT_SOFT), 2)
            r.add(info, weight = 1f)
            r.add(text("${s.stats!!.medianF0.toInt()} Hz", 14f, Brass.BRASS_TEXT), w = LinearLayout.LayoutParams.WRAP_CONTENT)
            body.add(r, 6)
        }
    }
}
