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
│   ├── lib.php       — file-locking + storage helpers
│   ├── index.php     — UI shell (login / main / modals)
│   ├── assets/       — app.js, style.css, notify.mp3 (drop your own)
│   └── data/         — JSON files (users.json hand-edited)
└── android/          — Android Studio / Kotlin project
    └── app/src/main/…
```

## Deploying the server

1. Copy the `web/` directory to your Apache document root for
   `chat.kimiasoft.ir`.
2. Make sure `web/data/` is writable by the PHP user.
3. Edit `web/data/users.json` to list the actual users (plain JSON, plaintext
   passwords are fine per the locked-in low-security stance).
4. Drop a real `notify.mp3` into `web/assets/`.
5. Verify Apache has `mod_rewrite` and `AllowOverride All` so the
   `.htaccess` files (cleartext auth header pass-through + `data/` deny) take
   effect.

Browse to `http://chat.kimiasoft.ir/` and log in.

## Building the Android client

See [`android/README.MD`](android/README.MD).

## Defaults locked in

* No HTTPS (intranet, low security).
* Passwords stored plaintext in `users.json`.
* Sessions never expire; tokens are 32-byte hex.
* 7-day server retention; messages older are pruned.
* Notify window: 0–2 min as "notified", 2 min – 2 h as "missed", > 2 h dropped
  silently to history only.
* Online definition: a user is online if their last poll was less than 35 s ago.
* `ALL` recipient fans out server-side into N records (one per other user);
  sender excluded.
* DND is respected by both clients.
* PING is hidden on the `ALL` row.
