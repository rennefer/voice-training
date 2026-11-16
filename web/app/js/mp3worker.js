// MP3 encoding off the main thread with lamejs (LGPL). Input: mono Float32 at any rate.
importScripts('../vendor/lame.min.js');

onmessage = (e) => {
  try {
    const { samples, rate, kbps } = e.data;
    const enc = new lamejs.Mp3Encoder(1, rate, kbps);
    const block = 1152 * 8;
    const pcm = new Int16Array(block);
    const parts = [];
    for (let i = 0; i < samples.length; i += block) {
      const n = Math.min(block, samples.length - i);
      for (let k = 0; k < n; k++) { const v = samples[i + k]; pcm[k] = v < -1 ? -32768 : v > 1 ? 32767 : v * 32767; }
      const out = enc.encodeBuffer(n === block ? pcm : pcm.subarray(0, n));
      if (out.length) parts.push(new Uint8Array(out));
      if ((i / block) % 20 === 0) postMessage({ progress: i / samples.length });
    }
    const end = enc.flush();
    if (end.length) parts.push(new Uint8Array(end));
    postMessage({ parts });
  } catch (err) {
    postMessage({ error: String(err && err.message || err) });
  }
};
