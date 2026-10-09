<?php
declare(strict_types=1);
session_start();
header('Cache-Control: private, no-store');
header('X-Content-Type-Options: nosniff');
header('Referrer-Policy: no-referrer');
header('X-Frame-Options: DENY');

/**
 * Administrative actions are deliberately NOT in the public license API.
 * This page needs cPanel Directory Privacy / HTTP authentication. Browser
 * sessions alone or a client-submitted Android device ID are not sufficient.
 */
$authenticatedUser = trim((string)($_SERVER['REMOTE_USER'] ?? $_SERVER['PHP_AUTH_USER'] ?? ''));
$httpsOn = in_array(strtolower((string)($_SERVER['HTTPS'] ?? '')), ['on', '1'], true)
    || (int)($_SERVER['SERVER_PORT'] ?? 0) === 443;
if ($authenticatedUser === '' || !$httpsOn) {
    http_response_code(403);
    echo 'Access denied: this area requires HTTPS and cPanel Directory Privacy authentication.';
    exit;
}
$home = dirname((string)($_SERVER['DOCUMENT_ROOT'] ?? ''));
$core = $home . '/public_html/the-one-remote-api/license-core.php';
if (!is_file($core)) {
    http_response_code(503);
    echo 'Private license service not deployed. No changes made.';
    exit;
}
require_once $core;
function el(string $value): string { return htmlspecialchars($value, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8'); }
if (empty($_SESSION['theone_license_csrf'])) $_SESSION['theone_license_csrf'] = bin2hex(random_bytes(32));
$csrf = (string)$_SESSION['theone_license_csrf'];
$message = '';
$oneTimeCode = '';
try {
    if ($_SERVER['REQUEST_METHOD'] === 'POST') {
        if (!hash_equals($csrf, (string)($_POST['csrf'] ?? ''))) throw new RuntimeException('Beveiligingscontrole mislukt');
        $action = (string)($_POST['action'] ?? '');
        $result = one_license_locked(static function(array &$state, callable $dirty) use ($action): array {
            if ($action === 'create_owner_pairing') {
                $code = one_license_owner_pairing_create($state);
                $dirty();
                return ['message' => 'Koppelcode voor de eigenaar aangemaakt; geldig gedurende 10 minuten.', 'code' => $code];
            }
            if ($action === 'create_code') {
                $app = (string)($_POST['app'] ?? '');
                $name = trim((string)($_POST['person'] ?? ''));
                if ($name === '') throw new InvalidArgumentException('Vul eerst een naam in');
                $value = one_license_create_code($state, $app, $name, 1);
                $dirty();
                return ['message' => 'Activatiecode gegenereerd voor ' . $name . ' (' . $app . ')', 'code' => $value['code']];
            }
            if ($action === 'revoke_grant') {
                $id = (string)($_POST['id'] ?? '');
                if (!isset($state['grants'][$id])) throw new InvalidArgumentException('Onbekende licentie');
                if (($state['grants'][$id]['type'] ?? '') === 'grandfathered') {
                    throw new RuntimeException('Bestaande Main-apparaten blijven standaard actief; intrekken moet via afzonderlijk apparaatbeheer');
                }
                $state['grants'][$id]['status'] = 'revoked';
                $state['grants'][$id]['revoked_at'] = gmdate('c');
                $dirty();
                return ['message' => 'Licentie ingetrokken'];
            }
            if ($action === 'revoke_code') {
                $id = (string)($_POST['id'] ?? '');
                if (!isset($state['codes'][$id])) throw new InvalidArgumentException('Onbekende activatiecode');
                $state['codes'][$id]['status'] = 'revoked';
                $dirty();
                return ['message' => 'Ongebruikte activatiecode ingetrokken'];
            }
            if ($action === 'resolve_request') {
                $id = (string)($_POST['id'] ?? '');
                $decision = (string)($_POST['decision'] ?? '');
                if (!isset($state['requests'][$id])) throw new InvalidArgumentException('Onbekende aanvraag');
                if (!in_array($decision, ['approved', 'rejected'], true)) throw new InvalidArgumentException('Ongeldige beslissing');
                $request = &$state['requests'][$id];
                if ($request['status'] !== 'pending') throw new InvalidArgumentException('Aanvraag al behandeld');
                $request['status'] = $decision;
                $request['resolved_at'] = gmdate('c');
                $dirty();
                // Give the code to the user yourself: it is never displayed to
                // the unauthenticated applicant in a pending request response.
                if ($decision === 'approved') {
                    if (!empty($request['request_secret_hash'])) {
                        // The requesting installation can claim access securely by
                        // presenting its private request secret. No manual code needed.
                        return ['message' => 'Aanvraag goedgekeurd. De aanvrager kan automatisch activeren.'];
                    }
                    $issued = one_license_create_code($state, $request['app'], $request['person'], 1);
                    $request['code_id'] = $issued['license_id'];
                    return ['message' => 'Aanvraag goedgekeurd: geef de eenmalige code veilig aan deze persoon', 'code' => $issued['code']];
                }
                return ['message' => 'Aanvraag afgewezen'];
            }
            if ($action === 'migrate_main') {
                if (($state['main_enforced'] ?? false) || ($state['migration_at'] ?? null) !== null) {
                    throw new RuntimeException('Migratie is al uitgevoerd');
                }
                if ((string)($_POST['confirmation'] ?? '') !== 'BEHOUD BESTAANDE APPARATEN') {
                    throw new InvalidArgumentException('Typ eerst de bevestiging precies over');
                }
                $home = dirname((string)($_SERVER['DOCUMENT_ROOT'] ?? ''));
                $registry = $home . '/the-one-remote-data/main-devices.json';
                $out = one_license_initialize_main($state, $registry);
                $dirty();
                return ['message' => $out['grandfathered'] . ' bestaande Main-apparaten vastgelegd. Nieuwe installaties vereisen nu activatie.'];
            }
            if ($action === 'enable_studio') {
                if ((string)($_POST['confirmation'] ?? '') !== 'STUDIO DOWNLOAD BEVEILIGD') {
                    throw new InvalidArgumentException('Beveilig eerst de echte Windows-download en typ daarna de bevestiging');
                }
                $state['studio_enforced'] = true;
                $dirty();
                return ['message' => 'Music Studio-licenties zijn nu verplicht voor nieuwe beveiligde builds'];
            }
            throw new InvalidArgumentException('Onbekende actie');
        });
        $message = (string)($result['message'] ?? 'Opgeslagen');
        $oneTimeCode = (string)($result['code'] ?? '');
    }
    $current = one_license_locked(static fn(array &$state, callable $dirty): array => one_license_list_admin($state));
} catch (Throwable $error) {
    $message = 'Niet uitgevoerd: ' . $error->getMessage();
    $current = one_license_locked(static fn(array &$state, callable $dirty): array => one_license_list_admin($state));
}
?><!doctype html>
<html lang="nl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>The One — Licenties beheren</title><style>
:root{color-scheme:dark;font:15px system-ui;background:#071525;color:#e1f6ff}
body{margin:0 auto;padding:20px;max-width:1100px}
h1,h2{color:#64d9ff;margin:12px 0}h1{font-size:23px}h2{font-size:17px}
small,p{color:#afc7db}section{border:1px solid #285371;border-radius:15px;margin:18px 0;padding:16px;background:#0d2134}
strong{color:#c1f5ff}label{display:block;margin:10px 0}input,select,button{font:inherit;padding:9px 10px;border:1px solid #387ba1;border-radius:8px;background:#15364e;color:#f1fdff;max-width:100%}
button{cursor:pointer;background:#16648a}button:hover{background:#2580a9}
.danger{background:#6a2d39;border-color:#a44b55}.danger:hover{background:#9b3b45}
.alert{padding:12px;border-radius:10px;background:#132f44}
.code{font:16px monospace;overflow-wrap:anywhere;background:#0b463f;border:1px solid #4fbda2;padding:13px;border-radius:9px}
table{border-collapse:collapse;width:100%}td,th{text-align:left;padding:8px;border-bottom:1px solid #284459;font-size:13px}form.inline{display:inline}
code{color:#7ddfff}.scroll{overflow:auto}
</style></head><body>
<h1>🔐 The One Family — Licenties beheren</h1>
<p>Beheerlicenties vanuit Main → Laptop → Music Studio toegangscodes. Deze pagina vereist een echte geauthenticeerde Dev Hub-beheersessie.</p>
<?php if ($message !== ''): ?><p class="alert"><?=el($message)?></p><?php endif; ?>
<?php if ($oneTimeCode !== ''): ?>
<section><h2>Nieuwe eenmalige code — kopieer hem nu</h2><p class="code"><?=el($oneTimeCode)?></p><p>Deze code wordt niet in leesbare vorm bewaard en verschijnt niet opnieuw. Deel hem alleen veilig met de bedoelde persoon.</p></section>
<?php endif; ?>
<section><h2>Huidige status</h2>
<p>Main-licenties: <strong><?=!empty($current['main_enforced'])?'ACTIEF':'Nog niet ingeschakeld'?></strong>
 · Bestaande apparaten vastgelegd: <strong><?=count($current['grants'])>=0?(int)$current['grandfathered_count']:0?></strong></p>
<p>Music Studio-licenties: <strong><?=!empty($current['studio_enforced'])?'ACTIEF':'Nog niet ingeschakeld'?></strong></p>
<p>Een licentieblokkade op Main mag pas worden aangezet na een gecontroleerde back-up en momentopname van de bestaande registratie.</p>
</section>
<section><h2>Main eigenaar koppelen voor blijvende aanmeldmeldingen</h2>
<p>Alleen de eigenaar kan een koppelcode maken. Open in Main: Laptop → Apparaten beheren → Koppel licentiemeldingen. Vul daar de code binnen tien minuten in. Na koppeling verschijnen nieuwe aanmeldingen blijvend in Main Meldingen totdat je ze goedkeurt.</p>
<form method="post"><input type="hidden" name="csrf" value="<?=el($csrf)?>"><input type="hidden" name="action" value="create_owner_pairing">
<button>Maak een eenmalige Main-koppelcode</button></form>
</section>
<section><h2>Nieuwe code toewijzen</h2>
<form method="post"><input type="hidden" name="csrf" value="<?=el($csrf)?>"><input type="hidden" name="action" value="create_code">
<label>App <select name="app"><option value="studio">Music Studio (Windows)</option><option value="main">The One Main (Android)</option></select></label>
<label>Naam gebruiker of apparaat <input name="person" required maxlength="90" placeholder="Bijv. apparaat / gebruiker"></label>
<button>Genereer eenmalige activatiecode</button></form></section>
<section><h2>Openstaande licentieaanvragen</h2><div class="scroll"><table><thead><tr><th>App</th><th>Naam</th><th>Apparaat</th><th>Datum</th><th>Beslissing</th></tr></thead><tbody>
<?php foreach ($current['requests'] as $request): if (($request['status'] ?? '') !== 'pending') continue; ?>
<tr><td><?=el($request['app'])?></td><td><?=el($request['person'])?></td><td><?=el(substr($request['device'],0,25))?></td><td><?=el($request['created_at'])?></td><td>
<form method="post" class="inline"><input type="hidden" name="csrf" value="<?=el($csrf)?>"><input type="hidden" name="action" value="resolve_request"><input type="hidden" name="id" value="<?=el($request['id'])?>"><button name="decision" value="approved">Goedkeuren + code</button><button class="danger" name="decision" value="rejected">Afwijzen</button></form></td></tr>
<?php endforeach; ?></tbody></table></div></section>
<section><h2>Actieve apparaatlicenties</h2><div class="scroll"><table><thead><tr><th>App</th><th>Gebruiker</th><th>Apparaat</th><th>Type</th><th>Status</th><th>Actie</th></tr></thead><tbody>
<?php foreach ($current['grants'] as $grant): ?><tr><td><?=el($grant['app'])?></td><td><?=el($grant['person'])?></td><td><?=el(substr($grant['device'],0,25))?></td><td><?=el($grant['type'])?></td><td><?=el($grant['status'])?></td><td>
<?php if ($grant['status']==='active' && $grant['type']!=='grandfathered'): ?>
<form method="post"><input type="hidden" name="csrf" value="<?=el($csrf)?>"><input type="hidden" name="action" value="revoke_grant"><input type="hidden" name="id" value="<?=el($grant['id'])?>"><button class="danger">Intrekken</button></form>
<?php else: ?>Bestaand apparaat / inactief<?php endif;?></td></tr><?php endforeach;?></tbody></table></div></section>
<section><h2>Uitgegeven activatiecodes (waarden verborgen)</h2><div class="scroll"><table><thead><tr><th>App</th><th>Omschrijving</th><th>Uitgegeven</th><th>Gebruikt</th><th>Status</th><th>Actie</th></tr></thead><tbody>
<?php foreach ($current['codes'] as $code): ?><tr><td><?=el($code['app'])?></td><td><?=el($code['label'])?></td><td><?=el($code['created_at'])?></td><td><?= (int)$code['redeemed'] ?>/<?= (int)$code['limit'] ?></td><td><?=el($code['status'])?></td><td>
<?php if ($code['status']==='active'): ?><form method="post"><input type="hidden" name="csrf" value="<?=el($csrf)?>"><input type="hidden" name="action" value="revoke_code"><input type="hidden" name="id" value="<?=el($code['id'])?>"><button class="danger">Code intrekken</button></form><?php endif;?></td></tr><?php endforeach;?></tbody></table></div></section>
<section><h2>Veilige migratie (éénmalig)</h2><p>**Laat dit UIT totdat een backup van de geregistreerde Main-apparaten is gecontroleerd.** Alle bestaande registraties worden éénmalig veilig overgenomen; alleen nieuwe Main-installaties moeten daarna activeren.</p>
<form method="post"><input type="hidden" name="csrf" value="<?=el($csrf)?>"><input type="hidden" name="action" value="migrate_main"><label>Typ BEHOUD BESTAANDE APPARATEN <input name="confirmation" autocomplete="off"></label><button class="danger" <?=!empty($current['main_enforced'])?'disabled':''?>>Bestaande apparaten vastleggen en Main-activering inschakelen</button></form>
<p>Music Studio kan pas op verplicht worden gezet nadat de beveiligde Windows-builds en afgeschermde downloads aantoonbaar werken.</p>
<form method="post"><input type="hidden" name="csrf" value="<?=el($csrf)?>"><input type="hidden" name="action" value="enable_studio"><label>Typ STUDIO DOWNLOAD BEVEILIGD <input name="confirmation" autocomplete="off"></label><button class="danger" <?=!empty($current['studio_enforced'])?'disabled':''?>>Music Studio-licenties verplicht maken</button></form>
</section></body></html>
