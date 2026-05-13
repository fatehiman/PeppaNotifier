# PeppaNotifier — Implementation Plan

Intranet-only notification app. PHP backend (no framework, files only) + native Android client (Kotlin).
Server: `http://chat.kimiasoft.ir`. HTTP only. Security is low priority by design.

---

## 1. Decisions (locked)

| Topic | Decision |
|---|---|
| User provisioning | Admin hand-edits `web/data/users.json`. No signup, no forgot-password. |
| Android runtime | Native Android Studio / Kotlin. No Expo, no React Native. |
| Delivery model | Two states per recipient: `notified` (ring / silent-ring) and `opened` (timestamp). |
| Web sound activation | One-time "Enable notifications" banner shown right after login. |
| Do Not Disturb | Respected. No `ACCESS_NOTIFICATION_POLICY`. |
| PING on ALL row | Hidden. ALL row only opens the Send modal. |
| Base URL | Hardcoded `http://chat.kimiasoft.ir` in `Api.kt`; single edit point. |
| Passwords | Plaintext in `users.json` (low-security app, intranet). |
| Sessions | Random 32-byte hex bearer token, never expires. |
| HTTPS | Not used. `usesCleartextTraffic="true"` + network security config. |

---

## 2. Server storage layout

All files under `web/data/`, all JSON, all writes guarded by `flock(LOCK_EX)`, reads by `flock(LOCK_SH)`.

| File | Shape |
|---|---|
| `users.json` | `[{"username":"alice","password":"plain"}]` (you edit this) |
| `sessions.json` | `{ "<token>": {"user":"alice","created":1715500000,"last_sent_cursor":1715500000} }` |
| `online.json` | `{"alice": 1715500000}` (last poll unix ts) |
| `mutes.json` | `{"alice": 1715600000}` (mute expiry unix ts; absent or `<=now` means not muted) |
| `messages.json` | array of message records, pruned to last 7 days on every poll |

Message record:
```json
{
  "id": "<uuid>",
  "group_id": "<uuid>",
  "sender": "alice",
  "recipient": "bob",
  "text": "hello",
  "sent_at": 1715500000,
  "notified_at": null,
  "notified_state": null,
  "opened_at": null
}
```

- `notified_state` ∈ `null | "notified" | "missed"`.
- For ALL sends, the server fans out at send time into N records (one per other user), sharing `group_id` so the sender can group/inspect per-recipient delivery state. The sender is excluded from the fan-out.

---

## 3. HTTP API — single `api.php?action=...` dispatcher

Auth via `Authorization: Bearer <token>` header on everything except `login`.

| Action | Method | Body | Returns |
|---|---|---|---|
| `login` | POST | `{username,password}` | `{token}` or 401 |
| `logout` | POST | — | 204; drops session |
| `poll` | GET | — | `{new_messages:[…], sent_updates:[…], unread_count:N}` |
| `ack_opened` | POST | `{ids:[…]}` | 204; sets `opened_at` on those records |
| `history` | GET | — | last 7d of records where I'm sender or recipient (newest first) |
| `users` | GET | — | `[{username,last_seen,online}, …, {username:"ALL"}]`; self excluded |
| `send` | POST | `{recipient,text}` | created record id(s); fan-out if `recipient="ALL"` |
| `mute` | POST | `{until: unix_ts}` (0 to clear) | `{muted_until}` |

### Poll semantics (per request, server-side)

1. Touch `online.json[me] = now`.
2. Prune `messages.json`: drop records with `sent_at < now - 7*86400`.
3. For each record where `recipient == me` and `notified_at IS NULL`:
   - `age = now - sent_at`
   - `age ≤ 120` → include in `new_messages`, set `notified_at=now`, `notified_state="notified"`.
   - `120 < age ≤ 7200` → include in `new_messages`, set `notified_at=now`, `notified_state="missed"`.
   - `age > 7200` → set `notified_at=now`, `notified_state="missed"`, **do not return** (history only).
4. `sent_updates` = my outgoing records whose `notified_at` or `opened_at` changed since the token's `last_sent_cursor`; then advance cursor to `now`.
5. `unread_count` = `count(new_messages)` (the client uses this only for sanity / debugging).
6. Poll response also contains:
   - `my_muted_until`: my own mute expiry (0 if not muted) — controls whether my client silences sound / OS notifications.
   - `mutes`: `{username: expiry_ts, …}` for any users currently muted, so the Zzz icon stays fresh in the user list.

Once a record is included in `new_messages` for a recipient, it will never be returned again to that recipient (its `notified_at` is set). It still appears in `history`.

### Mute semantics

- A user is "muted" if `mutes[user] > now`. While muted **on a given client**:
  - the poll-driven sound playback is suppressed,
  - the OS notification is **not** posted,
  - the history list is still updated normally (the message is still marked
    `notified` server-side once polled — we never silently "lose" a message).
