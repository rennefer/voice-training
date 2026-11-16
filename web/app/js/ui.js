// Small DOM helpers and shared widgets (no framework).

/** el('div.panel.row', {onclick}, child, 'text', ...) */
export function el(spec, attrs, ...kids) {
  if (attrs == null || typeof attrs !== 'object' || attrs instanceof Node || Array.isArray(attrs)) { if (attrs != null) kids.unshift(attrs); attrs = {}; }
  const [tag, ...cls] = spec.split('.');
  const e = document.createElement(tag || 'div');
  if (cls.length) e.className = cls.join(' ');
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (k === 'style' && typeof v === 'object') Object.assign(e.style, v);
    else if (k === 'html') e.innerHTML = v;
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const k of kids.flat()) if (k != null && k !== false) e.append(k instanceof Node ? k : String(k));
  return e;
}

export const icons = {
  back: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M15 5l-7 7 7 7"/></svg>',
  close: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg>',
  play: '<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M7 4l13 8-13 8z"/></svg>',
  pause: '<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M6 4h4v16H6zM14 4h4v16h-4z"/></svg>',
  folder: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" aria-hidden="true"><path d="M3 7h6l2 2h10v10H3z"/></svg>',
  share: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3v12M7 8l5-5 5 5M5 14v6h14v-6"/></svg>',
  trash: '<svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"/></svg>',
  download: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3v12M7 10l5 5 5-5M5 20h14"/></svg>',
};
export function icon(name) { const s = el('span', { html: icons[name] }); s.style.display = 'inline-flex'; return s; }

export function header(title, right, onBack = () => history.back()) {
  return el('div.header',
    el('button.round', { 'aria-label': 'Back', onclick: onBack, html: icons.back }),
    el('div.h2', title),
    right ? el('div.small.brass-text', right) : null);
}

export function tabs(labels, selected, onSelect) {
  const box = el('div.tabs', { role: 'tablist' });
  const btns = labels.map((l, i) => el('button', { role: 'tab', 'aria-selected': String(i === selected), class: i === selected ? 'on' : '', onclick: () => { set(i); onSelect(i); } }, l));
  function set(i) { btns.forEach((b, j) => { b.className = j === i ? 'on' : ''; b.setAttribute('aria-selected', String(j === i)); }); box.selected = i; }
  box.append(...btns); box.selected = selected; box.set = set;
  return box;
}

export function toast(msg, ms = 2200) {
  const t = el('div.toast', { role: 'status' }, msg);
  document.body.append(t);
  setTimeout(() => t.remove(), ms);
}

/** Modal dialog. buttons: [{label, primary, onClick}] — returns a close() function. */
export function modal(content, buttons = [{ label: 'OK', primary: true }]) {
  const back = el('div.modal-back');
  const close = () => back.remove();
  const box = el('div.modal', { role: 'dialog', 'aria-modal': 'true' }, content,
    el('div.row', { style: { justifyContent: 'flex-end', marginTop: '6px' } },
      buttons.map(b => el(b.primary ? 'button.btn' : 'button.ghost', { onclick: () => { close(); b.onClick?.(); } }, b.label))));
  back.addEventListener('click', (e) => { if (e.target === back) close(); });
  back.append(box); document.body.append(back);
  return close;
}

export function confirmBox(text, okLabel, onOk) {
  modal(el('div', text), [{ label: 'Cancel' }, { label: okLabel, primary: true, onClick: onOk }]);
}

export function fmtTime(sec) { sec = Math.max(0, Math.floor(sec)); const h = Math.floor(sec / 3600), m = Math.floor(sec / 60) % 60, s = sec % 60; return h ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`; }
export function fmtTenths(sec) { return fmtTime(sec) + '.' + Math.floor((sec * 10) % 10); }
export function mb(bytes) { return bytes < 1e6 ? Math.max(1, Math.round(bytes / 1e3)) + ' KB' : (bytes / 1e6).toFixed(1) + ' MB'; }

/** Canvas sized for the device pixel ratio; draw(ctx, w, h) called on resize and redraw(). */
export function canvasView(heightPx, draw, cls = '') {
  const c = el('canvas' + (cls ? '.' + cls : ''));
  c.style.height = heightPx + 'px';
  const ctx = c.getContext('2d');
  let w = 0, h = 0;
  function size() {
    const r = c.getBoundingClientRect(); const dpr = window.devicePixelRatio || 1;
    w = r.width; h = r.height;
    if (!w) return false;
    c.width = Math.round(w * dpr); c.height = Math.round(h * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    return true;
  }
  let raf = 0;
  c.redraw = () => { if (raf) return; raf = requestAnimationFrame(() => { raf = 0; if (!w && !size()) return; ctx.clearRect(0, 0, w, h); draw(ctx, w, h); }); };
  new ResizeObserver(() => { if (size()) { ctx.clearRect(0, 0, w, h); draw(ctx, w, h); } }).observe(c);
  return c;
}

/** Brass colour at an angle (deg, 0 = right, clockwise), lit from the top left — same as the app. */
export function brassAt(a) {
  const lerp = (x, y, t) => x.map((v, i) => v + (y[i] - v) * t);
  const d = Math.cos((a - 235) * Math.PI / 180), t = (d + 1) / 2;
  const dark = [92, 68, 32], mid = [170, 136, 80], light = [232, 212, 160], spec = [250, 240, 214];
  let c = t < 0.7 ? lerp(dark, mid, Math.min(1, t * 1.4)) : lerp(mid, light, (t - 0.7) / 0.3);
  const n = ((a % 360) + 360) % 360;
  c = lerp(c, spec, Math.exp(-(((n - 250) / 9) ** 2)) * 0.85);
  return `rgb(${c.map(Math.round).join(',')})`;
}

/** 40-segment brass dial (FineVolume style). value 0..1 */
export function drawDial(ctx, w, h, value) {
  const cx = w / 2, cy = h / 2, r = Math.min(cx, cy);
  const n = 40, lit = value <= 0 ? 0 : Math.max(1, Math.round(n * value));
  ctx.lineCap = 'round'; ctx.lineWidth = 4.5;
  for (let i = 0; i < n; i++) {
    const a = 135 + 270 * i / (n - 1), rad = a * Math.PI / 180, on = i < lit;
    const r1 = on ? r * 0.80 : r * 0.82, r2 = on ? r * 0.98 : r * 0.95;
    ctx.strokeStyle = on ? brassAt(a) : 'rgba(255,255,255,0.13)';
    ctx.beginPath(); ctx.moveTo(cx + r1 * Math.cos(rad), cy + r1 * Math.sin(rad)); ctx.lineTo(cx + r2 * Math.cos(rad), cy + r2 * Math.sin(rad)); ctx.stroke();
  }
}

export function metalGradient(ctx, x0, y0, x1, y1) {
  const g = ctx.createLinearGradient(x0, y0, x1, y1);
  [[0, '#EFE1BB'], [0.3, '#C8A96C'], [0.58, '#9C7B43'], [0.8, '#C9AB70'], [1, '#7A5F30']].forEach(([o, c]) => g.addColorStop(o, c));
  return g;
}

export function share(files, title) {
  if (navigator.canShare && navigator.canShare({ files })) return navigator.share({ files, title }).catch(() => {});
  for (const f of files) download(f);
}
export function download(file) {
  const a = el('a', { href: URL.createObjectURL(file), download: file.name });
  document.body.append(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 30000);
}
