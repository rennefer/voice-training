package app.mp3splitter.voice

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import app.mp3splitter.Brass

/** Exercise library: pick which exercises go into your routine, or run one directly. */
class ExercisesActivity : BrassActivity() {
    private lateinit var settings: VoiceSettings
    private lateinit var list: LinearLayout
    private lateinit var routineTitle: TextView
    private lateinit var routineSub: TextView
    private lateinit var chips: LinearLayout
    private var filter: Category? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = VoiceSettings(this)
        val c = page()
        c.add(header("Exercises"))
        c.add(goalSwitch(settings) { recreate() }, 12)

        // routine card
        val card = row().apply { background = Brass.metal(16 * d); setPadding(dp(18), dp(14), dp(14), dp(14)) }
        val info = col()
        info.add(caps("My routine", Brass.ON_BRASS))
        routineTitle = text("", 18f, Brass.ON_BRASS, "medium"); info.add(routineTitle, 2)
        routineSub = text("", 12f, Brass.ON_BRASS); info.add(routineSub, 2)
        card.add(info, weight = 1f)
        val start = TextView(this).apply {
            text = "Start"; textSize = 15f; setTextColor(0xFFEFE1BB.toInt()); gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 21 * d; setColor(Brass.ON_BRASS) }
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener {
                val ids = settings.routineList().map { it.id }
                if (ids.isNotEmpty()) startActivity(Intent(this@ExercisesActivity, ExerciseActivity::class.java)
                    .putExtra(ExerciseActivity.EXTRA_IDS, ids.toTypedArray()).putExtra(ExerciseActivity.EXTRA_ROUTINE, true))
            }
        }
        card.add(start, w = LinearLayout.LayoutParams.WRAP_CONTENT)
        c.add(card, 14)

        chips = row()
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
        c.add(hs, 12)

        list = col()
        c.add(list, 4)

        val health = col().apply {
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 14 * d; setStroke(dp(1), 0x59D6BB85) }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        health.add(text("Keep your voice healthy", 13f, Brass.BRASS_TEXT, "medium"))
        health.add(text("Stop if it hurts, feels strained or goes hoarse. Sip water, keep the neck and jaw relaxed, and don't push the larynx with your hands. 20–30 minutes a day is plenty, and rest days are fine." +
            (if (settings.goal == Goal.FTM) " Don't force your pitch lower than is comfortable, and don't use vocal fry to sound deeper. On testosterone the voice can crack and feel unsteady for a while — that's normal; keep it gentle." else ""), 12f, 0xFFA9B6C2.toInt()), 4)
        c.add(health, 16)

        render()
    }

    override fun onResume() { super.onResume(); if (::list.isInitialized) render() }

    private fun render() {
        val routine = settings.routine
        val chosen = Exercises.forGoal(settings.goal).filter { it.id in routine }
        routineTitle.text = "${chosen.size} chosen · ${chosen.sumOf { it.minutes }} min"
        val today = Scoring(this).daySessions(System.currentTimeMillis()).flatMap { s -> s.exercises.map { it.id } }.toSet()
        routineSub.text = "Today: ${chosen.count { it.id in today }} of ${chosen.size} done"

        chips.removeAllViews()
        val all = listOf<Category?>(null) + Category.values().toList()
        all.forEach { cat ->
            val ch = chip(cat?.chip ?: "All", filter == cat) { filter = cat; render() }
            chips.addView(ch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)).apply { rightMargin = dp(6) })
        }

        list.removeAllViews()
        for (cat in Category.values()) {
            if (filter != null && filter != cat) continue
            val head = row(Gravity.BOTTOM)
            head.add(caps(cat.title), weight = 1f)
            head.add(text(cat.hint, 11f, Brass.TEXT_SOFT), w = LinearLayout.LayoutParams.WRAP_CONTENT)
            list.add(head, 16)
            for (ex in Exercises.forGoal(settings.goal).filter { it.cat == cat }) list.add(item(ex, ex.id in routine, ex.id in today), 6)
        }
    }

    private fun item(ex: Exercise, inRoutine: Boolean, doneToday: Boolean): LinearLayout {
        val r = row().apply { background = Brass.panel(14 * d); setPadding(dp(14), dp(11), dp(10), dp(11)) }
        val info = col().apply {
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@ExercisesActivity, ExerciseActivity::class.java).putExtra(ExerciseActivity.EXTRA_IDS, arrayOf(ex.id)))
            }
        }
        info.add(text(if (doneToday) "${ex.name}  ✓" else ex.name, 15f, Brass.TEXT, "medium"))
        info.add(text(ex.how, 12f, 0xFFA9B6C2.toInt()).apply { setLineSpacing(0f, 1.15f) }, 2)
        info.add(text("${ex.minutes} min · ${ex.meter} · tap to try", 11f, Brass.TEXT_SOFT), 3)
        r.add(info, weight = 1f)
        val btn = TextView(this).apply {
            text = if (inRoutine) "Added" else "Add"; textSize = 13f; gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(if (inRoutine) Brass.ON_BRASS else Brass.BRASS_TEXT)
            background = if (inRoutine) Brass.metal(17 * d) else android.graphics.drawable.GradientDrawable().apply { cornerRadius = 17 * d; setStroke(dp(1), 0x80D6BB85.toInt()) }
            minWidth = dp(74); minHeight = dp(36)
            contentDescription = (if (inRoutine) "Remove " else "Add ") + ex.name + " to routine"
            setOnClickListener {
                val set = settings.routine.toMutableSet()
                if (!set.add(ex.id)) set.remove(ex.id)
                settings.routine = set
                render()
            }
        }
        r.add(btn, 10, LinearLayout.LayoutParams.WRAP_CONTENT)
        return r
    }
}
