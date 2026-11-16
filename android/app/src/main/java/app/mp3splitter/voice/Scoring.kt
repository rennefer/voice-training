package app.mp3splitter.voice

import android.content.Context
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.min

data class DayScore(
    val total: Int,
    val minutes: Float, val minutesPts: Float,
    val routineDone: Int, val routineSize: Int, val routinePts: Float,
    val onTarget: Float, val targetPts: Float,
    val improvementHz: Float, val improvePts: Float,
)

data class Achievement(val id: String, val name: String, val desc: String, val icon: String, val unlockedAt: Long, val progress: String)

data class Step(val title: String, val goal: String, val goals: List<Pair<String, Boolean>>) {
    val done get() = goals.all { it.second }
    val count get() = goals.count { it.second }
}

/** Daily score, streaks, training steps and achievements, all computed from saved sessions. */
class Scoring(private val ctx: Context) {
    private val settings = VoiceSettings(ctx)
    val sessions = VoiceStore.all(ctx)
    private val byDay: Map<Int, List<Session>> = sessions.groupBy { VoiceStore.dayKey(it.id) }

    fun daySessions(ms: Long) = byDay[VoiceStore.dayKey(ms)] ?: emptyList()

    fun dayScore(ms: Long): DayScore? {
        val day = daySessions(ms)
        if (day.isEmpty()) return null
        val minutes = day.sumOf { it.seconds.toDouble() }.toFloat() / 60f
        val minutesPts = min(1f, minutes / 15f) * 25f
        val routine = settings.routineList()
        val doneIds = day.flatMap { s -> s.exercises.map { it.id } }.toSet()
        val routineDone = routine.count { it.id in doneIds }
        val routinePts = if (routine.isEmpty()) minutesPts else routineDone.toFloat() / routine.size * 25f
        val voiced = day.mapNotNull { s -> s.stats?.let { it to it.voicedSeconds } }
        val totalVoiced = voiced.sumOf { it.second.toDouble() }.toFloat()
        val onTarget = if (totalVoiced > 0) voiced.sumOf { (it.first.pctOnTarget * it.second).toDouble() }.toFloat() / totalVoiced else 0f
        val targetPts = onTarget / 100f * 25f
        // improvement: today's median vs the average of the previous 7 training days, measured toward the target
        val target = settings.target
        val todayMed = weightedMedian(day)
        val prevDays = byDay.keys.filter { it < VoiceStore.dayKey(ms) }.sortedDescending().take(7)
        val prevMeds = prevDays.mapNotNull { k -> weightedMedian(byDay[k]!!).takeIf { it > 0 } }
        var improvement = 0f; var improvePts = 12.5f
        if (todayMed > 0 && prevMeds.isNotEmpty()) {
            val prev = prevMeds.average().toFloat()
            improvement = todayMed - prev
            val gain = abs(prev - target) - abs(todayMed - target)
            improvePts = 12.5f + (gain / 10f).coerceIn(-1f, 1f) * 12.5f
        }
        val total = (minutesPts + routinePts + targetPts + improvePts).toInt().coerceIn(0, 100)
        return DayScore(total, minutes, minutesPts, routineDone, routine.size, routinePts, onTarget, targetPts, improvement, improvePts)
    }

    private fun weightedMedian(day: List<Session>): Float {
        val w = day.mapNotNull { s -> s.stats?.takeIf { it.voicedSeconds > 2 } }
        if (w.isEmpty()) return 0f
        return w.sumOf { (it.medianF0 * it.voicedSeconds).toDouble() }.toFloat() / w.sumOf { it.voicedSeconds.toDouble() }.toFloat()
    }

    /** Consecutive training days ending today (or yesterday if not trained yet today). */
    fun streak(now: Long = System.currentTimeMillis()): Int {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        if (!byDay.containsKey(VoiceStore.dayKey(c.timeInMillis))) c.add(Calendar.DAY_OF_YEAR, -1)
        var n = 0
        while (byDay.containsKey(VoiceStore.dayKey(c.timeInMillis))) { n++; c.add(Calendar.DAY_OF_YEAR, -1) }
        return n
    }

    fun longestStreak(): Int {
        if (sessions.isEmpty()) return 0
        val days = sessions.map { dayStart(it.id) }.distinct().sorted()
        var best = 1; var cur = 1
        for (i in 1 until days.size) {
            cur = if (days[i] - days[i - 1] in 82_800_000L..90_000_000L) cur + 1 else 1
            best = maxOf(best, cur)
        }
        return best
    }

