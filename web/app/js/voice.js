// Voice tab: live pitch on a 10 Hz scale, range bar and meters; exercises list; guided exercise runner.
import { el, header, fmtTime, icons, modal, toast, confirmBox, tabs as tabsRow } from './ui.js';
import { S, saveSettings, sessions, setPending } from './store.js';
import { Scores, Ranges, computeStats, median, LiveSmoother } from './engine.js';
import { pitchChart, meter, rangeBar } from './views.js';
import { LiveAnalyzer, SessionBuilder } from './live.js';
import { exercisesFor, Categories, byId, guide, PASSAGE, routineList, routineIds, routinePatch, dirOf, DIRS, PLAN_CAT, parsePlan, planExercises, planDay, fillPlan } from './exercises.js';
import { Scoring } from './scoring.js';
import { decodeFile, playSamples } from './audio.js';
import { go, topBar, onLeave } from './main.js';

/** Switch to feminine/masculine training; each direction remembers its own target. */
export function setDirection(dir) {
  const set = S(), old = dirOf(set);
  const patch = { direction: dir, directionChosen: true };
  if (dir !== old) {
    patch[old === 'ftm' ? 'targetFtm' : 'targetMtf'] = set.target;
    patch.target = set[dir === 'ftm' ? 'targetFtm' : 'targetMtf'] || DIRS[dir].defaultTarget;
  }
  saveSettings(patch);
}

/** Big two-button Feminine | Masculine switch. */
export function goalSwitch(onChange, large = false) {
  const set = S();
  const box = el('div.goal-switch' + (large ? '.large' : ''), { role: 'radiogroup', 'aria-label': 'Training goal' });
  const make = (dir, title, sub) => {
    const on = (!large || set.directionChosen) && dirOf(set) === dir;
    return el('button', { role: 'radio', 'aria-checked': String(on), class: on ? 'on' : '', onclick: () => { setDirection(dir); onChange(dir); } }, el('span.g1', title), el('span.g2', sub));
  };
  box.append(make('mtf', 'Feminine voice', 'MtF · 165–255 Hz'), make('ftm', 'Masculine voice', 'FtM · 85–155 Hz'));
  return box;
}

// ---------------------------------------------------------------- live screen

