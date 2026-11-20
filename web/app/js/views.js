// Canvas charts and meters — ports of VoiceViews.kt.
import { el, canvasView, drawDial, metalGradient } from './ui.js';
import { Ranges, Scores, HIST_LO } from './engine.js';

const FEMALE_FILL = 'rgba(200,169,108,0.13)', MALE_FILL = 'rgba(120,160,200,0.13)';
const MALE_TEXT = '#9DB7D0', BRASS_LIGHT = '#EFE1BB', BRASS_MID = '#C8A96C', BRASS_DARK = '#9C7B43', SOFT = '#8FA1B0';
const MONO = "10px 'Roboto Mono', ui-monospace, Menlo, monospace";

/** Live pitch chart: line every 10 Hz, male/female bands, target zone, optional guide. */
/**
 * Pitch chart: line every 10 Hz, male/female bands, target zone, optional guide.
 * Live mode keeps the whole history: drag sideways to scroll back, tap to read a point,
 * "Live" jumps back to following. The zoom button cycles 6 s / 20 s / 60 s.
 */
export function pitchChart(height = 250) {
  const st = { lo: 120, hi: 280, target: 200, tolerance: 10, window: 6, roundSeconds: 0, guide: null, idleText: null, ts: [], fs: [], now: 0,
    follow: true, viewEnd: 0, cursor: -1 };
  const gutter = 36, WINDOWS = [6, 20, 60];
  const end = () => st.follow ? Math.max(st.now, st.window) : st.viewEnd;
  const c = canvasView(height, (g, w, h) => {
    const y = (hz) => h - (hz - st.lo) / (st.hi - st.lo) * h;
    const t1 = end(), t0 = t1 - st.window;
    const x = (t) => st.roundSeconds > 0 ? gutter + t / st.roundSeconds * (w - gutter) : gutter + (t - t0) / st.window * (w - gutter);
    const cl = (v) => Math.max(0, Math.min(h, v));
    g.fillStyle = FEMALE_FILL; g.fillRect(0, cl(y(Ranges.FEMALE_HI)), w, cl(y(Ranges.FEMALE_LO)) - cl(y(Ranges.FEMALE_HI)));
    g.fillStyle = MALE_FILL; g.fillRect(0, cl(y(Ranges.MALE_HI)), w, cl(y(Ranges.MALE_LO)) - cl(y(Ranges.MALE_HI)));
    g.font = "11px system-ui, sans-serif"; g.textAlign = 'right';
    if (y(Ranges.FEMALE_HI) > -4) { g.fillStyle = BRASS_MID; g.fillText('female', w - 6, y(Ranges.FEMALE_HI) + 13); }
    if (y(Ranges.MALE_HI) < h - 14) { g.fillStyle = MALE_TEXT; g.fillText('male', w - 6, y(Ranges.MALE_HI) + 13); }
    g.fillStyle = 'rgba(239,225,187,0.16)'; g.fillRect(gutter, y(st.target + st.tolerance), w - gutter, y(st.target - st.tolerance) - y(st.target + st.tolerance));
    g.strokeStyle = BRASS_LIGHT; g.lineWidth = 2; g.setLineDash([6, 5]);
    g.beginPath(); g.moveTo(gutter, y(st.target)); g.lineTo(w, y(st.target)); g.stroke(); g.setLineDash([]);
    g.font = MONO; g.textAlign = 'left'; g.lineWidth = 1;
    for (let v = Math.floor(st.lo / 10) * 10 + 10; v < st.hi; v += 10) {
      const major = v % 20 === 0, hundred = v % 100 === 0;
      g.strokeStyle = hundred ? 'rgba(255,255,255,0.16)' : major ? 'rgba(255,255,255,0.09)' : 'rgba(255,255,255,0.04)';
      const yy = Math.round(y(v)) + 0.5;
      g.beginPath(); g.moveTo(major ? gutter - 4 : gutter, yy); g.lineTo(w, yy); g.stroke();
      if (major) { g.fillStyle = hundred ? '#C9D2DB' : SOFT; g.fillText(String(v), 4, y(v) + 3.5); }
    }
    if (st.guide && st.roundSeconds > 0) {
      g.strokeStyle = 'rgba(214,187,133,0.75)'; g.lineWidth = 9; g.lineCap = 'round'; g.setLineDash([2, 9]);
      g.beginPath(); let started = false;
      for (let t = 0; t <= st.roundSeconds + 1e-6; t += st.roundSeconds / 120) {
        const hz = st.guide(t); if (hz == null) continue;
        if (!started) { g.moveTo(x(t), y(hz)); started = true; } else g.lineTo(x(t), y(hz));
      }
      g.stroke(); g.setLineDash([]);
    }
    // time ticks when looking back or zoomed out
    if (st.roundSeconds <= 0 && st.ts.length && (!st.follow || st.window > 6)) {
      const step = st.window <= 6 ? 1 : st.window <= 20 ? 5 : 10;
      g.font = MONO; g.textAlign = 'center'; g.fillStyle = SOFT;
      for (let t = Math.ceil(Math.max(0, t0) / step) * step; t <= t1; t += step) {
        const px = x(t); if (px < gutter + 12 || px > w - 12) continue;
        g.fillStyle = 'rgba(255,255,255,0.06)'; g.fillRect(px, 0, 1, h - 14);
        g.fillStyle = SOFT; g.fillText(fmtClock(t), px, h - 3);
      }
    }
    g.save(); g.beginPath(); g.rect(gutter, 0, w - gutter, h); g.clip();
    g.strokeStyle = BRASS_LIGHT; g.lineWidth = st.window > 20 ? 1.6 : 2.5; g.lineCap = 'round'; g.lineJoin = 'round';
    g.beginPath(); let pen = false, lastT = -1;
    // draw only the visible part (binary search for the first point)
    let i0 = 0;
    if (st.roundSeconds <= 0) { let lo = 0, hi = st.ts.length; while (lo < hi) { const m = (lo + hi) >> 1; if (st.ts[m] < t0 - 0.5) lo = m + 1; else hi = m; } i0 = lo; }
    for (let i = i0; i < st.ts.length; i++) {
      const f = st.fs[i], t = st.ts[i];
      if (st.roundSeconds <= 0 && t > t1 + 0.5) break;
      if (f <= 0 || (lastT >= 0 && t - lastT > 0.12)) pen = false;
      if (f > 0) { const px = x(t), py = y(Math.max(st.lo - 20, Math.min(st.hi + 20, f))); if (!pen) { g.moveTo(px, py); pen = true; } else g.lineTo(px, py); }
      lastT = t;
    }
    g.stroke();
    const n = st.fs.length;
    if (st.follow && n && st.fs[n - 1] > 0) { g.fillStyle = '#fff'; g.beginPath(); g.arc(x(st.ts[n - 1]), y(Math.max(st.lo, Math.min(st.hi, st.fs[n - 1]))), 6, 0, 7); g.fill(); }
    // cursor read-out
    if (!st.follow && st.cursor >= 0) {
      const k = nearest(st.cursor), px = x(st.cursor);
      g.fillStyle = 'rgba(255,255,255,0.8)'; g.fillRect(px - 0.75, 0, 1.5, h);
      const f = k >= 0 ? st.fs[k] : 0;
      if (f > 0) { g.fillStyle = '#fff'; g.beginPath(); g.arc(px, y(Math.max(st.lo, Math.min(st.hi, f))), 5, 0, 7); g.fill(); }
      const label = `${fmtClock(st.cursor)} · ${f > 0 ? Math.round(f) + ' Hz' : 'silence'}`;
      g.font = "600 12px system-ui, sans-serif"; const tw = g.measureText(label).width + 16;
      const bx = Math.max(gutter + 2, Math.min(w - tw - 2, px - tw / 2));
      g.fillStyle = 'rgba(13,20,28,0.9)'; roundRect(g, bx, 6, tw, 22, 11); g.fill();
      g.fillStyle = '#EFE1BB'; g.textAlign = 'left'; g.fillText(label, bx + 8, 21);
    }
    g.restore();
    if (!st.ts.length && st.idleText) { g.fillStyle = SOFT; g.font = "15px system-ui, sans-serif"; g.textAlign = 'center'; g.fillText(st.idleText, (gutter + w) / 2, h / 2); }
  }, 'chart');
  function nearest(t) {
    let lo = 0, hi = st.ts.length - 1; if (hi < 0) return -1;
    while (lo < hi) { const m = (lo + hi) >> 1; if (st.ts[m] < t) lo = m + 1; else hi = m; }
    return Math.abs(st.ts[lo] - t) < 0.2 ? lo : -1;
  }

  // overlay buttons
  const liveBtn = el('button.chip.on', { style: { position: 'absolute', right: '8px', bottom: '8px', minHeight: '32px', display: 'none' }, 'aria-label': 'Back to live', onclick: () => goLive() }, 'Live ▸');
  const zoomBtn = el('button.chip', { style: { position: 'absolute', left: '42px', bottom: '8px', minHeight: '30px', fontSize: '12px', padding: '0 10px', background: 'rgba(13,20,28,.75)' }, 'aria-label': 'Change time window', onclick: () => {
    const i = (WINDOWS.indexOf(st.window) + 1) % WINDOWS.length; const keep = end();
    st.window = WINDOWS[i]; if (!st.follow) st.viewEnd = Math.max(st.window, Math.min(st.now, keep)); sync(); c.redraw();
  } });
  const wrap = el('div', { style: { position: 'relative' } }, c, liveBtn, zoomBtn);
  function sync() {
    const scroll = st.roundSeconds <= 0 && st.ts.length > 0;
    liveBtn.style.display = scroll && !st.follow ? '' : 'none';
    zoomBtn.style.display = scroll ? '' : 'none';
    zoomBtn.textContent = `↔ ${st.window} s`;
  }
  function goLive() { st.follow = true; st.cursor = -1; sync(); c.redraw(); }

  // drag to scroll back, tap to read a point
  let drag = null;
  c.addEventListener('pointerdown', (e) => {
    if (st.roundSeconds > 0 || !st.ts.length) return;
    c.setPointerCapture(e.pointerId);
    drag = { x: e.clientX, end: end(), moved: false };
  });
  c.addEventListener('pointermove', (e) => {
    if (!drag) return;
    const w = c.getBoundingClientRect().width - gutter, dx = e.clientX - drag.x;
    if (Math.abs(dx) > 6) drag.moved = true;
    if (!drag.moved) return;
    st.viewEnd = Math.max(st.window, Math.min(st.now, drag.end - dx / w * st.window));
    st.follow = false; st.cursor = -1;
    if (st.viewEnd >= st.now - 0.05 && dx < 0) st.follow = true;
    sync(); c.redraw();
  });
  c.addEventListener('pointerup', (e) => {
    if (!drag) return;
    if (!drag.moved) {
      const r = c.getBoundingClientRect(), px = e.clientX - r.left;
      if (px > gutter) { if (st.follow) { st.viewEnd = end(); st.follow = false; } st.cursor = (end() - st.window) + (px - gutter) / (r.width - gutter) * st.window; }
    }
    drag = null; sync(); c.redraw();
  });

  wrap.st = st; wrap.redraw = () => { sync(); c.redraw(); };
  wrap.clear = () => { st.ts = []; st.fs = []; st.now = 0; st.follow = true; st.cursor = -1; sync(); c.redraw(); };
  wrap.add = (t, f0) => {
    st.ts.push(t); st.fs.push(f0); st.now = t;
    if (st.roundSeconds <= 0 && st.ts.length > 200000) { st.ts.splice(0, 20000); st.fs.splice(0, 20000); }  // ~2.5 h cap
    if (st.ts.length === 1) sync();
    if (st.follow) c.redraw();
  };
  wrap.goLive = goLive;
  wrap.centerOn = (hz, span = 160) => { st.lo = Math.max(60, hz - span / 2); st.hi = st.lo + span; c.redraw(); };
  return wrap;
}

