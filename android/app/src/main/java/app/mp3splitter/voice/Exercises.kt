package app.mp3splitter.voice

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

enum class Category(val title: String, val chip: String, val hint: String) {
    WARMUP("WARM-UP & BREATH", "Warm-up", "start here"),
    PITCH("PITCH", "Pitch", "Hz scale + target line"),
    RESONANCE("RESONANCE", "Resonance", "bright vs dark meter"),
    WEIGHT("VOCAL WEIGHT", "Weight", "light vs heavy meter"),
    MELODY("MELODY & SPEECH", "Melody", "pitch variation"),
    MIMICRY("MIMICRY", "Mimicry", "use your own clips"),
    PLAN("MY PLAN", "My plan", ""),   // exercises from an imported plan file; not in the library
}

/** How an exercise is run and scored. */
enum class Kind {
    TIMER,      // no voice analysis needed (breathing); score = completed
    FREE,       // voice with live meters; score = time voiced
    BASELINE,   // free talk; measures your comfortable pitch
    GLIDE,      // follow a siren guide line; score = match %
    LADDER,     // follow steps of 10 Hz; score = match %
    HOLD,       // hold the target zone; score = on target %
    TARGET,     // phrases on target; score = on target %
    RESONANCE,  // big resonance meter; score = median resonance
    WEIGHT,     // big weight meter; score = median lightness
    MELODY,     // vary pitch; score from melody (semitone spread)
    READ,       // read passage; score = in goal range %
    MIMIC,      // speak along a clip; score = on target %
}

/** Training direction: feminine (MtF) or masculine (FtM) voice. BOTH = exercise used in both. */
enum class Dir { BOTH, MTF, FTM }

/** Goal range and wording for each direction. */
enum class Goal(val label: String, val short: String, val goalLo: Float, val goalHi: Float, val goalName: String, val defaultTarget: Float, val goalText: String) {
    MTF("Feminine voice", "Feminine", Ranges.FEMALE_LO, Ranges.FEMALE_HI, "female", 200f, "Goal: brighter resonance and a lighter voice"),
    FTM("Masculine voice", "Masculine", Ranges.MALE_LO, Ranges.MALE_HI, "male", 120f, "Goal: darker resonance and a fuller voice");

    val dir get() = if (this == FTM) Dir.FTM else Dir.MTF
    /** Share of voiced time inside this direction's goal range. */
    fun pct(s: SessionStats) = if (this == FTM) s.pctMale else s.pctFemale
    companion object { fun of(key: String?) = if (key == "ftm") FTM else MTF }
}

data class Exercise(
    val id: String,
    val dir: Dir,
    val cat: Category,
    val name: String,
    val how: String,
    val minutes: Int,
    val kind: Kind,
    val meter: String,
    val def: String = "",          // "m" = in the MtF starting routine, "f" = FtM, "mf" = both
    val roundSeconds: Float = 0f,   // length of one guided round (guide kinds)
    val rounds: Int = 1,
    val steps: List<String> = emptyList(),   // numbered steps (plan exercises)
    val caution: String = "",
    val optional: Boolean = false,
)

