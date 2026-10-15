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
function signed_payload(OpenSSLAsymmetricKey $key, string $verb, string $id, string $scope, string $package='com.theone.cinema'): array {
    $nonce = base64_encode(random_bytes(32));
    need(openssl_sign($verb.'|'.$id.'|'.$scope.'|'.$nonce, $signature, $key, OPENSSL_ALGO_SHA256),
        'Could not sign request');
    return ['device_id'=>$id, 'package'=>$package,
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
copy(dirname(__DIR__).'/family-updates.php',$api.'/family-updates.php');
$updateBase="http://127.0.0.1:$port/the-one-remote-api/family-updates.php";
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

    // The app must collect a real typed name before registration, not auto-fill a device model.
    $withoutName=$reg;
    $withoutName['person_name']=' ';
    [$nameCode,$nameBody]=call_api($base,'family_register',$withoutName);
    need($nameCode===400 && ($nameBody['name_required']??false)===true,
        'Blank name was accepted for a new Family request');
    $oneCharacter=$reg;
    $oneCharacter['person_name']='A';
    [$nameCode,$nameBody]=call_api($base,'family_register',$oneCharacter);
    need($nameCode===400,'One-character name was accepted');
    $longName=$reg;
    $longName['person_name']=str_repeat('Z',81);
    [$nameCode,$nameBody]=call_api($base,'family_register',$longName);
    need($nameCode===400,'Overlong applicant name was accepted');

    [$code,$body]=call_api($base,'family_register',$reg);
    need($code===200 && ($body['device_id']??null)===$id && ($body['pending']??false)===true,
        'New installation did not request approval');
    [$code,$body]=call_api($base,'family_check',signed_payload($key,'check',$id,'cinema_android'));
    need($code===200 && ($body['allowed']??true)===false,'Unapproved installation received access');

    // A second register request must not create another device or reset the pending request.
    [$code,$body]=call_api($base,'family_register',$reg);
    need($code===200 && ($body['pending']??false)===true,'Pending approval was not retained');

    $renamed=$reg;
    $renamed['person_name']='Anna Aanvrager';
    [$code,$body]=call_api($base,'family_register',$renamed);
    need($code===200 && ($body['pending']??false)===true,
        'Pending request did not survive entering the applicant name');
    $current=json_decode((string)file_get_contents($root.'/the-one-remote-data/main-devices.json'),true,512,JSON_THROW_ON_ERROR);
    need(($current['devices'][$id]['person_name']??'')==='Anna Aanvrager',
        'Owner inbox did not get the person name supplied by the applicant');
    $personId = (string)($current['devices'][$id]['person_id'] ?? '');
    need(str_starts_with($personId, 'person-'), 'First applicant did not receive a person ID');

    // Another independently signed app install with the same name joins the person,
    // but must NOT inherit Cinema permission from the first installation.
    $secondKey = openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC, 'curve_name'=>'prime256v1']);
    need($secondKey instanceof OpenSSLAsymmetricKey, 'Second key generation failed');
    $secondPem = (string)openssl_pkey_get_details($secondKey)['key'];
    $secondDer = base64_decode(preg_replace('~-----[^-]+-----|\\s+~', '', $secondPem), true);
    need(is_string($secondDer), 'Second public key unavailable');
    $secondId = 'fam-' . substr(hash('sha256', $secondDer), 0, 48);
    $second = signed_payload($secondKey, 'register', $secondId, 'cinema_android');
    $second['public_key'] = base64_encode($secondDer);
    $second['person_name'] = '  anna   aanvrager ';
    $second['platform'] = 'Google TV test';
    [$code,$linked] = call_api($base,'family_register',$second);
    need($code===200 && ($linked['person_exists']??false) === true &&
        ($linked['person_id']??'') === $personId && ($linked['pending']??false) === true &&
        ($linked['allowed']??true) === false, 'Existing person was not reused safely');
    [$checkCode,$check] = call_api($base,'family_check',
        signed_payload($secondKey,'check',$secondId,'cinema_android'));
    need($checkCode===200 && ($check['allowed']??true)===false,
        'Second installation inherited approval by reusing a name');

    // A different name creates a separate person without touching other rights.
    $thirdKey = openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC, 'curve_name'=>'prime256v1']);
    need($thirdKey instanceof OpenSSLAsymmetricKey, 'Third key generation failed');
    $thirdPem = (string)openssl_pkey_get_details($thirdKey)['key'];
    $thirdDer = base64_decode(preg_replace('~-----[^-]+-----|\\s+~', '', $thirdPem), true);
    need(is_string($thirdDer), 'Third public key unavailable');
    $thirdId = 'fam-' . substr(hash('sha256', $thirdDer), 0, 48);
    $third = signed_payload($thirdKey, 'register', $thirdId, 'cinema_android');
    $third['public_key'] = base64_encode($thirdDer);
    $third['person_name'] = 'Nieuwe Persoon';
    [$code,$fresh] = call_api($base,'family_register',$third);
    need($code===200 && ($fresh['person_exists']??true)===false &&
        ($fresh['person_id']??'') !== $personId, 'New name reused wrong person identity');


    // The Main owner must see the pending app license in the same existing device inbox.
    $ownerId='test-owner-'.bin2hex(random_bytes(6));
    file_put_contents($root.'/the-one-remote-data/main-device-owner.json',
        json_encode(['device_id'=>$ownerId,'person_name'=>'Test eigenaar']));
    // A real owner device also exists in the registry; a fixture must model
    // that fact before testing the server's owner cannot be blocked guard.
    $ownerState=json_decode((string)file_get_contents($root.'/the-one-remote-data/main-devices.json'),true);
    $ownerState['devices'][$ownerId]=[
        'device_id'=>$ownerId,'name'=>'The One Main test','person_name'=>'Test eigenaar',
        'platform'=>'Android test','blocked'=>false,'last_seen'=>time(),
        'access_rights'=>[],'access_requests'=>[]
    ];
    file_put_contents($root.'/the-one-remote-data/main-devices.json',json_encode($ownerState));
    [$ownerCode,$ownerBody]=call_api($base,'pending_requests',['request_device_id'=>$ownerId]);
    $items=$ownerBody['requests']??[];
    need($ownerCode===200 && count(array_filter($items,fn($row)=>
        ($row['device_id']??'')===$id && ($row['scope']??'')==='cinema_android'))===1,
        'Pending Family request not shown in owner Main inbox');
    // The One user APK must create a signed, per-installation pending request.
    // A matching person's name must NOT inherit any Cinema or Shared Media right.
    $userKey=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC, 'curve_name'=>'prime256v1']);
    need($userKey instanceof OpenSSLAsymmetricKey,'User app key generation failed');
    $userPem=(string)openssl_pkey_get_details($userKey)['key'];
    $userDer=base64_decode(preg_replace('~-----[^-]+-----|\\s+~','',$userPem),true);
    need(is_string($userDer),'User app public key unavailable');
    $userId='fam-'.substr(hash('sha256',$userDer),0,48);
    $userReg=signed_payload($userKey,'register',$userId,'the_one_user','com.gmailorg.theoneuser');
    $userReg['public_key']=base64_encode($userDer);
    $userReg['person_name']='Anna Aanvrager';
    $userReg['platform']='Android Pixel';
    $userReg['device_name']='Google Pixel test';
    [$userCode,$userCreated]=call_api($base,'family_register',$userReg);
    need($userCode===200 && ($userCreated['pending']??false)===true &&
        ($userCreated['allowed']??true)===false &&
        ($userCreated['person_id']??'')===$personId,
        'The One user installation must await approval under the existing person');
    [$userCode,$userCheck]=call_api($base,'family_check',
        signed_payload($userKey,'check',$userId,'the_one_user','com.gmailorg.theoneuser'));
    need($userCode===200 && ($userCheck['allowed']??true)===false,
        'Unapproved The One user installation gained access');
    [$userCode,$userRights]=call_api($base,'access_status',
        ['device_id'=>$userId,'scope'=>'shared']);
    need($userCode===200 && ($userRights['allowed']??true)===false,
        'User APK inherited media rights without explicit owner approval');
    [$userCode,$ownerPending]=call_api($base,'pending_requests',['request_device_id'=>$ownerId]);
    need($userCode===200 && count(array_filter($ownerPending['requests']??[],
        fn($row)=>($row['device_id']??'')===$userId &&
                    ($row['scope']??'')==='the_one_user'))===1,
        'The One user APK request is missing from Main pending requests');
    [$userCode,$missingPin]=call_api($base,'set_access_right',
        ['request_device_id'=>$ownerId,'device_id'=>$userId,
         'scope'=>'the_one_user','enabled'=>true]);
    need($userCode===403,'The One user app approved without owner credential');
    $userState=json_decode((string)file_get_contents($root.'/the-one-remote-data/main-devices.json'),true);
    $userState['devices'][$userId]['access_rights']['the_one_user']=true;
    $userState['devices'][$userId]['access_requests']=[];
    file_put_contents($root.'/the-one-remote-data/main-devices.json',json_encode($userState));
    [$userCode,$approvedUser]=call_api($base,'family_check',
        signed_payload($userKey,'check',$userId,'the_one_user','com.gmailorg.theoneuser'));
    need($userCode===200 && ($approvedUser['allowed']??false)===true,
        'Approved The One user app did not unlock');
    [$userCode,$mediaStillClosed]=call_api($base,'access_status',
        ['device_id'=>$userId,'scope'=>'shared']);
    need($userCode===200 && ($mediaStillClosed['allowed']??true)===false,
        'Approval to enter The One unexpectedly granted Shared Media');
    $userForged=signed_payload($userKey,'check',$userId,'the_one_user','com.gmailorg.theoneuser');
    $userForged['package']='com.theone.cinema';
    [$userCode,$userCross]=call_api($base,'family_check',$userForged);
    need($userCode===404,'The One user license was incorrectly accepted for Cinema');

    // Change the legacy music bundle without destroying separately approved
    // The One User, DJ, downloads or pending Run authorizations.
    $userState=json_decode((string)file_get_contents($root.'/the-one-remote-data/main-devices.json'),true);
    $userState['devices'][$userId]['access_rights']['dj']=true;
    $userState['devices'][$userId]['access_rights']['download_files']=true;
    $userState['devices'][$userId]['access_requests']['run']=[
        'status'=>'pending','requested_at'=>gmdate('c')];
    file_put_contents($root.'/the-one-remote-data/main-devices.json',json_encode($userState));
    [$userCode,$enabledMusic]=call_api($base,'set_music_rights',[
        'request_device_id'=>$ownerId,'device_id'=>$userId,'enabled'=>true]);
    need($userCode===200 && ($enabledMusic['music_rights']??false)===true,
        'Owner could not enable the legacy music bundle');
    $userState=json_decode((string)file_get_contents($root.'/the-one-remote-data/main-devices.json'),true);
    $ur=$userState['devices'][$userId]['access_rights']??[];
    $pending=$userState['devices'][$userId]['access_requests']??[];
    need(($ur['the_one_user']??false)===true && ($ur['dj']??false)===true &&
         ($ur['download_files']??false)===true &&
         ($ur['shared']??false)===true && isset($pending['run']),
         'Music grant removed an independent User/DJ/download right or pending request');
    [$userCode,$stillAllowed]=call_api($base,'family_check',
        signed_payload($userKey,'check',$userId,'the_one_user','com.gmailorg.theoneuser'));
    need($userCode===200 && ($stillAllowed['allowed']??false)===true,
        'Music grant revoked existing The One User admission');
    [$userCode,$disabledMusic]=call_api($base,'set_music_rights',[
        'request_device_id'=>$ownerId,'device_id'=>$userId,'enabled'=>false]);
    need($userCode===200 && ($disabledMusic['music_rights']??true)===false,
        'Owner could not disable the legacy music bundle');
    $userState=json_decode((string)file_get_contents($root.'/the-one-remote-data/main-devices.json'),true);
    $ur=$userState['devices'][$userId]['access_rights']??[];
    $pending=$userState['devices'][$userId]['access_requests']??[];
    need(($ur['the_one_user']??false)===true && ($ur['dj']??false)===true &&
         ($ur['download_files']??false)===true && ($ur['shared']??true)===false &&
         isset($pending['run']),
         'Music revoke damaged unrelated user permissions or pending request');

    // Block/unblock through actual owner API calls on isolated test records.
    [$userCode,$notOwnerBlock]=call_api($base,'set_blocked',[
        'request_device_id'=>'unrelated-device','device_id'=>$userId,'blocked'=>true]);
    need($userCode===403,'Non-owner blocked a user installation');
    [$userCode,$blockedUser]=call_api($base,'set_blocked',[
        'request_device_id'=>$ownerId,'device_id'=>$userId,'blocked'=>true]);
    need($userCode===200 && ($blockedUser['blocked']??false)===true,
        'Owner could not block the user installation');
    [$userCode,$blockedCheck]=call_api($base,'family_check',
        signed_payload($userKey,'check',$userId,'the_one_user','com.gmailorg.theoneuser'));
    need($userCode===200 && ($blockedCheck['allowed']??true)===false,
        'Blocked The One User installation retained access');
    [$userCode,$blockedRequest]=call_api($base,'request_access',[
        'device_id'=>$userId,'scope'=>'tv_android']);
    need($userCode===403,'Blocked user could request a new app right');
    [$userCode,$unblockedUser]=call_api($base,'set_blocked',[
        'request_device_id'=>$ownerId,'device_id'=>$userId,'blocked'=>false]);
    need($userCode===200 && ($unblockedUser['blocked']??true)===false,
        'Owner could not unblock the user installation');
    [$userCode,$restoredUser]=call_api($base,'family_check',
        signed_payload($userKey,'check',$userId,'the_one_user','com.gmailorg.theoneuser'));
    need($userCode===200 && ($restoredUser['allowed']??false)===true,
        'Unblocking did not restore previously approved user access');
    [$userCode,$ownerBlocked]=call_api($base,'set_blocked',[
        'request_device_id'=>$ownerId,'device_id'=>$ownerId,'blocked'=>true]);
    need($userCode===409,'Owner device must not be blockable');

    [$strangerCode,$strangerBody]=call_api($base,'pending_requests',['request_device_id'=>'stranger']);
    need($strangerCode===403,'Non-owner may read the owner approval inbox');

    // A Cinema installation may not authorize Series, even with the same device key.
    $cross=signed_payload($key,'check',$id,'series_android');
    $cross['package']='com.theone.series';
    [$crossCode,$crossBody]=call_api($base,'family_check',$cross);
    need($crossCode===404,'Cross-app license accepted a Cinema identity as Series');
    [$crossCode,$crossBody]=call_api($updateBase,'check',$cross);
    need($crossCode===403,'Cross-app update accepted a Cinema identity as Series');
    // A forged registration must never create another trusted installation.
    $forged=$reg;
    $forged['signature']=base64_encode(random_bytes(72));
    [$forgeCode,$forgeBody]=call_api($base,'family_register',$forged);
    need($forgeCode===403,'Registration accepted a forged installation signature');

    $jsonPath=$root.'/the-one-remote-data/main-devices.json';
    $state=json_decode((string)file_get_contents($jsonPath),true,512,JSON_THROW_ON_ERROR);
    $state['devices'][$id]['access_rights']['cinema_android']=true;
    $state['devices'][$id]['access_requests']=[];
    file_put_contents($jsonPath,json_encode($state));
    [$code,$body]=call_api($base,'family_check',signed_payload($key,'check',$id,'cinema_android'));
    need($code===200 && ($body['allowed']??false)===true && ($body['valid_seconds']??0)===86400,
        'Approved installation did not receive a 24h online grant');

    $renameAfterApproval=$reg;
    $renameAfterApproval['person_name']='Andere Aanvrager';
    [$code,$body]=call_api($base,'family_register',$renameAfterApproval);
    need($code===200 && ($body['allowed']??false)===true,
        'Existing approved installation should remain approved');
    $confirmed=json_decode((string)file_get_contents($root.'/the-one-remote-data/main-devices.json'),true,512,JSON_THROW_ON_ERROR);
    need(($confirmed['devices'][$id]['person_name']??'')==='Anna Aanvrager',
        'An approved installation name was overwritten after approval');


    // The update manifest and APK are private; only approved signing keys may read them.
    $rootBuild=$root.'/the-one-private-builds/2026-10-13';
    mkdir($rootBuild.'/apks',0700,true);
    $apkName='The-One-Cinema-Android-v1.3.8.apk';
    $dummyApk="isolated-unit-test-only-apk\n";
    file_put_contents($rootBuild.'/apks/'.$apkName,$dummyApk);
    $buildInfo=[[
        'id'=>'cinema-phone',
        'apk'=>$apkName,
        'version_name'=>'1.3.8',
        'sha256'=>hash('sha256',$dummyApk),
        'package_badging'=>"package: name='com.theone.cinema' versionCode='138' versionName='1.3.8'",
        'dev_hub_status'=>'published'
    ]];
    file_put_contents($rootBuild.'/manifest.json',json_encode($buildInfo));
    [$code,$body]=call_api($updateBase,'check',signed_payload($key,'check',$id,'cinema_android'));
    need($code===200 && ($body['version_code']??0)===138,'Approved app cannot check private update');
    $fake=signed_payload($key,'check',$id,'cinema_android');
    $fake['signature']=base64_encode(random_bytes(72));
    [$code,$body]=call_api($updateBase,'check',$fake);
    need($code===403,'Update endpoint accepted forged signature');
    // No signed token may read an APK of an unapproved installation.
    $invalid=signed_payload($key,'check',$id,'cinema_android');
    $invalid['signature']=base64_encode(random_bytes(72));
    [$code,$body]=call_api($base,'family_check',$invalid);
    need($code===403,'Forged installation signature was accepted');
    $state['devices'][$id]['blocked']=true;
    file_put_contents($jsonPath,json_encode($state));
    [$code,$body]=call_api($base,'family_check',signed_payload($key,'check',$id,'cinema_android'));
    need($code===200 && ($body['allowed']??true)===false,'Blocked device still has access');
    [$updateCode,$updateBody]=call_api($updateBase,'check',signed_payload($key,'check',$id,'cinema_android'));
    need($updateCode===403,'Blocked installation can still check private builds');
    $state['devices'][$id]['blocked']=false;
    $state['devices'][$id]['access_rights']['cinema_android']=false;
    $state['devices'][$id]['family_denied_scopes']['cinema_android']=gmdate('c');
    file_put_contents($jsonPath,json_encode($state));
    [$code,$body]=call_api($base,'family_register',$reg);
    need($code===200 && ($body['pending']??true)===false,'Denied request was recreated automatically');
    echo "Family license and private update HTTP contract: checks passed\n";
} finally {
    proc_terminate($proc);
    proc_close($proc);
    cleanup($root);
}