function fmtClock(t) { t = Math.max(0, Math.floor(t)); return `${Math.floor(t / 60)}:${String(t % 60).padStart(2, '0')}`; }

/** Segmented meter with name/value on top and left/right words. */
export function meter(name, left, right) {
  const st = { value: null, text: '–' };
  const c = canvasView(40, (g, w) => {
    g.textBaseline = 'alphabetic';
    g.font = "500 13px system-ui, sans-serif"; g.fillStyle = '#EEF2F5'; g.textAlign = 'left'; g.fillText(name, 0, 14);
    g.font = "13px system-ui, sans-serif"; g.fillStyle = '#D6BB85'; g.textAlign = 'right'; g.fillText(st.text, w, 14);
    g.font = "11px system-ui, sans-serif"; g.fillStyle = SOFT; g.textAlign = 'left'; g.fillText(left, 0, 35);
    g.textAlign = 'right'; g.fillText(right, w, 35);
    const n = 24, gap = 2, x0 = 46, x1 = w - 46, sw = (x1 - x0 - gap * (n - 1)) / n;
    const lit = st.value == null ? 0 : Math.round(Math.max(0, Math.min(1, st.value)) * n);
    for (let i = 0; i < n; i++) {
      g.fillStyle = st.value == null ? 'rgba(255,255,255,0.08)' : i < lit ? (i < n / 2 ? BRASS_DARK : BRASS_MID) : 'rgba(255,255,255,0.13)';
      roundRect(g, x0 + i * (sw + gap), 26, sw, 10, 2); g.fill();
    }
  });
  c.set = (value, text) => { st.value = value; st.text = text; c.redraw(); };
  return c;
}

