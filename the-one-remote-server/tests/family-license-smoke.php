<?php
declare(strict_types=1);

/**
 * Isolated HTTP integration smoke test. Never touches live registrations.
 * Run with: php the-one-remote-server/tests/family-license-smoke.php
 */
function need(bool $ok, string $message): void {
    if (!$ok) throw new RuntimeException($message);
}
function call_api(string $base, string $action, array $payload = []): array {
    $context = stream_context_create(['http'=>[
        'method'=>$action === 'health' ? 'GET' : 'POST',
        'header'=>"Content-Type: application/json\r\n",
        'content'=>json_encode($payload, JSON_UNESCAPED_SLASHES),
        'ignore_errors'=>true,
        'timeout'=>5
    ]]);
    $response = @file_get_contents($base . '?action=' . $action, false, $context);
    $headers = $http_response_header ?? [];
    preg_match('~HTTP/\d(?:\.\d)? (\d{3})~', (string)($headers[0] ?? ''), $match);
    $code = isset($match[1]) ? (int)$match[1] : 0;
    return [$code, is_string($response) ? json_decode($response, true) : null];
}
function signed_payload(OpenSSLAsymmetricKey $key, string $verb, string $id, string $scope): array {
    $nonce = base64_encode(random_bytes(32));
    need(openssl_sign($verb.'|'.$id.'|'.$scope.'|'.$nonce, $signature, $key, OPENSSL_ALGO_SHA256),
        'Could not sign request');
    return ['device_id'=>$id, 'package'=>'com.theone.cinema',
        'nonce'=>$nonce, 'signature'=>base64_encode($signature)];
}
function cleanup(string $path): void {
    if (!is_dir($path)) return;
    foreach (scandir($path) ?: [] as $item) {
        if ($item === '.' || $item === '..') continue;
        $child=$path . DIRECTORY_SEPARATOR . $item;
        if (is_dir($child)) cleanup($child); else @unlink($child);
    }
    @rmdir($path);
}
$key=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC, 'curve_name'=>'prime256v1']);
need($key instanceof OpenSSLAsymmetricKey, 'P-256 test key unavailable');
$public=(string)openssl_pkey_get_details($key)['key'];
$der=base64_decode(preg_replace('~-----[^-]+-----|\s+~', '', $public), true);
need(is_string($der) && strlen($der)>70, 'Public key DER extraction failed');
$id='fam-'.substr(hash('sha256',$der),0,48);
$root=sys_get_temp_dir().'/the-one-family-test-'.bin2hex(random_bytes(6));
$www=$root.'/public';
$api=$www.'/the-one-remote-api';
$port=random_int(20000,48000);
$base="http://127.0.0.1:$port/the-one-remote-api/devices.php";
mkdir($api, 0700, true);
copy(dirname(__DIR__).'/devices.php',$api.'/devices.php');
$proc=proc_open(escapeshellarg(PHP_BINARY).' -S 127.0.0.1:'.$port.' -t '.escapeshellarg($www),
    [['pipe','r'],['file',$root.'/server.log','w'],['file',$root.'/error.log','w']],
    $pipes);
need(is_resource($proc), 'Could not start isolated PHP test server');
try {
    $healthy=false;
    for ($i=0;$i<40;$i++) {
        [$code,$json]=call_api($base,'health');
        if ($code===200) {$healthy=true;break;}
        usleep(100000);
    }
    need($healthy,'Isolated PHP server is not ready');
    $reg=signed_payload($key,'register',$id,'cinema_android');
    $reg['public_key']=base64_encode($der);
    $reg['person_name']='Licentie Test';
    $reg['platform']='Android test';
    [$code,$body]=call_api($base,'family_register',$reg);
    need($code===200 && ($body['device_id']??null)===$id && ($body['pending']??false)===true,
        'New installation did not request approval');
    [$code,$body]=call_api($base,'family_check',signed_payload($key,'check',$id,'cinema_android'));
    need($code===200 && ($body['allowed']??true)===false,'Unapproved installation received access');
    $jsonPath=$root.'/the-one-remote-data/main-devices.json';
    $state=json_decode((string)file_get_contents($jsonPath),true,512,JSON_THROW_ON_ERROR);
    $state['devices'][$id]['access_rights']['cinema_android']=true;
    $state['devices'][$id]['access_requests']=[];
    file_put_contents($jsonPath,json_encode($state));
    [$code,$body]=call_api($base,'family_check',signed_payload($key,'check',$id,'cinema_android'));
    need($code===200 && ($body['allowed']??false)===true && ($body['valid_seconds']??0)===86400,
        'Approved installation did not receive a 24h online grant');
    $invalid=signed_payload($key,'check',$id,'cinema_android');
    $invalid['signature']=base64_encode(random_bytes(72));
    [$code,$body]=call_api($base,'family_check',$invalid);
    need($code===403,'Forged installation signature was accepted');
    $state['devices'][$id]['blocked']=true;
    file_put_contents($jsonPath,json_encode($state));
    [$code,$body]=call_api($base,'family_check',signed_payload($key,'check',$id,'cinema_android'));
    need($code===200 && ($body['allowed']??true)===false,'Blocked device still has access');
    $state['devices'][$id]['blocked']=false;
    $state['devices'][$id]['access_rights']['cinema_android']=false;
    $state['devices'][$id]['family_denied_scopes']['cinema_android']=gmdate('c');
    file_put_contents($jsonPath,json_encode($state));
    [$code,$body]=call_api($base,'family_register',$reg);
    need($code===200 && ($body['pending']??true)===false,'Denied request was recreated automatically');
    echo "Family license HTTP contract: 6 checks passed\n";
} finally {
    proc_terminate($proc);
    proc_close($proc);
    cleanup($root);
}
