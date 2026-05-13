<?php
declare(strict_types=1);
require __DIR__ . '/lib.php';

log_request();

function action_login(): void {
    $b = body_json();
    $username = trim((string)($b['username'] ?? ''));
    $password = (string)($b['password'] ?? '');
    if ($username === '' || $password === '') {
        send_json(['error' => 'missing credentials'], 400);
        return;
    }
    $matched = false;
    foreach (load_users() as $u) {
        if (($u['username'] ?? null) === $username && ($u['password'] ?? null) === $password) {
            $matched = true;
            break;
        }
    }
    if (!$matched) {
        send_json(['error' => 'invalid credentials'], 401);
        return;
    }
    $tok = new_token();
    $now = time();
    db_modify('sessions.json', function (array &$s) use ($tok, $username, $now) {
        $s[$tok] = [
            'user' => $username,
            'created' => $now,
            'last_sent_cursor' => $now,
        ];
    });
    db_modify('online.json', function (array &$o) use ($username, $now) {
        $o[$username] = $now;
    });
    send_json(['token' => $tok, 'user' => $username]);
}

function action_logout(): void {
    $tok = bearer_token();
    if ($tok !== null) {
        db_modify('sessions.json', function (array &$s) use ($tok) {
            unset($s[$tok]);
        });
    }
    http_response_code(204);
}

function action_poll(): void {
    $me = require_user();
    $tok = bearer_token();
    $now = time();
    $newMessages = [];
    $sentUpdates = [];

    db_modify('online.json', function (array &$o) use ($me, $now) {
        $o[$me] = $now;
    });

    db_modify('messages.json', function (array &$messages) use ($me, $now, &$newMessages) {
        prune_messages($messages, $now);
        foreach ($messages as &$m) {
            if (($m['recipient'] ?? '') !== $me) continue;
            if (($m['notified_at'] ?? null) !== null) continue;
            $age = $now - (int)($m['sent_at'] ?? 0);
            if ($age <= NOTIFY_WINDOW) {
                $m['notified_at'] = $now;
                $m['notified_state'] = 'notified';
                $newMessages[] = $m;
            } elseif ($age <= MISSED_WINDOW) {
                $m['notified_at'] = $now;
                $m['notified_state'] = 'missed';
                $newMessages[] = $m;
            } else {
                $m['notified_at'] = $now;
                $m['notified_state'] = 'missed';
            }
        }
        unset($m);
    });

    $cursor = 0;
    db_modify('sessions.json', function (array &$s) use ($tok, $now, &$cursor) {
        $cursor = (int)($s[$tok]['last_sent_cursor'] ?? 0);
        $s[$tok]['last_sent_cursor'] = $now;
    });
    foreach (db_read('messages.json') as $m) {
        if (($m['sender'] ?? '') !== $me) continue;
        $sAt = (int)($m['sent_at'] ?? 0);
        $nAt = (int)($m['notified_at'] ?? 0);
        $oAt = (int)($m['opened_at'] ?? 0);
        // Include freshly-sent (sAt >= cursor) so the sender sees their own
        // brand-new messages on the next poll without waiting for the recipient.
        if ($sAt >= $cursor || $nAt >= $cursor || $oAt >= $cursor) {
            $sentUpdates[] = $m;
        }
    }

    send_json([
        'new_messages'    => $newMessages,
        'sent_updates'    => $sentUpdates,
        'unread_count'    => count($newMessages),
        'server_time'     => $now,
        'my_muted_until'  => get_mute($me),
        'mutes'           => (object)active_mutes($now),
        'ts_state'        => ts_state_for($me, $now),
        'today_date'      => date('Y-m-d', $now),
    ]);
}

function action_mute(): void {
    $me = require_user();
    $b = body_json();
    $until = (int)($b['until'] ?? 0);
    $now = time();
    if ($until > 0 && $until < $now) $until = 0;
    $stored = set_mute($me, $until);
    send_json(['muted_until' => $stored]);
}