/** 70–300 Hz strip with male/female ranges, target tick and current pitch marker. */
export function rangeBar() {
  const st = { hz: 0, target: 0 };
  const lo = 70, hi = 300;
  const c = canvasView(42, (g, w) => {
    const x = (f) => (f - lo) / (hi - lo) * w, h = 22;
    g.fillStyle = 'rgba(255,255,255,0.06)'; roundRect(g, 0, 0, w, h, 11); g.fill();
    g.save(); roundRect(g, 0, 0, w, h, 11); g.clip();
    g.fillStyle = 'rgba(120,160,200,0.38)'; g.fillRect(x(Ranges.MALE_LO), 0, x(Ranges.MALE_HI) - x(Ranges.MALE_LO), h);
    const gr = g.createLinearGradient(x(Ranges.FEMALE_LO), 0, x(Ranges.FEMALE_HI), 0);
    gr.addColorStop(0, 'rgba(156,123,67,0.7)'); gr.addColorStop(1, 'rgba(239,225,187,0.7)');
    g.fillStyle = gr; g.fillRect(x(Ranges.FEMALE_LO), 0, x(Ranges.FEMALE_HI) - x(Ranges.FEMALE_LO), h);
    if (st.target > 0) { g.fillStyle = '#1A140A'; g.fillRect(x(st.target) - 1, 0, 2, h); }
    if (st.hz > 0) { g.fillStyle = '#fff'; g.fillRect(x(Math.max(lo, Math.min(hi, st.hz))) - 1.5, 0, 3, h); }
    g.restore();
    g.font = "11px system-ui, sans-serif"; g.textAlign = 'left'; g.fillStyle = MALE_TEXT; g.fillText('Male 85–155', x(Ranges.MALE_LO), 38);
    g.textAlign = 'right'; g.fillStyle = '#D6BB85'; g.fillText('Female 165–255', x(Ranges.FEMALE_HI), 38);
  });
  c.set = (hz, target) => { if (hz != null) st.hz = hz; if (target != null) st.target = target; c.redraw(); };
  return c;
}

