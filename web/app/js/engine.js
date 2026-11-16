// Voice analysis engine — JavaScript port of the Android app's VoiceAnalyzer.kt.
// Pitch: YIN on a 4x decimated copy. Formants: LPC (order 12) roots (Durand–Kerner).
// Vocal weight: H1*–H2* (formant-corrected, Iseli & Alwan). HNR from YIN aperiodicity.
// Works at any sample rate (44.1 or 48 kHz). One analyzer per stream (not re-entrant).

export const FRAME = 2048;
export const HOP = 1024;
const DEC = 4;
const MIN_F0 = 65, MAX_F0 = 500;
export const SILENCE_DB = -52;
export const SPL_OFFSET = 92;
const LPC_ORDER = 12;

export const Ranges = { MALE_LO: 85, MALE_HI: 155, FEMALE_LO: 165, FEMALE_HI: 255 };

function makeLowpass(n, fc) {
  const h = new Float32Array(n); const m = (n - 1) / 2; let s = 0;
  for (let i = 0; i < n; i++) {
    const t = i - m;
    const sinc = t === 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * t) / (Math.PI * t);
    const w = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (n - 1));
    h[i] = sinc * w; s += h[i];
  }
  for (let i = 0; i < n; i++) h[i] /= s;
  return h;
}

