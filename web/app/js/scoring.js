// Daily score, streaks, training steps and achievements (port of Scoring.kt).
import { dayKey, dayStart, S, saveSettings } from './store.js';
import { byId, routineList, Categories, dirOf, goalPct } from './exercises.js';

const sum = (a, f) => a.reduce((s, x) => s + f(x), 0);

export class Scoring {
  constructor(sessions) {
    this.sessions = sessions;
    this.byDay = new Map();
    for (const s of sessions) { const k = dayKey(s.id); if (!this.byDay.has(k)) this.byDay.set(k, []); this.byDay.get(k).push(s); }
    this.settings = S();
  }
  daySessions(ms) { return this.byDay.get(dayKey(ms)) || []; }

  dayScore(ms) {
    const day = this.daySessions(ms); if (!day.length) return null;
    const minutes = sum(day, s => s.seconds) / 60, minutesPts = Math.min(1, minutes / 15) * 25;
    const routine = routineList(this.settings);
    const done = new Set(day.flatMap(s => s.exercises.map(e => e.id)));
    const routineDone = routine.filter(e => done.has(e.id)).length;
    const routinePts = routine.length ? routineDone / routine.length * 25 : minutesPts;
    const voiced = day.filter(s => s.stats).map(s => [s.stats, s.stats.voicedSeconds]);
    const tv = sum(voiced, v => v[1]);
    const onTarget = tv > 0 ? sum(voiced, v => v[0].pctOnTarget * v[1]) / tv : 0;
    const targetPts = onTarget / 100 * 25;
    const target = this.settings.target;
    const todayMed = this.weightedMedian(day);
    const k = dayKey(ms);
    const prevDays = [...this.byDay.keys()].filter(x => x < k).sort((a, b) => b - a).slice(0, 7);
    const prevMeds = prevDays.map(d => this.weightedMedian(this.byDay.get(d))).filter(v => v > 0);
    let improvement = 0, improvePts = 12.5;
    if (todayMed > 0 && prevMeds.length) {
      const prev = sum(prevMeds, v => v) / prevMeds.length;
      improvement = todayMed - prev;
      const gain = Math.abs(prev - target) - Math.abs(todayMed - target);
      improvePts = 12.5 + Math.max(-1, Math.min(1, gain / 10)) * 12.5;
    }
    const total = Math.max(0, Math.min(100, Math.floor(minutesPts + routinePts + targetPts + improvePts)));
    return { total, minutes, minutesPts, routineDone, routineSize: routine.length, routinePts, onTarget, targetPts, improvement, improvePts };
  }

  weightedMedian(day) {
    const w = day.map(s => s.stats).filter(st => st && st.voicedSeconds > 2);
    if (!w.length) return 0;
    return sum(w, st => st.medianF0 * st.voicedSeconds) / sum(w, st => st.voicedSeconds);
  }

  streak(now = Date.now()) {
    const d = new Date(now);
    if (!this.byDay.has(dayKey(d.getTime()))) d.setDate(d.getDate() - 1);
    let n = 0;
    while (this.byDay.has(dayKey(d.getTime()))) { n++; d.setDate(d.getDate() - 1); }
    return n;
  }

  longestStreak() {
    if (!this.sessions.length) return 0;
    const days = [...new Set(this.sessions.map(s => dayStart(s.id)))].sort((a, b) => a - b);
    let best = 1, cur = 1;
    for (let i = 1; i < days.length; i++) { const diff = days[i] - days[i - 1]; cur = (diff >= 82_800_000 && diff <= 90_000_000) ? cur + 1 : 1; best = Math.max(best, cur); }
    return best;
  }

  /** Monday-start week: 7 noon timestamps. */
  weekDays(ms = Date.now()) {
    const d = new Date(dayStart(ms)); const dow = (d.getDay() + 6) % 7;
    d.setDate(d.getDate() - dow);
    const out = [];
    for (let i = 0; i < 7; i++) { out.push(d.getTime() + 12 * 3600_000); d.setDate(d.getDate() + 1); }
    return out;
  }
  daysTrainedInWeek(ms = Date.now()) { return this.weekDays(ms).filter(t => this.byDay.has(dayKey(t))).length; }

