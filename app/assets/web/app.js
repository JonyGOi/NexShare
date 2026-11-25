/* NexShare web desktop: DSM-style UI for browsing and sharing the phone's files. */
'use strict';

// ---------------------------------------------------------------- helpers

const $ = (sel, root = document) => root.querySelector(sel);

function el(tag, attrs, ...kids) {
  const e = document.createElement(tag);
  if (attrs) for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === 'class') e.className = v;
    else if (k === 'html') e.innerHTML = v;
    else if (k === 'text') e.textContent = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (k === 'style' && typeof v === 'object') Object.assign(e.style, v);
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const k of kids.flat()) if (k != null && k !== false) e.append(k instanceof Node ? k : document.createTextNode(String(k)));
  return e;
}

// Like node.replaceChildren but drops null/false/undefined args (which would otherwise
// be inserted as the literal text "null"/"false").
function fill(node, ...kids) {
  node.replaceChildren(...kids.flat().filter(k => k != null && k !== false));
}

const store = {
  get(k, d) { try { const v = localStorage.getItem('ns.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('ns.' + k, JSON.stringify(v)); } catch { /* private mode */ } },
};

function qs(params) {
  const parts = [];
  for (const [k, v] of Object.entries(params || {})) {
    if (v == null) continue;
    for (const one of Array.isArray(v) ? v : [v]) parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(one));
  }
  return parts.length ? '?' + parts.join('&') : '';
}

async function api(path, params, opt = {}) {
  const r = await fetch('/api/' + path + qs(params), {
    method: opt.method || 'GET',
    body: opt.body,
    headers: opt.body ? { 'Content-Type': 'application/json' } : undefined,
    credentials: 'same-origin',
  });
  let j = {};
  try { j = await r.json(); } catch { /* empty body */ }
  if (r.status === 401 && path !== 'login') { Auth.show(); throw Object.assign(new Error('unauthorized'), { status: 401 }); }
  if (!r.ok) throw Object.assign(new Error(j.error || ('HTTP ' + r.status)), { status: r.status });
  return j;
}

const fileUrl = (p, dl) => '/api/file' + qs({ path: p, dl: dl ? 1 : null });
const imageUrl = (p) => /\.(heic|heif|tif|tiff)$/i.test(p || '') ? '/api/image' + qs({ path: p }) : fileUrl(p);
const thumbUrl = (p, s = 240) => '/api/thumb' + qs({ path: p, s });

function fmtSize(n) {
  n = Number(n);
  if (!isFinite(n) || n < 0) return '-';
  if (n < 1024) return Math.round(n) + ' B';
  const u = ['KB', 'MB', 'GB', 'TB'];
  let i = -1;
  do { n /= 1024; i++; } while (n >= 1024 && i < u.length - 1);
  return (n >= 100 ? n.toFixed(0) : n.toFixed(1)) + ' ' + u[i];
}
const fmtRate = n => fmtSize(Math.max(0, Number(n) || 0)) + '/s';
function fmtAgo(ms) {
  if (ms == null || ms < 0) return '-';
  const s = Math.round(ms / 1000);
  if (s < 5) return 'agora';
  if (s < 60) return s + 's';
  const m = Math.round(s / 60);
  if (m < 60) return m + ' min';
  const h = Math.round(m / 60);
  return h + ' h';
}
function fmtDate(ms) {
  const d = new Date(ms);
  const p = n => String(n).padStart(2, '0');
  return `${p(d.getDate())}/${p(d.getMonth() + 1)}/${d.getFullYear()} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
function fmtUptime(ms) {
  let s = Math.floor(ms / 1000);
  const d = Math.floor(s / 86400); s %= 86400;
  const h = Math.floor(s / 3600); s %= 3600;
  const m = Math.floor(s / 60);
  return (d ? d + 'd ' : '') + h + 'h ' + m + 'm';
}
const extOf = n => { const i = n.lastIndexOf('.'); return i > 0 ? n.slice(i + 1).toLowerCase() : ''; };

const KIND = {};
for (const [k, list] of Object.entries({
  image: 'jpg jpeg png gif webp bmp heic heif svg',
  video: 'mp4 m4v webm mkv 3gp mov avi ts m2ts mpg mpeg wmv flv vob ogv',
  audio: 'mp3 m4a aac ogg opus wav flac amr',
  pdf: 'pdf',
  text: 'txt log md csv json xml html htm css js ini conf yml yaml srt',
  archive: 'zip rar 7z tar gz bz2 xz',
  apk: 'apk xapk apks',
  doc: 'doc docx odt rtf',
  sheet: 'xls xlsx ods',
  slide: 'ppt pptx odp',
})) for (const e of list.split(' ')) KIND[e] = k;
const kindOf = entry => entry.dir ? 'folder' : (KIND[extOf(entry.name)] || 'file');
const KIND_LABEL = { folder: 'Folder', image: 'Image', video: 'Video', audio: 'Audio', pdf: 'PDF', text: 'Text', archive: 'Archive', apk: 'Android App', doc: 'Document', sheet: 'Spreadsheet', slide: 'Presentation', file: 'File' };

function toast(msg, ms = 2600) {
  const t = el('div', { class: 'toast', text: msg });
  $('#toasts').append(t);
  setTimeout(() => t.remove(), ms);
}

function dialog({ title, message, input, okText = 'OK', danger, cancel = true }) {
  return new Promise(resolve => {
    const field = input != null ? el('input', { value: input, spellcheck: 'false' }) : null;
    const close = v => { wrap.remove(); resolve(v); };
    const ok = el('button', { class: 'btn ' + (danger ? 'danger' : 'primary'), text: okText, onclick: () => close(field ? field.value : true) });
    const wrap = el('div', { id: 'modal', onmousedown: e => { if (e.target === wrap && cancel) close(null); } },
      el('div', { class: 'dialog' },
        el('div', { class: 'd-head', text: title }),
        el('div', { class: 'd-body' }, message ? el('div', { text: message, style: { marginBottom: field ? '10px' : 0 } }) : null, field),
        el('div', { class: 'd-foot' }, cancel ? el('button', { class: 'btn', text: 'Cancel', onclick: () => close(null) }) : null, ok)));
    wrap.addEventListener('keydown', e => {
      if (e.key === 'Enter') { e.preventDefault(); ok.click(); }
      if (e.key === 'Escape' && cancel) close(null);
    });
    document.body.append(wrap);
    if (field) {
      field.focus();
      const dot = field.value.lastIndexOf('.');
      field.setSelectionRange(0, dot > 0 ? dot : field.value.length);
    } else ok.focus();
  });
}

let openMenuEl = null;
function closeMenu() { if (openMenuEl) { openMenuEl.remove(); openMenuEl = null; } }
function menu(items, x, y) {
  closeMenu();
  const m = el('div', { class: 'menu' });
  for (const it of items) {
    if (!it) continue;
    if (it === '-') { m.append(el('hr')); continue; }
    m.append(el('button', {
      class: it.danger ? 'danger' : null, disabled: it.disabled,
      onclick: () => { closeMenu(); it.run(); },
    }, it.ico ? icon(it.ico) : null, it.label));
  }
  document.body.append(m);
  const r = m.getBoundingClientRect();
  m.style.left = Math.max(4, Math.min(x, innerWidth - r.width - 6)) + 'px';
  m.style.top = Math.max(4, Math.min(y, innerHeight - r.height - 6)) + 'px';
  openMenuEl = m;
}
function menuAt(btn, items) { const r = btn.getBoundingClientRect(); menu(items, r.left, r.bottom + 4); }

let openPop = null;
function closePop() { if (openPop) { openPop.el.remove(); openPop.btn && openPop.btn.classList.remove('active'); openPop = null; } }
document.addEventListener('mousedown', e => {
  if (openMenuEl && !openMenuEl.contains(e.target)) closeMenu();
  if (openPop && !openPop.el.contains(e.target) && !(openPop.btn && openPop.btn.contains(e.target))) closePop();
});
document.addEventListener('keydown', e => { if (e.key === 'Escape') { closeMenu(); closePop(); } });

// ---------------------------------------------------------------- icons

const G = {
  back: '<path d="M15 18l-6-6 6-6"/>',
  fwd: '<path d="M9 18l6-6-6-6"/>',
  up: '<path d="M12 19V5M5 12l7-7 7 7"/>',
  refresh: '<path d="M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-4-4"/>',
  upload: '<path d="M12 16V4M7 9l5-5 5 5"/><path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3"/>',
  download: '<path d="M12 4v12M7 11l5 5 5-5"/><path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  newfolder: '<path d="M3 6h6l2 2h10v11H3z"/><path d="M12 11v6M9 14h6"/>',
  rename: '<path d="M4 20h4L19 9l-4-4L4 16z"/>',
  trash: '<path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"/>',
  copy: '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3"/>',
  cut: '<circle cx="6" cy="18" r="3"/><circle cx="18" cy="18" r="3"/><path d="M8 16L19 4M16 16L5 4"/>',
  paste: '<rect x="5" y="4" width="14" height="17" rx="2"/><path d="M9 4V2h6v2"/>',
  zip: '<path d="M6 3h9l4 4v14H6z"/><path d="M11 6h2M11 9h2M11 12h2M10 15h4v3h-4z"/>',
  open: '<path d="M14 4h6v6M20 4l-9 9"/><path d="M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/>',
  link: '<path d="M10 14a4 4 0 0 0 6 0l3-3a4 4 0 0 0-6-6l-1 1"/><path d="M14 10a4 4 0 0 0-6 0l-3 3a4 4 0 0 0 6 6l1-1"/>',
  list: '<path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/>',
  grid: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
  eye: '<path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
  chev: '<path d="M6 9l6 6 6-6"/>',
  caret: '<path d="M9 6l6 6-6 6"/>',
  x: '<path d="M6 6l12 12M18 6L6 18"/>',
  min: '<path d="M5 12h14"/>',
  max: '<rect x="5" y="5" width="14" height="14" rx="2"/>',
  restore: '<rect x="4" y="8" width="12" height="12" rx="2"/><path d="M8 8V5a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1h-3"/>',
  logout: '<path d="M15 4h4a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-4M10 17l5-5-5-5M15 12H3"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 8v.01M12 11v5"/>',
  play: '<path d="M8 5v14l11-7z" fill="currentColor"/>',
  pause: '<rect x="6" y="5" width="4" height="14" rx="1" fill="currentColor"/><rect x="14" y="5" width="4" height="14" rx="1" fill="currentColor"/>',
  folderUp: '<path d="M3 6h6l2 2h10v11H3z"/><path d="M12 17v-6M9 14l3-3 3 3"/>',
  zoomIn: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-4-4M11 8v6M8 11h6"/>',
  zoomOut: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-4-4M8 11h6"/>',
  rotate: '<path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-1.19"/>',
  fit: '<path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3"/>',
};
function icon(name, cls) {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('viewBox', '0 0 24 24');
  s.setAttribute('fill', 'none');
  s.setAttribute('stroke', 'currentColor');
  s.setAttribute('stroke-width', '2');
  s.setAttribute('stroke-linecap', 'round');
  s.setAttribute('stroke-linejoin', 'round');
  if (cls) s.setAttribute('class', cls);
  s.innerHTML = G[name] || '';
  return s;
}

// App icons, 64x64.
const APP_ICO = {
  filestation: `<svg viewBox="0 0 64 64"><defs><linearGradient id="fsA" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffd76a"/><stop offset="1" stop-color="#f5a623"/></linearGradient></defs>
    <path d="M6 16a4 4 0 0 1 4-4h14l5 5h25a4 4 0 0 1 4 4v29a4 4 0 0 1-4 4H10a4 4 0 0 1-4-4z" fill="#e8951a"/>
    <path d="M6 23a4 4 0 0 1 4-4h44a4 4 0 0 1 4 4v27a4 4 0 0 1-4 4H10a4 4 0 0 1-4-4z" fill="url(#fsA)"/>
    <circle cx="38" cy="38" r="9" fill="#e9f3ff" stroke="#1e6fd9" stroke-width="4"/><path d="M45 45l8 8" stroke="#1e6fd9" stroke-width="5" stroke-linecap="round"/></svg>`,
  photos: `<svg viewBox="0 0 64 64"><rect x="4" y="4" width="56" height="56" rx="14" fill="#fff"/>
    <g transform="translate(32 32)"><ellipse rx="7" ry="14" cy="-11" fill="#f9b233" opacity=".9"/><ellipse rx="7" ry="14" cy="-11" fill="#ef5a5a" opacity=".85" transform="rotate(72)"/>
    <ellipse rx="7" ry="14" cy="-11" fill="#a35ee8" opacity=".85" transform="rotate(144)"/><ellipse rx="7" ry="14" cy="-11" fill="#2f8ae6" opacity=".85" transform="rotate(216)"/>
    <ellipse rx="7" ry="14" cy="-11" fill="#2fb36b" opacity=".85" transform="rotate(288)"/></g></svg>`,
  videos: `<svg viewBox="0 0 64 64"><defs><linearGradient id="vdA" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#8a5cf6"/><stop offset="1" stop-color="#5b3cc4"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#vdA)"/><rect x="13" y="18" width="38" height="28" rx="5" fill="none" stroke="#fff" stroke-width="3.5"/><path d="M28 25v14l11-7z" fill="#fff"/></svg>`,
  music: `<svg viewBox="0 0 64 64"><defs><linearGradient id="muA" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#ff6b8b"/><stop offset="1" stop-color="#e3345b"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#muA)"/><path d="M26 42V20l18-4v22" fill="none" stroke="#fff" stroke-width="3.5" stroke-linejoin="round"/><circle cx="21" cy="42" r="6" fill="#fff"/><circle cx="39" cy="38" r="6" fill="#fff"/></svg>`,
  monitor: `<svg viewBox="0 0 64 64"><defs><linearGradient id="rmA" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#34475d"/><stop offset="1" stop-color="#1c2836"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#rmA)"/><path d="M12 40h8l5-14 7 22 6-16 4 8h10" fill="none" stroke="#3ddc97" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round"/></svg>`,
  control: `<svg viewBox="0 0 64 64"><defs><linearGradient id="cpA" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#5ba7f7"/><stop offset="1" stop-color="#1e6fd9"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#cpA)"/>
    <path d="M18 20h28M18 32h28M18 44h28" stroke="#cfe3fb" stroke-width="3.5" stroke-linecap="round"/>
    <circle cx="38" cy="20" r="5" fill="#fff"/><circle cx="25" cy="32" r="5" fill="#fff"/><circle cx="41" cy="44" r="5" fill="#fff"/></svg>`,
  help: `<svg viewBox="0 0 64 64"><defs><linearGradient id="hpA" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#4fd18b"/><stop offset="1" stop-color="#22a35c"/></linearGradient></defs>
    <path d="M10 12a4 4 0 0 1 4-4h38v48H14a4 4 0 0 1-4-4z" fill="url(#hpA)"/><path d="M10 50a4 4 0 0 1 4-4h38" fill="none" stroke="#d8f6e5" stroke-width="3"/>
    <path d="M26 24a6 6 0 1 1 8 5.6c-1.6.7-2 1.6-2 3.4" fill="none" stroke="#fff" stroke-width="3.5" stroke-linecap="round"/><circle cx="32" cy="39" r="2.4" fill="#fff"/></svg>`,
  upload: `<svg viewBox="0 0 64 64"><defs><linearGradient id="upA" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#36c3f0"/><stop offset="1" stop-color="#1688d6"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#upA)"/><path d="M20 40a9 9 0 0 1 1-18 12 12 0 0 1 23 2 8 8 0 0 1 0 16" fill="none" stroke="#fff" stroke-width="3.5" stroke-linecap="round"/><path d="M32 48V30M25 36l7-7 7 7" fill="none" stroke="#fff" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round"/></svg>`,
  download: `<svg viewBox="0 0 64 64"><defs><linearGradient id="dsA" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#4fc3f7"/><stop offset="1" stop-color="#0b7bd6"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#dsA)"/><circle cx="32" cy="30" r="15" fill="none" stroke="#fff" stroke-width="3.5" opacity=".35"/>
    <path d="M32 14v26M22 31l10 10 10-10" fill="none" stroke="#fff" stroke-width="4.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M18 48h28" stroke="#fff" stroke-width="4.5" stroke-linecap="round"/></svg>`,
  player: `<svg viewBox="0 0 64 64"><defs><linearGradient id="mpA" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#ffa53a"/><stop offset="1" stop-color="#f05a28"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#mpA)"/><circle cx="32" cy="32" r="17" fill="rgba(255,255,255,.22)"/><path d="M27 22v20l16-10z" fill="#fff"/></svg>`,
  terminal: `<svg viewBox="0 0 64 64"><defs><linearGradient id="tmA" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#2d3b4e"/><stop offset="1" stop-color="#161e29"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#tmA)"/><path d="M16 22l9 8-9 8" fill="none" stroke="#5bd68a" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/><path d="M32 42h16" stroke="#9fb0c3" stroke-width="4" stroke-linecap="round"/></svg>`,
  screen: `<svg viewBox="0 0 64 64"><defs><linearGradient id="smA" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#36c3f0"/><stop offset="1" stop-color="#5566e0"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#smA)"/><rect x="20" y="12" width="24" height="40" rx="5" fill="none" stroke="#fff" stroke-width="3.2"/><circle cx="32" cy="46" r="2.4" fill="#fff"/><path d="M27 26l6 4-6 4z" fill="#fff"/></svg>`,
  remote: `<svg viewBox="0 0 64 64"><defs><linearGradient id="raA" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ff9d4d"/><stop offset="1" stop-color="#f06a1e"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#raA)"/><circle cx="32" cy="32" r="16" fill="none" stroke="#fff" stroke-width="3"/><path d="M16 32h32M32 16c6 5 6 27 0 32M32 16c-6 5-6 27 0 32" fill="none" stroke="#fff" stroke-width="2.6"/></svg>`,
  debrid: `<svg viewBox="0 0 64 64"><defs><linearGradient id="dbA" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#00c6ff"/><stop offset="1" stop-color="#0072ff"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#dbA)"/><path d="M36 10L18 34h12l-4 20 20-26H34z" fill="#fff"/></svg>`,
  packagecenter: `<svg viewBox="0 0 64 64"><defs><linearGradient id="pkA" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffd15c"/><stop offset="1" stop-color="#f3932b"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#pkA)"/><path d="M32 14l16 8v20l-16 8-16-8V22z" fill="none" stroke="#fff" stroke-width="3.2" stroke-linejoin="round"/><path d="M16 22l16 8 16-8M32 30v20" fill="none" stroke="#fff" stroke-width="3.2" stroke-linejoin="round"/></svg>`,
  group: `<svg viewBox="0 0 64 64"><rect x="4" y="4" width="56" height="56" rx="14" fill="rgba(30,40,55,.55)"/>
    <rect x="13" y="13" width="17" height="17" rx="5" fill="#fff"/><rect x="34" y="13" width="17" height="17" rx="5" fill="#8a5cf6"/><rect x="13" y="34" width="17" height="17" rx="5" fill="#ef5a7a"/><rect x="34" y="34" width="17" height="17" rx="5" fill="#2f8ae6" opacity=".55"/>
    <g transform="translate(21.5 21.5) scale(.18)"><ellipse rx="7" ry="14" cy="-11" fill="#f9b233" transform="rotate(0)"/><ellipse rx="7" ry="14" cy="-11" fill="#2f8ae6" transform="rotate(120)"/><ellipse rx="7" ry="14" cy="-11" fill="#2fb36b" transform="rotate(240)"/></g>
    <path d="M40 18v8l6-4z" fill="#fff"/></svg>`,
};
function appIco(name) { return el('div', { class: 'app-ico', html: APP_ICO[name] || '' }); }

// File-type icons (24x24 artwork drawn at any size).
const FILE_COLORS = { image: '#2fb36b', video: '#8a5cf6', audio: '#ef5a7a', pdf: '#e5484d', text: '#7a8794', archive: '#c08a2e', apk: '#3ddc84', doc: '#2b6fd6', sheet: '#1f9d55', slide: '#e8742a', file: '#9aa6b2' };
const FILE_TAG = { image: 'IMG', video: '▶', audio: '♪', pdf: 'PDF', text: 'TXT', archive: 'ZIP', apk: 'APK', doc: 'DOC', sheet: 'XLS', slide: 'PPT', file: '' };
function fileIcon(kind) {
  if (kind === 'folder') return el('span', { class: 'fi', html: '<svg viewBox="0 0 24 24" width="100%" height="100%"><path d="M2 6.5A1.5 1.5 0 0 1 3.5 5H9l2 2h9.5A1.5 1.5 0 0 1 22 8.5V18a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 18z" fill="#f5b432"/><path d="M2 9.5A1.5 1.5 0 0 1 3.5 8h17A1.5 1.5 0 0 1 22 9.5V18a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 18z" fill="#ffd25e"/></svg>' });
  const c = FILE_COLORS[kind] || FILE_COLORS.file;
  const tag = FILE_TAG[kind] || '';
  return el('span', { class: 'fi', html: `<svg viewBox="0 0 24 24" width="100%" height="100%"><path d="M5 2h9.5L20 7.5V21a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z" fill="#fff" stroke="#c9d2dc" stroke-width=".8"/><path d="M14.5 2v5.5H20" fill="#e9eef3" stroke="#c9d2dc" stroke-width=".8"/>${tag ? `<rect x="2.5" y="12" width="14" height="7" rx="1.5" fill="${c}"/><text x="9.5" y="17.4" font-size="5" font-family="Arial,sans-serif" font-weight="700" fill="#fff" text-anchor="middle">${tag}</text>` : ''}</svg>` });
}

// ---------------------------------------------------------------- state

const State = { roots: [], sys: null, notes: [], clipboard: null, isRoot: false, flavor: 'normal', authMode: 'pin', webdavEnabled: false, apiKey: '', tunnelUrl: '' };

function rootOf(p) {
  let best = null;
  for (const r of State.roots) if ((p === r.path || p.startsWith(r.path + '/')) && (!best || r.path.length > best.path.length)) best = r;
  return best;
}
function displayPath(p) {
  const r = rootOf(p);
  return r ? r.name + p.slice(r.path.length) : p;
}
function notify(text) {
  State.notes.unshift({ text, time: Date.now() });
  State.notes.length = Math.min(State.notes.length, 30);
}

// ---------------------------------------------------------------- window manager

const WM = {
  wins: new Map(),
  z: 100,
  active: null,
  open(app) {
    let w = this.wins.get(app.id);
    if (w) { w.restore(); return w; }
    w = new Win(app);
    this.wins.set(app.id, w);
    Taskbar.render();
    return w;
  },
};

class Win {
  constructor(app) {
    this.app = app;
    const n = WM.wins.size;
    const vw = innerWidth, vh = innerHeight;
    const w = Math.min(app.w || 900, vw - 40), h = Math.min(app.h || 560, vh - 80);
    const x = Math.max(10, Math.min(130 + n * 32, vw - w - 10));
    const y = Math.max(54, Math.min(64 + n * 28, vh - h - 10));
    this.body = el('div', { class: 'win-body' });
    this.maxBtn = el('button', { class: 'win-btn', title: 'Maximize', onclick: () => this.toggleMax() }, icon('max'));
    this.titleEl = el('div', { class: 'title', text: app.name });
    this.bar = el('div', { class: 'titlebar', ondblclick: e => { if (!e.target.closest('button')) this.toggleMax(); } },
      appIco(app.ico), this.titleEl,
      el('button', { class: 'win-btn', title: 'Minimize', onclick: () => this.minimize() }, icon('min')),
      this.maxBtn,
      el('button', { class: 'win-btn close', title: 'Close', onclick: () => this.close() }, icon('x')));
    this.grip = el('div', { class: 'resize' });
    this.el = el('section', { class: 'window', style: { left: x + 'px', top: y + 'px', width: w + 'px', height: h + 'px' } }, this.bar, this.body, this.grip);
    this.el.addEventListener('pointerdown', () => this.focus(), true);
    document.body.append(this.el);
    this.drag();
    this.focus();
    this.ctl = app.mount(this.body, this) || {};
  }
  setTitle(t) { this.titleEl.textContent = t; }
  focus() {
    if (WM.active && WM.active !== this) WM.active.el.classList.add('inactive');
    this.el.classList.remove('inactive');
    this.el.style.zIndex = ++WM.z;
    WM.active = this;
    Taskbar.render();
  }
  minimize() {
    this.el.classList.add('hidden');
    this.minimized = true;
    if (WM.active === this) WM.active = null;
    Taskbar.render();
  }
  restore() {
    this.el.classList.remove('hidden');
    this.minimized = false;
    this.focus();
  }
  toggleMax() {
    if (!this.max) {
      this.prev = this.el.getAttribute('style');
      Object.assign(this.el.style, { left: '0px', top: 'var(--bar-h)', width: '100%', height: 'calc(100% - var(--bar-h))' });
      this.el.classList.add('max');
      this.maxBtn.replaceChildren(icon('restore'));
    } else {
      this.el.setAttribute('style', this.prev);
      this.el.style.zIndex = ++WM.z;
      this.el.classList.remove('max');
      this.maxBtn.replaceChildren(icon('max'));
    }
    this.max = !this.max;
  }
  close() {
    if (this.ctl.destroy) this.ctl.destroy();
    this.el.remove();
    WM.wins.delete(this.app.id);
    if (WM.active === this) WM.active = null;
    Taskbar.render();
  }
  drag() {
    const track = (handle, onMove) => handle.addEventListener('pointerdown', e => {
      if (e.button !== 0 || e.target.closest('button') || this.max || innerWidth <= 760) return;
      e.preventDefault();
      const r = this.el.getBoundingClientRect();
      const sx = e.clientX, sy = e.clientY;
      const move = ev => onMove(r, ev.clientX - sx, ev.clientY - sy);
      const up = () => { removeEventListener('pointermove', move); removeEventListener('pointerup', up); };
      addEventListener('pointermove', move);
      addEventListener('pointerup', up);
    });
    track(this.bar, (r, dx, dy) => {
      this.el.style.left = Math.min(innerWidth - 80, Math.max(-r.width + 80, r.left + dx)) + 'px';
      this.el.style.top = Math.min(innerHeight - 40, Math.max(44, r.top + dy)) + 'px';
    });
    track(this.grip, (r, dx, dy) => {
      this.el.style.width = Math.max(320, r.width + dx) + 'px';
      this.el.style.height = Math.max(220, r.height + dy) + 'px';
    });
  }
}

// ---------------------------------------------------------------- taskbar & desktop

const Taskbar = {
  pinned: ['filestation', 'download'],
  render() {
    const box = $('#taskApps');
    box.replaceChildren();
    const ids = [...this.pinned];
    for (const id of WM.wins.keys()) if (!ids.includes(id)) ids.push(id);
    for (const id of ids) {
      const app = APPS[id];
      const w = WM.wins.get(id);
      const b = el('button', {
        class: 'tb-btn' + (w ? ' open' : '') + (w && w.minimized ? ' minimized' : '') + (w && WM.active === w ? ' active' : ''),
        title: app.name,
        onclick: () => {
          if (!w) openApp(id);
          else if (WM.active === w && !w.minimized) w.minimize();
          else w.restore();
        },
      }, appIco(app.ico));
      box.append(b);
    }
  },
};

function openApp(id, arg) {
  closePop();
  const w = WM.open(APPS[id]);
  if (arg && w.ctl.handle) w.ctl.handle(arg);
  return w;
}

const DESKTOP = [
  { app: 'filestation' },
  { app: 'torrents' },
  { app: 'torrentplayer' },
  { app: 'download' },
  { app: 'debrid' },
  { group: 'Multimedia', ico: 'group', apps: ['player', 'photos', 'videos', 'music', 'wallpaper'] },
  { app: 'monitor' },
  { app: 'remote' },
  { app: 'packagecenter' },
  { app: 'terminal', root: true },
  { app: 'screen', root: true },
  { app: 'control', badge: () => controlBadge() },
  { app: 'help' },
];

function controlBadge() {
  let n = 0;
  if (State.roots.length && !State.roots[0].readable) n++;
  if (Auth.pinRequired === false) n++;
  return n;
}

function renderDesktop() {
  const d = $('#desktop');
  d.replaceChildren();
  for (const it of DESKTOP) {
    if (it.root && !State.isRoot) continue;
    const app = it.app ? APPS[it.app] : null;
    const badge = it.badge ? it.badge() : 0;
    const btn = el('button', { class: 'desk-icon' }, appIco(app ? app.ico : it.ico), el('div', { class: 'lbl', text: app ? app.name : it.group }), badge ? el('span', { class: 'badge', text: badge }) : null);
    btn.addEventListener(innerWidth <= 760 ? 'click' : 'dblclick', () => app ? openApp(it.app) : groupPopover(btn, it));
    if (!app) btn.addEventListener('click', () => groupPopover(btn, it));
    d.append(btn);
  }
}

function appGridPopover(anchorBtn, ids, title, three) {
  closePop();
  const grid = el('div', { class: 'pop-grid' + (three ? ' three' : '') });
  const fill = q => {
    grid.replaceChildren();
    for (const id of ids) {
      const a = APPS[id];
      if (q && !a.name.toLowerCase().includes(q.toLowerCase())) continue;
      grid.append(el('button', { class: 'pop-tile', onclick: () => openApp(id) }, appIco(a.ico), a.name));
    }
  };
  const search = el('input', { class: 'search', placeholder: title || 'Search', oninput: e => fill(e.target.value) });
  const pop = el('div', { class: 'popover' }, search, grid);
  fill('');
  document.body.append(pop);
  const r = anchorBtn.getBoundingClientRect();
  const pr = pop.getBoundingClientRect();
  pop.style.left = Math.max(8, Math.min(r.left, innerWidth - pr.width - 8)) + 'px';
  pop.style.top = (anchorBtn.id === 'mainMenuBtn' ? r.bottom + 6 : Math.min(r.top, innerHeight - pr.height - 8)) + 'px';
  if (anchorBtn.classList.contains('desk-icon') && innerWidth > 760) pop.style.left = (r.right + 8) + 'px';
  anchorBtn.classList.add('active');
  openPop = { el: pop, btn: anchorBtn };
  if (innerWidth > 760) search.focus();
}
function groupPopover(btn, it) { appGridPopover(btn, it.apps, 'Search in ' + it.group, true); }

// ---------------------------------------------------------------- File Station

function FileStation(body, win) {
  const st = {
    path: null, back: [], fwd: [], entries: [], shown: [], sel: new Set(), anchor: null,
    sort: store.get('sort', { key: 'name', desc: false }), view: store.get('view', 'list'),
    hidden: store.get('hidden', false), searching: null,
  };

  const backBtn = el('button', { class: 'icon-btn', title: 'Back', onclick: () => history(-1) }, icon('back'));
  const fwdBtn = el('button', { class: 'icon-btn', title: 'Forward', onclick: () => history(1) }, icon('fwd'));
  const upBtn = el('button', { class: 'icon-btn hide-sm', title: 'Parent Folder', onclick: () => st.parent && go(st.parent) }, icon('up'));
  const crumbs = el('div', { class: 'crumbs' });
  const searchIn = el('input', { placeholder: 'Search in this folder', onkeydown: e => {
    if (e.key === 'Enter') doSearch(searchIn.value.trim());
    if (e.key === 'Escape') { searchIn.value = ''; if (st.searching) load(); }
  } });
  const nav = el('div', { class: 'fs-nav' }, backBtn, fwdBtn, upBtn,
    el('button', { class: 'icon-btn', title: 'Refresh', onclick: () => load() }, icon('refresh')),
    crumbs, el('div', { class: 'fs-search' }, icon('search'), searchIn));

  const uploadBtn = el('button', { class: 'btn primary', onclick: e => menuAt(e.currentTarget, [
    { label: 'Upload Files', ico: 'upload', run: () => pick('#filePick') },
    { label: 'Upload Folder', ico: 'folderUp', run: () => pick('#folderPick') },
  ]) }, icon('upload'), 'Upload', icon('chev', 'chev'));
  const createBtn = el('button', { class: 'btn', onclick: () => mkdir() }, icon('newfolder'), el('span', { class: 'hide-sm', text: 'New Folder' }));
  const actionBtn = el('button', { class: 'btn', onclick: e => menuAt(e.currentTarget, actionItems()) }, 'Actions', icon('chev', 'chev'));
  const dlBtn = el('button', { class: 'btn', onclick: () => download() }, icon('download'), el('span', { class: 'hide-sm', text: 'Download' }));
  const delBtn = el('button', { class: 'btn hide-sm', onclick: () => del() }, icon('trash'));
  const viewList = el('button', { class: 'icon-btn', title: 'List View', onclick: () => setView('list') }, icon('list'));
  const viewGrid = el('button', { class: 'icon-btn', title: 'Icon View', onclick: () => setView('grid') }, icon('grid'));
  const hidBtn = el('button', { class: 'icon-btn hide-sm', title: 'Show Hidden Files', onclick: () => { st.hidden = !st.hidden; store.set('hidden', st.hidden); render(); } }, icon('eye'));
  const tools = el('div', { class: 'fs-tools' }, uploadBtn, createBtn, actionBtn, dlBtn, delBtn, el('div', { class: 'grow' }), hidBtn, viewList, viewGrid);

  const list = el('div', { class: 'fs-list', tabindex: '0' });
  const status = el('div', { class: 'fs-status' });
  const side = el('div', { class: 'fs-side' });
  const main = el('div', { class: 'fs-main' }, nav, tools, list, status);
  body.append(el('div', { class: 'fs' }, side, main));

  // ---- sidebar tree
  function treeNode(name, path, depth) {
    const kids = el('div', { class: 'tree-kids hidden' });
    const caret = el('span', { class: 'caret' }, icon('caret'));
    const row = el('div', { class: 'tn', 'data-path': path, title: path }, caret, fileIcon('folder'), el('span', { class: 'nm', text: name }));
    let loaded = false;
    const toggle = async open => {
      const willOpen = open ?? kids.classList.contains('hidden');
      kids.classList.toggle('hidden', !willOpen);
      caret.classList.toggle('open', willOpen);
      if (willOpen && !loaded) {
        loaded = true;
        try {
          const r = await api('list', { path });
          const dirs = r.entries.filter(e => e.dir && !e.hidden).sort((a, b) => a.name.localeCompare(b.name));
          if (!dirs.length) caret.classList.add('none');
          for (const d of dirs) kids.append(treeNode(d.name, d.path, depth + 1));
        } catch { caret.classList.add('none'); }
      }
    };
    caret.addEventListener('click', e => { e.stopPropagation(); toggle(); });
    row.addEventListener('click', () => { go(path); toggle(true); });
    return el('div', { class: 'tree-node' }, row, kids);
  }
  function buildSide() {
    side.replaceChildren(el('div', { class: 'sec', text: 'Storage' }));
    for (const r of State.roots) side.append(treeNode(r.name, r.path, 0));
    const main = State.roots[0];
    if (main) {
      side.append(el('div', { class: 'sec', text: 'Quick Access' }));
      for (const [n, label] of [['DCIM', 'Camera (DCIM)'], ['Pictures', 'Pictures'], ['Download', 'Downloads'], ['Documents', 'Documents'], ['Movies', 'Movies'], ['Music', 'Music']]) {
        const p = main.path + '/' + n;
        side.append(el('div', { class: 'tree-node' }, el('div', { class: 'tn', 'data-path': p, onclick: () => go(p) },
          el('span', { class: 'caret none' }), fileIcon('folder'), el('span', { class: 'nm', text: label }))));
      }
    }
  }
  function markSide() {
    for (const n of side.querySelectorAll('.tn')) n.classList.toggle('cur', n.dataset.path === st.path && !st.searching);
  }

  // ---- navigation
  async function go(path, push = true) {
    if (push && st.path && st.path !== path) { st.back.push(st.path); st.fwd = []; }
    st.path = path;
    st.searching = null;
    searchIn.value = '';
    await load();
  }
  function history(dir) {
    const from = dir < 0 ? st.back : st.fwd, to = dir < 0 ? st.fwd : st.back;
    if (!from.length) return;
    to.push(st.path);
    go(from.pop(), false);
  }
  async function load(keepSel) {
    if (!st.path) return;
    if (!keepSel) st.sel.clear();
    st.searching = null;
    list.replaceChildren(el('div', { class: 'empty', text: 'Loading…' }));
    try {
      const r = await api('list', { path: st.path });
      st.entries = r.entries;
      st.parent = r.parent;
      st.error = null;
    } catch (e) {
      st.entries = [];
      st.parent = null;
      st.error = e.message === 'no_permission'
        ? 'NexShare on phone does not have storage access — open the app on phone and grant "All Files Access"'
        : 'Cannot open this folder (' + e.message + ')';
    }
    render();
  }
  async function doSearch(q) {
    if (!q) return load();
    st.searching = q;
    st.sel.clear();
    list.replaceChildren(el('div', { class: 'empty', text: 'Searching…' }));
    try {
      const r = await api('search', { path: st.path, q });
      st.entries = r.entries;
      st.error = null;
    } catch (e) { st.entries = []; st.error = e.message; }
    render();
  }

  // ---- rendering
  function sorted() {
    const { key, desc } = st.sort;
    const arr = st.entries.filter(e => st.hidden || !e.hidden);
    const cmp = {
      name: (a, b) => a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }),
      size: (a, b) => a.size - b.size,
      mtime: (a, b) => a.mtime - b.mtime,
      type: (a, b) => kindOf(a).localeCompare(kindOf(b)) || extOf(a.name).localeCompare(extOf(b.name)),
    }[key];
    arr.sort((a, b) => (b.dir - a.dir) || (desc ? -cmp(a, b) : cmp(a, b)));
    return arr;
  }

  function renderCrumbs() {
    crumbs.replaceChildren();
    const r = rootOf(st.path || '');
    if (!r) return;
    const parts = st.path.slice(r.path.length).split('/').filter(Boolean);
    let acc = r.path;
    crumbs.append(el('button', { text: r.name, onclick: () => go(r.path) }));
    for (const p of parts) {
      acc += '/' + p;
      const target = acc;
      crumbs.append(el('span', { class: 'sep', text: '›' }), el('button', { text: p, onclick: () => go(target) }));
    }
    if (st.searching) crumbs.append(el('span', { class: 'sep', text: '›' }), el('button', { text: `Search results for "${st.searching}"` }));
    crumbs.scrollLeft = crumbs.scrollWidth;
  }

  function render() {
    st.shown = sorted();
    renderCrumbs();
    markSide();
    backBtn.disabled = !st.back.length;
    fwdBtn.disabled = !st.fwd.length;
    upBtn.disabled = !st.parent;
    viewList.classList.toggle('on', st.view === 'list');
    viewGrid.classList.toggle('on', st.view === 'grid');
    hidBtn.classList.toggle('on', st.hidden);
    win.setTitle('File Station' + (st.path ? ' — ' + displayPath(st.path).split('/').pop() : ''));
    list.replaceChildren();
    if (st.error) { list.append(el('div', { class: 'notice', text: st.error })); updateSel(); return; }
    if (!st.shown.length) {
      list.append(el('div', { class: 'empty' }, fileIcon('folder'), st.searching ? 'No matching files found' : 'This folder is empty — drag files here to upload'));
      updateSel();
      return;
    }
    if (st.view === 'grid') renderGrid(); else renderTable();
    updateSel();
  }

  function renderTable() {
    const th = (key, label, cls) => el('th', {
      class: [cls, st.sort.key === key ? 'sorted' : '', st.sort.key === key && st.sort.desc ? 'desc' : ''].filter(Boolean).join(' '),
      onclick: () => { st.sort = { key, desc: st.sort.key === key ? !st.sort.desc : false }; store.set('sort', st.sort); render(); },
    }, label);
    const allChk = el('input', { type: 'checkbox', onclick: e => { e.stopPropagation(); if (e.target.checked) st.shown.forEach(x => st.sel.add(x.path)); else st.sel.clear(); updateSel(); } });
    const colgroup = el('colgroup', null, el('col', { style: { width: '36px' } }), el('col'), el('col', { style: { width: '96px' } }), el('col', { class: 'col-type', style: { width: '110px' } }), el('col', { class: 'col-date', style: { width: '150px' } }));
    const tbody = el('tbody');
    for (const e of st.shown) {
      const kind = kindOf(e);
      const chk = el('input', { type: 'checkbox', onclick: ev => { ev.stopPropagation(); toggleSel(e.path, ev.target.checked); } });
      const tr = el('tr', { 'data-path': e.path, class: e.hidden ? 'hidden-file' : null },
        el('td', { class: 'chk' }, chk),
        el('td', { class: 'name', title: e.name }, el('div', null, fileIcon(kind), el('span', { text: e.name }))),
        el('td', { class: 'dim', text: e.dir ? '' : fmtSize(e.size) }),
        el('td', { class: 'dim col-type', text: e.dir ? KIND_LABEL.folder : (extOf(e.name).toUpperCase() || KIND_LABEL.file) }),
        el('td', { class: 'dim col-date', text: fmtDate(e.mtime) }));
      bindItem(tr, e);
      tbody.append(tr);
    }
    list.append(el('table', { class: 'files' }, colgroup,
      el('thead', null, el('tr', null, el('th', { class: 'chk' }, allChk), th('name', 'Name'), th('size', 'Size'), th('type', 'Type', 'col-type'), th('mtime', 'Modified', 'col-date'))),
      tbody));
  }

  function renderGrid() {
    const g = el('div', { class: 'grid-files' });
    for (const e of st.shown) {
      const kind = kindOf(e);
      const pic = el('div', { class: 'pic' });
      if (kind === 'image' || kind === 'video') {
        const img = el('img', { loading: 'lazy', src: thumbUrl(e.path, 200), alt: '' });
        img.onerror = () => img.replaceWith(fileIcon(kind));
        pic.append(img);
      } else pic.append(fileIcon(kind));
      const item = el('div', { class: 'gf', 'data-path': e.path, title: e.name }, pic, el('div', { class: 'nm', text: e.name }));
      bindItem(item, e);
      g.append(item);
    }
    list.append(g);
  }

  // ---- selection
  const coarse = matchMedia('(pointer: coarse)').matches;
  function bindItem(node, e) {
    node.addEventListener('click', ev => {
      if (coarse && !ev.ctrlKey && !ev.shiftKey && !st.sel.size) { open(e); return; }
      if (ev.shiftKey && st.anchor) {
        const a = st.shown.findIndex(x => x.path === st.anchor), b = st.shown.findIndex(x => x.path === e.path);
        if (!ev.ctrlKey) st.sel.clear();
        for (let i = Math.min(a, b); i <= Math.max(a, b); i++) st.sel.add(st.shown[i].path);
      } else if (ev.ctrlKey || ev.metaKey || (coarse && st.sel.size)) {
        toggleSel(e.path, !st.sel.has(e.path));
        st.anchor = e.path;
        return;
      } else {
        st.sel.clear();
        st.sel.add(e.path);
        st.anchor = e.path;
      }
      updateSel();
    });
    node.addEventListener('dblclick', () => open(e));
    node.addEventListener('contextmenu', ev => {
      ev.preventDefault();
      if (!st.sel.has(e.path)) { st.sel.clear(); st.sel.add(e.path); st.anchor = e.path; updateSel(); }
      menu(actionItems(), ev.clientX, ev.clientY);
    });
  }
  function toggleSel(p, on) {
    if (on) st.sel.add(p); else st.sel.delete(p);
    updateSel();
  }
  function updateSel() {
    for (const n of list.querySelectorAll('[data-path]')) {
      const on = st.sel.has(n.dataset.path);
      n.classList.toggle('sel', on);
      const c = n.querySelector('input[type=checkbox]');
      if (c) c.checked = on;
    }
    const n = st.sel.size;
    dlBtn.disabled = !n;
    delBtn.disabled = !n;
    const files = st.shown.filter(e => !e.dir).length, dirs = st.shown.length - files;
    const size = selected().reduce((s, e) => s + (e.dir ? 0 : e.size), 0);
    status.replaceChildren(...[
      el('span', { text: `${st.shown.length} items (${dirs} folders, ${files} files)` }),
      n ? el('span', { text: `${n} selected${size ? ' · ' + fmtSize(size) : ''}` }) : null,
      State.clipboard ? el('span', { text: `Clipboard: ${State.clipboard.paths.length} items (${State.clipboard.move ? 'cut' : 'copy'})` }) : null].filter(Boolean));
  }
  const selected = () => st.shown.filter(e => st.sel.has(e.path));

  list.addEventListener('click', e => { if (e.target === list || e.target.classList.contains('grid-files')) { st.sel.clear(); updateSel(); } });
  list.addEventListener('contextmenu', e => {
    if (e.target.closest('[data-path]')) return;
    e.preventDefault();
    st.sel.clear();
    updateSel();
    menu([
      { label: 'New Folder', ico: 'newfolder', run: mkdir },
      { label: 'Upload Files', ico: 'upload', run: () => pick('#filePick') },
      { label: 'Upload Folder', ico: 'folderUp', run: () => pick('#folderPick') },
      State.clipboard && { label: 'Paste', ico: 'paste', run: paste },
      '-',
      { label: 'Refresh', ico: 'refresh', run: () => load() },
    ], e.clientX, e.clientY);
  });
  list.addEventListener('keydown', e => {
    if (e.target.tagName === 'INPUT') return;
    if (e.key === 'Delete') del();
    else if (e.key === 'F2') rename();
    else if (e.key === 'Backspace' && st.parent) go(st.parent);
    else if (e.key === 'Enter' && st.sel.size === 1) open(selected()[0]);
    else if ((e.ctrlKey || e.metaKey) && e.key === 'a') { e.preventDefault(); st.shown.forEach(x => st.sel.add(x.path)); updateSel(); }
    else if ((e.ctrlKey || e.metaKey) && e.key === 'c' && st.sel.size) clip(false);
    else if ((e.ctrlKey || e.metaKey) && e.key === 'x' && st.sel.size) clip(true);
    else if ((e.ctrlKey || e.metaKey) && e.key === 'v' && State.clipboard) paste();
  });

  // ---- actions
  function actionItems() {
    const sel = selected(), one = sel.length === 1 ? sel[0] : null;
    return [
      { label: 'Open', ico: 'open', disabled: !one, run: () => open(one) },
      { label: 'Download', ico: 'download', disabled: !sel.length, run: download },
      { label: 'Download as ZIP', ico: 'zip', disabled: !sel.length, run: () => download(true) },
      { label: 'Copy Download Link', ico: 'link', disabled: !one || one.dir, run: () => copyLink(one) },
      one && /\.(zip|cbz)$/i.test(one.name) ? { label: 'Abrir conteúdo do ZIP…', ico: 'archive', run: () => openZipPicker(one.path) } : null,
      one && /\.torrent$/i.test(one.name) ? { label: 'Add to Download Station', ico: 'download', run: () => addTorrentFromPhone(one) } : null,
      one && !one.dir && kindOf(one) === 'image' ? { label: 'Set as Wallpaper', ico: 'open', run: () => setWallpaperFromFile(one) } : null,
      '-',
      { label: 'Rename', ico: 'rename', disabled: !one, run: rename },
      { label: 'Copy', ico: 'copy', disabled: !sel.length, run: () => clip(false) },
      { label: 'Cut', ico: 'cut', disabled: !sel.length, run: () => clip(true) },
      { label: 'Paste', ico: 'paste', disabled: !State.clipboard, run: paste },
      '-',
      { label: 'Delete', ico: 'trash', danger: true, disabled: !sel.length, run: del },
    ];
  }
  async function setWallpaperFromFile(e) {
    try { await api('wallpaper', { path: e.path }, { method: 'POST' }); toast('Wallpaper set successfully'); }
    catch (x) { toast('Failed: ' + x.message); }
  }
  async function addTorrentFromPhone(e) {
    try {
      const blob = await (await fetch(fileUrl(e.path))).blob();
      if (await submitDownloads({ urls: '', dest: '', torrentFile: blob })) openApp('download');
    } catch (x) { toast('Failed: ' + x.message); }
  }
  function open(e) {
    if (e.dir) go(e.path);
    else openEntry(e, st.shown.filter(x => !x.dir), st.entries);
  }
  function download(asZip) {
    const sel = selected();
    if (!sel.length) return;
    const url = !asZip && sel.length === 1 && !sel[0].dir ? fileUrl(sel[0].path, true) : '/api/zip' + qs({ p: sel.map(e => e.path) });
    const a = el('a', { href: url, download: '' });
    document.body.append(a);
    a.click();
    a.remove();
  }
  function copyLink(e) {
    const url = location.origin + fileUrl(e.path, true);
    const done = () => toast('Link copied (recipient must be on the same Wi-Fi)');
    if (navigator.clipboard && isSecureContext) navigator.clipboard.writeText(url).then(done, () => dialog({ title: 'Download Link', input: url, cancel: false }));
    else dialog({ title: 'Download Link', input: url, cancel: false });
  }
  async function mkdir() {
    const name = await dialog({ title: 'New Folder', input: 'New Folder', okText: 'Create' });
    if (!name) return;
    try { await api('mkdir', { path: st.path, name }, { method: 'POST' }); await load(); toast('Folder created'); }
    catch (e) { toast(e.status === 409 ? 'A file or folder with this name already exists' : 'Failed to create: ' + e.message); }
  }
  async function rename() {
    const e = selected()[0];
    if (!e) return;
    const name = await dialog({ title: 'Rename', input: e.name, okText: 'Save' });
    if (!name || name === e.name) return;
    try { await api('rename', { path: e.path, name }, { method: 'POST' }); await load(); }
    catch (err) { toast(err.status === 409 ? 'A file or folder with this name already exists' : 'Failed to rename'); }
  }
  async function del() {
    const sel = selected();
    if (!sel.length) return;
    const ok = await dialog({ title: 'Delete ' + (sel.length === 1 ? `"${sel[0].name}"` : sel.length + ' items') + '?', message: 'Files will be permanently deleted from the phone.', okText: 'Delete', danger: true });
    if (!ok) return;
    try { const r = await api('delete', { p: sel.map(e => e.path) }, { method: 'POST' }); toast(`Deleted ${r.deleted} items`); }
    catch (e) { toast('Failed to delete: ' + e.message); }
    load();
  }
  function clip(move) {
    State.clipboard = { move, paths: selected().map(e => e.path) };
    toast((move ? 'Cut ' : 'Copied ') + State.clipboard.paths.length + ' items — navigate to target folder and click Paste');
    updateSel();
  }
  async function paste() {
    const c = State.clipboard;
    if (!c) return;
    toast((c.move ? 'Moving' : 'Copying') + '…');
    try {
      const r = await api(c.move ? 'move' : 'copy', { dst: st.path, p: c.paths }, { method: 'POST' });
      toast(`${c.move ? 'Moved' : 'Copied'} ${r.done} items`);
      if (c.move) State.clipboard = null;
    } catch (e) { toast('Failed: ' + e.message); }
    load();
  }
  function pick(sel) {
    const input = $(sel);
    input.value = '';
    input.onchange = () => {
      Uploader.add([...input.files].map(f => ({ file: f, rel: (f.webkitRelativePath || '').split('/').slice(0, -1).join('/') })), st.path);
    };
    input.click();
  }
  function setView(v) { st.view = v; store.set('view', v); render(); }

  // ---- drag & drop upload
  let dragDepth = 0;
  list.addEventListener('dragenter', e => { if ([...e.dataTransfer.types].includes('Files')) { dragDepth++; list.classList.add('drop'); } });
  list.addEventListener('dragleave', () => { if (--dragDepth <= 0) { dragDepth = 0; list.classList.remove('drop'); } });
  list.addEventListener('dragover', e => { e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; });
  list.addEventListener('drop', async e => {
    e.preventDefault();
    dragDepth = 0;
    list.classList.remove('drop');
    if (!st.path || st.error) return;
    const dest = st.path;
    const entries = [...e.dataTransfer.items].map(i => i.webkitGetAsEntry && i.webkitGetAsEntry()).filter(Boolean);
    if (!entries.length) { Uploader.add([...e.dataTransfer.files].map(f => ({ file: f, rel: '' })), dest); return; }
    const out = [];
    const walk = (entry, rel) => new Promise(res => {
      if (entry.isFile) entry.file(f => { out.push({ file: f, rel }); res(); }, () => res());
      else {
        const reader = entry.createReader();
        const sub = rel ? rel + '/' + entry.name : entry.name;
        const batch = () => reader.readEntries(async items => {
          if (!items.length) return res();
          for (const it of items) await walk(it, sub);
          batch();
        }, () => res());
        batch();
      }
    });
    for (const en of entries) await walk(en, '');
    Uploader.add(out, dest);
  });

  const onUploaded = dest => { if (st.path && !st.searching && (dest === st.path || dest.startsWith(st.path + '/'))) load(true); };
  Uploader.listeners.add(onUploaded);

  buildSide();
  go(State.roots[0] ? State.roots[0].path : null);
  setTimeout(() => list.focus(), 50);

  return {
    handle(arg) { if (arg.path) go(arg.path); if (arg.search) { searchIn.focus(); } },
    destroy() { Uploader.listeners.delete(onUploaded); },
  };
}

