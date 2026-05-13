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
