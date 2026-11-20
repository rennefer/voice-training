package app.mp3splitter.voice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/** User settings for voice training. */
class VoiceSettings(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
    /** Imported personal plan (e.g. home exercises from a speech therapist), or null. */
    val plan: Plan? get() = PlanStore.load(ctx)
    var target: Float
        get() = p.getFloat("target", 200f)
        set(v) = p.edit().putFloat("target", v).apply()
    var tolerance: Float
        get() = p.getFloat("tolerance", 10f)
        set(v) = p.edit().putFloat("tolerance", v).apply()
    var weeklyGoal: Int
        get() = p.getInt("weeklyGoal", 5)
        set(v) = p.edit().putInt("weeklyGoal", v).apply()
    /** Training direction: feminine (MtF, default) or masculine (FtM). */
    var goal: Goal
        get() = Goal.of(p.getString("direction", "mtf"))
        set(v) = p.edit().putString("direction", if (v == Goal.FTM) "ftm" else "mtf").apply()
    /** True once the user has picked a direction (asked on the first visit to the Voice tab). */
    var directionChosen: Boolean
        get() = p.getBoolean("directionChosen", false)
        set(v) = p.edit().putBoolean("directionChosen", v).apply()
    /** Switch direction; each direction remembers its own target. */
    fun switchTo(g: Goal) {
        val old = goal
        if (g != old) {
            p.edit().putFloat(if (old == Goal.FTM) "targetFtm" else "targetMtf", target).apply()
            goal = g
            target = p.getFloat(if (g == Goal.FTM) "targetFtm" else "targetMtf", g.defaultTarget)
        }
        directionChosen = true
    }
    private val routineKey get() = if (goal == Goal.FTM) "routineFtm" else "routine"
    /** Chosen exercises for the current direction (each direction keeps its own routine). */
    var routine: Set<String>
        get() = p.getStringSet(routineKey, null) ?: Exercises.defaultRoutine(goal)
        set(v) = p.edit().putStringSet(routineKey, v).apply()
    var baseline: Float
        get() = p.getFloat("baseline", 0f)
        set(v) = p.edit().putFloat("baseline", v).apply()

    /** Today's routine: the imported plan if there is one, otherwise the chosen exercises. */
    fun routineList(): List<Exercise> = plan?.routine ?: Exercises.forGoal(goal).filter { it.id in routine }

    /** First date (ms) each achievement was unlocked. */
    fun unlockedAt(id: String): Long = p.getLong("ach_$id", 0L)
    fun unlock(id: String, at: Long) { if (unlockedAt(id) == 0L) p.edit().putLong("ach_$id", at).apply() }
}

data class ExerciseResult(
    val id: String, val score: Int, val seconds: Float, val detail: String,
    val from: Int = -1, val to: Int = -1,               // frame range in the session (only while building)
    val trace: FloatArray? = null, val traceSec: Float = 0.25f,   // pitch graph of this exercise
) {
    fun toJson() = JSONObject().put("id", id).put("score", score).put("seconds", seconds.toDouble()).put("detail", detail).also { o ->
        trace?.let { t -> o.put("trace", JSONArray().apply { t.forEach { put(it.toDouble()) } }).put("traceSec", traceSec.toDouble()) }
    }
    companion object {
        fun from(o: JSONObject) = ExerciseResult(o.getString("id"), o.getInt("score"), o.getDouble("seconds").toFloat(), o.optString("detail"),
            trace = o.optJSONArray("trace")?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } },
            traceSec = o.optDouble("traceSec", 0.25).toFloat())
    }
}

