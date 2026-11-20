import { S } from './store.js';

// Training exercises for both directions (same as Exercises.kt).
// dir: 'both' | 'mtf' (feminine voice) | 'ftm' (masculine voice). Each direction has 25.
// def: which routines the exercise starts in — 'm' MtF, 'f' FtM, 'mf' both.

export const DIRS = {
  mtf: { label: 'Feminine voice', short: 'Feminine', goalLo: 165, goalHi: 255, goalName: 'female', defaultTarget: 200,
    goalText: 'Goal: brighter resonance and a lighter voice' },
  ftm: { label: 'Masculine voice', short: 'Masculine', goalLo: 85, goalHi: 155, goalName: 'male', defaultTarget: 120,
    goalText: 'Goal: darker resonance and a fuller voice' },
};

export const Categories = {
  WARMUP: { title: 'WARM-UP & BREATH', chip: 'Warm-up', hint: 'start here' },
  PITCH: { title: 'PITCH', chip: 'Pitch', hint: 'Hz scale + target line' },
  RESONANCE: { title: 'RESONANCE', chip: 'Resonance', hint: 'bright vs dark meter' },
  WEIGHT: { title: 'VOCAL WEIGHT', chip: 'Weight', hint: 'light vs heavy meter' },
  MELODY: { title: 'MELODY & SPEECH', chip: 'Melody', hint: 'pitch variation' },
  MIMICRY: { title: 'MIMICRY', chip: 'Mimicry', hint: 'use your own clips' },
};

const E = (id, dir, cat, name, how, minutes, kind, meter, def = '', roundSeconds = 0, rounds = 1) =>
  ({ id, dir, cat, name, how, minutes, kind, meter, def, roundSeconds, rounds });