export class VoiceAnalyzer {
  constructor(rate = 44100) {
    this.rate = rate;
    this.dRate = rate / DEC;
    this.dLen = FRAME / DEC;
    this.dec = new Float32Array(this.dLen);
    this.fir = makeLowpass(41, 4800 / rate);
    this.diff = new Float32Array(this.dLen / 2 + 1);
    this.hann = new Float32Array(FRAME).map((_, i) => 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FRAME - 1)));
    this.hamming = new Float32Array(this.dLen).map((_, i) => 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (this.dLen - 1)));
    this.re = new Float64Array(FRAME); this.im = new Float64Array(FRAME);
    this.lpcBuf = new Float64Array(this.dLen); this.r = new Float64Array(LPC_ORDER + 1);
  }

  /** x: Float32Array (-1..1), analyses FRAME samples from off. Returns a frame object. */
  analyze(x, off = 0) {
    let sum = 0;
    for (let i = 0; i < FRAME; i++) { const v = x[off + i]; sum += v * v; }
    const rms = Math.sqrt(sum / FRAME);
    const dbfs = 20 * Math.log10(rms + 1e-9);
    const db = dbfs + SPL_OFFSET;
    const none = { f0: 0, db, hnr: 0, f1: 0, f2: 0, f3: 0, h1h2: NaN };
    if (dbfs < SILENCE_DB) return none;
    this.decimate(x, off);
    const [f0, aper] = this.yin();
    if (f0 <= 0) return none;
    const hnr = Math.min(40, Math.max(-5, 10 * Math.log10(Math.max(1e-3, 1 - aper) / Math.max(1e-3, aper))));
    const fm = (hnr >= 6 && dbfs > SILENCE_DB + 10) ? this.formants() : [0, 0, 0];
    const h = this.h1h2(x, off, f0, fm[0], fm[1]);
    return { f0, db, hnr, f1: fm[0], f2: fm[1], f3: fm[2], h1h2: h };
  }

  decimate(x, off) {
    const n = this.fir.length, half = n >> 1;
    for (let j = 0; j < this.dLen; j++) {
      const c = j * DEC; let acc = 0;
      for (let k = 0; k < n; k++) {
        let idx = c + k - half; if (idx < 0) idx = 0; else if (idx > FRAME - 1) idx = FRAME - 1;
        acc += this.fir[k] * x[off + idx];
      }
      this.dec[j] = acc;
    }
  }

  yin() {
    const dRate = this.dRate, dec = this.dec, diff = this.diff;
    const tauMin = Math.floor(dRate / MAX_F0);
    const tauMax = Math.min(Math.floor(dRate / MIN_F0), this.dLen / 2 - 1);
    const w = this.dLen - tauMax;
    diff[0] = 0;
    for (let tau = 1; tau <= tauMax; tau++) {
      let s = 0;
      for (let j = 0; j < w; j++) { const d = dec[j] - dec[j + tau]; s += d * d; }
      diff[tau] = s;
    }
    const cmnd = new Float32Array(tauMax + 1); cmnd[0] = 1; let running = 0;
    for (let tau = 1; tau <= tauMax; tau++) { running += diff[tau]; cmnd[tau] = running > 0 ? diff[tau] * tau / running : 1; }
    let best = -1;
    for (let tau = tauMin; tau <= tauMax; tau++) {
      if (cmnd[tau] < 0.15) { let t = tau; while (t + 1 <= tauMax && cmnd[t + 1] < cmnd[t]) t++; best = t; break; }
    }
    if (best < 0) {
      let mv = Infinity;
      for (let tau = tauMin; tau <= tauMax; tau++) if (cmnd[tau] < mv) { mv = cmnd[tau]; best = tau; }
      if (mv > 0.35) return [0, 1];
    }
    const ap = cmnd[best];
    let tauF = best;
    if (best >= 1 && best < tauMax) {
      const a = diff[best - 1], b = diff[best], c = diff[best + 1], den = a - 2 * b + c;
      if (den !== 0) tauF = best + 0.5 * (a - c) / den;
    }
    const f0 = dRate / tauF;
    if (f0 < MIN_F0 || f0 > MAX_F0) return [0, 1];
    return [f0, ap];
  }

  formants() {
    const dLen = this.dLen, buf = this.lpcBuf, r = this.r, dec = this.dec, dRate = this.dRate;
    buf[0] = dec[0] * this.hamming[0];
    for (let i = 1; i < dLen; i++) buf[i] = (dec[i] - 0.97 * dec[i - 1]) * this.hamming[i];
    for (let k = 0; k <= LPC_ORDER; k++) { let s = 0; for (let i = k; i < dLen; i++) s += buf[i] * buf[i - k]; r[k] = s; }
    if (r[0] <= 0) return [0, 0, 0];
    r[0] *= 1.0001;
    const a = levinson(r, LPC_ORDER); if (!a) return [0, 0, 0];
    const roots = polyRoots(a); if (!roots) return [0, 0, 0];
    const cands = [];
    for (const [zr, zi] of roots) {
      if (zi <= 0) continue;
      const f = Math.atan2(zi, zr) * dRate / (2 * Math.PI);
      const bw = -Math.log(Math.hypot(zr, zi)) * dRate / Math.PI;
      if (f > 150 && f < dRate / 2 - 100 && bw < 500) cands.push(f);
    }
    cands.sort((p, q) => p - q);
    const out = [0, 0, 0], lo = [200, 600, 1500], hi = [1100, 3200, 4200];
    let idx = 0;
    for (const f of cands) {
      if (idx >= 3) break;
      if (f < lo[idx]) continue;
      if (idx > 0 && f - out[idx - 1] < 150) continue;
      if (f > hi[idx]) { while (idx < 3 && f > hi[idx]) idx++; if (idx >= 3 || f < lo[idx]) continue; }
      out[idx] = f; idx++;
    }
    return out;
  }

  h1h2(x, off, f0, f1, f2) {
    const re = this.re, im = this.im;
    for (let i = 0; i < FRAME; i++) { re[i] = x[off + i] * this.hann[i]; im[i] = 0; }
    fft(re, im);
    const binHz = this.rate / FRAME;
    const peakDb = (f) => {
      const c = f / binHz;
      const lo = Math.max(1, Math.floor(c - 0.15 * f / binHz - 1));
      const hi = Math.min(FRAME / 2 - 1, Math.floor(c + 0.15 * f / binHz + 1));
      let best = 0;
      for (let k = lo; k <= hi; k++) { const m = re[k] * re[k] + im[k] * im[k]; if (m > best) best = m; }
      return 10 * Math.log10(best + 1e-20);
    };
    if (2 * f0 > this.rate / 2 - 200) return NaN;
    let h1 = peakDb(f0), h2 = peakDb(2 * f0);
    if (f1 > 0) { h1 -= this.gain(f0, f1, 80); h2 -= this.gain(2 * f0, f1, 80); }
    if (f2 > 0) { h1 -= this.gain(f0, f2, 120); h2 -= this.gain(2 * f0, f2, 120); }
    return h1 - h2;
  }

  gain(f, fc, bw) {
    const rr = Math.exp(-Math.PI * bw / this.rate);
    const th = 2 * Math.PI * fc / this.rate, ph = 2 * Math.PI * f / this.rate;
    const num = 1 - 2 * rr * Math.cos(th) + rr * rr;
    const den = Math.sqrt((1 - 2 * rr * Math.cos(th - ph) + rr * rr) * (1 - 2 * rr * Math.cos(th + ph) + rr * rr));
    return 20 * Math.log10(num / den);
  }
}

