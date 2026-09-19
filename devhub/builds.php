<?php
session_start();
if (empty($_SESSION['csrf'])) $_SESSION['csrf'] = bin2hex(random_bytes(32));
$core = '/home/vanawiwj/devhub-core';
require_once $core . '/git.php';

$message = (string)($_SESSION['build_flash_message'] ?? '');
$error = (string)($_SESSION['build_flash_error'] ?? '');
unset($_SESSION['build_flash_message'], $_SESSION['build_flash_error']);

function csrf_ok(): bool {
    $sent = $_POST['csrf'] ?? '';
    $expected = $_SESSION['csrf'] ?? '';
    return $sent !== '' && $expected !== '' && hash_equals($expected, $sent);
}

function redirect_after_post(): never {
    header('Location: builds.php', true, 303);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] === 'POST' && ($_POST['action'] ?? '') === 'build') {
    if (!csrf_ok()) {
        $_SESSION['build_flash_error'] = 'Ongeldige beveiligingstoken. Ververs de pagina.';
        redirect_after_post();
    }

    try {
        $branchNow = trim(gitRun('branch --show-current'));
        if ($branchNow !== 'main') throw new RuntimeException('De repository moet op main staan voordat je een build start.');
        if (trim(gitRun('status --short')) !== '') throw new RuntimeException('Er zijn lokale wijzigingen. Commit of herstel die eerst.');
        gitRun('pull --ff-only origin main');
        try { gitRun('fetch --tags --quiet origin'); } catch (Throwable $e) {}

        $headNow = trim(gitRun('rev-parse HEAD'));
        $headMessage = trim(gitRun('log -1 --pretty=%s'));
        $latestTagNow = trim(gitRun('tag --list ' . escapeshellarg('v*') . ' --sort=-version:refname | head -1'));
        $tagCommitNow = $latestTagNow !== '' ? trim(gitRun('rev-list -n 1 ' . escapeshellarg($latestTagNow))) : '';
        if ($headNow !== $tagCommitNow && str_starts_with($headMessage, 'Build requested from The One Dev Hub')) {
            throw new RuntimeException('Er staat al een buildaanvraag open. Wacht tot die build is afgerond voordat je opnieuw start.');
        }

        $stamp = date('Y-m-d H:i:s');
        gitRun('commit --allow-empty -m ' . escapeshellarg('Build requested from The One Dev Hub - ' . $stamp));
        gitRun('push origin main');
        $_SESSION['build_flash_message'] = 'Nieuwe Android build is gestart via GitHub Actions.';
    } catch (Throwable $e) {
        $_SESSION['build_flash_error'] = $e->getMessage();
    }

    redirect_after_post();
}

