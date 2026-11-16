// "Today" (daily score dial, week, steps, achievements) and "Progress" (trends + session list).
import { el, header, fmtTime, tabs } from './ui.js';
import { sessions, S, dayKey, dayStart } from './store.js';
import { dial, bars, trendChart } from './views.js';
import { Scoring } from './scoring.js';
import { routineList, sessionLabel, dirOf, goalPct, DIRS } from './exercises.js';
import { go } from './main.js';

let achFilter = 2, range = 1;

export async function todayScreen(root) {
  const sc = new Scoring(await sessions()), set = S(), now = Date.now();
  const streak = sc.streak();
  root.append(header('Today', streak > 0 ? `${streak}-day streak` : null, () => go('#/voice')));
  const today = sc.dayScore(now), yesterday = sc.dayScore(now - 86_400_000);
  const d = dial(240, [el('div.caps', 'Daily score'), el('div', { style: { fontSize: '72px', fontWeight: 300, lineHeight: 1 } }, String(today?.total ?? 0)),
    el('div.soft', { style: { fontSize: '14px' } }, !today ? 'Train today to score' : yesterday ? `of 100 · ${today.total - yesterday.total >= 0 ? '+' : ''}${today.total - yesterday.total} vs yesterday` : 'of 100')]);
  d.set((today?.total ?? 0) / 100);
  root.append(d);

  const part = (name, pts, value) => el('div.row', { style: { padding: '4px 0' } }, el('div', { style: { width: '116px', fontSize: '13px' } }, name),
    el('div.progress.grow', { style: { height: '8px', borderRadius: '4px' } }, el('div', { style: { width: Math.max(0, Math.min(1, pts / 25)) * 100 + '%' } })),
    el('div.small.brass-text', { style: { width: '64px', textAlign: 'right' } }, value));
  const t = today;
  root.append(el('div.panel',
    part('Practice time', t?.minutesPts ?? 0, `${(t?.minutes ?? 0).toFixed(0)} min`),
    part('Routine done', t?.routinePts ?? 0, `${t?.routineDone ?? 0} / ${t?.routineSize ?? routineList(set).length}`),
    part('On target', t?.targetPts ?? 0, `${(t?.onTarget ?? 0).toFixed(0)} %`),
    part('Improvement', t?.improvePts ?? 0, t ? `${t.improvement >= 0 ? '+' : ''}${t.improvement.toFixed(0)} Hz` : '–')));

  const days = sc.weekDays(now), trained = sc.daysTrainedInWeek(now);
  const todayIdx = days.findIndex(x => dayKey(x) === dayKey(now));
  const scores = days.map(x => x > now + 43_200_000 ? null : sc.dayScore(x)?.total ?? null);
  const letters = days.map(x => new Intl.DateTimeFormat(undefined, { weekday: 'narrow' }).format(new Date(x)));
  const got = scores.filter(v => v != null);
  root.append(el('div.panel.col', el('div.row', el('div.grow', { style: { fontWeight: 500, fontSize: '13px' } }, 'This week'), el('div.brass-text', { style: { fontSize: '13px' } }, `${trained} of ${set.weeklyGoal} days goal`)),
    bars(scores, 90, letters, todayIdx, true),
    got.length ? el('div.small.soft', `Weekly average ${(got.reduce((a, b) => a + b, 0) / got.length).toFixed(0)} · best ${Math.max(...got)}`) : null));

  const steps = sc.steps(), cur = sc.currentStepIndex();
  const stepRow = (s, i) => {
    const dot = el('div', { style: { width: '26px', height: '26px', borderRadius: '50%', flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '12px',
      background: s.done ? 'var(--metal)' : 'none', color: s.done ? 'var(--on-brass)' : i === cur ? '#EFE1BB' : 'var(--soft)',
      border: s.done ? 'none' : `${i === cur ? 2 : 1}px solid ${i === cur ? 'var(--brass-t)' : 'rgba(255,255,255,.2)'}` } }, s.done ? '✓' : String(i + 1));
    const info = el('div.col.grow', { style: { gap: '2px' } },
      el('div', { style: { fontWeight: 500, fontSize: '14px', color: i <= cur ? 'var(--text)' : 'var(--soft)' } }, s.title),
      el('div.small', { style: { color: 'var(--softer)' } }, s.goal));
    if (i === cur && !s.done) {
      info.append(el('div.tiny.soft', { style: { marginTop: '4px' } }, `${s.count} of ${s.goals.length} goals:`));
      for (const [g, ok] of s.goals) info.append(el('div.small', { style: { color: ok ? 'var(--brass-t)' : '#C9D2DB' } }, (ok ? '✓  ' : '○  ') + g));
    }
    return el('div.row', { style: { alignItems: 'flex-start', gap: '12px' } }, dot, info);
  };
  root.append(el('div.panel.col', { style: { gap: '10px' } },
    el('div.row', el('div.grow', { style: { fontWeight: 500, fontSize: '13px' } }, 'Training steps'), el('div.small.brass-text', `Step ${cur + 1} of ${steps.length}`)),
    ...steps.map(stepRow)));

  const ach = sc.achievements();
  const weekStart = days[0] - 43_200_000, ds = dayStart(now);
  const achBox = el('div.list-gap');
  const chipRow = el('div.chips');
  const renderAch = () => {
    chipRow.replaceChildren(...['Today', 'This week', 'All'].map((l, i) => el('button.chip' + (achFilter === i ? '.on' : ''), { onclick: () => { achFilter = i; renderAch(); } }, l)));
    const shown = achFilter === 0 ? ach.filter(a => a.unlockedAt >= ds) : achFilter === 1 ? ach.filter(a => a.unlockedAt >= weekStart)
      : [...ach].sort((a, b) => (b.unlockedAt > 0) - (a.unlockedAt > 0) || b.unlockedAt - a.unlockedAt);
    const df = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short' });
    achBox.replaceChildren(...(shown.length ? [] : [el('div.soft', { style: { fontSize: '13px' } }, achFilter === 0 ? 'Nothing new today yet — keep going.' : 'Nothing new this week yet.')]),
      ...shown.map(a => { const on = a.unlockedAt > 0; return el('div.item', { style: { opacity: on ? 1 : 0.75 } },
        el('div', { style: { width: '36px', height: '36px', borderRadius: '50%', flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', background: on ? 'var(--metal)' : 'none', color: on ? 'var(--on-brass)' : 'var(--soft)', border: on ? 'none' : '1px solid rgba(255,255,255,.25)' } }, on ? a.icon : '·'),
        el('div.col.grow', { style: { gap: '2px' } }, el('div', { style: { fontWeight: 500, fontSize: '14px' } }, a.name), el('div.small.soft', a.desc)),
        el('div.tiny.brass-text', { style: { width: '84px', textAlign: 'right' } }, on ? df.format(new Date(a.unlockedAt)) : a.progress)); }));
  };
  renderAch();
  root.append(el('div.caps', { style: { marginTop: '8px' } }, `Achievements · ${ach.filter(a => a.unlockedAt > 0).length} of ${ach.length}`), chipRow, achBox,
    el('button.ghost', { style: { marginTop: '8px' }, onclick: () => go('#/progress') }, 'Progress over time'));
}

export async function progressScreen(root) {
  const all = await sessions(), sc = new Scoring(all), set = S();
  const streak = sc.streak();
  root.append(header('Progress', streak > 1 ? `${streak} days in a row` : null, () => go('#/today')));
  const body = el('div.col', { style: { gap: '10px' } });
  root.append(tabs(['Week', 'Month', 'All'], range, (i) => { range = i; render(); }), body);
  render();
  function render() {
    body.replaceChildren();
    const days = [7, 31, 100000][range], since = Date.now() - days * 86_400_000;
    const dir = dirOf(set), D = DIRS[dir];
    const list = all.filter(s => s.id >= since && s.stats && (s.dir || 'mtf') === dir);
    if (!list.length) { body.append(el('div.soft', { style: { marginTop: '12px' } }, `No saved ${D.short.toLowerCase()} training sessions in this period yet.` + ' Train on the Voice screen or run an exercise, then save the report.')); return; }
    const st = list.map(s => s.stats);
    body.append(el('div.panel.col',
      el('div.row', el('div.grow', { style: { fontWeight: 500, fontSize: '13px' } }, 'Median pitch'), el('div.brass-text', { style: { fontSize: '13px' } }, `${Math.round(st[0].medianF0)} → ${Math.round(st[st.length - 1].medianF0)} Hz`)),
      trendChart(st.map(x => x.medianF0), set.target),
      el('div.tiny.soft', `Dashed line = your target ${Math.round(set.target)} Hz · shaded = female and male ranges`)));
    const mini = (label, vals, fmt) => el('div.tile', el('div.small.soft', label), el('div.v', { style: { fontSize: '22px' } }, fmt(vals[vals.length - 1])), el('div', { style: { marginTop: '6px' } }, bars(vals.slice(-14), 40)));
    body.append(el('div.tiles',
      mini(`In ${D.goalName} range`, st.map(x => goalPct(x, dir)), v => `${Math.round(v)} %`),
      mini('Resonance', st.map(x => x.resonance), v => `${Math.round(v)}`),
      mini('Vocal weight', st.map(x => x.weight), v => `${Math.round(v)}`),
      mini('On target', st.map(x => x.pctOnTarget), v => `${Math.round(v)} %`)));
    body.append(el('div.caps', { style: { marginTop: '8px' } }, 'Sessions'));
    const fmt = new Intl.DateTimeFormat(undefined, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
    const box = el('div.list-gap');
    for (const s of [...list].reverse().slice(0, 40)) box.append(el('button.item', { style: { width: '100%', textAlign: 'left' }, onclick: () => go('#/report/' + s.id) },
      el('div.col.grow', { style: { gap: '2px' } }, el('div', { style: { fontWeight: 500, fontSize: '14px' } }, fmt.format(new Date(s.id))), el('div.small.soft', `${sessionLabel(s)} · ${fmtTime(s.seconds)}${(s.notes || '').trim() ? ' · notes' : ''}`)),
      el('div.brass-text', { style: { fontSize: '14px' } }, `${Math.round(s.stats.medianF0)} Hz`)));
    body.append(box);
  }
}
