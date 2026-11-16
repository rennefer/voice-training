package app.mp3splitter.voice

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import app.mp3splitter.Brass
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Exercise library: pick which exercises go into your routine, or run one directly. */
class ExercisesActivity : BrassActivity() {
    private lateinit var settings: VoiceSettings
    private lateinit var list: LinearLayout
    private lateinit var routineTitle: TextView
    private lateinit var routineSub: TextView
    private lateinit var chips: LinearLayout
    private var filter: Category? = null
    private lateinit var routineCaps: TextView
    private lateinit var planBox: LinearLayout

    /** Import a plan file (e.g. home exercises from a speech therapist). It stays on this phone. */
    private val pickPlan = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            val plan = Plan.parse(text)
            PlanStore.save(this, plan)
            Toast.makeText(this, "Plan added: ${plan.name}", Toast.LENGTH_SHORT).show()
            render()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Could not read that file", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = VoiceSettings(this)
        val c = page()
        c.add(header("Exercises"))
        c.add(goalSwitch(settings) { recreate() }, 12)

        // routine card
        val card = row().apply { background = Brass.metal(16 * d); setPadding(dp(18), dp(14), dp(14), dp(14)) }
        val info = col()
        routineCaps = caps("My routine", Brass.ON_BRASS); info.add(routineCaps)
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

        planBox = col()
        c.add(planBox, 12)

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
        val plan = settings.plan
        val chosen = settings.routineList()
        val today = Scoring(this).daySessions(System.currentTimeMillis()).flatMap { s -> s.exercises.map { it.id } }.toSet()
        routineCaps.text = (plan?.name ?: "My routine").uppercase()
        routineTitle.text = if (plan != null) plan.day().let { (d, done) -> if (done) "Finished · ${plan.days} days" else "Day $d of ${plan.days} · ${chosen.sumOf { it.minutes }} min" }
            else "${chosen.size} chosen · ${chosen.sumOf { it.minutes }} min"
        renderPlan(plan, today)
        routineSub.text = "Today: ${chosen.count { it.id in today }} of ${chosen.size} done"

