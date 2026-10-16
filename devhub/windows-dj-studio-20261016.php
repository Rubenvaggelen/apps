<?php
// Publicly released Windows downloads, displayed inside the password-protected
// Dev Hub. Keep other Dev Hub download cards unchanged.
$oneWindowsApps = [
    ['name' => 'The One DJ • Windows', 'version' => 'v240',
     'info' => 'Windows ZIP • uitpakken en Start-The-One-DJ.cmd openen',
     'url' => 'https://github.com/Rubenvaggelen/apps/releases/download/dj-windows-v240/The-One-DJ-Windows.zip'],
    ['name' => 'The One Studio / Music Maker • Windows', 'version' => 'v1.1.0',
     'info' => 'Windows installatieprogramma (.exe)',
     'url' => 'https://github.com/Rubenvaggelen/apps/releases/download/studio-v1.1.0/The-One-Studio-1.1.0-Windows.exe']
];
?>
<section class="card" id="windows-dj-studio-builds" style="max-width:1160px;margin:20px auto 30px;padding:22px;border:1px solid #25405f;border-radius:16px;background:#0d1929;color:#f6fbff">
  <h2 style="font-size:21px;margin:0 0 8px">The One • Windows downloads</h2>
  <p style="color:#91a9c5;margin:0 0 16px">De nieuwste geverifieerde DJ- en Studio-releases voor Windows.</p>
  <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:14px">
  <?php foreach ($oneWindowsApps as $app): ?>
    <article style="padding:18px;background:#112238;border:1px solid #25405f;border-radius:12px">
      <h3 style="margin:0 0 10px;font-size:18px"><?=htmlspecialchars($app['name'],ENT_QUOTES,'UTF-8')?></h3>
      <div style="color:#a6e9ff;font-weight:700"><?=htmlspecialchars($app['version'],ENT_QUOTES,'UTF-8')?></div>
      <p style="color:#91a9c5;min-height:38px"><?=htmlspecialchars($app['info'],ENT_QUOTES,'UTF-8')?></p>
      <a class="btn ok" href="<?=htmlspecialchars($app['url'],ENT_QUOTES,'UTF-8')?>" rel="noopener noreferrer" style="display:inline-block;padding:11px 15px;border:1px solid #18c8ff;border-radius:10px;color:#f6fbff;text-decoration:none">⬇ Download Windows</a>
    </article>
  <?php endforeach; ?>
  </div>
</section>