object Exercises {
    val all = listOf(
        Exercise("breath", Dir.BOTH, Category.WARMUP, "Belly breathing", "Breathe in low into the belly for 4, out slowly on \"sss\" for 8. Shoulders relaxed.", 1, Kind.TIMER, "breath", "mf"),
        Exercise("trills", Dir.BOTH, Category.WARMUP, "Lip trills & straw glides", "Bubble the lips (or hum through a straw) while sliding gently up and down.", 2, Kind.FREE, "warm-up", "mf"),
        Exercise("hum", Dir.MTF, Category.WARMUP, "Forward hum \"mm-hmm\"", "Hum \"mm-hmm\" like agreeing. Feel the buzz on your nose and cheekbones.", 1, Kind.RESONANCE, "resonance meter", "m"),
        Exercise("yawn", Dir.FTM, Category.WARMUP, "Yawn-sigh", "Start a gentle yawn, feel the throat open, then sigh out on an easy \"ahh\". Never push the larynx down.", 1, Kind.RESONANCE, "resonance meter", "f"),
        Exercise("sighs", Dir.BOTH, Category.WARMUP, "Cool-down sighs", "Soft, easy sighs from high to low to relax the voice after training.", 1, Kind.FREE, "end of session", ""),
        Exercise("baseline", Dir.BOTH, Category.PITCH, "Find your baseline", "Talk freely for 30 seconds in your most comfortable voice. The app measures where you are.", 1, Kind.BASELINE, "sets your start", ""),
        Exercise("glides", Dir.MTF, Category.PITCH, "Pitch glides (sirens)", "Slide on \"ooo\" or \"whoop\" from low to high and back. Follow the dashed brass line.", 2, Kind.GLIDE, "match score", "m", 6f, 5),
        Exercise("downglides", Dir.FTM, Category.PITCH, "Downward glides", "On an open \"ahh\", slide gently from a comfortable note down toward your target and back up. Follow the dashed line — never force the bottom.", 2, Kind.GLIDE, "match score", "f", 6f, 5),
        Exercise("halfway", Dir.MTF, Category.PITCH, "Up and halfway back", "Slide up to your top note, then settle halfway down and say \"hello, how are you\" from there.", 2, Kind.TARGET, "pitch target", ""),
        Exercise("settle", Dir.FTM, Category.PITCH, "Sigh and settle", "Sigh down from a medium note, let it land on a relaxed \"mm-hmm\", and say \"yeah, sounds good\" from there.", 2, Kind.TARGET, "pitch target", ""),
        Exercise("hold", Dir.BOTH, Category.PITCH, "Hold the target", "Sustain \"mmm\", then \"ee\", inside the target zone for 5 seconds. Rest and repeat.", 1, Kind.HOLD, "steadiness", "mf", 5f, 5),
        Exercise("ladder", Dir.BOTH, Category.PITCH, "Step ladder", "Count \"one, two, three…\" — each word 10 Hz higher, then back down. Follow the steps.", 2, Kind.LADDER, "pitch control", "", 9f, 4),
        Exercise("phrases", Dir.BOTH, Category.PITCH, "Phrases on target", "Say short phrases and start each one inside the target zone: \"Good morning.\" \"Thank you.\" \"See you soon.\"", 2, Kind.TARGET, "time on target", "mf"),
        Exercise("dogs", Dir.MTF, Category.RESONANCE, "Big dog, small dog", "Pant a deep \"uhh\" like a big dog, then a bright \"ehh\" like a small dog. End on the small dog.", 2, Kind.RESONANCE, "resonance meter", ""),
        Exercise("whisper", Dir.MTF, Category.RESONANCE, "Whisper siren", "Whisper a smooth slide from \"uhh\" to \"ehh\" and hold the bright end. Then add a little voice.", 2, Kind.RESONANCE, "resonance meter", "m"),
        Exercise("mvowels", Dir.MTF, Category.RESONANCE, "M + vowels", "Glide from a hum into vowels: \"mmay, mmee, mmy, mmow, mmoo\". Keep the buzz forward.", 1, Kind.RESONANCE, "resonance meter", ""),
        Exercise("eewords", Dir.MTF, Category.RESONANCE, "\"EE\" tongue words", "Hold a long \"ee\", then say may, my, moo, mate, might — keep the tongue high and forward.", 1, Kind.RESONANCE, "resonance meter", ""),
        Exercise("smile", Dir.MTF, Category.RESONANCE, "Smile \"haa\"", "Say a yawning \"haa\", then a \"haa\" with a small smile and no yawn. Keep the smile one.", 1, Kind.RESONANCE, "resonance meter", ""),
        Exercise("chesthum", Dir.FTM, Category.RESONANCE, "Chest hum", "Hand on your chest, hum a comfortable low \"mmm\" until you feel the buzz there. Then open into \"mmm-ah\", \"mmm-oh\".", 1, Kind.RESONANCE, "resonance meter", "f"),
        Exercise("bigdog", Dir.FTM, Category.RESONANCE, "Big dog \"uhh\"", "Pant a relaxed, deep \"uhh\" like a big dog, then say \"huh, hum, hug\" keeping that roomy space at the back of the mouth.", 1, Kind.RESONANCE, "resonance meter", ""),
        Exercise("deepo", Dir.FTM, Category.RESONANCE, "Deep \"O\" breathing", "Breathe in through a round \"O\" mouth, then breathe out on a low hum with the jaw open and tongue resting low.", 2, Kind.RESONANCE, "resonance meter", ""),
        Exercise("lowtongue", Dir.FTM, Category.RESONANCE, "Open \"aw\" words", "Tongue flat and relaxed, jaw loose: law, saw, call, ball, hot, thought. Keep the sound round and dark.", 1, Kind.RESONANCE, "resonance meter", ""),
        Exercise("yawnvowels", Dir.FTM, Category.RESONANCE, "Yawny vowels", "Keep the open feeling of a small yawn while saying \"ah, oh, oo\" and then \"how are you\". No pushing.", 1, Kind.RESONANCE, "resonance meter", ""),
        Exercise("lightslide", Dir.MTF, Category.WEIGHT, "Light slides down", "Start high and effortless, then glide down into speaking pitch keeping it just as light.", 2, Kind.WEIGHT, "weight meter", ""),
        Exercise("nostril", Dir.MTF, Category.WEIGHT, "One-nostril hum", "Pinch one nostril, hum mid-high, release slowly and open into \"ah\". Then short words.", 1, Kind.WEIGHT, "weight meter", ""),
        Exercise("puppy", Dir.MTF, Category.WEIGHT, "Puppy \"awww\"", "Say a soft \"awww\" like talking to a puppy, then words in that same voice.", 1, Kind.WEIGHT, "weight meter", ""),
        Exercise("onsets", Dir.MTF, Category.WEIGHT, "Soft onsets", "\"la, wa, hee, hey, hi\" — start each one gently, no hard click or buzz.", 1, Kind.WEIGHT, "weight meter", "m"),
        Exercise("firmvowels", Dir.FTM, Category.WEIGHT, "Firm vowels", "Sustain \"ah\", \"oh\" and \"oo\" for 4 seconds each with a firm, connected voice — not breathy, not pressed.", 1, Kind.WEIGHT, "weight meter", "f"),
        Exercise("count", Dir.FTM, Category.WEIGHT, "Full-voice counting", "Count from one to ten with a full, steady voice, powered by the breath from your belly, not the throat.", 1, Kind.WEIGHT, "weight meter", ""),
        Exercise("tonsets", Dir.FTM, Category.WEIGHT, "Crisp \"T\" onsets", "Whisper \"te, te, te\", then say it out loud at your low pitch, then \"teddy bear\", \"take ten\".", 1, Kind.WEIGHT, "weight meter", ""),
        Exercise("chestcall", Dir.FTM, Category.WEIGHT, "\"Hey there\" chest call", "Call \"hey there!\" as if to a friend across the room — relaxed and full from the chest, not shouted.", 1, Kind.WEIGHT, "weight meter", ""),
        Exercise("stress", Dir.MTF, Category.MELODY, "Move the stress", "Say \"We went to the shop on Monday\" five times, stressing a new word each time with pitch, not volume.", 2, Kind.MELODY, "melody score", ""),
        Exercise("question", Dir.MTF, Category.MELODY, "Question or statement", "Say \"You're coming tonight\" as a question, then as a statement. Let the end rise and fall.", 1, Kind.MELODY, "melody score", ""),
        Exercise("vowels", Dir.MTF, Category.MELODY, "Longer vowels, softer consonants", "Read the passage slowly: stretch the main vowels, keep s and f soft.", 1, Kind.READ, "full report", ""),
        Exercise("humstatement", Dir.FTM, Category.MELODY, "Hum into a statement", "Hum one comfortable note, then say a short sentence on that same level: \"I'll be there at six.\"", 1, Kind.MELODY, "steadiness", ""),
        Exercise("falling", Dir.FTM, Category.MELODY, "Falling endings", "Say statements that end going down, never up: \"That works for me.\" \"I'll call you tomorrow.\" \"It's done.\"", 1, Kind.MELODY, "steadiness", "f"),
        Exercise("slowread", Dir.FTM, Category.MELODY, "Slow, steady reading", "Read the passage slowly with an even pitch, letting each sentence fall at the end.", 1, Kind.READ, "full report", ""),
        Exercise("read", Dir.BOTH, Category.MELODY, "Read aloud", "Read the passage with natural melody, inside your target zone.", 2, Kind.READ, "full report", "mf"),
        Exercise("freetalk", Dir.BOTH, Category.MELODY, "Free talk", "Describe your day for a minute without stopping.", 1, Kind.TARGET, "full report", ""),
        Exercise("mimic", Dir.BOTH, Category.MIMICRY, "Speak along", "Open a voice clip you like and speak along with it, copying melody and voice. Headphones help.", 3, Kind.MIMIC, "compare to clip", ""),

    )

