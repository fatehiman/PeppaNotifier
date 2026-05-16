<?php
/**
 * One-way timesheet backup receiver.
 *
 * Sender (the main host) POSTs the full contents of one
 * data/timesheets/{YYYYMM}-{user}.json file here. We overwrite the
 * corresponding file under our own data/timesheets/.
 *
 * Authentication: shared secret in the `X-Backup-Token` header
 * (compared with hash_equals against BACKUP_TOKEN in env.php).
 *
 * Filename: in the `X-Backup-File` header. Strictly validated as
 * `YYYYMM-username.json` so there is no chance of path traversal.
 *
 * Role guard: receiveData.php only acts as a receiver when this host
 * is NOT a sender. If BACKUP_URL is non-empty, requests are refused —
 * that way the same codebase deployed to both hosts cannot accidentally
 * be written to via this endpoint on the production side.
 */
declare(strict_types=1);
require __DIR__ . '/lib.php';

header('Content-Type: application/json');
header('Cache-Control: no-store');

function reject(int $code, string $err): void {
    http_response_code($code);
    echo json_encode(['error' => $err]);
    exit;
}

if (($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'POST') {
    reject(405, 'method_not_allowed');
}

if (defined('BACKUP_URL') && BACKUP_URL !== '') {
    reject(403, 'host_is_sender_not_receiver');
}

if (!defined('BACKUP_TOKEN') || BACKUP_TOKEN === '') {
    reject(503, 'backup_token_not_configured');
}

$token = '';
if (function_exists('getallheaders')) {
    foreach ((array)getallheaders() as $k => $v) {
        if (strcasecmp($k, 'X-Backup-Token') === 0) { $token = (string)$v; break; }
    }
}
if ($token === '') $token = (string)($_SERVER['HTTP_X_BACKUP_TOKEN'] ?? '');
if (!hash_equals(BACKUP_TOKEN, $token)) {
    reject(403, 'bad_token');
}

$filename = '';
if (function_exists('getallheaders')) {
    foreach ((array)getallheaders() as $k => $v) {
        if (strcasecmp($k, 'X-Backup-File') === 0) { $filename = (string)$v; break; }
    }
}
if ($filename === '') $filename = (string)($_SERVER['HTTP_X_BACKUP_FILE'] ?? '');
if (!preg_match('/^[0-9]{6}-[a-zA-Z0-9_-]+\.json$/', $filename)) {
    reject(400, 'bad_filename');
}

$body = file_get_contents('php://input');
if ($body === false || $body === '') reject(400, 'empty_body');

$decoded = json_decode($body, true);
if (!is_array($decoded)) reject(400, 'invalid_json');

ts_ensure_dir();
$path = TS_DIR . '/' . $filename;

// Atomic write: write to .tmp, rename over.
$tmp = $path . '.tmp.' . bin2hex(random_bytes(4));
if (file_put_contents($tmp, $body, LOCK_EX) === false) {
    reject(500, 'write_failed');
}
if (!rename($tmp, $path)) {
    @unlink($tmp);
    reject(500, 'rename_failed');
}

echo json_encode([
    'ok'    => true,
    'file'  => $filename,
    'bytes' => strlen($body),
    'count' => count($decoded),
]);
