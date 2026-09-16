<?php
session_start();
if (empty($_SESSION['csrf'])) $_SESSION['csrf'] = bin2hex(random_bytes(32));
$core = '/home/vanawiwj/devhub-core';
require_once $core . '/git.php';

$message = '';
$error = '';

function csrf_ok(): bool {
    $sent = $_POST['csrf'] ?? '';
    $expected = $_SESSION['csrf'] ?? '';
    return $sent !== '' && $expected !== '' && hash_equals($expected, $sent);
}

if ($_SERVER['REQUEST_METHOD'] === 'POST' && ($_POST['action'] ?? '') === 'build') {
    if (!csrf_ok()) {
        $error = 'Ongeldige beveiligingstoken. Ververs de pagina.';
    } else {
        try {
            $branch = trim(gitRun('branch --show-current'));
            if ($branch !== 'main') throw new RuntimeException('De repository moet op main staan voordat je een build start.');
            if (trim(gitRun('status --short')) !== '') throw new RuntimeException('Er zijn lokale wijzigingen. Commit of herstel die eerst.');
            gitRun('pull --ff-only origin main');
            $stamp = date('Y-m-d H:i:s');
            gitRun('commit --allow-empty -m ' . escapeshellarg('Build requested from The One Dev Hub - ' . $stamp));
            gitRun('push origin main');
            $message = 'Nieuwe Android build is gestart via GitHub Actions.';
        } catch (Throwable $e) {
            $error = $e->getMessage();
        }
    }
}

try { gitRun('fetch --tags --quiet origin'); } catch (Throwable $e) {}
$branch = trim(gitRun('branch --show-current'));
$head = trim(gitRun('rev-parse HEAD'));
$commit = trim(gitRun('log -1 --oneline'));
$status = trim(gitRun('status --short'));
$tagsRaw = trim(gitRun('for-each-ref --sort=-version:refname --format=%(refname:short) refs/tags/v*'));
$tags = $tagsRaw === '' ? [] : array_values(array_filter(preg_split('/\R/', $tagsRaw)));
$latestTag = $tags[0] ?? '';
$tagCommit = $latestTag !== '' ? trim(gitRun('rev-list -n 1 ' . escapeshellarg($latestTag))) : '';
$buildReady = $latestTag !== '' && $tagCommit === $head;
$recentTags = array_slice($tags, 0, 8);
?>
<!doctype html>
<html lang="nl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Builds • The One Dev Hub</title>
<style>
:root{--bg:#0a0d12;--panel:#111722;--panel2:#171f2d;--line:#283447;--text:#f5f7fb;--muted:#91a0b7;--accent:#6c8cff;--ok:#48d597;--warn:#ffbf5f;--danger:#ff6b75}*{box-sizing:border-box}body{margin:0;background:linear-gradient(180deg,#090c11,#0d121a);color:var(--text);font-family:Inter,system-ui,sans-serif;min-height:100vh}a{color:inherit;text-decoration:none}.wrap{max-width:1050px;margin:0 auto;padding:28px}.top{display:flex;align-items:center;justify-content:space-between;gap:14px;margin-bottom:24px}.top h1{margin:0;font-size:26px}.top p{margin:5px 0 0;color:var(--muted)}.btn{display:inline-block;border:1px solid var(--line);background:#151c28;color:var(--text);border-radius:11px;padding:11px 15px;font-weight:800;cursor:pointer}.btn.primary{background:linear-gradient(135deg,#607cff,#4c6df2);border-color:#7189ff}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.card{background:var(--panel);border:1px solid var(--line);border-radius:16px;padding:20px}.card h2{font-size:16px;margin:0 0 13px}.big{font-size:21px;font-weight:900}.muted{color:var(--muted)}.ok{color:var(--ok)}.warn{color:var(--warn)}.bad{color:var(--danger)}.notice{padding:13px 15px;border-radius:12px;margin-bottom:18px;border:1px solid var(--line);background:var(--panel2)}.notice.ok{border-color:#295d43}.notice.bad{border-color:#6b3439}.tags{display:flex;flex-wrap:wrap;gap:8px}.tag{background:#1b2535;border:1px solid #2f3d53;border-radius:999px;padding:7px 10px;font-size:13px}.actions{display:flex;gap:10px;flex-wrap:wrap;margin-top:16px}code{color:#cbd6e6}@media(max-width:760px){.grid{grid-template-columns:1fr}.top{align-items:flex-start;flex-direction:column}}
</style>
</head>
<body><div class="wrap">
<div class="top"><div><h1>⚒ Android Builds</h1><p>The One Dev Hub • GitHub Actions build control</p></div><a class="btn" href="index.php">← Terug naar editor</a></div>
<?php if ($message): ?><div class="notice ok"><?=htmlspecialchars($message)?></div><?php endif; ?>
<?php if ($error): ?><div class="notice bad"><?=htmlspecialchars($error)?></div><?php endif; ?>
<div class="grid">
  <div class="card"><h2>Repository</h2><div class="big"><?=htmlspecialchars($branch ?: 'onbekend')?></div><p class="muted"><code><?=htmlspecialchars($commit)?></code></p><p class="<?= $status===''?'ok':'warn' ?>"><?= $status===''?'● Working tree clean':'● Lokale wijzigingen aanwezig' ?></p></div>
  <div class="card"><h2>Laatste Android build</h2><?php if($latestTag===''): ?><div class="big warn">Nog geen build-tag gevonden</div><?php else: ?><div class="big"><?=htmlspecialchars($latestTag)?></div><p class="<?= $buildReady?'ok':'warn' ?>"><?= $buildReady?'✓ Laatste commit heeft een afgeronde release':'● Laatste commit heeft nog geen afgeronde release' ?></p><?php endif; ?></div>
  <div class="card"><h2>Nieuwe build starten</h2><p class="muted">Dit maakt een lege commit op <strong>main</strong> en pusht die naar GitHub. De bestaande workflow bouwt daarna automatisch The One en The One Car.</p><form method="post"><input type="hidden" name="csrf" value="<?=htmlspecialchars($_SESSION['csrf'])?>"><input type="hidden" name="action" value="build"><button class="btn primary" type="submit">▶ Start nieuwe Android build</button></form></div>
  <div class="card"><h2>Recente releases</h2><?php if(!$recentTags): ?><p class="muted">Nog geen tags gevonden.</p><?php else: ?><div class="tags"><?php foreach($recentTags as $tag): ?><span class="tag"><?=htmlspecialchars($tag)?></span><?php endforeach; ?></div><?php endif; ?><div class="actions"><a class="btn" target="_blank" rel="noopener" href="https://github.com/Rubenvaggelen/apps/actions/workflows/build-apk.yml">GitHub Actions ↗</a><a class="btn" target="_blank" rel="noopener" href="https://github.com/Rubenvaggelen/apps/releases">Releases ↗</a></div></div>
</div>
</div></body></html>
