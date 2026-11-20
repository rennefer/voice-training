package app.mp3splitter.voice

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A4 PDF of one session, saved in Download/Recorder. */
object PdfReport {
    private const val W = 595; private const val H = 842
    private const val INK = 0xFF16202B.toInt()
    private const val SOFT = 0xFF4A5866.toInt()
    private const val BRASS = 0xFF7A5C2E.toInt()
    private const val RULE = 0xFFB8995F.toInt()
    private const val LINE = 0xFFE3E6EA.toInt()
    private const val FEM = 0xFFF3EBD9.toInt()
    private const val MALE = 0xFFE4ECF4.toInt()

    fun write(ctx: Context, s: Session): Uri {
        val doc = PdfDocument()
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        var y = draw(ctx, page.canvas, s)
        // one small graph per exercise, two per row, continuing on new pages
        if (s.exercises.isNotEmpty() && s.stats != null) {
            val x0 = 48f; val x1 = W - 48f
            val gw = (x1 - x0 - 18f) / 2f; val gh = 92f; val rowH = gh + 40f
            y += 22f
            fun head(c: Canvas) { c.drawText("Exercises · ${s.exercises.size}", x0, y, paint(11f, INK, true)); y += 10f }
            fun newPage() { doc.finishPage(page); pageNo++; page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create()); y = 52f; footer(page.canvas); head(page.canvas) }
            if (y + 14f + rowH > H - 60f) { doc.finishPage(page); pageNo++; page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create()); y = 52f; footer(page.canvas) }
            head(page.canvas)
            s.exercises.forEachIndexed { i, r ->
                val col = i % 2
                if (col == 0 && i > 0) y += rowH
                if (col == 0 && y + rowH > H - 60f) newPage()
                exerciseGraph(page.canvas, RectF(x0 + col * (gw + 18f), y, x0 + col * (gw + 18f) + gw, y + rowH - 8f), r, s)
            }
        }
        doc.finishPage(page)
        val name = "voice_report_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(s.id)) + ".pdf"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Recorder")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val res = ctx.contentResolver
        val uri = res.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("could not create $name")
        try {
            res.openOutputStream(uri)!!.use { doc.writeTo(it) }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            res.update(uri, values, null, null)
        } catch (e: Exception) {
            res.delete(uri, null, null); throw e
        } finally { doc.close() }
        return uri
    }

    fun share(ctx: Context, uri: Uri) {
        val i = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Voice report (saved in Download/Recorder)"))
    }

    private fun paint(size: Float, color: Int = INK, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
    }

    /** Draws page 1 and returns where the content ended. */
    private fun draw(ctx: Context, c: Canvas, s: Session): Float {
        val st = s.stats
        val all = VoiceStore.all(ctx)
        val g = s.goal
        val prevSession = all.lastOrNull { it.id < s.id && it.stats != null && it.goal == g }
        val prev = prevSession?.stats
        val x0 = 48f; val x1 = W - 48f
        var y = 52f

        // header
        c.drawText("RECORDER · VOICE TRAINING · ${g.label.uppercase()}", x0, y, paint(9f, BRASS, true).apply { letterSpacing = 0.15f })
        c.drawText("Session report", x0, y + 26f, paint(22f, INK, true))
        val p9 = paint(10f, SOFT).apply { textAlign = Paint.Align.RIGHT }
        c.drawText(SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date(s.id)), x1, y + 10f, p9)
        c.drawText("${s.label} · ${fmt(s.seconds)}", x1, y + 24f, p9)
        y += 38f
        c.drawRect(x0, y, x1, y + 2f, Paint().apply { color = RULE })
        y += 16f

        if (st == null) {
            c.drawText("Not enough voice was measured for a full analysis.", x0, y + 14f, paint(11f, SOFT))
            footer(c)
            return y + 20f
        }
        // tiles
        val tw = (x1 - x0 - 30f) / 4f
        fun d(v: Float, p: Float?, unit: String) = p?.let { "%+.0f%s".format(v - it, unit) } ?: "first session"
        val tiles = listOf(
            Triple("Median pitch", "${st.medianF0.toInt()} Hz", "${VoiceScores.noteName(st.medianF0)} · ${d(st.medianF0, prev?.medianF0, " Hz")}"),
            Triple("In ${g.goalName} range", "${g.pct(st).toInt()} %", d(g.pct(st), prev?.let { g.pct(it) }, " %")),
            Triple("Resonance", "${st.resonance.toInt()} / 100", d(st.resonance, prev?.resonance, "")),
            Triple("Vocal weight", "${st.weight.toInt()} / 100", (if (st.weight >= 50) "lighter · " else "heavier · ") + d(st.weight, prev?.weight, "")),
        )
        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = LINE; strokeWidth = 1f }
        tiles.forEachIndexed { i, (l, v, sub) ->
            val bx = x0 + i * (tw + 10f)
            c.drawRoundRect(RectF(bx, y, bx + tw, y + 58f), 6f, 6f, box)
            c.drawText(l, bx + 9f, y + 15f, paint(8.5f, SOFT))
            c.drawText(v, bx + 9f, y + 36f, paint(17f, INK))
            c.drawText(sub, bx + 9f, y + 50f, paint(8f, BRASS))
        }
        y += 74f

        // charts
        val cw = (x1 - x0 - 18f) / 2f; val ch = 130f
        c.drawText("Pitch over the session", x0, y, paint(11f, INK, true))
        c.drawText("Median pitch, last sessions", x0 + cw + 18f, y, paint(11f, INK, true))
        y += 8f
        traceChart(c, RectF(x0, y, x0 + cw, y + ch), s.trace, s.target)
        val meds = all.filter { it.id <= s.id && it.stats != null && it.goal == g }.takeLast(14).map { it.stats!!.medianF0 }.let { if (s.id !in all.map { a -> a.id }) (it + st.medianF0).takeLast(14) else it }
        trendChart(c, RectF(x0 + cw + 18f, y, x1, y + ch), meds, s.target)
        y += ch + 22f

        // table
        c.drawText("Measurements", x0, y, paint(11f, INK, true)); y += 6f
        val cols = floatArrayOf(x0, x0 + 190f, x0 + 320f, x0 + 430f)
        fun tr(a: String, b: String, cc: String, dd: String, head: Boolean = false) {
            y += 17f
            val p = paint(9.5f, if (head) SOFT else INK, head)
            c.drawText(a, cols[0], y, paint(9.5f, SOFT, head)); c.drawText(b, cols[1], y, p); c.drawText(cc, cols[2], y, paint(9.5f, SOFT, head)); c.drawText(dd, cols[3], y, paint(9.5f, if (head) SOFT else BRASS, head))
            c.drawRect(x0, y + 5f, x1, y + 5.6f, Paint().apply { color = 0xFFEDF0F2.toInt() })
        }
        fun ch(v: Float, p: Float?, unit: String, f: String = "%+.0f") = p?.let { f.format(v - it) + unit } ?: "–"
        tr("Measure", "This session", "Last session", "Change", true)
        tr("Median pitch", "${st.medianF0.toInt()} Hz (${VoiceScores.noteName(st.medianF0)})", prev?.let { "${it.medianF0.toInt()} Hz (${VoiceScores.noteName(it.medianF0)})" } ?: "–", ch(st.medianF0, prev?.medianF0, " Hz"))
        tr("Pitch range", "${st.lowF0.toInt()}–${st.highF0.toInt()} Hz (${VoiceScores.noteName(st.lowF0)}–${VoiceScores.noteName(st.highF0)})", prev?.let { "${it.lowF0.toInt()}–${it.highF0.toInt()} Hz" } ?: "–", "")
        tr("Time in female / male range", "${st.pctFemale.toInt()} % / ${st.pctMale.toInt()} %", prev?.let { "${it.pctFemale.toInt()} % / ${it.pctMale.toInt()} %" } ?: "–", ch(g.pct(st), prev?.let { g.pct(it) }, " % ${g.goalName}"))
        tr("On target (${s.target.toInt()} ±${s.tolerance.toInt()} Hz)", "${st.pctOnTarget.toInt()} %", prev?.let { "${it.pctOnTarget.toInt()} %" } ?: "–", ch(st.pctOnTarget, prev?.pctOnTarget, " %"))
        tr("Melody (variation)", "%.1f semitones".format(st.melody), prev?.let { "%.1f st".format(it.melody) } ?: "–", ch(st.melody, prev?.melody, " st", "%+.1f"))
        tr("Resonance", "${st.resonance.toInt()} / 100", prev?.let { "${it.resonance.toInt()}" } ?: "–", ch(st.resonance, prev?.resonance, ""))
        tr("Formants F1 / F2", "${st.f1.toInt()} / ${st.f2.toInt()} Hz", prev?.let { "${it.f1.toInt()} / ${it.f2.toInt()} Hz" } ?: "–", ch(st.f2, prev?.f2, " Hz F2"))
        tr("Vocal weight (H1*–H2*)", "${st.weight.toInt()} / 100 (%.1f dB)".format(st.h1h2), prev?.let { "${it.weight.toInt()}" } ?: "–", ch(st.weight, prev?.weight, ""))
        tr("Volume", "≈ ${st.volumeDb.toInt()} dB", prev?.let { "≈ ${it.volumeDb.toInt()} dB" } ?: "–", ch(st.volumeDb, prev?.volumeDb, " dB"))
        tr("Breathiness (HNR)", "%.0f dB".format(st.hnr), prev?.let { "%.0f dB".format(it.hnr) } ?: "–", "")
        y += 22f

        if (s.notes.isNotBlank() || s.feeling.isNotBlank() || s.notePrompt != null) {
            val title = "Notes" + if (s.feeling.isNotBlank()) " · felt ${s.feeling}" else ""
            val tp = paint(10f, INK)
            val q = s.notePrompt?.let { StaticLayout.Builder.obtain(it, 0, it.length, paint(9.5f, BRASS).apply { textSkewX = -0.2f }, (x1 - x0 - 20f).toInt()).setLineSpacing(2f, 1f).build() }
            val qh = q?.let { it.height + 8f } ?: 0f
            val lay = StaticLayout.Builder.obtain(s.notes.ifBlank { "–" }, 0, s.notes.ifBlank { "–" }.length, tp, (x1 - x0 - 20f).toInt()).setLineSpacing(2f, 1f).build()
            val bh = lay.height + qh + 30f
            c.drawRect(x0, y, x0 + 3f, y + bh, Paint().apply { color = RULE })
            c.drawRect(x0 + 3f, y, x1, y + bh, Paint().apply { color = 0xFFFAF8F3.toInt() })
            c.drawText(title, x0 + 12f, y + 16f, paint(11f, INK, true))
            q?.let { c.save(); c.translate(x0 + 12f, y + 22f); it.draw(c); c.restore() }
            c.save(); c.translate(x0 + 12f, y + 22f + qh); lay.draw(c); c.restore()
            y += bh + 12f
        }

        footer(c)
        return y
    }

    private fun footer(c: Canvas) {
        c.drawRect(48f, H - 50f, W - 48f, H - 49.5f, Paint().apply { color = LINE })
        c.drawText("Measured with the phone microphone. Resonance and vocal weight are estimates; compare sessions recorded the same way (same room, same distance).", 48f, H - 36f, paint(7.5f, SOFT))
    }

    /** One exercise: name + result, then its pitch graph with bands, target and guide line. */
    private fun exerciseGraph(c: Canvas, box: RectF, r: ExerciseResult, s: Session) {
        val ex = Exercises.byId(r.id)
        val name = (ex?.name ?: r.id).let { if (it.length > 48) it.take(47) + "…" else it }
        c.drawText(name, box.left, box.top + 12f, paint(8.5f, INK, true))
        val dp = paint(8f, BRASS); c.drawText(r.detail, box.right - dp.measureText(r.detail), box.top + 12f, dp)
        val g = RectF(box.left + 22f, box.top + 18f, box.right, box.bottom - 14f)
        val tr = r.trace ?: FloatArray(0)
        val v = tr.filter { it > 0f }
        if (v.isEmpty()) {
            c.drawRoundRect(g, 4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = LINE; strokeWidth = 1f })
            val t = if (ex?.kind == Kind.TIMER) "Timer · ${fmt(r.seconds)}" else if (r.trace == null) "No graph (older session)" else "No voice measured"
            val tp = paint(8.5f, SOFT); c.drawText(t, g.centerX() - tp.measureText(t) / 2, g.centerY() + 3f, tp)
            return
        }
        val total = maxOf(r.traceSec, tr.size * r.traceSec)
        val guide: ((Float) -> Float?)? = if (ex != null && ex.roundSeconds > 0f) { t -> Exercises.guide(ex, t, s.target) } else null
        var lo = minOf(v.min(), s.target) - 20f; var hi = maxOf(v.max(), s.target) + 20f
        if (guide != null) { var t = 0f; while (t <= total) { guide(t)?.let { lo = minOf(lo, it - 15f); hi = maxOf(hi, it + 15f) }; t += total / 40f } }
        lo = lo.coerceAtLeast(60f)
        bands(c, g, lo, hi, s.target)
        fun px(t: Float) = g.left + t / total * g.width()
        fun py(f: Float) = g.bottom - (f - lo) / (hi - lo) * g.height()
        if (guide != null) {
            val gp = Path(); var started = false; var t = 0f
            while (t <= total) { val gz = guide(t); if (gz == null) started = false else if (!started) { gp.moveTo(px(t), py(gz)); started = true } else gp.lineTo(px(t), py(gz)); t += minOf(0.1f, total / 150f) }
            c.drawPath(gp, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = RULE; strokeWidth = 1.6f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(1.5f, 2.5f), 0f) })
        }
        val path = Path(); var pen = false
        tr.forEachIndexed { i, f -> if (f <= 0f) pen = false else { val x = px((i + 0.5f) * r.traceSec); val y = py(f); if (!pen) { path.moveTo(x, y); pen = true } else path.lineTo(x, y) } }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = INK; strokeWidth = 1f })
        val lp = paint(7f, SOFT)
        c.drawText("0:00", g.left, g.bottom + 9f, lp)
        val te = fmt(total); c.drawText(te, g.right - lp.measureText(te), g.bottom + 9f, lp)
    }

    private fun block(c: Canvas, s: String, x: Float, y: Float, w: Float, tp: TextPaint): Float {
        val lay = StaticLayout.Builder.obtain(s, 0, s.length, tp, w.toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(3f, 1f).build()
        c.save(); c.translate(x, y + 4f); lay.draw(c); c.restore()
        return y + 4f + lay.height
    }

    private fun bands(c: Canvas, r: RectF, lo: Float, hi: Float, target: Float) {
        fun y(v: Float) = (r.bottom - (v - lo) / (hi - lo) * r.height()).coerceIn(r.top, r.bottom)
        val p = Paint()
        p.color = FEM; c.drawRect(r.left, y(Ranges.FEMALE_HI), r.right, y(Ranges.FEMALE_LO), p)
        p.color = MALE; c.drawRect(r.left, y(Ranges.MALE_HI), r.right, y(Ranges.MALE_LO), p)
        val lp = paint(7.5f, SOFT)
        var v = ((lo / 20).toInt() + 1) * 20f
        while (v < hi) { c.drawText(v.toInt().toString(), r.left - 2f - lp.measureText(v.toInt().toString()), y(v) + 3f, lp); c.drawRect(r.left, y(v), r.right, y(v) + 0.4f, Paint().apply { color = 0x22000000 }); v += 20f }
        if (target in lo..hi) c.drawRect(r.left, y(target) - 0.6f, r.right, y(target) + 0.6f, Paint().apply { color = RULE })
        c.drawText("female", r.right - 30f, y(Ranges.FEMALE_HI) + 9f, paint(7.5f, BRASS))
    }

    private fun traceChart(c: Canvas, r: RectF, trace: FloatArray, target: Float) {
        val voiced = trace.filter { it > 0 }
        val lo = ((voiced.minOrNull() ?: 120f).coerceAtMost(target) - 20f).coerceAtLeast(60f)
        val hi = (voiced.maxOrNull() ?: 260f).coerceAtLeast(target) + 20f
        bands(c, r, lo, hi, target)
        if (trace.isEmpty()) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = BRASS; strokeWidth = 1.3f }
        val path = Path(); var pen = false
        trace.forEachIndexed { i, f ->
            val x = r.left + i * r.width() / (trace.size - 1).coerceAtLeast(1)
            if (f <= 0f) { pen = false; return@forEachIndexed }
            val y = r.bottom - (f - lo) / (hi - lo) * r.height()
            if (!pen) { path.moveTo(x, y); pen = true } else path.lineTo(x, y)
        }
        c.drawPath(path, p)
    }

    private fun trendChart(c: Canvas, r: RectF, meds: List<Float>, target: Float) {
        val lo = ((meds.minOrNull() ?: 150f).coerceAtMost(target) - 15f).coerceAtLeast(60f)
        val hi = (meds.maxOrNull() ?: 220f).coerceAtLeast(target) + 15f
        bands(c, r, lo, hi, target)
        if (meds.isEmpty()) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = INK; strokeWidth = 1.3f }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RULE }
        val path = Path()
        meds.forEachIndexed { i, f ->
            val x = if (meds.size == 1) r.centerX() else r.left + 6f + i * (r.width() - 12f) / (meds.size - 1)
            val y = r.bottom - (f - lo) / (hi - lo) * r.height()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, p)
        meds.forEachIndexed { i, f ->
            val x = if (meds.size == 1) r.centerX() else r.left + 6f + i * (r.width() - 12f) / (meds.size - 1)
            c.drawCircle(x, r.bottom - (f - lo) / (hi - lo) * r.height(), 2.4f, dot)
        }
    }

    private fun fmt(sec: Float): String { val s = sec.toInt(); return "%d:%02d".format(s / 60, s % 60) }
}
