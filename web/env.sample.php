<?php
/**
 * Per-host environment overrides for PeppaNotifier.
 *
 * Copy this file to env.php (which is gitignored) and adjust as needed.
 * lib.php auto-loads env.php on every request and falls back to this template
 * if env.php is missing — so the app still boots after a fresh clone.
 *
 * Stored timestamps in timesheets are raw unix epoch (timezone-free). The
 * value below only controls how those timestamps are *rendered* (the date
 * and time fields the server attaches when returning timesheet data, the
 * "today" comparison used by the Start/Stop button, and the folder/file
 * naming for new timesheet entries).
 *
 * If you change TZ_VIEW after entries have been written, near-midnight
 * entries may end up grouped under the "wrong" day or month. Acceptable
 * for the current use case (working hours 10:30–19:30 IRST, well away
 * from any timezone-flip edge).
 */
declare(strict_types=1);

const TZ_VIEW = 'Asia/Tehran';

/**
 * One-way timesheet backup. After every write to data/timesheets/*.json,
 * the *sender* fires-and-forgets a POST of that single file to BACKUP_URL.
 *
 * Set BACKUP_URL to '' on the receiver (and any host that should not push
 * backups). receiveData.php refuses to act as a receiver when BACKUP_URL
 * is non-empty, so the two roles stay mutually exclusive on each host.
 *
 * BACKUP_TOKEN is a shared secret: the sender attaches it as the
 * `X-Backup-Token` request header and the receiver compares with
 * hash_equals(). Generate with: php -r 'echo bin2hex(random_bytes(32));'
 * Both hosts must use the exact same value.
 */
const BACKUP_URL   = '';
const BACKUP_TOKEN = '';