    /** Monday-start week containing ms: list of 7 day-start times. */
    fun weekDays(ms: Long = System.currentTimeMillis()): List<Long> {
        val c = Calendar.getInstance().apply { timeInMillis = dayStart(ms); firstDayOfWeek = Calendar.MONDAY }
        val dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7  // Monday = 0
        c.add(Calendar.DAY_OF_YEAR, -dow)
        return (0 until 7).map { val t = c.timeInMillis; c.add(Calendar.DAY_OF_YEAR, 1); t + 12 * 3600_000L }
    }

    fun daysTrainedInWeek(ms: Long = System.currentTimeMillis()) = weekDays(ms).count { byDay.containsKey(VoiceStore.dayKey(it)) }

    private fun dayStart(ms: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        return c.timeInMillis
    }

    // ---------------------------------------------------------------- facts used by steps and achievements

    private val stats get() = sessions.mapNotNull { it.stats }
    private val trainingDays get() = byDay.size
    private val totalMinutes get() = sessions.sumOf { it.seconds.toDouble() } / 60.0
    private fun exerciseDone(id: String) = sessions.any { s -> s.exercises.any { it.id == id } }
    private fun bestDay(f: (List<Session>) -> Double) = byDay.values.maxOfOrNull(f) ?: 0.0
    private fun onTargetMinutes(day: List<Session>) = day.sumOf { s -> s.stats?.let { it.pctOnTarget / 100.0 * it.voicedSeconds / 60.0 } ?: 0.0 }
    private fun readScore() = sessions.filter { s -> s.exercises.any { it.id == "read" } }.mapNotNull { it.exercises.firstOrNull { e -> e.id == "read" }?.score }.maxOrNull() ?: 0

    /** Current direction, and the sessions trained in it (old sessions count as MtF). */
    private val goal get() = settings.goal
    private val mine get() = sessions.filter { it.goal == goal }
    private val myStats get() = mine.mapNotNull { it.stats }
    private fun doneMine(id: String) = mine.any { s -> s.exercises.any { it.id == id } }

    fun steps(): List<Step> {
        val ftm = goal == Goal.FTM
        val st = myStats
        val readBest = mine.flatMap { s -> s.exercises.filter { it.id == "read" }.map { it.score } }.maxOrNull() ?: 0
        val foundations = Step("Foundations",
            if (ftm) "Breathing, open-throat warm-up, find your baseline, gentle downward glides" else "Breathing, warm-up, find your baseline, gentle pitch glides", listOf(
            "First session" to sessions.isNotEmpty(),
            "Find your baseline" to doneMine("baseline"),
            (if (ftm) "Downward glides" else "Pitch glides") to doneMine(if (ftm) "downglides" else "glides"),
            "Train on 3 days" to (trainingDays >= 3),
            "30 min in total" to (totalMinutes >= 30),
        ))
        val building = if (ftm) Step("Building", "Settle pitch toward your target, start chest resonance and a fuller voice", listOf(
            "A session with median 155 Hz or lower" to st.any { it.medianF0 > 0 && it.medianF0 <= 155 },
            "10 min on target in one day" to (bestDay { onTargetMinutes(it) } >= 10),
            "Resonance 50 or darker in a session" to st.any { it.resonance > 0 && it.resonance <= 50 },
            "Weight 50 or fuller in a session" to st.any { it.weight <= 50 && it.voicedSeconds > 10 },
            "5-day streak" to (longestStreak() >= 5),
        )) else Step("Building", "Raise pitch toward your target, start resonance and light weight", listOf(
            "A session with median 165 Hz+" to st.any { it.medianF0 >= 165 },
            "10 min on target in one day" to (bestDay { onTargetMinutes(it) } >= 10),
            "Resonance 50+ in a session" to st.any { it.resonance >= 50 },
            "Light weight 50+ in a session" to st.any { it.weight >= 50 },
            "5-day streak" to (longestStreak() >= 5),
        ))
        val speech = if (ftm) Step("Speech", "Steady, falling melody and reading on target across a whole session", listOf(
            "Read aloud 60%+ in male range" to (readBest >= 60),
            "Steady melody (2 semitones or less) in a session" to st.any { it.melody > 0 && it.melody <= 2 && it.voicedSeconds > 20 },
            "3 sessions with 70%+ in male range" to (st.count { it.pctMale >= 70 } >= 3),
            "Train on 20 days" to (trainingDays >= 20),
        )) else Step("Speech", "Melody, phrases and reading on target, steady across a whole session", listOf(
            "Read aloud 60%+ in female range" to (readBest >= 60),
            "Melody 2.5 semitones+ in a session" to st.any { it.melody >= 2.5 && it.voicedSeconds > 20 },
            "3 sessions with 70%+ in female range" to (st.count { it.pctFemale >= 70 } >= 3),
            "Train on 20 days" to (trainingDays >= 20),
        ))
        val everyday = Step("Everyday voice", "Free talk and mimicry, keep the voice through long conversations", listOf(
            "Free talk 70%+ in ${goal.goalName} range" to mine.any { s -> s.exercises.any { it.id == "freetalk" && it.score >= 70 } },
            "Speak along with a clip" to doneMine("mimic"),
            "14-day streak" to (longestStreak() >= 14),
            "10 hours in total" to (totalMinutes >= 600),
        ))
        return listOf(foundations, building, speech, everyday)
    }

