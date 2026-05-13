'use strict';

const POLL_INTERVAL_MS = 15000;
const BODY_TRUNCATE = 80;

const api = {
  base: 'api.php',
  async call(action, opts = {}) {
    const headers = {};
    if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
    const tok = localStorage.getItem('token');
    if (tok) headers.Authorization = 'Bearer ' + tok;
    let url = `${api.base}?action=${action}`;
    if (opts.query) {
      for (const [k, v] of Object.entries(opts.query)) {
        url += '&' + encodeURIComponent(k) + '=' + encodeURIComponent(v);
      }
    }
    const resp = await fetch(url, {
      method: opts.method || 'GET',
      headers,
      body: opts.body ? JSON.stringify(opts.body) : null,
    });
    if (resp.status === 204) return null;
    let data = null;
    try { data = await resp.json(); } catch (_) { data = {}; }
    if (!resp.ok) {
      const err = new Error((data && data.error) || ('http ' + resp.status));
      err.status = resp.status;
      throw err;
    }
    return data;
  },
  login: (u, p) => api.call('login', { method: 'POST', body: { username: u, password: p } }),
  logout: () => api.call('logout', { method: 'POST' }),
  poll: () => api.call('poll'),
  history: () => api.call('history'),
  users: () => api.call('users'),
  send: (recipient, text) => api.call('send', { method: 'POST', body: { recipient, text } }),
  ackOpened: (ids) => api.call('ack_opened', { method: 'POST', body: { ids } }),
  mute: (until) => api.call('mute', { method: 'POST', body: { until } }),
  tsState: () => api.call('ts_state'),
  tsToggle: () => api.call('ts_toggle', { method: 'POST' }),
  tsMonths: () => api.call('ts_months'),
  tsMonth: (y, m) => api.call('ts_month', { query: { year: y, month: m } }),
  stateGet: () => api.call('state_get'),
  stateToggle: () => api.call('state_toggle', { method: 'POST' }),
};

const state = {
  messages: new Map(),   // id -> record
  pollTimer: null,
  muteTicker: null,
  modalRecipient: null,  // recipient currently shown in send modal
  bootstrapped: false,
  mutedUntil: 0,         // my own mute expiry (unix sec, 0 = not muted)
  userMutes: {},         // {username: expiry_ts} for other users
  exited: false,
  sending: false,        // a /send HTTP request is currently in flight
  workState: 'stopped',  // 'started' | 'stopped'
  workToggling: false,   // POST ts_toggle in flight
  todayDate: '',         // server's current YYYY-MM-DD
  tsAvailableMonths: [], // [{year, month}, ...]
  tsYear: 0,
  tsMonth: 0,
  tsRowLogs: new Map(), // key="user|YYYY-MM-DD" → [entries] for the (i) modal
  stateOn: 0,            // 0 | 1 — for the amir-only ON/OFF menu
  stateToggling: false,  // POST state_toggle in flight
};

