// Record tab: record from the mic (no limit), loudness dial, trim, even out volume, MP3 split into parts.
import { el, fmtTime, fmtTenths, icons, toast, confirmBox, share, mb, canvasView } from './ui.js';
import { S, saveSettings, get, put, del } from './store.js';
import { startMic, decodeFile, playSamples, encodeMp3 } from './audio.js';
import { dial } from './views.js';
import { topBar, onLeave } from './main.js';

const QUALITIES = [{ label: 'Small', kbps: 32, rate: 16000 }, { label: 'Voice', kbps: 64, rate: 22050 }, { label: 'Good', kbps: 96, rate: 44100 }, { label: 'Best', kbps: 128, rate: 44100 }];
const SPLITS = [null, 5, 10, 25, -1];      // Whole · 5 · 10 · 25 · Custom (MB = 1,000,000 bytes)
const FILL = 0.985, MAX_FRAME = 1441;

/** Samples (at `rate`) that fit in one part of limitBytes at kbps. */
export function samplesPerPart(kbps, limitBytes, rate) { return Math.floor((limitBytes * FILL - 2 * MAX_FRAME) / (kbps * 125) * rate); }
export function planParts(start, end, kbps, limitBytes, rate) {
  if (!limitBytes) return [[start, end]];
  const per = samplesPerPart(kbps, limitBytes, rate);
  if (per < rate) throw new Error('Part size too small for this quality');
  const out = []; for (let s = start; s < end; s += per) out.push([s, Math.min(end, s + per)]);
  return out;
}

/** "Even out volume" — feed-forward compressor with make-up gain and soft ceiling (Compressor.kt). */
export function compress(x, rate, { thresholdDb = -26, ratio = 3, attackMs = 5, releaseMs = 180, makeupDb = 9, ceiling = 0.89 } = {}) {
  const att = Math.exp(-1 / (rate * attackMs / 1000)), rel = Math.exp(-1 / (rate * releaseMs / 1000)), makeup = 10 ** (makeupDb / 20), slope = 1 - 1 / ratio;
  let env = 0, gain = makeup; const y = new Float32Array(x.length);
  for (let i = 0; i < x.length; i++) {
    const a = Math.abs(x[i]);
    env = a > env ? a + att * (env - a) : a + rel * (env - a);
    if ((i & 15) === 0) { const over = 20 * Math.log10(env + 1e-9) - thresholdDb; gain = 10 ** ((over > 0 ? -over * slope : 0) / 20) * makeup; }
    let v = x[i] * gain; const av = Math.abs(v);
    if (av > ceiling) { const soft = ceiling + (1 - ceiling) * Math.tanh((av - ceiling) / (1 - ceiling)); v = v > 0 ? soft : -soft; }
    y[i] = v;
  }
  return y;
}

async function resample(x, from, to) {
  if (from === to) return x;
  const OAC = window.OfflineAudioContext || window.webkitOfflineAudioContext;
  const len = Math.max(1, Math.round(x.length * to / from));
  const oc = new OAC(1, len, to);
  const b = oc.createBuffer(1, x.length, from); b.getChannelData(0).set(x);
  const src = oc.createBufferSource(); src.buffer = b; src.connect(oc.destination); src.start();
  return (await oc.startRendering()).getChannelData(0);
}

// ---------------------------------------------------------------- recording kept across reloads

let rec = null;   // { samples: Float32Array, rate, name, created }
async function loadRec() {
  if (rec) return rec;
  const r = await get('kv', 'recording');
  if (r) rec = { samples: new Float32Array(await r.pcm.arrayBuffer()), rate: r.rate, name: r.name, created: r.created };
  return rec;
}
function storeRec() { if (rec) put('kv', 'recording', { pcm: new Blob([rec.samples]), rate: rec.rate, name: rec.name, created: rec.created }).catch(() => toast('Could not keep the recording (storage full?)')); }

// ---------------------------------------------------------------- screen

