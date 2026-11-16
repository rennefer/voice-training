package app.mp3splitter.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import app.mp3splitter.Brass
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** "Pling" sound and pop-up for newly unlocked achievements and training steps. */
object Celebration {

    /** A soft two-note bell (E6 then B6), made on the fly — no sound file needed. */
    fun pling() {
        Thread {
            try {
                val rate = 44100
                val n = (rate * 0.9).toInt()
                val pcm = ShortArray(n)
                val notes = listOf(0.0 to 1318.5, 0.12 to 1975.5)
                for (i in 0 until n) {
                    val t = i.toDouble() / rate
                    var v = 0.0
                    for ((start, f) in notes) {
                        if (t < start) continue
                        val tt = t - start
                        val env = (1 - exp(-tt * 400)) * exp(-tt * 5.5)
                        v += env * (sin(2 * PI * f * tt) + 0.35 * sin(2 * PI * 2 * f * tt) * exp(-tt * 8) + 0.15 * sin(2 * PI * 3 * f * tt) * exp(-tt * 12))
                    }
                    pcm[i] = (v * 0.28 * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(n * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, n)
                track.play()
                Thread.sleep(1000)
                track.release()
            } catch (_: Exception) {}
        }.start()
    }

    /** Snapshot of what is unlocked now (also records anything that became true). */
    fun snapshot(sc: Scoring): Pair<Set<String>, Int> =
        sc.achievements().filter { it.unlockedAt > 0 }.map { it.id }.toSet() to sc.currentStepIndex()

    /** Compare with [before]; if anything new, play the pling and show the pop-up. */
    fun check(activity: BrassActivity, before: Pair<Set<String>, Int>) {
        val sc = Scoring(activity)
        val all = sc.achievements()
        val fresh = all.filter { it.unlockedAt > 0 && it.id !in before.first }
        val step = sc.currentStepIndex()
        val steps = sc.steps()
        val newStep = step > before.second
        if (fresh.isEmpty() && !newStep) return
        pling()
        show(activity, fresh, if (newStep) steps[step.coerceAtMost(steps.size - 1)] else null, step)
    }

    private fun show(a: BrassActivity, fresh: List<Achievement>, step: Step?, stepIndex: Int) {
        val box = a.col().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(a.dp(24), a.dp(24), a.dp(24), a.dp(8)) }
        val medal = TextView(a).apply {
            text = "★"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(Brass.ON_BRASS)
            background = Brass.metal(0f, oval = true)
        }
        box.addView(medal, LinearLayout.LayoutParams(a.dp(72), a.dp(72)))
        val title = when {
            fresh.size > 1 -> "${fresh.size} achievements unlocked!"
            fresh.size == 1 -> "Achievement unlocked!"
            else -> "New training step!"
        }
        box.addView(a.text(title, 20f, Brass.TEXT, "medium").apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(14) })
        for (ach in fresh) {
            val r = a.col().apply { background = Brass.panel(14 * a.d); setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10)) }
            r.addView(a.text(ach.name, 15f, 0xFFEFE1BB.toInt(), "medium"))
            r.addView(a.text(ach.desc, 12f, Brass.TEXT_SOFT))
            box.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(8) })
        }
        if (step != null) {
            val r = a.col().apply { background = Brass.metal(14 * a.d); setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10)) }
            r.addView(a.caps("Step ${stepIndex + 1} of 4 reached", Brass.ON_BRASS))
            r.addView(a.text(step.title, 16f, Brass.ON_BRASS, "medium"))
            r.addView(a.text(step.goal, 12f, Brass.ON_BRASS))
            box.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(10) })
        }
        MaterialAlertDialogBuilder(a)
            .setView(box)
            .setPositiveButton("Nice!", null)
            .show()
    }
}