// ---------------------------------------------------------------- media apps

function MediaApp(type) {
  return (body, win) => {
    const pane = el('div', { class: 'photos' }, el('div', { class: 'empty', text: 'Loading…' }));
    body.append(pane);
    (async () => {
      try {
        const r = await api('media', { type });
        const items = r.entries;
        pane.replaceChildren();
        if (!items.length) { pane.append(el('div', { class: 'empty', text: 'No files found' })); return; }
        win.setTitle(win.app.name + ' — ' + items.length + ' items');
        if (type === 'audio') return renderAudio(items);
        let month = null, grid = null;
        for (const e of items) {
          const d = new Date(e.mtime);
          const m = d.toLocaleDateString('th-TH', { year: 'numeric', month: 'long' });
          if (m !== month) { month = m; grid = el('div', { class: 'ph-grid' }); pane.append(el('h4', { text: m }), grid); }
          const img = el('img', { loading: 'lazy', src: thumbUrl(e.path, 260), alt: '' });
          img.onerror = () => img.replaceWith(fileIcon(kindOf(e)));
          grid.append(el('div', { class: 'ph', title: e.name, onclick: () => openEntry(e, items) }, img,
            kindOf(e) === 'video' ? el('div', { class: 'play' }, icon('play')) : null));
        }
      } catch (e) { pane.replaceChildren(el('div', { class: 'notice', text: 'Failed to load: ' + e.message })); }
    })();
    function renderAudio(items) {
      const tbody = el('tbody');
      items.forEach(e => tbody.append(el('tr', { onclick: () => openEntry(e, items) },
        el('td', { class: 'name' }, el('div', null, fileIcon('audio'), el('span', { text: e.name }))),
        el('td', { class: 'dim', text: fmtSize(e.size), style: { width: '90px' } }))));
      pane.append(el('div', { style: { margin: '12px 2px', color: 'var(--text-2)' }, text: 'Click a track to play in Media Player (continuous playback)' }), el('table', { class: 'files' }, tbody));
    }
    return {};
  };
}

// ---------------------------------------------------------------- Resource Monitor / Control Panel / Help

const Stats = { hist: { cpu: [], ram: [], up: [], down: [] }, last: null, listeners: new Set() };

function drawChart(canvas, series, max, noLabels) {
  const dpr = devicePixelRatio || 1;
  const w = canvas.clientWidth, h = canvas.clientHeight;
  if (!w || !h) return;
  if (canvas.width !== w * dpr) { canvas.width = w * dpr; canvas.height = h * dpr; }
  const c = canvas.getContext('2d');
  c.setTransform(dpr, 0, 0, dpr, 0, 0);
  c.clearRect(0, 0, w, h);
  const top = max || Math.max(1, ...series.flatMap(s => s.data)) * 1.15;
  c.strokeStyle = '#e7ecf2';
  c.lineWidth = 1;
  c.fillStyle = '#9aa6b2';
  c.font = '10px sans-serif';
  const steps = noLabels ? 5 : 2;
  for (let i = 0; i <= steps; i++) {
    const y = Math.round(4 + (h - 8) * i / steps) + .5;
    c.beginPath(); c.moveTo(0, y); c.lineTo(w, y); c.stroke();
    if (max && !noLabels) c.fillText(String(Math.round(max * (1 - i / steps))), 2, i === 0 ? y + 10 : y - 2);
  }
  const N = 60;
  for (const s of series) {
    if (!s.data.length) continue;
    c.beginPath();
    s.data.forEach((v, i) => {
      const x = w - (s.data.length - 1 - i) * (w / (N - 1));
      const y = 4 + (h - 8) * (1 - Math.min(v, top) / top);
      i ? c.lineTo(x, y) : c.moveTo(x, y);
    });
    c.strokeStyle = s.color;
    c.lineWidth = 1.8;
    c.stroke();
    c.lineTo(w, h); c.lineTo(w - (s.data.length - 1) * (w / (N - 1)), h); c.closePath();
    c.globalAlpha = .1; c.fillStyle = s.color; c.fill(); c.globalAlpha = 1;
  }
}