function levinson(r, p) {
  const a = new Float64Array(p + 1); a[0] = 1;
  const tmp = new Float64Array(p + 1); let e = r[0];
  for (let i = 1; i <= p; i++) {
    let acc = r[i];
    for (let j = 1; j < i; j++) acc += a[j] * r[i - j];
    const k = -acc / e;
    if (Math.abs(k) >= 1) return null;
    for (let j = 0; j <= i; j++) tmp[j] = a[j];
    for (let j = 1; j < i; j++) a[j] = tmp[j] + k * tmp[i - j];
    a[i] = k; e *= (1 - k * k);
    if (e <= 0) return null;
  }
  return a;
}

function polyRoots(a) {
  const n = a.length - 1;
  const cr = new Float64Array(n), ci = new Float64Array(n);
  for (let i = 0; i < n; i++) { const ang = 2 * Math.PI * i / n + 0.4; cr[i] = 0.9 * Math.cos(ang); ci[i] = 0.9 * Math.sin(ang); }
  for (let iter = 0; iter < 400; iter++) {
    let change = 0;
    for (let i = 0; i < n; i++) {
      let pr = a[0], pi = 0;
      for (let k = 1; k <= n; k++) { const nr = pr * cr[i] - pi * ci[i] + a[k]; const ni = pr * ci[i] + pi * cr[i]; pr = nr; pi = ni; }
      let qr = a[0], qi = 0;
      for (let j = 0; j < n; j++) if (j !== i) {
        const dr = cr[i] - cr[j], di = ci[i] - ci[j];
        const nr = qr * dr - qi * di, ni = qr * di + qi * dr; qr = nr; qi = ni;
      }
      const den = qr * qr + qi * qi; if (den < 1e-300) continue;
      const sr = (pr * qr + pi * qi) / den, si = (pi * qr - pr * qi) / den;
      cr[i] -= sr; ci[i] -= si;
      change = Math.max(change, Math.abs(sr) + Math.abs(si));
    }
    if (change < 1e-10) break;
  }
  for (let i = 0; i < n; i++) if (Number.isNaN(cr[i]) || Number.isNaN(ci[i])) return null;
  return Array.from({ length: n }, (_, i) => [cr[i], ci[i]]);
}

function fft(re, im) {
  const n = re.length;
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    while (j & bit) { j ^= bit; bit >>= 1; }
    j ^= bit;
    if (i < j) { let t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
  }
  for (let len = 2; len <= n; len <<= 1) {
    const ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang), half = len >> 1;
    for (let i = 0; i < n; i += len) {
      let cr = 1, ci = 0;
      for (let k = 0; k < half; k++) {
        const ur = re[i + k], ui = im[i + k];
        const vr = re[i + k + half] * cr - im[i + k + half] * ci;
        const vi = re[i + k + half] * ci + im[i + k + half] * cr;
        re[i + k] = ur + vr; im[i + k] = ui + vi;
        re[i + k + half] = ur - vr; im[i + k + half] = ui - vi;
        const ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr;
      }
    }
  }
}

// ---------------------------------------------------------------- scores