- Mute is stored per username on the server; all of that user's signed-in
  devices share the state.
- Mute durations the client may pick:
  - simple offsets: `5m, 15m, 1h, 2h, 3h, 8h, 10h, 12h`.
  - `2days` is special: client computes `now + 2 days`, then snaps forward
    to the next local 08:00 (so Fri 20:30 + `2days` = Mon 08:00). The client
    sends the absolute `until` unix timestamp; the server just stores it.
- "Unmute" sends `until: 0` (or any timestamp ≤ now).

### Send semantics

- Server validates recipient exists (or equals `ALL`).
- If `ALL`: fan out into N records, one per other user, same `group_id`, same `text`, same `sent_at`.
- Otherwise: single record, `group_id == id`.

---

## 4. Sound playback rules

### Android (force-play `res/raw/notify.mp3` per message)

The OS notification is built with **no sound** (silent channel `MESSAGES_SILENT`); audio is played by `MediaPlayer`.

```
on new message:
  cur = audioMgr.getStreamVolume(STREAM_MUSIC)
  max = audioMgr.getStreamMaxVolume(STREAM_MUSIC)
  if cur / max < 0.05:
    if boostedPlays == 0: originalVolume = cur
    audioMgr.setStreamVolume(STREAM_MUSIC, round(0.30 * max), 0)
    boostedPlays += 1
  player = MediaPlayer.create(ctx, R.raw.notify)   // USAGE_NOTIFICATION
  player.setOnCompletionListener {
    player.release()
    if was-boosted:
      boostedPlays -= 1
      if boostedPlays == 0:
        audioMgr.setStreamVolume(STREAM_MUSIC, originalVolume, 0)
  }
  player.start()
```

- Stream = `STREAM_MUSIC` (most reliable when ringer is silent).
- Multiple simultaneous notifications: ref-counted boost so we only restore after the last one finishes.
- DND respected (no policy override).

### Web

- `assets/notify.mp3` (same file as Android).
- On each new message: `new Audio('notify.mp3').play()`. No system-volume control available in browsers.
- One-time activation: "Enable notifications 🔔" banner on first main-page load; click satisfies Chrome's user-gesture requirement and requests `Notification.requestPermission()`. Flag stored in `localStorage`.

---

## 5. UI specifications

### History list (web and Android, identical content)

- Scrollable, newest on top.
- Row format: `YYYY/MM/DD HH:mm > sender: text  [🔔|🔕]`
  - 🔔 if `notified_state == "notified"`.
  - 🔕 if `notified_state == "missed"`.
- For my own sent messages: row also shows recipient and an "opened HH:mm" stamp once available.
- **Tap on a row → open Send Message modal pre-filled with `recipient = otherParty`** (the other participant in that row).

### Users list modal

- Each user row: `● username  [Zzz HH:MM]                    [📍 PING]`
  - Left dot: green if online, gray if offline.
  - For offline users, the row also shows `last seen YYYY/MM/DD HH:mm`.
  - If `mutes[user] > now`, a Zzz badge appears between the name and the
    PING button, showing the remaining mute duration as `HH:MM` (hours can
    exceed 23 for the `2days` choice — e.g. `59:30`). On web the badge has
    a `title` tooltip with the exact expiry time; on Android the same info
    is inline (Android tooltips are unreliable across vendors).
- **Row tap (anywhere but the PING icon)** → open Send modal with that recipient.
- **PING icon tap** → POST `/api/send {recipient, text:"ping!"}`, then close the modal.
- **ALL row** at the bottom: row tap opens Send modal with `recipient="ALL"`. PING icon is **hidden** for ALL.

### Send Message modal

```
┌──────────────────────────────────────────────────────────────┐
│  ● bob                          (last seen 2026/05/12 13:42)  │
├──────────────────────────────────────────────────────────────┤
│  ┌────────────────────────────────────────────────────────┐  │
│  │ message text...                                        │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
│                     [ Send ]  [ Ping ]  [ Cancel ]           │
└──────────────────────────────────────────────────────────────┘
```

- Title bar: status dot + username; if offline, "(last seen YYYY/MM/DD HH:mm)" right-aligned.
- For `recipient="ALL"`: title shows `● ALL`, no last-seen.
- **Send**: requires non-empty text; POST `/api/send`; close.
- **Ping**: POST `/api/send` with `text:"ping!"` regardless of the textarea; close.
- **Cancel**: close without sending.

### Open-ack

- When a history row becomes visible on the main page, the client batches its id and POSTs `/api/ack_opened` so the sender's view can show "opened HH:mm".

### Main page bottom action bar

All four primary actions live below the history list (the top bar is just
brand + current user):

```
[ Send ]  [ Mute (HH:MM) ]  [ Exit ]  [ Logout ]
```