        chips.removeAllViews()
        val all = listOf<Category?>(null) + Category.values().filter { it != Category.PLAN }
        all.forEach { cat ->
            val ch = chip(cat?.chip ?: "All", filter == cat) { filter = cat; render() }
            chips.addView(ch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)).apply { rightMargin = dp(6) })
        }

        list.removeAllViews()
        for (cat in Category.values().filter { it != Category.PLAN }) {
            if (filter != null && filter != cat) continue
            val head = row(Gravity.BOTTOM)
            head.add(caps(cat.title), weight = 1f)
            head.add(text(cat.hint, 11f, Brass.TEXT_SOFT), w = LinearLayout.LayoutParams.WRAP_CONTENT)
            list.add(head, 16)
            for (ex in Exercises.forGoal(settings.goal).filter { it.cat == cat }) list.add(item(ex, ex.id in routine, ex.id in today, plan == null), 6)
        }
    }

    private fun item(ex: Exercise, inRoutine: Boolean, doneToday: Boolean, canAdd: Boolean = true): LinearLayout {
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
        if (canAdd) r.add(btn, 10, LinearLayout.LayoutParams.WRAP_CONTENT)
        return r
    }

    /** "My plan" panel: import button, or the plan's days, the user's own words and its exercises. */
    private fun renderPlan(plan: Plan?, today: Set<String>) {
        planBox.removeAllViews()
        if (plan == null) {
            planBox.add(ghostButton("Import plan file") { pickPlan.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) })
            planBox.add(text("A plan from your speech therapist, stored only on this phone", 11f, Brass.TEXT_SOFT).apply { gravity = Gravity.CENTER }, 6)
            return
        }
        val p = panel(col(), 14, 12)
        val (day, done) = plan.day()
        val head = row()
        head.add(caps("My plan"), weight = 1f)
        head.add(text(if (done) "Finished" else "Day $day of ${plan.days}", 12f, Brass.BRASS_TEXT), w = LinearLayout.LayoutParams.WRAP_CONTENT)
        p.add(head)
        if (plan.source.isNotEmpty()) p.add(text(plan.source, 12f, Brass.TEXT_SOFT), 4)
        // one circle per day, filled when the plan was trained that day
        val sessions = VoiceStore.all(this).filter { it.plan != null && it.id >= Plan.startOfDay(plan.startedAt) }
        val trained = sessions.map { Plan.startOfDay(it.id) }.toSet()
        val dots = row()
        for (i in 0 until plan.days) {
            val dayStart = Plan.startOfDay(plan.startedAt + i * 86_400_000L + 3_600_000L)
            val ok = dayStart in trained; val isToday = i + 1 == day && !done
            val dot = text(if (ok) "✓" else "${i + 1}", 12f, if (ok) Brass.ON_BRASS else if (isToday) 0xFFEFE1BB.toInt() else Brass.TEXT_SOFT).apply {
                gravity = Gravity.CENTER
                background = if (ok) Brass.metal(15 * d, oval = true) else android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setStroke(dp(if (isToday) 2 else 1), if (isToday) Brass.BRASS_TEXT else 0x33FFFFFF)
                }
            }
            dots.addView(dot, LinearLayout.LayoutParams(dp(30), dp(30)).apply { if (i > 0) marginStart = dp(6) })
        }
        p.add(dots, 10)
        if (plan.intro.isNotEmpty()) p.add(text(plan.intro, 13f, 0xFFA9B6C2.toInt()), 10)
        for (f in plan.fields) {
            p.add(text(f.label, 12f, Brass.TEXT), 10)
            val input = EditText(this).apply {
                setText(plan.values[f.key] ?: ""); hint = f.hint; textSize = 15f; setTextColor(Brass.TEXT); setHintTextColor(0x668FA1B0)
                isSingleLine = true; background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 10 * d; setColor(0x800D141C.toInt()); setStroke(dp(1), 0x1AFFFFFF) }
                setPadding(dp(10), dp(9), dp(10), dp(9)); contentDescription = f.label
                setOnFocusChangeListener { _, has -> if (!has) { plan.values[f.key] = text.toString().trim(); PlanStore.save(this@ExercisesActivity, plan) } }
            }
            p.add(input, 4)
        }
        for (ex in plan.exercises) {
            val r = col().apply {
                background = Brass.panel(14 * d); setPadding(dp(14), dp(10), dp(14), dp(10)); isClickable = true
                setOnClickListener { startActivity(Intent(this@ExercisesActivity, ExerciseActivity::class.java).putExtra(ExerciseActivity.EXTRA_IDS, arrayOf(ex.id))) }
            }
            r.add(text(if (ex.id in today) "${ex.name}  ✓" else ex.name, 14f, Brass.TEXT, "medium"))
            r.add(text("${ex.minutes} min · ${ex.meter}" + if (ex.optional) " · optional" else "", 11f, Brass.TEXT_SOFT), 2)
            p.add(r, 6)
        }
        if (plan.safety.isNotEmpty()) p.add(text(plan.safety, 11f, Brass.TEXT_SOFT), 10)
        val btns = row()
        btns.add(ghostButton("Restart") {
            MaterialAlertDialogBuilder(this).setMessage("Restart the plan from day 1 today? Your saved sessions are kept.")
                .setPositiveButton("Restart") { _, _ -> plan.startedAt = System.currentTimeMillis(); PlanStore.save(this, plan); render() }
                .setNegativeButton("Cancel", null).show()
        }, weight = 1f)
        btns.add(ghostButton("Remove plan") {
            MaterialAlertDialogBuilder(this).setMessage("Remove this plan from the app? Your saved sessions are kept, and your normal routine comes back.")
                .setPositiveButton("Remove") { _, _ -> PlanStore.save(this, null); render() }
                .setNegativeButton("Cancel", null).show()
        }, 8, weight = 1f)
        p.add(btns, 12)
        planBox.add(p)
    }

    override fun onPause() {
        super.onPause()
        currentFocus?.clearFocus()   // saves a field that is still being edited
    }
}
