// A4 PDF of one session with jsPDF (same layout as PdfReport.kt). Units: pt.
import { Scores, Ranges } from './engine.js';
import { byId, sessionLabel, goalPct, DIRS, guide } from './exercises.js';

let loaded = null;
function loadJsPdf() {
  if (window.jspdf) return Promise.resolve();
  if (!loaded) loaded = new Promise((res, rej) => {
    const s = document.createElement('script'); s.src = new URL('../vendor/jspdf.umd.min.js', import.meta.url).href;
    s.onload = res; s.onerror = () => rej(new Error('PDF library missing')); document.head.append(s);
  });
  return loaded;
}

// jsPDF's built-in fonts are WinAnsi: swap the few characters they lack.
const clean = (t) => t.replace(/≈/g, '~').replace(/→/g, '->').replace(/\u2009/g, ' ');
const INK = [22, 32, 43], SOFT = [74, 88, 102], BRASS = [122, 92, 46], RULE = [184, 153, 95], LINE = [227, 230, 234], FEM = [243, 235, 217], MALE = [228, 236, 244];

export async function pdfReport(s, all) {
  await loadJsPdf();
  const doc = new window.jspdf.jsPDF({ unit: 'pt', format: 'a4' });
  const W = 595, H = 842, x0 = 48, x1 = W - 48;
  const st = s.stats;
  const dir = s.dir || 'mtf', D = DIRS[dir];
  const prev = [...all].reverse().find(a => a.id < s.id && a.stats && (a.dir || 'mtf') === dir)?.stats;
  const txt = (t, x, y, size, color = INK, bold = false, align = 'left') => { doc.setFont('helvetica', bold ? 'bold' : 'normal'); doc.setFontSize(size); doc.setTextColor(...color); doc.text(clean(String(t)), x, y, { align }); };
  const rect = (x, y, w, h, color) => { doc.setFillColor(...color); doc.rect(x, y, w, h, 'F'); };
  const two = (n) => String(n).padStart(2, '0');
  const fmt = (sec) => `${Math.floor(sec / 60)}:${two(Math.floor(sec % 60))}`;
  let y = 52;

  txt(`RECORDER · VOICE TRAINING · ${D.label.toUpperCase()}`, x0, y, 8, BRASS, true);
  txt('Session report', x0, y + 26, 20, INK, true);
  const when = new Intl.DateTimeFormat(undefined, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(s.id));
  txt(when, x1, y + 10, 9.5, SOFT, false, 'right');
  txt(`${sessionLabel(s)} · ${fmt(s.seconds)}`, x1, y + 24, 9.5, SOFT, false, 'right');
  y += 38; rect(x0, y, x1 - x0, 2, RULE); y += 16;

  const name = () => { const d = new Date(s.id); return `voice_report_${d.getFullYear()}${two(d.getMonth() + 1)}${two(d.getDate())}_${two(d.getHours())}${two(d.getMinutes())}.pdf`; };
  if (!st) { txt('Not enough voice was measured for a full analysis.', x0, y + 14, 11, SOFT); return new File([doc.output('blob')], name(), { type: 'application/pdf' }); }

  const d = (v, p, unit) => p == null ? 'first session' : `${v - p >= 0 ? '+' : ''}${(v - p).toFixed(0)}${unit}`;
  const tiles = [
    ['Median pitch', `${Math.round(st.medianF0)} Hz`, `${Scores.noteName(st.medianF0)} · ${d(st.medianF0, prev?.medianF0, ' Hz')}`],
    [`In ${D.goalName} range`, `${Math.round(goalPct(st, dir))} %`, d(goalPct(st, dir), prev ? goalPct(prev, dir) : null, ' %')],
    ['Resonance', `${Math.round(st.resonance)} / 100`, d(st.resonance, prev?.resonance, '')],
    ['Vocal weight', `${Math.round(st.weight)} / 100`, (st.weight >= 50 ? 'lighter · ' : 'heavier · ') + d(st.weight, prev?.weight, '')],
  ];
  const tw = (x1 - x0 - 30) / 4;
  doc.setDrawColor(...LINE); doc.setLineWidth(1);
  tiles.forEach(([l, v, sub], i) => {
    const bx = x0 + i * (tw + 10);
    doc.roundedRect(bx, y, tw, 58, 6, 6, 'S');
    txt(l, bx + 9, y + 15, 8, SOFT); txt(v, bx + 9, y + 36, 16, INK); txt(sub, bx + 9, y + 50, 7.5, BRASS);
  });
  y += 74;

  const cw = (x1 - x0 - 18) / 2, ch = 130;
  txt('Pitch over the session', x0, y, 10.5, INK, true);
  txt('Median pitch, last sessions', x0 + cw + 18, y, 10.5, INK, true);
  y += 8;
  const bands = (r, lo, hi, target) => {
    const yy = (v) => Math.max(r.y, Math.min(r.y + r.h, r.y + r.h - (v - lo) / (hi - lo) * r.h));
    rect(r.x, yy(Ranges.FEMALE_HI), r.w, yy(Ranges.FEMALE_LO) - yy(Ranges.FEMALE_HI), FEM);
    rect(r.x, yy(Ranges.MALE_HI), r.w, yy(Ranges.MALE_LO) - yy(Ranges.MALE_HI), MALE);
    for (let v = (Math.floor(lo / 20) + 1) * 20; v < hi; v += 20) { txt(v, r.x - 3, yy(v) + 3, 7, SOFT, false, 'right'); rect(r.x, yy(v), r.w, 0.4, [210, 210, 210]); }
    if (target >= lo && target <= hi) rect(r.x, yy(target) - 0.6, r.w, 1.2, RULE);
    txt('female', r.x + r.w - 30, yy(Ranges.FEMALE_HI) + 9, 7, BRASS);
  };
  const line = (pts, color, width) => {
    doc.setDrawColor(...color); doc.setLineWidth(width);
    let prevPt = null;
    for (const p of pts) { if (p && prevPt) doc.line(prevPt[0], prevPt[1], p[0], p[1]); prevPt = p; }
  };
  { // trace
    const r = { x: x0, y, w: cw, h: ch }, v = s.trace.filter(f => f > 0);
    const lo = Math.max(60, Math.min(v.length ? Math.min(...v) : 120, s.target) - 20), hi = Math.max(v.length ? Math.max(...v) : 260, s.target) + 20;
    bands(r, lo, hi, s.target);
    const n = Math.max(1, s.trace.length - 1);
    line(s.trace.map((f, i) => f > 0 ? [r.x + i * r.w / n, r.y + r.h - (f - lo) / (hi - lo) * r.h] : null), BRASS, 1.2);
  }
  { // trend
    const r = { x: x0 + cw + 18, y, w: cw, h: ch };
    let meds = all.filter(a => a.id <= s.id && a.stats && (a.dir || 'mtf') === dir).slice(-14).map(a => a.stats.medianF0);
    if (!all.some(a => a.id === s.id)) meds = [...meds, st.medianF0].slice(-14);
    const lo = Math.max(60, Math.min(Math.min(...meds), s.target) - 15), hi = Math.max(Math.max(...meds), s.target) + 15;
    bands(r, lo, hi, s.target);
    const px = (i) => meds.length === 1 ? r.x + r.w / 2 : r.x + 6 + i * (r.w - 12) / (meds.length - 1), py = (f) => r.y + r.h - (f - lo) / (hi - lo) * r.h;
    line(meds.map((f, i) => [px(i), py(f)]), INK, 1.2);
    doc.setFillColor(...RULE); meds.forEach((f, i) => doc.circle(px(i), py(f), 2.4, 'F'));
  }
  y += ch + 22;

  txt('Measurements', x0, y, 10.5, INK, true); y += 6;
  const cols = [x0, x0 + 190, x0 + 320, x0 + 430];
  const tr = (a, b, c, e, head = false) => {
    y += 17;
    txt(a, cols[0], y, 9, SOFT, head); txt(b, cols[1], y, 9, head ? SOFT : INK, head); txt(c, cols[2], y, 9, SOFT, head); txt(e, cols[3], y, 9, head ? SOFT : BRASS, head);
    rect(x0, y + 5, x1 - x0, 0.6, [237, 240, 242]);
  };
  const chg = (v, p, unit, dp = 0) => p == null ? '–' : `${v - p >= 0 ? '+' : ''}${(v - p).toFixed(dp)}${unit}`;
  const n = Scores.noteName;
  tr('Measure', 'This session', 'Last session', 'Change', true);
  tr('Median pitch', `${Math.round(st.medianF0)} Hz (${n(st.medianF0)})`, prev ? `${Math.round(prev.medianF0)} Hz (${n(prev.medianF0)})` : '–', chg(st.medianF0, prev?.medianF0, ' Hz'));
  tr('Pitch range', `${Math.round(st.lowF0)}–${Math.round(st.highF0)} Hz (${n(st.lowF0)}–${n(st.highF0)})`, prev ? `${Math.round(prev.lowF0)}–${Math.round(prev.highF0)} Hz` : '–', '');
  tr('Time in female / male range', `${Math.round(st.pctFemale)} % / ${Math.round(st.pctMale)} %`, prev ? `${Math.round(prev.pctFemale)} % / ${Math.round(prev.pctMale)} %` : '–', chg(goalPct(st, dir), prev ? goalPct(prev, dir) : null, ` % ${D.goalName}`));
  tr(`On target (${Math.round(s.target)} ±${Math.round(s.tolerance)} Hz)`, `${Math.round(st.pctOnTarget)} %`, prev ? `${Math.round(prev.pctOnTarget)} %` : '–', chg(st.pctOnTarget, prev?.pctOnTarget, ' %'));
  tr('Melody (variation)', `${st.melody.toFixed(1)} semitones`, prev ? `${prev.melody.toFixed(1)} st` : '–', chg(st.melody, prev?.melody, ' st', 1));
  tr('Resonance', `${Math.round(st.resonance)} / 100`, prev ? `${Math.round(prev.resonance)}` : '–', chg(st.resonance, prev?.resonance, ''));
  tr('Formants F1 / F2', `${Math.round(st.f1)} / ${Math.round(st.f2)} Hz`, prev ? `${Math.round(prev.f1)} / ${Math.round(prev.f2)} Hz` : '–', chg(st.f2, prev?.f2, ' Hz F2'));
  tr('Vocal weight (H1*–H2*)', `${Math.round(st.weight)} / 100 (${(st.h1h2 || 0).toFixed(1)} dB)`, prev ? `${Math.round(prev.weight)}` : '–', chg(st.weight, prev?.weight, ''));
  tr('Volume', `≈ ${Math.round(st.volumeDb)} dB`, prev ? `≈ ${Math.round(prev.volumeDb)} dB` : '–', chg(st.volumeDb, prev?.volumeDb, ' dB'));
  tr('Breathiness (HNR)', `${st.hnr.toFixed(0)} dB`, prev ? `${prev.hnr.toFixed(0)} dB` : '–', '');
  y += 22;

  if ((s.notes || '').trim() || s.feeling || s.notePrompt) {
    doc.setFont('helvetica', 'normal'); doc.setFontSize(10);
    const q = s.notePrompt ? doc.splitTextToSize(clean(s.notePrompt), x1 - x0 - 20) : [];
    const lines = doc.splitTextToSize(clean((s.notes || '').trim() || '–'), x1 - x0 - 20);
    const bh = lines.length * 13 + q.length * 12 + (q.length ? 8 : 0) + 30;
    rect(x0, y, 3, bh, RULE); rect(x0 + 3, y, x1 - x0 - 3, bh, [250, 248, 243]);
    txt('Notes' + (s.feeling ? ` · felt ${s.feeling}` : ''), x0 + 12, y + 16, 10.5, INK, true);
    let ty = y + 32;
    if (q.length) { doc.setFont('helvetica', 'italic'); doc.setFontSize(9.5); doc.setTextColor(...BRASS); doc.text(q, x0 + 12, ty); ty += q.length * 12 + 8; }
    doc.setFont('helvetica', 'normal'); doc.setFontSize(10); doc.setTextColor(...INK); doc.text(lines, x0 + 12, ty);
    y += bh;
  }
  const footer = () => {
    rect(x0, H - 50, x1 - x0, 0.5, LINE);
    txt('Measured with the phone microphone. Resonance and vocal weight are estimates; compare sessions recorded the same way (same room, same distance).', x0, H - 36, 7, SOFT);
  };
  footer();

  // one small graph per exercise, two per row, continuing on new pages
  if (s.exercises.length) {
    y += 22;
    const gw = (x1 - x0 - 18) / 2, gh = 92, rowH = gh + 40;
    const head = () => { txt(`Exercises · ${s.exercises.length}`, x0, y, 10.5, INK, true); y += 10; };
    if (y + 14 + rowH > H - 60) { doc.addPage(); y = 52; footer(); }
    head();
    s.exercises.forEach((r, i) => {
      const col = i % 2;
      if (col === 0 && i > 0) y += rowH;
      if (col === 0 && y + rowH > H - 60) { doc.addPage(); y = 52; footer(); head(); }
      const bx = x0 + col * (gw + 18), ex = byId(r.id);
      txt(clean(ex?.name || r.id).slice(0, 48), bx, y + 12, 8.5, INK, true);
      txt(clean(r.detail || ''), bx + gw, y + 12, 8, BRASS, false, 'right');
      const tr = r.trace || [], v = tr.filter(f => f > 0);
      const box = { x: bx + 22, y: y + 18, w: gw - 22, h: gh };
      if (!v.length) {
        doc.setDrawColor(...LINE); doc.roundedRect(box.x, box.y, box.w, box.h, 4, 4, 'S');
        txt(ex?.kind === 'TIMER' ? `Timer · ${fmt(r.seconds)}` : 'No voice measured', box.x + box.w / 2, box.y + box.h / 2 + 3, 8.5, SOFT, false, 'center');
        return;
      }
      const guideAt = ex && ex.roundSeconds > 0 ? (t) => guide(ex, t, s.target) : null;
      const total = Math.max(r.traceSec, tr.length * r.traceSec);
      let lo = Math.min(Math.min(...v), s.target) - 20, hi = Math.max(Math.max(...v), s.target) + 20;
      if (guideAt) for (let t = 0; t <= total; t += total / 40) { const gz = guideAt(t); if (gz != null) { lo = Math.min(lo, gz - 15); hi = Math.max(hi, gz + 15); } }
      lo = Math.max(60, lo);
      bands(box, lo, hi, s.target);
      const px = (t) => box.x + t / total * box.w, py = (f) => box.y + box.h - (f - lo) / (hi - lo) * box.h;
      if (guideAt) {
        const pts = []; for (let t = 0; t <= total; t += Math.min(0.1, total / 150)) { const gz = guideAt(t); pts.push(gz == null ? null : [px(t), py(gz)]); }
        doc.setLineDashPattern([1.5, 2.5], 0); line(pts, RULE, 1.6); doc.setLineDashPattern([], 0);
      }
      line(tr.map((f, k) => f > 0 ? [px((k + 0.5) * r.traceSec), py(f)] : null), INK, 1);
      txt('0:00', box.x, box.y + box.h + 9, 7, SOFT); txt(fmt(total), box.x + box.w, box.y + box.h + 9, 7, SOFT, false, 'right');
    });
  }
  return new File([doc.output('blob')], name(), { type: 'application/pdf' });
}
