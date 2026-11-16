// Hash router and app start.
import { el, tabs } from './ui.js';
import { loadSettings } from './store.js';
import { audioContext } from './audio.js';

const root = document.getElementById('app');
let leaveFns = [];
export function onLeave(f) { leaveFns.push(f); }

export function go(hash, replace = false) {
  if (replace) { history.replaceState(null, '', hash); route(); }
  else if (location.hash === hash) route(); else location.hash = hash;
}

/** "Recorder" title + Record | Voice switch (+ optional buttons on the Record tab). */
export function topBar(selected, extra = []) {
  return el('div.col', { style: { gap: '10px' } },
    el('div.row', { style: { minHeight: '46px' } }, el('div.title.grow', 'Recorder'), ...extra),
    tabs(['Record', 'Voice'], selected, (i) => go(i === 0 ? '#/record' : '#/voice')));
}

const screens = {
  record: () => import('./recorder.js').then(m => m.recordScreen),
  voice: () => import('./voice.js').then(m => m.voiceScreen),
  exercises: () => import('./voice.js').then(m => m.exercisesScreen),
  exercise: () => import('./voice.js').then(m => m.exerciseScreen),
  report: () => import('./report.js').then(m => m.reportScreen),
  today: () => import('./today.js').then(m => m.todayScreen),
  progress: () => import('./today.js').then(m => m.progressScreen),
};

let seq = 0;
async function route() {
  const my = ++seq;
  for (const f of leaveFns) { try { f(); } catch (_) {} }
  leaveFns = [];
  const [name, ...args] = (location.hash.replace(/^#\/?/, '') || 'record').split('/');
  const load = screens[name] || screens.record;
  const fn = await load();
  if (my !== seq) return;
  root.replaceChildren();
  document.querySelectorAll('.modal-back').forEach(m => m.remove());
  window.scrollTo(0, 0);
  try { await fn(root, ...args); } catch (e) { console.error(e); root.append(el('div.notice', 'Something went wrong: ' + e.message)); }
}

// iOS only allows audio after a user gesture: resume the context on the first tap.
document.addEventListener('pointerdown', () => { try { audioContext(); } catch (_) {} }, { once: true, capture: true });
window.addEventListener('hashchange', route);

if ('serviceWorker' in navigator && location.protocol === 'https:') navigator.serviceWorker.register('./sw.js').catch(() => {});
if (navigator.storage?.persist) navigator.storage.persist().catch(() => {});

await loadSettings();
route();