export function voiceScreen(root) {
  const set = S();
  let analyzer = null, builder = null, t = 0, onT = 0, voiced = 0, shown = 0;
  const smooth = new LiveSmoother();

  const hz = el('div.big', '–');
  const targetTitle = el('div.caps'), offText = el('div', { style: { fontSize: '15px', fontWeight: 500, color: '#EFE1BB' } }), status = el('div.small.soft.mono', 'Tap start');
  const tb = el('button', { style: { textAlign: 'right', display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: '2px' }, 'aria-label': 'Change target', onclick: showSettings }, targetTitle, offText, status);
  const range = rangeBar();
  const chart = pitchChart(220); chart.st.idleText = 'Tap the brass button and speak';
  const mRes = meter('Resonance', 'Dark', 'Bright'), mW = meter('Vocal weight', 'Heavy', 'Light'), mVol = meter('Volume', 'Soft', 'Loud'), mBr = meter('Breathiness', 'Clear', 'Breathy');
  const goalText = el('div.tiny.soft', { style: { textAlign: 'center' } });
  const rec = el('button.rec-btn', { 'aria-label': 'Start voice analysis', onclick: () => analyzer ? stop() : start() }, el('div.dot'));

  root.append(
    topBar(1),
    goalSwitch(() => go('#/voice')),
    el('div.row', { style: { alignItems: 'flex-end', marginTop: '6px' } }, el('div.row.grow', { style: { alignItems: 'flex-end', gap: '8px' } }, hz, el('div.soft', { style: { fontSize: '18px', paddingBottom: '8px' } }, 'Hz')), tb),
    range, chart,
    el('div.panel.col', { style: { gap: '6px' } }, mRes, mW, mVol, mBr, goalText),
    el('div.spacer'),
    el('div.row', { style: { gap: '16px' } }, el('button.ghost.grow', { onclick: () => go('#/exercises') }, 'Exercises'), rec, el('button.ghost.grow', { onclick: () => go('#/today') }, 'Today')),
  );
  applyTarget();
  if (!set.directionChosen) {
    const close = modal(el('div.col', { style: { gap: '12px' } },
      el('div.h2', 'What are you training toward?'),
      el('div.small.soft', 'This sets your target, exercises, scores and achievements. You can switch any time at the top of the Voice screen.'),
      goalSwitch(() => { close(); go('#/voice'); }, true)), []);
  }

  function applyTarget() {
    const D = DIRS[dirOf(set)];
    targetTitle.textContent = `${D.short.toUpperCase()} · ${Math.round(set.target)} HZ ±${Math.round(set.tolerance)}`;
    goalText.textContent = D.goalText + ` · ${D.goalName} range ${D.goalLo}–${D.goalHi} Hz`;
    chart.st.target = set.target; chart.st.tolerance = set.tolerance;
    chart.st.lo = Math.max(60, set.target - 80); chart.st.hi = chart.st.lo + 160; chart.redraw();
    range.set(null, set.target);
  }

  async function start() {
    builder = new SessionBuilder(set.target, set.tolerance, dirOf(set));
    smooth.clear(); chart.clear(); t = 0; onT = 0; voiced = 0; shown = 0;
    analyzer = new LiveAnalyzer(onFrame, (m) => { status.textContent = m; stop(false); }, builder.pcm);
    rec.classList.add('on'); rec.setAttribute('aria-label', 'Stop and see report');
    if (!(await analyzer.start())) return;
    builder.rate = analyzer.rate; builder.frameSeconds = analyzer.frameSeconds;
  }

  function onFrame(f) {
    builder.add(f); t += analyzer.frameSeconds; chart.add(t, f.f0); smooth.add(f);
    if (f.f0 > 0) {
      voiced++; if (Math.abs(f.f0 - set.target) <= set.tolerance) onT++;
      shown = shown <= 0 ? f.f0 : shown * 0.6 + f.f0 * 0.4;
      hz.textContent = Math.round(shown); range.set(shown);
      const d = shown - set.target;
      offText.textContent = Math.abs(d) <= set.tolerance ? 'On target' : d < 0 ? `${Math.round(-d)} Hz under` : `${Math.round(d)} Hz over`;
    }
    status.textContent = `on target ${voiced ? Math.floor(onT * 100 / voiced) : 0} % · ${fmtTime(t)}`;
    const r = smooth.resonance(); if (r != null) mRes.set(r / 100, String(Math.round(r)));
    const w = smooth.weight(); if (w != null) mW.set(w / 100, (w >= 50 ? 'Light ' : dirOf(set) === 'ftm' ? 'Full ' : 'Heavy ') + Math.round(w));
    mVol.set(Scores.loudness(f.db) / 100, `≈ ${Math.round(f.db)} dB`);
    const b = smooth.breathiness(); if (b != null) mBr.set(b / 100, b < 35 ? 'Clear' : b < 65 ? 'Some air' : 'Breathy');
  }

  function stop(save = true) {
    analyzer?.stop(); analyzer = null;
    rec.classList.remove('on'); rec.setAttribute('aria-label', 'Start voice analysis');
    const b = builder; builder = null;
    if (!b || !save) return;
    const s = b.build();
    if (!s.stats) { status.textContent = 'Not enough voice to analyse'; return; }
    setPending(s); go('#/report');
  }

  function showSettings() {
    let dir = dirOf(set);
    const rows = [];
    const slider = (label, min, max, step, value, fmt) => {
      const out = el('div.small', `${label}: ${fmt(value)}`);
      const inp = el('input', { type: 'range', min, max, step, value, 'aria-label': label, oninput: () => { out.textContent = `${label}: ${fmt(+inp.value)}`; } });
      inp.show = () => { out.textContent = `${label}: ${fmt(+inp.value)}`; };
      rows.push(out, inp); return inp;
    };
    const dirTabs = tabsRow(['Feminine voice', 'Masculine voice'], dir === 'ftm' ? 1 : 0, (i) => {
      dir = i === 1 ? 'ftm' : 'mtf';
      tg.value = DIRS[dir].defaultTarget; tg.show(); hint.textContent = hintText();
    });
    const tg = slider('Target', 90, 280, 5, set.target, v => `${v} Hz`);
    const tol = slider('On-target zone', 5, 30, 5, set.tolerance, v => `± ${v} Hz`);
    const goal = slider('Weekly goal', 1, 7, 1, set.weeklyGoal, v => `${v} days`);
    const hintText = () => dir === 'ftm'
      ? 'Masculine goal: male range 85–155 Hz. Pick a target you can reach without pushing — testosterone does most of the pitch drop; training shapes resonance, weight and melody.'
      : 'Feminine goal: female range 165–255 Hz. Typical male speaking range is 85–155 Hz.';
    const hint = el('div.small.soft', hintText());
    modal(el('div.col', el('div.h2', 'Training target'), el('div.small', 'Training goal'), dirTabs, ...rows, hint),
      [{ label: 'Cancel' }, { label: 'Save', primary: true, onClick: () => { const changed = dir !== dirOf(set); setDirection(dir); saveSettings({ target: +tg.value, tolerance: +tol.value, weeklyGoal: +goal.value }); if (changed) go('#/voice'); else applyTarget(); } }]);
  }

  onLeave(() => { if (analyzer) stop(); });
  const vis = () => { if (document.hidden && analyzer) stop(); };
  document.addEventListener('visibilitychange', vis); onLeave(() => document.removeEventListener('visibilitychange', vis));
}

