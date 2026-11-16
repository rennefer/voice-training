package app.mp3splitter.voice

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.mp3splitter.Brass
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Today's score on the brass dial, the week, training steps and the achievement log. */
class ScoreActivity : BrassActivity() {
    private lateinit var body: LinearLayout
    private var achFilter = 2 // 0 today, 1 this week, 2 all

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = page()
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val c = body
        c.removeAllViews()
        val sc = Scoring(this)
        val settings = VoiceSettings(this)
        val now = System.currentTimeMillis()
        val streak = sc.streak()
        c.add(header("Today", if (streak > 0) "$streak-day streak" else null))

        // dial
        val today = sc.dayScore(now)
        val yesterday = sc.dayScore(now - 86_400_000L)
        val dialBox = FrameLayout(this)
        dialBox.addView(ScoreDialView(this).apply { fraction = (today?.total ?: 0) / 100f }, FrameLayout.LayoutParams(dp(240), dp(240), Gravity.CENTER))
        val mid = col().apply { gravity = Gravity.CENTER_HORIZONTAL }
        mid.add(caps("Daily score"), w = LinearLayout.LayoutParams.WRAP_CONTENT)
        mid.add(text(today?.total?.toString() ?: "0", 72f, Brass.TEXT, "light").apply { includeFontPadding = false }, w = LinearLayout.LayoutParams.WRAP_CONTENT)
        val sub = when {
            today == null -> "Train today to score"
            yesterday != null -> "of 100 · %+d vs yesterday".format(today.total - yesterday.total)
            else -> "of 100"
        }
        mid.add(text(sub, 14f, Brass.TEXT_SOFT), w = LinearLayout.LayoutParams.WRAP_CONTENT)
        dialBox.addView(mid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        c.addView(dialBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(250)).apply { topMargin = dp(8) })