export const ALL_EXERCISES = [
  // ---------------- warm-up
  E('breath', 'both', 'WARMUP', 'Belly breathing', 'Breathe in low into the belly for 4, out slowly on "sss" for 8. Shoulders relaxed.', 1, 'TIMER', 'breath', 'mf'),
  E('trills', 'both', 'WARMUP', 'Lip trills & straw glides', 'Bubble the lips (or hum through a straw) while sliding gently up and down.', 2, 'FREE', 'warm-up', 'mf'),
  E('hum', 'mtf', 'WARMUP', 'Forward hum "mm-hmm"', 'Hum "mm-hmm" like agreeing. Feel the buzz on your nose and cheekbones.', 1, 'RESONANCE', 'resonance meter', 'm'),
  E('yawn', 'ftm', 'WARMUP', 'Yawn-sigh', 'Start a gentle yawn, feel the throat open, then sigh out on an easy "ahh". Never push the larynx down.', 1, 'RESONANCE', 'resonance meter', 'f'),
  E('sighs', 'both', 'WARMUP', 'Cool-down sighs', 'Soft, easy sighs from high to low to relax the voice after training.', 1, 'FREE', 'end of session'),

  // ---------------- pitch
  E('baseline', 'both', 'PITCH', 'Find your baseline', 'Talk freely for 30 seconds in your most comfortable voice. The app measures where you are.', 1, 'BASELINE', 'sets your start'),
  E('glides', 'mtf', 'PITCH', 'Pitch glides (sirens)', 'Slide on "ooo" or "whoop" from low to high and back. Follow the dashed brass line.', 2, 'GLIDE', 'match score', 'm', 6, 5),
  E('downglides', 'ftm', 'PITCH', 'Downward glides', 'On an open "ahh", slide gently from a comfortable note down toward your target and back up. Follow the dashed line — never force the bottom.', 2, 'GLIDE', 'match score', 'f', 6, 5),
  E('halfway', 'mtf', 'PITCH', 'Up and halfway back', 'Slide up to your top note, then settle halfway down and say "hello, how are you" from there.', 2, 'TARGET', 'pitch target'),
  E('settle', 'ftm', 'PITCH', 'Sigh and settle', 'Sigh down from a medium note, let it land on a relaxed "mm-hmm", and say "yeah, sounds good" from there.', 2, 'TARGET', 'pitch target'),
  E('hold', 'both', 'PITCH', 'Hold the target', 'Sustain "mmm", then "ee", inside the target zone for 5 seconds. Rest and repeat.', 1, 'HOLD', 'steadiness', 'mf', 5, 5),
  E('ladder', 'both', 'PITCH', 'Step ladder', 'Count "one, two, three…" — each word 10 Hz higher, then back down. Follow the steps.', 2, 'LADDER', 'pitch control', '', 9, 4),
  E('phrases', 'both', 'PITCH', 'Phrases on target', 'Say short phrases and start each one inside the target zone: "Good morning." "Thank you." "See you soon."', 2, 'TARGET', 'time on target', 'mf'),

  // ---------------- resonance
  E('dogs', 'mtf', 'RESONANCE', 'Big dog, small dog', 'Pant a deep "uhh" like a big dog, then a bright "ehh" like a small dog. End on the small dog.', 2, 'RESONANCE', 'resonance meter'),
  E('whisper', 'mtf', 'RESONANCE', 'Whisper siren', 'Whisper a smooth slide from "uhh" to "ehh" and hold the bright end. Then add a little voice.', 2, 'RESONANCE', 'resonance meter', 'm'),
  E('mvowels', 'mtf', 'RESONANCE', 'M + vowels', 'Glide from a hum into vowels: "mmay, mmee, mmy, mmow, mmoo". Keep the buzz forward.', 1, 'RESONANCE', 'resonance meter'),
  E('eewords', 'mtf', 'RESONANCE', '"EE" tongue words', 'Hold a long "ee", then say may, my, moo, mate, might — keep the tongue high and forward.', 1, 'RESONANCE', 'resonance meter'),
  E('smile', 'mtf', 'RESONANCE', 'Smile "haa"', 'Say a yawning "haa", then a "haa" with a small smile and no yawn. Keep the smile one.', 1, 'RESONANCE', 'resonance meter'),
  E('chesthum', 'ftm', 'RESONANCE', 'Chest hum', 'Hand on your chest, hum a comfortable low "mmm" until you feel the buzz there. Then open into "mmm-ah", "mmm-oh".', 1, 'RESONANCE', 'resonance meter', 'f'),
  E('bigdog', 'ftm', 'RESONANCE', 'Big dog "uhh"', 'Pant a relaxed, deep "uhh" like a big dog, then say "huh, hum, hug" keeping that roomy space at the back of the mouth.', 1, 'RESONANCE', 'resonance meter'),
  E('deepo', 'ftm', 'RESONANCE', 'Deep "O" breathing', 'Breathe in through a round "O" mouth, then breathe out on a low hum with the jaw open and tongue resting low.', 2, 'RESONANCE', 'resonance meter'),
  E('lowtongue', 'ftm', 'RESONANCE', 'Open "aw" words', 'Tongue flat and relaxed, jaw loose: law, saw, call, ball, hot, thought. Keep the sound round and dark.', 1, 'RESONANCE', 'resonance meter'),
  E('yawnvowels', 'ftm', 'RESONANCE', 'Yawny vowels', 'Keep the open feeling of a small yawn while saying "ah, oh, oo" and then "how are you". No pushing.', 1, 'RESONANCE', 'resonance meter'),

  // ---------------- weight
  E('lightslide', 'mtf', 'WEIGHT', 'Light slides down', 'Start high and effortless, then glide down into speaking pitch keeping it just as light.', 2, 'WEIGHT', 'weight meter'),
  E('nostril', 'mtf', 'WEIGHT', 'One-nostril hum', 'Pinch one nostril, hum mid-high, release slowly and open into "ah". Then short words.', 1, 'WEIGHT', 'weight meter'),
  E('puppy', 'mtf', 'WEIGHT', 'Puppy "awww"', 'Say a soft "awww" like talking to a puppy, then words in that same voice.', 1, 'WEIGHT', 'weight meter'),
  E('onsets', 'mtf', 'WEIGHT', 'Soft onsets', '"la, wa, hee, hey, hi" — start each one gently, no hard click or buzz.', 1, 'WEIGHT', 'weight meter', 'm'),
  E('firmvowels', 'ftm', 'WEIGHT', 'Firm vowels', 'Sustain "ah", "oh" and "oo" for 4 seconds each with a firm, connected voice — not breathy, not pressed.', 1, 'WEIGHT', 'weight meter', 'f'),
  E('count', 'ftm', 'WEIGHT', 'Full-voice counting', 'Count from one to ten with a full, steady voice, powered by the breath from your belly, not the throat.', 1, 'WEIGHT', 'weight meter'),
  E('tonsets', 'ftm', 'WEIGHT', 'Crisp "T" onsets', 'Whisper "te, te, te", then say it out loud at your low pitch, then "teddy bear", "take ten".', 1, 'WEIGHT', 'weight meter'),
  E('chestcall', 'ftm', 'WEIGHT', '"Hey there" chest call', 'Call "hey there!" as if to a friend across the room — relaxed and full from the chest, not shouted.', 1, 'WEIGHT', 'weight meter'),

  // ---------------- melody
  E('stress', 'mtf', 'MELODY', 'Move the stress', 'Say "We went to the shop on Monday" five times, stressing a new word each time with pitch, not volume.', 2, 'MELODY', 'melody score'),
  E('question', 'mtf', 'MELODY', 'Question or statement', 'Say "You\'re coming tonight" as a question, then as a statement. Let the end rise and fall.', 1, 'MELODY', 'melody score'),
  E('vowels', 'mtf', 'MELODY', 'Longer vowels, softer consonants', 'Read the passage slowly: stretch the main vowels, keep s and f soft.', 1, 'READ', 'full report'),
  E('humstatement', 'ftm', 'MELODY', 'Hum into a statement', 'Hum one comfortable note, then say a short sentence on that same level: "I\'ll be there at six."', 1, 'MELODY', 'steadiness'),
  E('falling', 'ftm', 'MELODY', 'Falling endings', 'Say statements that end going down, never up: "That works for me." "I\'ll call you tomorrow." "It\'s done."', 1, 'MELODY', 'steadiness', 'f'),
  E('slowread', 'ftm', 'MELODY', 'Slow, steady reading', 'Read the passage slowly with an even pitch, letting each sentence fall at the end.', 1, 'READ', 'full report'),
  E('read', 'both', 'MELODY', 'Read aloud', 'Read the passage with natural melody, inside your target zone.', 2, 'READ', 'full report', 'mf'),
  E('freetalk', 'both', 'MELODY', 'Free talk', 'Describe your day for a minute without stopping.', 1, 'TARGET', 'full report'),

  // ---------------- mimicry
  E('mimic', 'both', 'MIMICRY', 'Speak along', 'Open a voice clip you like and speak along with it, copying melody and voice. Headphones help.', 3, 'MIMIC', 'compare to clip'),
];

