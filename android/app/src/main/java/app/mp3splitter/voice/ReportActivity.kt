package app.mp3splitter.voice

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import app.mp3splitter.Player
import app.mp3splitter.R
import app.mp3splitter.core.IN_RATE
import app.mp3splitter.core.Part
import app.mp3splitter.core.PartEncoder
import app.mp3splitter.core.Quality
import java.io.File
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import app.mp3splitter.Brass
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Report for one session: summary, pitch distribution, details, exercises, feeling and notes. */
class ReportActivity : BrassActivity() {
    companion object { const val EXTRA_ID = "id" }

    private lateinit var session: Session
    private var saved = false
    private lateinit var notes: EditText
    private lateinit var feelRow: LinearLayout
    private lateinit var saveBtn: TextView

    // playback
    private var pcmPlayer: Player? = null
    private var media: MediaPlayer? = null
    private var playing = false
    private var trace: TraceView? = null
    private var playBtn: ImageButton? = null
    private var timeText: TextView? = null
    private val ui = Handler(Looper.getMainLooper())
    private var audioSeconds = 0f
    private var gen = 0   // ignores callbacks from a player that was replaced

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        val s = if (id != 0L) VoiceStore.get(this, id) else VoiceStore.pending
        if (s == null) { finish(); return }
        session = s
        saved = id != 0L
        build()
    }

    private fun build() {
        val c = page()
        val s = session
        val st = s.stats
        c.add(header("Session report"))
        val when_ = SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()).format(Date(s.id))
        c.add(text("$when_ · ${s.label} · ${fmtTime(s.seconds)} · ${s.goal.short}", 12f, Brass.TEXT_SOFT).apply { setPadding(dp(54), 0, 0, 0) }, 2)

        buildPlayback(c)

        if (st == null) {
            c.add(text("Not enough voice was measured for a full analysis.", 14f, Brass.TEXT_SOFT), 16)
        } else {
            val g = s.goal
            val prev = VoiceStore.all(this).lastOrNull { it.id < s.id && it.stats != null && it.goal == g }?.stats
            fun delta(v: Float, p: Float?, unit: String, fmt: String = "%+.0f") = p?.let { (fmt.format(v - it)) + unit + " vs last" } ?: "first session"

            val grid = col()
            val r1 = row(); val r2 = row()
            tile(r1, "Median pitch", "${st.medianF0.toInt()} Hz", "${VoiceScores.noteName(st.medianF0)} · ${delta(st.medianF0, prev?.medianF0, " Hz")}")
            tile(r1, "In ${g.goalName} range", "${g.pct(st).toInt()} %", delta(g.pct(st), prev?.let { g.pct(it) }, " %"))
            tile(r2, "Resonance", "${st.resonance.toInt()}", (if (st.resonance >= 50) "bright side · " else "dark side · ") + delta(st.resonance, prev?.resonance, ""))
            tile(r2, "Vocal weight", "${st.weight.toInt()}", (if (st.weight >= 50) "lighter · " else "heavier · ") + delta(st.weight, prev?.weight, ""))
            grid.add(r1); grid.add(r2, 8)
            c.add(grid, 14)

            val hp = panel(col(), 14, 12)
            val hh = row(); hh.add(text("Where your pitch was", 13f, Brass.TEXT, "medium"), weight = 1f); hh.add(text("share of time", 12f, Brass.TEXT_SOFT), w = LinearLayout.LayoutParams.WRAP_CONTENT)
            hp.add(hh)
            hp.add(HistogramView(this, st.hist), 8)
            val legend = row()
            legend.add(text("■ female 165–255", 11f, Brass.BRASS_TEXT), w = LinearLayout.LayoutParams.WRAP_CONTENT)
            legend.add(text("■ male 85–155", 11f, MALE_TEXT), 14, LinearLayout.LayoutParams.WRAP_CONTENT)
            legend.add(text("■ between", 11f, Brass.TEXT_SOFT), 14, LinearLayout.LayoutParams.WRAP_CONTENT)
            hp.add(legend, 6)
            c.add(hp, 12)

            val det = panel(col(), 14, 4)
            fun line(k: String, v: String, note: String) {
                val r = row().apply { setPadding(0, dp(8), 0, dp(8)) }
                r.add(text(k, 14f, 0xFFC9D2DB.toInt()), weight = 1f)
                r.add(text(v, 14f), w = LinearLayout.LayoutParams.WRAP_CONTENT)
                r.add(text(note, 12f, Brass.BRASS_TEXT), 8, LinearLayout.LayoutParams.WRAP_CONTENT)
                det.add(r)
            }
            line("Pitch range", "${st.lowF0.toInt()}–${st.highF0.toInt()} Hz", "${VoiceScores.noteName(st.lowF0)}–${VoiceScores.noteName(st.highF0)}")
            if (g == Goal.FTM) line("In female range", "${st.pctFemale.toInt()} %", "165–255 Hz") else line("In male range", "${st.pctMale.toInt()} %", "85–155 Hz")
            line("On target", "${st.pctOnTarget.toInt()} %", "${s.target.toInt()} ±${s.tolerance.toInt()}")
            line("Melody (variation)", "%.1f semitones".format(st.melody), if (g == Goal.FTM) (when { st.melody <= 2 -> "steady"; st.melody < 3 -> "some movement"; else -> "lively" }) else (when { st.melody < 1.5 -> "flat"; st.melody < 2.5 -> "calm"; else -> "lively" }))
            line("Formants F1 / F2", "${st.f1.toInt()} / ${st.f2.toInt()} Hz", "median")
            line("Volume", "≈ ${st.volumeDb.toInt()} dB", "approx.")
            line("Breathiness (HNR)", "%.0f dB".format(st.hnr), when { st.breathiness < 35 -> "clear"; st.breathiness < 65 -> "some air"; else -> "breathy" })
            line("Voice time", fmtTime(st.voicedSeconds), "")
            c.add(det, 12)
        }

        if (s.exercises.isNotEmpty()) {
            c.add(caps("Exercises · ${s.exercises.size}"), 14)
            for (r in s.exercises) {
                val ex = Exercises.byId(r.id)
                val ep = panel(col(), 14, 10)
                val rr = row()
                rr.add(text(ex?.name ?: r.id, 14f, Brass.TEXT, "medium"), weight = 1f)
                rr.add(text(r.detail, 12f, Brass.BRASS_TEXT), w = LinearLayout.LayoutParams.WRAP_CONTENT)
                ep.add(rr)
                val tr = r.trace
                if (tr != null && tr.any { it > 0f }) {
                    val guide: ((Float) -> Float?)? = if (ex != null && ex.roundSeconds > 0f) { t -> Exercises.guide(ex, t, s.target) } else null
                    ep.addView(ExerciseChartView(this, tr, r.traceSec, s.target, s.tolerance, guide).apply { background = Brass.panel(14 * d) },
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(120)).apply { topMargin = dp(8) })
                } else {
                    ep.add(text(when {
                        ex?.kind == Kind.TIMER -> "Timer · ${fmtTime(r.seconds)}"
                        tr == null -> "${fmtTime(r.seconds)} · no graph (recorded before graphs were added)"
                        else -> "No voice measured"
                    }, 12f, Brass.TEXT_SOFT), 6)
                }
                c.add(ep, 8)
            }
        }

        // feeling + notes
        val np = panel(col(), 14, 12)
        val fr = row(); fr.add(text("How did it feel?", 13f, Brass.TEXT, "medium"), weight = 1f)
        feelRow = row(); fr.add(feelRow, w = LinearLayout.LayoutParams.WRAP_CONTENT)
        np.add(fr)
        renderFeel()
        notes = EditText(this).apply {
            setText(s.notes); hint = if (s.notePrompt != null) "Your answer…" else "Notes — how your voice felt, what worked, what to try next"
            setTextColor(Brass.TEXT); setHintTextColor(Brass.TEXT_SOFT); textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2; gravity = Gravity.TOP
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 10 * d; setColor(0x800D141C.toInt()); setStroke(dp(1), 0x1AFFFFFF) }
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        np.add(s.notePrompt?.let { text(it, 13f, Brass.BRASS_TEXT) } ?: text("Notes", 12f, Brass.TEXT_SOFT), 10)
        np.add(notes, 4)
        c.add(np, 12)

        c.add(text("Measured with the phone microphone. Resonance and weight are estimates — compare sessions recorded the same way.", 11f, Brass.TEXT_SOFT), 12)

        val b = row()
        b.add(ghostButton("PDF report", 52) { exportPdf() }, weight = 1f)
        saveBtn = brassButton(if (saved) "Save changes" else "Save session") { save() }
        b.add(saveBtn, 10, weight = 1f)
        c.add(b, 14)
        if (saved) c.add(ghostButton("Delete session") { confirmDelete() }, 10)
    }

    // ---------------------------------------------------------------- playback

    private fun audioSource(): Pair<File, Boolean>? {   // file, isMp3
        val mp3 = VoiceStore.audioFile(this, session.id)
        if (saved && mp3.exists() && mp3.length() > 0) return mp3 to true
        session.pcmPath?.let { val f = File(it); if (f.exists() && f.length() > 0) return f to false }
        if (mp3.exists() && mp3.length() > 0) return mp3 to true
        return null
    }

    private fun buildPlayback(c: LinearLayout) {
        val src = audioSource()
        val pp = panel(col(), 14, 12)
        val head = row()
        head.add(text("Playback", 13f, Brass.TEXT, "medium"), weight = 1f)
        timeText = text("", 12f, Brass.TEXT_SOFT, "mono")
        head.add(timeText!!, w = LinearLayout.LayoutParams.WRAP_CONTENT)
        pp.add(head)
        if (src == null) {
            pp.add(text("No recording kept for this session.", 12f, Brass.TEXT_SOFT), 6)
            c.add(pp, 12); return
        }
        audioSeconds = if (src.second) (try { MediaPlayer().run { setDataSource(src.first.absolutePath); prepare(); val d = duration / 1000f; release(); d } } catch (e: Exception) { 0f })
            else src.first.length() / 2f / IN_RATE
        val r = row()
        val btn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_play); setColorFilter(Brass.ON_BRASS); background = Brass.metal(0f, oval = true)
            contentDescription = "Play recording"; setOnClickListener { togglePlay() }
        }
        playBtn = btn
        r.addView(btn, LinearLayout.LayoutParams(dp(48), dp(48)))
        val tv = TraceView(this, session.trace, session.traceSec, session.target)
        tv.onSeek = { t -> seek(t) }
        trace = tv
        r.add(tv, 12, weight = 1f)
        pp.add(r, 8)
        updateTime(0f)
        c.add(pp, 12)
    }

    private fun updateTime(t: Float) {
        trace?.position = t
        timeText?.text = "${fmtTime(t)} / ${fmtTime(audioSeconds)}"
    }

    private fun togglePlay() { if (playing) pause() else play(trace?.position ?: 0f) }

    private fun play(from: Float) {
        val (f, mp3) = audioSource() ?: return
        stopAudio()
        playing = true
        val my = ++gen
        playBtn?.setImageResource(R.drawable.ic_pause)
        val start = if (from >= audioSeconds - 0.2f) 0f else from
        if (mp3) {
            media = MediaPlayer().apply {
                setDataSource(f.absolutePath); prepare(); seekTo((start * 1000).toInt()); start()
                setOnCompletionListener { if (gen == my) done() }
            }
            tick()
        } else {
            val p = Player(f); pcmPlayer = p
            val total = f.length() / 2
            p.play((start * IN_RATE).toLong().coerceIn(0, total), total,
                onPosition = { pos -> ui.post { if (playing && gen == my) updateTime(pos.toFloat() / IN_RATE) } },
                onDone = { ui.post { if (playing && gen == my) done() } })
        }
    }

    private fun tick() {
        val m = media ?: return
        if (!playing) return
        try { updateTime(m.currentPosition / 1000f) } catch (_: Exception) {}
        ui.postDelayed({ tick() }, 50)
    }

    private fun seek(t: Float) { updateTime(t); if (playing) play(t) }

    private fun pause() {
        playing = false
        stopAudio()
        playBtn?.setImageResource(R.drawable.ic_play)
    }

    private fun done() {
        playing = false
        stopAudio()
        playBtn?.setImageResource(R.drawable.ic_play)
        updateTime(0f)
    }

    private fun stopAudio() {
        pcmPlayer?.let { val p = it; pcmPlayer = null; Thread { p.stop() }.start() }
        media?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        media = null
    }

    override fun onPause() { super.onPause(); if (playing) pause() }

    private fun tile(parent: LinearLayout, label: String, value: String, sub: String) {
        val t = col().apply { background = Brass.panel(14 * d); setPadding(dp(14), dp(12), dp(14), dp(12)) }
        t.add(text(label, 12f, Brass.TEXT_SOFT))
        t.add(text(value, 28f, Brass.TEXT, "light"), 2)
        t.add(text(sub, 12f, Brass.BRASS_TEXT), 2)
        parent.add(t, if (parent.childCount == 0) 0 else 8, weight = 1f)
    }

    private fun renderFeel() {
        feelRow.removeAllViews()
        for (f in listOf("Easy", "OK", "Strained")) {
            val ch = chip(f, session.feeling == f) { session.feeling = if (session.feeling == f) "" else f; renderFeel() }
            feelRow.addView(ch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)).apply { leftMargin = dp(4) })
        }
    }

    private fun save() {
        session.notes = notes.text.toString().trim()
        val before = Celebration.snapshot(Scoring(this))
        VoiceStore.save(this, session)
        if (!saved) VoiceStore.pending = null
        saved = true
        val pcm = session.pcmPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }
        val mp3 = VoiceStore.audioFile(this, session.id)
        if (pcm != null && !mp3.exists()) {
            saveBtn.text = "Saving recording…"
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        PartEncoder(pcm).encode(Part(0, 0, pcm.length() / 2), Quality.VOICE, false, mp3)
                    }
                } catch (e: Exception) { mp3.delete() }
                saveBtn.text = "Saved ✓"
                Celebration.check(this@ReportActivity, before)
            }
        } else {
            saveBtn.text = "Saved ✓"
            Celebration.check(this, before)
        }
        Toast.makeText(this, "Session saved", Toast.LENGTH_SHORT).show()
    }

    private fun exportPdf() {
        session.notes = notes.text.toString().trim()
        lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) { PdfReport.write(this@ReportActivity, session) }
                PdfReport.share(this@ReportActivity, uri)
            } catch (e: Exception) {
                Toast.makeText(this@ReportActivity, "Could not make the PDF: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setMessage("Delete this session? It can't be undone.")
            .setPositiveButton("Delete") { _, _ -> VoiceStore.delete(this, session.id); finish() }
            .setNegativeButton("Cancel", null).show()
    }
}
