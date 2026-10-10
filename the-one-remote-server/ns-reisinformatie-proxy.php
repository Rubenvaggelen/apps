<?php
declare(strict_types=1);

/**
 * The One Family NS Reisinformatie proxy.
 *
 * The NS subscription key MUST be kept in a private server-side file:
 *   dirname($_SERVER['DOCUMENT_ROOT']) . '/the-one-remote-data/ns-subscription.key'
 * Permissions 0600, not under public_html; never commit the key.
 *
 * The public GitHub Pages PWA calls this limited, rate-limited JSON proxy.
 * No arbitrary upstream URL or HTTP header is accepted from the client.
 */
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');
header('Referrer-Policy: no-referrer');

$allowedOrigins = [
    'https://rubenvanaggelen.github.io',
    'https://rubenvanaggelen.com',
    'https://www.rubenvanaggelen.com',
];
$origin = trim((string)($_SERVER['HTTP_ORIGIN'] ?? ''));
if ($origin !== '') {
    if (!in_array($origin, $allowedOrigins, true)) {
        http_response_code(403);
        echo '{"error":"origin_not_allowed"}';
        exit;
    }
    header('Access-Control-Allow-Origin: ' . $origin);
    header('Vary: Origin');
    header('Access-Control-Allow-Methods: GET, OPTIONS');
}
if (($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'OPTIONS') {
    http_response_code(204);
    exit;
}
if (($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'GET') {
    header('Allow: GET, OPTIONS');
    http_response_code(405);
    echo '{"error":"method_not_allowed"}';
    exit;
}

function ns_fail(int $status, string $error): never {
    http_response_code($status);
    echo json_encode(['error' => $error], JSON_UNESCAPED_SLASHES);
    exit;
}

$resource = trim((string)($_GET['resource'] ?? ''));
$params = [];
switch ($resource) {
    case 'stations':
        $path = '/reisinformatie-api/api/v2/stations';
        break;
    case 'trips':
        $path = '/reisinformatie-api/api/v3/trips';
        $from = trim((string)($_GET['fromStation'] ?? ''));
        $to = trim((string)($_GET['toStation'] ?? ''));
        $when = trim((string)($_GET['dateTime'] ?? ''));
        if (!preg_match('/^[A-Za-z0-9_-]{2,12}$/D', $from) ||
            !preg_match('/^[A-Za-z0-9_-]{2,12}$/D', $to) ||
            strlen($when) > 48 ||
            !preg_match('/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?(?:Z|[+-]\d\d:\d\d)$/D', $when)) {
            ns_fail(400, 'invalid_trip_parameters');
        }
        $params = ['fromStation' => $from, 'toStation' => $to, 'dateTime' => $when];
        if (isset($_GET['searchForArrival'])) {
            $arrival = strtolower(trim((string)$_GET['searchForArrival']));
            if ($arrival !== 'true' && $arrival !== 'false') ns_fail(400, 'invalid_search_direction');
            $params['searchForArrival'] = $arrival;
        }
        break;
    default:
        ns_fail(400, 'unsupported_resource');
}

$home = dirname((string)($_SERVER['DOCUMENT_ROOT'] ?? __DIR__));
$dataDir = $home . '/the-one-remote-data';
$secretPath = $dataDir . '/ns-subscription.key';
if (!is_file($secretPath) || !is_readable($secretPath)) {
    ns_fail(503, 'ns_service_not_configured');
}
$key = trim((string)file_get_contents($secretPath));
if (!preg_match('/^[a-fA-F0-9]{32}$/D', $key)) ns_fail(503, 'ns_service_not_configured');

$rateDir = $dataDir . '/ns-proxy-rates';
if (!is_dir($rateDir) && !@mkdir($rateDir, 0700, true) && !is_dir($rateDir)) {
    ns_fail(503, 'service_unavailable');
}
$ip = trim((string)($_SERVER['REMOTE_ADDR'] ?? 'unknown'));
$rateFile = $rateDir . '/' . hash('sha256', $ip) . '.json';
$fd = @fopen($rateFile, 'c+');
if (!$fd || !flock($fd, LOCK_EX)) ns_fail(503, 'service_unavailable');
$previous = stream_get_contents($fd);
$state = json_decode($previous ?: 'null', true);
$now = time();
$windowStarted = is_array($state) ? (int)($state['started'] ?? 0) : 0;
$requests = is_array($state) ? (int)($state['requests'] ?? 0) : 0;
if ($windowStarted <= 0 || $now - $windowStarted >= 600) {
    $windowStarted = $now;
    $requests = 0;
}
if ($requests >= 90) {
    flock($fd, LOCK_UN);
    fclose($fd);
    header('Retry-After: 600');
    ns_fail(429, 'rate_limit');
}
++$requests;
rewind($fd);
ftruncate($fd, 0);
fwrite($fd, json_encode(['started' => $windowStarted, 'requests' => $requests], JSON_THROW_ON_ERROR));
fflush($fd);
flock($fd, LOCK_UN);
fclose($fd);
@chmod($rateFile, 0600);

$cacheDir = $dataDir . '/ns-proxy-cache';
if (!is_dir($cacheDir) && !@mkdir($cacheDir, 0700, true) && !is_dir($cacheDir)) {
    ns_fail(503, 'service_unavailable');
}
$queryString = $params === [] ? '' : '?' . http_build_query($params, '', '&', PHP_QUERY_RFC3986);
$cachePath = $cacheDir . '/' . hash('sha256', $resource . $queryString) . '.json';
$ttl = $resource === 'stations' ? 24 * 60 * 60 : 45;
if (is_file($cachePath) && (int)filemtime($cachePath) >= $now - $ttl) {
    $cached = @file_get_contents($cachePath);
    if ($cached !== false && json_validate($cached)) {
        echo $cached;
        exit;
    }
}
if (!function_exists('curl_init')) ns_fail(503, 'service_unavailable');

$handle = curl_init('https://gateway.apiportal.ns.nl' . $path . $queryString);
if (!$handle) ns_fail(503, 'service_unavailable');
curl_setopt_array($handle, [
    CURLOPT_HTTPGET => true,
    CURLOPT_RETURNTRANSFER => true,
    CURLOPT_TIMEOUT => 15,
    CURLOPT_CONNECTTIMEOUT => 6,
    CURLOPT_FOLLOWLOCATION => false,
    CURLOPT_SSL_VERIFYPEER => true,
    CURLOPT_HTTPHEADER => [
        'Accept: application/json',
        'Ocp-Apim-Subscription-Key: ' . $key,
    ],
]);
$json = curl_exec($handle);
$status = (int)curl_getinfo($handle, CURLINFO_HTTP_CODE);
curl_close($handle);
unset($key);
if (!is_string($json) || $status !== 200 || !json_validate($json)) {
    ns_fail(502, 'ns_upstream_unavailable');
}
$tmp = $cachePath . '.' . bin2hex(random_bytes(5));
if (@file_put_contents($tmp, $json, LOCK_EX) !== false) {
    @chmod($tmp, 0600);
    if (!@rename($tmp, $cachePath)) @unlink($tmp);
}
echo $json;