    /** The 25 exercises for one direction. */
    fun forGoal(g: Goal) = all.filter { it.dir == Dir.BOTH || it.dir == g.dir }
    fun defaultRoutine(g: Goal) = forGoal(g).filter { it.def.contains(if (g == Goal.FTM) 'f' else 'm') }.map { it.id }.toSet()

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: PlanStore.current?.exercises?.firstOrNull { it.id == id }

    const val PASSAGE =
        "The morning light came slowly over the hills, and the lake was so still that it held the whole sky. " +
        "I took my coffee to the window and watched a small boat drift past the pier. " +
        "Do you remember the summer we swam there every evening? " +
        "We always said we would go back, and maybe this year we finally will. " +
        "There is something lovely about a quiet day with nowhere to be."

    /** Guide pitch (Hz) at time t (s) within one round, or null when the exercise has no guide. */
    fun guide(ex: Exercise, t: Float, target: Float): Float? {
        val r = ex.roundSeconds
        if (r <= 0f) return null
        val p = (t % r) / r
        return when (ex.kind) {
            Kind.GLIDE -> if (ex.dir == Dir.FTM) {
                // downward: from above the target down toward it and back up
                val hi = target + 60f; val lo = (target - 25f).coerceAtLeast(80f)
                (hi * (lo / hi).toDouble().pow(sin(PI * p))).toFloat()
            } else {
                val lo = (target - 50f).coerceAtLeast(110f); val hi = target + 70f
                (lo * (hi / lo).toDouble().pow(sin(PI * p))).toFloat()
            }
            Kind.HOLD -> target
            Kind.LADDER -> {
                val steps = 7 // -30..+30 by 10, up then down
                val i = (p * (2 * steps - 1)).toInt()
                val k = if (i < steps) i else 2 * steps - 2 - i
                (target - 30f + 10f * k).coerceAtLeast(70f)
            }
            else -> null
        }
    }
}

/** Melody score 0..100: MtF rewards variation (4 semitones = 100), FtM rewards a steady line (1 st = 100, 4 st = 0). */
fun melodyScore(sd: Float, goal: Goal): Int =
    if (goal == Goal.FTM) ((4f - sd) / 3f * 100f).toInt().coerceIn(0, 100) else (sd / 4f * 100f).toInt().coerceAtMost(100)
