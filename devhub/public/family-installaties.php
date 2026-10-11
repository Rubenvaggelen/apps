<?php
declare(strict_types=1);
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');
/*
 * Read-only installer inventory. Directory Privacy/.htaccess of Dev Hub
 * remains in control; this page never reads secrets, tokens or local paths.
 */
$apps = [
 ['Android','The One Main','v996','main-v','app-debug.apk','main-v996','app-debug.apk','available','Muziek, films, Main player en apparatenbeheer'],
 ['Android','The One DJ','v1050','dj-v','thedj-debug.apk','dj-v1050','thedj-debug.apk','available','Eigen DJ APK en onafhankelijke updates; v1051 is nog test'],
 ['Android','The One Car','v944','car-v','carradio-debug.apk','car-v944','carradio-debug.apk','available','Zelfstandige Car APK'],
 ['Android','The One Run','v1012','run-v','runcoach-debug.apk','run-v1012','runcoach-debug.apk','available','Hardloopcoach voor Android'],
 ['Wear OS','The One Run Wear','v1012','run-v','runcoach-wear-debug.apk','run-v1012','runcoach-wear-debug.apk','available','Apart horloge-installatiepakket'],
 ['Android','The One Media Player','v2400','media-player-v','The-One-Media-Player-v2400.apk','media-player-v2400','The-One-Media-Player-v2400.apk','available','Nieuwere eigen APK, in plaats van verouderde v2316-link'],
 ['Android TV','The One Media Player TV','TV-release','','','media-player-tv-latest','The-One-Media-Player-TV.apk','available','Afzonderlijke Android TV APK'],
 ['Android','The One Remote','v927 (ouder)','','','v927','remoteapp-debug.apk','archive','Bestaande oudere APK; actuele functionaliteit opnieuw testen'],
 ['Windows','The One Window','v192','windows-v','The-One-Main-Windows.zip','windows-v192','The-One-Main-Windows.zip','available','Eigen ZIP-installatiebron en updatekanaal'],
 ['Windows','The One DJ Windows','v240','dj-windows-v','The-One-DJ-Windows.zip','dj-windows-v240','The-One-DJ-Windows.zip','available','Eigen ZIP; nog geen zelfstandige Windows Setup EXE'],
 ['Windows','The One Music Studio Portable','v1.4.0 bron op Ruben','','','','','pending','Nieuwe portable EXE moet van de actuele bron gebouwd en gepubliceerd worden'],
 ['Windows','The One Music Studio Setup','v1.4.0 bron op Ruben','','','','','pending','Nieuwe Setup EXE nog bouwen en publiceren; v1.1.0 is verouderd'],
 ['Windows','The One Studio — oud archief','v1.1.0','','','studio-v1.1.0','The-One-Studio-1.1.0-Windows.exe','archive','Niet gelijk aan The One Music Studio v1.4.0'],
 ['iOS','The One Run iOS','In ontwikkeling','','','','','pending','Geen ondertekende iOS-installatiebron aangetroffen'],
 ['Service','The One Hub / Shared Media','Actieve achtergrondservice','','','','','service','Windows-agent/synchronisatie; zelfstandige installer nog niet vastgesteld'],
 ['Web','The One Dev Hub','Actief','','','','','service','Website: ontwikkel-, download- en beheerportaal'],
 ['Web','Mail & Kalender','Actieve webapp','','','','','service','NS-koppeling via beveiligde Family-server; geen APK'],
 ['Module','The One Financiën','Verborgen','','','','','pending','Nog niet publiceren of zichtbaar maken zonder goedkeuring'],
 ['Ander project','Rutu BBQ','Installatiebron nog te verifiëren','','','','','pending','Niet als zelfstandige release aangetroffen in deze repository'],
 ['Ander project','The Jade Emperor / Imperial Sweets','Concept','','','','','pending','Nog geen zelfstandige apprelease geverifieerd'],
];
function e(string $x): string { return htmlspecialchars($x, ENT_QUOTES|ENT_SUBSTITUTE, 'UTF-8'); }
function linkFor(array $a): string {
 if ($a[5]==='' || $a[6]==='') return '';
 return 'https://github.com/Rubenvaggelen/apps/releases/download/'.rawurlencode($a[5]).'/'.rawurlencode($a[6]);
}
?>
<!doctype html>
<html lang="nl">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex,nofollow">
<title>Family installaties • The One Dev Hub</title>
<link rel="icon" href="brand-logo.php">
<style>
:root{color-scheme:dark;--bg:#06101b;--card:#102136;--border:#27425d;--cyan:#32c7ff;--text:#f2f8ff;--muted:#a4b7cd;--green:#86edcc}
*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 5% 0%,#133458,var(--bg) 38%);font:15px/1.5 system-ui,'Segoe UI',sans-serif;color:var(--text)}
main{max-width:1140px;margin:auto;padding:22px 18px 60px}nav{display:flex;gap:12px;flex-wrap:wrap;align-items:center;justify-content:space-between}
a{color:var(--cyan)}.back,.download{border:1px solid var(--border);border-radius:11px;padding:9px 12px;text-decoration:none;color:var(--text);display:inline-block}
.back:hover,.download:hover{border-color:var(--cyan)}h1{margin:22px 0 0;font-size:clamp(26px,4vw,36px)}h2{margin:25px 0 10px;font-size:20px}
p{color:var(--muted);margin:7px 0 18px}.logo{display:flex;align-items:center;gap:12px}.logo img{height:37px;width:37px;object-fit:contain}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:13px}
.card{padding:16px;background:var(--card);border:1px solid var(--border);border-radius:16px;display:flex;flex-direction:column;gap:7px;min-height:185px}
.card h3{margin:0;font-size:17px}.card .description{color:var(--muted);font-size:13px;flex:1}
.meta{display:flex;gap:7px;flex-wrap:wrap;align-items:center}.meta span{font-size:11px;border:1px solid var(--border);padding:2px 7px;border-radius:18px}
.download{font-weight:700;text-align:center;color:#051320;background:var(--cyan);border-color:transparent;margin-top:5px}
.download:hover{color:#051320;background:#7bddff}.placeholder{color:var(--muted);font-size:13px;margin-top:8px}
.note{padding:12px 14px;border-left:2px solid var(--cyan);background:#0b1a2b;border-radius:4px}
</style>
</head>
<body>
<main>
<nav><div class="logo"><img src="brand-logo.php" alt=""><strong>The One Family • Installaties</strong></div><a class="back" href="builds.php">← Terug naar Builds</a></nav>
<h1>Alle apps en installatiebronnen</h1>
<p>Iedere zelfstandige Android-app heeft haar eigen APK. Windows-programma's krijgen een eigen ZIP, Setup of Portable. Bestaande releases blijven gescheiden; ontbrekende installaties tonen geen fictieve download.</p>
<p class="note">Voor onafhankelijke updates en licenties blijft het installatie-ID per app behouden. Voor The One Music Studio moeten Setup en Portable v1.4.0 nog worden gepubliceerd en moet clientlicentiecontrole worden afgerond.</p>
<?php
$sections=['Android','Wear OS','Android TV','Windows','iOS','Service','Web','Module','Ander project'];
foreach($sections as $section):
 $entries=array_values(array_filter($apps,static fn(array $a): bool=>$a[0]===$section));
 if(!$entries)continue;
?>
<section><h2><?=e($section)?></h2><div class="grid">
<?php foreach($entries as $a): $url=linkFor($a); $downloadable=$url!=='';?>
<article class="card" data-prefix="<?=e($a[3])?>" data-asset="<?=e($a[4])?>" data-state="<?=e($a[7])?>">
<h3><?=e($a[1])?></h3>
<div class="meta"><span class="version"><?=e($a[2])?></span><span><?=e($a[7]==='available'?'Beschikbaar':($a[7]==='archive'?'Oudere release':($a[7]==='service'?'Dienst / Web':'Nog niet gereed')))?></span></div>
<div class="description"><?=e($a[8])?></div>
<?php if($downloadable):?>
<a class="download" href="<?=e($url)?>" rel="noopener" data-download>Download installatiebron ↗</a>
<?php else:?>
<div class="placeholder">Installatiebron nog niet beschikbaar</div>
<?php endif;?>
</article>
<?php endforeach;?>
</div></section>
<?php endforeach;?>
</main>
<script>
/* Cosmetic refresh of independently published release tags only. Never
   replace a verified fixed link with an unverified or missing asset. */
(async()=>{
 try{
  const r=await fetch('https://api.github.com/repos/Rubenvaggelen/apps/releases?per_page=100',{cache:'no-store',headers:{Accept:'application/vnd.github+json'}});
  if(!r.ok)return;
  const releases=await r.json();
  if(!Array.isArray(releases))return;
  for(const card of document.querySelectorAll('article[data-prefix]')){
   const prefix=card.dataset.prefix, assetPattern=card.dataset.asset;
   if(!prefix||!assetPattern||card.dataset.state!=='available')continue;
   for(const rel of releases){
    if(rel.draft||rel.prerelease||!String(rel.tag_name||'').startsWith(prefix))continue;
    const asset=(rel.assets||[]).find(a=>assetPattern.includes('*')?new RegExp('^'+assetPattern.replace(/[.*+?^${}()|[\]\\]/g,'\\$&').replace('\\*','.*')+'$').test(a.name):a.name===assetPattern);
    if(!asset||!/^https:\/\/github\.com\//.test(asset.browser_download_url||''))continue;
    const link=card.querySelector('[data-download]');
    if(link){link.href=asset.browser_download_url;card.querySelector('.version').textContent=rel.tag_name}
    break;
   }
  }
 }catch(_){}
})();
</script>
</body></html>
