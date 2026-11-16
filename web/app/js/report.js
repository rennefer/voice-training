// Session report with playback, notes and feeling; save (+ MP3 of the session) and PDF; celebration pop-up.
import { el, header, fmtTime, icons, modal, toast, confirmBox, share } from './ui.js';
import { sessions, saveSession, deleteSession, getSession, saveAudio, getAudio, pending, setPending, S } from './store.js';
import { Scores } from './engine.js';
import { histogram, traceView } from './views.js';
import { byId, sessionLabel, goalPct, DIRS } from './exercises.js';
import { Scoring } from './scoring.js';
import { join, encodeMp3, playSamples, audioContext, pling } from './audio.js';
import { go, onLeave } from './main.js';
import { pdfReport } from './pdf.js';

const dateFmt = new Intl.DateTimeFormat(undefined, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });

export async function reportScreen(root, idStr) {
  const id = idStr ? Number(idStr) : 0;
  const s = id ? await getSession(id) : pending;
  if (!s) { go('#/voice', true); return; }
  let saved = !!id;
  const st = s.stats;
  const all = await sessions();
  const dir = s.dir || 'mtf', D = DIRS[dir];
  const prev = [...all].reverse().find(x => x.id < s.id && x.stats && (x.dir || 'mtf') === dir)?.stats;

  root.append(header('Session report', null, () => go(saved ? '#/progress' : '#/voice')),
    el('div.small.soft', { style: { paddingLeft: '58px', marginTop: '-8px' } }, `${dateFmt.format(new Date(s.id))} · ${sessionLabel(s)} · ${fmtTime(s.seconds)} · ${D.short}`));

  await playback(root, s, saved);

  if (!st) root.append(el('div.soft', 'Not enough voice was measured for a full analysis.'));
  else {
    const delta = (v, p, unit, d = 0) => p == null ? 'first session' : `${v - p >= 0 ? '+' : ''}${(v - p).toFixed(d)}${unit} vs last`;
    const tile = (l, v, sub) => el('div.tile', el('div.small.soft', l), el('div.v', { style: { fontSize: '28px' } }, v), el('div.s', sub));
    root.append(el('div.tiles',
      tile('Median pitch', `${Math.round(st.medianF0)} Hz`, `${Scores.noteName(st.medianF0)} · ${delta(st.medianF0, prev?.medianF0, ' Hz')}`),
      tile(`In ${D.goalName} range`, `${Math.round(goalPct(st, dir))} %`, delta(goalPct(st, dir), prev ? goalPct(prev, dir) : null, ' %')),
      tile('Resonance', `${Math.round(st.resonance)}`, (st.resonance >= 50 ? 'bright side · ' : 'dark side · ') + delta(st.resonance, prev?.resonance, '')),
      tile('Vocal weight', `${Math.round(st.weight)}`, (st.weight >= 50 ? 'lighter · ' : 'heavier · ') + delta(st.weight, prev?.weight, ''))));
    root.append(el('div.panel.col',
      el('div.row', el('div.grow', { style: { fontWeight: 500, fontSize: '13px' } }, 'Where your pitch was'), el('div.small.soft', 'share of time')),
      histogram(st.hist),
      el('div.row.tiny', { style: { gap: '14px' } }, el('span.brass-text', '■ female 165–255'), el('span', { style: { color: 'var(--male)' } }, '■ male 85–155'), el('span.soft', '■ between'))));
    const line = (k, v, n) => el('div.kv', el('div.k', k), el('div', v), el('div.n', n));
    root.append(el('div.panel', { style: { padding: '4px 14px' } },
      line('Pitch range', `${Math.round(st.lowF0)}–${Math.round(st.highF0)} Hz`, `${Scores.noteName(st.lowF0)}–${Scores.noteName(st.highF0)}`),
      dir === 'ftm' ? line('In female range', `${Math.round(st.pctFemale)} %`, '165–255 Hz') : line('In male range', `${Math.round(st.pctMale)} %`, '85–155 Hz'),
      line('On target', `${Math.round(st.pctOnTarget)} %`, `${Math.round(s.target)} ±${Math.round(s.tolerance)}`),
      line('Melody (variation)', `${st.melody.toFixed(1)} semitones`, dir === 'ftm' ? (st.melody <= 2 ? 'steady' : st.melody < 3 ? 'some movement' : 'lively') : (st.melody < 1.5 ? 'flat' : st.melody < 2.5 ? 'calm' : 'lively')),
      line('Formants F1 / F2', `${Math.round(st.f1)} / ${Math.round(st.f2)} Hz`, 'median'),
      line('Volume', `≈ ${Math.round(st.volumeDb)} dB`, 'approx.'),
      line('Breathiness (HNR)', `${st.hnr.toFixed(0)} dB`, st.breathiness < 35 ? 'clear' : st.breathiness < 65 ? 'some air' : 'breathy'),
      line('Voice time', fmtTime(st.voicedSeconds), '')));
  }
  if (s.exercises.length) root.append(el('div.panel', { style: { padding: '10px 14px' } }, el('div', { style: { fontWeight: 500, fontSize: '13px', marginBottom: '4px' } }, 'Exercises'),
    ...s.exercises.map(r => el('div.kv', el('div.k', byId(r.id)?.name || r.id), el('div.n', { style: { fontSize: '13px' } }, r.detail)))));

  const feelRow = el('div.row', { style: { gap: '4px' } });
  const renderFeel = () => feelRow.replaceChildren(...['Easy', 'OK', 'Strained'].map(f => el('button.chip' + (s.feeling === f ? '.on' : ''), { onclick: () => { s.feeling = s.feeling === f ? '' : f; renderFeel(); } }, f)));
  renderFeel();
  const notes = el('textarea', { rows: 3, placeholder: 'Notes — how your voice felt, what worked, what to try next', 'aria-label': 'Notes' });
  notes.value = s.notes || '';
  root.append(el('div.panel.col', el('div.row', el('div.grow', { style: { fontWeight: 500, fontSize: '13px' } }, 'How did it feel?'), feelRow), el('div.small.soft', 'Notes'), notes));
  root.append(el('div.tiny.soft', 'Measured with the phone microphone. Resonance and weight are estimates — compare sessions recorded the same way.'));

  const saveBtn = el('button.btn.grow', { onclick: save }, saved ? 'Save changes' : 'Save session');
  root.append(el('div.row', el('button.ghost.grow', { style: { minHeight: '52px' }, onclick: pdf }, 'PDF report'), saveBtn));
  if (saved) root.append(el('button.ghost', { onclick: () => confirmBox("Delete this session? It can't be undone.", 'Delete', async () => { await deleteSession(s.id); go('#/progress', true); }) }, 'Delete session'));

  async function save() {
    s.notes = notes.value.trim();
    const before = snapshot(new Scoring(await sessions()));
    await saveSession(s);
    if (!saved) {
      saved = true;
      if (s.pcm) {
        saveBtn.textContent = 'Saving recording…';
        try {
          const n = s.pcm.reduce((a, c) => a + c.length, 0), f = new Float32Array(n); let o = 0;
          for (const c of s.pcm) { for (let i = 0; i < c.length; i++) f[o + i] = c[i] / 32768; o += c.length; }
          await saveAudio(s.id, await encodeMp3(f, s.rate, 64));
        } catch (_) {}
      }
      setPending(null);
    }
    saveBtn.textContent = 'Saved ✓'; toast('Session saved');
    await celebrate(before);
  }
  async function pdf() {
    s.notes = notes.value.trim();
    try { const file = await pdfReport(s, await sessions()); share([file], 'Voice report'); }
    catch (e) { toast('Could not make the PDF: ' + e.message, 4000); }
  }
}