try { gitRun('fetch --tags --quiet origin'); } catch (Throwable $e) {}
$branch = trim(gitRun('branch --show-current'));
$head = trim(gitRun('rev-parse HEAD'));
$commit = trim(gitRun('log -1 --oneline'));
$status = trim(gitRun('status --short'));
$tagsRaw = trim(gitRun('tag --list ' . escapeshellarg('v*') . ' --sort=-version:refname'));
$tags = $tagsRaw === '' ? [] : array_values(array_filter(preg_split('/\R/', $tagsRaw)));
$latestTag = $tags[0] ?? '';
$tagCommit = $latestTag !== '' ? trim(gitRun('rev-list -n 1 ' . escapeshellarg($latestTag))) : '';
$buildReady = $latestTag !== '' && $tagCommit === $head;
$recentTags = array_slice($tags, 0, 8);
$appDownload = $latestTag !== '' ? 'https://github.com/Rubenvaggelen/apps/releases/download/' . rawurlencode($latestTag) . '/app-debug.apk' : '';
$carDownload = $latestTag !== '' ? 'https://github.com/Rubenvaggelen/apps/releases/download/' . rawurlencode($latestTag) . '/carradio-debug.apk' : '';
$tvDownload = 'https://github.com/Rubenvaggelen/apps/releases/download/media-player-tv-latest/The-One-Media-Player-TV.apk';
?>
<!doctype html>
<html lang="nl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Builds • The One Dev Hub</title>
<link rel="icon" href="brand-logo.php">
<style>
:root{--bg:#06101b;--panel:#0d1929;--panel2:#112238;--line:#25405f;--text:#f6fbff;--muted:#91a9c5;--accent:#18c8ff;--accent2:#2f63ff;--ok:#46e0a3;--warn:#ffbd59;--danger:#ff6f7d;--shadow:0 20px 60px rgba(0,0,0,.28)}*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 18% -8%,#0e3e74 0,transparent 32%),linear-gradient(180deg,#06101b,#0a1321 55%,#08111d);color:var(--text);font-family:Inter,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;min-height:100vh}a{color:inherit;text-decoration:none}.wrap{max-width:1160px;margin:0 auto;padding:28px}.top{display:flex;align-items:center;justify-content:space-between;gap:18px;margin-bottom:22px}.brand{display:flex;align-items:center;gap:18px}.brand-logo{width:132px;height:98px;object-fit:cover;border-radius:18px;border:1px solid #1e587a;box-shadow:0 0 34px rgba(22,199,255,.24)}.brand h1{margin:0;font-size:30px}.brand p{margin:6px 0 0;color:var(--muted)}.btn{display:inline-block;border:1px solid var(--line);background:#101d2f;color:var(--text);border-radius:12px;padding:11px 15px;font-weight:800;cursor:pointer;transition:.18s}.btn:hover{transform:translateY(-1px);border-color:#4477a6}.btn.primary{background:linear-gradient(135deg,#168fff,#2f63ff);border-color:#4f88ff;box-shadow:0 0 22px rgba(47,99,255,.22)}.btn.ok{background:#123728;border-color:#2a6b4e;color:#9df2c8}.btn[disabled]{opacity:.55;cursor:not-allowed;transform:none}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.card{background:linear-gradient(180deg,rgba(16,30,49,.98),rgba(11,22,37,.98));border:1px solid var(--line);border-radius:18px;padding:22px;box-shadow:var(--shadow)}.card.wide{grid-column:1/-1}.card h2{font-size:16px;margin:0 0 14px}.big{font-size:24px;font-weight:900}.muted{color:var(--muted)}.ok{color:var(--ok)}.warn{color:var(--warn)}.bad{color:var(--danger)}.notice{padding:13px 15px;border-radius:12px;margin-bottom:18px;border:1px solid var(--line);background:var(--panel2)}.notice.ok{border-color:#2a6b4e;color:#a4f3cb}.notice.bad{border-color:#6b3439;color:#ffc5cc}.tags{display:flex;flex-wrap:wrap;gap:8px}.tag{background:#13243a;border:1px solid #315172;border-radius:999px;padding:7px 11px;font-size:13px}.actions{display:flex;gap:10px;flex-wrap:wrap;margin-top:16px}.statusline{display:flex;align-items:center;gap:10px}.statusdot{width:12px;height:12px;border-radius:50%;background:#617792;box-shadow:0 0 0 5px rgba(97,119,146,.12)}.statusdot.busy{background:var(--warn);box-shadow:0 0 0 5px rgba(255,189,89,.12)}.statusdot.ok{background:var(--ok);box-shadow:0 0 0 5px rgba(70,224,163,.12)}.statusdot.bad{background:var(--danger);box-shadow:0 0 0 5px rgba(255,111,125,.12)}.statusname{font-size:22px;font-weight:900}.tiny{font-size:12px;color:#718ba8}.runmeta{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;margin-top:16px}.mini{padding:12px;background:#0b1726;border:1px solid #1f3651;border-radius:12px}.mini strong{display:block;font-size:12px;color:#7590ad;margin-bottom:5px}.mini span{font-weight:800}.download-row{display:flex;gap:10px;flex-wrap:wrap;margin-top:16px}code{color:#cbd9e9}.build-copy{line-height:1.55}.spinner{display:none;width:16px;height:16px;border:2px solid #6f7f95;border-top-color:var(--warn);border-radius:50%;animation:spin .8s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}@media(max-width:760px){.wrap{padding:18px}.grid{grid-template-columns:1fr}.card.wide{grid-column:auto}.top{align-items:flex-start;flex-direction:column}.brand-logo{width:110px;height:82px}.runmeta{grid-template-columns:1fr}.brand h1{font-size:25px}}
</style>
</head>
<body>
<div class="wrap">
  <div class="top">
    <div class="brand"><img class="brand-logo" src="brand-logo.php" alt="The One Dev Hub"><div><h1>Android Builds</h1><p>The One Dev Hub • GitHub Actions build control</p></div></div>
    <a class="btn" href="index.php">← Terug naar editor</a>
  </div>

  <?php if ($message): ?><div class="notice ok"><?=htmlspecialchars($message)?></div><?php endif; ?>
  <?php if ($error): ?><div class="notice bad"><?=htmlspecialchars($error)?></div><?php endif; ?>

  <div class="grid">
    <div class="card">
      <h2>Repository</h2>
      <div class="big"><?=htmlspecialchars($branch ?: 'onbekend')?></div>
      <p class="muted"><code><?=htmlspecialchars($commit)?></code></p>
      <p class="<?= $status===''?'ok':'warn' ?>"><?= $status===''?'● Working tree clean':'● Lokale wijzigingen aanwezig' ?></p>
    </div>

    <div class="card" id="liveCard">
      <h2>Live buildstatus</h2>
      <div class="statusline"><span class="statusdot" id="liveDot"></span><span class="spinner" id="spinner"></span><span class="statusname" id="liveStatus">Status laden…</span></div>
      <p class="muted" id="liveText">GitHub Actions wordt gecontroleerd.</p>
      <div class="runmeta">
        <div class="mini"><strong>Build</strong><span id="runNumber">—</span></div>
        <div class="mini"><strong>Branch</strong><span id="runBranch">main</span></div>
        <div class="mini"><strong>Laatst gecontroleerd</strong><span id="checkedAt">—</span></div>
      </div>
      <div class="actions"><a class="btn" id="runLink" href="https://github.com/Rubenvaggelen/apps/actions/workflows/build-apk.yml" target="_blank" rel="noopener">GitHub Actions ↗</a></div>
    </div>

    <div class="card">
      <h2>Laatste afgeronde release</h2>
      <?php if($latestTag===''): ?>
        <div class="big warn" id="releaseVersion">Nog geen build-tag gevonden</div>
      <?php else: ?>
        <div class="big" id="releaseVersion"><?=htmlspecialchars($latestTag)?></div>
        <p class="<?= $buildReady?'ok':'warn' ?>" id="releaseState"><?= $buildReady?'✓ Deze release hoort bij de nieuwste commit':'● Nieuwere commit/build aanwezig; dit is de laatste afgeronde release' ?></p>
        <div class="download-row"><a class="btn ok" id="appDownload" href="<?=htmlspecialchars($appDownload)?>">⬇ The One APK</a><a class="btn ok" id="carDownload" href="<?=htmlspecialchars($carDownload)?>">⬇ The One Car APK</a><a class="btn ok" href="<?=htmlspecialchars($tvDownload)?>">⬇ Media Player Android TV APK</a></div>
      <?php endif; ?>
    </div>

    <div class="card">
      <h2>Nieuwe build starten</h2>
      <p class="muted build-copy">Start één nieuwe GitHub Actions-build voor <strong>The One</strong> en <strong>The One Car</strong>. De knop wordt automatisch geblokkeerd zolang GitHub nog aan het bouwen is.</p>
      <form method="post" id="buildForm"><input type="hidden" name="csrf" value="<?=htmlspecialchars($_SESSION['csrf'])?>"><input type="hidden" name="action" value="build"><button class="btn primary" id="buildBtn" type="submit">▶ Start nieuwe Android build</button></form>
    </div>

    <div class="card wide">
      <h2>Recente releases</h2>
      <?php if(!$recentTags): ?><p class="muted">Nog geen tags gevonden.</p><?php else: ?><div class="tags"><?php foreach($recentTags as $tag): ?><span class="tag"><?=htmlspecialchars($tag)?></span><?php endforeach; ?></div><?php endif; ?>
      <div class="actions"><a class="btn" target="_blank" rel="noopener" href="https://github.com/Rubenvaggelen/apps/actions/workflows/build-apk.yml">Alle builds ↗</a><a class="btn" target="_blank" rel="noopener" href="https://github.com/Rubenvaggelen/apps/releases">Alle releases ↗</a></div>
    </div>
  </div>
</div>
<script>
const apiUrl='https://api.github.com/repos/Rubenvaggelen/apps/actions/workflows/build-apk.yml/runs?branch=main&per_page=1';
let wasBusy=false;
let polls=0;
function setLive(kind,title,text,run){const dot=document.getElementById('liveDot');const spin=document.getElementById('spinner');const status=document.getElementById('liveStatus');dot.className='statusdot'+(kind?' '+kind:'');spin.style.display=kind==='busy'?'inline-block':'none';status.textContent=title;document.getElementById('liveText').textContent=text;document.getElementById('runNumber').textContent=run?'#'+run.run_number:'—';document.getElementById('runBranch').textContent=run?.head_branch||'main';document.getElementById('checkedAt').textContent=new Date().toLocaleTimeString('nl-NL',{hour:'2-digit',minute:'2-digit',second:'2-digit'});const link=document.getElementById('runLink');if(run?.html_url)link.href=run.html_url}
function setBuildButton(busy){const btn=document.getElementById('buildBtn');btn.disabled=busy;btn.textContent=busy?'Build bezig…':'▶ Start nieuwe Android build'}
async function loadLive(){polls++;try{const r=await fetch(apiUrl+'&t='+Date.now(),{cache:'no-store',headers:{Accept:'application/vnd.github+json'}});if(!r.ok)throw new Error('HTTP '+r.status);const j=await r.json();const run=j.workflow_runs&&j.workflow_runs[0];if(!run){setLive('','Geen builds','Er zijn nog geen GitHub Actions-runs gevonden.',null);setBuildButton(false);return}const busy=run.status==='queued'||run.status==='in_progress';setBuildButton(busy);if(busy){wasBusy=true;setLive('busy',run.status==='queued'?'In wachtrij':'Bezig met bouwen','The One en The One Car worden nu gebouwd.',run);if(polls<25)setTimeout(loadLive,20000);return}if(run.conclusion==='success'){setLive('ok','Geslaagd','Build '+run.run_number+' is succesvol afgerond.',run);const version='v'+run.run_number;const release=document.getElementById('releaseVersion');if(release)release.textContent=version;const state=document.getElementById('releaseState');if(state){state.textContent='✓ Laatste GitHub-build is afgerond';state.className='ok'}const app=document.getElementById('appDownload');const car=document.getElementById('carDownload');if(app)app.href='https://github.com/Rubenvaggelen/apps/releases/download/'+version+'/app-debug.apk';if(car)car.href='https://github.com/Rubenvaggelen/apps/releases/download/'+version+'/carradio-debug.apk';if(wasBusy)setTimeout(()=>location.reload(),2500)}else{setLive('bad','Mislukt',run.conclusion==='cancelled'?'De build is geannuleerd.':'De laatste build is niet geslaagd: '+(run.conclusion||'onbekend')+'.',run)}}catch(e){setLive('bad','Status niet beschikbaar','GitHub Actions kon nu niet worden uitgelezen. Gebruik de knop naar GitHub Actions voor details.',null);setBuildButton(false)}}
document.getElementById('buildForm').addEventListener('submit',e=>{const btn=document.getElementById('buildBtn');if(btn.disabled){e.preventDefault();return}btn.disabled=true;btn.textContent='Build starten…'});
loadLive();
</script>
</body>
</html>
