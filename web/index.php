<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>PeppaNotifier</title>
  <link rel="stylesheet" href="assets/style.css?v=13">
</head>
<body>

<header class="topbar hidden" id="topbar">
  <span class="brand">PeppaNotifier</span>
  <nav class="topnav">
    <button type="button" id="nav-home" class="nav-btn">Home</button>
    <button type="button" id="nav-work-toggle" class="nav-btn nav-work">
      <span class="work-icon" aria-hidden="true">▶</span>
      <span class="work-label">Start</span>
    </button>
    <button type="button" id="nav-timesheet" class="nav-btn">Timesheet</button>
    <button type="button" id="nav-state" class="nav-switch hidden" aria-label="Toggle state">
      <span class="switch-track"><span class="switch-thumb"></span></span>
      <span class="switch-label">OFF</span>
    </button>
  </nav>
  <a href="#" id="me-logout" class="me-logout" title="Click to logout">
    <span class="power-icon" aria-hidden="true">⏻</span>
    <span class="me" id="me-label"></span>
  </a>
</header>

<div id="view-login" class="view">
  <form id="login-form" class="login-box">
    <h1>PeppaNotifier</h1>
    <label>Username <input id="login-username" autocomplete="username" required></label>
    <label>Password <input id="login-password" type="password" autocomplete="current-password" required></label>
    <button type="submit">Login</button>
    <div id="login-error" class="error"></div>
  </form>
</div>

<div id="view-main" class="view hidden">
  <div id="enable-sound-banner" class="banner hidden">
    <span>Click to enable notifications and sound for this device.</span>
    <button id="btn-enable-sound">Enable notifications 🔔</button>
  </div>

  <div id="history-list" class="history"></div>

  <footer class="bottombar">
    <button id="btn-send">Send</button>
    <button id="btn-mute"><span class="lbl">Mute</span><span id="mute-remaining" class="mute-remaining"></span></button>
    <button id="btn-logout">Logout</button>
  </footer>
</div>

<div id="view-timesheet" class="view hidden">
  <div class="ts-controls">
    <label>Year <select id="ts-year"></select></label>
    <label>Month <select id="ts-month"></select></label>
    <span class="ts-status" id="ts-status"></span>
  </div>
  <div class="ts-tables">
    <table class="ts-table" id="ts-table">
      <thead>
        <tr>
          <th>Day</th>
          <th>User</th>
          <th>Start</th>
          <th>Stop</th>
          <th>Gaps</th>
          <th>Total</th>
        </tr>
      </thead>
      <tbody></tbody>
    </table>

    <h3 class="ts-summary-title">Monthly totals</h3>
    <table class="ts-summary" id="ts-summary">
      <thead>
        <tr><th>User</th><th>Total time</th></tr>
      </thead>
      <tbody></tbody>
    </table>
  </div>
</div>

<div id="view-exited" class="view hidden">
  <div class="exited-card">
    <div>App exited.</div>
    <button id="btn-resume">Resume</button>
  </div>
</div>

<div id="modal-mute" class="modal hidden">
  <div class="modal-card">
    <header class="modal-head">
      <span>Mute notifications for…</span>
      <button class="x" data-close>&times;</button>
    </header>
    <div class="mute-grid">
      <button class="mute-opt" data-mute-seconds="300">5m</button>
      <button class="mute-opt" data-mute-seconds="900">15m</button>
      <button class="mute-opt" data-mute-seconds="3600">1h</button>
      <button class="mute-opt" data-mute-seconds="7200">2h</button>
      <button class="mute-opt" data-mute-seconds="10800">3h</button>
      <button class="mute-opt" data-mute-seconds="28800">8h</button>
      <button class="mute-opt" data-mute-seconds="36000">10h</button>
      <button class="mute-opt" data-mute-seconds="43200">12h</button>
      <button class="mute-opt mute-2d" data-mute-2days="1">2days</button>
      <button class="mute-opt mute-unmute" id="btn-unmute" data-mute-seconds="0">Unmute</button>
    </div>
  </div>
</div>

<div id="modal-users" class="modal hidden">
  <div class="modal-card">
    <header class="modal-head">
      <span>Pick a user</span>
      <button class="x" data-close>&times;</button>
    </header>
    <div id="users-list" class="users-list"></div>
  </div>
</div>

<div id="modal-send" class="modal hidden">
  <div class="modal-card">
    <header class="modal-head" id="send-title">
      <span class="status-dot"></span>
      <span class="recipient-name"></span>
      <span class="last-seen"></span>
      <button class="x" data-close>&times;</button>
    </header>
    <textarea id="send-text" rows="4" placeholder="Type a message..."></textarea>
    <footer class="modal-actions">
      <button id="btn-send-msg" class="primary">Send</button>
      <button id="btn-ping">Ping</button>
      <button data-close>Cancel</button>
    </footer>
  </div>
</div>

<div id="modal-log" class="modal hidden">
  <div class="modal-card modal-log-card">
    <header class="modal-head">
      <span id="log-title"></span>
      <button class="x" data-close>&times;</button>
    </header>
    <table class="log-table">
      <tbody></tbody>
    </table>
  </div>
</div>

<div id="toast" class="toast hidden" role="status" aria-live="polite"></div>

<audio id="notify-audio" src="assets/notify.mp3" preload="auto"></audio>

<script src="assets/app.js?v=13"></script>
</body>
</html>