export const Scores = {
  spacing: (f1, f2, f3) => (f1 > 0 && f2 > 0 && f3 > 0) ? (0.5 * f1 + 1.5 * f2 + 2.5 * f3) / 8.75 : 0,
  resonance: (sp) => clamp((sp - 1010) / 240 * 100),
  weight: (h) => clamp(h / 12 * 100),
  breathiness: (hnr) => clamp((22 - hnr) / 12 * 100),
  loudness: (db) => clamp((db - 40) / 45 * 100),
  semitones: (f, ref) => 12 * Math.log2(f / ref),
  noteName(f) {
    if (!(f > 0)) return '';
    const names = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'];
    const m = Math.round(69 + 12 * Math.log2(f / 440));
    return names[((m % 12) + 12) % 12] + (Math.floor(m / 12) - 1);
  },
};
function clamp(v) { return Math.max(0, Math.min(100, v)); }

function pct(sorted, p) { if (!sorted.length) return 0; return sorted[Math.min(sorted.length - 1, Math.max(0, Math.floor((sorted.length - 1) * p)))]; }
export function median(xs) { return xs.length ? pct([...xs].sort((a, b) => a - b), 0.5) : 0; }

export const HIST_LO = 60, HIST_BINS = 25;

/** Summary of a list of frames (same as SessionStats.compute in the Android app). */
export function computeStats(frames, frameSeconds, target, tolerance) {
  const v = frames.filter(f => f.f0 > 0);
  if (v.length < 3) return null;
  const f0s = v.map(f => f.f0).sort((a, b) => a - b);
  const med = pct(f0s, 0.5);
  const share = (pred) => v.filter(f => pred(f.f0)).length * 100 / v.length;
  const semis = v.map(f => Scores.semitones(f.f0, med));
  const mean = semis.reduce((a, b) => a + b, 0) / semis.length;
  const sd = Math.sqrt(semis.reduce((a, b) => a + (b - mean) ** 2, 0) / semis.length);
  const wf = v.filter(f => f.f1 > 0 && f.f2 > 0 && f.f3 > 0);
  const spacing = median(wf.map(f => Scores.spacing(f.f1, f.f2, f.f3)));
  const h = median(v.map(f => f.h1h2).filter(x => !Number.isNaN(x)));
  const hnr = median(v.map(f => f.hnr));
  const hist = new Array(HIST_BINS).fill(0);
  for (const f of v) { const b = Math.floor((f.f0 - HIST_LO) / 10); if (b >= 0 && b < HIST_BINS) hist[b]++; }
  return {
    voicedSeconds: v.length * frameSeconds,
    medianF0: med, lowF0: pct(f0s, 0.05), highF0: pct(f0s, 0.95),
    pctFemale: share(f => f >= Ranges.FEMALE_LO && f <= Ranges.FEMALE_HI),
    pctMale: share(f => f >= Ranges.MALE_LO && f <= Ranges.MALE_HI),
    pctOnTarget: share(f => f >= target - tolerance && f <= target + tolerance),
    melody: sd,
    f1: median(wf.map(f => f.f1)), f2: median(wf.map(f => f.f2)), f3: median(wf.map(f => f.f3)),
    resonance: spacing > 0 ? Scores.resonance(spacing) : 0,
    h1h2: h, weight: Scores.weight(h),
    volumeDb: v.reduce((a, f) => a + f.db, 0) / v.length,
    hnr, breathiness: Scores.breathiness(hnr),
    hist,
  };
}

/** Live smoothing for the meters: medians of the last N voiced frames. */
export class LiveSmoother {
  constructor(size = 20) { this.size = size; this.clear(); }
  clear() { this.f1 = []; this.f2 = []; this.f3 = []; this.h = []; this.hnr = []; }
  push(a, x) { a.push(x); if (a.length > this.size) a.shift(); }
  add(fr) {
    if (!(fr.f0 > 0)) return;
    if (fr.f1 > 0 && fr.f2 > 0 && fr.f3 > 0) { this.push(this.f1, fr.f1); this.push(this.f2, fr.f2); this.push(this.f3, fr.f3); }
    if (!Number.isNaN(fr.h1h2)) this.push(this.h, fr.h1h2);
    this.push(this.hnr, fr.hnr);
  }
  resonance() { return this.f1.length < 4 ? null : Scores.resonance(Scores.spacing(median(this.f1), median(this.f2), median(this.f3))); }
  weight() { return this.h.length < 4 ? null : Scores.weight(median(this.h)); }
  breathiness() { return this.hnr.length < 4 ? null : Scores.breathiness(median(this.hnr)); }
}