// ---------------------------------------------------------------- exercise library

/** "My plan" panel: import a private plan file, or show the plan with its own words, days and exercises. */
function planPanel(set, sc, refresh) {
  const fileIn = el('input', { type: 'file', accept: '.json,application/json,.recorderplan', class: 'hidden', onchange: async () => {
    const f = fileIn.files[0]; fileIn.value = ''; if (!f) return;
    try { const plan = parsePlan(await f.text()); saveSettings({ plan }); toast(`Plan added: ${plan.name}`); refresh(); }
    catch (e) { toast(e.message || 'Could not read that file', 3500); }
  } });
  const p = set.plan;
  if (!p) return el('div', el('button.ghost', { style: { width: '100%' }, onclick: () => fileIn.click() }, 'Import plan file'),
    el('div.tiny.soft', { style: { marginTop: '6px', textAlign: 'center' } }, 'A plan from your speech therapist, stored only on this phone'), fileIn);

  const pd = planDay(set), exs = planExercises(set);
  const doneDays = new Set(sc.sessions.filter(s => s.plan && s.id >= p.startedAt).map(s => new Date(s.id).toDateString()));
  const dots = el('div.row', { style: { gap: '6px', flexWrap: 'wrap' } });
  for (let i = 0; i < p.days; i++) {
    const d = new Date(p.startedAt); d.setDate(d.getDate() + i);
    const ok = doneDays.has(d.toDateString()), today = i + 1 === pd.day && !pd.done;
    dots.append(el('div', { title: `Day ${i + 1}`, style: { width: '30px', height: '30px', borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '12px',
      background: ok ? 'var(--metal)' : 'none', color: ok ? 'var(--on-brass)' : today ? '#EFE1BB' : 'var(--soft)', border: ok ? 'none' : `${today ? 2 : 1}px solid ${today ? 'var(--brass-t)' : 'rgba(255,255,255,.2)'}` } }, ok ? '✓' : String(i + 1)));
  }
  const fields = p.fields.map(f => {
    const inp = el('input', { type: 'text', placeholder: f.hint || '', 'aria-label': f.label, value: p.values[f.key] || '',
      onchange: () => { p.values[f.key] = inp.value.trim(); saveSettings({ plan: p }); } });
    return el('div.col', { style: { gap: '4px' } }, el('div.small', f.label), inp);
  });
  const today = new Set(sc.daySessions(Date.now()).flatMap(s => s.exercises.map(e => e.id)));
  const list = exs.map(e => el('button.item', { style: { width: '100%', textAlign: 'left' }, onclick: () => go('#/exercise/one/' + e.id) },
    el('div.col.grow', { style: { gap: '2px' } }, el('div', { style: { fontWeight: 500, fontSize: '14px' } }, e.name + (today.has(e.id) ? '  ✓' : '')),
      el('div.tiny.soft', `${e.minutes} min · ${e.kind === 'TIMER' ? 'timer' : 'live pitch'}${e.optional ? ' · optional' : ''}`))));
  return el('div.panel.col', { style: { gap: '10px' } },
    el('div.row', el('div.caps.grow', 'My plan'), el('div.small.brass-text', pd.done ? 'Finished' : `Day ${pd.day} of ${pd.days}`)),
    p.source ? el('div.small.soft', p.source) : null, dots,
    p.intro ? el('div.small', { style: { color: 'var(--softer)' } }, p.intro) : null,
    ...fields, el('div.list-gap', list),
    p.safety ? el('div.tiny.soft', p.safety) : null,
    el('div.row', el('button.ghost.grow', { onclick: () => confirmBox('Restart the plan from day 1 today? Your saved sessions are kept.', 'Restart', () => { p.startedAt = Date.now(); saveSettings({ plan: p }); refresh(); }) }, 'Restart'),
      el('button.ghost.grow', { onclick: () => confirmBox('Remove this plan from the app? Your saved sessions are kept, and your normal routine comes back.', 'Remove', () => { saveSettings({ plan: null }); refresh(); }) }, 'Remove plan')));
}

