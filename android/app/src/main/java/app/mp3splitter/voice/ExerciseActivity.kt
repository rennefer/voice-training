package app.mp3splitter.voice

import android.Manifest
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import app.mp3splitter.Brass
import app.mp3splitter.R
import kotlin.math.abs
import kotlin.math.ln

/** Runs one exercise, or a whole routine one exercise after another. */
class ExerciseActivity : BrassActivity() {
    companion object {
        const val EXTRA_IDS = "ids"
        const val EXTRA_ROUTINE = "routine"
    }

    private enum class Phase { READY, RUNNING, DONE }

    private lateinit var settings: VoiceSettings
    private lateinit var exercises: List<Exercise>
    private var index = 0
    private var phase = Phase.READY
    private var isRoutine = false
    private lateinit var session: SessionBuilder
    private var goal = Goal.MTF

    private lateinit var catText: TextView
    private lateinit var nameText: TextView
    private lateinit var roundText: TextView
    private lateinit var howText: TextView
    private lateinit var stepsText: TextView
    private lateinit var cautionText: TextView
    private lateinit var passage: TextView
    private lateinit var chart: PitchChartView
    private lateinit var meterA: MeterView
    private lateinit var meterB: MeterView
    private lateinit var meters: LinearLayout
    private lateinit var bigTimer: TextView
    private lateinit var tileScore: Pair<TextView, TextView>
    private lateinit var tileNow: Pair<TextView, TextView>
    private lateinit var tileTime: Pair<TextView, TextView>
    private lateinit var leftBtn: TextView
    private lateinit var mainBtn: TextView