- **Send** → users modal (unchanged).
- **Mute** → mute modal:
  ```
  [ 5m ] [ 15m ] [ 1h  ] [ 2h  ]
  [ 3h ] [ 8h  ] [ 10h ] [ 12h ]
  [ 2days ]                     [ Unmute ]
  ```
  Tapping any duration computes `until` (special case for `2days`), POSTs
  `mute`, closes the modal. The button caption updates to
  `Mute HH:MM` and decrements every minute until expiry. `Unmute` is only
  enabled when currently muted.
- **Exit** stops the polling service / loop and tears down the UI but keeps
  the session. Relaunch resumes without re-login.
- **Logout** ends the server session and returns to the login screen.

---

## 6. Android app structure

```
android/
  build.gradle, settings.gradle, gradle.properties, gradlew[.bat]
  app/
    build.gradle
    src/main/
      AndroidManifest.xml
      res/
        layout/                  activity_login.xml, activity_main.xml,
                                 dialog_send.xml, item_history.xml, item_user.xml
        drawable/                ic_bell.xml, ic_bell_off.xml, ic_ping.xml,
                                 ic_dot_online.xml, ic_dot_offline.xml
        raw/                     notify.mp3
        values/                  strings.xml, colors.xml, themes.xml
      java/ir/kimiasoft/peppanotifier/
        App.kt                   // Application; channels created here
        Api.kt                   // OkHttp client; one method per action
        Prefs.kt                 // EncryptedSharedPreferences: token, cached lists
        NotificationHelper.kt    // silent channel + per-message notifications
        SoundPlayer.kt           // ref-counted boost + MediaPlayer
        LoginActivity.kt
        MainActivity.kt          // history RecyclerView, FAB Send, overflow Logout
        SendDialogFragment.kt
        UsersDialogFragment.kt
        HistoryAdapter.kt
        UsersAdapter.kt
        PollingService.kt        // foreground service, 15s coroutine loop
        BootReceiver.kt          // BOOT_COMPLETED, MY_PACKAGE_REPLACED → start service
```

### Foreground service

- `startForeground(...)` with low-priority permanent notification ("PeppaNotifier running").
- `CoroutineScope` running `while(isActive) { poll(); delay(15_000) }`.
- `START_STICKY`. Restarted by `BootReceiver` on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.
- Belt-and-braces: a `WorkManager` periodic worker every 15 min that ensures the service is alive.

### Notification → app open

- Each message id → its own notification (`notificationId = stableHash(messageId)`).
- Title = sender, body = first ~80 chars of text + ellipsis.
- Tap → `PendingIntent` to `MainActivity` with `FLAG_ACTIVITY_CLEAR_TOP`.

---

## 7. Web structure

```
web/
  index.php             // renders <login> or <main> based on session cookie
  api.php               // dispatcher for ?action=...
  lib.php               // session, locking, storage helpers
  assets/
    app.js              // poll loop, render, sound, ack
    style.css
    notify.mp3
    bell.svg, bell-off.svg, ping.svg
  data/
    users.json          // hand-edited
    sessions.json
    online.json
    messages.json
  .htaccess             // deny direct access to /data/
```

---

## 8. Build order

1. **PHP server**
   - `web/lib.php`, `web/api.php` with all 7 actions, file locking, 7-day prune.
   - `web/data/users.json` placeholder with a comment showing the format.
   - `.htaccess` to deny `/data/`.
   - Verify each action with `curl`.
2. **Web UI**
   - `web/index.php` + `assets/app.js` / `style.css`.
   - Login → poll loop → render history → users modal (PING + status) → send modal (Send/Ping/Cancel + title status) → sound activation → in-page `Notification` API.
3. **Android skeleton**
   - Gradle wrapper, manifest, App.kt, Api.kt, Prefs.kt, NotificationHelper.kt, SoundPlayer.kt, channels, vector drawables, `res/raw/notify.mp3`.
4. **Android UI**
   - LoginActivity → MainActivity (history) → UsersDialogFragment (PING + status) → SendDialogFragment (Send/Ping/Cancel + title status).
   - Wire history-row tap → Send modal pre-filled.
5. **Android service & notifications**
   - PollingService (foreground, 15s loop), BootReceiver, WorkManager watchdog.
   - Per-message notifications with silent channel; SoundPlayer with volume-boost rule.
   - Tap → MainActivity. Open-ack on visible rows.
6. **Release APK**
   - `./gradlew assembleRelease` with a self-signed keystore checked in.

---

## 9. Timesheet (web only, v2)

A lightweight work-start / work-stop tracker bolted onto the existing chat.
Server is the source of truth; UI lives in the same SPA shell (no new routes
at the Apache level, just a hash route).

### Top-bar menu

Top bar (visible on chat and timesheet views) carries three buttons after
the brand:

```
[PeppaNotifier]  [Home]  [Start | Stop]  [Timesheet]                  [me]
```

- **Home** → `location.hash = ''`, switches to the chat view.
- **Start / Stop** → toggle button. Disabled while `POST ts_toggle` is in
  flight. The label reflects server state, reconciled on every poll.
- **Timesheet** → `location.hash = '#timesheet'`, switches to the timesheet
  view (same top bar stays).

There is **no** hamburger; all three items are always visible.

### Storage

```
web/data/timesheets/{YYYYMM}-{user}.json   (one file per user per month)
```

Each file is an append-only JSON array:

```json
[
  {"ts": 1715500000, "kind": "start"},
  {"ts": 1715501800, "kind": "stop"}
]
```

- Only the raw unix timestamp and the kind are stored — no timezone-baked
  strings. The view timezone (`TZ_VIEW`, see `web/env.php`) is applied at
  read time, so changing it later reformats every entry consistently.
- All writes go through `flock(LOCK_EX)`, same pattern as `messages.json`.
- The `data/` `.htaccess` already denies HTTP access to the whole tree, so
  the subfolder is private without extra config.

### Timezone — env.php

```
web/env.php          (gitignored, per-host)
web/env.sample.php   (committed template, used as fallback if env.php absent)
```

Both define `const TZ_VIEW = 'Asia/Tehran';`. `lib.php` loads env on every
request and calls `date_default_timezone_set(TZ_VIEW)`. From that point on,
every server-side `date()` call is in the view timezone, and the API's
`ts_month` response also returns the `tz_view` it used.

Edge case the user explicitly accepts: storage filenames embed
`YYYYMM-{user}.json` based on TZ_VIEW at write time. Changing TZ_VIEW
later won't move entries that were written near a TZ boundary into the
month they would now belong to. Acceptable because real working hours
(10:30–19:30 IRST) are far from any UTC/IRST flip point.

### State derivation

A user is **started** iff the last entry in their *current-month* file has
`date == today` AND `kind == "start"`. This handles midnight rollover
automatically: a `start` from yesterday with no following `stop` lands on
"stopped" today, and the next toggle on the new day appends a fresh `start`.
This matches the rule "no work across midnight — user must stop before
00:00 and start again after".

### API additions

| Action | Method | Body / query | Returns |
|---|---|---|---|
| `ts_state` | GET | — | `{state: "started"\|"stopped", today_date}` for the caller. |
| `ts_toggle` | POST | — (server flips the current state) | `{state, today_date}`. Server appends a timesheet entry only — **no chat broadcast**. The web client shows a local `Started!` / `Stopped!` toast on success. |
| `ts_months` | GET | — | `[{year, month}, ...]` sorted desc. Always includes the current month even if empty on disk. |
| `ts_month` | GET | `year`, `month` | `{year, month, entries: {username: [...]}}`. Returns every user's entries — everyone can see everyone's timesheet. |

`poll` is also extended to return `ts_state` and `today_date` so the toggle
button reconciles on the existing 15 s tick without a separate timer.

### Daily-row computation (client)

For one user's same-day entries sorted by ts:

- `start` = time of the *first* `start`.
- `stop` = time of the *last* `stop`, or `—` if the day ends in an open session.
- `gaps` = sum of `(next_start − previous_stop)` across all reopen gaps.
- `total` = sum of each `(stop − start)` session, or `—` if the day ends in
  an open session. Equivalent to `(last_stop − first_start) − gaps`.

If a day has no entries from anyone, a single dimmed placeholder row is still
rendered (per the "at least one row per day" rule).

### Timesheet view

```
[ Year ▾ ]  [ Month ▾ ]                                          (status)

┌─ ts-table ────────────────────────────────────────────────────────────┐
│ Day │ Weekday │ User    │ Start │ Stop  │ Gaps  │ Total │
├─────┼─────────┼─────────┼───────┼───────┼───────┼───────┤
│  1  │  Mon    │ amir    │ 09:02 │ 17:31 │ 01:05 │ 07:24 │
│  1  │  Mon    │ fatemeh │ 08:50 │ 17:00 │ 00:30 │ 07:40 │
│  2  │  Tue    │   —     │   —   │   —   │   —   │   —   │
│ ... │                                                       │
└────────────────────────────────────────────────────────────┘

Monthly totals
┌──────────┬───────────────┐
│ User     │ Total time    │
├──────────┼───────────────┤
│ amir     │ 162:48        │
│ fatemeh  │ 170:05        │
└──────────┴───────────────┘
```

- Weekday is computed in JS from `new Date(year, month-1, day).getDay()`.
- Sat (6) and Sun (0) rows get a weekend background (amber).
- Year dropdown lists only years with data on disk; month dropdown lists
  only months with data for the selected year. The current month is always
  available even if empty.