/** Share of voiced time per 10 Hz bin with note names ("200 G3"). */
export function histogram(hist) {
  const used = hist.map((v, i) => v > 0 ? i : -1).filter(i => i >= 0);
  const rows = [];
  if (used.length) for (let b = Math.min(hist.length - 1, used[used.length - 1] + 1); b >= Math.max(0, used[0] - 1); b--) rows.push(b);
  const rowH = 15;
  const c = canvasView(Math.max(20, rows.length * rowH), (g, w) => {
    const max = Math.max(1, ...hist), lw = 64;
    rows.forEach((b, i) => {
      const hz = HIST_LO + b * 10 + 5, top = i * rowH;
      const fem = hz >= Ranges.FEMALE_LO && hz <= Ranges.FEMALE_HI, male = hz <= Ranges.MALE_HI;
      if (fem || male) { g.fillStyle = fem ? FEMALE_FILL : MALE_FILL; roundRect(g, lw, top + 1, w - lw, rowH - 2, 3); g.fill(); }
      g.font = MONO; g.fillStyle = SOFT; g.textAlign = 'left'; g.fillText(`${hz - 5} ${Scores.noteName(hz)}`, 0, top + rowH - 4);
      const bw = (w - lw) * hist[b] / max;
      if (bw > 0) {
        if (fem) { const gr = g.createLinearGradient(lw, 0, lw + bw, 0); gr.addColorStop(0, BRASS_DARK); gr.addColorStop(1, BRASS_LIGHT); g.fillStyle = gr; }
        else g.fillStyle = male ? '#7FA2C4' : 'rgba(238,242,245,0.4)';
        roundRect(g, lw, top + 3, bw, rowH - 6, 3); g.fill();
      }
    });
  });
  return c;
}

