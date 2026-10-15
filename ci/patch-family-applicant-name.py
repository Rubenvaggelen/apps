#!/usr/bin/env python3
"""Narrow live Family enrollment patch; refuse unexpected source or duplicate edits."""
import sys
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch-family-applicant-name.py LOCAL_COPY_OF_DEVICES_PHP")
path=Path(sys.argv[1])
source=path.read_text(encoding="utf-8")
begin=source.find("if ($action === 'family_register') {")
end=source.find("if ($action === 'family_check') {",begin)
if begin<0 or end<0:
    raise SystemExit("Expected signed Family registration actions missing, not safe to patch")
piece=source[begin:end]
old_name="""    $personName = trim((string)($body['person_name'] ?? ''));
    if ($personName === '') respond_devices(400, ['ok' => false, 'name_required' => true]);"""
new_name="""    $personName = preg_replace('/\\\\s+/u', ' ', trim((string)($body['person_name'] ?? '')));
    if (!is_string($personName) || mb_strlen($personName) < 2 ||
        mb_strlen($personName) > 80 || preg_match('/[\\\\x00-\\\\x1F\\\\x7F]/u', $personName)) {
        respond_devices(400, ['ok' => false, 'name_required' => true,
            'error' => 'Applicant name must contain 2 to 80 characters']);
    }"""
if old_name in piece:
    piece=piece.replace(old_name,new_name,1)
elif new_name not in piece:
    raise SystemExit("Live applicant-name validation differs unexpectedly, aborting")
old_device="""        'name' => mb_substr($package . ' (' . trim((string)($body['platform'] ?? 'Android')) . ')', 0, 100),"""
new_device="""        'name' => mb_substr($package . ' (' . trim((string)($body['device_name'] ?? ($body['platform'] ?? 'Android'))) . ')', 0, 100),"""
if old_device in piece:
    piece=piece.replace(old_device,new_device,1)
elif new_device not in piece:
    raise SystemExit("Live Family device display-name field differs unexpectedly, aborting")
old_pending="""    $rights = device_access_rights($device);
    $requests = device_access_requests($device);
    if (!(bool)($rights[$scope] ?? false) && !isset($requests[$scope])"""
new_pending="""    $rights = device_access_rights($device);
    $requests = device_access_requests($device);
    // Refresh still-pending owner requests with the typed applicant name.
    // Never rewrite an approved installation's owner-visible identity.
    if (isset($requests[$scope]) && !(bool)($rights[$scope] ?? false)) {
        $device['person_name'] = $personName;
    }
    if (!(bool)($rights[$scope] ?? false) && !isset($requests[$scope])"""
if old_pending in piece:
    piece=piece.replace(old_pending,new_pending,1)
elif new_pending not in piece:
    raise SystemExit("Live Family pending block differs unexpectedly, aborting")
assert piece.count("    $device['person_name'] = $personName;")==1
patched=source[:begin]+piece+source[end:]
if patched==source:
    print("APPLICANT_NAME_PATCH=already-present")
else:
    path.write_text(patched,encoding="utf-8")
    print("APPLICANT_NAME_PATCH=staged")
