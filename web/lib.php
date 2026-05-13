<?php
declare(strict_types=1);

const DATA_DIR = __DIR__ . '/data';
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
