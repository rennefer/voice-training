// IndexedDB storage: sessions (JSON), audio blobs, the current recording, settings.

const DB = 'recorder', VERSION = 1;
let dbp = null;
function db() {
  if (!dbp) dbp = new Promise((res, rej) => {
    const r = indexedDB.open(DB, VERSION);
    r.onupgradeneeded = () => {
      const d = r.result;
      for (const s of ['sessions', 'audio', 'kv']) if (!d.objectStoreNames.contains(s)) d.createObjectStore(s);
    };
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  });
  return dbp;
}
async function tx(store, mode, fn) {
  const d = await db();
  return new Promise((res, rej) => {
    const t = d.transaction(store, mode); const s = t.objectStore(store);
    const out = fn(s);
    t.oncomplete = () => res(out && 'result' in out ? out.result : out);
    t.onerror = () => rej(t.error);
  });
}
export const get = (store, key) => tx(store, 'readonly', s => s.get(key));
export const put = (store, key, val) => tx(store, 'readwrite', s => s.put(val, key));
export const del = (store, key) => tx(store, 'readwrite', s => s.delete(key));
export const all = (store) => tx(store, 'readonly', s => s.getAll());

// ---------------------------------------------------------------- settings (cached in memory)

const defaults = {
  direction: 'mtf', directionChosen: false, targetMtf: 0, targetFtm: 0, target: 200, tolerance: 10, weeklyGoal: 5, routine: null, routineFtm: null, plan: null, baseline: 0, unlocked: {},
  qualityIdx: 1, splitIdx: 1, customMb: '8', evenOut: false,
};
let settings = null;
export async function loadSettings() {
  settings = Object.assign({}, defaults, (await get('kv', 'settings')) || {});
  return settings;
}
export function S() { return settings; }
export function saveSettings(patch) { Object.assign(settings, patch); put('kv', 'settings', settings); }

// ---------------------------------------------------------------- sessions

let cache = null;
export async function sessions() {
  if (!cache) cache = (await all('sessions')).sort((a, b) => a.id - b.id);
  return cache;
}
export async function saveSession(s) { const copy = Object.assign({}, s); delete copy.pcm; await put('sessions', s.id, copy); cache = null; }
export async function deleteSession(id) { await del('sessions', id); await del('audio', id); cache = null; }
export async function getSession(id) { return (await sessions()).find(s => s.id === id); }
export const saveAudio = (id, blob) => put('audio', id, blob);
export const getAudio = (id) => get('audio', id);

/** The session waiting on the report screen (not saved yet). */
export let pending = null;
export function setPending(s) { pending = s; }

export function dayKey(ms) { const d = new Date(ms); return d.getFullYear() * 1000 + Math.floor((d - new Date(d.getFullYear(), 0, 0)) / 864e5); }
export function dayStart(ms) { const d = new Date(ms); d.setHours(0, 0, 0, 0); return d.getTime(); }