function ResourceMonitor(body) {
  const mk = (title) => {
    const val = el('div', { class: 'val', text: '-' });
    const sub = el('div', { class: 'dim', style: { fontSize: '12px', color: 'var(--text-2)', marginBottom: '8px' } });
    const cv = el('canvas', { class: 'chart' });
    return { card: el('div', { class: 'card' }, el('h4', { text: title }), val, sub, cv), val, sub, cv };
  };
  const cpu = mk('CPU'), ram = mk('Memory (RAM)'), net = mk('Network');

  // ---- big state card: is the phone being used right now?
  const stateDot = el('span', { style: { fontSize: '30px', lineHeight: '1' }, text: '○' });
  const stateTitle = el('div', { style: { fontSize: '20px', fontWeight: '700' }, text: 'Checking…' });
  const stateSub = el('div', { class: 'dim', style: { fontSize: '12px', marginTop: '2px' }, text: '' });
  const stateRaw = el('div', { class: 'dim', style: { fontSize: '11px', marginTop: '4px', opacity: '.75', fontFamily: 'monospace' }, text: '' });
  const stateCard = el('div', {
    class: 'card',
    style: { display: 'flex', alignItems: 'center', gap: '14px', gridColumn: '1 / -1', padding: '14px 16px' }
  }, stateDot, el('div', { style: { minWidth: 0, flex: 1 } }, stateTitle, stateSub, stateRaw));

  // ---- small cards
  const miniCard = (title) => {
    const box = el('div', { style: { display: 'flex', flexDirection: 'column', gap: '2px' } });
    return { card: el('div', { class: 'card' }, el('h4', { text: title }), box), box };
  };
  const netNow = miniCard('Network now');
  const netDown = el('div', { style: { fontSize: '22px', fontWeight: '700', color: 'var(--down)' }, text: '▼ -' });
  const netUp = el('div', { style: { fontSize: '22px', fontWeight: '700', color: 'var(--up)' }, text: '▲ -' });
  const netExtra = el('div', { class: 'dim', style: { fontSize: '12px', marginTop: '4px' }, text: '' });
  netNow.box.append(netDown, netUp, netExtra);

  const dial = miniCard('Connected clients / transfers');
  const dialVal = el('div', { style: { fontSize: '22px', fontWeight: '700' }, text: '-' });
  const dialExtra = el('div', { class: 'dim', style: { fontSize: '12px', marginTop: '4px' }, text: '' });
  dial.box.append(dialVal, dialExtra);

  const store = miniCard('Storage (phone)');
  const storeVal = el('div', { style: { fontSize: '22px', fontWeight: '700' }, text: '-' });
  const storeBar = el('div', { class: 'bar', style: { height: '8px', margin: '6px 0' } }, el('i', { style: { width: '0%' } }));
  const storeExtra = el('div', { class: 'dim', style: { fontSize: '12px' }, text: '' });
  store.box.append(storeVal, storeBar, storeExtra);

  // ---- active transfers
  const txBody = el('div', { style: { display: 'flex', flexDirection: 'column', gap: '6px' } });
  const txCard = el('div', { class: 'card', style: { gridColumn: '1 / -1' } },
    el('h4', { style: { margin: '0 0 8px' }, text: 'Active transfers' }), txBody);

  // ---- session
  const sessBody = el('div', { class: 'dim', style: { fontSize: '12px', lineHeight: '1.7' }, text: '' });
  const sessCard = el('div', { class: 'card', style: { gridColumn: '1 / -1' } },
    el('h4', { style: { margin: '0 0 6px' }, text: 'This session (since you opened this window)' }), sessBody);

  body.append(el('div', { class: 'pane' },
    el('div', { style: { display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: '10px' } },
      stateCard, netNow.card, dial.card, store.card, cpu.card, ram.card, net.card, txCard, sessCard)));

  const sess = { start: Date.now(), rx0: null, tx0: null, samples: 0 };

  /** Torrents the phone is working on, straight from /api/system (one request, always in sync). */
  function renderTasks(list) {
    const rows = (list || []).slice(0, 6);
    if (!rows.length) {
      txBody.replaceChildren(el('div', { class: 'dim', style: { fontSize: '13px' }, text: 'Nothing downloading, seeding or streaming right now.' }));
      return;
    }
    txBody.replaceChildren(...rows.map(t => {
      const pct = t.total > 0 ? Math.min(100, (t.done / t.total) * 100) : 0;
      const rate = (t.speed || 0) > 0 ? `▼ ${fmtRate(t.speed)}` : ((t.upSpeed || 0) > 0 ? `▲ ${fmtRate(t.upSpeed)}` : 'idle');
      const eta = (t.eta > 0 && (t.speed || 0) > 0) ? ' · ' + fmtEta(t.eta) : '';
      return el('div', { style: { display: 'flex', alignItems: 'center', gap: '10px' } },
        el('div', { style: { flex: 1, minWidth: 0 } },
          el('div', { style: { fontSize: '12px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }, title: t.name, text: t.name }),
          el('div', { class: 'bar', style: { height: '5px', marginTop: '3px' } }, el('i', { style: { width: pct.toFixed(1) + '%' } }))),
        el('div', { style: { fontSize: '11px', color: 'var(--text-3)', flex: 'none', textAlign: 'right' } },
          el('div', { text: rate + eta }),
          el('div', { text: `${pct.toFixed(0)}% · ${t.status}${t.addon ? ' · Nuvio' : ''}${t.streaming ? ' · ▶' : ''}` })));
    }));
  }

  const setBusy = (busy, title, sub) => {
    stateDot.textContent = busy ? '●' : '○';
    stateDot.style.color = busy ? '#16a34a' : 'var(--text-3)';
    stateTitle.textContent = title;
    stateTitle.style.color = busy ? '#15803d' : 'var(--text-2)';
    stateSub.textContent = sub;
  };

  const update = s => {
    const H = Stats.hist;
    cpu.val.textContent = s.cpu >= 0 ? s.cpu.toFixed(0) + '%' : 'N/A';
    cpu.sub.textContent = s.cores + ' Cores';
    drawChart(cpu.cv, [{ data: H.cpu, color: '#1e6fd9' }], 100);
    const used = s.memTotal - s.memAvail;
    ram.val.textContent = Math.round(used / s.memTotal * 100) + '%';
    ram.sub.textContent = fmtSize(used) + ' / ' + fmtSize(s.memTotal);
    drawChart(ram.cv, [{ data: H.ram, color: '#2fb36b' }], 100);
    net.val.replaceChildren(el('span', { style: { color: 'var(--up)' }, text: '▲ ' + fmtRate(Stats.up) }), ' ', el('span', { style: { color: 'var(--down)', fontSize: '20px' }, text: '▼ ' + fmtRate(Stats.down) }));
    net.sub.textContent = 'Total RX ' + fmtSize(s.rx) + ' · Total TX ' + fmtSize(s.tx);
    drawChart(net.cv, [{ data: H.up, color: '#2a8cf0' }, { data: H.down, color: '#32b67a' }]);

    // network now
    netDown.textContent = '▼ ' + fmtRate(Stats.down);
    netUp.textContent = '▲ ' + fmtRate(Stats.up);
    netExtra.textContent = `Session: ↓ ${fmtSize(sess.down)} · ↑ ${fmtSize(sess.up)}`;

    // clients / transfers
    const conns = s.connections || 0, files = s.transfers || 0, tor = s.activeTorrents || 0;
    dialVal.textContent = files > 0 ? `${files} file transfer${files > 1 ? 's' : ''}` : (conns > 0 ? `${conns} connection${conns > 1 ? 's' : ''}` : '0');
    const bits = [];
    if (conns) bits.push(`${conns} open connection${conns > 1 ? 's' : ''}`);
    if (files) bits.push(`serving ${files} file${files > 1 ? 's' : ''}`);
    if (tor) bits.push(`downloading ${tor} torrent${tor > 1 ? 's' : ''}`);
    if (s.streaming) bits.push(`${s.streaming} streaming`);
    if (s.webdav) bits.push('WebDAV on');
    if (s.tunnel) bits.push('tunnel up');
    dialExtra.textContent = bits.length ? bits.join(' · ') : 'nothing open';

    // storage
    const dTotal = s.diskTotal || 0, dFree = s.diskFree || 0;
    const dPct = dTotal > 0 ? ((dTotal - dFree) / dTotal) * 100 : 0;
    storeVal.textContent = dTotal > 0 ? Math.round(dPct) + '% used' : '-';
    storeBar.firstChild.style.width = dPct.toFixed(1) + '%';
    storeBar.firstChild.style.background = dPct >= 90 ? '#e5484d' : (dPct >= 75 ? '#f0a020' : '#2fb36b');
    storeExtra.textContent = dTotal > 0 ? `${fmtSize(dFree)} free of ${fmtSize(dTotal)}` : '-';

    // session counters
    if (sess.rx0 == null) { sess.rx0 = s.rx; sess.tx0 = s.tx; }
    sess.down = Math.max(0, s.rx - sess.rx0);
    sess.up = Math.max(0, s.tx - sess.tx0);
    sess.samples++;
    const mins = Math.max(1, Math.round((Date.now() - sess.start) / 60000));
    sessBody.textContent = `↓ ${fmtSize(sess.down)} received · ↑ ${fmtSize(sess.up)} sent in ${mins} min` +
      (s.uptime ? ` · phone uptime ${fmtUptime(s.uptime)}` : '') +
      (s.battery >= 0 ? ` · battery ${s.battery}%${s.charging ? ' (charging)' : ''}` : '') +
      (s.temp || s.batteryTemp ? ` · ${(s.temp || s.batteryTemp)}°C` : '');

    // state: any torrent the phone is working on counts as "in use"
    const list = s.torrents || [];
    renderTasks(list);
    const torrentDown = s.torrentDown || 0, torrentUp = s.torrentUp || 0;
    const working = list.filter(t => (t.speed || 0) > 0 || (t.upSpeed || 0) > 0
      || t.status === 'downloading' || t.status === 'metadata' || t.status === 'checking');
    const busy = files > 0 || working.length > 0 || torrentDown > 4096 || torrentUp > 4096
      || Stats.down > 51200 || Stats.up > 51200;
    stateRaw.textContent = `files ${files} · torrents ${list.length} (${working.length} ativos) · swarm ↓ ${fmtRate(torrentDown)} ↑ ${fmtRate(torrentUp)} · net ↓ ${fmtRate(Stats.down || 0)} · conexões ${conns}`;
    if (busy) {
      const why = [];
      if (working.length) {
        const t = working[0];
        const pct = t.total > 0 ? Math.round((t.done / t.total) * 100) : 0;
        why.push(`"${t.name.slice(0, 48)}" ${t.status} ${pct}%` + (t.speed > 0 ? ' · ' + fmtRate(t.speed) : ''));
        if (working.length > 1) why.push(`+${working.length - 1} torrent(s)`);
      }
      if (files > 0) why.push(`servindo ${files} arquivo${files > 1 ? 's' : ''}`);
      if (s.streaming) why.push(`${s.streaming} em reprodução`);
      if (!working.length && Stats.up > 51200) why.push('enviando ' + fmtRate(Stats.up));
      setBusy(true, 'Em uso agora', why.join(' · ') || 'tráfego de rede');
    } else {
      const last = s.lastTransfer || 0;
      const idle = last ? ` — último arquivo servido há ${fmtAgo(Date.now() - last)}` : '';
      const paused = list.filter(t => t.status === 'paused').length;
      setBusy(false, 'Ocioso', 'nada sendo transferido ou assistido agora' + idle + (paused ? ` · ${paused} torrent(s) pausado(s)` : ''));
    }
  };

  async function loadTasks() {
    try {
      const r = await api('dl/list');
      const tasks = (r.tasks || []).filter(t => t.type === 'torrent');
      const active = tasks.filter(t => (t.speed || 0) > 0 || (t.upSpeed || 0) > 0);
      if (!active.length) {
        txBody.replaceChildren(el('div', { class: 'dim', style: { fontSize: '13px' }, text: 'Nothing downloading or seeding right now.' }));
        return;
      }
      txBody.replaceChildren(...active.slice(0, 8).map(t => {
        const pct = t.total > 0 ? Math.min(100, t.done / t.total * 100) : 0;
        return el('div', { style: { display: 'flex', alignItems: 'center', gap: '10px' } },
          el('div', { style: { flex: 1, minWidth: 0 } },
            el('div', { style: { fontSize: '12px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }, title: t.name, text: t.name }),
            el('div', { class: 'bar', style: { height: '5px', marginTop: '3px' } }, el('i', { style: { width: pct.toFixed(1) + '%' } }))),
          el('div', { style: { fontSize: '11px', color: 'var(--text-3)', flex: 'none', textAlign: 'right' } },
            el('div', { text: `▼ ${fmtRate(t.speed || 0)}` }),
            el('div', { text: `${pct.toFixed(0)}% · ${t.status}` })));
      }));
    } catch { /* offline */ }
  }

  Stats.listeners.add(update);
  if (Stats.last) update(Stats.last);
  return {
    destroy() {
      Stats.listeners.delete(update);
    }
  };
}

const updateState = { info: null, checking: false, installing: false, progress: 0, statusMsg: '', pollTimer: null, autoUpdate: true, activeTransfers: 0, busy: false };

function ControlPanel(body) {
  const pane = el('div', { class: 'pane' });
  body.append(pane);
  const fetchUpdateStatus = async () => {
    try {
      const st = await api('update/status');
      if (st) {
        updateState.autoUpdate = st.autoUpdate !== false;
        updateState.activeTransfers = st.activeTransfers || 0;
        updateState.busy = !!st.busy;
        if (st.state === 'waiting_idle' || st.state === 'downloading' || st.state === 'installing') {
          updateState.statusMsg = st.msg || updateState.statusMsg;
          if (st.state === 'downloading') updateState.progress = st.progress || 0;
        }
      }
    } catch (ignored) { }
  };
  fetchUpdateStatus();
  const render = () => {
    const s = Stats.last || {};
    const urls = [
      s.ip ? `https://${s.ip}:${s.httpsPort || 8443}` : null,
      `https://${s.host || 'nexshare.local'}:${s.httpsPort || 8443}`,
      s.ip ? `http://${s.ip}:${s.port || 8080}` : null,
      `http://${s.host || 'nexshare.local'}:${s.port || 8080}`,
    ].filter(Boolean);
    fill(pane,
      el('h2', { text: 'Control Panel' }),
      State.roots.length && !State.roots[0].readable ? el('div', { class: 'notice', style: { margin: '0 0 12px' }, text: 'Storage access not granted — open NexShare app on your phone and tap "Grant All Files Access"' }) : null,
      Auth.pinRequired === false ? el('div', { class: 'notice', style: { margin: '0 0 12px' }, text: 'PIN authentication is disabled. Anyone on this Wi-Fi can access your files. You can enable PIN in the mobile app.' }) : null,
      el('div', { class: 'notice', style: { margin: '0 0 12px' }, text: '⚡ O servidor fica ativo com a tela desligada e em segundo plano. Se o Android ainda suspender o app, abra as configurações do NexShare e desative a otimização de bateria (sem isso alguns fabricantes encerram serviços à força).' }),
      el('div', { class: 'card' }, el('h3', { text: 'Device', style: { marginTop: 0 } }), el('dl', { class: 'kv' },
        el('dt', { text: 'Server Name' }), el('dd', { text: s.name || '-' }),
        el('dt', { text: 'Model' }), el('dd', { text: s.model || '-' }),
        el('dt', { text: 'Android' }), el('dd', { text: s.android ? `${s.android} (API ${s.sdk})` : '-' }),
        el('dt', { text: 'Temperature' }), el('dd', { text: (s.temp || s.batteryTemp) ? `${(s.temp || s.batteryTemp).toFixed(1)}°C${s.batteryTemp && s.temp && s.temp !== s.batteryTemp ? ` (Battery: ${s.batteryTemp}°C)` : ''}` : '-' }),
        el('dt', { text: 'Power Mode' }), el('dd', { text: s.eco ? '🌱 Eco Mode (Power Saving / Idle)' : '⚡ Active Mode' }),
        el('dt', { text: 'Uptime' }), el('dd', { text: s.uptime ? fmtUptime(s.uptime) : '-' }))),
      el('div', { class: 'card' }, el('h3', { text: 'Network Access (HTTP & HTTPS)', style: { marginTop: 0 } }), el('dl', { class: 'kv' },
        ...urls.flatMap((u, i) => [el('dt', { text: u.startsWith('https:') ? 'HTTPS Secure' : 'HTTP Standard' }), el('dd', { class: 'mono', text: u })]),
        el('dt', { text: 'Security' }), el('dd', { text: State.authMode === 'password' ? 'Username + Password' : State.authMode === 'pin' ? 'PIN required' : 'No login required' }))),
      el('div', { class: 'card' },
        el('h3', { text: 'WebDAV Network Drive (Windows)', style: { marginTop: 0 } }),
        el('div', { style: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '8px' } },
          el('span', { text: 'WebDAV Status: ' + (State.webdavEnabled ? 'Enabled' : 'Disabled') }),
          el('button', {
            class: 'btn' + (State.webdavEnabled ? ' danger' : ''),
            onclick: async () => {
              try {
                const res = await api('webdav/toggle', null, { method: 'POST', body: JSON.stringify({ enabled: !State.webdavEnabled }) });
                State.webdavEnabled = res.enabled;
                toast(State.webdavEnabled ? 'WebDAV enabled' : 'WebDAV disabled');
                render();
              } catch (e) { toast('Failed to change WebDAV status'); }
            }
          }, State.webdavEnabled ? 'Disable WebDAV' : 'Enable WebDAV')
        ),
        State.webdavEnabled ? el('div', null,
          el('dl', { class: 'kv' },
            el('dt', { text: 'HTTPS WebDAV (certificado próprio)' }), el('dd', { class: 'mono', style: { cursor: 'pointer', color: '#16a34a', fontWeight: 'bold' }, title: 'Click to copy', onclick: e => { navigator.clipboard?.writeText(e.target.textContent); toast('HTTPS WebDAV URL copied'); }, text: (s.ip ? `https://${s.ip}:${s.httpsPort || 8443}/dav/` : `https://${s.host || 'nexshare.local'}:${s.httpsPort || 8443}/dav/`) }),
            el('dt', { text: 'HTTP WebDAV' }), el('dd', { class: 'mono', style: { cursor: 'pointer', color: '#1e6fd9' }, title: 'Click to copy', onclick: e => { navigator.clipboard?.writeText(e.target.textContent); toast('HTTP WebDAV URL copied'); }, text: (s.ip ? `http://${s.ip}:${s.port || 8080}/dav/` : `http://${s.host || 'nexshare.local'}:${s.port || 8080}/dav/`) }),
            State.tunnelUrl ? el('dt', { text: 'Público (Internet) ⭐' }) : null,
            State.tunnelUrl ? el('dd', { class: 'mono', style: { cursor: 'pointer', color: '#7c3aed', fontWeight: 'bold' }, title: 'Click to copy', onclick: e => { navigator.clipboard?.writeText(e.target.textContent); toast('URL pública WebDAV copiada'); }, text: State.tunnelUrl + '/dav/' }) : null,
            el('dt', { text: 'Username' }), el('dd', { class: 'mono', text: (s.authMode === 'password' && s.username ? s.username : (s.authMode === 'none' ? 'Nenhum' : 'nexshare (ou qualquer nome)')) }),
            el('dt', { text: 'Password' }), el('dd', { text: (s.authMode === 'password' ? 'Sua senha configurada no app' : (s.authMode === 'none' ? 'Nenhuma (acesso livre)' : 'PIN de 6 dígitos exibido no app')) }),
            el('dt', { text: 'Windows: caminho fácil (sem admin)' }), el('dd', { class: 'mono', style: { fontSize: '11px', wordBreak: 'break-all' }, text: `map-drive.ps1 -PhoneIP ${s.ip || 'IP'} -Key ${State.apiKey || '<API key>'}` }),
            el('dt', { text: 'Windows CMD (manual, HTTPS)' }), el('dd', { class: 'mono', style: { fontSize: '11px', wordBreak: 'break-all' }, text: (s.authMode === 'none' ? `net use * https://${s.ip || 'IP'}:${s.httpsPort || 8443}/dav/` : `net use * https://${s.ip || 'IP'}:${s.httpsPort || 8443}/dav/ /user:${(s.authMode === 'password' && s.username ? s.username : 'nexshare')} *`) }),
            el('dt', { text: 'Windows CMD (HTTP)' }), el('dd', { class: 'mono', style: { fontSize: '11px', wordBreak: 'break-all' }, text: (s.authMode === 'none' ? `net use * http://${s.ip || 'IP'}:${s.port || 8080}/dav/` : `net use * http://${s.ip || 'IP'}:${s.port || 8080}/dav/ /user:${(s.authMode === 'password' && s.username ? s.username : 'nexshare')} *`) }),
            State.tunnelUrl ? el('dt', { text: 'Windows CMD (Internet, funciona em faculdade/empresa)' }) : null,
            State.tunnelUrl ? el('dd', { class: 'mono', style: { fontSize: '11px', wordBreak: 'break-all' }, text: (s.authMode === 'none' ? `net use * ${State.tunnelUrl}/dav/` : `net use * ${State.tunnelUrl}/dav/ /user:${(s.authMode === 'password' && s.username ? s.username : 'nexshare')} *`) }) : null
          ),
          el('div', { style: { display: 'flex', gap: '8px', marginTop: '10px', flexWrap: 'wrap' } },
            el('a', { class: 'btn', href: '/api/cert', download: 'nexshare-ca.crt' }, icon('download'), 'Baixar Certificado SSL (nexshare-ca.crt)'),
            el('a', { class: 'btn', href: '/api/webdav/reg', download: 'windows-webdav.reg' }, icon('download'), 'Baixar windows-webdav.reg'),
            el('button', { class: 'btn', onclick: () => checkWebdavHealth() }, icon('info'), ' Verificar Saúde WebDAV')
          ),
          el('div', { id: 'webdavHealthOut', style: { display: 'none', marginTop: '10px', padding: '10px', background: '#f1f5f9', border: '1px solid #e2e8f0', borderRadius: '6px', fontSize: '12px', fontFamily: 'monospace', whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: '200px', overflow: 'auto' } }),
          el('div', { class: 'hint', style: { marginTop: '8px', fontSize: '12px', lineHeight: '1.5' }, html: '<b>Erro 67 / “rede não encontrada”?</b> O Windows costuma recusar WebDAV com certificado próprio ou atrás de proxy. No PC, rode <b>map-drive.ps1</b>: ele abre uma ponte local (sem admin, sem certificado, sem registro) e monta a unidade sozinho. Se preferir o caminho direto, ligue o <b>Acesso Remoto</b> e use a URL <b>pública HTTPS</b> acima.' })
        ) : el('div', { class: 'hint', text: 'Ative para mapear os arquivos do celular como unidade de rede Z: no Windows.' })
      ),
      el('div', { class: 'card' },
        el('h3', { text: 'App Updates', style: { marginTop: 0 } }),
        el('div', { style: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '8px' } },
          el('div', null,
            el('b', { text: `NexShare v${s.version || '1.10.2'}` }),
            el('span', { style: { marginLeft: '8px', fontSize: '11px', textTransform: 'uppercase', background: '#eef3fb', color: '#1e6fd9', padding: '2px 6px', borderRadius: '4px' }, text: (s.flavor || 'normal') + ' edition' })
          ),
          el('button', {
            class: 'btn',
            disabled: updateState.checking || updateState.installing,
            onclick: async () => {
              updateState.checking = true;
              render();
              try {
                updateState.info = await api('update/check');
              } catch (err) {
                toast('Failed to check for updates: ' + (err.message || err));
              } finally {
                updateState.checking = false;
                render();
              }
            }
          }, updateState.checking ? 'Checking...' : 'Check for Updates')
        ),
        el('label', { style: { display: 'flex', alignItems: 'center', gap: '8px', cursor: 'pointer', margin: '8px 0', fontSize: '13px' } },
          el('input', {
            type: 'checkbox',
            checked: updateState.autoUpdate !== false,
            onchange: async e => {
              const on = e.target.checked;
              updateState.autoUpdate = on;
              try {
                await api('update/auto', null, { method: 'POST', body: JSON.stringify({ enabled: on }) });
                toast(on ? 'Auto-update enabled' : 'Auto-update disabled');
              } catch { toast('Failed to update setting'); }
              render();
            }
          }),
          el('span', { text: 'Auto-update in background (only when no files are transferring)' })
        ),
        updateState.activeTransfers > 0
          ? el('div', { style: { fontSize: '12px', color: '#d97706', marginBottom: '8px' }, text: `⏳ Active file transfers: ${updateState.activeTransfers} (updates will pause until transfers finish)` })
          : el('div', { style: { fontSize: '12px', color: '#64748b', marginBottom: '8px' }, text: '✓ Transfer guard: Idle (safe to update)' }),
        updateState.statusMsg && (updateState.statusMsg.includes('Waiting') || updateState.statusMsg.includes('transfer'))
          ? el('div', { style: { background: '#fef3c7', color: '#92400e', padding: '8px 10px', borderRadius: '4px', fontSize: '12px', marginBottom: '8px', border: '1px solid #fde68a' }, text: updateState.statusMsg })
          : null,
        updateState.info ? el('div', { style: { marginTop: '10px', padding: '10px', background: '#f8fafc', borderRadius: '6px', border: '1px solid #e2e8f0' } },
          updateState.info.hasUpdate ? [
            el('div', { style: { color: '#0d9488', fontWeight: 'bold', marginBottom: '6px' }, text: `🎉 New version available: v${updateState.info.latestVersion}` }),
            updateState.info.title ? el('div', { style: { fontWeight: '600', fontSize: '13px' }, text: updateState.info.title }) : null,
            updateState.info.notes ? el('pre', { style: { whiteSpace: 'pre-wrap', maxHeight: '100px', overflowY: 'auto', fontSize: '11px', background: '#fff', padding: '6px', border: '1px solid #cbd5e1', borderRadius: '4px', margin: '6px 0' }, text: updateState.info.notes }) : null,

            updateState.installing ? el('div', { style: { margin: '10px 0' } },
              el('div', { style: { fontSize: '12px', fontWeight: 'bold', marginBottom: '4px', color: '#1e6fd9' }, text: updateState.statusMsg || 'Updating...' }),
              el('div', { style: { height: '8px', background: '#e2e8f0', borderRadius: '4px', overflow: 'hidden' } },
                el('div', { style: { height: '100%', background: '#1e6fd9', width: (updateState.progress || 0) + '%', transition: 'width 0.3s ease' } })
              )
            ) : null,

            el('div', { style: { display: 'flex', gap: '8px', marginTop: '8px', flexWrap: 'wrap' } },
              el('button', {
                class: 'btn primary',
                disabled: updateState.installing,
                onclick: async () => {
                  updateState.installing = true;
                  updateState.progress = 0;
                  updateState.statusMsg = 'Initiating download on phone...';
                  render();
                  try {
                    await api('update/install', null, { method: 'POST', body: JSON.stringify({ url: updateState.info.downloadUrl }) });
                    
                    const pollStatus = async () => {
                      if (!updateState.installing) return;
                      try {
                        const st = await api('update/status');
                        if (st.state === 'downloading') {
                          updateState.progress = st.progress;
                          updateState.statusMsg = `Downloading APK: ${st.progress}% (${fmtSize(st.downloaded)} / ${fmtSize(st.total)})`;
                          render();
                          updateState.pollTimer = setTimeout(pollStatus, 800);
                        } else if (st.state === 'installing') {
                          updateState.progress = 100;
                          updateState.statusMsg = 'Installing via Root... NexShare will restart in a few seconds.';
                          render();
                          setTimeout(waitForRestart, 3000);
                        } else if (st.state === 'prompt') {
                          updateState.installing = false;
                          updateState.statusMsg = '✓ APK downloaded. Installer prompt opened on your phone screen!';
                          toast('Check your phone screen to confirm installation!');
                          render();
                        } else if (st.state === 'error') {
                          updateState.installing = false;
                          updateState.statusMsg = '❌ ' + (st.error || 'Update failed');
                          toast('Update error: ' + (st.error || 'Failed'));
                          render();
                        } else if (st.state === 'done') {
                          updateState.progress = 100;
                          updateState.statusMsg = 'Update applied! Server restarting...';
                          render();
                          setTimeout(waitForRestart, 2000);
                        }
                      } catch (err) {
                        if (updateState.installing) {
                          updateState.statusMsg = 'Server restarting with new version... Reconnecting...';
                          render();
                          setTimeout(waitForRestart, 2500);
                        }
                      }
                    };

                    const waitForRestart = async () => {
                      try {
                        const sess = await api('session');
                        if (sess) {
                          toast('🎉 NexShare updated successfully! Reloading...');
                          setTimeout(() => location.reload(), 1200);
                          return;
                        }
                      } catch (ignored) { }
                      setTimeout(waitForRestart, 2000);
                    };

                    pollStatus();
                  } catch (err) {
                    updateState.installing = false;
                    toast('Failed to start update: ' + (err.message || err));
                    render();
                  }
                }
              }, updateState.installing ? 'Updating on phone...' : (updateState.info.flavor === 'root' ? 'Install Update via Root' : 'Send & Install on Phone')),
              el('a', { class: 'btn', href: updateState.info.downloadUrl, target: '_blank', text: 'Download APK to PC' })
            )
          ] : [
            el('div', { style: { color: '#16a34a', fontSize: '13px' }, text: `✓ NexShare is up to date (v${updateState.info.currentVersion})` }),
            updateState.info.downloadUrl ? el('div', { style: { marginTop: '8px' } },
              el('a', { class: 'btn', href: updateState.info.downloadUrl, target: '_blank', text: 'Re-download Current APK' })
            ) : null
          ]
        ) : null
      ),
      el('div', { class: 'card' }, el('h3', { text: 'Storage', style: { marginTop: 0 } }),
        ...State.roots.map(r => el('div', { class: 'meter' },
          el('div', { class: 'row' }, el('span', { text: r.name }), el('b', { text: r.total ? `${fmtSize(r.total - r.free)} / ${fmtSize(r.total)}` : '-' })),
          el('div', { class: 'bar' }, el('i', { style: { width: r.total ? ((r.total - r.free) / r.total * 100) + '%' : 0 } }))))),
      Auth.pinRequired ? el('button', { class: 'btn', onclick: () => Auth.logout() }, icon('logout'), 'Sign Out') : null);
  };
  render();

  async function checkWebdavHealth() {
    const out = $('#webdavHealthOut');
    out.style.display = 'block';
    out.textContent = 'Verificando…';
    try {
      const r = await api('webdav/health');
      const lines = [
        `WebDAV: ${r.enabled ? 'Habilitado ✓' : 'Desabilitado ✗'}`,
        `Modo Auth: ${r.authMode || '?'}`,
        `Storage: ${r.storageRoot || '?'}`,
        `  Existe: ${r.storageExists ? 'Sim' : 'Não'}`,
        `  Legível: ${r.storageReadable ? 'Sim' : 'Não'}`,
        `  Gravável: ${r.storageWritable ? 'Sim' : 'Não'}`,
        `Espaço Total: ${r.totalSpace ? fmtSize(r.totalSpace) : '?'}`,
        `Espaço Livre: ${r.freeSpace ? fmtSize(r.freeSpace) : '?'}`,
        `IP Local: ${r.lanIp || '?'}`,
        `URL LAN: ${r.lanUrl || '?'}`,
        `URL LAN HTTPS: ${r.lanHttpsUrl || '?'}`,
        `MDNS Host: ${r.mdnsHost || '?'}`,
        r.tunnelUrl ? `Tunnel URL: ${r.tunnelUrl}` : 'Tunnel: Desabilitado',
        `Usuário: ${r.username || '?'}`,
        `Senha/PIN: ${r.pin || '?'}`,
        `Windows Registry: ${r.windowsRegistryUrl || '?'}`,
        r.error ? `ERRO: ${r.error}` : ''
      ].filter(x => x !== '');
      out.textContent = lines.join('\n');
    } catch (e) {
      out.textContent = 'Erro: ' + e.message;
    }
  }

  const t = setInterval(render, 5000);
  return { destroy() { clearInterval(t); } };
}

function Help(body) {
  body.append(el('div', { class: 'pane help', html: `
    <h2>How to use NexShare</h2>
    <ol>
      <li>Open the <b>NexShare</b> app on your phone and tap <b>Start Sharing</b> (grant All Files Access on first run).</li>
      <li>Connect your computer or other devices to the same Wi-Fi (or to this phone's Hotspot).</li>
      <li>Open a browser at <b class="mono">http://nexshare.local:8080</b> (or use the IP address link shown in the app).</li>
      <li>Enter the 6-digit PIN shown in the app.</li>
    </ol>
    <h3>File Station</h3>
    <ul>
      <li>Double-click to open folders or preview files (single tap on touch screens).</li>
      <li>Drag and drop files or folders from your computer into the window to upload.</li>
      <li>Select multiple files with Ctrl/Shift or checkboxes, then click Download (multi-file zip on the fly).</li>
      <li>Right-click to Rename / Copy / Cut / Paste / Delete · Shortcuts: Delete, F2, Ctrl+A, Ctrl+C, Ctrl+X, Ctrl+V</li>
    </ul>
    <h3>Troubleshooting Connection</h3>
    <ul>
      <li>Most Windows 10/11, macOS, iPhone, and Linux devices support .local mDNS names. If unreachable, use the IP link instead.</li>
      <li>Public Wi-Fi networks with AP/Client Isolation enabled will prevent devices from connecting to each other.</li>
    </ul>` }));
}

// ---------------------------------------------------------------- viewer

const Viewer = {
  scale: 1, rot: 0, panX: 0, panY: 0, img: null,
  open(entry, list) {
    this.list = list && list.length ? list : [entry];
    this.i = Math.max(0, this.list.findIndex(e => e.path === entry.path));
    if (!this.el) this.build();
    this.el.classList.remove('hidden');
    this.show();
  },
  resetTransform() {
    this.scale = 1;
    this.rot = 0;
    this.panX = 0;
    this.panY = 0;
    this.applyTransform();
  },
  applyTransform() {
    const target = this.img || this.video;
    if (!target) return;
    target.style.transform = `translate(${this.panX}px, ${this.panY}px) rotate(${this.rot}deg) scale(${this.scale})`;
    if (this.img) this.img.classList.toggle('zoomed', this.scale > 1);
  },
  zoom(delta) {
    this.scale = Math.min(6, Math.max(0.2, +(this.scale + delta).toFixed(2)));
    if (this.scale <= 1) { this.panX = 0; this.panY = 0; }
    this.applyTransform();
  },
  rotate(deg) {
    this.rot = (this.rot + deg) % 360;
    this.applyTransform();
  },
  build() {
    this.title = el('div', { class: 'v-title' });
    this.body = el('div', { class: 'v-body' });
    this.prev = el('button', { class: 'v-nav prev', onclick: () => this.step(-1) }, icon('back'));
    this.next = el('button', { class: 'v-nav next', onclick: () => this.step(1) }, icon('fwd'));
    this.btnZoomIn = el('button', { title: 'Zoom in (+)', onclick: () => this.zoom(0.25) }, icon('zoomIn'));
    this.btnZoomOut = el('button', { title: 'Zoom out (-)', onclick: () => this.zoom(-0.25) }, icon('zoomOut'));
    this.btnRotate = el('button', { title: 'Rotate 90°', onclick: () => this.rotate(90) }, icon('rotate'));
    this.btnFit = el('button', { title: 'Reset zoom (fit)', onclick: () => this.resetTransform() }, icon('fit'));

    this.el = el('div', { id: 'viewer', class: 'hidden' },
      el('div', { class: 'v-top' }, this.title,
        this.btnZoomIn, this.btnZoomOut, this.btnRotate, this.btnFit,
        el('button', { title: 'Open in new tab', onclick: () => window.open(fileUrl(this.cur.path), '_blank') }, icon('open')),
        el('button', { title: 'Download', onclick: () => { location.href = fileUrl(this.cur.path, true); } }, icon('download')),
        el('button', { title: 'Close', onclick: () => this.close() }, icon('x'))),
      el('div', { style: { flex: 1, minHeight: 0, position: 'relative', display: 'flex', overflow: 'hidden' } }, this.body, this.prev, this.next));

    document.body.append(this.el);

    // Mouse wheel zoom
    this.body.addEventListener('wheel', e => {
      if (!this.img || kindOf(this.cur) !== 'image') return;
      e.preventDefault();
      this.zoom(e.deltaY < 0 ? 0.25 : -0.25);
    }, { passive: false });

    // Drag pan when zoomed
    let dragging = false, lx = 0, ly = 0;
    this.body.addEventListener('mousedown', e => {
      if (e.target !== this.img || this.scale <= 1) return;
      dragging = true;
      lx = e.clientX;
      ly = e.clientY;
      this.img.classList.add('dragging');
      e.preventDefault();
    });
    window.addEventListener('mousemove', e => {
      if (!dragging || !this.img) return;
      this.panX += e.clientX - lx;
      this.panY += e.clientY - ly;
      lx = e.clientX;
      ly = e.clientY;
      this.applyTransform();
    });
    window.addEventListener('mouseup', () => {
      if (dragging && this.img) {
        dragging = false;
        this.img.classList.remove('dragging');
      }
    });

    // Double click to zoom in / fit
    this.body.addEventListener('dblclick', e => {
      if (e.target !== this.img) return;
      if (this.scale > 1) this.resetTransform();
      else { this.scale = 2; this.applyTransform(); }
    });

    addEventListener('keydown', e => {
      if (this.el.classList.contains('hidden') || $('#modal')) return;
      if (e.key === 'Escape') this.close();
      if (e.key === 'ArrowLeft') this.step(-1);
      if (e.key === 'ArrowRight') this.step(1);
      if (e.key === '+' || e.key === '=') this.zoom(0.25);
      if (e.key === '-' || e.key === '_') this.zoom(-0.25);
      if (e.key === '0') this.resetTransform();
      if (e.key === 'r' || e.key === 'R') this.rotate(90);
    });

    let sx = null;
    this.body.addEventListener('touchstart', e => { sx = e.touches[0].clientX; }, { passive: true });
    this.body.addEventListener('touchend', e => {
      if (sx == null) return;
      const dx = e.changedTouches[0].clientX - sx;
      if (Math.abs(dx) > 60 && kindOf(this.cur) === 'image' && this.scale <= 1) this.step(dx < 0 ? 1 : -1);
      sx = null;
    });
  },
  step(d) {
    const n = this.i + d;
    if (n < 0 || n >= this.list.length) return;
    this.i = n;
    this.show();
  },
  async show() {
    this.resetTransform();
    if (this.video) {
      this.video.pause();
      this.video.removeAttribute('src');
      this.video.load();
      this.video = null;
    }
    const e = this.cur = this.list[this.i];
    this.title.textContent = `${e.name}  ·  ${fmtSize(e.size)}` + (this.list.length > 1 ? `  ·  ${this.i + 1}/${this.list.length}` : '');
    this.prev.classList.toggle('hidden', this.i === 0);
    this.next.classList.toggle('hidden', this.i >= this.list.length - 1);
    const url = fileUrl(e.path);
    const k = kindOf(e);
    this.body.replaceChildren();
    this.img = null;

    const isImg = k === 'image';
    const isVid = k === 'video';
    this.btnZoomIn.classList.toggle('hidden', !isImg);
    this.btnZoomOut.classList.toggle('hidden', !isImg);
    this.btnRotate.classList.toggle('hidden', !isImg && !isVid);
    this.btnFit.classList.toggle('hidden', !isImg && !isVid);

    if (isImg) {
      this.img = el('img', { src: imageUrl(e.path), alt: e.name });
      this.img.onerror = () => { this.img.src = fileUrl(e.path); };
      this.body.append(this.img);
    }
    else if (isVid) {
      const note = el('div', { class: 'v-info', style: { marginBottom: '6px', fontSize: '12px' } });
      this.video = el('video', { controls: true, autoplay: true, playsinline: true });
      const video = this.video;
      this.body.append(note, video);
      setPlayableSource(video, e.path, text => {
        if (this.video === video) { note.classList.remove('hidden'); note.textContent = text; }
      }, () => this.video === video).then(() => {
        if (this.video !== video) return;
        note.classList.add('hidden');
        video.play().catch(() => {});
      }).catch(err => {
        if (this.video !== video) return;
        note.classList.remove('hidden');
        note.replaceChildren(
          el('div', { text: 'Não foi possível tocar no navegador: ' + err.message }),
          el('div', { style: { display: 'flex', gap: '8px', marginTop: '8px', flexWrap: 'wrap' } },
            el('a', { class: 'btn', href: fileUrl(e.path, true), text: 'Baixar arquivo', style: { textDecoration: 'none' } }),
            el('a', { class: 'btn', href: 'vlc://' + location.origin + fileUrl(e.path), text: 'Abrir no VLC', style: { textDecoration: 'none' } })));
      });
    }
    else if (k === 'audio') this.body.append(el('div', { class: 'v-info' }, fileIcon('audio'), el('audio', { src: url, controls: true, autoplay: true })));
    else if (k === 'pdf') this.body.append(el('iframe', { src: url }));
    else if (k === 'text' && e.size < 4 * 1024 * 1024) {
      const pre = el('pre', { text: 'Loading…' });
      this.body.append(pre);
      try {
        const r = await fetch(url, { headers: { Range: 'bytes=0-1048575' } });
        pre.textContent = await r.text();
      } catch { pre.textContent = 'Failed to load'; }
    } else {
      this.body.append(el('div', { class: 'v-info' }, fileIcon(k), el('div', { text: 'No preview available for this file type' }),
        el('div', { style: { margin: '6px 0 16px' }, text: `${fmtSize(e.size)} · Modified ${fmtDate(e.mtime)}` }),
        el('a', { class: 'btn primary', href: fileUrl(e.path, true), style: { textDecoration: 'none' } }, icon('download'), 'Download')));
    }
  },
  close() {
    this.el.classList.add('hidden');
    if (this.video) {
      this.video.pause();
      this.video.removeAttribute('src');
      this.video.load();
      this.video = null;
    }
    this.body.replaceChildren();
    this.img = null;
    this.resetTransform();
  },
};

// ---------------------------------------------------------------- uploads

const Uploader = {
  queue: [], active: 0, listeners: new Set(),
  add(items, dest) {
    if (!items.length || !dest) return;
    $('#uploads').classList.remove('hidden');
    for (const it of items) {
      const bar = el('i');
      const s = el('span', { class: 's', text: 'Queued' });
      const node = el('div', { class: 'u-item' }, el('div', { class: 'r1' }, el('span', { class: 'n', text: (it.rel ? it.rel + '/' : '') + it.file.name, title: it.file.name }), s), el('div', { class: 'bar' }, bar));
      $('#uList').prepend(node);
      this.queue.push({ ...it, dest, node, bar, s });
    }
    this.pump();
    this.badge();
  },
  pump() {
    while (this.active < 2 && this.queue.length) this.send(this.queue.shift());
  },
  send(job) {
    this.active++;
    this.badge();
    const xhr = new XMLHttpRequest();
    const t0 = performance.now();
    xhr.open('POST', '/api/upload' + qs({ path: job.dest, name: job.file.name, rel: job.rel || null, mtime: job.file.lastModified || null }));
    xhr.upload.onprogress = e => {
      if (!e.lengthComputable) return;
      const pct = e.loaded / e.total * 100;
      job.bar.style.width = pct + '%';
      const secs = (performance.now() - t0) / 1000;
      job.s.textContent = `${pct.toFixed(0)}% · ${fmtRate(e.loaded / Math.max(secs, .1))}`;
    };
    xhr.onload = () => {
      const ok = xhr.status === 200;
      job.node.classList.add(ok ? 'done' : 'err');
      job.bar.style.width = '100%';
      job.s.textContent = ok ? fmtSize(job.file.size) + ' ✓' : 'Failed';
      if (xhr.status === 401) Auth.show();
      this.finish(job, ok);
    };
    xhr.onerror = () => {
      job.node.classList.add('err');
      job.s.textContent = 'Connection lost';
      this.finish(job, false);
    };
    xhr.send(job.file);
  },
  finish(job, ok) {
    this.active--;
    const dest = job.rel ? job.dest + '/' + job.rel : job.dest;
    clearTimeout(this.refreshT);
    this.refreshT = setTimeout(() => this.listeners.forEach(f => f(job.dest)), 300);
    if (!this.active && !this.queue.length) {
      const done = $('#uList').querySelectorAll('.u-item.done').length;
      notify(`Uploaded ${done} files to ${displayPath(dest)}`);
      toast(ok ? 'Upload complete' : 'Upload finished (some files failed)');
    }
    this.pump();
    this.badge();
  },
  badge() {
    const n = this.active + this.queue.length;
    const b = $('#uploadsBadge');
    b.textContent = n;
    b.classList.toggle('hidden', !n);
    $('#uTitle').textContent = n ? `Uploading (${n})` : 'Uploads';
  },
};
$('#uClose').onclick = () => $('#uploads').classList.add('hidden');
$('#uClear').onclick = () => $('#uList').querySelectorAll('.u-item.done, .u-item.err').forEach(n => n.remove());
$('#uploadsBtn').onclick = () => $('#uploads').classList.toggle('hidden');

// ---------------------------------------------------------------- widgets & polling

function setBar(id, pct) {
  const b = $('#' + id);
  b.style.width = Math.max(0, Math.min(100, pct)) + '%';
  b.parentElement.classList.toggle('warn', pct >= 75 && pct < 90);
  b.parentElement.classList.toggle('crit', pct >= 90);
}

async function poll() {
  try {
    const s = await api('system');
    const H = Stats.hist;
    if (Stats.last) {
      const dt = Math.max(.5, (s.time - Stats.last.time) / 1000);
      Stats.up = Math.max(0, (s.tx - Stats.last.tx) / dt);
      Stats.down = Math.max(0, (s.rx - Stats.last.rx) / dt);
    } else { Stats.up = Stats.down = 0; }
    const ram = (s.memTotal - s.memAvail) / s.memTotal * 100;
    const push = (a, v) => { a.push(v); if (a.length > 60) a.shift(); };
    push(H.cpu, Math.max(0, s.cpu));
    push(H.ram, ram);
    push(H.up, Stats.up);
    push(H.down, Stats.down);
    Stats.last = s;

    $('#serverName').textContent = s.name;
    $('#hName').textContent = s.name;
    $('#hIp').textContent = s.ip || '-';
    $('#hHost').textContent = s.host + ':' + s.port;
    $('#hUp').textContent = fmtUptime(s.uptime);
    if ($('#hEco')) {
      $('#hEco').textContent = s.eco ? '🌱 Eco (Idle)' : '⚡ Active';
      $('#hEco').style.color = s.eco ? '#16a34a' : '#1e6fd9';
    }
    const warn = (s.battery >= 0 && s.battery <= 15 && !s.charging) || s.diskFree / s.diskTotal < .05;
    $('#hState').textContent = warn ? 'Warning' : 'Good';
    $('#hState').style.color = warn ? '#e8742a' : '';
    $('#hSub').textContent = warn ? (s.diskFree / s.diskTotal < .05 ? 'Storage is almost full' : 'Battery is low') : 'Your phone is running normally';
    $('#rCpu').textContent = s.cpu >= 0 ? s.cpu.toFixed(0) + '%' : 'N/A';
    setBar('rCpuBar', Math.max(0, s.cpu));
    $('#rRam').textContent = ram.toFixed(0) + '%';
    setBar('rRamBar', ram);

    const tempVal = s.temp != null && s.temp > 0 ? s.temp : (s.batteryTemp != null && s.batteryTemp > 0 ? s.batteryTemp : null);
    if ($('#rTemp')) {
      $('#rTemp').textContent = tempVal != null ? tempVal.toFixed(1) + '°C' : '-';
      const tempPct = tempVal != null ? Math.max(0, Math.min(100, ((tempVal - 20) / 50) * 100)) : 0;
      setBar('rTempBar', tempPct);
    }
    $('#rUp').textContent = fmtRate(Stats.up);
    $('#rDown').textContent = fmtRate(Stats.down);
    drawChart($('#rChart'), [{ data: H.cpu, color: '#1e9ad6' }, { data: H.ram, color: '#2fb36b' }], 100, true);
    const used = s.diskTotal - s.diskFree;
    $('#sPct').textContent = (used / s.diskTotal * 100).toFixed(0) + '%';
    setBar('sBar', used / s.diskTotal * 100);
    $('#sUsed').textContent = fmtSize(used) + ' / ' + fmtSize(s.diskTotal);
    $('#sFree').textContent = fmtSize(s.diskFree);
    $('#sBat').textContent = s.battery >= 0 ? s.battery + '%' + (s.charging ? ' ⚡ Charging' : '') : '-';
    Stats.listeners.forEach(f => f(s));
  } catch { /* server unreachable or logged out */ }
}

function initWidgets() {
  const panel = $('#widgets'), btn = $('#widgetsBtn'), pin = $('#wPin');
  const hiddenW = new Set(store.get('hiddenWidgets', []));
  const apply = () => {
    const show = store.get('widgets', true);
    panel.classList.toggle('hidden', !show);
    btn.classList.toggle('active', show);
    const pinned = store.get('pinned', false);
    pin.classList.toggle('on', pinned);
    panel.style.zIndex = pinned ? 4500 : 50;
    for (const id of ['wHealth', 'wRes', 'wStorage']) $('#' + id).classList.toggle('hidden', hiddenW.has(id));
  };
  btn.onclick = () => { store.set('widgets', !store.get('widgets', true)); apply(); };
  $('#wMin').onclick = () => { store.set('widgets', false); apply(); };
  pin.onclick = () => { store.set('pinned', !store.get('pinned', false)); apply(); };
  $('#wAdd').onclick = e => menuAt(e.currentTarget, [['wHealth', 'System Health'], ['wRes', 'Resource Monitor'], ['wStorage', 'Storage']].map(([id, label]) => ({
    label: (hiddenW.has(id) ? '☐ ' : '☑ ') + label,
    run: () => { hiddenW.has(id) ? hiddenW.delete(id) : hiddenW.add(id); store.set('hiddenWidgets', [...hiddenW]); apply(); },
  })));
  apply();
}

// ---------------------------------------------------------------- Download Station

const DL_STATUS = { queued: 'Queued', downloading: 'Downloading', paused: 'Paused', done: 'Completed', seeding: 'Seeding', error: 'Error', checking: 'Checking', metadata: 'Fetching metadata', choose: 'Waiting for file selection' };
const DL_STATUS_PT = { queued: 'Na fila', downloading: 'Baixando', paused: 'Pausado', done: 'Concluído', seeding: 'Semeando', error: 'Erro', checking: 'Verificando', metadata: 'Buscando metadados', choose: 'Escolha os arquivos' };

function fmtEta(secs) {
  if (!isFinite(secs) || secs <= 0) return '';
  if (secs < 60) return Math.ceil(secs) + 's';
  if (secs < 3600) return Math.ceil(secs / 60) + 'm';
  return (secs / 3600).toFixed(1) + 'h';
}

function addDownloadDialog(info, preset) {
  return new Promise(resolve => {
    const onlyTorrents = !!info.torrentsOnly;
    const urls = el('textarea', { placeholder: onlyTorrents ? 'magnet:?xt=urn:btih:...' : 'https://example.com/file.zip\nmagnet:?xt=urn:btih:...', spellcheck: 'false', rows: 4 });
    if (preset) urls.value = preset;
    const dest = el('input', { value: info.defaultDir || '', spellcheck: 'false' });
    let torrentFile = null;
    const tName = el('span', { class: 'hint' });
    const tPick = el('input', { type: 'file', accept: '.torrent,application/x-bittorrent', class: 'hidden', onchange: () => {
      torrentFile = tPick.files[0] || null;
      tName.textContent = torrentFile ? ' ' + torrentFile.name : '';
    } });
    const close = v => { wrap.remove(); resolve(v); };
    const wrap = el('div', { id: 'modal', onmousedown: e => { if (e.target === wrap) close(null); } },
      el('div', { class: 'dialog wide' },
        el('div', { class: 'd-head', text: onlyTorrents ? 'Adicionar Torrent' : 'Criar Tarefa de Download' }),
        el('div', { class: 'd-body' },
          el('label', { class: 'f', text: onlyTorrents ? 'Magnet link (magnet:?xt=urn:btih:...)' : 'Links (um por linha) — http, https, ou magnet' }), urls,
          info.torrent ? el('div', { style: { marginTop: '10px' } },
            el('button', { class: 'btn', onclick: () => tPick.click() }, icon('upload'), 'Escolher arquivo .torrent'), tName, tPick)
            : el('div', { class: 'hint', text: 'BitTorrent não suportado neste dispositivo' }),
          el('label', { class: 'f', text: 'Salvar na pasta do celular' }), dest,
          el('div', { class: 'hint', text: onlyTorrents
            ? 'Primeiro o NexShare busca os metadados e mostra a lista de arquivos. Nada é baixado antes de você confirmar a seleção.'
            : 'O celular baixa em segundo plano mesmo se você fechar esta página' })),
        el('div', { class: 'd-foot' },
          el('button', { class: 'btn', text: 'Cancelar', onclick: () => close(null) }),
          el('button', { class: 'btn primary', text: onlyTorrents ? 'Buscar metadados e escolher arquivos' : 'Próximo: Selecionar Arquivos', onclick: () => close({ urls: urls.value, dest: dest.value.trim(), torrentFile, torrentsOnly: onlyTorrents, showFilePicker: true }) }))));
    document.body.append(wrap);
    urls.focus();
  });
}

/**
 * Shows a qBittorrent-style file picker for a torrent task.
 * The task should already be added and paused (metadata fetched).
 */
function showTorrentFilePicker(taskId, dest, onConfirm) {
  // Fetch file list
  api('debrid/files', { id: taskId }).then(data => {
    if (!data.files || !data.files.length) {
      toast('Nenhum arquivo encontrado no torrent');
      if (onConfirm) onConfirm(null);
      return;
    }
    showFilePickerDialog(data.task, data.files, dest, onConfirm);
  }).catch(e => {
    toast('Erro ao buscar arquivos: ' + e.message);
    if (onConfirm) onConfirm(null);
  });
}

function showFilePickerDialog(task, files, defaultDest, onConfirm) {
  const st = {
    files: files,
    checked: new Set(files.filter(f => f.selected !== 0).map(f => f.id)),
    dirty: false,
    dest: defaultDest
  };

  // Build folder tree
  const root = { name: '', dirs: new Map(), files: [] };
  const byId = new Map(files.map(f => [f.id, f]));
  for (const f of files) {
    const parts = String(f.path || f.name || '').split('/').filter(Boolean);
    parts.pop();
    let node = root;
    for (const part of parts) {
      if (!node.dirs.has(part)) node.dirs.set(part, { name: part, dirs: new Map(), files: [] });
      node = node.dirs.get(part);
    }
    node.files.push(f);
  }
  const idsUnder = (node) => {
    const out = node.files.map(f => f.id);
    for (const d of node.dirs.values()) out.push(...idsUnder(d));
    return out;
  };
  const sizeUnder = (node) => {
    let total = node.files.reduce((s, f) => s + (Number(f.size) || 0), 0);
    for (const d of node.dirs.values()) total += sizeUnder(d);
    return total;
  };

  const isVideoName = (n) => /\.(mp4|mkv|avi|mov|webm|flv|m4v|ts|m2ts|mpg|mpeg|wmv|vob|ogv)$/i.test(n || '');

  const destInput = el('input', { value: st.dest || '', spellcheck: 'false', readOnly: true, style: { flex: 1, marginRight: '8px', color: '#667085', background: '#f6f8fb' } });
  const pickDestBtn = el('span', { class: 'dim', style: { fontSize: '12px', whiteSpace: 'nowrap' }, text: 'definida ao adicionar' });
  const applyBtn = el('button', { class: 'btn primary', onclick: () => apply() }, 'Confirmar e Baixar');
  const countLabel = el('span', { class: 'dim', style: { fontSize: '12px' } });

  function refreshCount() {
    const totalSize = [...st.checked].reduce((s, id) => s + (byId.get(id)?.size || 0), 0);
    countLabel.textContent = `${st.checked.size} de ${files.length} arquivo(s) · ${fmtSize(totalSize)}`;
    applyBtn.disabled = st.checked.size === 0 && !st.dirty;
  }

  function toggleSubtree(node, on) {
    for (const id of idsUnder(node)) {
      if (on) st.checked.add(id); else st.checked.delete(id);
    }
    st.dirty = true;
    redrawTree();
  }

  const treeBox = el('div', { style: { display: 'flex', flexDirection: 'column', gap: '2px', maxHeight: '400px', overflow: 'auto' } });

  function fileRow(f, depth) {
    const on = st.checked.has(f.id);
    const pct = Math.max(0, Math.min(100, Number(f.progress) || 0));
    const isVideo = isVideoName(f.name);
    const cb = el('input', {
      type: 'checkbox',
      style: { flex: 'none', marginRight: '8px' },
      onchange: e => {
        if (e.target.checked) st.checked.add(f.id); else st.checked.delete(f.id);
        st.dirty = true;
        refreshCount();
      }
    });
    cb.checked = on;
    return el('div', { style: { display: 'flex', alignItems: 'center', gap: '8px', padding: '7px 8px', paddingLeft: (8 + depth * 18) + 'px', borderRadius: '6px', background: on ? '#eef4ff' : '#f8fafc', border: '1px solid #e2e8f0' } },
      cb,
      el('div', { style: { minWidth: 0, flex: 1 } },
        el('div', { style: { fontSize: '13px', fontWeight: on ? '600' : '400', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }, title: f.path || f.name, text: f.name }),
        el('div', { style: { display: 'flex', alignItems: 'center', gap: '8px', marginTop: '3px' } },
          el('div', { class: 'bar', style: { flex: 1, height: '4px', maxWidth: '220px' } }, el('i', { style: { width: pct.toFixed(1) + '%' } })),
          el('span', { style: { fontSize: '11px', color: 'var(--text-3)' }, text: `${pct.toFixed(0)}% de ${fmtSize(f.size)}` }))),
      el('div', { style: { display: 'flex', gap: '6px', flex: 'none', flexWrap: 'wrap' } },
        isVideo ? el('button', {
          class: 'btn', style: { padding: '3px 9px', fontSize: '12px' },
          onclick: () => { if (!st.checked.has(f.id)) { st.checked.add(f.id); st.dirty = true; refreshCount(); } previewFile(f); }
        }, '▶ Preview') : null));
  }

  function folderRow(node, depth) {
    const ids = idsUnder(node);
    const on = ids.filter(id => st.checked.has(id)).length;
    const all = on === ids.length && ids.length > 0;
    const cb = el('input', { type: 'checkbox', style: { flex: 'none', marginRight: '8px' }, onchange: e => toggleSubtree(node, e.target.checked) });
    cb.checked = all;
    cb.indeterminate = !all && on > 0;
    return el('div', { style: { display: 'flex', alignItems: 'center', gap: '8px', padding: '7px 8px', paddingLeft: (8 + depth * 18) + 'px', borderRadius: '6px', background: '#f1f5f9', border: '1px solid #e2e8f0' } },
      cb,
      el('div', { style: { minWidth: 0, flex: 1, fontSize: '13px', fontWeight: '600', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }, text: '📁 ' + node.name }),
      el('span', { class: 'dim', style: { fontSize: '11px', flex: 'none' }, text: `${ids.length} arq. · ${fmtSize(sizeUnder(node))}` }));
  }

  function renderNode(node, depth, out) {
    for (const d of node.dirs.values()) {
      out.push(folderRow(d, depth));
      renderNode(d, depth + 1, out);
    }
    for (const f of node.files) out.push(fileRow(f, depth));
  }

  function redrawTree() {
    const rows = [];
    renderNode(root, 0, rows);
    treeBox.replaceChildren(...rows);
    refreshCount();
  }

  const markAll = (fn) => {
    st.checked = new Set(files.filter(fn).map(f => f.id));
    st.dirty = true;
    redrawTree();
  };

  const bar = el('div', { style: { display: 'flex', gap: '6px', flexWrap: 'wrap', alignItems: 'center', marginBottom: '10px' } },
    el('button', { class: 'btn', onclick: () => markAll(() => true) }, 'Marcar tudo'),
    el('button', { class: 'btn', onclick: () => markAll(() => false) }, 'Desmarcar'),
    el('button', { class: 'btn', onclick: () => markAll(f => isVideoName(f.name)) }, 'Só vídeos'),
    applyBtn,
    countLabel);

  function pickFolder(input) {
    // Use the File Station's folder picker via API
    // For now, just let user type the path
    input.focus();
  }

  async function previewFile(f) {
    // Open in Media Player
    openApp('player', { entry: { path: f.fullPath, name: f.name, size: f.size }, list: [{ path: f.fullPath, name: f.name, size: f.size }] });
  }

  async function apply() {
    applyBtn.disabled = true;
    applyBtn.textContent = 'Aplicando…';
    try {
      // The destination is fixed when the torrent is added; the picker only chooses files.
      const r = await api('debrid/select', null, { method: 'POST', body: JSON.stringify({ id: task.id, files: [...st.checked], start: true }) });
      st.dirty = false;
      toast(st.checked.size === files.length
        ? 'Download iniciado (todos os arquivos)'
        : `Download iniciado — ${st.checked.size} de ${files.length} arquivos selecionados`);
      if (onConfirm) onConfirm({ taskId: task.id, selectedFiles: [...st.checked], dest: st.dest });
      wrap.remove();
    } catch (e) {
      toast('Falha ao aplicar seleção: ' + e.message);
      applyBtn.disabled = false;
      applyBtn.textContent = 'Confirmar e Baixar';
    }
  }

  const close = v => { wrap.remove(); if (onConfirm) onConfirm(null); };
  const wrap = el('div', { id: 'modal', onmousedown: e => { if (e.target === wrap) close(null); } },
    el('div', { class: 'dialog wide', style: { maxWidth: '900px' } },
      el('div', { class: 'd-head', text: `Selecionar arquivos — ${task.name}` }),
      el('div', { class: 'd-body', style: { maxHeight: '60vh', overflow: 'auto' } },
        el('label', { class: 'f', text: 'Pasta de destino no celular' }),
        el('div', { style: { display: 'flex', gap: '8px', marginBottom: '12px', alignItems: 'center' } }, destInput, pickDestBtn),
        el('div', { class: 'hint', style: { marginBottom: '10px' }, text: 'Desmarque arquivos/pastas que não quer baixar. O download só começa após clicar em "Confirmar e Baixar". Para outra pasta, cancele e adicione o torrent de novo.' }),
        bar,
        treeBox),
      el('div', { class: 'd-foot' },
        el('button', { class: 'btn', text: 'Cancelar', onclick: () => close(null) }),
        applyBtn)));
  document.body.append(wrap);
  redrawTree();
  destInput.focus();
}

function waitForTorrentFiles(taskId) {
  return new Promise(resolve => {
    let stopped = false;
    const msg = el('div', { class: 'hint', text: 'Buscando metadados no enxame… isso pode levar alguns segundos.' });
    const stop = v => { if (stopped) return; stopped = true; wrap.remove(); resolve(v); };
    const wrap = el('div', { id: 'modal' },
      el('div', { class: 'dialog' },
        el('div', { class: 'd-head', text: 'Preparando a lista de arquivos' }),
        el('div', { class: 'd-body' }, msg),
        el('div', { class: 'd-foot' }, el('button', { class: 'btn', text: 'Cancelar', onclick: () => stop(null) }))));
    document.body.append(wrap);
    let tries = 0;
    const tick = async () => {
      if (stopped) return;
      try {
        const d = await api('debrid/files', { id: taskId });
        if (d.files && d.files.length) { stopped = true; wrap.remove(); resolve(d); return; }
      } catch { /* metadata not ready yet */ }
      if (++tries >= 40) { stopped = true; wrap.remove(); resolve({ timeout: true }); return; }
      setTimeout(tick, 700);
    };
    tick();
  });
}

async function pickFilesForTask(taskId, dest) {
  const data = await waitForTorrentFiles(taskId);
  if (!data) return null;
  if (data.timeout) return 'timeout';
  return await new Promise(resolve => {
    showFilePickerDialog(data.task, data.files, dest, res => resolve(res));
  });
}

async function submitDownloads(v) {
  if (!v) return false;
  const dest = v.dest;
  const lines = String(v.urls || '').split('\n').map(s => s.trim()).filter(Boolean);
  const magnets = lines.filter(l => l.startsWith('magnet:'));
  const others = v.torrentsOnly ? [] : lines.filter(l => !l.startsWith('magnet:'));
  if (v.torrentsOnly && lines.some(l => !l.startsWith('magnet:'))) {
    toast('No Torrent Downloader use um magnet ou envie um arquivo .torrent; links HTTP são do Download Station.', 6000);
  }
  let ok = false;
  try {
    if (others.length) {
      const r = await api('dl/add', null, { method: 'POST', body: JSON.stringify({ urls: others.join('\n'), dest }) });
      toast(`Adicionadas ${r.added} tarefa(s)`);
      ok = true;
    }
    const pending = [];
    if (v.torrentFile) {
      const r = await fetch('/api/dl/torrent' + qs({ dest: dest || null, paused: 1 }), { method: 'POST', body: v.torrentFile });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || r.status);
      toast('Arquivo .torrent adicionado');
      if (j.ids && j.ids[0]) pending.push(j.ids[0]);
      ok = true;
    }
    for (const m of magnets) {
      // Added in metadata-only mode: nothing is downloaded before the confirmation.
      const r = await api('dl/add', null, { method: 'POST', body: JSON.stringify({ urls: m, dest, paused: true }) });
      if (r.ids && r.ids[0]) pending.push(r.ids[0]);
      ok = true;
    }
    for (const id of pending) {
      const res = await pickFilesForTask(id, dest);
      if (res === 'timeout') {
        toast('Os metadados ainda não chegaram. O torrent continua buscando apenas as informações — nada foi baixado. Use "Selecionar arquivos" quando a lista aparecer.', 7000);
        continue;
      }
      if (!res) {
        await api('dl/remove', { id }, { method: 'POST' }).catch(() => {});
        toast('Torrent cancelado — nada foi baixado');
      }
    }
  } catch (e) { toast('Falha ao adicionar tarefa: ' + e.message, 4000); }
  return ok;
}

function DownloadStation(body, win, opts) {
  const onlyTorrents = !!(opts && opts.torrentsOnly);
  const st = { filter: 'all', tasks: [], info: { torrentsOnly: onlyTorrents }, sel: new Set() };
  const side = el('div', { class: 'ds-side' });
  const tbody = el('tbody');
  const empty = el('div', { class: 'empty hidden' }, fileIcon('archive'), onlyTorrents
    ? 'Nenhum torrent ainda — clique em "Adicionar" e cole um magnet ou envie um .torrent'
    : 'No download tasks yet — click "Create" and paste links or magnets');
  const btn = (ico, label, run, cls) => el('button', { class: 'btn ' + (cls || ''), onclick: run }, icon(ico), el('span', { class: cls === 'primary' ? null : 'hide-sm', text: label }));
  const tools = el('div', { class: 'ds-tools' },
    btn('plus', onlyTorrents ? 'Adicionar torrent' : 'Create', () => create(), 'primary'),
    btn('play', onlyTorrents ? 'Retomar' : 'Resume', () => act('resume')),
    btn('min', onlyTorrents ? 'Pausar' : 'Pause', () => act('pause')),
    btn('trash', onlyTorrents ? 'Excluir' : 'Delete', () => removeSel()),
    el('div', { style: { flex: 1 } }),
    btn('folderUp', onlyTorrents ? 'Abrir pasta' : 'Open Folder', () => openFolder()),
    btn('x', onlyTorrents ? 'Limpar concluídos' : 'Clear Completed', async () => { await api('dl/clear', null, { method: 'POST' }); refresh(); }));
  const table = el('table', { class: 'files dl' },
    el('colgroup', null, el('col', { style: { width: '36px' } }), el('col'), el('col', { style: { width: '90px' } }), el('col', { style: { width: '170px' } }), el('col', { class: 'col-type', style: { width: '120px' } }), el('col', { class: 'col-date', style: { width: '140px' } })),
    el('thead', null, el('tr', null, el('th', { class: 'chk' }), el('th', { text: onlyTorrents ? 'Nome' : 'File Name' }), el('th', { text: onlyTorrents ? 'Tamanho' : 'Size' }), el('th', { text: onlyTorrents ? 'Progresso' : 'Progress' }), el('th', { class: 'col-type', text: onlyTorrents ? 'Velocidade' : 'Speed' }), el('th', { class: 'col-date', text: 'Status' }))),
    tbody);
  body.append(el('div', { class: 'ds' }, side, el('div', { class: 'ds-main' }, tools, el('div', { class: 'ds-list' }, table, empty))));

  const FILTERS = onlyTorrents
    ? [['all', 'Todos'], ['active', 'Baixando'], ['paused', 'Aguardando escolha'], ['done', 'Concluídos'], ['error', 'Erro']]
    : [['all', 'All'], ['active', 'Downloading'], ['done', 'Completed'], ['paused', 'Paused'], ['error', 'Error']];
  const match = (t, f) => f === 'all' || (f === 'active' ? ['downloading', 'queued', 'checking', 'seeding'].includes(t.status) : f === 'paused' ? ['paused', 'choose', 'metadata'].includes(t.status) : t.status === f);

  function render() {
    side.replaceChildren(...FILTERS.map(([k, label]) => el('div', { class: 'tn' + (st.filter === k ? ' cur' : ''), onclick: () => { st.filter = k; render(); } },
      el('span', { text: label }), el('span', { class: 'cnt', text: st.tasks.filter(t => match(t, k)).length || '' }))));
    const shown = st.tasks.filter(t => match(t, st.filter));
    empty.classList.toggle('hidden', shown.length > 0);
    table.classList.toggle('hidden', !shown.length);
    tbody.replaceChildren(...shown.map(t => {
      const pct = t.total > 0 ? Math.min(100, t.done / t.total * 100) : (t.status === 'done' ? 100 : 0);
      const eta = t.status === 'downloading' && t.speed > 0 && t.total > 0 ? fmtEta((t.total - t.done) / t.speed) : '';
      const speed = t.status === 'downloading' || t.status === 'seeding'
        ? '▼ ' + fmtRate(t.speed) + (t.type === 'torrent' ? '\n▲ ' + fmtRate(t.upSpeed) : '') : '';
      const chk = el('input', { type: 'checkbox', checked: st.sel.has(t.id), onclick: e => { e.stopPropagation(); e.target.checked ? st.sel.add(t.id) : st.sel.delete(t.id); } });
      const tr = el('tr', { class: 'st-' + t.status + (st.sel.has(t.id) ? ' sel' : ''), title: t.error || t.url,
        onclick: () => { st.sel.clear(); st.sel.add(t.id); render(); },
        ondblclick: () => openTask(t),
        oncontextmenu: e => { e.preventDefault(); if (!st.sel.has(t.id)) { st.sel.clear(); st.sel.add(t.id); render(); } menu(taskMenu(t), e.clientX, e.clientY); } },
        el('td', { class: 'chk' }, chk),
        el('td', { class: 'name' }, el('div', null, fileIcon(t.type === 'torrent' ? 'archive' : kindOf({ name: t.name })), el('span', { text: t.name }))),
        el('td', { class: 'dim', text: t.total > 0 ? fmtSize(t.total) : '-' }),
        el('td', { class: 'prog' }, el('div', { class: 'pct', text: `${pct.toFixed(1)}%${eta ? ' · ETA ' + eta : ''}${t.type === 'torrent' && t.peers ? ' · ' + t.peers + ' peers' : ''}` }), el('div', { class: 'bar' }, el('i', { style: { width: pct + '%' } }))),
        el('td', { class: 'dim col-type', style: { whiteSpace: 'pre', lineHeight: '1.3', fontSize: '12px' }, text: speed }),
        el('td', { class: 'col-date' }, el('span', { class: 'st-pill ' + t.status, text: t.status === 'error' ? 'Erro: ' + (t.error || '') : ((onlyTorrents ? DL_STATUS_PT[t.status] : DL_STATUS[t.status]) || t.status) })));
      return tr;
    }));
    const active = st.tasks.filter(t => t.status === 'downloading').length;
    win.setTitle((onlyTorrents ? 'Torrent Downloader' : 'Download Station') + (active ? ` — ${onlyTorrents ? 'Baixando' : 'Downloading'} ${active}` : ''));
  }

  async function copyTaskStream(t) {
    try {
      const r = await api('list', { path: t.dest });
      let target = r.entries.find(x => x.path === t.dest + '/' + t.name);
      if (target && target.dir) {
        const inside = await api('list', { path: target.path });
        const videos = inside.entries.filter(x => !x.dir && /\.(mp4|mkv|avi|mov|webm|m4v|ts|m2ts|mpg|mpeg|wmv)$/i.test(x.name));
        videos.sort((a, b) => (b.size || 0) - (a.size || 0));
        target = videos[0];
      }
      if (!target || target.dir) return toast('Nenhum vídeo encontrado nesse torrent. Abra a pasta para ver os arquivos.');
      copyText(location.origin + fileUrl(target.path), 'Link copiado — cole no VLC');
    } catch (e) { toast('Não consegui montar o link: ' + e.message); }
  }

  function taskMenu(t) {
    return [
      { label: 'Open File', ico: 'open', disabled: t.status !== 'done' && t.status !== 'seeding', run: () => openTask(t) },
      { label: 'Open Destination Folder', ico: 'folderUp', run: () => openApp('filestation', { path: t.dest }) },
      { label: 'Copy Source Link', ico: 'link', run: () => dialog({ title: 'Source Link', input: t.url, cancel: false }) },
      '-',
      { label: t.status === 'metadata' ? 'Aguardar metadados e escolher…' : 'Selecionar arquivos…', ico: 'list', disabled: !['choose', 'metadata', 'paused'].includes(t.status) || t.type !== 'torrent', run: () => pickFiles(t) },
      t.type === 'torrent' ? { label: 'Abrir no Torrent Player', ico: 'play', run: () => openApp('torrentplayer', { task: t.id }) } : null,
      t.type === 'torrent' ? { label: 'Abrir conteúdo do ZIP…', ico: 'archive', disabled: t.status !== 'done' && t.status !== 'seeding', run: () => openArchiveForTask(t) } : null,
      t.type === 'torrent' && (t.status === 'done' || t.status === 'seeding') ? { label: 'Copiar link para VLC', ico: 'link', run: () => copyTaskStream(t) } : null,
      { label: onlyTorrents ? 'Retomar' : 'Resume', ico: 'play', disabled: !['paused', 'error'].includes(t.status), run: () => act('resume') },
      { label: onlyTorrents ? 'Pausar' : 'Pause', ico: 'min', disabled: !['downloading', 'queued', 'seeding', 'metadata'].includes(t.status), run: () => act('pause') },
      { label: onlyTorrents ? 'Excluir tarefa' : 'Delete Task', ico: 'trash', danger: true, run: removeSel },
    ];
  }
  async function pickFiles(t) {
    try {
      const data = await waitForTorrentFiles(t.id);
      if (!data) return;
      if (data.timeout) return toast('Os metadados ainda não chegaram. O torrent continua só buscando metadados — nada foi baixado. Tente de novo em alguns segundos.', 7000);
      showFilePickerDialog(data.task, data.files, t.dest, res => { if (res) refresh(); });
    } catch (e) { toast('Falha ao abrir os arquivos: ' + e.message); }
  }

  async function openTask(t) {
    if (t.status !== 'done' && t.status !== 'seeding') return;
    const path = t.dest + '/' + t.name;
    try {
      const r = await api('list', { path: t.dest });
      const e = r.entries.find(x => x.path === path);
      if (!e) return toast('File not found');
      if (e.dir) return openApp('filestation', { path });
      openEntry(e, r.entries.filter(x => !x.dir));
    } catch (e) { toast('Cannot open: ' + e.message); }
  }
  async function act(kind) {
    for (const id of st.sel) await api('dl/' + kind, { id }, { method: 'POST' }).catch(() => {});
    refresh();
  }
  async function removeSel() {
    if (!st.sel.size) return;
    const files = el('input', { type: 'checkbox' });
    const ok = await new Promise(resolve => {
      const close = v => { wrap.remove(); resolve(v); };
      const wrap = el('div', { id: 'modal' }, el('div', { class: 'dialog' },
        el('div', { class: 'd-head', text: `Delete ${st.sel.size} tasks?` }),
        el('div', { class: 'd-body' }, el('label', { style: { display: 'flex', gap: '8px', alignItems: 'center', cursor: 'pointer' } }, files, 'Also delete downloaded files from phone')),
        el('div', { class: 'd-foot' }, el('button', { class: 'btn', text: 'Cancel', onclick: () => close(false) }), el('button', { class: 'btn danger', text: 'Delete', onclick: () => close(true) }))));
      document.body.append(wrap);
    });
    if (!ok) return;
    for (const id of st.sel) await api('dl/remove', { id, files: files.checked ? 1 : 0 }, { method: 'POST' }).catch(() => {});
    st.sel.clear();
    refresh();
  }
  function openFolder() {
    const t = st.tasks.find(x => st.sel.has(x.id));
    openApp('filestation', { path: t ? t.dest : st.info.defaultDir });
  }
  async function create(preset) {
    const v = await addDownloadDialog(st.info, preset);
    if (await submitDownloads(v)) refresh();
  }
  async function refresh() {
    try {
      const r = await api('dl/list');
      st.tasks = (r.tasks || []).filter(t => !onlyTorrents || t.type === 'torrent');
      st.info = { defaultDir: r.defaultDir, torrent: r.torrent, torrentsOnly: onlyTorrents };
      for (const id of [...st.sel]) if (!st.tasks.some(t => t.id === id)) st.sel.delete(id);
      render();
    } catch { /* offline */ }
  }
  refresh();
  const timer = setInterval(refresh, 1500);
  return { destroy() { clearInterval(timer); }, handle(arg) { if (arg.add) create(arg.add); } };
}

function TorrentDownloader(body, win) {
  return DownloadStation(body, win, { torrentsOnly: true });
}

// ---------------------------------------------------------------- ZIP archives

/** Opens the selective extraction dialog for a ZIP file. */
async function openZipPicker(path) {
  let data;
  try { data = await api('archive/list', { path }); }
  catch (e) { return toast('Não foi possível abrir o ZIP: ' + e.message, 6000); }
  const all = data.entries || [];
  const files = all.filter(e => !e.dir);
  if (!files.length) return toast('Este ZIP está vazio');

  const zipName = String(path).split('/').pop() || 'arquivo.zip';
  const folder = zipName.replace(/\.(zip|cbz)$/i, '');
  const baseDir = String(path).replace(/\/[^/]+$/, '');
  const defaultDest = baseDir + '/' + folder;
  const destInput = el('input', { value: defaultDest, spellcheck: 'false', style: { width: '100%', fontFamily: 'monospace', fontSize: '12px' } });
  const filter = el('input', { placeholder: 'Filtrar arquivos ou pastas…', spellcheck: 'false', style: { width: '100%', marginBottom: '8px' } });
  const list = el('div', { style: { maxHeight: '360px', overflow: 'auto', border: '1px solid #e2e8f0', borderRadius: '8px', background: '#f8fafc' } });
  const checked = new Set(files.map(f => f.name));
  const count = el('span', { class: 'dim', style: { fontSize: '12px' } });
  const allBox = el('input', { type: 'checkbox' });
  allBox.checked = true;
  let query = '';
  const close = v => { wrap.remove(); resolve(v); };
  let resolve;
  const result = new Promise(r => { resolve = r; });

  function visible() {
    const q = query.toLowerCase();
    const rows = all.filter(e => !q || e.name.toLowerCase().includes(q));
    // Keep the list responsive with huge repacks.
    return rows.slice(0, 2500);
  }
  function refreshCount() {
    let size = 0;
    for (const f of files) if (checked.has(f.name)) size += Number(f.size) || 0;
    count.textContent = `${checked.size} de ${files.length} arquivo(s) · ${fmtSize(size)}`;
    allBox.checked = checked.size === files.length;
    startBtn.disabled = checked.size === 0;
  }
  function draw() {
    const rows = visible();
    const frag = [];
    const q = query.toLowerCase();
    for (const e of rows) {
      if (e.dir) {
        const prefix = e.name.endsWith('/') ? e.name : e.name + '/';
        const kids = files.filter(f => f.name.startsWith(prefix));
        const on = kids.length > 0 && kids.every(f => checked.has(f.name));
        const cb = el('input', { type: 'checkbox', onchange: ev => {
          for (const f of kids) { if (ev.target.checked) checked.add(f.name); else checked.delete(f.name); }
          refreshCount(); draw();
        } });
        cb.checked = on;
        frag.push(el('div', { style: { display: 'flex', alignItems: 'center', gap: '7px', padding: '5px 10px', fontWeight: '600', fontSize: '12px', color: '#475569' } }, cb, '📁 ' + e.name.replace(/\/$/, '')));
      } else {
        const cb = el('input', { type: 'checkbox', onchange: ev => {
          if (ev.target.checked) checked.add(e.name); else checked.delete(e.name);
          refreshCount(); allBox.checked = checked.size === files.length;
        } });
        cb.checked = checked.has(e.name);
        frag.push(el('div', { style: { display: 'flex', alignItems: 'center', gap: '7px', padding: '4px 10px 4px 24px', fontSize: '12px', borderTop: '1px solid #eef2f7' } },
          cb, el('span', { style: { flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }, title: e.name, text: e.name.split('/').pop() }),
          el('span', { class: 'dim', style: { fontSize: '11px', flex: 'none' }, text: fmtSize(e.size) })));
      }
    }
    if (!frag.length) frag.push(el('div', { class: 'dim', style: { padding: '18px', textAlign: 'center' }, text: 'Nada encontrado com esse filtro.' }));
    list.replaceChildren(...frag);
  }
  allBox.onchange = () => {
    checked.clear();
    if (allBox.checked) for (const f of files) checked.add(f.name);
    refreshCount(); draw();
  };
  filter.oninput = () => { query = filter.value.trim(); draw(); };

  const startBtn = el('button', { class: 'btn primary', onclick: async () => {
    const chosen = checked.size === files.length ? [] : [...checked];
    const dest = destInput.value.trim();
    startBtn.disabled = true;
    startBtn.textContent = 'Iniciando…';
    try {
      const r = await api('archive/extract', null, { method: 'POST', body: JSON.stringify({ path, dest, entries: chosen }) });
      close(true);
      showExtractProgress(r);
    } catch (e) {
      startBtn.disabled = false;
      startBtn.textContent = 'Extrair selecionados';
      toast('Falha ao extrair: ' + e.message, 6000);
    }
  } }, 'Extrair selecionados');

  const wrap = el('div', { id: 'modal', onmousedown: e => { if (e.target === wrap) close(false); } },
    el('div', { class: 'dialog wide' },
      el('div', { class: 'd-head', text: '📦 ' + zipName }),
      el('div', { class: 'd-body' },
        el('div', { class: 'hint', style: { marginBottom: '8px' }, text: 'Escolha somente o que deseja extrair. O ZIP já está completo no celular; a extração cria os arquivos selecionados na pasta abaixo.' }),
        el('div', { style: { display: 'flex', gap: '8px', alignItems: 'center', marginBottom: '6px' } },
          el('label', { style: { display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px', cursor: 'pointer' } }, allBox, 'Selecionar tudo'),
          el('div', { style: { flex: 1 } }), count),
        filter, list,
        el('label', { class: 'f', text: 'Extrair para', style: { marginTop: '10px' } }), destInput),
      el('div', { class: 'd-foot' },
        el('button', { class: 'btn', text: 'Cancelar', onclick: () => close(false) }),
        startBtn)));
  document.body.append(wrap);
  refreshCount(); draw();
  return result;
}

/** Progress window for a background extraction job. */
function showExtractProgress(job) {
  let closed = false;
  const bar = el('div', { class: 'bar', style: { height: '10px', margin: '10px 0' } }, el('i', { style: { width: '0%' } }));
  const pct = el('div', { style: { fontSize: '13px', fontWeight: '600' }, text: 'Preparando…' });
  const cur = el('div', { class: 'dim', style: { fontSize: '11px', marginTop: '4px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }, text: job.current || '' });
  const foot = el('div', { class: 'd-foot' });
  const close = () => { closed = true; wrap.remove(); };
  const wrap = el('div', { id: 'modal' }, el('div', { class: 'dialog' },
    el('div', { class: 'd-head', text: '📦 Extraindo arquivos' }),
    el('div', { class: 'd-body' }, pct, bar, cur,
      el('div', { class: 'hint', style: { marginTop: '8px' }, text: 'Você pode fechar esta janela; a extração continua no celular.' })),
    foot));
  const cancelBtn = el('button', { class: 'btn danger', text: 'Cancelar', onclick: async () => {
    cancelBtn.disabled = true;
    cancelBtn.textContent = 'Cancelando…';
    try { await api('archive/cancel', { id: job.id }, { method: 'POST' }); } catch { /* worker stops on next chunk */ }
  } });
  foot.append(cancelBtn, el('button', { class: 'btn', text: 'Fechar', onclick: close }));
  document.body.append(wrap);

  async function tick() {
    if (closed) return;
    try {
      const s = await api('archive/status', { id: job.id });
      const p = Number(s.progress) || 0;
      bar.firstChild.style.width = p + '%';
      pct.textContent = s.state === 'done' ? '✓ Concluído' : s.state === 'error' ? '✗ Erro' : s.state === 'cancelled' ? 'Cancelado' : `Extraindo… ${p.toFixed(0)}%`;
      cur.textContent = s.current ? `${s.current} — ${s.extracted} extraído(s), ${s.skipped} ignorado(s)` : '';
      if (['done', 'error', 'cancelled'].includes(s.state)) {
        if (s.error) cur.textContent = s.error;
        foot.replaceChildren(
          el('button', { class: 'btn', text: 'Abrir pasta', onclick: () => { close(); openApp('filestation', { path: s.dest }); } }),
          el('button', { class: 'btn primary', text: 'Fechar', onclick: close }));
        if (s.state === 'done') toast('Extração concluída');
        return;
      }
    } catch { /* retry */ }
    setTimeout(tick, 700);
  }
  tick();
}

/** Finds and opens a ZIP belonging to a torrent task. */
async function openArchiveForTask(t) {
  try {
    const r = await api('archive/find', { task: t.id });
    const zips = r.entries || [];
    if (!zips.length) return toast('Nenhum ZIP encontrado nos arquivos desse torrent. Baixe o torrent primeiro (ou ele ainda não terminou).', 7000);
    if (zips.length === 1) return openZipPicker(zips[0].path);
    const wrap = el('div', { id: 'modal', onmousedown: e => { if (e.target === wrap) wrap.remove(); } },
      el('div', { class: 'dialog' },
        el('div', { class: 'd-head', text: 'Escolha o ZIP' }),
        el('div', { class: 'd-body', style: { display: 'flex', flexDirection: 'column', gap: '8px', maxHeight: '420px', overflow: 'auto' } },
          ...zips.map(z => el('button', { class: 'btn', style: { justifyContent: 'space-between' }, onclick: () => { wrap.remove(); openZipPicker(z.path); } },
            el('span', { text: z.name }), el('span', { class: 'dim', text: fmtSize(z.size) })))),
        el('div', { class: 'd-foot' }, el('button', { class: 'btn', text: 'Cancelar', onclick: () => wrap.remove() }))));
    document.body.append(wrap);
  } catch (e) { toast('Falha ao localizar ZIP: ' + e.message); }
}

// ---------------------------------------------------------------- Media Player

function srtToVtt(text) {
  return 'WEBVTT\n\n' + text.replace(/\r/g, '').replace(/^\d+\s*$/gm, '').replace(/(\d\d:\d\d:\d\d),(\d\d\d)/g, '$1.$2').trim() + '\n';
}
const fmtClock = s => {
  if (!isFinite(s)) return '0:00';
  s = Math.floor(s);
  const h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, sec = String(s % 60).padStart(2, '0');
  return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`;
};


/** True for containers that browsers usually refuse even when H.264/AAC is inside. */
function needsBrowserRemux(name) {
  return /\.(mkv|avi|mov|ts|m2ts|mpg|mpeg|wmv|flv|vob)$/i.test(name || '');
}

/**
 * Sets a browser-playable source. MKV/AVI/TS are remuxed to MP4 on the phone first
 * (no re-encode, so quality is preserved and it is fast).
 */
async function setPlayableSource(video, path, onStatus, isCurrent) {
  const name = String(path).split('/').pop() || '';
  if (!needsBrowserRemux(name)) {
    video.src = fileUrl(path);
    video.load();
    return;
  }
  const status = onStatus || (() => {});
  status('Preparando vídeo para o navegador…');
  let job;
  try {
    job = await api('remux', { path }, { method: 'POST' });
  } catch (e) {
    throw new Error('conversão indisponível: ' + e.message);
  }
  while (job && job.state !== 'done' && job.state !== 'error' && job.state !== 'cancelled') {
    if (isCurrent && !isCurrent()) return;
    status(job.state === 'remuxing' ? `Convertendo para MP4… ${Math.round(job.progress || 0)}%` : 'Preparando vídeo para o navegador…');
    await new Promise(r => setTimeout(r, 700));
    try { job = await api('remux/status', { id: job.id }); }
    catch { /* keep polling */ }
  }
  if (!job || job.state !== 'done') {
    throw new Error(job && job.error ? job.error : 'não foi possível converter o vídeo');
  }
  if (isCurrent && !isCurrent()) return;
  status('Vídeo pronto — iniciando…');
  video.src = '/api/remux/file?id=' + encodeURIComponent(job.id);
  video.load();
}

function MediaPlayer(body, win) {
  const st = { list: [], i: -1, folder: [], shuffle: false, loop: store.get('mp.loop', 'list'), subUrl: null, subs: [], seq: 0 };
  const video = el('video', { playsinline: true, preload: 'metadata' });
  video.volume = store.get('mp.vol', 1);
  const art = el('div', { class: 'mp-art hidden' }, el('div', null, el('div', { class: 'disc' }), el('div', { class: 't' }), el('div', { class: 's' })));
  const osd = el('div', { class: 'mp-osd', style: { opacity: 0 } });
  const big = el('div', { class: 'mp-big', style: { opacity: 0 } });
  const msg = el('div', { class: 'mp-msg hidden' });
  const screen = el('div', { class: 'mp-screen' }, video, art, big, osd, msg);

  const played = el('div', { class: 'played' }), buf = el('div', { class: 'buf' }), knob = el('div', { class: 'knob' }), tip = el('div', { class: 'tip' });
  const seek = el('div', { class: 'mp-seek' }, el('div', { class: 'track' }), buf, played, knob, tip);
  const time = el('span', { class: 'time', text: '0:00 / 0:00' });
  const b = (ico, title, run) => el('button', { title, onclick: run }, typeof ico === 'string' ? icon(ico) : ico);
  const playBtn = b('play', 'Play/Pause (Space)', () => toggle());
  const vol = el('input', { type: 'range', min: 0, max: 1, step: 0.05, value: video.volume, class: 'vol', oninput: e => { video.volume = +e.target.value; video.muted = false; store.set('mp.vol', video.volume); } });
  const muteBtn = b(volIcon(), 'Mute (M)', () => { video.muted = !video.muted; });
  const speedBtn = el('button', { title: 'Playback Speed', text: '1x', onclick: e => menuAt(e.currentTarget, [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2].map(r => ({ label: (video.playbackRate === r ? '● ' : '') + r + 'x', run: () => setRate(r) }))) });
  const ccBtn = el('button', { title: 'Subtitles', text: 'CC', onclick: e => subMenu(e.currentTarget) });
  let videoRot = 0;
  function rotateVideo() {
    videoRot = (videoRot + 90) % 360;
    video.style.transform = videoRot ? `rotate(${videoRot}deg)` : '';
    flash('Rotate ' + videoRot + '°');
  }

  const loopBtn = b('refresh', 'Loop', () => cycleLoop());
  const shufBtn = el('button', { title: 'Shuffle', text: '⤮', style: { fontSize: '17px' }, onclick: () => { st.shuffle = !st.shuffle; shufBtn.classList.toggle('on', st.shuffle); flash(st.shuffle ? 'Shuffle: On' : 'Shuffle: Off'); } });
  const listBtn = b('list', 'Playlist', () => { listPane.classList.toggle('hidden'); listBtn.classList.toggle('on', !listPane.classList.contains('hidden')); });
  if (window.innerWidth <= 760) {
    listPane.classList.add('hidden');
    listBtn.classList.remove('on');
  } else {
    listBtn.classList.add('on');
  }
  const more = b('link', 'Stream Link / Options', e => menuAt(e.currentTarget, [
    { label: 'Rotate 90° (R)', ico: 'rotate', run: () => rotateVideo() },
    { label: 'Copy stream link (for VLC or external players)', ico: 'link', run: () => copyStream() },
    { label: 'Open in VLC app (Phone)', ico: 'open', run: () => { location.href = 'vlc://' + location.origin + fileUrl(cur().path); } },
    { label: 'Download this file', ico: 'download', run: () => { location.href = fileUrl(cur().path, true); } },
    document.pictureInPictureEnabled ? { label: 'Picture-in-Picture (PiP)', ico: 'restore', run: () => video.requestPictureInPicture().catch(() => {}) } : null,
  ]));
  const fsBtn = b('max', 'Fullscreen (F)', () => fullscreen());
  const ctrl = el('div', { class: 'mp-ctrl' }, seek, el('div', { class: 'mp-row' },
    b('back', 'Previous (P)', () => step(-1)), playBtn, b('fwd', 'Next (N)', () => step(1)),
    time, el('div', { class: 'grow' }), muteBtn, vol, speedBtn, ccBtn, loopBtn, shufBtn, more, listBtn, fsBtn));
  const stage = el('div', { class: 'mp-stage' }, screen, ctrl);
  const listItems = el('div', { class: 'items' });
  const listHead = el('h4');
  const listPane = el('div', { class: 'mp-list' }, listHead, listItems);
  const root = el('div', { class: 'mp', tabindex: '0' }, stage, listPane);
  body.append(root);
  updateLoopBtn();

  function volIcon() {
    const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    s.setAttribute('viewBox', '0 0 24 24'); s.setAttribute('fill', 'none'); s.setAttribute('stroke', 'currentColor'); s.setAttribute('stroke-width', '2'); s.setAttribute('stroke-linecap', 'round');
    s.innerHTML = '<path d="M4 9h4l5-4v14l-5-4H4z" fill="currentColor"/><path class="w" d="M16 9a4 4 0 0 1 0 6M18.5 6.5a8 8 0 0 1 0 11"/>';
    return s;
  }
  const cur = () => st.list[st.i];
  const isAudio = e => kindOf(e) === 'audio';

  function flash(text) {
    osd.textContent = text;
    osd.style.opacity = 1;
    clearTimeout(flash.t);
    flash.t = setTimeout(() => { osd.style.opacity = 0; }, 900);
  }
  function bigIcon(name) {
    big.replaceChildren(icon(name));
    big.style.opacity = 1;
    clearTimeout(bigIcon.t);
    bigIcon.t = setTimeout(() => { big.style.opacity = 0; }, 450);
  }
  function setRate(r) { video.playbackRate = r; speedBtn.textContent = r + 'x'; flash('Speed ' + r + 'x'); }
  function cycleLoop() {
    st.loop = { list: 'one', one: 'off', off: 'list' }[st.loop];
    store.set('mp.loop', st.loop);
    updateLoopBtn();
    flash({ list: 'Repeat Playlist', one: 'Repeat Track', off: 'Repeat Off' }[st.loop]);
  }
  function updateLoopBtn() {
    loopBtn.classList.toggle('on', st.loop !== 'off');
    loopBtn.title = { list: 'Repeat: All', one: 'Repeat: Track', off: 'Repeat: Off' }[st.loop];
    loopBtn.style.position = 'relative';
    loopBtn.dataset.one = st.loop === 'one' ? '1' : '';
    loopBtn.replaceChildren(icon('refresh'), st.loop === 'one' ? el('span', { text: '1', style: { position: 'absolute', fontSize: '9px', right: '6px', bottom: '5px' } }) : '');
  }
  function toggle() { if (video.paused) video.play().catch(() => {}); else video.pause(); }
  function fullscreen() {
    if (document.fullscreenElement) document.exitFullscreen();
    else (stage.requestFullscreen ? stage.requestFullscreen() : video.webkitEnterFullscreen && video.webkitEnterFullscreen());
  }
  function copyStream() {
    const url = location.origin + fileUrl(cur().path);
    if (navigator.clipboard && isSecureContext) navigator.clipboard.writeText(url).then(() => toast('Link copied to clipboard'));
    else dialog({ title: 'Stream Link — Paste into VLC (Media › Open Network Stream)', input: url, cancel: false });
  }

  function savePos() {
    const e = cur();
    if (!e || !isFinite(video.duration) || video.duration < 120) return;
    const pos = video.currentTime > 10 && video.currentTime < video.duration - 15 ? Math.floor(video.currentTime) : 0;
    const all = store.get('mp.pos', {});
    if (pos) all[e.path] = pos; else delete all[e.path];
    const keys = Object.keys(all);
    if (keys.length > 200) delete all[keys[0]];
    store.set('mp.pos', all);
  }

  async function play(i, resume = true) {
    savePos();
    st.i = i;
    const e = cur();
    if (!e) return;
    videoRot = 0;
    video.style.transform = '';
    msg.classList.add('hidden');
    [...video.querySelectorAll('track')].forEach(t => t.remove());
    if (st.subUrl) { URL.revokeObjectURL(st.subUrl); st.subUrl = null; }
    const seq = ++st.seq;
    const audio = isAudio(e);
    art.classList.toggle('hidden', !audio);
    if (audio) { art.querySelector('.t').textContent = e.name.replace(/\.[^.]+$/, ''); art.querySelector('.s').textContent = `${st.i + 1} / ${st.list.length} · ${fmtSize(e.size)}`; }
    win.setTitle('Media Player — ' + e.name);
    const pos = resume && store.get('mp.pos', {})[e.path];
    if (pos) video.addEventListener('loadedmetadata', () => { video.currentTime = pos; flash('Resuming from ' + fmtClock(pos)); }, { once: true });
    setPlayableSource(video, e.path, text => {
      if (seq !== st.seq) return;
      msg.classList.remove('hidden');
      msg.textContent = text;
    }, () => seq === st.seq).then(() => {
      if (seq !== st.seq) return;
      msg.classList.add('hidden');
      video.play().catch(() => {});
    }).catch(err => {
      if (seq !== st.seq) return;
      msg.classList.remove('hidden');
      msg.textContent = 'Não foi possível tocar este vídeo: ' + err.message + ' — use o VLC pelo menu.';
    });
    renderList();
    findSubs(e);
  }
  function step(d) {
    if (!st.list.length) return;
    let n;
    if (st.shuffle && st.list.length > 1) { do n = Math.floor(Math.random() * st.list.length); while (n === st.i); }
    else n = st.i + d;
    if (n >= st.list.length) { if (st.loop === 'list') n = 0; else return; }
    if (n < 0) n = st.list.length - 1;
    play(n);
  }

  async function findSubs(e) {
    st.subs = [];
    ccBtn.classList.remove('on');
    if (isAudio(e)) return;
    let folder = st.folder;
    const dir = e.path.slice(0, e.path.lastIndexOf('/'));
    if (!folder.length || !folder.some(x => x.path.startsWith(dir + '/'))) {
      try { folder = (await api('list', { path: dir })).entries; st.folder = folder; } catch { folder = []; }
    }
    const base = e.name.replace(/\.[^.]+$/, '').toLowerCase();
    st.subs = folder.filter(x => /\.(srt|vtt)$/i.test(x.name) && x.path.startsWith(dir + '/'))
      .sort((a, b) => (b.name.toLowerCase().startsWith(base)) - (a.name.toLowerCase().startsWith(base)));
    const auto = st.subs.find(x => x.name.toLowerCase().startsWith(base));
    if (auto) loadSub(auto, true);
  }
  async function loadSub(s, quiet) {
    try {
      let text = await (await fetch(fileUrl(s.path))).text();
      if (/\.srt$/i.test(s.name)) text = srtToVtt(text);
      [...video.querySelectorAll('track')].forEach(t => t.remove());
      if (st.subUrl) URL.revokeObjectURL(st.subUrl);
      st.subUrl = URL.createObjectURL(new Blob([text], { type: 'text/vtt' }));
      const tr = el('track', { kind: 'subtitles', src: st.subUrl, srclang: 'th', label: s.name, default: true });
      video.append(tr);
      tr.track.mode = 'showing';
      ccBtn.classList.add('on');
      if (!quiet) flash('Subtitles: ' + s.name);
    } catch { toast('Failed to load subtitles'); }
  }
  function subMenu(anchor) {
    const pickLocal = el('input', { type: 'file', accept: '.srt,.vtt', class: 'hidden', onchange: async () => {
      const f = pickLocal.files[0];
      if (!f) return;
      let text = await f.text();
      if (/\.srt$/i.test(f.name)) text = srtToVtt(text);
      [...video.querySelectorAll('track')].forEach(t => t.remove());
      st.subUrl = URL.createObjectURL(new Blob([text], { type: 'text/vtt' }));
      const tr = el('track', { kind: 'subtitles', src: st.subUrl, label: f.name, default: true });
      video.append(tr);
      tr.track.mode = 'showing';
      ccBtn.classList.add('on');
    } });
    document.body.append(pickLocal);
    const track = video.textTracks[0];
    menuAt(anchor, [
      { label: (track && track.mode === 'showing' ? '' : '● ') + 'Disable subtitles', run: () => { if (track) track.mode = 'hidden'; ccBtn.classList.remove('on'); } },
      ...st.subs.map(s => ({ label: s.name, run: () => loadSub(s) })),
      '-',
      { label: 'Load subtitle file from computer…', ico: 'upload', run: () => pickLocal.click() },
    ]);
  }

  function renderList() {
    listHead.replaceChildren(el('span', { text: 'Playlist', style: { color: '#e6edf3', fontWeight: 600 } }), el('span', { text: st.list.length + ' tracks' }));
    listItems.replaceChildren(...st.list.map((e, i) => {
      const k = kindOf(e);
      let thumb;
      if (k === 'video') { thumb = el('img', { loading: 'lazy', src: thumbUrl(e.path, 160), alt: '' }); thumb.onerror = () => thumb.replaceWith(el('div', { class: 'ph' }, fileIcon(k))); }
      else thumb = el('div', { class: 'ph' }, fileIcon(k));
      return el('div', { class: 'mp-item' + (i === st.i ? ' cur' : ''), onclick: () => play(i) }, thumb,
        el('div', { style: { minWidth: 0 } }, el('div', { class: 'n', text: e.name }), el('div', { class: 'z', text: fmtSize(e.size) })));
    }));
    const c = listItems.children[st.i];
    if (c) c.scrollIntoView({ block: 'nearest' });
  }

  // ---- events
  const updateTime = () => {
    const d = video.duration || 0, t = video.currentTime || 0;
    const p = d ? t / d * 100 : 0;
    played.style.width = p + '%';
    knob.style.left = p + '%';
    time.textContent = fmtClock(t) + ' / ' + fmtClock(d);
    if (video.buffered.length && d) buf.style.width = video.buffered.end(video.buffered.length - 1) / d * 100 + '%';
  };
  video.addEventListener('timeupdate', updateTime);
  video.addEventListener('progress', updateTime);
  video.addEventListener('loadedmetadata', updateTime);
  video.addEventListener('play', () => { playBtn.replaceChildren(icon('pause')); art.classList.add('spin'); });
  video.addEventListener('pause', () => { playBtn.replaceChildren(icon('play')); art.classList.remove('spin'); savePos(); });
  video.addEventListener('volumechange', () => { vol.value = video.muted ? 0 : video.volume; muteBtn.querySelector('.w').style.display = video.muted || !video.volume ? 'none' : ''; });
  video.addEventListener('ended', () => { if (st.loop === 'one') { video.currentTime = 0; video.play(); } else step(1); });
  video.addEventListener('error', () => {
    if (!cur()) return;
    msg.replaceChildren(el('div', null,
      el('div', { style: { fontSize: '15px', marginBottom: '6px' }, text: 'Browser cannot play this media file' }),
      el('div', { style: { color: '#8b98a5', marginBottom: '14px', fontSize: '12.5px' }, text: 'Often caused by unsupported codecs (e.g. HEVC/H.265, AC3, DTS in .mkv/.avi). Try opening with VLC instead.' }),
      el('button', { class: 'btn primary', onclick: copyStream }, icon('link'), 'Copy stream link for VLC'),
      el('a', { class: 'btn', href: 'vlc://' + location.origin + fileUrl(cur().path), style: { textDecoration: 'none' } }, icon('open'), 'Open VLC App'),
      el('button', { class: 'btn', onclick: () => step(1) }, icon('fwd'), 'Next Track')));
    msg.classList.remove('hidden');
  });
  screen.addEventListener('click', e => { if (e.target === video || e.target === screen) { toggle(); bigIcon(video.paused ? 'pause' : 'play'); } });
  screen.addEventListener('dblclick', fullscreen);

  const seekTo = x => { const r = seek.getBoundingClientRect(); const f = Math.min(1, Math.max(0, (x - r.left) / r.width)); if (video.duration) video.currentTime = f * video.duration; };
  seek.addEventListener('pointerdown', e => {
    seekTo(e.clientX);
    const move = ev => seekTo(ev.clientX);
    const up = () => { removeEventListener('pointermove', move); removeEventListener('pointerup', up); };
    addEventListener('pointermove', move);
    addEventListener('pointerup', up);
  });
  seek.addEventListener('mousemove', e => {
    const r = seek.getBoundingClientRect();
    const f = Math.min(1, Math.max(0, (e.clientX - r.left) / r.width));
    tip.style.left = f * 100 + '%';
    tip.textContent = fmtClock(f * (video.duration || 0));
  });

  root.addEventListener('keydown', e => {
    if (e.target.tagName === 'INPUT' || $('#modal')) return;
    const k = e.key.toLowerCase();
    const seekBy = s => { video.currentTime = Math.max(0, Math.min(video.duration || 0, video.currentTime + s)); flash((s > 0 ? '+' : '') + s + 's'); };
    if (k === ' ' || k === 'k') { e.preventDefault(); toggle(); bigIcon(video.paused ? 'pause' : 'play'); }
    else if (k === 'arrowright') seekBy(5);
    else if (k === 'arrowleft') seekBy(-5);
    else if (k === 'l') seekBy(10);
    else if (k === 'j') seekBy(-10);
    else if (k === 'arrowup') { e.preventDefault(); video.volume = Math.min(1, video.volume + .05); flash('Volume ' + Math.round(video.volume * 100) + '%'); }
    else if (k === 'arrowdown') { e.preventDefault(); video.volume = Math.max(0, video.volume - .05); flash('Volume ' + Math.round(video.volume * 100) + '%'); }
    else if (k === 'm') video.muted = !video.muted;
    else if (k === 'f') fullscreen();
    else if (k === 'r') rotateVideo();
    else if (k === 'n') step(1);
    else if (k === 'p') step(-1);
    else if (k === '>' || k === '.') setRate(Math.min(2, video.playbackRate + .25));
    else if (k === '<' || k === ',') setRate(Math.max(.25, video.playbackRate - .25));
    else if (k === 'c') { const t = video.textTracks[0]; if (t) { t.mode = t.mode === 'showing' ? 'hidden' : 'showing'; ccBtn.classList.toggle('on', t.mode === 'showing'); } }
  });
  if ('mediaSession' in navigator) {
    navigator.mediaSession.setActionHandler('nexttrack', () => step(1));
    navigator.mediaSession.setActionHandler('previoustrack', () => step(-1));
  }

  return {
    handle(arg) {
      const kind = kindOf(arg.entry);
      st.list = (arg.list && arg.list.length ? arg.list : [arg.entry]).filter(x => ['video', 'audio'].includes(kindOf(x)) && (kind === 'audio') === isAudio(x));
      if (!st.list.some(x => x.path === arg.entry.path)) st.list.unshift(arg.entry);
      st.folder = arg.folder || [];
      play(st.list.findIndex(x => x.path === arg.entry.path));
      setTimeout(() => root.focus(), 50);
    },
    destroy() { savePos(); video.pause(); video.removeAttribute('src'); video.load(); if (st.subUrl) URL.revokeObjectURL(st.subUrl); },
  };
}

/** Opens a file in the best app: media → Media Player, everything else → viewer. */
function openEntry(e, siblings, folder) {
  const k = kindOf(e);
  if (k === 'video' || k === 'audio') openApp('player', { entry: e, list: siblings, folder: folder || siblings });
  else Viewer.open(e, siblings);
}

// ---------------------------------------------------------------- Terminal (root)

function Terminal(body, win) {
  const out = el('div', { class: 'term-out' });
  const ps = el('span', { class: 'ps', text: '/data/local/tmp' });
  const input = el('input', { spellcheck: 'false', autocapitalize: 'off', autocomplete: 'off', placeholder: 'Type command and press Enter' });
  const st = { cwd: '/data/local/tmp', history: store.get('term.hist', []), hi: -1, busy: false };
  const quick = ['ls -la', 'busybox | head -40', 'id', 'getprop ro.product.model', 'df -h', 'free -m', 'ps -A | head', 'pm list packages -3'];
  const bar = el('div', { class: 'term-bar' }, ...quick.map(c => el('button', { class: 'term-chip', text: c, onclick: () => run(c) })));
  const inBar = el('div', { class: 'term-in' }, ps, input);
  body.append(el('div', { class: 'term' }, bar, out, inBar));

  function line(text, cls) {
    out.append(el('div', { class: cls || null, text }));
    out.scrollTop = out.scrollHeight;
  }
  function write(text) {
    if (!text) return;
    const pre = el('div');
    pre.textContent = text.replace(/\n$/, '');
    out.append(pre);
    out.scrollTop = out.scrollHeight;
  }

  async function run(cmd) {
    cmd = cmd.trim();
    if (!cmd || st.busy) return;
    if (cmd === 'clear') { out.replaceChildren(); input.value = ''; return; }
    st.history = st.history.filter(h => h !== cmd); st.history.push(cmd);
    if (st.history.length > 100) st.history.shift();
    store.set('term.hist', st.history);
    st.hi = -1;
    line(cmd, 'cmd');
    input.value = '';
    st.busy = true;
    input.disabled = true;
    try {
      const r = await api('root/exec', null, { method: 'POST', body: JSON.stringify({ cmd, cwd: st.cwd }) });
      write(r.output);
      if (r.cwd) { st.cwd = r.cwd; ps.textContent = r.cwd; }
    } catch (e) {
      line(e.message, 'err');
      if (e.status === 401) Auth.show();
    }
    st.busy = false;
    input.disabled = false;
    input.focus();
  }

  input.addEventListener('keydown', e => {
    if (e.key === 'Enter') run(input.value);
    else if (e.key === 'ArrowUp') { if (st.hi < st.history.length - 1) { st.hi++; input.value = st.history[st.history.length - 1 - st.hi]; e.preventDefault(); } }
    else if (e.key === 'ArrowDown') { if (st.hi > 0) { st.hi--; input.value = st.history[st.history.length - 1 - st.hi]; } else { st.hi = -1; input.value = ''; } }
    else if (e.key === 'c' && e.ctrlKey) { input.value = ''; line('^C', 'err'); }
  });
  body.addEventListener('click', e => { if (!e.target.closest('button') && !window.getSelection().toString()) input.focus(); });

  (async () => {
    try {
      const s = await api('root/status');
      if (!s.root) {
        out.append(el('div', { class: 'notice', text: s.flavor !== 'root'
          ? 'Terminal is only available on NexShare Root edition'
          : 'Root access not granted — open NexShare on your phone and tap "Request root permission" (requires Magisk/SuperSU)' }));
        input.disabled = true;
        return;
      }
      line('NexShare root shell'
        + (s.busybox ? ' · BusyBox available (~300 tools: ls, grep, sed, awk, tar, vi, wget, top…)' : '')
        + (s.termux ? ' · Termux detected (Termux packages accessible)' : ''));
      run('id && getprop ro.product.model');
      input.focus();
    } catch (e) { out.append(el('div', { class: 'notice', text: e.message })); }
  })();

  return { handle() { input.focus(); } };
}

// ---------------------------------------------------------------- Screen mirror (root)

function ScreenMirror(body, win) {
  const img = el('img', { alt: 'Phone screen', draggable: false });
  const stage = el('div', { class: 'scr-stage' }, el('div', { class: 'scr-msg', text: 'Connecting…' }));
  const fps = el('span', { class: 'fps', text: '' });
  const liveBtn = el('button', { class: 'btn', onclick: () => toggle() }, icon('play'), el('span', { text: 'Start Live' }));
  const shotBtn = el('button', { class: 'btn', onclick: () => grab() }, icon('refresh'), el('span', { class: 'hide-sm', text: 'Capture' }));
  const saveBtn = el('button', { class: 'btn', onclick: () => save() }, icon('download'), el('span', { class: 'hide-sm', text: 'Save' }));
  const rateSel = el('select', { onchange: () => { st.interval = +rateSel.value; if (st.live) { stop(); start(); } }, style: { height: '30px', borderRadius: '7px', background: '#1b2330', color: '#d6dee8', border: '1px solid #2a3440' } },
    ...[[700, 'Normal ~1.4 fps'], [400, 'Fast ~2.5 fps'], [1500, 'Battery Saver'], [250, 'Max Smooth (high battery)']].map(([v, l]) => el('option', { value: v, text: l })));
  const tapChk = el('label', { style: { color: '#c4cfda', fontSize: '12px', display: 'flex', gap: '4px', alignItems: 'center' } },
    el('input', { type: 'checkbox', checked: true, onchange: e => st.control = e.target.checked }), 'Remote Control (Tap/Swipe)');
  const bar = el('div', { class: 'scr-bar' }, liveBtn, shotBtn, rateSel, tapChk, el('span', { class: 'grow' }), fps, saveBtn);
  const key = (label, code) => el('button', { title: label, onclick: () => sendKey(code) }, label === 'Back' ? icon('back') : label === 'Home' ? el('span', { text: '⬤', style: { fontSize: '12px' } }) : label === 'Recents' ? icon('max') : el('span', { text: label }));
  const nav = el('div', { class: 'scr-nav' }, key('Back', 4), key('Home', 3), key('Recents', 187),
    el('button', { title: 'Power', onclick: () => sendKey(26) }, el('span', { text: '⏻' })),
    el('button', { title: 'Volume +', onclick: () => sendKey(24) }, el('span', { text: '🔊' })),
    el('button', { title: 'Volume −', onclick: () => sendKey(25) }, el('span', { text: '🔈' })));
  body.append(el('div', { class: 'scr' }, bar, stage, nav));

  const st = { live: false, interval: 700, control: true, busy: false, lastShot: null, natW: 0, natH: 0, lastT: 0, timer: null };

  async function grab() {
    if (st.busy) return;
    st.busy = true;
    try {
      const r = await fetch('/api/root/screen?t=' + Date.now(), { credentials: 'same-origin' });
      if (!r.ok) {
        const j = await r.json().catch(() => ({}));
        if (r.status === 401) { Auth.show(); stop(); return; }
        throw new Error(j.error || r.status);
      }
      const blob = await r.blob();
      const url = URL.createObjectURL(blob);
      if (st.lastShot) URL.revokeObjectURL(st.lastShot);
      st.lastShot = url;
      if (!img.parentElement) stage.replaceChildren(img);
      img.src = url;
      const now = performance.now();
      if (st.lastT) fps.textContent = (1000 / (now - st.lastT)).toFixed(1) + ' fps';
      st.lastT = now;
    } catch (e) {
      if (!st.live) stage.replaceChildren(el('div', { class: 'scr-msg', text: 'Capture failed: ' + e.message }));
    }
    st.busy = false;
  }
  function start() {
    st.live = true;
    liveBtn.replaceChildren(icon('pause'), el('span', { text: 'Stop' }));
    const loop = async () => { if (!st.live) return; await grab(); if (st.live) st.timer = setTimeout(loop, st.interval); };
    loop();
  }
  function stop() {
    st.live = false;
    clearTimeout(st.timer);
    liveBtn.replaceChildren(icon('play'), el('span', { text: 'Start Live' }));
    fps.textContent = '';
  }
  function toggle() { st.live ? stop() : start(); }

  img.addEventListener('load', () => { st.natW = img.naturalWidth; st.natH = img.naturalHeight; });
  function mapCoord(ev) {
    const r = img.getBoundingClientRect();
    return { x: Math.round((ev.clientX - r.left) / r.width * st.natW), y: Math.round((ev.clientY - r.top) / r.height * st.natH) };
  }
  let down = null;
  img.addEventListener('pointerdown', e => { if (st.control) { down = { ...mapCoord(e), t: Date.now() }; e.preventDefault(); } });
  img.addEventListener('pointerup', async e => {
    if (!st.control || !down) return;
    const up = mapCoord(e);
    const dist = Math.hypot(up.x - down.x, up.y - down.y);
    try {
      if (dist < 12 && Date.now() - down.t < 400) await sendInput({ type: 'tap', x: up.x, y: up.y });
      else await sendInput({ type: 'swipe', x1: down.x, y1: down.y, x2: up.x, y2: up.y, ms: Math.min(800, Date.now() - down.t) });
      setTimeout(grab, 120);
    } catch (err) { toast(err.message); }
    down = null;
  });
  async function sendInput(b) { await api('root/input', null, { method: 'POST', body: JSON.stringify(b) }); }
  async function sendKey(code) { try { await sendInput({ type: 'key', key: String(code) }); setTimeout(grab, 150); } catch (e) { toast(e.message); } }
  async function save() {
    if (!st.lastShot) return;
    const a = el('a', { href: st.lastShot, download: 'screen-' + Date.now() + '.png' });
    document.body.append(a); a.click(); a.remove();
  }

  (async () => {
    try {
      const s = await api('root/status');
      if (!s.root) {
        stage.replaceChildren(el('div', { class: 'scr-msg', text: s.flavor !== 'root'
          ? 'Screen mirroring is only available on NexShare Root edition' : 'Root access required — grant root permission in the mobile app first' }));
        [liveBtn, shotBtn, saveBtn].forEach(b => b.disabled = true);
        return;
      }
      await grab();
      start();
    } catch (e) { stage.replaceChildren(el('div', { class: 'scr-msg', text: e.message })); }
  })();

  return { destroy() { stop(); if (st.lastShot) URL.revokeObjectURL(st.lastShot); } };
}

// ---------------------------------------------------------------- Package Center

function PackageCenter(body, win) {
  const st = { apps: [], root: false, flavor: 'normal', q: '', showSystem: false };
  const search = el('input', { placeholder: 'Search apps', oninput: e => { st.q = e.target.value.toLowerCase(); render(); } });
  const sysChk = el('label', { style: { display: 'flex', gap: '5px', alignItems: 'center', fontSize: '12.5px', color: 'var(--text-2)', whiteSpace: 'nowrap' } },
    el('input', { type: 'checkbox', onchange: e => { st.showSystem = e.target.checked; load(); } }), 'Include system apps');
  const installBtn = el('button', { class: 'btn primary', onclick: () => pick() }, icon('upload'), el('span', { text: 'Install APK' }));
  const bar = el('div', { class: 'pc-bar' }, el('div', { class: 'pc-search' }, icon('search'), search), sysChk, installBtn);
  const drop = el('div', { class: 'pc-drop', text: 'Drag and drop .apk file here to install' });
  const list = el('div', { class: 'pc-list' }, drop);
  const apkPick = el('input', { type: 'file', accept: '.apk', class: 'hidden', onchange: () => { if (apkPick.files[0]) installApk(apkPick.files[0]); apkPick.value = ''; } });
  body.append(el('div', { class: 'pc' }, bar, list), apkPick);

  function render() {
    const rows = st.apps.filter(a => !st.q || a.label.toLowerCase().includes(st.q) || a.pkg.includes(st.q));
    win.setTitle('Package Center — ' + st.apps.length + ' apps');
    list.replaceChildren(drop, ...rows.map(a => {
      const ico = el('img', { loading: 'lazy', src: '/api/root/appicon' + qs({ pkg: a.pkg }), alt: '' });
      ico.onerror = () => ico.replaceWith(el('div', { class: 'ph' }, fileIcon('apk')));
      return el('div', { class: 'pc-item' }, ico,
        el('div', { class: 'meta' },
          el('div', { class: 'nm' }, a.label, a.system ? el('span', { class: 'tag sys', text: ' System', style: { marginLeft: '6px' } }) : '', !a.enabled ? el('span', { class: 'tag off', text: ' Disabled', style: { marginLeft: '6px' } }) : ''),
          el('div', { class: 'sub', text: a.pkg + (a.version ? ' · v' + a.version : '') + (a.size ? ' · ' + fmtSize(a.size) : '') })),
        el('button', { class: 'btn', onclick: e => menuAt(e.currentTarget, appMenu(a)) }, 'Manage', icon('chev', 'chev')));
    }));
  }
  function appMenu(a) {
    const act = async (action, confirm) => {
      if (confirm && !await dialog({ title: confirm, okText: 'OK', danger: true })) return;
      try { const r = await api('root/app', { pkg: a.pkg, action }, { method: 'POST' }); toast('Success'); if (['uninstall', 'disable', 'enable'].includes(action)) load(); }
      catch (e) { toast('Failed: ' + e.message); }
    };
    return [
      { label: 'Launch App', ico: 'open', run: () => act('open') },
      st.root ? { label: 'Force Stop', ico: 'min', run: () => act('force-stop') } : null,
      st.root ? { label: 'Clear Data', ico: 'trash', run: () => act('clear', 'Clear data for ' + a.label + '?') } : null,
      st.root && a.enabled && a.system ? { label: 'Disable App', run: () => act('disable', 'Disable ' + a.label + '?') } : null,
      st.root && !a.enabled ? { label: 'Enable App', run: () => act('enable') } : null,
      st.root && !a.system ? { label: 'Uninstall', ico: 'trash', danger: true, run: () => act('uninstall', 'Uninstall ' + a.label + '?') } : null,
    ].filter(Boolean);
  }
  function pick() { apkPick.click(); }
  async function installApk(file) {
    toast('Uploading and installing ' + file.name + '…', 4000);
    try {
      const r = await fetch('/api/root/install', { method: 'POST', body: file, credentials: 'same-origin' });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || r.status);
      if (j.mode === 'root') { toast('Installation succeeded'); load(); }
      else toast('Uploaded — confirm installation on phone screen', 5000);
    } catch (e) { toast('Installation failed: ' + e.message, 5000); }
  }
  let dragN = 0;
  list.addEventListener('dragenter', e => { if ([...e.dataTransfer.types].includes('Files')) { dragN++; drop.classList.add('drag'); } });
  list.addEventListener('dragleave', () => { if (--dragN <= 0) { dragN = 0; drop.classList.remove('drag'); } });
  list.addEventListener('dragover', e => e.preventDefault());
  list.addEventListener('drop', e => { e.preventDefault(); dragN = 0; drop.classList.remove('drag'); const f = [...e.dataTransfer.files].find(f => /\.apk$/i.test(f.name)); if (f) installApk(f); else toast('Drop .apk files only'); });

  async function load() {
    try {
      const r = await api('root/apps', { system: st.showSystem ? 1 : 0 });
      st.apps = r.apps;
      st.root = r.root;
      drop.textContent = st.root ? 'Drop .apk file here for silent install (root)' : 'Drop .apk file here to install (confirm on phone)';
      render();
    } catch (e) { list.replaceChildren(el('div', { class: 'notice', text: 'Failed to load apps: ' + e.message })); }
  }
  (async () => { try { st.flavor = (await api('root/status')).flavor; } catch {} load(); })();
  return { handle(arg) { if (arg && arg.install) pick(); } };
}

// ---------------------------------------------------------------- NexOS Wallpaper

const NEX_LOGO = `<g transform="translate(-54,-54)"><rect width="108" height="108" rx="26" fill="#1e6fd9"/><path d="M26,40h22l8,8h32a5,5 0 0 1 5,5v30a5,5 0 0 1 -5,5h-62a5,5 0 0 1 -5,-5v-38a5,5 0 0 1 5,-5z" fill="#ffc94a"/><path d="M54,52l16,16h-11v16h-10v-16h-11z" fill="#fff"/></g>`;

const WALLPAPERS = {
  nexos_dark: {
    name: 'NexOS Dark',
    svg: (w, h, sub) => `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">
      <defs><radialGradient id="g" cx="50%" cy="34%" r="80%"><stop offset="0" stop-color="#1b2a44"/><stop offset="55%" stop-color="#0e1626"/><stop offset="1" stop-color="#070b14"/></radialGradient>
      <linearGradient id="t" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#5aa0f0"/><stop offset="1" stop-color="#9f7bff"/></linearGradient></defs>
      <rect width="${w}" height="${h}" fill="url(#g)"/>
      ${rays(w, h, 0.05)}
      <g transform="translate(${w/2},${h*0.4}) scale(${Math.min(w,h)/420})">${NEX_LOGO}</g>
      <text x="${w/2}" y="${h*0.4 + Math.min(w,h)*0.2}" text-anchor="middle" font-family="Segoe UI, Arial" font-weight="700" font-size="${Math.min(w,h)*0.085}" fill="url(#t)">NexOS</text>
      <text x="${w/2}" y="${h*0.4 + Math.min(w,h)*0.26}" text-anchor="middle" font-family="Segoe UI, Arial" font-size="${Math.min(w,h)*0.028}" fill="#8ba0bd" letter-spacing="6">N E X S H A R E</text>
      ${sub ? `<text x="${w/2}" y="${h*0.93}" text-anchor="middle" font-family="Segoe UI, Arial" font-size="${Math.min(w,h)*0.024}" fill="#5b6c84">${esc(sub)}</text>` : ''}
    </svg>`,
  },
  nexos_blue: {
    name: 'NexOS Aqua',
    svg: (w, h, sub) => `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">
      <defs><linearGradient id="g" x1="0" y1="0" x2="0.6" y2="1"><stop offset="0" stop-color="#d6eefc"/><stop offset="0.4" stop-color="#8fc6ee"/><stop offset="1" stop-color="#3d80c6"/></linearGradient></defs>
      <rect width="${w}" height="${h}" fill="url(#g)"/>
      ${rays(w, h, 0.12)}
      <g transform="translate(${w/2},${h*0.42}) scale(${Math.min(w,h)/380})">${NEX_LOGO}</g>
      <text x="${w/2}" y="${h*0.42 + Math.min(w,h)*0.21}" text-anchor="middle" font-family="Segoe UI, Arial" font-weight="700" font-size="${Math.min(w,h)*0.088}" fill="#13477f">NexOS</text>
      <text x="${w/2}" y="${h*0.42 + Math.min(w,h)*0.275}" text-anchor="middle" font-family="Segoe UI, Arial" font-size="${Math.min(w,h)*0.028}" letter-spacing="6" fill="#1e6fd9">N E X S H A R E</text>
      ${sub ? `<text x="${w/2}" y="${h*0.93}" text-anchor="middle" font-family="Segoe UI, Arial" font-size="${Math.min(w,h)*0.024}" fill="#14467c">${esc(sub)}</text>` : ''}
    </svg>`,
  },
  nexos_grid: {
    name: 'NexOS Grid',
    svg: (w, h, sub) => `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">
      <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#0b1220"/><stop offset="1" stop-color="#101d33"/></linearGradient>
      <pattern id="grid" width="${w/18}" height="${w/18}" patternUnits="userSpaceOnUse"><path d="M${w/18} 0H0V${w/18}" fill="none" stroke="#1d3a5f" stroke-width="1"/></pattern></defs>
      <rect width="${w}" height="${h}" fill="url(#g)"/><rect width="${w}" height="${h}" fill="url(#grid)" opacity="0.5"/>
      <circle cx="${w/2}" cy="${h*0.4}" r="${Math.min(w,h)*0.28}" fill="none" stroke="#1e6fd9" stroke-width="2" opacity="0.4"/>
      <g transform="translate(${w/2},${h*0.4}) scale(${Math.min(w,h)/400})">${NEX_LOGO}</g>
      <text x="${w/2}" y="${h*0.4 + Math.min(w,h)*0.2}" text-anchor="middle" font-family="Segoe UI, Arial" font-weight="700" font-size="${Math.min(w,h)*0.082}" fill="#e6edf3">NexOS</text>
      <text x="${w/2}" y="${h*0.4 + Math.min(w,h)*0.255}" text-anchor="middle" font-family="Segoe UI, Arial" font-size="${Math.min(w,h)*0.026}" fill="#5aa0f0" letter-spacing="5">NEXSHARE EDITION</text>
      ${sub ? `<text x="${w/2}" y="${h*0.93}" text-anchor="middle" font-family="monospace" font-size="${Math.min(w,h)*0.022}" fill="#3f6187">${esc(sub)}</text>` : ''}
    </svg>`,
  },
};
function rays(w, h, a) {
  let s = `<g opacity="${a}" fill="#fff">`;
  for (let i = 0; i < 7; i++) { const x = w * (0.6 + i * 0.12); s += `<polygon points="${w},0 ${x},0 ${w},${h * (0.3 + i * 0.1)}"/>`; }
  return s + '</g>';
}
const esc = s => String(s).replace(/[<>&]/g, c => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' }[c]));

function Wallpaper(body, win) {
  const st = { key: 'nexos_dark', showInfo: true, w: screen.width * (devicePixelRatio || 1), h: screen.height * (devicePixelRatio || 1) };
  if (!st.w || st.w < 720) { st.w = 1080; st.h = 2340; }
  const preview = el('div', { style: { flex: '1', display: 'grid', placeItems: 'center', background: '#11161d', minHeight: 0, padding: '14px' } });
  const frame = el('div', { style: { height: '100%', aspectRatio: st.w + '/' + st.h, maxWidth: '100%', borderRadius: '18px', overflow: 'hidden', boxShadow: '0 10px 40px rgba(0,0,0,.5)', border: '6px solid #222' } });
  preview.append(frame);
  const thumbs = el('div', { style: { display: 'flex', gap: '10px', padding: '12px', overflowX: 'auto', borderTop: '1px solid var(--line)' } });
  const infoChk = el('label', { style: { display: 'flex', gap: '6px', alignItems: 'center', fontSize: '13px' } },
    el('input', { type: 'checkbox', checked: true, onchange: e => { st.showInfo = e.target.checked; render(); } }), 'Include device name & IP');
  const status = el('span', { style: { color: 'var(--text-3)', fontSize: '12px' } });
  const setBtn = el('button', { class: 'btn primary', onclick: e => menuAt(e.currentTarget, [
    { label: 'Set as Wallpaper (Home + Lock)', run: () => apply('both') },
    { label: 'Home Screen only', run: () => apply('home') },
    { label: 'Lock Screen only', run: () => apply('lock') },
    { label: 'Download Image', ico: 'download', run: () => download() },
  ]) }, icon('download'), 'Set as Wallpaper', icon('chev', 'chev'));
  const bar = el('div', { class: 'ds-tools' }, infoChk, el('span', { style: { flex: 1 } }), status, setBtn);
  body.append(el('div', { style: { flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 } }, bar, preview, thumbs));

  const sub = () => st.showInfo && Stats.last ? `${Stats.last.name}  ·  ${Stats.last.ip || ''}  ·  nexshare.local` : '';
  const svgOf = key => WALLPAPERS[key].svg(st.w, st.h, sub());

  function render() {
    frame.innerHTML = svgOf(st.key);
    const s = frame.querySelector('svg');
    if (s) { s.setAttribute('width', '100%'); s.setAttribute('height', '100%'); s.style.display = 'block'; }
    thumbs.replaceChildren(...Object.keys(WALLPAPERS).map(k => {
      const t = el('div', { style: { flex: 'none', width: '72px', aspectRatio: st.w + '/' + st.h, borderRadius: '10px', overflow: 'hidden', cursor: 'pointer', border: k === st.key ? '2px solid var(--accent)' : '2px solid transparent' }, onclick: () => { st.key = k; render(); } });
      t.innerHTML = WALLPAPERS[k].svg(st.w, st.h, '');
      const sv = t.querySelector('svg'); if (sv) { sv.setAttribute('width', '100%'); sv.setAttribute('height', '100%'); }
      return t;
    }));
    win.setTitle('Wallpaper — ' + WALLPAPERS[st.key].name);
  }
  async function toBlob() {
    const svg = svgOf(st.key);
    const img = new Image();
    const url = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml' }));
    await new Promise((res, rej) => { img.onload = res; img.onerror = rej; img.src = url; });
    const canvas = el('canvas'); canvas.width = st.w; canvas.height = st.h;
    canvas.getContext('2d').drawImage(img, 0, 0, st.w, st.h);
    URL.revokeObjectURL(url);
    return await new Promise(r => canvas.toBlob(r, 'image/jpeg', 0.92));
  }
  async function apply(which) {
    status.textContent = 'Setting wallpaper…';
    try {
      const blob = await toBlob();
      const r = await fetch('/api/wallpaper' + qs({ which }), { method: 'POST', body: blob, credentials: 'same-origin' });
      if (!r.ok) { const j = await r.json().catch(() => ({})); throw new Error(j.error || r.status); }
      status.textContent = '';
      toast('NexOS wallpaper set successfully');
    } catch (e) { status.textContent = ''; toast('Failed: ' + e.message, 4000); }
  }
  async function download() {
    const blob = await toBlob();
    const a = el('a', { href: URL.createObjectURL(blob), download: 'NexOS-' + st.key + '.jpg' });
    document.body.append(a); a.click(); setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
  }
  render();
  return {};
}
// ---------------------------------------------------------------- Remote Access (Cloudflare Tunnel)

const TUN_STATE = { off: 'Disabled', starting: 'Connecting…', connected: 'Connected', error: 'Error' };

function RemoteAccess(body, win) {
  const pane = el('div', { class: 'pane' });
  body.append(pane);
  let timer, lastState = null, logOpen = false;

  const dot = el('span', { style: { width: '12px', height: '12px', borderRadius: '50%', background: '#9aa6b2', flex: 'none' } });
  const stateLabel = el('b', { text: 'Checking status...' });
  const modePill = el('span', { class: 'st-pill', style: { display: 'none' } });
  const statusRow = el('div', { style: { display: 'flex', alignItems: 'center', gap: '10px' } }, dot, stateLabel, modePill);

  const urlBox = el('div', { style: { display: 'none', marginTop: '12px' } });
  const urlLink = el('a', { class: 'mono', target: '_blank', style: { fontSize: '16px', color: 'var(--accent)', wordBreak: 'break-all' } });
  const copyBtn = el('button', { class: 'btn', onclick: () => copy(urlLink.href) }, icon('link'), ' Copiar Link');
  const davHint = el('div', { style: { display: 'none', marginTop: '10px', fontSize: '12px', color: 'var(--text-2)', lineHeight: 1.6 } });
  const namedHint = el('div', { style: { display: 'none', marginTop: '8px', fontSize: '12px', color: '#b45309', lineHeight: 1.6 } },
    el('b', { text: 'Dica: ' }), 'se o domínio não abrir, confira em Cloudflare → seu túnel → Public hostnames se existe uma rota apontando para http://localhost:8080 e preencha o domínio no campo acima.');
  urlBox.append(
    el('div', { style: { fontSize: '12px', color: 'var(--text-3)', marginBottom: '4px' }, text: 'Link Público (Acessível em qualquer lugar do mundo):' }),
    el('div', { style: { display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap' } }, urlLink, copyBtn),
    davHint,
    namedHint
  );

  const errNotice = el('div', { class: 'notice danger', style: { display: 'none', margin: '12px 0 0', color: '#e5484d', lineHeight: 1.55 } });
  const errRaw = el('div', { class: 'mono', style: { display: 'none', margin: '6px 0 0', fontSize: '11px', color: 'var(--text-3)', wordBreak: 'break-word' } });
  const progressNote = el('div', { style: { display: 'none', margin: '10px 0 0', fontSize: '12px', color: '#b45309' } });
  const statusCard = el('div', { class: 'card' }, statusRow, progressNote, urlBox, errNotice, errRaw);

  const quickBtn = el('button', { class: 'btn primary', style: { fontSize: '14px', padding: '10px 16px' }, onclick: () => act('start', { mode: 'quick' }) }, icon('link'), ' ⚡ Conectar Quick Tunnel (Grátis / 1 Clique)');
  const stopBtn = el('button', { class: 'btn danger', style: { display: 'none' }, onclick: () => act('stop') }, icon('x'), ' Parar Túnel');
  const reconnectBtn = el('button', { class: 'btn', style: { display: 'none' }, onclick: () => act('start', { mode: (lastState && lastState.mode === 'named') ? 'named' : 'quick' }) }, icon('refresh'), ' Reconectar');

  const tokenInput = el('input', {
    type: 'text',
    placeholder: 'Cole seu token do Cloudflare Zero Trust (eyJh...) aqui',
    style: { fontFamily: 'monospace', flex: '1', minWidth: '220px', padding: '8px 10px', borderRadius: '6px', border: '1px solid #cbd5e1' }
  });
  let userEdited = false;
  tokenInput.addEventListener('input', () => { userEdited = true; });

  const hostInput = el('input', {
    type: 'text',
    placeholder: 'Domínio público (opcional): nas.seudominio.com',
    style: { fontFamily: 'monospace', flex: '1', minWidth: '220px', padding: '8px 10px', borderRadius: '6px', border: '1px solid #cbd5e1' }
  });
  hostInput.value = (lastState && lastState.host) || '';

  const tokenBtn = el('button', {
    class: 'btn',
    onclick: () => {
      const val = tokenInput.value.trim();
      act('start', { mode: 'named', token: val || undefined, host: hostInput.value.trim() });
    }
  }, icon('link'), ' Conectar com Token');

  const testTokenBtn = el('button', {
    class: 'btn',
    style: { background: '#0ea5e9', color: '#fff' },
    onclick: async () => {
      const val = tokenInput.value.trim();
      if (!val) { toast('Cole o token primeiro'); return; }
      testTokenBtn.disabled = true;
      testTokenBtn.textContent = 'Testando…';
      try {
        const r = await api('tunnel/test-token', null, { method: 'POST', body: JSON.stringify({ token: val }) });
        if (r.ok) {
          toast('✓ ' + (r.message || 'Token válido!'), 5000);
        } else {
          toast('✗ ' + (r.message || 'Token inválido'), 6000);
        }
      } catch (e) {
        toast('Erro no teste: ' + e.message);
      } finally {
        testTokenBtn.disabled = false;
        testTokenBtn.textContent = 'Testar Token';
      }
    }
  }, icon('info'), ' Testar Token');

  const clearTokenBtn = el('button', {
    class: 'btn',
    style: { display: 'none' },
    onclick: () => { tokenInput.value = ''; userEdited = true; tokenInput.placeholder = 'Cole o token aqui'; tokenInput.focus(); }
  }, 'Limpar');

  const actionsCard = el('div', { class: 'card' },
    el('h3', { style: { marginTop: 0 }, text: '⚡ Acesso Remoto com 1 Clique (Recomendado)' }),
    el('div', { class: 'notice', style: { margin: '0 0 12px' }, text: 'O Quick Tunnel permite acessar o celular pela internet sem precisar criar conta, sem configurar portas e sem tokens. Funciona em faculdades, Wi-Fi institucional e redes móveis.' }),
    el('div', { style: { display: 'flex', gap: '8px', flexWrap: 'wrap', marginBottom: '16px' } }, quickBtn, stopBtn, reconnectBtn),
    el('h3', { text: 'Ou usar Domínio Próprio (Named Tunnel)' }),
    el('div', { class: 'hint', style: { marginBottom: '8px', lineHeight: 1.6 }, html: 'Zero Trust → Networks → Tunnels → seu túnel → <b>Configure</b> → <b>Install connector</b>: copie só o texto depois de <span class="mono">--token</span> (começa com <span class="mono">eyJh</span>). Em <b>Public hostnames</b> aponte seu domínio para <span class="mono">http://localhost:' + (Stats.last ? Stats.last.port : 8080) + '</span> e informe o mesmo domínio abaixo.' }),
    el('div', { style: { display: 'flex', gap: '8px', flexWrap: 'wrap', alignItems: 'center' } }, tokenInput, tokenBtn, testTokenBtn, clearTokenBtn),
    el('div', { style: { display: 'flex', gap: '8px', flexWrap: 'wrap', alignItems: 'center', marginTop: '8px' } }, hostInput),
    el('div', { style: { display: 'flex', gap: '8px', flexWrap: 'wrap', marginTop: '12px' } },
      el('button', { class: 'btn', onclick: () => diagnose() }, icon('info'), ' Testar conexão / diagnóstico'))
  );

  async function diagnose() {
    try {
      const d = await api('tunnel/diagnose');
      const lines = [
        'Binário: ' + (d.supported ? 'ok' : 'AUSENTE') + ' (' + (d.arch || '?') + ') ' + (d.binary || ''),
        'Executável: ' + (d.executable ? 'sim' : 'não'),
        'Internet: ' + (d.internet ? 'sim' : 'NÃO'),
        'DNS do celular: ' + (d.dns || '-'),
        'api.trycloudflare.com: ' + d.api,
        'region1.v2.argotunnel.com: ' + d.edge,
        'Estado: ' + d.state + ' (tentativa ' + (d.attempt || 0) + '/2)',
        d.explain ? 'Diagnóstico: ' + d.explain : '',
        d.error ? 'Último erro: ' + d.error : '',
        '',
        lastState && lastState.log ? lastState.log : '(sem logs)',
      ].filter(x => x !== '');
      logOpen = true;
      logPre.textContent = lines.join('\n');
      renderLogs();
    } catch (e) { toast('Diagnóstico falhou: ' + e.message, 4000); }
  }

  const logPre = el('pre', { class: 'mono', style: { display: 'none', marginTop: '10px', maxHeight: '220px', overflow: 'auto', background: '#0d1117', color: '#c9d1d9', padding: '12px', borderRadius: '8px', fontSize: '11px', whiteSpace: 'pre-wrap', userSelect: 'text' } });
  const logsCard = el('div', { class: 'card' },
    el('div', { style: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', cursor: 'pointer' }, onclick: () => { logOpen = !logOpen; renderLogs(); } },
      el('h3', { style: { margin: 0 }, text: 'Logs do Túnel' }), icon('chev')),
    logPre
  );


  // ---------- alternative access routes ----------
  const altList = el('div', { style: { display: 'flex', flexDirection: 'column', gap: '8px' } });
  const altCard = el('div', { class: 'card' },
    el('h3', { style: { marginTop: 0 }, text: '🔀 Outras formas de acessar' }),
    el('div', { class: 'hint', style: { marginBottom: '10px', lineHeight: 1.6 }, text: 'Se o Quick Tunnel estiver bloqueado na sua rede, use uma destas rotas. Elas não dependem da Cloudflare.' }),
    altList,
    el('div', { style: { borderTop: '1px solid #e2e8f0', margin: '14px 0 12px' } }),
    el('h4', { style: { margin: '0 0 6px' }, text: '☁️ Cloudflare com domínio próprio (login)' }),
    el('div', { style: { fontSize: '13px', lineHeight: 1.7, color: 'var(--text-2)' } },
      el('p', { style: { margin: '0 0 8px' }, text: 'A NexShare não guarda sua senha da Cloudflare. Faça o login no site, crie o túnel e cole aqui apenas o token do conector — é a forma segura e a que evita o aviso de “site perigoso”.' }),
      el('div', { style: { display: 'flex', gap: '8px', flexWrap: 'wrap', margin: '8px 0' } },
        el('a', { class: 'btn primary', href: 'https://dash.cloudflare.com/login', target: '_blank', rel: 'noopener', style: { textDecoration: 'none' } }, icon('link'), ' Entrar na Cloudflare'),
        el('a', { class: 'btn', href: 'https://one.dash.cloudflare.com/', target: '_blank', rel: 'noopener', style: { textDecoration: 'none' } }, ' Zero Trust / Tunnels')),
      el('ol', { style: { margin: '6px 0 0', paddingLeft: '20px' } },
        el('li', null, 'Entre na Cloudflare e abra Zero Trust → Networks → Tunnels.'),
        el('li', null, 'Create a tunnel → Cloudflared → copie o token (ou o comando de instalação).'),
        el('li', null, 'Em Public hostname, aponte seu domínio para http://localhost:8080.'),
        el('li', null, 'Cole o token no campo “Conectar com Token” acima e clique em conectar.'))));

  async function loadAltRoutes() {
    try {
      const s = await api('system');
      const rows = [];
      const add = (title, desc, url) => {
        if (!url) return;
        rows.push(el('div', { style: { display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap', background: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '9px 10px' } },
          el('div', { style: { flex: 1, minWidth: '190px' } },
            el('div', { style: { fontSize: '13px', fontWeight: '600' }, text: title }),
            el('div', { class: 'dim', style: { fontSize: '11px', marginTop: '2px' }, text: desc }),
            el('div', { class: 'mono', style: { fontSize: '12px', marginTop: '3px', wordBreak: 'break-all' }, text: url })),
          el('button', { class: 'btn', style: { padding: '4px 10px', fontSize: '12px' }, onclick: () => copy(url) }, 'Copiar')));
      };
      const urls = s.urls || [];
      const lan = urls.find(u => u.kind === 'lan') || (s.ip ? { url: 'http://' + s.ip + ':' + (s.port || 8080) } : null);
      add('Rede local (Wi-Fi)', 'Mesma rede do celular — mais rápido e sem internet.', lan && lan.url);
      const ts = urls.find(u => u.kind === 'tailscale');
      if (ts) add('Tailscale (VPN privada)', 'Funciona fora de casa sem abrir portas. O IP 100.x é privado da sua VPN.', ts.url);
      else rows.push(el('div', { style: { fontSize: '12px', color: 'var(--text-2)', lineHeight: 1.6, padding: '4px 2px' } },
        el('b', { text: 'Tailscale: ' }), 'instale o Tailscale no celular e no PC, entre na mesma conta e use o IP 100.x mostrado no app. É a opção mais estável fora de casa.',
        el('a', { href: 'https://tailscale.com/download', target: '_blank', rel: 'noopener', style: { marginLeft: '5px', fontWeight: 600 }, text: 'Baixar' })));
      const v6 = urls.find(u => u.kind === 'ipv6') || (s.ipv6 ? { url: 'http://[' + s.ipv6 + ']:' + (s.port || 8080) } : null);
      if (v6) add('IPv6 direto', 'Só funciona se a operadora e o roteador liberarem IPv6 de entrada.', v6.url);
      else rows.push(el('div', { class: 'hint', style: { fontSize: '12px' }, text: 'Sem IPv6 global agora. Muitas operadoras usam CGNAT; nesse caso use Tailscale ou Cloudflare.' }));
      if (!rows.length) rows.push(el('div', { class: 'hint', text: 'Não encontrei endereços de rede. Confira se o Wi-Fi está ligado.' }));
      altList.replaceChildren(...rows);
    } catch (e) {
      altList.replaceChildren(el('div', { class: 'hint', text: 'Não foi possível ler os endereços: ' + e.message }));
    }
  }
  loadAltRoutes();

  const safetyCard = el('div', { class: 'card' },
    el('h3', { style: { marginTop: 0 } }, 'Aviso de “site perigoso”'),
    el('p', { style: { margin: '0 0 8px', color: 'var(--text-2)', lineHeight: 1.6 } },
      'O Quick Tunnel usa um subdomínio trycloudflare.com compartilhado por milhares de pessoas. ',
      'Navegadores e antivírus às vezes marcam esse domínio inteiro como suspeito — não é o seu celular.'),
    el('p', { style: { margin: '0 0 8px', color: 'var(--text-2)', lineHeight: 1.6 } },
      'A solução definitiva é o túnel nomeado com um domínio seu (campo acima): o endereço deixa de ser compartilhado. ',
      'Se o aviso continuar, peça a revisão:'),
    el('a', { href: 'https://safebrowsing.google.com/safebrowsing/report_error/?hl=pt-BR', target: '_blank', rel: 'noopener', style: { fontWeight: 600 } },
      'Reportar falso positivo ao Google Safe Browsing')
  );

  pane.append(
    el('h2', null, 'Acesso Remoto'),
    statusCard,
    actionsCard,
    altCard,
    safetyCard,
    logsCard
  );

  async function act(path, payload) {
    try {
      const r = await api('tunnel/' + path, null, payload ? { method: 'POST', body: JSON.stringify(payload) } : { method: 'POST' });
      render(r);
    } catch (e) { toast('Falha: ' + e.message, 4000); refresh(); }
  }

  function renderLogs() {
    logPre.style.display = logOpen ? 'block' : 'none';
    if (lastState && lastState.log) logPre.textContent = lastState.log;
  }

  function render(s) {
    lastState = s;
    const connected = s.state === 'connected';
    const starting = s.state === 'starting';
    const isError = s.state === 'error';
    const dotColor = { off: '#9aa6b2', starting: '#f0a020', connected: '#2fb36b', error: '#e5484d' }[s.state] || '#9aa6b2';

    dot.style.background = dotColor;
    stateLabel.textContent = TUN_STATE[s.state] || s.state;

    if (s.mode && s.mode !== 'off') {
      modePill.style.display = 'inline-block';
      modePill.textContent = s.mode === 'named' ? 'Custom Domain' : 'Quick Tunnel';
    } else {
      modePill.style.display = 'none';
    }

    if (connected && s.url) {
      urlBox.style.display = 'block';
      namedHint.style.display = 'none';
      urlLink.href = s.url;
      urlLink.textContent = s.url;
      State.tunnelUrl = s.url;
      if (State.webdavEnabled) {
        const dav = s.url + '/dav/';
        davHint.style.display = 'block';
        davHint.replaceChildren(
          el('div', null, el('b', { text: 'WebDAV público (Windows): ' }), el('span', { class: 'mono', text: dav })),
          el('div', { style: { marginTop: '4px', opacity: .85 }, text: 'No Windows, rode map-drive.ps1 como administrador: ele monta a unidade por uma ponte local, libera arquivos grandes no WebClient e evita o erro "o arquivo foi movido". Use HTTPS em redes que bloqueiam portas.' }),
          el('button', { class: 'btn', style: { marginTop: '6px' }, onclick: () => copy(dav) }, icon('link'), ' Copiar URL WebDAV')
        );
      } else {
        davHint.style.display = 'none';
      }
    } else if (connected && s.mode === 'named' && !s.url) {
      urlBox.style.display = 'block';
      urlLink.href = '#';
      urlLink.textContent = 'Conectado — acessível pelo domínio configurado no Cloudflare';
      davHint.style.display = 'none';
      namedHint.style.display = 'block';
      State.tunnelUrl = '';
    } else {
      urlBox.style.display = 'none';
      namedHint.style.display = 'none';
      State.tunnelUrl = '';
    }

    if (starting) {
      progressNote.style.display = 'block';
      const mode = (s.attempt || 0) === 0 ? 'automático (QUIC)' : (s.attempt === 1 ? 'HTTP/2' : 'HTTP/2 + IPv4 + DNS público');
      progressNote.textContent = `Conectando… tentativa ${(s.attempt || 0) + 1}/3 · transporte ${mode}. Se a rede bloquear, o app troca de rota sozinho.`;
    } else {
      progressNote.style.display = 'none';
    }

    if (isError && (s.explain || s.error)) {
      errNotice.style.display = 'block';
      errNotice.textContent = s.explain || s.error;
      if (s.explain && s.error && s.explain !== s.error) {
        errRaw.style.display = 'block';
        errRaw.textContent = 'cloudflared: ' + s.error;
      } else {
        errRaw.style.display = 'none';
      }
    } else {
      errNotice.style.display = 'none';
      errRaw.style.display = 'none';
    }

    if (s.host && document.activeElement !== hostInput) hostInput.value = s.host;

    quickBtn.disabled = !s.supported;
    tokenBtn.disabled = !s.supported;

    if (connected || starting) {
      stopBtn.style.display = 'inline-flex';
      reconnectBtn.style.display = 'inline-flex';
      quickBtn.style.display = 'none';
    } else {
      stopBtn.style.display = 'none';
      reconnectBtn.style.display = 'none';
      quickBtn.style.display = 'inline-flex';
    }

    if (!userEdited && !tokenInput.value && s.hasToken) {
      tokenInput.placeholder = 'Token salvo no dispositivo (cole novo para alterar)';
    }
    clearTokenBtn.style.display = s.hasToken ? 'inline-flex' : 'none';

    renderLogs();
  }

  function copy(url) {
    if (navigator.clipboard && isSecureContext) navigator.clipboard.writeText(url).then(() => toast('Link copiado para a área de transferência!'));
    else dialog({ title: 'Link Público', input: url, cancel: false });
  }

  async function refresh() {
    try { render(await api('tunnel/status')); } catch { /* offline */ }
  }

  refresh();
  // Always poll while the window is open: a Quick Tunnel gets a new URL when it reconnects.
  timer = setInterval(() => refresh(), 3000);
  return { destroy() { clearInterval(timer); } };
}

// ---------------------------------------------------------------- Debrid Hub

function DebridHub(body, win) {
  const pane = el('div', { class: 'pane' });
  body.append(pane);

  const st = { task: null, files: [], active: [], timer: null, tab: 'guide', busy: false };

  // Torrents live in their own qBittorrent-style app now. The old stream tab is kept
  // only as a hidden implementation detail for backwards compatibility.
  const tabStream = el('button', { class: 'btn primary', text: '⚡ Desbridar & Assistir', onclick: () => switchTab('stream') });
  const tabGuide = el('button', { class: 'btn primary', text: '🔌 Usar no VLC / Stremio / Windows', onclick: () => switchTab('guide') });
  const tabAddon = el('button', { class: 'btn', text: '⚙️ Configurar Addon Nuvio', onclick: () => switchTab('addon') });
  const tabBar = el('div', { style: { display: 'flex', gap: '8px', marginBottom: '14px', flexWrap: 'wrap' } }, tabGuide, tabAddon);

  // ---------- stream tab ----------
  const input = el('textarea', {
    placeholder: 'Cole um magnet link (magnet:?xt=urn:btih:...) ou uma URL de .torrent / vídeo direto',
    rows: 3,
    style: { width: '100%', boxSizing: 'border-box', fontFamily: 'monospace', fontSize: '13px', padding: '10px', borderRadius: '8px', border: '1px solid #cbd5e1', resize: 'vertical' }
  });
  const goBtn = el('button', { class: 'btn primary', style: { padding: '9px 16px' }, onclick: () => unrestrict(input.value.trim()) }, icon('play'), ' Desbridar & Transmitir');
  const pasteBtn = el('button', {
    class: 'btn',
    onclick: async () => {
      try {
        const text = await navigator.clipboard.readText();
        if (text) { input.value = text; input.focus(); }
      } catch { toast('Use Ctrl+V para colar no campo'); input.focus(); }
    }
  }, icon('link'), ' Colar');
  const statusMsg = el('div', { style: { marginTop: '10px', fontSize: '13px', color: 'var(--text-2)' } });
  const filesBox = el('div', { style: { marginTop: '14px' } });
  const activeBox = el('div', { style: { marginTop: '18px' } });

  const streamCard = el('div', { class: 'card' },
    el('h3', { style: { marginTop: 0 }, text: '⚡ Desbridador de Torrents' }),
    el('div', { class: 'notice', style: { margin: '0 0 12px' }, text: 'O download é feito em ordem (streaming sequencial) começando pelo arquivo de vídeo, então a reprodução começa em segundos — como no Real-Debrid.' }),
    input,
    el('div', { style: { display: 'flex', gap: '8px', alignItems: 'center', marginTop: '10px', flexWrap: 'wrap' } }, goBtn, pasteBtn, el('span', { class: 'dim', style: { fontSize: '12px' }, text: 'Aceita magnet, .torrent e links http(s) diretos.' })),
    statusMsg,
    filesBox,
    activeBox
  );

  // ---------- setup tab ----------
  const apiBase = () => (State.tunnelUrl || location.origin) + '/rest/1.0/';
  const apiKey = () => State.apiKey || '';
  const lanBase = () => location.origin;

  const code = (text) => el('div', { class: 'mono', style: { display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap', background: '#f1f5f9', border: '1px solid #e2e8f0', borderRadius: '6px', padding: '8px 10px', fontSize: '12px', wordBreak: 'break-all' } },
    el('span', { style: { flex: 1 }, text }),
    el('button', { class: 'btn', style: { padding: '3px 10px', fontSize: '12px' }, onclick: () => copyText(text, 'Copiado!') }, 'Copiar'));

  const keyRow = code(apiKey() || '(faça login no painel para gerar a chave)');
  const guideCard = el('div', { class: 'card', style: { display: 'block' } },
    el('h3', { style: { marginTop: 0 }, text: '🔌 NexShare como serviço Debrid' }),
    el('div', { class: 'notice', style: { margin: '0 0 14px', display: 'flex', gap: '10px', alignItems: 'center', flexWrap: 'wrap' } },
      el('span', { style: { flex: 1 }, text: 'Para adicionar magnets, ver a lista de arquivos e baixar como no qBittorrent, use o app Torrent Downloader.' }),
      el('button', { class: 'btn primary', onclick: () => openApp('torrents') }, icon('download'), ' Abrir Torrent Downloader')),
    el('div', { class: 'notice', style: { margin: '0 0 14px' }, text: 'A API é compatível com o formato do Real-Debrid (/rest/1.0), então VLC, Stremio, Kodi e qualquer cliente que fale a API do RD podem usar o celular como fonte.' }),
    el('h4', { style: { margin: '0 0 6px' }, text: 'Chave de API' }), keyRow,
    el('div', { style: { marginTop: '6px' } },
      el('button', {
        class: 'btn',
        onclick: async () => {
          const go = await dialog({ title: 'Gerar nova chave?', message: 'Os links já copiados no VLC/Stremio deixam de funcionar.', okText: 'Gerar', danger: true });
          if (!go) return;
          try {
            const r = await api('debrid/rotatekey', null, { method: 'POST' });
            State.apiKey = r.apiKey;
            keyRow.replaceChildren(el('span', { style: { flex: 1 }, text: r.apiKey }), el('button', { class: 'btn', style: { padding: '3px 10px', fontSize: '12px' }, onclick: () => copyText(r.apiKey, 'Copiado!') }, 'Copiar'));
            toast('Nova chave gerada');
          } catch (e) { toast('Falha: ' + e.message); }
        }
      }, 'Gerar nova chave')),
    el('h4', { style: { margin: '14px 0 6px' }, text: 'Endpoint na rede local' }), code(lanBase() + '/rest/1.0/'),
    el('h4', { style: { margin: '14px 0 6px' }, text: 'Endpoint pela internet (Cloudflare)' }),
    code(State.tunnelUrl ? State.tunnelUrl + '/rest/1.0/' : 'Ligue o Acesso Remoto em Remote Access para ter uma URL pública HTTPS'),
    el('h4', { style: { margin: '16px 0 6px', color: '#1e6fd9' }, text: '1. VLC / MPV / Smart TV' }),
    el('ol', { style: { margin: 0, paddingLeft: '20px', lineHeight: 1.7, fontSize: '13px' } },
      el('li', null, 'Abra o Torrent Downloader, cole o magnet (ou envie um .torrent) e clique em Adicionar.'),
      el('li', null, 'Quando a lista aparecer, marque só os arquivos que quer e confirme — o download só começa aí.'),
      el('li', null, 'No menu do torrent (clique com o botão direito), escolha "Copiar link para VLC".'),
      el('li', null, 'No VLC: Mídia → Abrir Fluxo de Rede (Ctrl+N), cole o link e clique em Reproduzir.')),
    el('h4', { style: { margin: '16px 0 6px', color: '#1e6fd9' }, text: '2. Stremio (addon com suporte a Real-Debrid)' }),
    el('div', { style: { fontSize: '13px', lineHeight: 1.7 } },
      el('p', { style: { margin: '0 0 6px' }, text: 'No addon / configuração de debrid do Stremio, informe:' }),
      el('ul', { style: { margin: 0, paddingLeft: '20px' } },
        el('li', null, el('b', { text: 'API URL: ' }), el('span', { class: 'mono', text: apiBase() })),
        el('li', null, el('b', { text: 'API Key: ' }), el('span', { class: 'mono', text: apiKey() || '(veja acima)' }))),
      el('p', { class: 'dim', style: { margin: '8px 0 0', fontSize: '12px' }, text: 'Fora de casa use a URL pública: ligue o Acesso Remoto e copie o endpoint HTTPS acima.' })),
    el('h4', { style: { margin: '16px 0 6px', color: '#1e6fd9' }, text: '3. Nuvio / Stremio: addon NexShare (pronto, sem configurar nada)' }),
    (() => {
      const manifest = lanBase() + '/addon/' + apiKey() + '/manifest.json';
      const manifestTunnel = State.tunnelUrl ? State.tunnelUrl + '/addon/' + apiKey() + '/manifest.json' : '';
      const testOut = el('div', { class: 'dim', style: { fontSize: '12px', marginTop: '6px' } });
      return el('div', { style: { fontSize: '13px', lineHeight: 1.7 } },
        el('p', { style: { margin: '0 0 8px' }, text: 'Este addon faz o Nuvio (e o Stremio) usar o celular como debrid: ele consulta o Torrentio, e o torrent só entra no celular buscando metadados quando você toca no stream. O arquivo escolhido começa a baixar e toca na hora.' }),
        code(manifest),
        manifestTunnel ? el('div', { style: { marginTop: '6px' } }, code(manifestTunnel)) : el('div', null),
        el('ol', { style: { margin: '8px 0 0', paddingLeft: '20px' } },
          el('li', null, 'No Nuvio: Settings → Content & Discovery → Addons → cole a URL acima → Install Addon.'),
          el('li', null, 'No Stremio: Addons → Community → instalar pelo mesmo link (ou Addon Manager → Install from URL).'),
          el('li', null, 'Escolha um filme/episódio e toque num stream marcado "▶ desbridar no celular".')),
        el('div', { style: { marginTop: '8px', display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap' } },
          el('button', {
            class: 'btn',
            onclick: async (e) => {
              const b = e.currentTarget;
              b.disabled = true;
              testOut.textContent = 'Testando o Torrentio a partir do celular…';
              try {
                const r = await api('addon/test', { id: 'tt0111161' });
                if (r.ok) testOut.textContent = `✓ Torrentio respondeu ${r.streams} resultado(s) em ${r.ms} ms`;
                else testOut.textContent = `✗ Falha (${r.ms} ms): ${r.error}`;
              } catch (err) {
                testOut.textContent = '✗ ' + err.message;
              } finally { b.disabled = false; }
            }
          }, 'Testar addon'),
          el('span', { class: 'dim', style: { fontSize: '12px' }, text: 'o teste consulta o Torrentio pelo celular' })),
        testOut,
        el('p', { class: 'dim', style: { margin: '8px 0 0', fontSize: '12px' }, text: 'Fora de casa, use a URL pública (liga o Acesso Remoto primeiro). Se o celular tiver o app do PC com "Mandar pro PC automaticamente" ligado, os torrents vindos do addon ficam de fora, para o arquivo não sair do celular no meio do filme.' }));
    })(),
    el('h4', { style: { margin: '16px 0 6px', color: '#1e6fd9' }, text: '4. Windows: unidade de rede (WebDAV)' }),
    el('div', { style: { fontSize: '13px', lineHeight: 1.7 } },
      el('p', { style: { margin: '0 0 8px' }, text: 'Os arquivos ficam em Download/NexShare no celular. Habilite o WebDAV no Control Panel e use HTTPS (funciona em redes de instituição, sem ajustar o registro do Windows):' }),
      code(`net use Z: ${(State.tunnelUrl || lanBase())}/dav/ /user:nexshare <SEU_PIN>`))
  );

  // ---------- addon settings tab ----------
  const addonCard = el('div', { class: 'card', style: { display: 'none' } },
    el('h3', { style: { marginTop: 0 }, text: '⚙️ Configurar Addon Nuvio / Stremio' }),
    el('div', { class: 'notice', style: { margin: '0 0 14px' }, text: 'Configure como o addon busca streams no Torrentio. As mudanças valem para novos filmes/séries — o cache expira automaticamente.' }),
    el('h4', { style: { margin: '10px 0 6px' }, text: 'URL do Scraper' }),
    el('div', { style: { display: 'flex', gap: '8px', flexWrap: 'wrap', alignItems: 'center', marginBottom: '8px' } },
      el('input', {
        id: 'addonScraperUrl',
        type: 'text',
        placeholder: 'https://torrentio.strem.fun',
        value: '',
        style: { flex: 1, minWidth: '220px', padding: '8px 10px', borderRadius: '6px', border: '1px solid #cbd5e1', fontFamily: 'monospace' }
      }),
      el('button', { class: 'btn primary', onclick: () => saveAddonSetting('scraperUrl') }, icon('download'), ' Salvar'),
      el('button', { class: 'btn', onclick: () => resetAddonSetting('scraperUrl') }, 'Padrão')
    ),
    el('p', { class: 'dim', style: { fontSize: '12px', marginTop: '4px' }, text: 'O addon consulta esta URL para buscar streams. O padrão é o Torrentio oficial. Você pode apontar para um fork ou proxy próprio.' }),
    el('h4', { style: { margin: '14px 0 6px' }, text: 'Cache de Resultados (minutos)' }),
    el('div', { style: { display: 'flex', gap: '8px', flexWrap: 'wrap', alignItems: 'center', marginBottom: '8px' } },
      el('input', {
        id: 'addonCacheMinutes',
        type: 'number',
        min: 0,
        max: 1440,
        value: 10,
        style: { width: '100px', padding: '8px 10px', borderRadius: '6px', border: '1px solid #cbd5e1' }
      }),
      el('button', { class: 'btn primary', onclick: () => saveAddonSetting('cacheMinutes') }, icon('download'), ' Salvar'),
      el('span', { class: 'dim', style: { fontSize: '12px' }, text: '0 = sem cache. O cache evita chamadas repetidas ao Torrentio para o mesmo filme/série.' })
    ),
    el('h4', { style: { margin: '14px 0 6px' }, text: 'Máximo de Streams por Resultado' }),
    el('div', { style: { display: 'flex', gap: '8px', flexWrap: 'wrap', alignItems: 'center', marginBottom: '8px' } },
      el('input', {
        id: 'addonMaxStreams',
        type: 'number',
        min: 1,
        max: 100,
        value: 30,
        style: { width: '100px', padding: '8px 10px', borderRadius: '6px', border: '1px solid #cbd5e1' }
      }),
      el('button', { class: 'btn primary', onclick: () => saveAddonSetting('maxStreams') }, icon('download'), ' Salvar')
    ),
    el('h4', { style: { margin: '14px 0 6px' }, text: 'Timeout de Metadados (ms)' }),
    el('div', { style: { display: 'flex', gap: '8px', flexWrap: 'wrap', alignItems: 'center', marginBottom: '8px' } },
      el('input', {
        id: 'addonMetadataTimeout',
        type: 'number',
        min: 5000,
        max: 120000,
        step: 1000,
        value: 7000,
        style: { width: '120px', padding: '8px 10px', borderRadius: '6px', border: '1px solid #cbd5e1' }
      }),
      el('button', { class: 'btn primary', onclick: () => saveAddonSetting('metadataTimeout') }, icon('download'), ' Salvar'),
      el('span', { class: 'dim', style: { fontSize: '12px' }, text: 'Tempo máximo para buscar metadados do torrent antes de permitir escolher o arquivo.' })
    ),
    el('hr', { style: { margin: '16px 0' } }),
    el('h4', { style: { margin: '0 0 6px' }, text: 'Saúde do Addon' }),
    el('div', { style: { display: 'flex', gap: '8px', flexWrap: 'wrap', marginBottom: '8px' } },
      el('button', { class: 'btn', onclick: checkAddonHealth }, icon('info'), ' Verificar saúde'),
      el('button', { class: 'btn', onclick: clearAddonCache }, icon('trash'), ' Limpar cache')
    ),
    el('div', { id: 'addonHealthOut', class: 'mono', style: { fontSize: '12px', background: '#f1f5f9', border: '1px solid #e2e8f0', borderRadius: '6px', padding: '10px', minHeight: '60px', whiteSpace: 'pre-wrap', wordBreak: 'break-word', color: 'var(--text-2)' } }, 'Clique em "Verificar saúde" para testar a conexão com o scraper.')
  );

  // Load current addon settings
  loadAddonSettings();

  // streamCard deliberately not appended: torrent add/picker moved to Torrent Downloader.
  pane.append(tabBar, guideCard, addonCard);

  function switchTab(t) {
    st.tab = t;
    tabStream.className = 'btn' + (t === 'stream' ? ' primary' : '');
    tabGuide.className = 'btn' + (t === 'guide' ? ' primary' : '');
    tabAddon.className = 'btn' + (t === 'addon' ? ' primary' : '');
    streamCard.style.display = t === 'stream' ? 'block' : 'none';
    guideCard.style.display = t === 'guide' ? 'block' : 'none';
    addonCard.style.display = t === 'addon' ? 'block' : 'none';
  }

  async function loadAddonSettings() {
    try {
      const r = await api('addon/health');
      if (r.scraper) $('#addonScraperUrl').value = r.scraper;
      if (r.cacheMinutes != null) $('#addonCacheMinutes').value = r.cacheMinutes;
      if (r.maxStreams != null) $('#addonMaxStreams').value = r.maxStreams;
      if (r.metadataTimeoutMs != null) $('#addonMetadataTimeout').value = r.metadataTimeoutMs;
    } catch { /* ignore */ }
  }

  async function saveAddonSetting(which) {
    try {
      const body = {};
      if (which === 'scraperUrl') body.scraperUrl = $('#addonScraperUrl').value.trim();
      else if (which === 'cacheMinutes') body.cacheMinutes = parseInt($('#addonCacheMinutes').value) || 0;
      else if (which === 'maxStreams') body.maxStreams = parseInt($('#addonMaxStreams').value) || 30;
      else if (which === 'metadataTimeout') body.metadataTimeoutMs = parseInt($('#addonMetadataTimeout').value) || 7000;
      await api('addon/settings', null, { method: 'POST', body: JSON.stringify(body) });
      toast('Configuração salva');
      loadAddonSettings();
    } catch (e) { toast('Falha ao salvar: ' + e.message); }
  }

  async function resetAddonSetting(which) {
    try {
      if (which === 'scraperUrl') {
        await api('addon/settings', null, { method: 'POST', body: JSON.stringify({ scraperUrl: '' }) });
        $('#addonScraperUrl').value = '';
        toast('Restaurado para padrão');
      }
      loadAddonSettings();
    } catch (e) { toast('Falha: ' + e.message); }
  }

  async function checkAddonHealth() {
    const out = $('#addonHealthOut');
    out.textContent = 'Verificando…';
    try {
      const r = await api('addon/health', { test: 1 });
      out.style.color = r.ok ? '#16a34a' : '#b91c1c';
      out.textContent = `Status: ${r.ok ? '✓ Online' : '✗ Offline'}\n` +
        `Scraper: ${r.scraper || '?'}\n` +
        (r.scraperOk === false ? `Erro: ${r.scraperError || 'falha desconhecida'}\n` : '') +
        `Streams de teste: ${r.scraperStreams != null ? r.scraperStreams : '?'}\n` +
        `Cache: ${r.cacheMinutes || 0} min\n` +
        `Max streams: ${r.maxStreams || 30}\n` +
        `Timeout: ${r.metadataTimeoutMs || 7000} ms\n` +
        `Manifest: ${r.manifestPath || '?'}`;
    } catch (e) {
      out.style.color = '#b91c1c';
      out.textContent = 'Erro: ' + e.message;
    }
  }

  async function clearAddonCache() {
    try {
      await api('addon/clear-cache', null, { method: 'POST' });
      toast('Cache limpo');
      checkAddonHealth();
    } catch (e) { toast('Falha: ' + e.message); }
  }

  function copyText(text, msg) {
    if (!text) { toast('Nada para copiar'); return; }
    if (navigator.clipboard && isSecureContext) navigator.clipboard.writeText(text).then(() => toast(msg || 'Copiado!'), () => dialog({ title: 'Link', input: text, cancel: false }));
    else dialog({ title: 'Link', input: text, cancel: false });
  }

  function setStatus(text, kind) {
    statusMsg.textContent = text || '';
    statusMsg.style.color = kind === 'error' ? '#e5484d' : (kind === 'ok' ? '#16a34a' : 'var(--text-2)');
  }

  async function unrestrict(value) {
    if (st.busy) return;
    if (!value) { toast('Cole um link magnet ou URL primeiro'); return; }
    st.busy = true;
    goBtn.disabled = true;
    setStatus('⏳ Buscando metadados no swarm…');
    try {
      const r = await api('debrid/unrestrict', null, { method: 'POST', body: JSON.stringify({ magnet: value }) });
      st.task = r.task;
      st.dirty = false;
      setStatus((r.files && r.files.length)
        ? '✓ Torrent adicionado pausado — desmarque o que não quer e clique em Aplicar e baixar'
        : '⏳ Torrent adicionado pausado — buscando metadados… a lista de arquivos aparece aqui', 'ok');
      renderFiles(r.files || [], r, true);
      filesBox.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      refreshActive();
    } catch (e) {
      setStatus('Erro: ' + e.message, 'error');
    } finally {
      st.busy = false;
      goBtn.disabled = false;
    }
  }

  function streamFor(f, local) {
    return local ? f.streamUrl : (f.remoteStreamUrl || f.streamUrl);
  }

  /**
   * File picker: shows the torrent's folder structure with checkboxes, so the user chooses
   * what to download (whole folders, single files, or only the videos) instead of pulling
   * everything by default.
   */
  function renderFiles(files, data, force) {
    if (st.dirty && !force) return; // keep the user's unsaved checkbox edits on the screen
    st.files = files;
    if (!files.length) {
      filesBox.replaceChildren(el('div', { class: 'dim', style: { fontSize: '13px' }, text: st.task ? 'Aguardando metadados do torrent (depende dos seeders)…' : '' }));
      return;
    }
    st.dirty = false;
    st.checked = new Set(files.filter(f => f.selected !== 0).map(f => f.id));

    const entryOf = (f) => ({ path: f.fullPath, name: f.name, dir: false, size: f.size });
    const isVideoName = (n) => /\.(mp4|mkv|avi|mov|webm|flv|m4v|ts|m2ts|mpg|mpeg|wmv|vob|ogv)$/i.test(n || '');
    const videoFiles = files.filter(f => isVideoName(f.name));
    const byId = new Map(files.map(f => [f.id, f]));

    // Build the folder tree from the relative paths ("Season 1/ep1.mkv" -> dir "Season 1").
    const root = { name: '', dirs: new Map(), files: [] };
    for (const f of files) {
      const parts = String(f.path || f.name || '').split('/').filter(Boolean);
      parts.pop();
      let node = root;
      for (const part of parts) {
        if (!node.dirs.has(part)) node.dirs.set(part, { name: part, dirs: new Map(), files: [] });
        node = node.dirs.get(part);
      }
      node.files.push(f);
    }
    const idsUnder = (node) => {
      const out = node.files.map(f => f.id);
      for (const d of node.dirs.values()) out.push(...idsUnder(d));
      return out;
    };
    const sizeUnder = (node) => {
      let total = node.files.reduce((s, f) => s + (Number(f.size) || 0), 0);
      for (const d of node.dirs.values()) total += sizeUnder(d);
      return total;
    };

    const paused = st.task && st.task.status === 'paused';
    const applyBtn = el('button', { class: 'btn primary', onclick: () => applySelection() }, paused ? '▶ Aplicar e baixar' : 'Aplicar seleção');
    const countLabel = el('span', { class: 'dim', style: { fontSize: '12px' } });

    function refreshCount() {
      countLabel.textContent = `${st.checked.size} de ${files.length} arquivo(s) marcados`;
      applyBtn.disabled = st.checked.size === 0 && !st.dirty;
    }

    function toggleSubtree(node, on) {
      for (const id of idsUnder(node)) {
        const f = byId.get(id);
        if (!f) continue;
        if (on) st.checked.add(id); else st.checked.delete(id);
      }
      st.dirty = true;
      redrawTree();
    }

    const treeBox = el('div', { style: { display: 'flex', flexDirection: 'column', gap: '2px' } });

    function fileRow(f, depth) {
      const on = st.checked.has(f.id);
      const pct = Math.max(0, Math.min(100, Number(f.progress) || 0));
      const isVideo = isVideoName(f.name);
      const isDirect = /^https?:/i.test(f.fullPath || '');
      const cb = el('input', {
        type: 'checkbox',
        style: { flex: 'none', marginRight: '8px' },
        onchange: e => {
          if (e.target.checked) st.checked.add(f.id); else st.checked.delete(f.id);
          st.dirty = true;
          refreshCount();
        }
      });
      cb.checked = on;
      return el('div', { style: { display: 'flex', alignItems: 'center', gap: '8px', padding: '7px 8px', paddingLeft: (8 + depth * 18) + 'px', borderRadius: '6px', background: on ? '#eef4ff' : '#f8fafc', border: '1px solid #e2e8f0' } },
        cb,
        el('div', { style: { minWidth: 0, flex: 1 } },
          el('div', { style: { fontSize: '13px', fontWeight: on ? '600' : '400', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }, title: f.path || f.name, text: f.name }),
          el('div', { style: { display: 'flex', alignItems: 'center', gap: '8px', marginTop: '3px' } },
            el('div', { class: 'bar', style: { flex: 1, height: '4px', maxWidth: '220px' } }, el('i', { style: { width: pct.toFixed(1) + '%' } })),
            el('span', { style: { fontSize: '11px', color: 'var(--text-3)' }, text: `${pct.toFixed(0)}% de ${fmtSize(f.size)}` }))),
        el('div', { style: { display: 'flex', gap: '6px', flex: 'none', flexWrap: 'wrap' } },
          (isVideo || isDirect) ? el('button', {
            class: 'btn', style: { padding: '3px 9px', fontSize: '12px' },
            onclick: () => {
              if (isDirect) { window.open(f.streamUrl, '_blank'); return; }
              if (!st.checked.has(f.id)) { st.checked.add(f.id); st.dirty = true; refreshCount(); }
              api('debrid/prioritize', null, { method: 'POST', body: JSON.stringify({ id: st.task && st.task.id, fileIndex: f.id }) }).catch(() => { });
              openApp('player', { entry: entryOf(f), list: videoFiles.map(entryOf) });
            }
          }, '▶ Assistir') : null,
          el('button', { class: 'btn', style: { padding: '3px 9px', fontSize: '12px' }, onclick: () => copyText(streamFor(f, true), 'Link local copiado (VLC)') }, 'Link VLC'),
          f.remoteStreamUrl ? el('button', { class: 'btn', style: { padding: '3px 9px', fontSize: '12px' }, onclick: () => copyText(f.remoteStreamUrl, 'Link público copiado') }, 'Link público') : null));
    }

    function folderRow(node, depth) {
      const ids = idsUnder(node);
      const on = ids.filter(id => st.checked.has(id)).length;
      const all = on === ids.length && ids.length > 0;
      const cb = el('input', { type: 'checkbox', style: { flex: 'none', marginRight: '8px' }, onchange: e => toggleSubtree(node, e.target.checked) });
      cb.checked = all;
      cb.indeterminate = !all && on > 0;
      return el('div', { style: { display: 'flex', alignItems: 'center', gap: '8px', padding: '7px 8px', paddingLeft: (8 + depth * 18) + 'px', borderRadius: '6px', background: '#f1f5f9', border: '1px solid #e2e8f0' } },
        cb,
        el('div', { style: { minWidth: 0, flex: 1, fontSize: '13px', fontWeight: '600', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }, text: '📁 ' + node.name }),
        el('span', { class: 'dim', style: { fontSize: '11px', flex: 'none' }, text: `${ids.length} arq. · ${fmtSize(sizeUnder(node))}` }));
    }

    function renderNode(node, depth, out) {
      for (const d of node.dirs.values()) {
        out.push(folderRow(d, depth));
        renderNode(d, depth + 1, out);
      }
      for (const f of node.files) out.push(fileRow(f, depth));
    }

    function redrawTree() {
      const rows = [];
      renderNode(root, 0, rows);
      treeBox.replaceChildren(...rows);
      refreshCount();
    }

    async function applySelection() {
      if (!st.task) return;
      applyBtn.disabled = true;
      toast('Aplicando seleção…');
      try {
        const r = await api('debrid/select', null, { method: 'POST', body: JSON.stringify({ id: st.task.id, files: [...st.checked], start: true }) });
        st.dirty = false;
        if (r.task) st.task = r.task;
        renderFiles(r.files || [], r, true);
        toast(st.checked.size === files.length
          ? 'Download iniciado (todos os arquivos)'
          : `Download iniciado — ${st.checked.size} de ${files.length} arquivos`);
      } catch (e) {
        toast('Falha ao aplicar seleção: ' + e.message);
      } finally {
        applyBtn.disabled = false;
      }
    }

    const markAll = (fn) => {
      st.checked = new Set(files.filter(fn).map(f => f.id));
      st.dirty = true;
      redrawTree();
    };

    const bar = el('div', { style: { display: 'flex', gap: '6px', flexWrap: 'wrap', alignItems: 'center', marginBottom: '10px' } },
      el('button', { class: 'btn', onclick: () => markAll(() => true) }, 'Marcar tudo'),
      el('button', { class: 'btn', onclick: () => markAll(() => false) }, 'Desmarcar'),
      el('button', { class: 'btn', onclick: () => markAll(f => isVideoName(f.name)) }, 'Só vídeos'),
      applyBtn,
      countLabel);

    filesBox.replaceChildren(
      el('div', { style: { display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: '10px', flexWrap: 'wrap', margin: '0 0 8px' } },
        el('h4', { style: { margin: 0, color: '#1e6fd9' }, text: `📂 Conteúdo do torrent (${files.length} arquivo(s))` }),
        el('span', { class: 'dim', style: { fontSize: '12px' }, text: 'desmarque o que não quer baixar' })),
      bar,
      treeBox);
    redrawTree();
  }

  async function refreshActive() {
    try {
      if (st.task) {
        const det = await api('debrid/files', { id: st.task.id });
        if (det.task) st.task = det.task;
        if (det.files && det.files.length) renderFiles(det.files, det);
      }
      const list = await api('dl/list');
      const torrents = (list.tasks || []).filter(t => t.type === 'torrent');
      if (!torrents.length) { activeBox.replaceChildren(); return; }
      activeBox.replaceChildren(
        el('h4', { style: { margin: '0 0 10px', color: '#1e6fd9' }, text: `📥 Torrents no celular (${torrents.length})` }),
        ...torrents.map(t => {
          const pct = t.total > 0 ? (t.done / t.total * 100) : 0;
          return el('div', { class: 'card', style: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '10px', padding: '10px 14px', marginBottom: '6px', background: '#f8fafc' } },
            el('div', { style: { minWidth: 0, flex: 1 } },
              el('div', { style: { fontWeight: 'bold', fontSize: '13px', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }, text: t.name }),
              el('div', { class: 'dim', style: { fontSize: '11px', marginTop: '2px' }, text: `${fmtSize(t.done)} / ${fmtSize(t.total)} (${pct.toFixed(0)}%) · ▼ ${fmtRate(t.speed)} · ${t.status}` }),
              t.error ? el('div', { style: { fontSize: '11px', marginTop: '2px', color: '#b91c1c' }, text: '⚠ ' + t.error }) : null),
            el('div', { style: { display: 'flex', gap: '6px', flex: 'none' } },
              el('button', { class: 'btn', onclick: () => { st.task = t; st.dirty = false; api('debrid/files', { id: t.id }).then(r => { renderFiles(r.files || [], r, true); filesBox.scrollIntoView({ behavior: 'smooth', block: 'nearest' }); }).catch(() => { }); } }, 'Arquivos'),
              el('button', {
                class: 'btn',
                title: 'Opções do torrent',
                onclick: e => menuAt(e.currentTarget, [
                  t.status === 'paused'
                    ? { label: 'Retomar download', ico: 'play', run: () => torrentAction(t, 'resume') }
                    : { label: 'Pausar download', ico: 'pause', run: () => torrentAction(t, 'pause') },
                  '-',
                  { label: 'Remover torrent (manter arquivos)', ico: 'trash', run: () => removeTorrent(t, false) },
                  { label: 'Remover e apagar arquivos do celular', ico: 'trash', run: () => removeTorrent(t, true) },
                ])
              }, '⋯')));
        })
      );
    } catch { /* offline */ }
  }

  async function torrentAction(t, what) {
    try {
      await api('dl/' + what, { id: t.id });
      toast(what === 'pause' ? 'Download pausado' : 'Download retomado');
      refreshActive();
    } catch (e) { toast('Falha: ' + e.message); }
  }

  async function removeTorrent(t, deleteFiles) {
    const ok = await dialog({
      title: deleteFiles ? 'Remover e apagar arquivos?' : 'Remover torrent?',
      message: deleteFiles
        ? `O torrent e os arquivos baixados serão apagados do celular.

${t.name}`
        : `O torrent sai da lista, mas os arquivos continuam no celular.

${t.name}`,
      okText: 'Remover', danger: true
    });
    if (!ok) return;
    try {
      await api('dl/remove', { id: t.id, files: deleteFiles ? 1 : null });
      if (st.task && st.task.id === t.id) dismissTask();
      toast(deleteFiles ? 'Torrent e arquivos removidos' : 'Torrent removido');
      refreshActive();
    } catch (e) { toast('Falha ao remover: ' + e.message); }
  }

  /** Forgets the torrent currently shown in the file list (after it was removed). */
  function dismissTask() {
    st.task = null;
    st.files = [];
    st.checked = new Set();
    st.dirty = false;
    filesBox.replaceChildren();
    setStatus('');
  }

  refreshActive();
  st.timer = setInterval(() => { if (st.tab === 'stream') refreshActive(); }, 2500);
  switchTab('guide');
  return { destroy() { clearInterval(st.timer); }, handle(arg) { if (arg && arg.magnet) openApp('torrents', { add: arg.magnet }); } };
}

// ---------------------------------------------------------------- auth & boot

const Auth = {
  pinRequired: null,
  mode: 'pin',
  applyMode() {
    const pw = this.mode === 'password';
    $('#pin').classList.toggle('hidden', pw);
    $('#loginUser').classList.toggle('hidden', !pw);
    $('#loginPass').classList.toggle('hidden', !pw);
    $('#loginServer').textContent = pw ? 'Sign in with username and password' : 'Enter the PIN shown on your phone app';
  },
  show() {
    this.applyMode();
    $('#login').classList.remove('hidden');
    setTimeout(() => (this.mode === 'password' ? $('#loginUser') : $('#pin')).focus(), 50);
  },
  async logout() {
    try { await api('logout', null, { method: 'POST' }); } catch { /* ignore */ }
    location.reload();
  },
};

$('#loginForm').addEventListener('submit', async e => {
  e.preventDefault();
  const err = $('#loginErr');
  err.textContent = '';
  const body = Auth.mode === 'password'
    ? { username: $('#loginUser').value, password: $('#loginPass').value }
    : { pin: $('#pin').value };
  try {
    await api('login', null, { method: 'POST', body: JSON.stringify(body) });
    $('#login').classList.add('hidden');
    $('#pin').value = $('#loginPass').value = '';
    start();
  } catch (x) {
    err.textContent = x.status === 429 ? 'Too many failed attempts. Wait 1 minute and try again.'
      : Auth.mode === 'password' ? 'Invalid username or password' : 'Incorrect PIN';
    (Auth.mode === 'password' ? $('#loginPass') : $('#pin')).select();
  }
});

const APPS = {
  filestation: { id: 'filestation', name: 'File Station', ico: 'filestation', w: 980, h: 600, mount: FileStation },
  photos: { id: 'photos', name: 'Photos', ico: 'photos', w: 900, h: 600, mount: MediaApp('image') },
  videos: { id: 'videos', name: 'Video Station', ico: 'videos', w: 900, h: 600, mount: MediaApp('video') },
  music: { id: 'music', name: 'Audio Station', ico: 'music', w: 640, h: 560, mount: MediaApp('audio') },
  monitor: { id: 'monitor', name: 'Usage Monitor', ico: 'monitor', w: 980, h: 720, mount: ResourceMonitor },
  control: { id: 'control', name: 'Control Panel', ico: 'control', w: 620, h: 600, mount: ControlPanel },
  help: { id: 'help', name: 'NexShare Help', ico: 'help', w: 640, h: 560, mount: Help },
  download: { id: 'download', name: 'Download Station', ico: 'download', w: 960, h: 560, mount: DownloadStation },
  torrents: { id: 'torrents', name: 'Torrent Downloader', ico: 'download', w: 980, h: 600, mount: TorrentDownloader },
  torrentplayer: { id: 'torrentplayer', name: 'Torrent Player', ico: 'player', w: 1040, h: 660, mount: TorrentPlayer },
  debrid: { id: 'debrid', name: 'Debrid Hub', ico: 'debrid', w: 900, h: 620, mount: DebridHub },
  player: { id: 'player', name: 'Media Player', ico: 'player', w: 1000, h: 600, mount: MediaPlayer },
  packagecenter: { id: 'packagecenter', name: 'Package Center', ico: 'packagecenter', w: 760, h: 580, mount: PackageCenter },
  terminal: { id: 'terminal', name: 'Terminal', ico: 'terminal', w: 820, h: 540, mount: Terminal, root: true },
  screen: { id: 'screen', name: 'Screen Mirror', ico: 'screen', w: 520, h: 640, mount: ScreenMirror, root: true },
  wallpaper: { id: 'wallpaper', name: 'Wallpaper', ico: 'photos', w: 520, h: 640, mount: Wallpaper },
  remote: { id: 'remote', name: 'Remote Access', ico: 'remote', w: 660, h: 620, mount: RemoteAccess },
};

$('#mainMenuBtn').onclick = e => openPop && openPop.btn === e.currentTarget ? closePop() : appGridPopover(e.currentTarget, Object.keys(APPS).filter(k => !APPS[k].root || State.isRoot), 'Search apps');
$('#searchBtn').onclick = () => openApp('filestation', { search: true });
$('#infoBtn').onclick = () => openApp('control');
$('#userBtn').onclick = e => menuAt(e.currentTarget, [
  { label: Stats.last ? Stats.last.model : 'NexShare', ico: 'info', run: () => openApp('control') },
  Auth.pinRequired ? { label: 'Sign Out', ico: 'logout', run: () => Auth.logout() } : null,
]);
$('#notifBtn').onclick = e => {
  const items = State.notes.length ? State.notes.slice(0, 8).map(n => ({ label: n.text, run: () => {} })) : [{ label: 'No notifications yet', disabled: true, run: () => {} }];
  menuAt(e.currentTarget, items);
};
document.addEventListener('dragover', e => e.preventDefault());
document.addEventListener('drop', e => { if (!e.target.closest('.fs-list')) { e.preventDefault(); toast('Open File Station and drop files into the window to upload'); } });
addEventListener('resize', () => { renderDesktop(); });

let started = false;
async function start() {
  try {
    const s = await api('session');
    if (s && s.auth) {
      State.apiKey = s.apiKey || State.apiKey;
      State.tunnelUrl = s.tunnelUrl || State.tunnelUrl;
      State.webdavEnabled = !!s.webdavEnabled;
      State.authMode = s.authMode || State.authMode;
      State.isRoot = !!s.root;
      State.flavor = s.flavor || State.flavor;
    }
  } catch { /* older build or offline */ }
  try {
    const r = await api('roots');
    State.roots = r.roots;
  } catch (e) { if (e.status === 401) return; }
  renderDesktop();
  Taskbar.render();
  if (started) return;
  started = true;
  initWidgets();
  poll();
  schedulePoll();
  if (State.roots.length && !State.roots[0].readable) notify('Mobile app does not have storage access');
  if (innerWidth > 760) openApp('filestation');
}

let pollTimer = null;
let lastUserAction = Date.now();
['mousemove', 'keydown', 'scroll', 'touchstart'].forEach(ev => {
  window.addEventListener(ev, () => { lastUserAction = Date.now(); }, { passive: true });
});

function schedulePoll() {
  clearTimeout(pollTimer);
  const isBackground = document.hidden;
  const isIdle = (Date.now() - lastUserAction) > 40000;
  const delay = isBackground ? 20000 : (isIdle ? 8000 : 3000);
  pollTimer = setTimeout(async () => {
    await poll();
    schedulePoll();
  }, delay);
}

document.addEventListener('visibilitychange', () => {
  if (!document.hidden) {
    poll();
    schedulePoll();
  }
});

(async function boot() {
  for (const n of document.querySelectorAll('[data-ico]')) n.innerHTML = APP_ICO[n.dataset.ico];
  renderDesktop();
  Taskbar.render();
  try {
    const s = await api('session');
    Auth.pinRequired = s.pinRequired;
    Auth.mode = s.authMode || 'pin';
    State.authMode = Auth.mode;
    State.flavor = s.flavor || 'normal';
    State.isRoot = !!s.root;
    State.webdavEnabled = !!s.webdavEnabled;
    State.apiKey = s.apiKey || '';
    State.tunnelUrl = s.tunnelUrl || '';
    if (!s.auth) { Auth.show(); return; }
    start();
  } catch { toast('Cannot connect to phone'); }
})();
