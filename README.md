# PeppaNotifier

Tiny intranet notification system. PHP backend (no framework, files only) +
native Android client (Kotlin) + browser web UI. Designed for a deployment that
has DNS but no internet access; no push notifications, no database.

See [`PLAN.md`](PLAN.md) for the full design and locked-in decisions.

## Layout

```
PeppaNotifier/
├── PLAN.md           — design, decisions, build order
├── web/              — PHP server + web UI
│   ├── api.php       — single dispatcher: ?action=login|poll|send|...
│   ├── lib.php       — file-locking + storage helpers; loads env.php
│   ├── index.php     — UI shell (login / chat / timesheet / modals)
│   ├── state.php     — public on/off probe served at /state via .htaccess rewrite
│   ├── env.sample.php — committed template; copy to env.php (gitignored) and edit
│   ├── assets/       — app.js, style.css, notify.mp3 (drop your own)
│   └── data/         — JSON files (users.json hand-edited; everything else runtime)
│       └── timesheets/  — per-user-per-month start/stop logs (YYYYMM-{user}.json)
└── android/          — Android Studio / Kotlin project
    └── app/src/main/…
```

## Web UI tour

After login the browser shows a chat-style history list. The top blue bar has,
left-to-right:

| | |
|---|---|
| `PeppaNotifier` | brand |
| **Home** | back to chat (hash `#`) |
| **▶ Start / ⏹ Stop** | toggle the personal work-time log. Icon + label both describe the *next* action; the button turns amber while you're currently working, and shows a one-line `Started!` / `Stopped!` toast on success. |
| **Timesheet** | switches to the timesheet table (hash `#timesheet`) |
| **ON/OFF switch** | only shown for user `amir` — iOS-style toggle for the public `/state` probe |
| `⏻ username` | click anywhere on the icon or name to log out |

Bottom bar (chat view only): **Send · Mute · Logout**.

The Timesheet table lists every day of the selected month, with one row per
user-with-activity per day. Sat/Sun rows get an amber background. The Total
column has a small blue **ⓘ** that opens a per-day log modal showing every
start/stop click and its time. A monthly-totals summary appears below.

## Deploying the server

1. Copy the `web/` directory to your Apache document root for
   `chat.kimiasoft.ir`.
2. Make sure `web/data/` is writable by the PHP user.
3. Edit `web/data/users.json` to list the actual users (plain JSON, plaintext
   passwords are fine per the locked-in low-security stance).
4. `cp web/env.sample.php web/env.php` and edit `TZ_VIEW` if you don't want
   `Asia/Tehran`. `env.php` is gitignored; `env.sample.php` is the fallback.
5. Drop a real `notify.mp3` into `web/assets/`.
6. Verify Apache has `mod_rewrite` and `AllowOverride All` so the
   `.htaccess` files (cleartext auth header pass-through + `/state` rewrite +
   `data/` deny) take effect.

Browse to `http://chat.kimiasoft.ir/` and log in.

## Building the Android client

See [`android/README.MD`](android/README.MD).

## Defaults locked in

* No HTTPS (intranet, low security).
* Passwords stored plaintext in `users.json`.
* Sessions never expire; tokens are 32-byte hex.
* 7-day server retention for chat messages; older are pruned.
* Notify window: 0–2 min as "notified", 2 min – 2 h as "missed", > 2 h dropped
  silently to history only.
* Online definition: a user is online if their last poll was less than 35 s ago.
* `ALL` recipient fans out server-side into N records (one per other user);
  sender excluded.
* DND is respected by both clients.
* PING is hidden on the `ALL` row.
* `TZ_VIEW = 'Asia/Tehran'` — timesheet entries store raw unix `ts` only;
  date / time strings are computed on read using this constant, so changing
  the constant later reformats every existing row consistently.

---

## Web tech reference

Implementation notes for the PHP server + JS client. Pair with [`PLAN.md`](PLAN.md)
for the original design and rationale.

### API actions

All under `api.php?action=…`. Auth via `Authorization: Bearer <token>` on
everything except `login`.

