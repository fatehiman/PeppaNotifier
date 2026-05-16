<?php
declare(strict_types=1);

// Per-host config: TZ_VIEW (and anything else added later).
// env.php is gitignored; env.sample.php is the committed fallback.
$__env = __DIR__ . '/env.php';
if (!file_exists($__env)) $__env = __DIR__ . '/env.sample.php';
require $__env;
unset($__env);

date_default_timezone_set(TZ_VIEW);

const DATA_DIR = __DIR__ . '/data';
const TS_DIR = __DIR__ . '/data/timesheets';
const RETENTION_SECONDS = 7 * 86400;
const NOTIFY_WINDOW = 120;
const MISSED_WINDOW = 7200;
const ONLINE_WINDOW = 35;

function data_path(string $name): string {
    return DATA_DIR . '/' . $name;
}

function ensure_data_files(): void {
    if (!is_dir(DATA_DIR)) {
        mkdir(DATA_DIR, 0755, true);
    }
    $defaults = [
        'sessions.json' => '{}',
        'online.json'   => '{}',
        'mutes.json'    => '{}',
        'messages.json' => '[]',
    ];
    foreach ($defaults as $name => $init) {
        $p = data_path($name);
        if (!file_exists($p)) {
            file_put_contents($p, $init);
        }
    }
}

function db_read(string $name): array {
    ensure_data_files();
    $path = data_path($name);
    $fp = fopen($path, 'rb');
    if ($fp === false) return [];
    flock($fp, LOCK_SH);
    $contents = stream_get_contents($fp);
    flock($fp, LOCK_UN);
    fclose($fp);
    if ($contents === '' || $contents === false) return [];
    $data = json_decode($contents, true);
    return is_array($data) ? $data : [];
}

