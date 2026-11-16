// Microphone capture with an AudioWorklet (ScriptProcessor fallback), MP3 encoding,
// file decoding, playback and the achievement "pling".

let ctx = null;
export function audioContext() {
  if (!ctx) ctx = new (window.AudioContext || window.webkitAudioContext)({ latencyHint: 'interactive' });
  if (ctx.state === 'suspended') ctx.resume();
  return ctx;
}

const WORKLET = `
class Tap extends AudioWorkletProcessor {
  constructor() { super(); this.buf = new Float32Array(2048); this.n = 0; }
  process(inputs) {
    const ch = inputs[0] && inputs[0][0];
    if (ch) {
      for (let i = 0; i < ch.length; i++) {
        this.buf[this.n++] = ch[i];
        if (this.n === this.buf.length) { this.port.postMessage(this.buf); this.buf = new Float32Array(2048); this.n = 0; }
      }
    }
    return true;
  }
}
registerProcessor('tap', Tap);`;

/**
 * Starts the microphone. onChunk(Float32Array) gets ~2048-sample blocks.
 * processing=false turns off echo cancellation / noise suppression / auto gain (for voice analysis).
 * Returns { rate, stop() }.
 */
export async function startMic(onChunk, { processing = false } = {}) {
  const ac = audioContext();
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: { echoCancellation: processing, noiseSuppression: processing, autoGainControl: processing, channelCount: 1 },
  });
  const src = ac.createMediaStreamSource(stream);
  let node, stopNode;
  if (ac.audioWorklet) {
    if (!startMic.loaded) {
      const url = URL.createObjectURL(new Blob([WORKLET], { type: 'application/javascript' }));
      await ac.audioWorklet.addModule(url); startMic.loaded = true;
    }
    node = new AudioWorkletNode(ac, 'tap', { numberOfInputs: 1, numberOfOutputs: 1, channelCount: 1 });
    node.port.onmessage = (e) => onChunk(e.data);
    const mute = ac.createGain(); mute.gain.value = 0;
    src.connect(node); node.connect(mute); mute.connect(ac.destination);
    stopNode = () => { node.port.onmessage = null; node.disconnect(); mute.disconnect(); };
  } else {
    node = ac.createScriptProcessor(2048, 1, 1);
    node.onaudioprocess = (e) => onChunk(new Float32Array(e.inputBuffer.getChannelData(0)));
    src.connect(node); node.connect(ac.destination);
    stopNode = () => { node.onaudioprocess = null; node.disconnect(); };
  }
  let wake = null;
  try { wake = await navigator.wakeLock?.request('screen'); } catch (_) {}
  return {
    rate: ac.sampleRate,
    stop() { stopNode(); src.disconnect(); stream.getTracks().forEach(t => t.stop()); try { wake?.release(); } catch (_) {} },
  };
}

/** Joins Float32 chunks into one array. */
export function join(chunks) {
  const n = chunks.reduce((a, c) => a + c.length, 0);
  const out = new Float32Array(n); let o = 0;
  for (const c of chunks) { out.set(c, o); o += c.length; }
  return out;
}

/** Decodes any audio file the browser can play into mono Float32 at the context rate. */
export async function decodeFile(file) {
  const ac = audioContext();
  const buf = await ac.decodeAudioData(await file.arrayBuffer());
  const n = buf.length, chs = buf.numberOfChannels, out = new Float32Array(n);
  for (let c = 0; c < chs; c++) { const d = buf.getChannelData(c); for (let i = 0; i < n; i++) out[i] += d[i] / chs; }
  return { samples: out, rate: buf.sampleRate };
}

/** Plays samples[from..to) at rate. Returns {stop(), position()} (seconds since `from`). */
export function playSamples(samples, rate, from = 0, to = samples.length, onEnd) {
  const ac = audioContext();
  const len = Math.max(1, to - from);
  const b = ac.createBuffer(1, len, rate);
  b.getChannelData(0).set(samples.subarray(from, to));
  const s = ac.createBufferSource(); s.buffer = b; s.connect(ac.destination);
  const t0 = ac.currentTime; let stopped = false;
  s.onended = () => { if (!stopped) onEnd?.(); };
  s.start();
  return { stop() { stopped = true; try { s.stop(); } catch (_) {} }, position: () => ac.currentTime - t0 };
}

/** Encodes mono Float32 to an MP3 Blob in a worker. onProgress(0..1). */
export function encodeMp3(samples, rate, kbps, onProgress) {
  return new Promise((resolve, reject) => {
    const w = new Worker(new URL('./mp3worker.js', import.meta.url));
    w.onmessage = (e) => {
      if (e.data.progress != null) onProgress?.(e.data.progress);
      else if (e.data.error) { w.terminate(); reject(new Error(e.data.error)); }
      else { w.terminate(); resolve(new Blob(e.data.parts, { type: 'audio/mpeg' })); }
    };
    w.onerror = (e) => { w.terminate(); reject(new Error(e.message || 'encoder failed')); };
    w.postMessage({ samples, rate, kbps }, [samples.buffer]);
  });
}

/** A soft two-note bell (E6 then B6). */
export function pling() {
  const ac = audioContext();
  const t = ac.currentTime + 0.02;
  [[0, 1318.5], [0.12, 1975.5]].forEach(([d, f]) => {
    for (const [mult, amp, decay] of [[1, 0.22, 1.6], [2, 0.08, 0.9], [3, 0.035, 0.6]]) {
      const o = ac.createOscillator(), g = ac.createGain();
      o.frequency.value = f * mult; o.type = 'sine';
      g.gain.setValueAtTime(0, t + d); g.gain.linearRampToValueAtTime(amp, t + d + 0.005);
      g.gain.exponentialRampToValueAtTime(0.0001, t + d + decay);
      o.connect(g); g.connect(ac.destination); o.start(t + d); o.stop(t + d + decay + 0.05);
    }
  });
}