export async function recordScreen(root) {
  const set = S();
  let mic = null, chunks = [], recSamples = 0, recStart = 0, timerIv = 0, level = 0, player = null, raf = 0, exporting = false, cancel = false, parts = [];
  let selStart = 0, selEnd = 0;

  const status = el('div.small.soft', { role: 'status', style: { minHeight: '17px', textAlign: 'center' } });
  const timer = el('div', { style: { fontSize: '48px', fontWeight: 300, fontVariantNumeric: 'tabular-nums', lineHeight: 1.1 } }, '0:00');
  const recLabel = el('div.caps', { style: { display: 'flex', alignItems: 'center', gap: '6px', visibility: 'hidden' } }, el('span', { style: { width: '8px', height: '8px', borderRadius: '50%', background: '#C8382F', display: 'inline-block' } }), 'REC');
  const recInfo = el('div.small.soft', 'Tap to record');
  const d = dial(230, [recLabel, timer, recInfo]);
  const recBtn = el('button.rec-btn', { 'aria-label': 'Start recording', onclick: () => mic ? stopRec() : startRec() }, el('div.dot'));
  const recPanel = el('div.col', { style: { alignItems: 'center', gap: '14px', padding: '6px 0 4px' } }, d, recBtn, el('div.tiny.soft', 'Keep the screen on while recording — iPhone stops the mic when it locks'));

  // editor
  const selLen = el('div', { style: { fontSize: '28px', fontWeight: 300 } }), ofTotal = el('div.small.soft');
  const wave = waveform();
  const startT = el('div.mono.small', { style: { minWidth: '62px', textAlign: 'center' } }), endT = el('div.mono.small', { style: { minWidth: '62px', textAlign: 'center' } });
  const nudge = (which, s) => { const r = rec.rate; if (which) setSel(selStart + s * r, selEnd); else setSel(selStart, selEnd + s * r); };
  const playBtn = el('button.round.metal', { 'aria-label': 'Play selection', html: icons.play, onclick: togglePlay });
  const small = (t, f, label) => el('button.round', { style: { width: '36px', height: '36px', fontSize: '18px' }, 'aria-label': label, onclick: f }, t);
  const qBar = segBar(QUALITIES.map(q => String(q.kbps)), set.qualityIdx, (i) => { saveSettings({ qualityIdx: i }); updateEstimate(); });
  const sBar = segBar(['Whole', '5', '10', '25', 'Custom'], set.splitIdx, (i) => { saveSettings({ splitIdx: i }); custom.classList.toggle('hidden', i !== 4); updateEstimate(); });
  const customIn = el('input', { type: 'number', inputmode: 'decimal', min: '1', step: '0.5', value: set.customMb, 'aria-label': 'Custom MB per part', oninput: () => { saveSettings({ customMb: customIn.value }); updateEstimate(); } });
  const custom = el('div.row', customIn, el('div.soft', 'MB'));
  custom.classList.toggle('hidden', set.splitIdx !== 4);
  const even = el('input', { type: 'checkbox', 'aria-label': 'Even out volume', onchange: () => saveSettings({ evenOut: even.checked }) }); even.checked = set.evenOut;
  const estimate = el('div.small.soft');
  const exportBtn = el('button.btn', { onclick: () => exporting ? (cancel = true) : doExport() }, 'Save MP3');
  const prog = el('div.progress.hidden', el('div'));
  const results = el('div.list-gap');
  const shareAll = el('button.ghost.hidden', { onclick: () => share(parts, 'Recording') }, icons.share ? el('span', { html: icons.share, style: { display: 'inline-flex' } }) : null, 'Share all');
  const editor = el('div.col.hidden', { style: { gap: '10px' } },
    el('div.row', { style: { alignItems: 'baseline' } }, el('div.caps.grow', 'Selection'), selLen, ofTotal),
    wave,
    el('div.row', { style: { justifyContent: 'space-between' } },
      el('div.row', { style: { gap: '4px' } }, small('−', () => nudge(true, -1), 'Start 1 second earlier'), startT, small('+', () => nudge(true, 1), 'Start 1 second later')),
      playBtn,
      el('div.row', { style: { gap: '4px' } }, small('−', () => nudge(false, -1), 'End 1 second earlier'), endT, small('+', () => nudge(false, 1), 'End 1 second later'))),
    el('div.row', el('div.caps', { style: { width: '92px' } }, 'kbps'), el('div.grow', qBar)),
    el('div.row', el('div.caps', { style: { width: '92px' } }, 'MB per part'), el('div.grow', sBar)),
    custom,
    el('div.row.panel', el('div.grow', 'Even out volume', el('div.tiny.soft', 'Lifts quiet speech, holds loud peaks down')), el('label.switch', even, el('span'))),
    estimate, exportBtn, prog, results, shareAll);

  const fileIn = el('input', { type: 'file', accept: 'audio/*,video/mp4,.m4a,.amr,.3gp,.aac,.wav,.mp3', class: 'hidden', onchange: openFile });
  const top = topBar(0, [
    el('button.round', { 'aria-label': 'Open audio file', html: icons.folder, onclick: () => fileIn.click() }),
    el('button.round', { 'aria-label': 'Delete recording', html: icons.trash, onclick: deleteRec }),
  ]);
  const newRec = el('button.ghost.hidden', { onclick: () => { stopPlay(); showEditor(false); } }, el('span', { style: { width: '10px', height: '10px', borderRadius: '50%', background: '#C8382F', display: 'inline-block' } }), 'New recording');
  root.append(top, status, recPanel, newRec, editor, fileIn);

  if (await loadRec()) showEditor(true);

  // ---------------- recording

  async function startRec() {
    if (rec && !confirmedOverwrite) { confirmBox('Start a new recording? The current one will be replaced (saved MP3s are kept).', 'Record', () => { confirmedOverwrite = true; startRec(); }); return; }
    confirmedOverwrite = false;
    chunks = []; recSamples = 0;
    try {
      mic = await startMic((c) => {
        chunks.push(c); recSamples += c.length;
        let pk = 0; for (let i = 0; i < c.length; i++) { const a = Math.abs(c[i]); if (a > pk) pk = a; }
        level = Math.max(pk, level * 0.85);
      }, { processing: true });
    } catch (e) { status.textContent = e?.name === 'NotAllowedError' ? 'Microphone permission is needed. Allow it in Settings → Safari → Microphone.' : 'Could not open the microphone.'; mic = null; return; }
    recStart = Date.now();
    recBtn.classList.add('on'); recBtn.setAttribute('aria-label', 'Stop recording');
    recLabel.style.visibility = 'visible'; status.textContent = '';
    timerIv = setInterval(() => {
      const secs = recSamples / mic.rate;
      timer.textContent = fmtTime(secs);
      recInfo.textContent = `≈ ${mb(secs * 44100 * 2)} raw`;
      const db = 20 * Math.log10(level + 1e-9); d.set(Math.max(0, Math.min(1, (db + 50) / 50)));
    }, 100);
  }
  let confirmedOverwrite = false;

  function stopRec() {
    const rate = mic.rate; mic.stop(); mic = null; clearInterval(timerIv);
    recBtn.classList.remove('on'); recBtn.setAttribute('aria-label', 'Start recording'); recLabel.style.visibility = 'hidden'; d.set(0);
    const n = chunks.reduce((a, c) => a + c.length, 0);
    if (n < rate / 2) { status.textContent = 'Too short'; return; }
    const s = new Float32Array(n); let o = 0; for (const c of chunks) { s.set(c, o); o += c.length; } chunks = [];
    const dt = new Date(recStart), p = (x) => String(x).padStart(2, '0');
    rec = { samples: s, rate, name: `rec_${dt.getFullYear()}${p(dt.getMonth() + 1)}${p(dt.getDate())}_${p(dt.getHours())}${p(dt.getMinutes())}`, created: recStart };
    storeRec(); showEditor(true);
  }

  async function openFile() {
    const f = fileIn.files[0]; fileIn.value = ''; if (!f) return;
    status.textContent = 'Opening…';
    try {
      const { samples, rate } = await decodeFile(f);
      const name = f.name.replace(/\.[^.]+$/, '').replace(/[^\p{L}\p{N}._-]+/gu, '_').replace(/^_+|_+$/g, '').slice(0, 60) || 'audio';
      rec = { samples, rate, name, created: Date.now() };
      storeRec(); status.textContent = ''; showEditor(true);
    } catch (e) { status.textContent = "Could not open that file. iPhone can open MP3, M4A, WAV and AAC; AMR/3GP call recordings may need converting first."; }
  }

  function deleteRec() {
    if (!rec) { toast('Nothing to delete'); return; }
    confirmBox('Delete this recording? It can’t be undone. MP3s you already saved or shared are not affected.', 'Delete', async () => {
      stopPlay(); rec = null; parts = []; await del('kv', 'recording'); showEditor(false); toast('Recording deleted');
    });
  }

  // ---------------- editor

  function showEditor(on) {
    editor.classList.toggle('hidden', !on); newRec.classList.toggle('hidden', !on); recPanel.classList.toggle('hidden', on);
    if (!on) { timer.textContent = '0:00'; recInfo.textContent = 'Tap to record'; return; }
    wave.setData(rec.samples, rec.rate);
    results.replaceChildren(); shareAll.classList.add('hidden'); parts = [];
    setSel(0, rec.samples.length);
    status.textContent = rec.name;
  }
  function setSel(a, b) {
    const n = rec.samples.length, minLen = Math.floor(rec.rate / 2);
    a = Math.max(0, Math.min(Math.round(a), n - minLen)); b = Math.max(a + minLen, Math.min(Math.round(b), n));
    selStart = a; selEnd = b; wave.setSel(a, b);
    startT.textContent = fmtTenths(a / rec.rate); endT.textContent = fmtTenths(b / rec.rate);
    selLen.textContent = fmtTime((b - a) / rec.rate); ofTotal.textContent = ` of ${fmtTime(n / rec.rate)}`;
    updateEstimate();
  }
  wave.onSel = (a, b) => setSel(a, b);

  function limitBytes() {
    const m = SPLITS[sBar.selected]; if (m == null) return null;
    const v = m === -1 ? parseFloat(String(customIn.value).replace(',', '.')) : m;
    if (!(v >= 1)) throw new Error('Enter a size of at least 1 MB');
    return Math.round(v * 1e6);
  }
  function updateEstimate() {
    if (!rec) return;
    const q = QUALITIES[qBar.selected], secs = (selEnd - selStart) / rec.rate, bytes = secs * q.kbps * 125;
    try {
      const lim = limitBytes();
      const n = lim ? planParts(0, Math.round(secs * q.rate), q.kbps, lim, q.rate).length : 1;
      estimate.textContent = `≈ ${mb(bytes)} · ${n} ${n === 1 ? 'file' : 'files'}` + (lim ? ` of max ${mb(lim)}` : '') + ` · ${q.label}`;
    } catch (e) { estimate.textContent = e.message; }
  }

  function togglePlay() { if (player) { stopPlay(); return; } play(); }
  function play() {
    const r = rec.rate;
    player = playSamples(rec.samples, r, selStart, selEnd, stopPlay);
    playBtn.innerHTML = icons.pause; playBtn.setAttribute('aria-label', 'Pause');
    const tick = () => { if (!player) return; wave.setHead(selStart + player.position() * r); raf = requestAnimationFrame(tick); }; tick();
  }
  function stopPlay() { cancelAnimationFrame(raf); player?.stop(); player = null; playBtn.innerHTML = icons.play; playBtn.setAttribute('aria-label', 'Play selection'); wave.setHead(-1); }

  async function doExport() {
    let lim; try { lim = limitBytes(); } catch (e) { toast(e.message); return; }
    stopPlay();
    const q = QUALITIES[qBar.selected];
    exporting = true; cancel = false; exportBtn.textContent = 'Cancel'; prog.classList.remove('hidden'); prog.firstChild.style.width = '0%';
    results.replaceChildren(); shareAll.classList.add('hidden'); parts = [];
    try {
      let x = rec.samples.slice(selStart, selEnd);
      if (even.checked) x = compress(x, rec.rate);
      x = await resample(x, rec.rate, q.rate);
      const plan = planParts(0, x.length, q.kbps, lim, q.rate);
      for (let i = 0; i < plan.length; i++) {
        if (cancel) throw new Error('Cancelled');
        const [a, b] = plan[i];
        const blob = await encodeMp3(x.slice(a, b), q.rate, q.kbps, (f) => { prog.firstChild.style.width = ((i + f) / plan.length * 100) + '%'; });
        const name = plan.length > 1 ? `${rec.name}_part${String(i + 1).padStart(2, '0')}of${String(plan.length).padStart(2, '0')}.mp3` : `${rec.name}.mp3`;
        const file = new File([blob], name, { type: 'audio/mpeg' });
        parts.push(file);
        results.append(el('div.item',
          el('div.col.grow', { style: { gap: '2px' } }, el('div', { style: { fontWeight: 500, fontSize: '14px', wordBreak: 'break-all' } }, name), el('div.small.soft', `${mb(file.size)} · ${fmtTime((b - a) / q.rate)}`)),
          el('button.round', { 'aria-label': 'Share ' + name, html: icons.share, onclick: () => share([file], name) })));
      }
      prog.firstChild.style.width = '100%';
      shareAll.classList.toggle('hidden', parts.length < 2);
      status.textContent = `${parts.length} ${parts.length === 1 ? 'file' : 'files'} ready — tap share to save to Files or send`;
    } catch (e) { status.textContent = e.message === 'Cancelled' ? 'Cancelled' : 'Export failed: ' + e.message; }
    finally { exporting = false; exportBtn.textContent = 'Save MP3'; setTimeout(() => prog.classList.add('hidden'), 600); }
  }

  onLeave(() => { stopPlay(); if (mic) stopRec(); });
}