/** Median Hz per session over the bands, target dashed. */
export function trendChart(values, target) {
  return canvasView(160, (g, w, h) => {
    const gx = 32, vs = values.filter(v => v > 0);
    let lo = Math.min(vs.length ? Math.min(...vs) : 150, target || 200) - 15;
    let hi = Math.max(vs.length ? Math.max(...vs) : 220, target || 200) + 15;
    lo = Math.floor(lo / 10) * 10; hi = (Math.floor(hi / 10) + 1) * 10;
    const y = (v) => 6 + (1 - (v - lo) / (hi - lo)) * (h - 12), cl = (v) => Math.max(0, Math.min(h, v));
    g.fillStyle = FEMALE_FILL; g.fillRect(gx, cl(y(Ranges.FEMALE_HI)), w - gx, cl(y(Ranges.FEMALE_LO)) - cl(y(Ranges.FEMALE_HI)));
    g.fillStyle = MALE_FILL; g.fillRect(gx, cl(y(Ranges.MALE_HI)), w - gx, cl(y(Ranges.MALE_LO)) - cl(y(Ranges.MALE_HI)));
    const step = hi - lo > 120 ? 40 : 20;
    g.font = MONO; g.textAlign = 'left';
    for (let v = Math.floor(lo / step) * step; v <= hi; v += step) if (v >= lo) {
      g.fillStyle = 'rgba(255,255,255,0.08)'; g.fillRect(gx, y(v), w - gx, 1);
      g.fillStyle = SOFT; g.fillText(String(v), 0, y(v) + 3.5);
    }
    if (target > 0) { g.strokeStyle = 'rgba(239,225,187,0.5)'; g.lineWidth = 1.5; g.setLineDash([6, 5]); g.beginPath(); g.moveTo(gx, y(target)); g.lineTo(w, y(target)); g.stroke(); g.setLineDash([]); }
    const n = values.length; if (!n) return;
    const x = (i) => n === 1 ? (gx + w) / 2 : gx + 6 + i * (w - gx - 12) / (n - 1);
    g.strokeStyle = BRASS_MID; g.lineWidth = 2; g.beginPath(); let s = false;
    values.forEach((f, i) => { if (f > 0) { if (!s) { g.moveTo(x(i), y(f)); s = true; } else g.lineTo(x(i), y(f)); } });
    g.stroke();
    values.forEach((f, i) => { if (f > 0) { g.fillStyle = i === n - 1 ? '#fff' : BRASS_LIGHT; g.beginPath(); g.arc(x(i), y(f), 3.5, 0, 7); g.fill(); } });
  });
}