  get stats() { return this.sessions.map(s => s.stats).filter(Boolean); }
  get trainingDays() { return this.byDay.size; }
  get totalMinutes() { return sum(this.sessions, s => s.seconds) / 60; }
  exerciseDone(id) { return this.sessions.some(s => s.exercises.some(e => e.id === id)); }
  bestDay(f) { let m = 0; for (const d of this.byDay.values()) m = Math.max(m, f(d)); return m; }
  onTargetMinutes(day) { return sum(day, s => s.stats ? s.stats.pctOnTarget / 100 * s.stats.voicedSeconds / 60 : 0); }
  readScore() { let m = 0; for (const s of this.sessions) for (const e of s.exercises) if (e.id === 'read') m = Math.max(m, e.score); return m; }

  /** Sessions trained in the current direction (old sessions without dir count as MtF). */
  get dir() { return dirOf(this.settings); }
  get mine() { return this.sessions.filter(s => (s.dir || 'mtf') === this.dir); }
  get myStats() { return this.mine.map(s => s.stats).filter(Boolean); }

  steps() {
    const st = this.myStats, ftm = this.dir === 'ftm', mine = this.mine;
    const mk = (title, goal, goals) => ({ title, goal, goals, done: goals.every(g => g[1]), count: goals.filter(g => g[1]).length });
    const done = (id) => mine.some(s => s.exercises.some(e => e.id === id));
    const readScore = Math.max(0, ...mine.flatMap(s => s.exercises.filter(e => e.id === 'read').map(e => e.score)));
    const foundations = mk('Foundations', ftm ? 'Breathing, open-throat warm-up, find your baseline, gentle downward glides' : 'Breathing, warm-up, find your baseline, gentle pitch glides', [
      ['First session', this.sessions.length > 0],
      ['Find your baseline', done('baseline')],
      [ftm ? 'Downward glides' : 'Pitch glides', done(ftm ? 'downglides' : 'glides')],
      ['Train on 3 days', this.trainingDays >= 3],
      ['30 min in total', this.totalMinutes >= 30],
    ]);
    const building = ftm
      ? mk('Building', 'Settle pitch toward your target, start chest resonance and a fuller voice', [
        ['A session with median 155 Hz or lower', st.some(s => s.medianF0 > 0 && s.medianF0 <= 155)],
        ['10 min on target in one day', this.bestDay(d => this.onTargetMinutes(d)) >= 10],
        ['Resonance 50 or darker in a session', st.some(s => s.resonance <= 50 && s.resonance > 0)],
        ['Weight 50 or fuller in a session', st.some(s => s.weight <= 50 && s.voicedSeconds > 10)],
        ['5-day streak', this.longestStreak() >= 5],
      ])
      : mk('Building', 'Raise pitch toward your target, start resonance and light weight', [
        ['A session with median 165 Hz+', st.some(s => s.medianF0 >= 165)],
        ['10 min on target in one day', this.bestDay(d => this.onTargetMinutes(d)) >= 10],
        ['Resonance 50+ in a session', st.some(s => s.resonance >= 50)],
        ['Light weight 50+ in a session', st.some(s => s.weight >= 50)],
        ['5-day streak', this.longestStreak() >= 5],
      ]);
    const speech = ftm
      ? mk('Speech', 'Steady, falling melody and reading on target across a whole session', [
        ['Read aloud 60%+ in male range', readScore >= 60],
        ['Steady melody (2 semitones or less) in a session', st.some(s => s.melody > 0 && s.melody <= 2 && s.voicedSeconds > 20)],
        ['3 sessions with 70%+ in male range', st.filter(s => s.pctMale >= 70).length >= 3],
        ['Train on 20 days', this.trainingDays >= 20],
      ])
      : mk('Speech', 'Melody, phrases and reading on target, steady across a whole session', [
        ['Read aloud 60%+ in female range', readScore >= 60],
        ['Melody 2.5 semitones+ in a session', st.some(s => s.melody >= 2.5 && s.voicedSeconds > 20)],
        ['3 sessions with 70%+ in female range', st.filter(s => s.pctFemale >= 70).length >= 3],
        ['Train on 20 days', this.trainingDays >= 20],
      ]);
    const everyday = mk('Everyday voice', 'Free talk and mimicry, keep the voice through long conversations', [
      [`Free talk 70%+ in ${ftm ? 'male' : 'female'} range`, mine.some(s => s.exercises.some(e => e.id === 'freetalk' && e.score >= 70))],
      ['Speak along with a clip', done('mimic')],
      ['14-day streak', this.longestStreak() >= 14],
      ['10 hours in total', this.totalMinutes >= 600],
    ]);
    return [foundations, building, speech, everyday];
  }
  currentStepIndex() { const i = this.steps().findIndex(s => !s.done); return i < 0 ? 3 : i; }