    fun currentStepIndex(): Int = steps().indexOfFirst { !it.done }.let { if (it < 0) 3 else it }

    fun achievements(): List<Achievement> {
        val now = System.currentTimeMillis()
        val st = stats
        val routine = settings.routineList().map { it.id }.toSet()
        val fullRoutineDay = routine.isNotEmpty() && byDay.values.any { day -> routine.all { id -> day.any { s -> s.exercises.any { it.id == id } } } }
        val weekGoal = settings.weeklyGoal
        val bestWeek = sessions.map { daysTrainedInWeek(it.id) }.maxOrNull() ?: 0
        val cats = sessions.flatMap { s -> s.exercises.mapNotNull { Exercises.byId(it.id)?.cat } }.toSet()
        val streak = streak(); val longest = longestStreak()
        fun rem(n: Int, unit: String) = "${n.coerceAtLeast(0)} $unit left"
        val my = myStats
        val bestMed = my.maxOfOrNull { it.medianF0 } ?: 0f
        val lowMed = my.map { it.medianF0 }.filter { it > 0 }.minOrNull() ?: 0f
        fun myMax(f: (SessionStats) -> Float) = my.maxOfOrNull(f) ?: 0f
        fun myMinPos(f: (SessionStats) -> Float) = my.map(f).filter { it > 0 }.minOrNull() ?: 0f
        val pitchAch = if (goal == Goal.FTM) listOf(
            Triple("m_hz155", "Into the male range", "A session with median pitch 155 Hz or lower") to ((lowMed > 0 && lowMed <= 155) to (if (lowMed > 0) "best %.0f Hz".format(lowMed) else "Train once")),
            Triple("m_hz140", "140 Hz", "A session with median pitch 140 Hz or lower") to ((lowMed > 0 && lowMed <= 140) to (if (lowMed > 0) "%.0f Hz to go".format((lowMed - 140).coerceAtLeast(0f)) else "–")),
            Triple("m_hz125", "125 Hz", "A session with median pitch 125 Hz or lower") to ((lowMed > 0 && lowMed <= 125) to (if (lowMed > 0) "%.0f Hz to go".format((lowMed - 125).coerceAtLeast(0f)) else "–")),
            Triple("m_male70", "Mostly male range", "70% of a session inside 85–155 Hz") to (my.any { it.pctMale >= 70 } to "best %.0f%%".format(myMax { it.pctMale })),
            Triple("m_res50", "Darker", "Resonance 50 or darker in a session") to (my.any { it.resonance > 0 && it.resonance <= 50 } to "best %.0f".format(myMinPos { it.resonance })),
            Triple("m_res30", "Deep resonance", "Resonance 30 or darker in a session") to (my.any { it.resonance > 0 && it.resonance <= 30 } to "best %.0f".format(myMinPos { it.resonance })),
            Triple("m_full40", "Full voice", "Vocal weight 40 or fuller in a session") to (my.any { it.weight <= 40 && it.voicedSeconds > 10 } to "best %.0f".format(my.minOfOrNull { it.weight } ?: 0f)),
            Triple("m_steady", "Steady melody", "Melody of 2 semitones or less over 20 s of voice") to (my.any { it.melody > 0 && it.melody <= 2 && it.voicedSeconds > 20 } to "best %.1f st".format(myMinPos { it.melody })),
        ) else listOf(
            Triple("hz165", "Into the female range", "A session with median pitch 165 Hz or higher") to ((bestMed >= 165) to "best %.0f Hz".format(bestMed)),
            Triple("hz180", "180 Hz", "A session with median pitch 180 Hz or higher") to ((bestMed >= 180) to "%.0f Hz to go".format((180 - bestMed).coerceAtLeast(0f))),
            Triple("hz200", "200 Hz", "A session with median pitch 200 Hz or higher") to ((bestMed >= 200) to "%.0f Hz to go".format((200 - bestMed).coerceAtLeast(0f))),
            Triple("female70", "Mostly female range", "70% of a session inside 165–255 Hz") to (my.any { it.pctFemale >= 70 } to "best %.0f%%".format(myMax { it.pctFemale })),
            Triple("res50", "Brighter", "Resonance 50 or more in a session") to (my.any { it.resonance >= 50 } to "best %.0f".format(myMax { it.resonance })),
            Triple("res70", "Bright resonance", "Resonance 70 or more in a session") to (my.any { it.resonance >= 70 } to "best %.0f".format(myMax { it.resonance })),
            Triple("light60", "Light voice", "Vocal weight 60 or lighter in a session") to (my.any { it.weight >= 60 } to "best %.0f".format(myMax { it.weight })),
            Triple("melody", "Lively melody", "Melody of 3 semitones or more in a session") to (my.any { it.melody >= 3 && it.voicedSeconds > 20 } to "best %.1f st".format(myMax { it.melody })),
        )
        val list = listOf(
            Triple("first", "First session", "Saved your first training session") to (sessions.isNotEmpty() to "Train once"),
            Triple("routine", "Full routine", "Finished every chosen exercise in one day") to (fullRoutineDay to "Do the whole routine"),
            Triple("streak3", "3-day streak", "Trained 3 days in a row") to ((longest >= 3) to rem(3 - streak, "days")),
            Triple("streak7", "7-day streak", "Trained every day for a week") to ((longest >= 7) to rem(7 - streak, "days")),
            Triple("streak14", "14-day streak", "Two weeks without a break") to ((longest >= 14) to rem(14 - streak, "days")),
            Triple("streak30", "30-day streak", "Trained every day for a month") to ((longest >= 30) to rem(30 - streak, "days")),
            Triple("week", "Week goal", "Trained $weekGoal days in one week") to ((bestWeek >= weekGoal) to rem(weekGoal - daysTrainedInWeek(now), "days this week")),
            Triple("ontarget10", "10 minutes on target", "10 min inside your target zone in one day") to ((bestDay { onTargetMinutes(it) } >= 10) to "%.0f of 10 min today".format(onTargetMinutes(daySessions(now)))),
            Triple("practice30", "Half an hour", "Practised 30 minutes in one day") to ((bestDay { d -> d.sumOf { it.seconds.toDouble() } / 60 } >= 30) to "30 min in a day"),
            *pitchAch.toTypedArray(),
            Triple("sessions10", "10 sessions", "Saved 10 sessions") to ((sessions.size >= 10) to rem(10 - sessions.size, "sessions")),
            Triple("sessions50", "50 sessions", "Saved 50 sessions") to ((sessions.size >= 50) to rem(50 - sessions.size, "sessions")),
            Triple("sessions100", "100 sessions", "Saved 100 sessions") to ((sessions.size >= 100) to rem(100 - sessions.size, "sessions")),
            Triple("allcats", "All-rounder", "Tried every kind of exercise") to ((cats.size >= Category.values().size) to "${cats.size} of ${Category.values().size} kinds"),
            Triple("notes5", "Journal", "Wrote notes on 5 sessions") to ((sessions.count { it.notes.isNotBlank() } >= 5) to "${sessions.count { it.notes.isNotBlank() }} of 5"),
            Triple("hours5", "5 hours", "5 hours of training in total") to ((totalMinutes >= 300) to "%.0f of 300 min".format(totalMinutes)),
            Triple("step4", "Everyday voice", "Reached the last training step") to ((currentStepIndex() >= 3) to "step ${currentStepIndex() + 1} of 4"),
        )
        val icons = mapOf("streak" to "flame", "hz" to "target", "m_hz" to "target", "ontarget" to "target", "week" to "calendar")
        return list.map { (t, v) ->
            val (id, name, desc) = t
            if (v.first) settings.unlock(id, now)
            val icon = icons.entries.firstOrNull { id.startsWith(it.key) }?.value ?: "star"
            Achievement(id, name, desc, icon, if (v.first) settings.unlockedAt(id) else 0L, v.second)
        }
    }
}