/** Vertical bars 0..100. */
export function bars(values, height, labels = null, highlight = -1, showValues = false) {
  return canvasView(height, (g, w, h) => {
    const n = values.length; if (!n) return;
    const gap = n > 10 ? 3 : 6, bw = (w - gap * (n - 1)) / n;
    const top = showValues ? 14 : 0, bottom = h - (labels ? 16 : 0), hl = highlight >= 0 ? highlight : n - 1;
    values.forEach((v, i) => {
      const x = i * (bw + gap);
      const hgt = (v == null || v <= 0) ? 3 : Math.max(3, (bottom - top) * Math.min(1, v / 100));
      if (v == null || v <= 0) g.fillStyle = 'rgba(255,255,255,0.12)';
      else if (i === hl) { const gr = g.createLinearGradient(0, bottom - hgt, 0, bottom); gr.addColorStop(0, BRASS_LIGHT); gr.addColorStop(1, BRASS_MID); g.fillStyle = gr; }
      else g.fillStyle = BRASS_DARK;
      roundRect(g, x, bottom - hgt, bw, hgt, 2.5); g.fill();
      g.textAlign = 'center';
      if (showValues && v > 0) { g.font = "10px system-ui"; g.fillStyle = '#C9D2DB'; g.fillText(String(Math.round(v)), x + bw / 2, bottom - hgt - 3); }
      if (labels && labels[i] != null) { g.font = "11px system-ui"; g.fillStyle = i === hl ? BRASS_LIGHT : SOFT; g.fillText(labels[i], x + bw / 2, h - 3); }
    });
  });
}