/** Exercises for a direction ('mtf' | 'ftm'). */
export const exercisesFor = (dir) => ALL_EXERCISES.filter(e => e.dir === 'both' || e.dir === dir);
/** Kept for older callers: the current direction's list is chosen by routineList/exercisesFor. */
export const EXERCISES = ALL_EXERCISES;
export const byId = (id) => ALL_EXERCISES.find(e => e.id === id) || planExercises(S()).find(e => e.id === id);

// ---------------------------------------------------------------- personal plan (imported file, kept on the device)

export const PLAN_CAT = { title: 'MY PLAN', chip: 'My plan', hint: '' };

/** Checks an imported plan file and returns a clean copy, or throws. */
export function parsePlan(json) {
  const p = typeof json === 'string' ? JSON.parse(json) : json;
  if (!p || p.type !== 'recorder-plan' || !Array.isArray(p.exercises) || !p.exercises.length) throw new Error('This is not a Recorder plan file');
  const str = (v, max = 600) => typeof v === 'string' ? v.slice(0, max) : '';
  return {
    name: str(p.name, 80) || 'My plan', source: str(p.source, 120), days: Math.max(1, Math.min(60, p.days | 0 || 7)),
    intro: str(p.intro), safety: str(p.safety), notePrompt: str(p.notePrompt, 300),
    fields: (Array.isArray(p.fields) ? p.fields : []).slice(0, 6).map(f => ({ key: str(f.key, 30).replace(/\W/g, ''), label: str(f.label, 60), hint: str(f.hint, 60) })).filter(f => f.key),
    exercises: p.exercises.slice(0, 20).map((e, i) => ({
      id: str(e.id, 30).replace(/\W/g, '') || 'e' + i, name: str(e.name, 80) || `Exercise ${i + 1}`,
      kind: ['TIMER', 'FREE', 'TARGET', 'HOLD', 'RESONANCE', 'WEIGHT', 'MELODY', 'READ'].includes(e.kind) ? e.kind : 'FREE',
      minutes: Math.max(1, Math.min(20, e.minutes | 0 || 1)), optional: !!e.optional,
      purpose: str(e.purpose), steps: (Array.isArray(e.steps) ? e.steps : []).slice(0, 8).map(x => str(x)), caution: str(e.caution),
    })),
    values: {}, startedAt: Date.now(),
  };
}