| Action | Method | Body / query | Notes |
|---|---|---|---|
| `login` | POST | `{username, password}` | `{token, user}` or 401 |
| `logout` | POST | — | 204 |
| `poll` | GET | — | `{new_messages, sent_updates, unread_count, server_time, my_muted_until, mutes, ts_state, today_date}` |
| `ack_opened` | POST | `{ids:[…]}` | 204 |
| `history` | GET | — | last 7 days of records where I'm sender or recipient |
| `users` | GET | — | `[{username, last_seen, online, muted_until}, …, {username:"ALL"}]` |
| `send` | POST | `{recipient, text}` | created record(s); fan-out if `recipient="ALL"` |
| `mute` | POST | `{until}` | `{muted_until}` |
| `ts_state` | GET | — | `{state: "started"\|"stopped", today_date}` |
| `ts_toggle` | POST | — | flips state, appends `{ts, kind}` to current-month file. **No chat broadcast** — UI shows a local toast. |
| `ts_months` | GET | — | `[{year, month}]` desc; current month always included |
| `ts_month` | GET | `?year=YYYY&month=MM` | `{year, month, tz_view, entries:{user:[…]}}`. Entries are enriched with `date`+`time` strings computed from `ts` in `TZ_VIEW`. |
| `state_get` | GET | — | `{on: 0\|1}` |
| `state_toggle` | POST | — | flips, returns `{on}`. **`amir` only** — others get 403. |

Plus `state.php` (no `?action=`) served at `/state` via `.htaccess` rewrite:
text/plain body of `"1"` (HTTP 200) or `"0"` (HTTP 404). Public, no auth —
designed for external uptime probes.

### Data files (all under `web/data/`)

| File | Shape |
|---|---|
| `users.json` | `[{"username","password"}, …]` (hand-edited) |
| `sessions.json` | `{ "<token>": {"user", "created", "last_sent_cursor"} }` |
| `online.json` | `{"<user>": last_poll_unix}` |
| `mutes.json` | `{"<user>": expiry_unix}` |
| `messages.json` | array of message records, pruned to last 7 days on every poll |
| `state.json` | `{"on": 0|1}` — the public on/off flag |
| `timesheets/{YYYYMM}-{user}.json` | append-only `[{"ts", "kind"}, …]` for that user-month |

All writes are `flock(LOCK_EX)`, reads `flock(LOCK_SH)`. The `.htaccess` in
`web/data/` denies direct HTTP access to the entire tree.

### Timesheet rules

- Entries store only `{ts, kind}`. `kind ∈ {"start", "stop"}`.
- `date` and `time` (HH:MM) are computed from `ts` at read time using
  `TZ_VIEW` (set in `env.php`).
- A user is "started" iff the last entry in their *current-month* file is a
  `start` AND its `TZ_VIEW`-local date equals today. This auto-handles the
  midnight rollover: a `start` from yesterday with no `stop` reads as
  "stopped" on the new day.
- Per-day totals: `total = sum of (stop_i − start_i)`. Days with an open
  session at end-of-day show `stop` and `total` as `—`.
- `gaps`: sum of `(next_start − previous_stop)` reopen intervals.

### Web client (`assets/app.js`) — state model

A single in-memory `state` object:

| Field | Purpose |
|---|---|
| `messages` (Map) | id → record, sourced from `/history` and `/poll` |
| `pollTimer`, `muteTicker` | setInterval handles |
| `modalRecipient` | current Send modal target |
| `mutedUntil`, `userMutes` | mute state |
| `workState`, `workToggling`, `todayDate` | drives the Start/Stop button; reconciled on every poll |
| `tsAvailableMonths`, `tsYear`, `tsMonth`, `tsRowLogs` | timesheet view state and the per-row entry cache that feeds the (ⓘ) modal |
| `stateOn`, `stateToggling` | amir-only ON/OFF switch state |
| `sending`, `exited`, `bootstrapped` | flags |

Hash router: empty hash → chat; `#timesheet` → timesheet. The top bar persists
across both views. Clicking Start/Stop while on the timesheet view re-fetches
the currently-selected month (handy because the new row appears immediately
without changing the dropdown).

### Cache-busting

`index.php` references `assets/app.js?v=N` and `assets/style.css?v=N`. Bump
both when you change either file so browsers pick up new code without a hard
refresh.

### See also

- [`PLAN.md`](PLAN.md) — original design doc with the full feature matrix and
  the locked-in decisions.
- [`web/assets/README.md`](web/assets/README.md) — what to drop in
  `assets/notify.mp3`.
- [`android/README.MD`](android/README.MD) — Android-side build notes.