function action_ack_opened(): void {
    $me = require_user();
    $b = body_json();
    $ids = [];
    foreach ((array)($b['ids'] ?? []) as $id) {
        if (is_string($id) && $id !== '') $ids[] = $id;
    }
    if (empty($ids)) {
        http_response_code(204);
        return;
    }
    $now = time();
    db_modify('messages.json', function (array &$messages) use ($ids, $me, $now) {
        foreach ($messages as &$m) {
            if (!in_array(($m['id'] ?? ''), $ids, true)) continue;
            if (($m['recipient'] ?? '') !== $me) continue;
            if (($m['opened_at'] ?? null) === null) {
                $m['opened_at'] = $now;
            }
        }
        unset($m);
    });
    http_response_code(204);
}

function action_history(): void {
    $me = require_user();
    $now = time();
    $cutoff = $now - RETENTION_SECONDS;
    $out = [];
    foreach (db_read('messages.json') as $m) {
        if ((int)($m['sent_at'] ?? 0) < $cutoff) continue;
        if (($m['sender'] ?? '') === $me || ($m['recipient'] ?? '') === $me) {
            $out[] = $m;
        }
    }
    usort($out, fn($a, $b) => (int)($b['sent_at'] ?? 0) <=> (int)($a['sent_at'] ?? 0));
    send_json($out);
}

function action_users(): void {
    $me = require_user();
    $now = time();
    db_modify('online.json', function (array &$o) use ($me, $now) {
        $o[$me] = $now;
    });
    $online = db_read('online.json');
    $mutes = active_mutes($now);
    $out = [];
    foreach (all_usernames() as $u) {
        if ($u === $me) continue;
        $last = (int)($online[$u] ?? 0);
        $out[] = [
            'username'    => $u,
            'last_seen'   => $last,
            'online'      => ($last > 0 && ($now - $last) < ONLINE_WINDOW),
            'muted_until' => (int)($mutes[$u] ?? 0),
        ];
    }
    $out[] = ['username' => 'ALL', 'last_seen' => 0, 'online' => true, 'muted_until' => 0];
    send_json($out);
}