let filter = null;
export async function exercisesScreen(root) {
  const set = S();
  const sc = new Scoring(await sessions());
  const title = el('div', { style: { fontSize: '18px', fontWeight: 500 } }), sub = el('div.small');
  const card = el('div.row', { style: { background: 'var(--metal)', color: 'var(--on-brass)', borderRadius: '16px', padding: '14px 14px 14px 18px' } },
    el('div.col.grow', { style: { gap: '2px' } }, el('div.caps', { style: { color: 'var(--on-brass)' } }, set.plan ? set.plan.name : 'My routine'), title, sub),
    el('button', { style: { background: 'var(--on-brass)', color: '#EFE1BB', borderRadius: '21px', padding: '10px 20px', fontWeight: 500 }, onclick: () => { const ids = routineList(set).map(e => e.id); if (ids.length) go('#/exercise/routine/' + ids.join(',')); } }, 'Start'));
  const dirNote = el('div.small.soft');
  const planBox = planPanel(set, sc, () => go('#/exercises', true));
  const chips = el('div.chips'), list = el('div');
  root.append(header('Exercises', null, () => go('#/voice')), goalSwitch(() => go('#/exercises')), card, planBox, chips, list,
    el('div.notice', el('div', { style: { color: 'var(--brass-t)', fontWeight: 500, marginBottom: '4px' } }, 'Keep your voice healthy'),
      "Stop if it hurts, feels strained or goes hoarse. Sip water, keep the neck and jaw relaxed, and don't push the larynx with your hands. 20–30 minutes a day is plenty, and rest days are fine." +
      (dirOf(set) === 'ftm' ? ' Don\'t force your pitch lower than is comfortable, and don\'t use vocal fry to sound deeper. On testosterone the voice can crack and feel unsteady for a while — that\'s normal; keep it gentle.' : '')));
  render();

  function render() {
    const routine = routineList(set), ids = routine.map(e => e.id);
    const today = new Set(sc.daySessions(Date.now()).flatMap(s => s.exercises.map(e => e.id)));
    const pd = planDay(set);
    title.textContent = pd ? (pd.done ? `Finished · ${pd.days} days` : `Day ${pd.day} of ${pd.days} · ${routine.reduce((a, e) => a + e.minutes, 0)} min`) : `${routine.length} chosen · ${routine.reduce((a, e) => a + e.minutes, 0)} min`;
    sub.textContent = `Today: ${routine.filter(e => today.has(e.id)).length} of ${routine.length} done`;
    chips.replaceChildren(...[null, ...Object.keys(Categories)].map(c => el('button.chip' + (filter === c ? '.on' : ''), { onclick: () => { filter = c; render(); } }, c ? Categories[c].chip : 'All')));
    list.replaceChildren();
    for (const [key, cat] of Object.entries(Categories)) {
      if (filter && filter !== key) continue;
      list.append(el('div.row', { style: { alignItems: 'flex-end', margin: '16px 0 6px' } }, el('div.caps.grow', cat.title), el('div.tiny.soft', cat.hint)));
      const box = el('div.list-gap');
      for (const ex of exercisesFor(dirOf(set)).filter(e => e.cat === key)) {
        const inR = ids.includes(ex.id);
        box.append(el('div.item',
          el('button.col.grow', { style: { textAlign: 'left', gap: '2px', alignItems: 'stretch' }, onclick: () => go('#/exercise/one/' + ex.id) },
            el('div', { style: { fontSize: '15px', fontWeight: 500 } }, ex.name + (today.has(ex.id) ? '  ✓' : '')),
            el('div.small', { style: { color: 'var(--softer)' } }, ex.how),
            el('div.tiny.soft', `${ex.minutes} min · ${ex.meter} · tap to try`)),
          set.plan ? null : el('button.add' + (inR ? '.on' : ''), { 'aria-label': (inR ? 'Remove ' : 'Add ') + ex.name + ' to routine', onclick: () => {
            const cur = new Set(ids); if (inR) cur.delete(ex.id); else cur.add(ex.id);
            saveSettings(routinePatch(dirOf(set), [...cur])); render();
          } }, inR ? 'Added' : 'Add')));
      }
      list.append(box);
    }
  }
}