        // imported plan: day X of N and today's progress
        settings.plan?.let { plan ->
            val (day, done) = plan.day(now)
            val r = plan.routine
            val doneToday = sc.daySessions(now).flatMap { s -> s.exercises.map { it.id } }.toSet()
            val n = r.count { it.id in doneToday }
            val card = row().apply { background = Brass.metal(16 * d); setPadding(dp(18), dp(14), dp(14), dp(14)) }
            val info = col()
            info.add(caps(plan.name, Brass.ON_BRASS))
            info.add(text(if (done) "Plan finished" else "Day $day of ${plan.days}", 18f, Brass.ON_BRASS, "medium"), 2)
            info.add(text(if (n >= r.size) "Done for today ✓" else "Today: $n of ${r.size} exercises", 12f, Brass.ON_BRASS), 2)
            card.add(info, weight = 1f)
            val start = TextView(this).apply {
                text = "Start"; textSize = 15f; setTextColor(0xFFEFE1BB.toInt()); gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = 21 * d; setColor(Brass.ON_BRASS) }
                setPadding(dp(20), dp(10), dp(20), dp(10))
                setOnClickListener {
                    startActivity(android.content.Intent(this@ScoreActivity, ExerciseActivity::class.java)
                        .putExtra(ExerciseActivity.EXTRA_IDS, r.map { it.id }.toTypedArray()).putExtra(ExerciseActivity.EXTRA_ROUTINE, true))
                }
            }
            card.add(start, w = LinearLayout.LayoutParams.WRAP_CONTENT)
            c.add(card, 12)
        }

        // parts
        val parts = panel(col(), 16, 12)
        fun part(name: String, pts: Float, value: String) {
            val r = row().apply { setPadding(0, dp(4), 0, dp(4)) }
            r.add(text(name, 13f), w = dp(116))
            val track = FrameLayout(this).apply { background = GradientDrawable().apply { cornerRadius = 4 * d; setColor(0x1AFFFFFF) } }
            val fill = View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF9C7B43.toInt(), 0xFFEFE1BB.toInt())).apply { cornerRadius = 4 * d } }
            track.addView(fill, FrameLayout.LayoutParams(0, dp(8)))
            track.post { fill.layoutParams = FrameLayout.LayoutParams((track.width * (pts / 25f).coerceIn(0f, 1f)).toInt(), dp(8)) }
            r.addView(track, LinearLayout.LayoutParams(0, dp(8), 1f))
            r.add(text(value, 12f, Brass.BRASS_TEXT).apply { gravity = Gravity.END }, 8, dp(64))
            parts.add(r)
        }
        val t = today
        part("Practice time", t?.minutesPts ?: 0f, "%.0f min".format(t?.minutes ?: 0f))
        part("Routine done", t?.routinePts ?: 0f, "${t?.routineDone ?: 0} / ${t?.routineSize ?: settings.routineList().size}")
        part("On target", t?.targetPts ?: 0f, "%.0f %%".format(t?.onTarget ?: 0f))
        part("Improvement", t?.improvePts ?: 0f, if (t == null) "–" else "%+.0f Hz".format(t.improvementHz))
        c.add(parts, 8)

        // week
        val wk = panel(col(), 16, 12)
        val days = sc.weekDays(now)
        val trained = sc.daysTrainedInWeek(now)
        val wh = row()
        wh.add(text("This week", 13f, Brass.TEXT, "medium"), weight = 1f)
        wh.add(text("$trained of ${settings.weeklyGoal} days goal", 13f, Brass.BRASS_TEXT), w = LinearLayout.LayoutParams.WRAP_CONTENT)
        wk.add(wh)
        val todayIdx = days.indexOfFirst { VoiceStore.dayKey(it) == VoiceStore.dayKey(now) }
        val scores = days.map { if (it > now + 43_200_000L) null else sc.dayScore(it)?.total?.toFloat() }
        val dayLetters = days.map { SimpleDateFormat("EEEEE", Locale.getDefault()).format(Date(it)) }
        wk.add(BarsView(this, 90f, dayLetters, todayIdx).apply { showValues = true; values = scores }, 8)
        val got = scores.filterNotNull()
        if (got.isNotEmpty()) wk.add(text("Weekly average %.0f · best %.0f".format(got.average(), got.maxOrNull() ?: 0f), 12f, Brass.TEXT_SOFT), 6)
        c.add(wk, 8)

        // steps
        val steps = sc.steps(); val cur = sc.currentStepIndex()
        val sp = panel(col(), 16, 14)
        val sh = row(); sh.add(text("Training steps", 13f, Brass.TEXT, "medium"), weight = 1f)
        sh.add(text("Step ${cur + 1} of ${steps.size}", 12f, Brass.BRASS_TEXT), w = LinearLayout.LayoutParams.WRAP_CONTENT)
        sp.add(sh)
        steps.forEachIndexed { i, s ->
            val r = row(Gravity.TOP).apply { setPadding(0, dp(10), 0, 0) }
            val dot = TextView(this).apply {
                gravity = Gravity.CENTER; textSize = 12f
                text = if (s.done) "✓" else "${i + 1}"
                setTextColor(if (s.done) Brass.ON_BRASS else if (i == cur) 0xFFEFE1BB.toInt() else Brass.TEXT_SOFT)
                background = if (s.done) Brass.metal(0f, oval = true) else GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(if (i == cur) 2 else 1), if (i == cur) Brass.BRASS_TEXT else 0x33FFFFFF) }
            }
            r.addView(dot, LinearLayout.LayoutParams(dp(26), dp(26)))
            val info = col().apply { setPadding(dp(12), 0, 0, 0) }
            info.add(text(s.title, 14f, if (i <= cur) Brass.TEXT else Brass.TEXT_SOFT, "medium"))
            info.add(text(s.goal, 12f, 0xFFA9B6C2.toInt()), 2)
            if (i == cur && !s.done) {
                info.add(text("${s.count} of ${s.goals.size} goals:", 11f, Brass.TEXT_SOFT), 6)
                s.goals.forEach { (g, ok) -> info.add(text((if (ok) "✓  " else "○  ") + g, 12f, if (ok) Brass.BRASS_TEXT else 0xFFC9D2DB.toInt()), 2) }
            }
            r.add(info, weight = 1f)
            sp.add(r)
        }
        c.add(sp, 8)

        // achievements
        val ach = sc.achievements()
        val weekStart = days.first() - 43_200_000L
        val dayStart = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        val ah = row()
        ah.add(caps("Achievements · ${ach.count { it.unlockedAt > 0 }} of ${ach.size}"), weight = 1f)
        c.add(ah, 18)
        val chips = row()
        listOf("Today", "This week", "All").forEachIndexed { i, l ->
            chips.addView(chip(l, achFilter == i) { achFilter = i; render() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)).apply { rightMargin = dp(6) })
        }
        c.add(chips, 8)
        val shown = when (achFilter) {
            0 -> ach.filter { it.unlockedAt >= dayStart }
            1 -> ach.filter { it.unlockedAt >= weekStart }
            else -> ach.sortedWith(compareByDescending<Achievement> { it.unlockedAt > 0 }.thenByDescending { it.unlockedAt })
        }
        if (shown.isEmpty()) c.add(text(if (achFilter == 0) "Nothing new today yet — keep going." else "Nothing new this week yet.", 13f, Brass.TEXT_SOFT), 8)
        val df = SimpleDateFormat("d MMM", Locale.getDefault())
        for (a in shown) {
            val got1 = a.unlockedAt > 0
            val r = row().apply { background = Brass.panel(14 * d); setPadding(dp(12), dp(10), dp(12), dp(10)); alpha = if (got1) 1f else 0.75f }
            val medal = TextView(this).apply {
                gravity = Gravity.CENTER; textSize = 16f
                text = when { !got1 -> "·"; a.icon == "flame" -> "▲"; a.icon == "target" -> "◎"; a.icon == "calendar" -> "▦"; else -> "★" }
                setTextColor(if (got1) Brass.ON_BRASS else Brass.TEXT_SOFT)
                background = if (got1) Brass.metal(0f, oval = true) else GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(1), 0x40FFFFFF) }
            }
            r.addView(medal, LinearLayout.LayoutParams(dp(36), dp(36)))
            val info = col().apply { setPadding(dp(12), 0, dp(8), 0) }
            info.add(text(a.name, 14f, Brass.TEXT, "medium")); info.add(text(a.desc, 12f, Brass.TEXT_SOFT), 2)
            r.add(info, weight = 1f)
            r.add(text(if (got1) df.format(Date(a.unlockedAt)) else a.progress, 11f, Brass.BRASS_TEXT).apply { gravity = Gravity.END }, w = dp(84))
            c.add(r, 6)
        }

        c.add(ghostButton("Progress over time") { startActivity(Intent(this, ProgressActivity::class.java)) }, 18)
    }
}