/** Fills {mword}-style placeholders with the user's own words. */
export function fillPlan(text, plan) {
  return String(text || '').replace(/\{(\w+)\}/g, (_, k) => (plan?.values?.[k] || '').trim() || `(${(plan?.fields || []).find(f => f.key === k)?.label.toLowerCase() || k} — add it on the Exercises screen)`);
}

/** The plan's exercises in the app's exercise shape (ids start with "plan_"). */
export function planExercises(set) {
  const p = set?.plan; if (!p) return [];
  return p.exercises.map(e => ({ id: 'plan_' + e.id, dir: 'both', cat: 'PLAN', name: e.name, how: e.purpose, steps: e.steps, caution: e.caution,
    minutes: e.minutes, kind: e.kind, meter: e.kind === 'TIMER' ? 'timer' : 'live pitch', def: '', roundSeconds: 0, rounds: 1, optional: e.optional, plan: true }));
}

/** Day of the plan (1-based) and whether it is finished. */
export function planDay(set, now = Date.now()) {
  const p = set?.plan; if (!p) return null;
  const d0 = new Date(p.startedAt); d0.setHours(0, 0, 0, 0);
  const d1 = new Date(now); d1.setHours(0, 0, 0, 0);
  const day = Math.round((d1 - d0) / 864e5) + 1;
  return { day: Math.min(day, p.days), days: p.days, done: day > p.days };
}

export const PASSAGE =
  'The morning light came slowly over the hills, and the lake was so still that it held the whole sky. ' +
  'I took my coffee to the window and watched a small boat drift past the pier. ' +
  'Do you remember the summer we swam there every evening? ' +
  'We always said we would go back, and maybe this year we finally will. ' +
  'There is something lovely about a quiet day with nowhere to be.';

/** Seconds the guide line runs behind the clock. */
export const GUIDE_DELAY = 1;

/** Guide pitch (Hz) at time t within one round, or null. */
export function guide(ex, t, target) {
  const r = ex.roundSeconds; if (r <= 0) return null;
  // the guide runs 1 s behind the round clock so there is time to react (wraps smoothly: rounds start and end on the same note)
  const p = (((t - GUIDE_DELAY) % r) + r) % r / r;
  if (ex.kind === 'GLIDE') {
    if (ex.dir === 'ftm') { const hi = target + 60, lo = Math.max(80, target - 25); return hi * Math.pow(lo / hi, Math.sin(Math.PI * p)); }
    const lo = Math.max(110, target - 50), hi = target + 70; return lo * Math.pow(hi / lo, Math.sin(Math.PI * p));
  }
  if (ex.kind === 'HOLD') return target;
  if (ex.kind === 'LADDER') { const steps = 7, i = Math.floor(p * (2 * steps - 1)), k = i < steps ? i : 2 * steps - 2 - i; return Math.max(70, target - 30 + 10 * k); }
  return null;
}

export const dirOf = (settings) => settings.direction === 'ftm' ? 'ftm' : 'mtf';
const routineKey = (dir) => dir === 'ftm' ? 'routineFtm' : 'routine';
export function routineIds(settings, dir = dirOf(settings)) {
  if (settings.plan) return planExercises(settings).filter(e => !e.optional).map(e => e.id);
  return settings[routineKey(dir)] || exercisesFor(dir).filter(e => e.def.includes(dir === 'ftm' ? 'f' : 'm')).map(e => e.id);
}
export function routinePatch(dir, ids) { return { [routineKey(dir)]: ids }; }
export function routineList(settings, dir = dirOf(settings)) {
  if (settings.plan) return planExercises(settings).filter(e => !e.optional);
  const ids = routineIds(settings, dir);
  return exercisesFor(dir).filter(e => ids.includes(e.id));
}

/** Share of voiced time inside the goal range for a session's direction. */
export const goalPct = (stats, dir) => dir === 'ftm' ? stats.pctMale : stats.pctFemale;

export function sessionLabel(s) {
  const ex = s.exercises || [];
  if (!ex.length) return 'Live practice';
  if (s.plan) return `${s.plan} · ${ex.length} exercise${ex.length === 1 ? '' : 's'}`;
  if (s.routineSize > 0 && ex.length >= s.routineSize) return `Routine · ${ex.length} exercises`;
  if (ex.length === 1) return byId(ex[0].id)?.name || 'Exercise';
  return `${ex.length} exercises`;
}