// ---------------------------------------------------------------- guided exercise runner

export function exerciseScreen(root, mode, idList) {
  const set = S();
  const exercises = idList.split(',').map(byId).filter(Boolean);
  if (!exercises.length) { go('#/exercises'); return; }
  const isRoutine = mode === 'routine';
  const dir = dirOf(set);
  const session = new SessionBuilder(set.target, set.tolerance, dir);
  let index = 0, phase = 'READY', analyzer = null, exFrames = [], exTimes = [], t = 0, lastRound = -1, timerStart = 0, timer = 0, clip = null, clipPlayer = null;
  const smooth = new LiveSmoother();
  const ex = () => exercises[index];

  const catText = el('div.caps'), nameText = el('div', { style: { fontSize: '19px', fontWeight: 500 } }), roundText = el('div.mono.soft', { style: { fontSize: '14px' } });
  const how = el('div', { style: { fontSize: '15px', color: '#C9D2DB', lineHeight: 1.45 } });
  const stepsBox = el('ol', { style: { margin: '0', paddingLeft: '22px', color: '#EEF2F5', fontSize: '15px', lineHeight: 1.45, display: 'flex', flexDirection: 'column', gap: '6px' } });
  const caution = el('div.notice', { style: { padding: '9px 12px' } });
  const passage = el('div.panel.passage', PASSAGE);
  const bigTimer = el('div.big', { style: { textAlign: 'center', margin: '24px 0' } });
  const chart = pitchChart(300);
  const mA = meter('Resonance', 'Dark', 'Bright'), mB = meter('Vocal weight', 'Heavy', 'Light');
  const meters = el('div.panel.col', { style: { gap: '6px' } }, mA, mB);
  const tile = (l) => { const lab = el('div.small.soft', l), v = el('div.v', { style: { fontSize: '22px' } }, '–'); const box = el('div.tile', lab, v); box.l = lab; box.v = v; return box; };
  const tScore = tile('Score'), tNow = tile('Now'), tTime = tile('Time');
  const tiles = el('div.tiles.three', tScore, tNow, tTime);
  const leftBtn = el('button.ghost.grow', { onclick: onLeft }), mainBtn = el('button.btn', { style: { flex: '1.3' }, onclick: onMain });
  const fileIn = el('input', { type: 'file', accept: 'audio/*', class: 'hidden', onchange: async () => {
    const f = fileIn.files[0]; if (!f) return;
    try { clip = await decodeFile(f); leftBtn.textContent = 'Clip chosen ✓'; } catch (_) { toast('Could not open that file'); }
  } });

  root.append(
    el('div.row', el('button.round', { 'aria-label': 'Close exercise', html: icons.close, onclick: leave }), el('div.col.grow', { style: { gap: '2px' } }, catText, nameText), roundText),
    how, stepsBox, caution, passage, bigTimer, chart, meters, tiles, el('div.spacer'), el('div.row', leftBtn, mainBtn), fileIn);
  load();

  function load() {
    phase = 'READY'; exFrames = []; exTimes = []; smooth.clear(); t = 0; lastRound = -1;
    const e = ex();
    catText.textContent = (Categories[e.cat] || PLAN_CAT).chip.toUpperCase() + (exercises.length > 1 ? ` · ${index + 1} OF ${exercises.length}` : '');
    nameText.textContent = e.name; how.textContent = e.how;
    stepsBox.replaceChildren(...(e.steps || []).map(t => el('li', fillPlan(t, set.plan))));
    stepsBox.classList.toggle('hidden', !e.steps?.length);
    caution.textContent = e.caution || ''; caution.classList.toggle('hidden', !e.caution);
    passage.classList.toggle('hidden', e.kind !== 'READ');
    const timerOnly = e.kind === 'TIMER';
    bigTimer.classList.toggle('hidden', !timerOnly); bigTimer.textContent = fmtTime(e.minutes * 60);
    [chart, meters, tiles].forEach(x => x.classList.toggle('hidden', timerOnly));
    chart.clear();
    Object.assign(chart.st, { target: set.target, tolerance: set.tolerance, lo: Math.max(60, set.target - 100), hi: set.target + 120, idleText: 'Tap Start, then follow along' });
    if (e.roundSeconds > 0) { chart.st.roundSeconds = e.roundSeconds; chart.st.guide = (tt) => guide(e, tt, set.target); roundText.textContent = `Round 1/${e.rounds}`; }
    else { chart.st.roundSeconds = 0; chart.st.guide = null; roundText.textContent = ''; }
    chart.redraw();
    tScore.l.textContent = e.plan && e.kind !== 'TIMER' ? 'Median' : ({ GLIDE: 'Match', LADDER: 'Match', HOLD: 'On target', TARGET: 'On target', MIMIC: 'On target', PLANFREE: 'Median', RESONANCE: dir === 'ftm' ? 'Darkness' : 'Resonance', WEIGHT: dir === 'ftm' ? 'Fullness' : 'Lightness', MELODY: dir === 'ftm' ? 'Steadiness' : 'Melody', READ: dir === 'ftm' ? 'Male range' : 'Female range', BASELINE: 'Median' })[e.kind] || 'Voice time';
    tScore.v.textContent = '–'; tNow.v.textContent = '–'; tTime.v.textContent = fmtTime(0);
    mA.set(null, '–'); mB.set(null, '–');
    leftBtn.textContent = e.kind === 'MIMIC' ? (clip ? 'Clip chosen ✓' : 'Open clip') : exercises.length > 1 ? 'Skip' : 'Back';
    mainBtn.textContent = 'Start';
  }

  function onLeft() {
    if (phase === 'RUNNING') return;
    if (ex().kind === 'MIMIC' && phase === 'READY') fileIn.click();
    else if (exercises.length > 1) next(); else leave();
  }
  function onMain() { if (phase === 'READY') start(); else if (phase === 'RUNNING') stop(); else next(); }

  async function start() {
    phase = 'RUNNING'; mainBtn.textContent = 'Stop'; timerStart = Date.now();
    const e = ex();
    if (e.kind === 'TIMER') { tick(); return; }
    analyzer = new LiveAnalyzer(onFrame, (m) => { how.textContent = m; stop(); }, session.pcm);
    if (!(await analyzer.start())) return;
    session.rate = analyzer.rate; session.frameSeconds = analyzer.frameSeconds;
    if (e.kind === 'MIMIC' && clip) {
      const loop = () => { clipPlayer = playSamples(clip.samples, clip.rate, 0, clip.samples.length, () => { if (phase === 'RUNNING') loop(); }); };
      loop();
    }
  }
  function tick() {
    if (phase !== 'RUNNING') return;
    const left = ex().minutes * 60 - (Date.now() - timerStart) / 1000;
    bigTimer.textContent = fmtTime(Math.max(0, left));
    if (left <= 0) { stop(); return; }
    timer = setTimeout(tick, 250);
  }
  const maxSeconds = () => ex().roundSeconds > 0 ? ex().roundSeconds * ex().rounds : ex().minutes * 60;

  function onFrame(f) {
    const e = ex();
    t += analyzer.frameSeconds; exFrames.push(f); exTimes.push(t); session.add(f); smooth.add(f);
    if (e.roundSeconds > 0) {
      const round = Math.floor(t / e.roundSeconds);
      if (round !== lastRound) { lastRound = round; chart.clear(); roundText.textContent = `Round ${Math.min(round + 1, e.rounds)}/${e.rounds}`; }
      chart.add(t - round * e.roundSeconds, f.f0);
    } else chart.add(t, f.f0);
    if (f.f0 > 0) tNow.v.textContent = `${Math.round(f.f0)} Hz`;
    const r = smooth.resonance(); if (r != null) mA.set(r / 100, String(Math.round(r)));
    const w = smooth.weight(); if (w != null) mB.set(w / 100, String(Math.round(w)));
    tTime.v.textContent = fmtTime(t);
    if (exFrames.length % 10 === 0) {
      if (e.plan) { const v = exFrames.filter(x => x.f0 > 0).map(x => x.f0); if (v.length) tScore.v.textContent = `${Math.round(median(v))} Hz`; }
      else tScore.v.textContent = scoreText(score());
    }
    if (t >= maxSeconds()) stop();
  }

  function score() {
    const e = ex(); if (e.kind === 'TIMER') return 100;
    const v = exFrames.map((f, i) => f.f0 > 0 ? i : -1).filter(i => i >= 0);
    if (!v.length) return 0;
    const tg = set.target, tol = set.tolerance, fs = analyzer?.frameSeconds || session.frameSeconds;
    const pctOf = (pred) => Math.floor(v.filter(pred).length * 100 / v.length);
    const st = () => computeStats(exFrames, fs, tg, tol);
    switch (e.kind) {
      case 'GLIDE': case 'LADDER': return pctOf(i => { const g = guide(e, exTimes[i] % e.roundSeconds, tg); return g != null && Math.abs(12 * Math.log2(exFrames[i].f0 / g)) <= 1.5; });
      case 'HOLD': case 'TARGET': case 'MIMIC': return pctOf(i => Math.abs(exFrames[i].f0 - tg) <= tol);
      case 'READ': { const D = DIRS[dir]; return pctOf(i => exFrames[i].f0 >= D.goalLo && exFrames[i].f0 <= D.goalHi); }
      case 'RESONANCE': { const r = Math.floor(st()?.resonance || 0); return dir === 'ftm' ? 100 - r : r; }
      case 'WEIGHT': { const w = Math.floor(st()?.weight || 0); return dir === 'ftm' ? 100 - w : w; }
      case 'MELODY': return melodyScore(st()?.melody || 0, dir);
      case 'BASELINE': return Math.floor(median(v.map(i => exFrames[i].f0)));
      case 'FREE': return Math.min(100, Math.floor(v.length * fs / 30 * 100));
    }
    return 0;
  }
  const scoreText = (s) => ex().kind === 'BASELINE' ? `${s} Hz` : (ex().kind === 'RESONANCE' || ex().kind === 'WEIGHT') ? String(s) : `${s} %`;

  function stop() {
    if (phase !== 'RUNNING') return;
    analyzer?.stop(); analyzer = null; clipPlayer?.stop(); clipPlayer = null; clearTimeout(timer);
    phase = 'DONE';
    const e = ex();
    const secs = e.kind === 'TIMER' ? (Date.now() - timerStart) / 1000 : t;
    session.activeSeconds += secs;
    const s = score(); tScore.v.textContent = scoreText(s);
    let detail = scoreText(s);
    let saveScore = s;
    if (e.plan) {
      const v = exFrames.filter(f => f.f0 > 0).map(f => f.f0);
      detail = e.kind === 'TIMER' || !v.length ? 'done' : `done · ${Math.round(median(v))} Hz`; saveScore = 100;
      tScore.v.textContent = v.length ? `${Math.round(median(v))} Hz` : '✓';
    }
    if (e.kind === 'BASELINE') { if (s > 0) saveSettings({ baseline: s }); detail = `comfortable pitch ${s} Hz`; }
    if (e.kind === 'TIMER' || exFrames.some(f => f.f0 > 0)) session.results.push({ id: e.id, score: e.kind === 'BASELINE' ? 100 : saveScore, seconds: secs, detail });
    how.textContent = e.kind === 'BASELINE' ? `Your comfortable pitch right now is about ${s} Hz. Your target is ${Math.round(set.target)} Hz — move toward it in small steps.`
      : e.kind === 'TIMER' ? 'Done. Nice and calm.' : `Done — ${tScore.l.textContent.toLowerCase()} ${scoreText(s)}.`;
    mainBtn.textContent = index < exercises.length - 1 ? 'Next exercise' : 'Finish';
  }
  function next() { if (phase === 'RUNNING') stop(); if (index < exercises.length - 1) { index++; load(); } else finish(); }
  function finish() {
    if (!session.results.length) { go('#/exercises'); return; }
    const planName = set.plan && session.results.some(r => r.id.startsWith('plan_')) ? set.plan.name : null;
    setPending(session.build(isRoutine ? exercises.length : 0, planName)); go('#/report', true);
  }
  function leave() { if (phase === 'RUNNING') stop(); finish(); }
  onLeave(() => { if (phase === 'RUNNING') { analyzer?.stop(); clipPlayer?.stop(); clearTimeout(timer); } });
}

/** Melody score 0..100: MtF rewards variation (4 st = 100), FtM rewards a steady line (1 st = 100, 4 st = 0). */
export function melodyScore(sd, dir) {
  return dir === 'ftm' ? Math.max(0, Math.min(100, Math.round((4 - sd) / 3 * 100))) : Math.min(100, Math.floor(sd / 4 * 100));
}