    private var analyzer: LiveAnalyzer? = null
    private val exFrames = ArrayList<VoiceFrame>()
    private val exTimes = ArrayList<Float>()
    private val smooth = LiveSmoother()
    private var t = 0f
    private var lastRound = -1
    private var timerStart = 0L
    private var exFrom = 0
    private val handler = Handler(Looper.getMainLooper())
    private var clip: Uri? = null
    private var player: MediaPlayer? = null

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) start() else howText.text = "Microphone permission is needed for this exercise."
    }
    private val pickClip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { clip = uri; leftBtn.text = "Clip chosen ✓" }
    }

    private val ex get() = exercises[index]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = VoiceSettings(this)
        exercises = (intent.getStringArrayExtra(EXTRA_IDS) ?: emptyArray()).mapNotNull { Exercises.byId(it) }
        isRoutine = intent.getBooleanExtra(EXTRA_ROUTINE, false)
        if (exercises.isEmpty()) { finish(); return }
        goal = settings.goal
        session = SessionBuilder(settings.target, settings.tolerance, VoiceStore.pendingPcm(this).apply { delete() }, goal)

        val c = page()
        val head = row()
        val close = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close); setColorFilter(Brass.BRASS_TEXT); background = Brass.panel(0f, oval = true)
            contentDescription = "Close exercise"; setOnClickListener { leave() }
        }
        head.addView(close, LinearLayout.LayoutParams(dp(42), dp(42)))
        val titles = col().apply { setPadding(dp(12), 0, 0, 0) }
        catText = caps(""); nameText = text("", 19f, Brass.TEXT, "medium")
        titles.add(catText); titles.add(nameText, 2)
        head.add(titles, weight = 1f)
        roundText = text("", 14f, Brass.TEXT_SOFT, "mono")
        head.add(roundText, w = LinearLayout.LayoutParams.WRAP_CONTENT)
        c.add(head)

        howText = text("", 15f, 0xFFC9D2DB.toInt()).apply { setLineSpacing(0f, 1.2f) }
        c.add(howText, 12)
        stepsText = text("", 15f, Brass.TEXT).apply { setLineSpacing(0f, 1.25f) }
        c.add(stepsText, 10)
        cautionText = text("", 13f, 0xFFA9B6C2.toInt()).apply {
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 14 * d; setStroke(dp(1), 0x59D6BB85) }
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        c.add(cautionText, 10)

        passage = text(Exercises.PASSAGE, 17f, Brass.TEXT).apply {
            setLineSpacing(0f, 1.35f); background = Brass.panel(16 * d); setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        c.add(passage, 12)

        bigTimer = text("", 64f, Brass.TEXT, "light").apply { gravity = Gravity.CENTER }
        c.add(bigTimer, 24)

        chart = PitchChartView(this).apply { background = Brass.panel(16 * d) }
        c.addView(chart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(300)).apply { topMargin = dp(12) })

        meters = panel(col(), 16, 10)
        meterA = MeterView(this, "Resonance", "Dark", "Bright")
        meterB = MeterView(this, "Vocal weight", "Heavy", "Light")
        meters.add(meterA); meters.add(meterB, 6)
        c.add(meters, 10)

        val tiles = row()
        tileScore = tile(tiles, "Score"); tileNow = tile(tiles, "Now"); tileTime = tile(tiles, "Time")
        c.add(tiles, 10)

        c.add(View(this)).let { (it.layoutParams as LinearLayout.LayoutParams).apply { height = 0; weight = 1f } }
        val bottom = row()
        leftBtn = ghostButton("Skip") { onLeft() }
        bottom.add(leftBtn, weight = 1f)
        mainBtn = brassButton("Start") { onMain() }
        bottom.add(mainBtn, 12, weight = 1.3f)
        c.add(bottom, 16)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = leave() })
        load()
    }

    private fun tile(parent: LinearLayout, label: String): Pair<TextView, TextView> {
        val t = col().apply { background = Brass.panel(14 * d); setPadding(dp(12), dp(10), dp(12), dp(10)) }
        val l = text(label, 12f, Brass.TEXT_SOFT); val v = text("–", 22f, Brass.TEXT, "light")
        t.add(l); t.add(v, 2)
        parent.add(t, if (parent.childCount == 0) 0 else 8, weight = 1f)
        return l to v
    }

    private fun load() {
        phase = Phase.READY
        exFrames.clear(); exTimes.clear(); smooth.clear(); t = 0f; lastRound = -1
        catText.text = "${ex.cat.chip.uppercase()}" + if (exercises.size > 1) " · ${index + 1} OF ${exercises.size}" else ""
        nameText.text = ex.name
        howText.text = ex.how
        val plan = settings.plan
        stepsText.text = ex.steps.mapIndexed { i, st -> "${i + 1}.  ${plan?.fill(st) ?: st}" }.joinToString("\n")
        stepsText.visibility = if (ex.steps.isEmpty()) View.GONE else View.VISIBLE
        cautionText.text = ex.caution
        cautionText.visibility = if (ex.caution.isEmpty()) View.GONE else View.VISIBLE
        passage.visibility = if (ex.kind == Kind.READ) View.VISIBLE else View.GONE
        val timerOnly = ex.kind == Kind.TIMER
        bigTimer.visibility = if (timerOnly) View.VISIBLE else View.GONE
        bigTimer.text = fmtTime(ex.minutes * 60f)
        chart.visibility = if (timerOnly) View.GONE else View.VISIBLE
        meters.visibility = if (timerOnly) View.GONE else View.VISIBLE
        (tileScore.first.parent as View).visibility = if (timerOnly) View.GONE else View.VISIBLE
        (tileNow.first.parent.parent as View).visibility = if (timerOnly) View.GONE else View.VISIBLE
        chart.clear()
        chart.target = settings.target; chart.tolerance = settings.tolerance
        chart.lo = (settings.target - 100f).coerceAtLeast(60f); chart.hi = settings.target + 120f
        if (ex.roundSeconds > 0f) {
            chart.roundSeconds = ex.roundSeconds
            chart.guide = { tt -> Exercises.guide(ex, tt, settings.target) }
            roundText.text = "Round 1/${ex.rounds}"
        } else {
            chart.roundSeconds = 0f; chart.guide = null; roundText.text = ""
        }
        chart.idleText = "Tap Start, then follow along"
        tileScore.first.text = if (ex.cat == Category.PLAN && ex.kind != Kind.TIMER) "Median" else when (ex.kind) {
            Kind.GLIDE, Kind.LADDER -> "Match"; Kind.HOLD, Kind.TARGET, Kind.MIMIC -> "On target"
            Kind.RESONANCE -> if (goal == Goal.FTM) "Darkness" else "Resonance"
            Kind.WEIGHT -> if (goal == Goal.FTM) "Fullness" else "Lightness"
            Kind.MELODY -> if (goal == Goal.FTM) "Steadiness" else "Melody"
            Kind.READ -> if (goal == Goal.FTM) "Male range" else "Female range"; Kind.BASELINE -> "Median"; else -> "Voice time"
        }
        tileScore.second.text = "–"; tileNow.second.text = "–"; tileTime.second.text = fmtTime(0f)
        meterA.value = null; meterA.valueText = "–"; meterB.value = null; meterB.valueText = "–"
        leftBtn.text = if (ex.kind == Kind.MIMIC) (if (clip != null) "Clip chosen ✓" else "Open clip") else if (exercises.size > 1) "Skip" else "Back"
        mainBtn.text = "Start"
    }

    private fun onLeft() {
        when {
            phase == Phase.RUNNING -> {}
            ex.kind == Kind.MIMIC && phase == Phase.READY -> try { pickClip.launch(arrayOf("audio/*")) } catch (_: Exception) {}
            exercises.size > 1 -> next()
            else -> leave()
        }
    }

    private fun onMain() {
        when (phase) {
            Phase.READY -> if (ex.kind == Kind.TIMER || hasMic()) start() else askMic.launch(Manifest.permission.RECORD_AUDIO)
            Phase.RUNNING -> stop()
            Phase.DONE -> next()
        }
    }

    private fun start() {
        phase = Phase.RUNNING
        mainBtn.text = "Stop"
        timerStart = System.currentTimeMillis(); exFrom = session.frames.size
        HzService.stop(this)   // the exercise needs the microphone
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (ex.kind == Kind.TIMER) { tickTimer(); return }
        if (ex.kind == Kind.MIMIC) clip?.let { u ->
            player = try { MediaPlayer().apply { setDataSource(this@ExerciseActivity, u); isLooping = true; prepare(); start() } } catch (e: Exception) { null }
        }
        analyzer = LiveAnalyzer({ onFrame(it) }, { msg -> howText.text = msg; stop() }, session.pcm).also { it.start() }
    }

    private fun tickTimer() {
        if (phase != Phase.RUNNING) return
        val el = (System.currentTimeMillis() - timerStart) / 1000f
        val left = ex.minutes * 60f - el
        bigTimer.text = fmtTime(left.coerceAtLeast(0f))
        if (left <= 0f) { stop(); return }
        handler.postDelayed({ tickTimer() }, 250)
    }

    // guided rounds get the guide delay added so the last round finishes too
    private val maxSeconds get() = if (ex.roundSeconds > 0f) ex.roundSeconds * ex.rounds + Exercises.GUIDE_DELAY else ex.minutes * 60f

    private fun onFrame(f: VoiceFrame) {
        if (phase != Phase.RUNNING) return   // frames still queued after the exercise ended
        t += LiveAnalyzer.FRAME_SECONDS
        exFrames += f; exTimes += t; session.add(f); smooth.add(f)
        if (ex.roundSeconds > 0f) {
            val round = (t / ex.roundSeconds).toInt().coerceAtMost(ex.rounds - 1)
            if (round != lastRound) { lastRound = round; chart.clear(); roundText.text = "Round ${(round + 1).coerceAtMost(ex.rounds)}/${ex.rounds}" }
            chart.add(t - round * ex.roundSeconds, f.f0)
        } else chart.add(t, f.f0)
        if (f.voiced) tileNow.second.text = "${f.f0.toInt()} Hz"
        smooth.resonance()?.let { meterA.value = it / 100f; meterA.valueText = it.toInt().toString() }
        smooth.weight()?.let { meterB.value = it / 100f; meterB.valueText = it.toInt().toString() }
        tileTime.second.text = fmtTime(t)
        if (exFrames.size % 10 == 0) tileScore.second.text = if (ex.cat == Category.PLAN) planMedian()?.let { "$it Hz" } ?: "–" else scoreText(score())
        if (t >= maxSeconds) stop()
    }

    /** Score 0..100 for the current exercise from its frames. */
    private fun score(): Int {
        val v = exFrames.indices.filter { exFrames[it].voiced }
        if (ex.kind == Kind.TIMER) return 100
        if (v.isEmpty()) return 0
        val tg = settings.target; val tol = settings.tolerance
        return when (ex.kind) {
            Kind.GLIDE, Kind.LADDER -> {
                val hits = v.count { i ->
                    val tt = exTimes[i] % ex.roundSeconds
                    val g = Exercises.guide(ex, tt, tg) ?: return@count false
                    abs(12 * ln(exFrames[i].f0 / g) / ln(2.0)) <= 1.5
                }
                hits * 100 / v.size
            }
            Kind.HOLD, Kind.TARGET, Kind.MIMIC -> v.count { abs(exFrames[it].f0 - tg) <= tol } * 100 / v.size
            Kind.READ -> v.count { exFrames[it].f0 in goal.goalLo..goal.goalHi } * 100 / v.size
            Kind.RESONANCE -> (SessionStats.compute(exFrames, LiveAnalyzer.FRAME_SECONDS, tg, tol)?.resonance ?: 0f).toInt().let { if (goal == Goal.FTM) 100 - it else it }
            Kind.WEIGHT -> (SessionStats.compute(exFrames, LiveAnalyzer.FRAME_SECONDS, tg, tol)?.weight ?: 0f).toInt().let { if (goal == Goal.FTM) 100 - it else it }
            Kind.MELODY -> melodyScore(SessionStats.compute(exFrames, LiveAnalyzer.FRAME_SECONDS, tg, tol)?.melody ?: 0f, goal)
            Kind.BASELINE -> SessionStats.medianOf(v.map { exFrames[it].f0 }).toInt()
            Kind.FREE -> (v.size * LiveAnalyzer.FRAME_SECONDS / 30f * 100f).toInt().coerceAtMost(100)
            else -> 0
        }
    }

    /** Median pitch of this exercise so far, rounded, or null. */
    private fun planMedian(): Int? = exFrames.filter { it.voiced }.map { it.f0 }.takeIf { it.isNotEmpty() }?.let { SessionStats.medianOf(it).toInt() }

    private fun scoreText(s: Int) = when (ex.kind) {
        Kind.BASELINE -> "$s Hz"
        Kind.RESONANCE, Kind.WEIGHT -> "$s"
        else -> "$s %"
    }

    private fun stop() {
        if (phase != Phase.RUNNING) return
        analyzer?.stop(); analyzer = null
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }; player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        phase = Phase.DONE
        val secs = if (ex.kind == Kind.TIMER) (System.currentTimeMillis() - timerStart) / 1000f else t
        session.activeSeconds += secs
        val s = score()
        tileScore.second.text = scoreText(s)
        val med = planMedian()
        val detail = when {
            ex.cat == Category.PLAN -> if (ex.kind == Kind.TIMER || med == null) "done" else "done · $med Hz"
            ex.kind == Kind.BASELINE -> { if (s > 0) settings.baseline = s.toFloat(); "comfortable pitch $s Hz" }
            else -> scoreText(s)
        }
        if (ex.cat == Category.PLAN) tileScore.second.text = med?.let { "$it Hz" } ?: "✓"
        val saved = if (ex.kind == Kind.BASELINE || ex.cat == Category.PLAN) 100 else s
        if (ex.kind == Kind.TIMER || exFrames.any { it.voiced }) session.results += ExerciseResult(ex.id, saved, secs, detail, exFrom, session.frames.size)
        howText.text = when (ex.kind) {
            Kind.BASELINE -> "Your comfortable pitch right now is about $s Hz. Your target is ${settings.target.toInt()} Hz — move toward it in small steps."
            Kind.TIMER -> "Done. Nice and calm."
            else -> if (ex.cat == Category.PLAN) "Done." + (med?.let { " Your median pitch was $it Hz." } ?: "") else "Done — ${tileScore.first.text.toString().lowercase()} ${scoreText(s)}."
        }
        mainBtn.text = if (index < exercises.size - 1) "Next exercise" else "Finish"
    }

    private fun next() {
        if (phase == Phase.RUNNING) stop()
        if (index < exercises.size - 1) { index++; load() } else finishSession()
    }

    private fun finishSession() {
        if (session.results.isEmpty()) { finish(); return }
        val s = session.build(if (isRoutine) exercises.size else 0)
        if (session.results.any { it.id.startsWith("plan_") }) settings.plan?.let { p -> s.plan = p.name; s.notePrompt = p.notePrompt.ifEmpty { null } }
        VoiceStore.pending = s
        startActivity(Intent(this, ReportActivity::class.java))
        finish()
    }

    private fun leave() {
        if (phase == Phase.RUNNING) stop()
        finishSession()
    }

    override fun onPause() {
        super.onPause()
        if (phase == Phase.RUNNING && ex.kind != Kind.TIMER) stop()
    }

    override fun onDestroy() {
        analyzer?.stop(); player?.release(); handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