/** One training session: stats + notes + exercises + a short pitch trace for charts. */
class Session(
    val id: Long,                     // start time, ms
    var seconds: Float,               // total time incl. pauses
    var stats: SessionStats?,
    var exercises: MutableList<ExerciseResult> = mutableListOf(),
    var notes: String = "",
    var feeling: String = "",         // Easy / OK / Strained / ""
    var trace: FloatArray = FloatArray(0),  // f0 per ~0.25 s, 0 = silence
    var target: Float = 200f,
    var tolerance: Float = 10f,
    var routineSize: Int = 0,
    var traceSec: Float = 0.25f,      // seconds per trace point
    var dir: String = "mtf",          // "mtf" or "ftm": direction trained in this session
) {
    /** Name of the imported plan this session belongs to, and the plan's question for the notes. */
    var plan: String? = null
    var notePrompt: String? = null
    val goal get() = Goal.of(dir)
    /** Raw audio of an unsaved session (cache), null once saved. */
    var pcmPath: String? = null
    val label: String get() = when {
        exercises.isEmpty() -> "Live practice"
        plan != null -> "$plan · ${exercises.size} exercise${if (exercises.size == 1) "" else "s"}"
        exercises.size >= routineSize && routineSize > 0 -> "Routine · ${exercises.size} exercises"
        exercises.size == 1 -> Exercises.byId(exercises[0].id)?.name ?: "Exercise"
        else -> "${exercises.size} exercises"
    }

    fun toJson(): JSONObject {
        val o = JSONObject().put("id", id).put("seconds", seconds.toDouble()).put("notes", notes).put("feeling", feeling)
            .put("target", target.toDouble()).put("tolerance", tolerance.toDouble()).put("routineSize", routineSize)
            .put("traceSec", traceSec.toDouble()).put("dir", dir)
        plan?.let { o.put("plan", it) }; notePrompt?.let { o.put("notePrompt", it) }
        o.put("exercises", JSONArray().apply { exercises.forEach { put(it.toJson()) } })
        o.put("trace", JSONArray().apply { trace.forEach { put(Math.round(it * 10) / 10.0) } })
        stats?.let { s ->
            o.put("stats", JSONObject()
                .put("voicedSeconds", s.voicedSeconds.toDouble()).put("medianF0", s.medianF0.toDouble())
                .put("lowF0", s.lowF0.toDouble()).put("highF0", s.highF0.toDouble())
                .put("pctFemale", s.pctFemale.toDouble()).put("pctMale", s.pctMale.toDouble()).put("pctOnTarget", s.pctOnTarget.toDouble())
                .put("melody", s.melody.toDouble()).put("f1", s.f1.toDouble()).put("f2", s.f2.toDouble()).put("f3", s.f3.toDouble())
                .put("resonance", s.resonance.toDouble()).put("h1h2", s.h1h2.toDouble()).put("weight", s.weight.toDouble())
                .put("volumeDb", s.volumeDb.toDouble()).put("hnr", s.hnr.toDouble()).put("breathiness", s.breathiness.toDouble())
                .put("hist", JSONArray().apply { s.hist.forEach { put(it) } }))
        }
        return o
    }

    companion object {
        fun from(o: JSONObject): Session {
            val st = o.optJSONObject("stats")?.let { s ->
                val h = s.getJSONArray("hist")
                SessionStats(
                    s.getDouble("voicedSeconds").toFloat(), s.getDouble("medianF0").toFloat(), s.getDouble("lowF0").toFloat(),
                    s.getDouble("highF0").toFloat(), s.getDouble("pctFemale").toFloat(), s.getDouble("pctMale").toFloat(),
                    s.getDouble("pctOnTarget").toFloat(), s.getDouble("melody").toFloat(), s.getDouble("f1").toFloat(),
                    s.getDouble("f2").toFloat(), s.getDouble("f3").toFloat(), s.getDouble("resonance").toFloat(),
                    s.getDouble("h1h2").toFloat(), s.getDouble("weight").toFloat(), s.getDouble("volumeDb").toFloat(),
                    s.getDouble("hnr").toFloat(), s.getDouble("breathiness").toFloat(),
                    IntArray(h.length()) { h.getInt(it) })
            }
            val ex = o.optJSONArray("exercises") ?: JSONArray()
            val tr = o.optJSONArray("trace") ?: JSONArray()
            return Session(
                o.getLong("id"), o.getDouble("seconds").toFloat(), st,
                MutableList(ex.length()) { ExerciseResult.from(ex.getJSONObject(it)) },
                o.optString("notes"), o.optString("feeling"),
                FloatArray(tr.length()) { tr.getDouble(it).toFloat() },
                o.optDouble("target", 200.0).toFloat(), o.optDouble("tolerance", 10.0).toFloat(), o.optInt("routineSize", 0),
                o.optDouble("traceSec", 0.25).toFloat(), o.optString("dir", "mtf")).also { s ->
                s.plan = o.optString("plan").ifEmpty { null }; s.notePrompt = o.optString("notePrompt").ifEmpty { null }
            }
        }
    }
}

/** Sessions saved as one JSON file each in filesDir/voice. */
object VoiceStore {
    /** The session just finished, waiting on the report screen. */
    var pending: Session? = null

    private fun dir(ctx: Context) = File(ctx.filesDir, "voice").apply { mkdirs() }

    fun save(ctx: Context, s: Session) {
        File(dir(ctx), "${s.id}.json").writeText(s.toJson().toString())
        cache = null
    }

    fun delete(ctx: Context, id: Long) { File(dir(ctx), "$id.json").delete(); audioFile(ctx, id).delete(); cache = null }

    /** Saved recording of a session (MP3). */
    fun audioFile(ctx: Context, id: Long) = File(dir(ctx), "$id.mp3")

    /** Raw audio of the session being recorded now. */
    fun pendingPcm(ctx: Context) = File(ctx.cacheDir, "voice_session.pcm")

    private var cache: List<Session>? = null

    /** All saved sessions, oldest first. */
    fun all(ctx: Context): List<Session> {
        cache?.let { return it }
        val list = dir(ctx).listFiles { f -> f.name.endsWith(".json") }?.mapNotNull {
            try { Session.from(JSONObject(it.readText())) } catch (e: Exception) { null }
        }?.sortedBy { it.id } ?: emptyList()
        cache = list
        return list
    }

    fun get(ctx: Context, id: Long) = all(ctx).firstOrNull { it.id == id }

    fun dayKey(ms: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }
}
