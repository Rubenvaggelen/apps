<?php
declare(strict_types=1);

if (PHP_SAPI !== 'cli') {
    fwrite(STDERR, "CLI only\n");
    exit(2);
}

$home = getenv('HOME') ?: dirname(__DIR__, 2);
$root = rtrim($home, '/') . '/the-one-music-cache';
$filesDir = $root . '/files';
$metaDir = $root . '/meta';

if (!is_dir($filesDir) || !is_dir($metaDir)) {
    fwrite(STDERR, "Shared Media cache not found\n");
    exit(1);
}

$canonicalBySha = [];
$relinked = 0;
$poolLinks = 0;
$skipped = 0;
$bytesSaved = 0;

function cache_key(string $device, string $stick, string $path): string {
    return hash('sha256', $device . "\n" . $stick . "\n" . $path);
}

foreach (glob($metaDir . '/*.json') ?: [] as $metaFile) {
    if (str_ends_with($metaFile, '.sync.json') || str_contains($metaFile, '.tmp.')) continue;

    $raw = @file_get_contents($metaFile);
    $doc = is_string($raw) ? json_decode($raw, true) : null;
    if (!is_array($doc)) continue;

    $device = trim((string)($doc['device_id'] ?? ''));
    $stick = trim((string)($doc['stick_id'] ?? ''));
    if ($device === '' || $stick === '') continue;

    foreach ((array)($doc['files'] ?? []) as $row) {
        if (!is_array($row)) continue;
        $path = trim((string)($row['path'] ?? ''));
        $sha = strtolower(trim((string)($row['sha256'] ?? '')));
        if ($path === '' || !preg_match('/^[a-f0-9]{64}$/', $sha)) continue;

        $file = $filesDir . '/' . cache_key($device, $stick, $path) . '.bin';
        if (!is_file($file)) continue;

        $pool = $filesDir . '/sha-' . $sha . '.bin';

        if (!isset($canonicalBySha[$sha])) {
            $canonicalBySha[$sha] = $file;

            if (!is_file($pool)) {
                if (@link($file, $pool)) {
                    @chmod($pool, 0600);
                    $poolLinks++;
                }
            }
            continue;
        }

        $canonical = is_file($pool) ? $pool : $canonicalBySha[$sha];
        if (!is_file($canonical)) {
            $canonicalBySha[$sha] = $file;
            continue;
        }

        $a = @stat($canonical);
        $b = @stat($file);
        if (is_array($a) && is_array($b) && ($a['ino'] ?? null) === ($b['ino'] ?? null)) {
            continue;
        }

        $size = (int)@filesize($file);
        $tmp = $file . '.dedupe.' . bin2hex(random_bytes(4));
        if (@link($canonical, $tmp) && @rename($tmp, $file)) {
            @chmod($file, 0600);
            $relinked++;
            $bytesSaved += max(0, $size);
        } else {
            @unlink($tmp);
            $skipped++;
        }

        if (!is_file($pool) && @link($canonical, $pool)) {
            @chmod($pool, 0600);
            $poolLinks++;
        }
    }
}

foreach (glob($filesDir . '/*.dedupe.*') ?: [] as $tmp) {
    @unlink($tmp);
}

echo "CACHE_DEDUPE relinked={$relinked} pool_links={$poolLinks} skipped={$skipped} bytes_saved={$bytesSaved}\n";