function $(sel) { return document.querySelector(sel); }
function pad(n) { return String(n).padStart(2, '0'); }
function fmtTs(unix) {
  if (!unix) return '';
  const d = new Date(unix * 1000);
  return `${d.getFullYear()}/${pad(d.getMonth()+1)}/${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
function truncate(s, n) { return s.length > n ? s.slice(0, n - 1) + '…' : s; }
function me() { return localStorage.getItem('user'); }
function escapeHtml(s) { return s.replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
function nowSec() { return Math.floor(Date.now() / 1000); }
function fmtRemaining(secsLeft) {
  if (secsLeft <= 0) return '';
  const totalMin = Math.floor(secsLeft / 60);
  const h = Math.floor(totalMin / 60);
  const m = totalMin % 60;
  return String(h).padStart(2, '0') + ':' + String(m).padStart(2, '0');
}
function secsToHhmm(secs) {
  if (!secs || secs <= 0) return '00:00';
  const totalMin = Math.floor(secs / 60);
  const h = Math.floor(totalMin / 60);
  const m = totalMin % 60;
  return String(h).padStart(2, '0') + ':' + String(m).padStart(2, '0');
}
function compute2DaysUntil() {
  const now = new Date();
  const twoDays = new Date(now.getTime() + 2 * 86400_000);
  const snap = new Date(twoDays);
  snap.setHours(8, 0, 0, 0);
  if (snap.getTime() <= twoDays.getTime()) snap.setDate(snap.getDate() + 1);
  return Math.floor(snap.getTime() / 1000);
}
function isMuted() { return state.mutedUntil > nowSec(); }

/* ---------- toast ---------- */
let _toastTimer = null;
let _toastHideTimer = null;
function showToast(text, kind) {
  const el = document.getElementById('toast');
  if (!el) return;
  el.textContent = text;
  el.className = 'toast' + (kind ? ' ' + kind : '');
  el.classList.remove('hidden');
  // force reflow so the transition replays even if .show was already on
  void el.offsetWidth;
  el.classList.add('show');
  if (_toastTimer) clearTimeout(_toastTimer);
  if (_toastHideTimer) clearTimeout(_toastHideTimer);
  _toastTimer = setTimeout(() => {
    el.classList.remove('show');
    _toastHideTimer = setTimeout(() => el.classList.add('hidden'), 300);
  }, 2200);
}

/* ---------- routing ---------- */
function currentRoute() {
  return location.hash === '#timesheet' ? 'timesheet' : 'home';
}
function applyRoute() {
  if (!localStorage.getItem('token')) return;
  if (state.exited) return;
  if (currentRoute() === 'timesheet') showTimesheet();
  else showChat();
}
function setActiveNav(name) {
  document.getElementById('nav-home').classList.toggle('active', name === 'home');
  document.getElementById('nav-timesheet').classList.toggle('active', name === 'timesheet');
}

/* ---------- view switching ---------- */
function showLogin() {
  $('#topbar').classList.add('hidden');
  $('#view-main').classList.add('hidden');
  $('#view-timesheet').classList.add('hidden');
  $('#view-exited').classList.add('hidden');
  $('#view-login').classList.remove('hidden');
  $('#login-username').value = '';
  $('#login-password').value = '';
  $('#login-error').textContent = '';
}
function showChat() {
  $('#view-login').classList.add('hidden');
  $('#view-timesheet').classList.add('hidden');
  $('#view-exited').classList.add('hidden');
  $('#topbar').classList.remove('hidden');
  $('#view-main').classList.remove('hidden');
  $('#me-label').textContent = me();
  setActiveNav('home');
  if (localStorage.getItem('soundEnabled') !== '1') {
    $('#enable-sound-banner').classList.remove('hidden');
  }
}
function showTimesheet() {
  $('#view-login').classList.add('hidden');
  $('#view-main').classList.add('hidden');
  $('#view-exited').classList.add('hidden');
  $('#topbar').classList.remove('hidden');
  $('#view-timesheet').classList.remove('hidden');
  $('#me-label').textContent = me();
  setActiveNav('timesheet');
  loadTimesheetMonths().then(renderTimesheet).catch(() => {});
}

/* ---------- history ---------- */
function renderHistory() {
  const list = $('#history-list');
  list.innerHTML = '';
  const sorted = [...state.messages.values()].sort((a, b) => b.sent_at - a.sent_at);
  const meName = me();
  for (const m of sorted) {
    const row = document.createElement('div');
    row.className = 'history-row';
    if (m._pending) row.classList.add('pending');
    if (m._failed)  row.classList.add('failed');

    const isMine = m.sender === meName;
    let icon;
    if (m._pending)      icon = '⏳';
    else if (m._failed)  icon = '⚠';
    else if (isMine) {
      icon = m.notified_at ? '✓✓' : '✓';
    } else {
      icon = m.notified_state === 'notified' ? '🔔'
           : m.notified_state === 'missed'    ? '🔕'
           : '⋯';
    }
    if (isMine && !m._pending && !m._failed) row.classList.add(m.notified_at ? 'tick-double' : 'tick-single');

    let timingFrag = '';
    if (!m._pending && !m._failed) {
      if (isMine) {
        if (m.opened_at) {
          timingFrag = `<span class="opened">opened ${fmtTs(m.opened_at)}</span>`;
        } else if (m.notified_at) {
          timingFrag = `<span class="delivered">delivered ${fmtTs(m.notified_at)}</span>`;
        }
      } else if (m.notified_at) {
        timingFrag = `<span class="received">received ${fmtTs(m.notified_at)}</span>`;
      }
    }

    const otherParty = isMine ? m.recipient : m.sender;
    const whoLabel = isMine ? `me → ${m.recipient}` : m.sender;
    row.innerHTML =
      `<span class="ts">${fmtTs(m.sent_at)}</span>` +
      `<span>&gt;</span>` +
      `<span class="who">${escapeHtml(whoLabel)}:</span>` +
      `<span class="text">${escapeHtml(m.text)}</span>` +
      `<span class="icon">${icon}</span>` +
      timingFrag;
    row.addEventListener('click', () => {
      if (m._pending || m._failed) return;
      if (otherParty && otherParty !== meName) openSendModal(otherParty);
    });
    list.appendChild(row);
  }
  const toAck = sorted.filter(m => m.recipient === meName && !m.opened_at).map(m => m.id);
  if (toAck.length) {
    api.ackOpened(toAck).then(() => {
      const now = Math.floor(Date.now() / 1000);
      for (const id of toAck) {
        const m = state.messages.get(id);
        if (m && !m.opened_at) m.opened_at = now;
      }
    }).catch(() => {});
  }
}

/* ---------- notifications ---------- */
function playSound() {
  if (localStorage.getItem('soundEnabled') !== '1') return;
  const a = $('#notify-audio');
  try { a.currentTime = 0; a.play().catch(() => {}); } catch (_) {}
}
function fireOsNotification(m) {
  if (!('Notification' in window)) return;
  if (Notification.permission !== 'granted') return;
  try {
    const n = new Notification(m.sender, {
      body: truncate(m.text, BODY_TRUNCATE),
      tag: m.id,
      silent: true,
    });
    n.onclick = () => { window.focus(); n.close(); };
  } catch (_) {}
}
function notifyNew(messages) {
  if (!messages.length) return;
  if (isMuted()) return;
  playSound();
  for (const m of messages) fireOsNotification(m);
}

/* ---------- mute UI ---------- */
function renderMuteButton() {
  const btn = document.getElementById('btn-mute');
  const remaining = document.getElementById('mute-remaining');
  if (!btn || !remaining) return;
  if (isMuted()) {
    btn.classList.add('is-muted');
    remaining.textContent = fmtRemaining(state.mutedUntil - nowSec());
  } else {
    btn.classList.remove('is-muted');
    remaining.textContent = '';
    state.mutedUntil = 0;
  }
}
function startMuteTicker() {
  stopMuteTicker();
  state.muteTicker = setInterval(renderMuteButton, 30000);
}
function stopMuteTicker() {
  if (state.muteTicker) { clearInterval(state.muteTicker); state.muteTicker = null; }
}
function openMuteModal() {
  document.getElementById('btn-unmute').disabled = !isMuted();
  document.getElementById('modal-mute').classList.remove('hidden');
}
async function chooseMute(seconds, twoDays) {
  let until;
  if (twoDays) until = compute2DaysUntil();
  else if (seconds === 0) until = 0;
  else until = nowSec() + seconds;
  try {
    const r = await api.mute(until);
    state.mutedUntil = r.muted_until || 0;
    renderMuteButton();
    closeAllModals();
  } catch (e) {
    alert('Mute failed: ' + e.message);
  }
}

/* ---------- on/off state (amir only) ---------- */
function isAdminUser() { return me() === 'amir'; }

function renderStateButton() {
  const btn = document.getElementById('nav-state');
  if (!btn) return;
  if (!isAdminUser()) {
    btn.classList.add('hidden');
    return;
  }
  btn.classList.remove('hidden');
  btn.disabled = state.stateToggling;
  const on = state.stateOn === 1;
  btn.classList.toggle('is-on', on);
  const label = btn.querySelector('.switch-label');
  if (label) label.textContent = state.stateToggling ? '...' : (on ? 'ON' : 'OFF');
}

async function loadStateOnce() {
  if (!isAdminUser()) return;
  try {
    const r = await api.stateGet();
    state.stateOn = (r && r.on === 1) ? 1 : 0;
  } catch (_) { state.stateOn = 0; }
  renderStateButton();
}

async function onToggleState() {
  if (!isAdminUser()) return;
  if (state.stateToggling) return;
  state.stateToggling = true;
  renderStateButton();
  try {
    const r = await api.stateToggle();
    state.stateOn = (r && r.on === 1) ? 1 : 0;
  } catch (e) {
    alert('Toggle failed: ' + e.message);
  } finally {
    state.stateToggling = false;
    renderStateButton();
  }
}

/* ---------- work toggle ---------- */
function renderWorkButton() {
  const btn = document.getElementById('nav-work-toggle');
  if (!btn) return;
  btn.disabled = state.workToggling;
  const working = state.workState === 'started';
  btn.classList.toggle('is-working', working);
  const iconEl = btn.querySelector('.work-icon');
  const labelEl = btn.querySelector('.work-label');
  if (!iconEl || !labelEl) return;
  if (state.workToggling) {
    iconEl.textContent = '⏳';
    labelEl.textContent = '...';
  } else if (working) {
    // Currently working → next action is stop. Both icon and label say "stop".
    iconEl.textContent = '⏹';
    labelEl.textContent = 'Stop';
  } else {
    // Currently idle → next action is start.
    iconEl.textContent = '▶';
    labelEl.textContent = 'Start';
  }
}
async function onToggleWork() {
  if (state.workToggling) return;
  state.workToggling = true;
  renderWorkButton();
  try {
    const r = await api.tsToggle();
    state.workState = r.state || 'stopped';
    state.todayDate = r.today_date || state.todayDate;
    showToast(state.workState === 'started' ? 'Started!' : 'Stopped!', 'success');
    if (currentRoute() === 'timesheet') renderTimesheet();
  } catch (e) {
    showToast('Toggle failed: ' + e.message, 'warn');
  } finally {
    state.workToggling = false;
    renderWorkButton();
  }
}

/* ---------- polling ---------- */
async function pollOnce() {
  try {
    const r = await api.poll();
    let dirty = false;
    const fresh = [];
    for (const m of r.new_messages || []) {
      state.messages.set(m.id, m);
      fresh.push(m);
      dirty = true;
    }
    for (const m of r.sent_updates || []) {
      state.messages.set(m.id, m);
      dirty = true;
    }
    const newMutedUntil = r.my_muted_until || 0;
    if (newMutedUntil !== state.mutedUntil) {
      state.mutedUntil = newMutedUntil;
      renderMuteButton();
    }
    state.userMutes = r.mutes || {};

    // Work toggle reconciliation (handles midnight rollover for free).
    const newWork = r.ts_state || 'stopped';
    const newToday = r.today_date || '';
    if (newWork !== state.workState || newToday !== state.todayDate) {
      state.workState = newWork;
      state.todayDate = newToday;
      if (!state.workToggling) renderWorkButton();
    }

    if (dirty) renderHistory();
    if (fresh.length && state.bootstrapped) notifyNew(fresh);
  } catch (e) {
    if (e.status === 401) { onLogout(true); }
  }
}
function startPolling() {
  stopPolling();
  state.pollTimer = setInterval(pollOnce, POLL_INTERVAL_MS);
}
function stopPolling() {
  if (state.pollTimer) { clearInterval(state.pollTimer); state.pollTimer = null; }
}

/* ---------- users modal ---------- */
async function openUsersModal() {
  const modal = $('#modal-users');
  const list = $('#users-list');
  list.innerHTML = '<div class="user-row">Loading...</div>';
  modal.classList.remove('hidden');
  let users;
  try { users = await api.users(); }
  catch (e) { list.innerHTML = '<div class="user-row">Failed to load.</div>'; return; }
  list.innerHTML = '';
  const now = nowSec();
  for (const u of users) {
    const row = document.createElement('div');
    row.className = 'user-row' + (u.username === 'ALL' ? ' all' : '');
    const dot = `<span class="status-dot ${u.online ? 'online' : ''}"></span>`;
    const lastSeen = (!u.online && u.last_seen) ? `<span class="last-seen">last seen ${fmtTs(u.last_seen)}</span>` : '';
    const name = `<span class="name">${escapeHtml(u.username)}</span>`;
    let zzz = '';
    const mu = u.muted_until || 0;
    if (mu > now) {
      const rem = fmtRemaining(mu - now);
      zzz = `<span class="zzz" title="muted until ${fmtTs(mu)} (${rem} remaining)">Zzz ${rem}</span>`;
    }
    const ping = u.username === 'ALL' ? '' : `<button class="ping-btn" data-ping>📍 PING</button>`;
    row.innerHTML = dot + name + zzz + lastSeen + ping;
    row.addEventListener('click', (ev) => {
      if (ev.target.closest('[data-ping]')) {
        closeAllModals();
        doSend(u.username, 'ping!');
        return;
      }
      closeAllModals();
      openSendModal(u.username, u);
    });
    list.appendChild(row);
  }
}

/* ---------- send modal ---------- */
async function openSendModal(recipient, userInfo) {
  state.modalRecipient = recipient;
  const title = $('#send-title');
  const dot = title.querySelector('.status-dot');
  const nameEl = title.querySelector('.recipient-name');
  const lsEl = title.querySelector('.last-seen');
  nameEl.textContent = recipient;
  dot.classList.toggle('online', recipient === 'ALL' || (userInfo && userInfo.online));
  if (recipient === 'ALL') {
    lsEl.textContent = '';
  } else if (userInfo && userInfo.online) {
    lsEl.textContent = '';
  } else if (userInfo) {
    lsEl.textContent = userInfo.last_seen ? `(last seen ${fmtTs(userInfo.last_seen)})` : '(never seen)';
  } else {
    lsEl.textContent = '';
    try {
      const list = await api.users();
      const u = list.find(x => x.username === recipient);
      if (u) {
        dot.classList.toggle('online', !!u.online);
        if (!u.online) lsEl.textContent = u.last_seen ? `(last seen ${fmtTs(u.last_seen)})` : '(never seen)';
      }
    } catch (_) {}
  }
  $('#send-text').value = '';
  $('#modal-send').classList.remove('hidden');
  setTimeout(() => $('#send-text').focus(), 50);
}
function closeAllModals() {
  $('#modal-users').classList.add('hidden');
  $('#modal-send').classList.add('hidden');
  $('#modal-mute').classList.add('hidden');
  $('#modal-log').classList.add('hidden');
}

function openLogModal(label, entries) {
  $('#log-title').textContent = label;
  const tbody = $('#modal-log tbody');
  tbody.innerHTML = '';
  for (const e of entries) {
    const tr = document.createElement('tr');
    tr.className = 'kind-' + (e.kind || '');
    tr.innerHTML =
      `<td class="log-kind">${escapeHtml(e.kind || '')}</td>` +
      `<td class="log-time">${escapeHtml(e.time || '—')}</td>`;
    tbody.appendChild(tr);
  }
  $('#modal-log').classList.remove('hidden');
}

/* ---------- send / ping (optimistic) ---------- */
function tempId() { return 'tmp-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8); }

async function doSend(recipient, text) {
  if (state.sending) return;
  if (!recipient || !text) return;
  state.sending = true;

  const meName = me();
  const sentAt = nowSec();
  const tmpGroup = 'tmp-' + tempId();

  const tmp = {
    id: tempId(),
    group_id: tmpGroup,
    sender: meName,
    recipient: recipient,
    text: text,
    sent_at: sentAt,
    notified_at: null,
    notified_state: null,
    opened_at: null,
    _pending: true,
  };
  state.messages.set(tmp.id, tmp);
  renderHistory();

  try {
    const r = await api.send(recipient, text);
    state.messages.delete(tmp.id);
    if (r && Array.isArray(r.messages)) {
      for (const m of r.messages) state.messages.set(m.id, m);
    }
    renderHistory();
    pollOnce();
  } catch (e) {
    const t = state.messages.get(tmp.id);
    if (t) { t._pending = false; t._failed = true; }
    renderHistory();
    alert('Send failed: ' + e.message);
  } finally {
    state.sending = false;
  }
}

/* ---------- timesheet ---------- */
const WEEKDAY_SHORT = ['Sun','Mon','Tue','Wed','Thu','Fri','Sat'];

async function loadTimesheetMonths() {
  let months = [];
  try { months = await api.tsMonths(); }
  catch (_) { months = []; }
  if (!Array.isArray(months) || months.length === 0) {
    const d = new Date();
    months = [{ year: d.getFullYear(), month: d.getMonth() + 1 }];
  }
  state.tsAvailableMonths = months;

  // Pick the most recent month as the default selection.
  if (!state.tsYear || !state.tsMonth ||
      !months.some(x => x.year === state.tsYear && x.month === state.tsMonth)) {
    state.tsYear = months[0].year;
    state.tsMonth = months[0].month;
  }

  const yearSel = $('#ts-year');
  yearSel.innerHTML = '';
  const years = [...new Set(months.map(m => m.year))].sort((a, b) => b - a);
  for (const y of years) {
    const opt = document.createElement('option');
    opt.value = String(y); opt.textContent = String(y);
    yearSel.appendChild(opt);
  }
  yearSel.value = String(state.tsYear);

  populateMonthOptions();
}

function populateMonthOptions() {
  const monthSel = $('#ts-month');
  monthSel.innerHTML = '';
  const names = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
  const monthsForYear = state.tsAvailableMonths
    .filter(m => m.year === state.tsYear)
    .map(m => m.month);
  const sorted = [...new Set(monthsForYear)].sort((a, b) => b - a);
  for (const m of sorted) {
    const opt = document.createElement('option');
    opt.value = String(m); opt.textContent = names[m - 1];
    monthSel.appendChild(opt);
  }
  if (!sorted.includes(state.tsMonth)) {
    state.tsMonth = sorted[0] || state.tsMonth;
  }
  monthSel.value = String(state.tsMonth);
}

function computeDayStats(entries) {
  // entries: same-day entries for one user, sorted by ts ascending.
  let firstStart = null;
  let lastStop = null;
  let gapSecs = 0;
  let workSecs = 0;
  let inSession = false;
  let sessionStartTs = null;
  let lastStopTs = null;

  for (const e of entries) {
    if (e.kind === 'start') {
      if (!firstStart) firstStart = e;
      if (!inSession) {
        if (lastStopTs !== null) gapSecs += (e.ts - lastStopTs);
        sessionStartTs = e.ts;
        inSession = true;
      }
      // double-start: ignore, keep first start time
    } else if (e.kind === 'stop') {
      if (inSession) {
        workSecs += (e.ts - sessionStartTs);
        lastStopTs = e.ts;
        lastStop = e;
        inSession = false;
      }
      // orphan stop (no matching start): ignore
    }
  }

  const open = inSession;
  return {
    startStr: firstStart ? firstStart.time : '—',
    stopStr:  open ? '—' : (lastStop ? lastStop.time : '—'),
    gapsStr:  secsToHhmm(gapSecs),
    totalStr: open ? '—' : secsToHhmm(workSecs),
    totalSecs: open ? 0 : workSecs,
  };
}

async function renderTimesheet() {
  if (!state.tsYear || !state.tsMonth) return;
  const status = $('#ts-status');
  status.textContent = 'Loading…';

  let data;
  try {
    data = await api.tsMonth(state.tsYear, state.tsMonth);
  } catch (e) {
    status.textContent = 'Failed to load: ' + e.message;
    return;
  }
  status.textContent = '';

  const year = data.year;
  const month = data.month;
  const entries = data.entries || {};

  const tbody = $('#ts-table tbody');
  const summaryTbody = $('#ts-summary tbody');
  tbody.innerHTML = '';
  summaryTbody.innerHTML = '';
  state.tsRowLogs.clear();

  const daysInMonth = new Date(year, month, 0).getDate();
  const userTotals = {};

  for (let d = 1; d <= daysInMonth; d++) {
    const dateObj = new Date(year, month - 1, d);
    const weekdayIdx = dateObj.getDay();
    const weekdayShort = WEEKDAY_SHORT[weekdayIdx];
    const isWeekend = weekdayIdx === 0 || weekdayIdx === 6; // Sat=6, Sun=0
    const dateStr = `${year}-${pad(month)}-${pad(d)}`;

    const usersOnDay = [];
    for (const [user, list] of Object.entries(entries)) {
      const dayEntries = list
        .filter(e => e && e.date === dateStr)
        .sort((a, b) => (a.ts || 0) - (b.ts || 0));
      if (dayEntries.length) usersOnDay.push({ user, dayEntries });
    }
    usersOnDay.sort((a, b) => a.user.localeCompare(b.user));

    if (usersOnDay.length === 0) {
      const tr = document.createElement('tr');
      tr.className = 'empty' + (isWeekend ? ' weekend' : '');
      tr.innerHTML =
        `<td>${d}</td><td>${weekdayShort}</td>` +
        `<td>—</td><td>—</td><td>—</td><td>—</td><td>—</td>`;
      tbody.appendChild(tr);
    } else {
      for (const { user, dayEntries } of usersOnDay) {
        const s = computeDayStats(dayEntries);
        userTotals[user] = (userTotals[user] || 0) + s.totalSecs;
        const logKey = `${user}|${dateStr}`;
        state.tsRowLogs.set(logKey, dayEntries);
        const tr = document.createElement('tr');
        if (isWeekend) tr.classList.add('weekend');
        const logBtn =
          `<button type="button" class="log-btn" data-log-key="${escapeHtml(logKey)}" ` +
          `title="Show ${escapeHtml(user)}'s log for ${dateStr}" aria-label="Show log">ⓘ</button>`;
        tr.innerHTML =
          `<td>${d}</td><td>${weekdayShort}</td>` +
          `<td>${escapeHtml(user)}</td>` +
          `<td>${s.startStr}</td><td>${s.stopStr}</td>` +
          `<td>${s.gapsStr}</td>` +
          `<td class="total">${s.totalStr}${logBtn}</td>`;
        tbody.appendChild(tr);
      }
    }
  }

  const summaryUsers = Object.keys(userTotals).sort();
  if (summaryUsers.length === 0) {
    const tr = document.createElement('tr');
    tr.innerHTML = '<td colspan="2" style="color:#9ca3af">No entries for this month.</td>';
    summaryTbody.appendChild(tr);
  } else {
    for (const u of summaryUsers) {
      const tr = document.createElement('tr');
      tr.innerHTML = `<td>${escapeHtml(u)}</td><td>${secsToHhmm(userTotals[u])}</td>`;
      summaryTbody.appendChild(tr);
    }
  }
}

/* ---------- exit / resume ---------- */
function exitApp() {
  state.exited = true;
  stopPolling();
  stopMuteTicker();
  $('#topbar').classList.add('hidden');
  $('#view-main').classList.add('hidden');
  $('#view-timesheet').classList.add('hidden');
  $('#view-login').classList.add('hidden');
  $('#view-exited').classList.remove('hidden');
}
function resumeApp() {
  state.exited = false;
  $('#view-exited').classList.add('hidden');
  if (localStorage.getItem('token') && localStorage.getItem('user')) bootstrap();
  else showLogin();
}

/* ---------- login / logout ---------- */
async function onLogin(ev) {
  ev.preventDefault();
  const u = $('#login-username').value.trim();
  const p = $('#login-password').value;
  $('#login-error').textContent = '';
  try {
    const r = await api.login(u, p);
    localStorage.setItem('token', r.token);
    localStorage.setItem('user', r.user);
    await bootstrap();
  } catch (e) {
    $('#login-error').textContent = e.status === 401 ? 'Invalid credentials' : ('Error: ' + e.message);
  }
}
async function onLogout(silent) {
  stopPolling();
  stopMuteTicker();
  state.messages.clear();
  state.mutedUntil = 0;
  state.bootstrapped = false;
  state.workState = 'stopped';
  state.workToggling = false;
  try { if (!silent) await api.logout(); } catch (_) {}
  localStorage.removeItem('token');
  localStorage.removeItem('user');
  $('#view-exited').classList.add('hidden');
  showLogin();
}

/* ---------- sound activation ---------- */
async function enableSound() {
  const a = $('#notify-audio');
  try { a.muted = true; await a.play(); a.pause(); a.currentTime = 0; a.muted = false; } catch (_) {}
  if ('Notification' in window && Notification.permission === 'default') {
    try { await Notification.requestPermission(); } catch (_) {}
  }
  localStorage.setItem('soundEnabled', '1');
  $('#enable-sound-banner').classList.add('hidden');
}

/* ---------- bootstrap ---------- */
async function bootstrap() {
  applyRoute();
  renderWorkButton();
  renderStateButton();
  loadStateOnce();
  try {
    const hist = await api.history();
    state.messages.clear();
    for (const m of hist) state.messages.set(m.id, m);
    renderHistory();
  } catch (e) {
    if (e.status === 401) { onLogout(true); return; }
  }
  startPolling();
  startMuteTicker();
  renderMuteButton();
  await pollOnce();
  state.bootstrapped = true;
}

/* ---------- wire up ---------- */
function wire() {
  $('#login-form').addEventListener('submit', onLogin);
  $('#btn-logout').addEventListener('click', () => onLogout(false));
  $('#btn-send').addEventListener('click', openUsersModal);
  $('#btn-enable-sound').addEventListener('click', enableSound);
  document.addEventListener('click', (ev) => {
    if (ev.target.matches('[data-close]') || ev.target.classList.contains('modal')) {
      closeAllModals();
    }
  });
  $('#btn-send-msg').addEventListener('click', () => {
    const text = $('#send-text').value.trim();
    if (!text || !state.modalRecipient) return;
    const recipient = state.modalRecipient;
    closeAllModals();
    doSend(recipient, text);
  });
  $('#btn-ping').addEventListener('click', () => {
    if (!state.modalRecipient) return;
    const recipient = state.modalRecipient;
    closeAllModals();
    doSend(recipient, 'ping!');
  });
  $('#btn-mute').addEventListener('click', openMuteModal);
  $('#btn-resume').addEventListener('click', resumeApp);
  $('#me-logout').addEventListener('click', (ev) => {
    ev.preventDefault();
    onLogout(false);
  });
  document.querySelectorAll('.mute-opt').forEach(el => {
    el.addEventListener('click', () => {
      const secs = parseInt(el.getAttribute('data-mute-seconds') || '0', 10);
      const two = el.getAttribute('data-mute-2days') === '1';
      chooseMute(secs, two);
    });
  });

  $('#nav-home').addEventListener('click', () => {
    if (location.hash) location.hash = '';
    else applyRoute();
  });
  $('#nav-timesheet').addEventListener('click', () => {
    if (location.hash !== '#timesheet') location.hash = '#timesheet';
    else applyRoute();
  });
  $('#nav-work-toggle').addEventListener('click', onToggleWork);
  $('#nav-state').addEventListener('click', onToggleState);

  window.addEventListener('hashchange', applyRoute);

  $('#ts-table').addEventListener('click', (ev) => {
    const btn = ev.target.closest('.log-btn');
    if (!btn) return;
    const key = btn.getAttribute('data-log-key');
    const entries = state.tsRowLogs.get(key);
    if (!entries) return;
    const [user, dateStr] = key.split('|');
    openLogModal(`${user} — ${dateStr}`, entries);
  });

  $('#ts-year').addEventListener('change', (ev) => {
    state.tsYear = parseInt(ev.target.value, 10);
    populateMonthOptions();
    renderTimesheet();
  });
  $('#ts-month').addEventListener('change', (ev) => {
    state.tsMonth = parseInt(ev.target.value, 10);
    renderTimesheet();
  });
}

document.addEventListener('DOMContentLoaded', () => {
  wire();
  if (localStorage.getItem('token') && localStorage.getItem('user')) {
    bootstrap();
  } else {
    showLogin();
  }
});
