package app.mp3splitter.voice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * A personal training plan imported from a file (e.g. home exercises from a speech therapist).
 * Kept only on the device, in filesDir/voice/plan.json — never part of the app itself.
 * Same file format as the web app ("type": "recorder-plan").
 */
class Plan(
    val name: String,
    val source: String,
    val days: Int,
    val intro: String,
    val safety: String,
    val notePrompt: String,
    val fields: List<Field>,
    val items: List<Item>,
    val values: MutableMap<String, String>,
    var startedAt: Long,
) {
    class Field(val key: String, val label: String, val hint: String)
    class Item(val id: String, val name: String, val kind: Kind, val minutes: Int, val optional: Boolean,
               val purpose: String, val steps: List<String>, val caution: String)

    /** Plan exercises in the app's exercise shape (ids start with "plan_"). */
    val exercises: List<Exercise> by lazy {
        items.map { Exercise("plan_" + it.id, Dir.BOTH, Category.PLAN, it.name, it.purpose, it.minutes, it.kind,
            if (it.kind == Kind.TIMER) "timer" else "live pitch", "", steps = it.steps, caution = it.caution, optional = it.optional) }
    }
    val routine get() = exercises.filter { !it.optional }

    /** 1-based day of the plan; done = past the last day. */
    fun day(now: Long = System.currentTimeMillis()): Pair<Int, Boolean> {
        val d = ((startOfDay(now) - startOfDay(startedAt) + 43_200_000) / 86_400_000).toInt() + 1
        return d.coerceAtMost(days) to (d > days)
    }

    /** Replaces {mword}-style placeholders with the user's own words. */
    fun fill(text: String): String = Regex("\\{(\\w+)\\}").replace(text) { m ->
        val k = m.groupValues[1]
        values[k]?.trim()?.takeIf { it.isNotEmpty() }
            ?: "(${fields.firstOrNull { it.key == k }?.label?.lowercase() ?: k} — add it on the Exercises screen)"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("type", "recorder-plan").put("version", 1).put("name", name).put("source", source).put("days", days)
        .put("intro", intro).put("safety", safety).put("notePrompt", notePrompt).put("startedAt", startedAt)
        .put("values", JSONObject(values as Map<*, *>))
        .put("fields", JSONArray().apply { fields.forEach { put(JSONObject().put("key", it.key).put("label", it.label).put("hint", it.hint)) } })
        .put("exercises", JSONArray().apply {
            items.forEach { e ->
                put(JSONObject().put("id", e.id).put("name", e.name).put("kind", e.kind.name).put("minutes", e.minutes).put("optional", e.optional)
                    .put("purpose", e.purpose).put("caution", e.caution).put("steps", JSONArray(e.steps)))
            }
        })

    companion object {
        private val KINDS = setOf("TIMER", "FREE", "TARGET", "HOLD", "RESONANCE", "WEIGHT", "MELODY", "READ")

        fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
            timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        /** Checks a plan file and returns a clean copy; throws IllegalArgumentException if it isn't one. */
        fun parse(text: String, keepStart: Boolean = false): Plan {
            val o = try { JSONObject(text) } catch (e: Exception) { throw IllegalArgumentException("This is not a Recorder plan file") }
            val ex = o.optJSONArray("exercises")
            require(o.optString("type") == "recorder-plan" && ex != null && ex.length() > 0) { "This is not a Recorder plan file" }
            fun s(obj: JSONObject, k: String, max: Int = 600) = obj.optString(k, "").take(max)
            fun clean(id: String) = id.replace(Regex("\\W"), "").take(30)
            val fields = (0 until minOf(6, o.optJSONArray("fields")?.length() ?: 0)).mapNotNull { i ->
                val f = o.getJSONArray("fields").getJSONObject(i)
                clean(s(f, "key")).takeIf { it.isNotEmpty() }?.let { Field(it, s(f, "label", 60), s(f, "hint", 60)) }
            }
            val items = (0 until minOf(20, ex.length())).map { i ->
                val e = ex.getJSONObject(i)
                val steps = e.optJSONArray("steps")?.let { a -> (0 until minOf(8, a.length())).map { a.optString(it).take(600) } } ?: emptyList()
                Item(clean(s(e, "id")).ifEmpty { "e$i" }, s(e, "name", 80).ifEmpty { "Exercise ${i + 1}" },
                    Kind.valueOf(s(e, "kind").takeIf { it in KINDS } ?: "FREE"), e.optInt("minutes", 1).coerceIn(1, 20),
                    e.optBoolean("optional", false), s(e, "purpose"), steps, s(e, "caution"))
            }
            val values = mutableMapOf<String, String>()
            o.optJSONObject("values")?.let { v -> v.keys().forEach { k -> values[k] = v.optString(k).take(200) } }
            return Plan(s(o, "name", 80).ifEmpty { "My plan" }, s(o, "source", 120), o.optInt("days", 7).coerceIn(1, 60),
                s(o, "intro"), s(o, "safety"), s(o, "notePrompt", 300), fields, items, values,
                if (keepStart) o.optLong("startedAt", System.currentTimeMillis()) else System.currentTimeMillis())
        }
    }
}

/** The current plan, cached in memory; loaded once per process. */
object PlanStore {
    @Volatile private var loaded = false
    var current: Plan? = null
        private set

    private fun file(ctx: Context) = File(ctx.filesDir, "voice/plan.json")

    fun load(ctx: Context): Plan? {
        if (!loaded) {
            current = try { file(ctx).takeIf { it.exists() }?.readText()?.let { Plan.parse(it, keepStart = true) } } catch (e: Exception) { null }
            loaded = true
        }
        return current
    }

    fun save(ctx: Context, plan: Plan?) {
        val f = file(ctx)
        if (plan == null) f.delete() else { f.parentFile?.mkdirs(); f.writeText(plan.toJson().toString()) }
        current = plan; loaded = true
    }
}
