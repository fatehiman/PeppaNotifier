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
    const resp = await fetch(`${api.base}?action=${action}`, {
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
function compute2DaysUntil() {
  const now = new Date();
  const twoDays = new Date(now.getTime() + 2 * 86400_000);
  const snap = new Date(twoDays);
  snap.setHours(8, 0, 0, 0);
  if (snap.getTime() <= twoDays.getTime()) snap.setDate(snap.getDate() + 1);
  return Math.floor(snap.getTime() / 1000);
}
function isMuted() { return state.mutedUntil > nowSec(); }

/* ---------- view switching ---------- */
function showLogin() {
  $('#view-main').classList.add('hidden');
  $('#view-login').classList.remove('hidden');
  $('#login-username').value = '';
  $('#login-password').value = '';
  $('#login-error').textContent = '';
}
function showMain() {
  $('#view-login').classList.add('hidden');
  $('#view-main').classList.remove('hidden');
  $('#me-label').textContent = me();
  if (localStorage.getItem('soundEnabled') !== '1') {
    $('#enable-sound-banner').classList.remove('hidden');
  }
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
    // Icon — sender side uses ticks (Telegram-style), receiver side uses the ring.
    let icon;
    if (m._pending)      icon = '⏳';
    else if (m._failed)  icon = '⚠';
    else if (isMine) {
      // Sent. notified_at set means recipient's client has received it
      // (notification fired, missed-window alert, or silently-during-mute).
      icon = m.notified_at ? '✓✓' : '✓';
    } else {
      icon = m.notified_state === 'notified' ? '🔔'
           : m.notified_state === 'missed'    ? '🔕'
           : '⋯';
    }
    if (isMine && !m._pending && !m._failed) row.classList.add(m.notified_at ? 'tick-double' : 'tick-single');

    // Timing line.
    //   Sender:   opened HH:MM   if recipient has opened the app
    //             delivered HH:MM   if recipient's client has the msg but not opened
    //   Receiver: received HH:MM    when my own client got the message
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
  // ack opened for received messages not yet opened
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
    // mute state
    const newMutedUntil = r.my_muted_until || 0;
    if (newMutedUntil !== state.mutedUntil) {
      state.mutedUntil = newMutedUntil;
      renderMuteButton();
    }
    state.userMutes = r.mutes || {};
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
    // fetch fresh state for this user
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

  // For non-ALL we render exactly one optimistic row. For ALL we don't yet know
  // the recipient list, so render one placeholder row with recipient="ALL";
  // when /send returns we'll replace it with N real per-recipient rows.
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
    // remove the optimistic placeholder
    state.messages.delete(tmp.id);
    // adopt the real records from the server response (per-recipient for ALL)
    if (r && Array.isArray(r.messages)) {
      for (const m of r.messages) state.messages.set(m.id, m);
    }
    renderHistory();
    // an immediate poll picks up any notify/open state that landed in the meantime
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

/* ---------- exit / resume ---------- */
function exitApp() {
  state.exited = true;
  stopPolling();
  stopMuteTicker();
  $('#view-main').classList.add('hidden');
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
  try { if (!silent) await api.logout(); } catch (_) {}
  localStorage.removeItem('token');
  localStorage.removeItem('user');
  $('#view-exited').classList.add('hidden');
  showLogin();
}

/* ---------- sound activation ---------- */
async function enableSound() {
  // unlock audio with the user gesture
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
  showMain();
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
  $('#btn-exit').addEventListener('click', exitApp);
  $('#btn-resume').addEventListener('click', resumeApp);
  document.querySelectorAll('.mute-opt').forEach(el => {
    el.addEventListener('click', () => {
      const secs = parseInt(el.getAttribute('data-mute-seconds') || '0', 10);
      const two = el.getAttribute('data-mute-2days') === '1';
      chooseMute(secs, two);
    });
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