  achievements() {
    const now = Date.now(), st = this.stats, set = this.settings;
    const routine = routineList(set).map(e => e.id);
    const fullRoutineDay = routine.length > 0 && [...this.byDay.values()].some(day => routine.every(id => day.some(s => s.exercises.some(e => e.id === id))));
    const weekGoal = set.weeklyGoal;
    const bestWeek = Math.max(0, ...this.sessions.map(s => this.daysTrainedInWeek(s.id)));
    const cats = new Set(this.sessions.flatMap(s => s.exercises.map(e => byId(e.id)?.cat).filter(c => c && c !== 'PLAN')));
    const ncats = Object.keys(Categories).length;
    const streak = this.streak(), longest = this.longestStreak();
    const max = (f) => st.length ? Math.max(...st.map(f)) : 0;
    const ftm = this.dir === 'ftm', my = this.myStats;
    const myMax = (f) => my.length ? Math.max(...my.map(f)) : 0;
    const myMin = (f) => { const v = my.map(f).filter(x => x > 0); return v.length ? Math.min(...v) : 0; };
    const bestMed = myMax(s => s.medianF0), lowMed = myMin(s => s.medianF0);
    const rem = (n, unit) => `${Math.max(0, n)} ${unit} left`;
    const notes = this.sessions.filter(s => (s.notes || '').trim()).length;
    const step = this.currentStepIndex();
    const pitchAch = ftm ? [
      ['m_hz155', 'Into the male range', 'A session with median pitch 155 Hz or lower', lowMed > 0 && lowMed <= 155, lowMed ? `best ${lowMed.toFixed(0)} Hz` : 'Train once'],
      ['m_hz140', '140 Hz', 'A session with median pitch 140 Hz or lower', lowMed > 0 && lowMed <= 140, lowMed ? `${Math.max(0, lowMed - 140).toFixed(0)} Hz to go` : '–'],
      ['m_hz125', '125 Hz', 'A session with median pitch 125 Hz or lower', lowMed > 0 && lowMed <= 125, lowMed ? `${Math.max(0, lowMed - 125).toFixed(0)} Hz to go` : '–'],
      ['m_male70', 'Mostly male range', '70% of a session inside 85–155 Hz', my.some(s => s.pctMale >= 70), `best ${myMax(s => s.pctMale).toFixed(0)}%`],
      ['m_res50', 'Darker', 'Resonance 50 or darker in a session', my.some(s => s.resonance > 0 && s.resonance <= 50), `best ${myMin(s => s.resonance).toFixed(0)}`],
      ['m_res30', 'Deep resonance', 'Resonance 30 or darker in a session', my.some(s => s.resonance > 0 && s.resonance <= 30), `best ${myMin(s => s.resonance).toFixed(0)}`],
      ['m_full40', 'Full voice', 'Vocal weight 40 or fuller in a session', my.some(s => s.weight <= 40 && s.voicedSeconds > 10), `best ${my.length ? Math.min(...my.map(s => s.weight)).toFixed(0) : 0}`],
      ['m_steady', 'Steady melody', 'Melody of 2 semitones or less over 20 s of voice', my.some(s => s.melody > 0 && s.melody <= 2 && s.voicedSeconds > 20), `best ${myMin(s => s.melody).toFixed(1)} st`],
    ] : [
      ['hz165', 'Into the female range', 'A session with median pitch 165 Hz or higher', bestMed >= 165, `best ${bestMed.toFixed(0)} Hz`],
      ['hz180', '180 Hz', 'A session with median pitch 180 Hz or higher', bestMed >= 180, `${Math.max(0, 180 - bestMed).toFixed(0)} Hz to go`],
      ['hz200', '200 Hz', 'A session with median pitch 200 Hz or higher', bestMed >= 200, `${Math.max(0, 200 - bestMed).toFixed(0)} Hz to go`],
      ['female70', 'Mostly female range', '70% of a session inside 165–255 Hz', my.some(s => s.pctFemale >= 70), `best ${myMax(s => s.pctFemale).toFixed(0)}%`],
      ['res50', 'Brighter', 'Resonance 50 or more in a session', my.some(s => s.resonance >= 50), `best ${myMax(s => s.resonance).toFixed(0)}`],
      ['res70', 'Bright resonance', 'Resonance 70 or more in a session', my.some(s => s.resonance >= 70), `best ${myMax(s => s.resonance).toFixed(0)}`],
      ['light60', 'Light voice', 'Vocal weight 60 or lighter in a session', my.some(s => s.weight >= 60), `best ${myMax(s => s.weight).toFixed(0)}`],
      ['melody', 'Lively melody', 'Melody of 3 semitones or more in a session', my.some(s => s.melody >= 3 && s.voicedSeconds > 20), `best ${myMax(s => s.melody).toFixed(1)} st`],
    ];
    const list = [
      ['first', 'First session', 'Saved your first training session', this.sessions.length > 0, 'Train once'],
      ['routine', 'Full routine', 'Finished every chosen exercise in one day', fullRoutineDay, 'Do the whole routine'],
      ['streak3', '3-day streak', 'Trained 3 days in a row', longest >= 3, rem(3 - streak, 'days')],
      ['streak7', '7-day streak', 'Trained every day for a week', longest >= 7, rem(7 - streak, 'days')],
      ['streak14', '14-day streak', 'Two weeks without a break', longest >= 14, rem(14 - streak, 'days')],
      ['streak30', '30-day streak', 'Trained every day for a month', longest >= 30, rem(30 - streak, 'days')],
      ['week', 'Week goal', `Trained ${weekGoal} days in one week`, bestWeek >= weekGoal, rem(weekGoal - this.daysTrainedInWeek(now), 'days this week')],
      ['ontarget10', '10 minutes on target', '10 min inside your target zone in one day', this.bestDay(d => this.onTargetMinutes(d)) >= 10, `${this.onTargetMinutes(this.daySessions(now)).toFixed(0)} of 10 min today`],
      ['practice30', 'Half an hour', 'Practised 30 minutes in one day', this.bestDay(d => sum(d, s => s.seconds) / 60) >= 30, '30 min in a day'],
      ...pitchAch,
      ['sessions10', '10 sessions', 'Saved 10 sessions', this.sessions.length >= 10, rem(10 - this.sessions.length, 'sessions')],
      ['sessions50', '50 sessions', 'Saved 50 sessions', this.sessions.length >= 50, rem(50 - this.sessions.length, 'sessions')],
      ['sessions100', '100 sessions', 'Saved 100 sessions', this.sessions.length >= 100, rem(100 - this.sessions.length, 'sessions')],
      ['allcats', 'All-rounder', 'Tried every kind of exercise', cats.size >= ncats, `${cats.size} of ${ncats} kinds`],
      ['notes5', 'Journal', 'Wrote notes on 5 sessions', notes >= 5, `${notes} of 5`],
      ['hours5', '5 hours', '5 hours of training in total', this.totalMinutes >= 300, `${this.totalMinutes.toFixed(0)} of 300 min`],
      ['step4', 'Everyday voice', 'Reached the last training step', step >= 3, `step ${step + 1} of 4`],
    ];
    const icons = [['streak', '🔥'], ['hz', '◎'], ['m_hz', '◎'], ['ontarget', '◎'], ['week', '▦']];
    const unlocked = Object.assign({}, set.unlocked); let changed = false;
    const out = list.map(([id, name, desc, ok, progress]) => {
      if (ok && !unlocked[id]) { unlocked[id] = now; changed = true; }
      const icon = (icons.find(([p]) => id.startsWith(p)) || [0, '★'])[1];
      return { id, name, desc, icon, unlockedAt: ok ? unlocked[id] : 0, progress };
    });
    if (changed) saveSettings({ unlocked });
    return out;
  }
}
