<?php
declare(strict_types=1);

if (PHP_SAPI !== 'cli') {
    http_response_code(404);
    exit;
}

$home = getenv('HOME') ?: dirname(__DIR__, 2);
$root = rtrim($home, '/') . '/the-one-music-cache';
$filesDir = $root . '/files';
$metaDir = $root . '/meta';
$flag = $root . '/hub-adopted.flag';

if (is_file($flag)) {
    echo "HUB_ADOPT_ALREADY_DONE\n";
    exit(0);
}

$best = null;
$bestCount = -1;
foreach (glob($metaDir . '/*.json') ?: [] as $file) {
    if (str_ends_with($file, '.sync.json')) {
        continue;
    }
    $doc = json_decode((string) @file_get_contents($file), true);
    if (!is_array($doc)) {
        continue;
    }
    $name = mb_strtolower(trim((string) ($doc['device_name'] ?? '')));
    if ($name !== 'surface') {
        continue;
    }
    $count = is_array($doc['files'] ?? null) ? count($doc['files']) : 0;
    if ($count > $bestCount) {
        $best = $doc;
        $bestCount = $count;
    }
}

if (!is_array($best) || $bestCount < 1) {
    fwrite(STDERR, "No Surface catalog available for hub adoption\n");
    exit(2);
}

$srcDevice = trim((string) ($best['device_id'] ?? ''));
$srcStick = trim((string) ($best['stick_id'] ?? ''));
if ($srcDevice === '' || $srcStick === '') {
    fwrite(STDERR, "Surface catalog identity incomplete\n");
    exit(3);
}

$hubDevice = 'THEONE-HUB';
$hubStick = 'hub-primary';
$hub = $best;
$hub['device_id'] = $hubDevice;
$hub['device_name'] = 'THEONE-HUB';
$hub['stick_id'] = $hubStick;
$hub['online'] = true;
$hub['presence_updated_at'] = gmdate('c');
$hub['updated_at'] = gmdate('c');
$hub['adopted_from'] = [
    'device_id' => $srcDevice,
    'device_name' => (string) ($best['device_name'] ?? ''),
    'stick_id' => $srcStick,
    'at' => gmdate('c')
];

$cached = 0;
foreach ($hub['files'] as &$row) {
    if (!is_array($row)) {
        continue;
    }
    $path = trim((string) ($row['path'] ?? ''));
    $sha = strtolower(trim((string) ($row['sha256'] ?? '')));
    if ($path === '' || !preg_match('/^[a-f0-9]{64}$/', $sha)) {
        $row['cached'] = false;
        continue;
    }

    $destKey = hash('sha256', $hubDevice . "\n" . $hubStick . "\n" . $path);
    $dest = $filesDir . '/' . $destKey . '.bin';
    if (!is_file($dest)) {
        $pool = $filesDir . '/sha-' . $sha . '.bin';
        $srcKey = hash('sha256', $srcDevice . "\n" . $srcStick . "\n" . $path);
        $src = $filesDir . '/' . $srcKey . '.bin';

        if (is_file($pool)) {
            @link($pool, $dest);
        } elseif (is_file($src)) {
            @link($src, $dest);
        }
        if (is_file($dest)) {
            @chmod($dest, 0600);
        }
    }

    $row['cached'] = is_file($dest);
    if ($row['cached']) {
        $cached++;
    }
}
unset($row);

$total = count($hub['files']);
if ($cached !== $total) {
    fwrite(STDERR, "Hub adoption incomplete: cached=$cached total=$total\n");
    exit(4);
}

$target = $metaDir . '/' . $hubDevice . '__' . $hubStick . '.json';
$tmp = $target . '.tmp.' . bin2hex(random_bytes(4));
$json = json_encode($hub, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
if (!is_string($json) || @file_put_contents($tmp, $json, LOCK_EX) === false || !@rename($tmp, $target)) {
    @unlink($tmp);
    fwrite(STDERR, "Could not write hub catalog\n");
    exit(5);
}
@chmod($target, 0600);
@file_put_contents($flag, gmdate('c') . "\n", LOCK_EX);
@chmod($flag, 0600);

echo "HUB_ADOPT_DONE files=$total cached=$cached\n";