function action_send(): void {
    $me = require_user();
    $b = body_json();
    $recipient = trim((string)($b['recipient'] ?? ''));
    $text = (string)($b['text'] ?? '');
    if ($recipient === '' || $text === '') {
        send_json(['error' => 'missing fields'], 400);
        return;
    }
    if ($recipient === 'ALL') {
        $targets = array_values(array_filter(all_usernames(), fn($u) => $u !== $me));
    } else {
        if (!user_exists($recipient)) {
            send_json(['error' => 'unknown recipient'], 404);
            return;
        }
        if ($recipient === $me) {
            send_json(['error' => 'cannot send to self'], 400);
            return;
        }
        $targets = [$recipient];
    }
    if (empty($targets)) {
        send_json(['error' => 'no recipients'], 400);
        return;
    }
    $now = time();
    $groupId = uuid();
    $ids = [];
    $created = [];
    db_modify('messages.json', function (array &$messages) use ($me, $text, $now, $targets, $groupId, &$ids, &$created) {
        foreach ($targets as $r) {
            $id = uuid();
            $ids[] = $id;
            $rec = [
                'id'             => $id,
                'group_id'       => $groupId,
                'sender'         => $me,
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
    send_json([
        'ids'      => $ids,
        'group_id' => $groupId,
        'sent_at'  => $now,
        'messages' => $created,
    ]);
}

function action_state_get(): void {
    require_user();
    send_json(['on' => state_get()]);
}

function action_state_toggle(): void {
    $me = require_user();
    if ($me !== 'amir') {
        send_json(['error' => 'forbidden'], 403);
        return;
    }
    $new = state_get() === 1 ? 0 : 1;
    state_set($new);
    send_json(['on' => $new]);
}

function action_ts_state(): void {
    $me = require_user();
    $now = time();
    send_json([
        'state'      => ts_state_for($me, $now),
        'today_date' => date('Y-m-d', $now),
    ]);
}

function action_ts_toggle(): void {
    $me = require_user();
    $now = time();
    $today = date('Y-m-d', $now);
    $year  = (int)date('Y', $now);
    $month = (int)date('n', $now);

    // Server is the source of truth: derive the new state by flipping the current one.
    $current = ts_state_for($me, $now);
    $newKind = $current === 'started' ? 'stop' : 'start';
    $newState = $newKind === 'start' ? 'started' : 'stopped';

    // Store only the raw timestamp + kind. Date/time strings are derived from
    // ts on read, using TZ_VIEW — that way changing TZ_VIEW later reformats
    // every existing entry consistently.
    ts_append_user_month($year, $month, $me, [
        'ts'   => $now,
        'kind' => $newKind,
    ]);

    send_json([
        'state'      => $newState,
        'today_date' => $today,
    ]);
}

function action_ts_months(): void {
    require_user();
    $months = ts_list_months();
    // Always include the current month so the UI has at least one selectable option.
    $now = time();
    $cy = (int)date('Y', $now);
    $cm = (int)date('n', $now);
    $hasCurrent = false;
    foreach ($months as $m) {
        if ($m['year'] === $cy && $m['month'] === $cm) { $hasCurrent = true; break; }
    }
    if (!$hasCurrent) {
        array_unshift($months, ['year' => $cy, 'month' => $cm]);
    }
    send_json($months);
}

function action_ts_month(): void {
    require_user();
    $year  = (int)($_GET['year']  ?? date('Y'));
    $month = (int)($_GET['month'] ?? date('n'));
    if ($year < 2000 || $year > 2100 || $month < 1 || $month > 12) {
        send_json(['error' => 'bad year/month'], 400);
        return;
    }
    $raw = ts_read_month_all_users($year, $month);
    // Attach `date` and `time` strings derived from `ts` in TZ_VIEW. Any
    // pre-existing `date`/`time` fields on legacy entries are ignored, so
    // a future TZ_VIEW change rewrites every row consistently.
    $entries = [];
    foreach ($raw as $user => $list) {
        $entries[$user] = array_map(function ($e) {
            $ts = (int)($e['ts'] ?? 0);
            return [
                'ts'   => $ts,
                'kind' => (string)($e['kind'] ?? ''),
                'date' => $ts > 0 ? date('Y-m-d', $ts) : '',
                'time' => $ts > 0 ? date('H:i', $ts)   : '',
            ];
        }, $list);
    }
    send_json([
        'year'    => $year,
        'month'   => $month,
        'tz_view' => TZ_VIEW,
        'entries' => (object)$entries,
    ]);
}

$action = $_GET['action'] ?? '';
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

try {
    switch ("$method:$action") {
        case 'POST:login':       action_login(); break;
        case 'POST:logout':      action_logout(); break;
        case 'GET:poll':         action_poll(); break;
        case 'POST:ack_opened':  action_ack_opened(); break;
        case 'GET:history':      action_history(); break;
        case 'GET:users':        action_users(); break;
        case 'POST:send':        action_send(); break;
        case 'POST:mute':        action_mute(); break;
        case 'GET:ts_state':     action_ts_state(); break;
        case 'POST:ts_toggle':   action_ts_toggle(); break;
        case 'GET:ts_months':    action_ts_months(); break;
        case 'GET:ts_month':     action_ts_month(); break;
        case 'GET:state_get':    action_state_get(); break;
        case 'POST:state_toggle': action_state_toggle(); break;
        default:
            send_json(['error' => 'unknown action', 'method' => $method, 'action' => $action], 404);
    }
} catch (Throwable $e) {
    send_json(['error' => 'server', 'message' => $e->getMessage()], 500);
}
