<?php
declare(strict_types=1);
require_once dirname(__DIR__) . '/license-core.php';

function expect_license(bool $condition, string $message): void {
    if (!$condition) throw new RuntimeException($message);
}
$state = one_license_default();
$device = 'surface-license-test';
$installationA = '11111111-1111-4111-8111-111111111111';
$installationB = '22222222-2222-4222-8222-222222222222';
$requested = one_license_request($state, 'music', $device, 'Old Request', $installationA);
$id = $requested['request_id'];
$secret = $requested['request_secret'];
expect_license(count(one_license_owner_pending($state)) === 1, 'initial request not listed');
expect_license(one_license_owner_deny($state, $id), 'owner cannot deny pending request');
expect_license($state['requests'][$id]['status'] === 'denied', 'status not denied');
expect_license(count(one_license_owner_pending($state)) === 0, 'denied request remains pending');
expect_license(!one_license_owner_deny($state, $id), 'denied request can be denied again');
expect_license(!one_license_owner_approve($state, $id), 'denied request can be approved');
$claim = one_license_request_claim($state, $id, $device, $installationA, $secret);
expect_license(($claim['allowed'] ?? false) === false && ($claim['status'] ?? '') === 'denied', 'denied request claimed');
$repeat = one_license_request($state, 'music', $device, 'Old Request', $installationA);
expect_license($repeat['request_id'] === $id && $repeat['status'] === 'denied', 'same install recreates denied request');
expect_license(count($state['requests']) === 1, 'duplicate request created');
$second = one_license_request($state, 'music', $device, 'Old Request', $installationB);
expect_license($second['request_id'] !== $id, 'new installation not independent');
expect_license(one_license_owner_approve($state, $second['request_id']), 'new installation cannot be approved');
expect_license(!one_license_owner_deny($state, $second['request_id']), 'approved request was denied');
$grant = one_license_request_claim($state, $second['request_id'], $device, $installationB, $second['request_secret']);
expect_license(($grant['allowed'] ?? false) === true, 'approved new installation cannot claim');
expect_license(count($state['grants']) === 1, 'approved grant lost');
$fake = str_repeat('a', 24);
expect_license(!one_license_owner_deny($state, $fake), 'unknown request could be denied');
expect_license(count(one_license_owner_pending($state)) === 0, 'hidden pending request');
echo "PASS: deny only pending, no reappearance, new installs, grants preserved\n";
