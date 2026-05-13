<?php
declare(strict_types=1);
require __DIR__ . '/lib.php';

$on = state_get();

header('Content-Type: text/plain; charset=utf-8');
header('Cache-Control: no-store');

if ($on === 1) {
    http_response_code(200);
    echo "1";
} else {
    http_response_code(404);
    echo "0";
}