function db_modify(string $name, callable $fn) {
    ensure_data_files();
    $path = data_path($name);
    $fp = fopen($path, 'c+b');
    if ($fp === false) {
        throw new RuntimeException("cannot open $path");
    }
    flock($fp, LOCK_EX);
    $contents = stream_get_contents($fp);
    $data = ($contents === '' || $contents === false) ? [] : json_decode($contents, true);
    if (!is_array($data)) $data = [];
    $result = $fn($data);
    rewind($fp);
    ftruncate($fp, 0);
    fwrite($fp, json_encode($data, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
    fflush($fp);
    flock($fp, LOCK_UN);
    fclose($fp);
    return $result;
}

function uuid(): string {
    $b = random_bytes(16);
    $b[6] = chr((ord($b[6]) & 0x0f) | 0x40);
    $b[8] = chr((ord($b[8]) & 0x3f) | 0x80);
    $hex = bin2hex($b);
    return substr($hex, 0, 8) . '-' .
           substr($hex, 8, 4) . '-' .
           substr($hex, 12, 4) . '-' .
           substr($hex, 16, 4) . '-' .
           substr($hex, 20, 12);
}

function new_token(): string {
    return bin2hex(random_bytes(32));
}

function bearer_token(): ?string {
    $candidates = [
        $_SERVER['HTTP_AUTHORIZATION'] ?? '',
        $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '',
    ];
    if (function_exists('getallheaders')) {
        $hdrs = getallheaders();
        if (is_array($hdrs)) {
            foreach ($hdrs as $k => $v) {
                if (strcasecmp($k, 'Authorization') === 0) {
                    $candidates[] = $v;
                }
            }
        }
    }
    foreach ($candidates as $hdr) {
        if (preg_match('/^Bearer\s+([0-9a-f]+)$/i', (string)$hdr, $m)) {
            return strtolower($m[1]);
        }
    }
    return null;
}

function send_json($data, int $status = 200): void {
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
}

function require_user(): string {
    $tok = bearer_token();
    if ($tok === null) {
        send_json(['error' => 'unauthorized'], 401);
        exit;
    }
    $sessions = db_read('sessions.json');
    if (!isset($sessions[$tok]['user'])) {
        send_json(['error' => 'unauthorized'], 401);
        exit;
    }
    return $sessions[$tok]['user'];
}

function body_json(): array {
    $raw = file_get_contents('php://input');
    if ($raw === false || $raw === '') return [];
    $data = json_decode($raw, true);
    return is_array($data) ? $data : [];
}

function load_users(): array {
    $path = data_path('users.json');
    if (!file_exists($path)) return [];
    $fp = fopen($path, 'rb');
    if ($fp === false) return [];
    flock($fp, LOCK_SH);
    $contents = stream_get_contents($fp);
    flock($fp, LOCK_UN);
    fclose($fp);
    if ($contents === '' || $contents === false) return [];
    $data = json_decode($contents, true);
    return is_array($data) ? $data : [];
}

function user_exists(string $username): bool {
    foreach (load_users() as $u) {
        if (($u['username'] ?? null) === $username) return true;
    }
    return false;
}

function all_usernames(): array {
    $out = [];
    foreach (load_users() as $u) {
        if (isset($u['username']) && is_string($u['username'])) {
            $out[] = $u['username'];
        }
    }
    return $out;
}

function get_mute(string $user): int {
    $mutes = db_read('mutes.json');
    return (int)($mutes[$user] ?? 0);
}

function set_mute(string $user, int $until): int {
    $stored = 0;
    db_modify('mutes.json', function (array &$m) use ($user, $until, &$stored) {
        if ($until > 0) {
            $m[$user] = $until;
            $stored = $until;
        } else {
            unset($m[$user]);
            $stored = 0;
        }
    });
    return $stored;
}

function active_mutes(int $now): array {
    $out = [];
    foreach (db_read('mutes.json') as $user => $until) {
        $u = (int)$until;
        if ($u > $now) $out[$user] = $u;
    }
    return $out;
}

/**
 * Append a one-line trace of the current request to data/api.log.
 * Each line: [date time] METHOD action user=… tok=… ip=… ua=… body=…
 * The data/ dir is denied to direct HTTP by .htaccess, so the log is private.
 */
function log_request(): void {
    $ts     = date('Y-m-d H:i:s');
    $method = $_SERVER['REQUEST_METHOD'] ?? '?';
    $action = $_GET['action'] ?? '?';
    // Through Arvancloud the real client IP arrives in X-Forwarded-For / X-Real-IP.
    $ip = $_SERVER['HTTP_X_FORWARDED_FOR']
       ?? $_SERVER['HTTP_X_REAL_IP']
       ?? $_SERVER['REMOTE_ADDR']
       ?? '?';
    $ua = $_SERVER['HTTP_USER_AGENT'] ?? '-';
    if (strlen($ua) > 80) $ua = substr($ua, 0, 80) . '...';

    $tok = bearer_token();
    $tokShort = $tok ? substr($tok, 0, 8) : '-';
    $user = '-';
    if ($tok !== null) {
        $sessions = db_read('sessions.json');
        $user = $sessions[$tok]['user'] ?? '-';
    }

    $body = '-';
    if ($method === 'POST') {
        $raw = @file_get_contents('php://input');
        if ($raw !== false && $raw !== '') {
            if (strlen($raw) > 500) $raw = substr($raw, 0, 500) . '...';
            $body = str_replace(["\r", "\n"], ['', '\n'], $raw);
        }
    }

    $line = sprintf(
        "[%s] %s %s user=%s tok=%s ip=%s ua=%s body=%s\n",
        $ts, $method, $action, $user, $tokShort, $ip, $ua, $body
    );
    @file_put_contents(data_path('api.log'), $line, FILE_APPEND | LOCK_EX);
}

function prune_messages(array &$messages, int $now): void {
    $cutoff = $now - RETENTION_SECONDS;
    $messages = array_values(array_filter(
        $messages,
        fn($m) => (int)($m['sent_at'] ?? 0) >= $cutoff
    ));
}

/* ---------- On/Off state flag ---------- */

function state_get(): int {
    $s = db_read('state.json');
    return (int)($s['on'] ?? 0) === 1 ? 1 : 0;
}

function state_set(int $on): int {
    $v = $on === 1 ? 1 : 0;
    db_modify('state.json', function (array &$s) use ($v) {
        $s['on'] = $v;
    });
    return $v;
}

/* ---------- Timesheets ---------- */

function ts_safe_user(string $user): string {
    return preg_replace('/[^a-zA-Z0-9_-]/', '_', $user);
}

function ts_filename(int $year, int $month, string $user): string {
    return sprintf('%04d%02d-%s.json', $year, $month, ts_safe_user($user));
}

function ts_path(int $year, int $month, string $user): string {
    return TS_DIR . '/' . ts_filename($year, $month, $user);
}

function ts_ensure_dir(): void {
    if (!is_dir(TS_DIR)) {
        @mkdir(TS_DIR, 0755, true);
    }
}

function ts_read_user_month(int $year, int $month, string $user): array {
    $path = ts_path($year, $month, $user);
    if (!file_exists($path)) return [];
    $fp = fopen($path, 'rb');
    if ($fp === false) return [];
    flock($fp, LOCK_SH);
    $contents = stream_get_contents($fp);
    flock($fp, LOCK_UN);
    fclose($fp);
    if ($contents === '' || $contents === false) return [];
    $data = json_decode($contents, true);
    return is_array($data) ? $data : [];
}

function ts_append_user_month(int $year, int $month, string $user, array $entry): void {
    ts_ensure_dir();
    $path = ts_path($year, $month, $user);
    $fp = fopen($path, 'c+b');
    if ($fp === false) {
        throw new RuntimeException("cannot open $path");
    }
    flock($fp, LOCK_EX);
    $contents = stream_get_contents($fp);
    $data = ($contents === '' || $contents === false) ? [] : json_decode($contents, true);
    if (!is_array($data)) $data = [];
    $data[] = $entry;
    rewind($fp);
    ftruncate($fp, 0);
    fwrite($fp, json_encode($data, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
    fflush($fp);
    flock($fp, LOCK_UN);
    fclose($fp);
}

/**
 * Replace every entry whose TZ_VIEW-local date equals $dateStr in $user's
 * $year-$month file with the supplied $newDayEntries (each {ts, kind}).
 * Other days in the same file are untouched. Used by the amir-only
 * action_ts_replace_day editor.
 */
function ts_replace_day_entries(int $year, int $month, string $user, string $dateStr, array $newDayEntries): void {
    ts_ensure_dir();
    $path = ts_path($year, $month, $user);
    $fp = fopen($path, 'c+b');
    if ($fp === false) throw new RuntimeException("cannot open $path");
    flock($fp, LOCK_EX);
    $contents = stream_get_contents($fp);
    $data = ($contents === '' || $contents === false) ? [] : json_decode($contents, true);
    if (!is_array($data)) $data = [];

    // Keep entries on OTHER days, drop the ones on $dateStr.
    $kept = [];
    foreach ($data as $e) {
        $ts = (int)($e['ts'] ?? 0);
        if ($ts > 0 && date('Y-m-d', $ts) !== $dateStr) {
            $kept[] = ['ts' => $ts, 'kind' => (string)($e['kind'] ?? '')];
        }
    }
    // Append the new day entries.
    foreach ($newDayEntries as $e) {
        $kept[] = ['ts' => (int)$e['ts'], 'kind' => (string)$e['kind']];
    }
    // Sort whole file by ts ascending.
    usort($kept, fn($a, $b) => $a['ts'] <=> $b['ts']);

    rewind($fp);
    ftruncate($fp, 0);
    fwrite($fp, json_encode($kept, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
    fflush($fp);
    flock($fp, LOCK_UN);
    fclose($fp);
}

/** Returns [['year'=>Y, 'month'=>M], ...] sorted desc, distinct across users. */
function ts_list_months(): array {
    if (!is_dir(TS_DIR)) return [];
    $seen = [];
    foreach (scandir(TS_DIR) as $f) {
        if (preg_match('/^(\d{4})(\d{2})-.+\.json$/', $f, $m)) {
            $key = $m[1] . $m[2];
            $seen[$key] = ['year' => (int)$m[1], 'month' => (int)$m[2]];
        }
    }
    krsort($seen);
    return array_values($seen);
}

/** Returns ['user' => [entries...], ...] for the given month. */
function ts_read_month_all_users(int $year, int $month): array {
    if (!is_dir(TS_DIR)) return [];
    $prefix = sprintf('%04d%02d-', $year, $month);
    $suffix = '.json';
    $out = [];
    foreach (scandir(TS_DIR) as $f) {
        if (strpos($f, $prefix) !== 0) continue;
        if (substr($f, -strlen($suffix)) !== $suffix) continue;
        $user = substr($f, strlen($prefix), -strlen($suffix));
        if ($user === '') continue;
        $entries = ts_read_user_month($year, $month, $user);
        if (!empty($entries)) $out[$user] = $entries;
    }
    return $out;
}

/**
 * Derives current toggle state for a user from their current-month file.
 * 'started' iff the last entry's date is today AND kind is 'start'.
 */
function ts_state_for(string $user, int $now): string {
    $year = (int)date('Y', $now);
    $month = (int)date('n', $now);
    $today = date('Y-m-d', $now);
    $entries = ts_read_user_month($year, $month, $user);
    if (empty($entries)) return 'stopped';
    $last = $entries[count($entries) - 1];
    // Always derive the entry's local date from its raw `ts` using TZ_VIEW;
    // do not trust any stored `date` field — it may have been written under
    // a different TZ_VIEW.
    $ts = (int)($last['ts'] ?? 0);
    $lastDate = $ts > 0 ? date('Y-m-d', $ts) : '';
    if ($lastDate === $today && ($last['kind'] ?? '') === 'start') {
        return 'started';
    }
    return 'stopped';
}

/** Fan out a chat message to everyone except $sender. Returns created records. */
function fan_out_to_all(string $sender, string $text, int $now): array {
    $targets = array_values(array_filter(all_usernames(), fn($u) => $u !== $sender));
    if (empty($targets)) return [];
    $groupId = uuid();
    $created = [];
    db_modify('messages.json', function (array &$messages) use ($sender, $text, $now, $targets, $groupId, &$created) {
        foreach ($targets as $r) {
            $rec = [
                'id'             => uuid(),
                'group_id'       => $groupId,
                'sender'         => $sender,
                'recipient'      => $r,
                'text'           => $text,
                'sent_at'        => $now,
                'notified_at'    => null,
                'notified_state' => null,
                'opened_at'      => null,
            ];
            $messages[] = $rec;
            $created[] = $rec;
        }
    });
    return $created;
}
