<?php
declare(strict_types=1);

/**
 * Standalone authenticated Android/Google TV update feed.
 * Requires an owner-approved Family installation with a valid P-256 signature.
 * All releases remain in the private Dev Hub build directory.
 */
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

function family_update_reply(int $status, array $body): never {
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($body, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    exit;
}

$action = (string)($_GET['action'] ?? '');
if ($action === 'health') {
    family_update_reply(200, ['ok' => true, 'service' => 'The One Family Secure Updates']);
}
if ($_SERVER['REQUEST_METHOD'] !== 'POST' || !in_array($action, ['check', 'download'], true)) {
    family_update_reply(405, ['ok' => false, 'error' => 'Invalid method or operation']);
}
$json = file_get_contents('php://input');
if (!is_string($json) || strlen($json) > 8192) {
    family_update_reply(400, ['ok' => false, 'error' => 'Invalid request']);
}
$request = json_decode($json, true);
if (!is_array($request)) family_update_reply(400, ['ok' => false, 'error' => 'Invalid JSON']);

$package = (string)($request['package'] ?? '');
$packageToApp = [
    'com.theone.cinema' => ['cinema_android', 'cinema-phone'],
    'com.theone.cinema.tv' => ['cinema_android', 'cinema-google-tv'],
    'com.theone.series' => ['series_android', 'series-phone'],
    'com.theone.series.tv' => ['series_android', 'series-google-tv'],
    'com.theone.tv.phone' => ['tv_android', 'tv-phone'],
    'com.theone.tv' => ['tv_android', 'tv-google-tv'],
];
if (!array_key_exists($package, $packageToApp)) {
    family_update_reply(400, ['ok' => false, 'error' => 'Unknown package']);
}
[$scope, $appId] = $packageToApp[$package];
$deviceId = (string)($request['device_id'] ?? '');
if (!preg_match('/^fam-[a-f0-9]{48}$/D', $deviceId)) {
    family_update_reply(400, ['ok' => false, 'error' => 'Invalid installation']);
}
$home = dirname((string)($_SERVER['DOCUMENT_ROOT'] ?? __DIR__));
$registry = $home . '/the-one-remote-data/main-devices.json';
$raw = @file_get_contents($registry);
$state = is_string($raw) ? json_decode($raw, true) : null;
$device = is_array($state) ? ($state['devices'][$deviceId] ?? null) : null;
if (!is_array($device) || (string)($device['family_package'] ?? '') !== $package ||
    (bool)($device['blocked'] ?? false) ||
    !(bool)($device['access_rights'][$scope] ?? false)) {
    family_update_reply(403, ['ok' => false, 'error' => 'Installation not approved']);
}
$nonce = (string)($request['nonce'] ?? '');
$nonceBytes = base64_decode($nonce, true);
$signature = base64_decode((string)($request['signature'] ?? ''), true);
if ($nonceBytes === false || strlen($nonceBytes) !== 32 ||
    $signature === false || strlen($signature) < 60 || strlen($signature) > 256) {
    family_update_reply(403, ['ok' => false, 'error' => 'Invalid proof']);
}
$publicKey = openssl_pkey_get_public((string)($device['family_public_key'] ?? ''));
$message = $action . '|' . $deviceId . '|' . $scope . '|' . $nonce;
if ($publicKey === false ||
    openssl_verify($message, $signature, $publicKey, OPENSSL_ALGO_SHA256) !== 1) {
    family_update_reply(403, ['ok' => false, 'error' => 'Invalid signature']);
}
$buildRoot = $home . '/the-one-private-builds/2026-10-13';
$manifestPath = $buildRoot . '/manifest.json';
$manifestRaw = @file_get_contents($manifestPath);
$manifest = is_string($manifestRaw) ? json_decode($manifestRaw, true) : null;
if (!is_array($manifest)) family_update_reply(503, ['ok' => false, 'error' => 'Build manifest unavailable']);

$build = null;
foreach ($manifest as $candidate) {
    if (is_array($candidate) && (string)($candidate['id'] ?? '') === $appId) {
        $build = $candidate;
        break;
    }
}
if (!is_array($build) || (string)($build['dev_hub_status'] ?? '') !== 'published') {
    family_update_reply(404, ['ok' => false, 'error' => 'No published build']);
}
$apkName = (string)($build['apk'] ?? '');
$expectedDigest = (string)($build['sha256'] ?? '');
$badging = (string)($build['package_badging'] ?? '');
if (!preg_match('/^[a-zA-Z0-9._-]+\.apk$/D', $apkName) ||
    !preg_match('/^[a-f0-9]{64}$/D', $expectedDigest) ||
    !preg_match("/package:\s+name='".preg_quote($package, '/')."'\s+versionCode='([0-9]+)'/", $badging, $badge)) {
    family_update_reply(503, ['ok' => false, 'error' => 'Unverified build metadata']);
}
$apkDir = realpath($buildRoot . '/apks');
$apkPath = realpath($buildRoot . '/apks/' . $apkName);
if ($apkDir === false || $apkPath === false ||
    dirname($apkPath) !== $apkDir || !is_file($apkPath)) {
    family_update_reply(404, ['ok' => false, 'error' => 'Build not found']);
}
$details = [
    'ok' => true,
    'package' => $package,
    'version_name' => (string)($build['version_name'] ?? ''),
    'version_code' => (int)$badge[1],
    'size_bytes' => filesize($apkPath),
    'sha256' => $expectedDigest
];
if ($action === 'check') family_update_reply(200, $details);
if (!hash_equals($expectedDigest, (string)hash_file('sha256', $apkPath))) {
    family_update_reply(503, ['ok' => false, 'error' => 'Build integrity failure']);
}
header('Content-Type: application/vnd.android.package-archive');
header('Content-Disposition: attachment; filename="' . $apkName . '"');
header('Content-Length: ' . filesize($apkPath));
header('X-Family-APK-SHA256: ' . $expectedDigest);
readfile($apkPath);
exit;
