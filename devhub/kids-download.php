<?php
declare(strict_types=1);
// Download route is hosted only inside Dev Hub's existing Basic-Auth protected documentroot.
header('Cache-Control: private, no-store');
header('X-Content-Type-Options: nosniff');
if (empty($_SERVER['REMOTE_USER']) && empty($_SERVER['PHP_AUTH_USER'])) {
    http_response_code(403);
    exit('Dev Hub authentication required.');
}
$base = '/home/vanawiwj/the-one-private-builds/kids';
$manifest = $base . '/latest.json';
if (!is_file($manifest)) {
    http_response_code(404);
    exit('Kids build not available.');
}
$meta = json_decode((string) file_get_contents($manifest), true);
if (!is_array($meta) || ($meta['id'] ?? '') !== 'kids') {
    http_response_code(404);
    exit('Kids build not available.');
}
$name = (string) ($meta['apk'] ?? '');
$sha = (string) ($meta['sha256'] ?? '');
if (!preg_match('/\\AThe-One-Kids-v[0-9.]+-[A-Za-z0-9._-]+\\.apk\\z/D', $name) ||
    !preg_match('/\\A[a-f0-9]{64}\\z/D', $sha)) {
    http_response_code(500);
    exit('Kids build metadata invalid.');
}
$file = $base . '/apks/' . $name;
if (!is_file($file) || !hash_equals($sha, (string) hash_file('sha256', $file))) {
    http_response_code(503);
    exit('Kids build integrity validation failed.');
}
header('Content-Type: application/vnd.android.package-archive');
header('Content-Disposition: attachment; filename="' . $name . '"');
header('Content-Length: ' . filesize($file));
header('X-Kids-Version: ' . (string) ($meta['version_name'] ?? ''));
readfile($file);
