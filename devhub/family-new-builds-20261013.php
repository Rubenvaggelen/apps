<?php
$root='/home/vanawiwj/the-one-private-builds/2026-10-13';
$data=json_decode((string)file_get_contents($root.'/manifest.json'),true);
if(!is_array($data)){return;}
?>
<section class="card" id="standalone-family-builds" style="margin:18px 0;padding:20px;border:1px solid #25405f;border-radius:16px;background:#0d1929">
<h2>Nieuwe losse apps · 13 oktober 2026</h2>
<p style="color:#91a9c5">Kies de juiste versie voor je telefoon of Android / Google TV.</p>
<div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:12px">
<?php foreach($data as $build): ?>
<article style="padding:16px;background:#112238;border:1px solid #25405f;border-radius:12px">
<h3 style="margin:0 0 8px"><?=htmlspecialchars($build['app_name'],ENT_QUOTES,'UTF-8')?></h3>
<p style="color:#91a9c5"><?=htmlspecialchars($build['platform'],ENT_QUOTES,'UTF-8')?> · v<?=htmlspecialchars($build['version_name'],ENT_QUOTES,'UTF-8')?></p>
<p><?=number_format($build['size_bytes']/1048576,1,',','.')?> MB</p>
<a class="btn ok" href="family-download-20261013.php?app=<?=rawurlencode($build['id'])?>" style="display:inline-block;padding:10px 14px;color:#f6fbff;border:1px solid #18c8ff;border-radius:9px">Download APK</a>
</article>
<?php endforeach; ?>
</div>
</section>