async function playback(root, s, saved) {
  const timeText = el('div.small.soft.mono');
  const panel = el('div.panel.col', el('div.row', el('div.grow', { style: { fontWeight: 500, fontSize: '13px' } }, 'Playback'), timeText));
  root.append(panel);
  // Source: in-memory PCM for an unsaved session, MP3 from IndexedDB for a saved one.
  let samples = null, rate = 48000;
  const load = async () => {
    if (samples) return true;
    if (s.pcm) { const n = s.pcm.reduce((a, c) => a + c.length, 0); samples = new Float32Array(n); let o = 0; for (const c of s.pcm) { for (let i = 0; i < c.length; i++) samples[o + i] = c[i] / 32768; o += c.length; } rate = s.rate; return true; }
    const blob = saved ? await getAudio(s.id) : null;
    if (!blob) return false;
    const buf = await audioContext().decodeAudioData(await blob.arrayBuffer());
    samples = buf.getChannelData(0); rate = buf.sampleRate; return true;
  };
  const hasAudio = s.pcm || (saved && await getAudio(s.id));
  if (!hasAudio) { panel.append(el('div.small.soft', 'No recording kept for this session.')); return; }
  const total = s.pcm ? s.pcm.reduce((a, c) => a + c.length, 0) / s.rate : s.trace.length * s.traceSec;
  let player = null, raf = 0, from = 0, gen = 0;
  const btn = el('button.round.metal', { style: { width: '48px', height: '48px' }, 'aria-label': 'Play recording', html: icons.play, onclick: () => player ? pause() : play(trace.st?.pos ?? from) });
  const trace = traceView(s.trace, s.traceSec, s.target, (t) => { from = t; update(t); if (player) play(t); });
  panel.append(el('div.row', btn, el('div.grow', trace)));
  const update = (t) => { trace.setPos(t); timeText.textContent = `${fmtTime(t)} / ${fmtTime(total)}`; };
  update(0);
  async function play(t) {
    if (!(await load())) return;
    stopAudio(); const my = ++gen;
    from = t >= total - 0.2 ? 0 : t;
    player = playSamples(samples, rate, Math.floor(from * rate), samples.length, () => { if (gen === my) done(); });
    btn.innerHTML = icons.pause; btn.setAttribute('aria-label', 'Pause');
    const tick = () => { if (!player || gen !== my) return; update(from + player.position()); raf = requestAnimationFrame(tick); };
    tick();
  }
  function stopAudio() { cancelAnimationFrame(raf); player?.stop(); player = null; }
  function pause() { if (player) from = from + player.position(); stopAudio(); btn.innerHTML = icons.play; btn.setAttribute('aria-label', 'Play recording'); }
  function done() { stopAudio(); from = 0; btn.innerHTML = icons.play; update(0); }
  onLeave(stopAudio);
}