// ---------------------------------------------------------------- widgets

function segBar(labels, selected, onSelect) {
  const box = el('div.tabs'); box.selected = selected;
  const btns = labels.map((l, i) => el('button', { class: i === selected ? 'on' : '', 'aria-pressed': String(i === selected), onclick: () => { box.selected = i; btns.forEach((b, j) => { b.className = j === i ? 'on' : ''; b.setAttribute('aria-pressed', String(j === i)); }); onSelect(i); } }, l));
  box.append(...btns); return box;
}

/** Waveform with draggable trim handles and a playhead. */
function waveform() {
  const st = { peaks: null, n: 0, a: 0, b: 0, head: -1 };
  const c = canvasView(130, (g, w, h) => {
    if (!st.peaks) return;
    const x = (s) => s / st.n * w, mid = h / 2, cols = st.peaks.length;
    for (let i = 0; i < cols; i++) {
      const px = i / cols * w, s = i / cols * st.n, inSel = s >= st.a && s <= st.b, v = Math.max(1, st.peaks[i] * (h / 2 - 6));
      g.fillStyle = inSel ? '#C8A96C' : 'rgba(255,255,255,0.18)'; g.fillRect(px, mid - v, Math.max(1, w / cols - 1), v * 2);
    }
    g.fillStyle = 'rgba(13,20,28,0.45)'; g.fillRect(0, 0, x(st.a), h); g.fillRect(x(st.b), 0, w - x(st.b), h);
    for (const s of [st.a, st.b]) { const px = x(s); g.fillStyle = '#EFE1BB'; g.fillRect(px - 1.5, 0, 3, h); g.beginPath(); g.arc(px, h - 10, 8, 0, 7); g.fill(); }
    if (st.head >= 0) { g.fillStyle = '#fff'; g.fillRect(x(st.head) - 1, 0, 2, h); }
  }, 'chart');
  c.setData = (samples, rate) => {
    const cols = 300, per = Math.max(1, Math.floor(samples.length / cols)); st.n = samples.length; st.peaks = new Float32Array(cols);
    let max = 1e-6;
    for (let i = 0; i < cols; i++) { let pk = 0; const s0 = i * per, s1 = Math.min(samples.length, s0 + per); for (let j = s0; j < s1; j += 4) { const a = Math.abs(samples[j]); if (a > pk) pk = a; } st.peaks[i] = pk; if (pk > max) max = pk; }
    for (let i = 0; i < cols; i++) st.peaks[i] = Math.sqrt(st.peaks[i] / max);
    c.redraw();
  };
  c.setSel = (a, b) => { st.a = a; st.b = b; c.redraw(); };
  c.setHead = (h) => { st.head = h; c.redraw(); };
  let drag = null;
  const pos = (e) => { const r = c.getBoundingClientRect(); return Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)) * st.n; };
  c.addEventListener('pointerdown', (e) => { if (!st.n) return; const p = pos(e); drag = Math.abs(p - st.a) < Math.abs(p - st.b) ? 'a' : 'b'; c.setPointerCapture(e.pointerId); move(e); });
  c.addEventListener('pointermove', (e) => { if (drag) move(e); });
  c.addEventListener('pointerup', () => { drag = null; });
  function move(e) { const p = pos(e); if (drag === 'a') c.onSel?.(p, st.b); else c.onSel?.(st.a, p); }
  return c;
}
