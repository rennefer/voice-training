// Live voice analysis: microphone → 2048-sample frames every 1024 samples → VoiceAnalyzer.
import { startMic } from './audio.js';
import { VoiceAnalyzer, FRAME, HOP, computeStats } from './engine.js';

export class LiveAnalyzer {
  /** onFrame(frame) per hop; pcmOut: array that receives Int16Array chunks (for playback). */
  constructor(onFrame, onError, pcmOut = null) { this.onFrame = onFrame; this.onError = onError; this.pcmOut = pcmOut; this.running = false; }
  async start() {
    this.running = true;
    this.win = new Float32Array(FRAME); this.fill = 0; this.pend = new Float32Array(0);
    try {
      this.mic = await startMic((c) => this.chunk(c), { processing: false });
    } catch (e) {
      this.running = false;
      this.onError(e && e.name === 'NotAllowedError' ? 'Microphone permission is needed.' : 'Could not open the microphone.');
      return false;
    }
    this.rate = this.mic.rate;
    this.analyzer = new VoiceAnalyzer(this.rate);
    this.frameSeconds = HOP / this.rate;
    return true;
  }
  chunk(c) {
    if (!this.running) return;
    if (this.pcmOut) { const s = new Int16Array(c.length); for (let i = 0; i < c.length; i++) { const v = c[i]; s[i] = v < -1 ? -32768 : v > 1 ? 32767 : v * 32767; } this.pcmOut.push(s); }
    // join leftover + new, consume in HOP blocks
    const buf = new Float32Array(this.pend.length + c.length); buf.set(this.pend); buf.set(c, this.pend.length);
    let o = 0;
    while (buf.length - o >= HOP) {
      this.win.copyWithin(0, HOP);
      this.win.set(buf.subarray(o, o + HOP), FRAME - HOP);
      o += HOP; this.fill += HOP;
      if (this.fill >= FRAME) this.onFrame(this.analyzer.analyze(this.win, 0));
    }
    this.pend = buf.slice(o);
  }
  stop() { this.running = false; try { this.mic?.stop(); } catch (_) {} this.mic = null; }
}

/** Collects frames for one session and turns them into a session object. */
export class SessionBuilder {
  constructor(target, tolerance, dir = 'mtf') {
    this.dir = dir; this.start = Date.now(); this.frames = []; this.results = []; this.activeSeconds = 0;
    this.target = target; this.tolerance = tolerance; this.pcm = []; this.rate = 48000; this.frameSeconds = HOP / 48000;
  }
  add(f) { this.frames.push(f); }
  build(routineSize = 0, plan = null) {
    const fs = this.frameSeconds;
    const stats = computeStats(this.frames, fs, this.target, this.tolerance);
    const per = Math.max(1, Math.floor(0.25 / fs));
    const step = Math.max(per, Math.floor(this.frames.length / 1200) + 1);
    const trace = [];
    for (let i = 0; i < this.frames.length; i += step) {
      const ch = this.frames.slice(i, i + step);
      const v = ch.filter(f => f.f0 > 0).map(f => f.f0).sort((a, b) => a - b);
      trace.push(v.length * 2 < ch.length ? 0 : Math.round(v[v.length >> 1] * 10) / 10);
    }
    const seconds = this.activeSeconds > 0 ? this.activeSeconds : this.frames.length * fs;
    return {
      id: this.start, seconds, stats, exercises: [...this.results], notes: '', feeling: '',
      trace, dir: this.dir, plan, target: this.target, tolerance: this.tolerance, routineSize, traceSec: step * fs,
      pcm: this.pcm.length ? this.pcm : null, rate: this.rate,
    };
  }
}