// ---------------------------------------------------------------- celebration

export function snapshot(sc) { return { ids: new Set(sc.achievements().filter(a => a.unlockedAt > 0).map(a => a.id)), step: sc.currentStepIndex() }; }

export async function celebrate(before) {
  const sc = new Scoring(await sessions());
  const fresh = sc.achievements().filter(a => a.unlockedAt > 0 && !before.ids.has(a.id));
  const step = sc.currentStepIndex(), steps = sc.steps(), newStep = step > before.step;
  if (!fresh.length && !newStep) return;
  pling();
  const title = fresh.length > 1 ? `${fresh.length} achievements unlocked!` : fresh.length === 1 ? 'Achievement unlocked!' : 'New training step!';
  const st = steps[Math.min(step, steps.length - 1)];
  modal(el('div.col', { style: { alignItems: 'stretch', gap: '8px' } },
    el('div.medal', '★'),
    el('div.h2', { style: { textAlign: 'center', marginTop: '6px' } }, title),
    ...fresh.map(a => el('div.panel', el('div', { style: { color: '#EFE1BB', fontWeight: 500 } }, a.name), el('div.small.soft', a.desc))),
    newStep ? el('div', { style: { background: 'var(--metal)', color: 'var(--on-brass)', borderRadius: '14px', padding: '10px 14px' } },
      el('div.caps', { style: { color: 'var(--on-brass)' } }, `Step ${step + 1} of 4 reached`), el('div', { style: { fontWeight: 500, fontSize: '16px' } }, st.title), el('div.small', st.goal)) : null),
    [{ label: 'Nice!', primary: true }]);
}