/** FineVolume-style dial with content in the middle. */
export function dial(size, inner) {
  const wrap = el('div', { style: { position: 'relative', width: size + 'px', height: size + 'px', alignSelf: 'center', flexShrink: '0' } });
  const st = { v: 0 };
  const c = canvasView(size, (g, w, h) => drawDial(g, w, h, st.v));
  c.style.width = size + 'px';
  const mid = el('div', { style: { position: 'absolute', inset: '0', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' } }, inner);
  wrap.append(c, mid);
  wrap.set = (v) => { st.v = Math.max(0, Math.min(1, v)); c.redraw(); };
  return wrap;
}

/** Small pitch graph for one exercise: bands, target zone, the exercise's guide line (if any) and your pitch. */
export function exerciseChart(trace, traceSec, target, tolerance, guideAt = null, height = 120) {
  const total = Math.max(traceSec, trace.length * traceSec);
  const v = trace.filter(f => f > 0);
  let lo = Math.min(v.length ? Math.min(...v) : target, target) - 20, hi = Math.max(v.length ? Math.max(...v) : target, target) + 20;
  if (guideAt) for (let t = 0; t <= total; t += total / 60) { const gz = guideAt(t); if (gz != null) { lo = Math.min(lo, gz - 15); hi = Math.max(hi, gz + 15); } }
  lo = Math.max(60, Math.floor(lo / 10) * 10); hi = Math.ceil(hi / 10) * 10; if (hi - lo < 60) hi = lo + 60;
  const gx = 30;
  return canvasView(height, (g, w, h) => {
    const y = (f) => 4 + (1 - (f - lo) / (hi - lo)) * (h - 18), x = (t) => gx + t / total * (w - gx - 4), cl = (q) => Math.max(0, Math.min(h - 14, q));
    g.fillStyle = FEMALE_FILL; g.fillRect(gx, cl(y(Ranges.FEMALE_HI)), w - gx, cl(y(Ranges.FEMALE_LO)) - cl(y(Ranges.FEMALE_HI)));
    g.fillStyle = MALE_FILL; g.fillRect(gx, cl(y(Ranges.MALE_HI)), w - gx, cl(y(Ranges.MALE_LO)) - cl(y(Ranges.MALE_HI)));
    g.fillStyle = 'rgba(239,225,187,0.14)'; g.fillRect(gx, y(target + tolerance), w - gx, y(target - tolerance) - y(target + tolerance));
    g.font = MONO; g.textAlign = 'left';
    const step = hi - lo > 120 ? 40 : 20;
    for (let f = Math.ceil(lo / step) * step; f <= hi; f += step) { g.fillStyle = 'rgba(255,255,255,0.07)'; g.fillRect(gx, y(f), w - gx, 1); g.fillStyle = SOFT; g.fillText(String(f), 0, y(f) + 3.5); }
    g.textAlign = 'right'; g.fillStyle = SOFT; g.fillText(fmtClock(total), w - 12, h - 3); g.textAlign = 'left'; g.fillText('0:00', gx + 4, h - 3);
    if (guideAt) {
      g.strokeStyle = 'rgba(214,187,133,0.7)'; g.lineWidth = 5; g.lineCap = 'round'; g.setLineDash([1.5, 6]); g.beginPath(); let s0 = false;
      for (let t = 0; t <= total; t += Math.min(0.1, total / 200)) { const gz = guideAt(t); if (gz == null) { s0 = false; continue; } if (!s0) { g.moveTo(x(t), y(gz)); s0 = true; } else g.lineTo(x(t), y(gz)); }
      g.stroke(); g.setLineDash([]);
    }
    g.strokeStyle = BRASS_LIGHT; g.lineWidth = 2; g.lineJoin = 'round'; g.beginPath(); let pen = false;
    trace.forEach((f, i) => { if (f <= 0) { pen = false; return; } const px = x((i + 0.5) * traceSec), py = y(f); if (!pen) { g.moveTo(px, py); pen = true; } else g.lineTo(px, py); });
    g.stroke();
  }, 'chart');
}

/** Whole-session pitch line with a playhead; tap or drag to seek. */
export function traceView(trace, traceSec, target, onSeek) {
  const total = trace.length * traceSec, st = { pos: 0 };
  const v = trace.filter(f => f > 0);
  const lo = Math.max(60, Math.min(v.length ? Math.min(...v) : 120, target) - 20);
  const hi = Math.max(v.length ? Math.max(...v) : 260, target) + 20;
  const c = canvasView(120, (g, w, h) => {
    const y = (f) => h - (f - lo) / (hi - lo) * h, x = (t) => total <= 0 ? 0 : t / total * w, cl = (q) => Math.max(0, Math.min(h, q));
    g.fillStyle = FEMALE_FILL; g.fillRect(0, cl(y(Ranges.FEMALE_HI)), w, cl(y(Ranges.FEMALE_LO)) - cl(y(Ranges.FEMALE_HI)));
    g.fillStyle = MALE_FILL; g.fillRect(0, cl(y(Ranges.MALE_HI)), w, cl(y(Ranges.MALE_LO)) - cl(y(Ranges.MALE_HI)));
    g.fillStyle = 'rgba(239,225,187,0.33)'; g.fillRect(0, y(target) - 1, w, 2);
    const path = new Path2D(); let pen = false;
    trace.forEach((f, i) => { if (f <= 0) { pen = false; return; } const px = x(i * traceSec), py = y(f); if (!pen) { path.moveTo(px, py); pen = true; } else path.lineTo(px, py); });
    g.strokeStyle = BRASS_MID; g.lineWidth = 1.8; g.stroke(path);
    g.save(); g.beginPath(); g.rect(0, 0, x(st.pos), h); g.clip(); g.strokeStyle = BRASS_LIGHT; g.lineWidth = 2.2; g.stroke(path); g.restore();
    g.fillStyle = '#fff'; g.fillRect(x(st.pos) - 1, 0, 2, h);
  }, 'chart');
  const seek = (e, fire) => {
    if (total <= 0) return;
    const r = c.getBoundingClientRect();
    st.pos = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)) * total; c.redraw();
    if (fire) onSeek?.(st.pos);
  };
  let down = false;
  c.addEventListener('pointerdown', (e) => { down = true; c.setPointerCapture(e.pointerId); seek(e, false); });
  c.addEventListener('pointermove', (e) => { if (down) seek(e, false); });
  c.addEventListener('pointerup', (e) => { if (down) { down = false; seek(e, true); } });
  c.setPos = (p) => { st.pos = p; c.redraw(); };
  c.total = total;
  return c;
}

export function roundRect(g, x, y, w, h, r) {
  r = Math.min(r, w / 2, h / 2);
  g.beginPath(); g.moveTo(x + r, y); g.arcTo(x + w, y, x + w, y + h, r); g.arcTo(x + w, y + h, x, y + h, r); g.arcTo(x, y + h, x, y, r); g.arcTo(x, y, x + w, y, r); g.closePath();
}
export { metalGradient };
