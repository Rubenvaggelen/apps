#!/usr/bin/env python3
"""Strict, additive patch to the ACTUAL live Main/Car devices.php.

Every new 'dj' registry heartbeat MUST carry an active DJ grant bound to the
same device ID and installation ID. No Car/Windows/Main bypass is expanded.
Refuses already-patched or unexpected live source. Never rewrites user data.
"""
from pathlib import Path
import re
import sys

MARKER="THE_ONE_DJ_LICENSED_REGISTRY_BRIDGE_20261010"
ROLE_ANCHOR="""    $theOneWindowsRequest = $theOneRole === 'windows'
        && str_starts_with($theOnePlatform, 'windows')
        && str_starts_with($theOneHeartbeatId, 'windows-');"""
RETIRE_GUARD="    if (!$theOneCarRequest && !$theOneWindowsRequest) {"
MAIN_GUARD="""    if (!$theOneCarRequest && !$theOneWindowsRequest
        && !isset($theOneApprovedLegacyMainIds[$theOneHeartbeatId])) {"""
ROLE_ALLOW="""    if (!in_array($deviceRole, ['main', 'car', 'windows'], true)) $deviceRole = 'main';"""

INJECT= """
    // THE_ONE_DJ_LICENSED_REGISTRY_BRIDGE_20261010
    // DJ is NOT Main, Car, or Windows. Never trust a claimed DJ role by itself.
    $theOneDjRequest = $theOneRole === 'dj';
    if ($theOneDjRequest) {
        require_once __DIR__ . '/dj-heartbeat-license-bridge.php';
        if (!one_dj_license_heartbeat_granted(
            $theOneHeartbeatId,
            (string)($body['installation_id'] ?? ''),
            (string)($body['license_token'] ?? '')
        )) {
            respond_devices(403, [
                'ok' => false,
                'error' => 'DJ installation needs valid owner-approved DJ license',
                'activation_required' => true
            ]);
        }
    }
"""

def patch(source: str) -> str:
    for label,needle in (
        ("Main bridge", "one_license_main_heartbeat_granted("),
        ("owner retirement guard","THE_ONE_OWNER_RETIRED_GUARD_20261010"),
        ("legacy allowlist","THE_ONE_MAIN_ALLOWLIST_CLEANUP_20261010"),
        ("device role whitelist",ROLE_ALLOW),
        ("Windows classification",ROLE_ANCHOR),
        ("Main-only retirement guard",RETIRE_GUARD),
        ("Main grant guard",MAIN_GUARD),
    ):
        if source.count(needle)!=1:
            raise ValueError(f"Refusing changed live {label}: found {source.count(needle)}")
    if MARKER in source or "one_dj_license_heartbeat_granted" in source:
        raise ValueError("DJ bridge already present, refusing second patch")
    new=source.replace(ROLE_ANCHOR,ROLE_ANCHOR+"\n"+INJECT,1)
    new=new.replace(RETIRE_GUARD,
        "    if (!$theOneCarRequest && !$theOneWindowsRequest && !$theOneDjRequest) {",1)
    new=new.replace(MAIN_GUARD,
        """    if (!$theOneCarRequest && !$theOneWindowsRequest && !$theOneDjRequest
        && !isset($theOneApprovedLegacyMainIds[$theOneHeartbeatId])) {""",1)
    new=new.replace(ROLE_ALLOW,
        """    if (!in_array($deviceRole, ['main', 'car', 'windows', 'dj'], true)) $deviceRole = 'main';""",1)
    if new.count(MARKER)!=1 or new.count("one_dj_license_heartbeat_granted(")!=1:
        raise ValueError("Unexpected DJ guard insertion")
    # Existing owner registry, allowlist, and media rights must be unchanged.
    for needle in ("$theOneApprovedLegacyMainIds = [","$theOneAllowedCarIds = [","$theOneRevokedCarIds = ["):
        if source.count(needle)!=1 or new.count(needle)!=1: raise ValueError("Registry allowlist changed")
    return new

if __name__=="__main__":
    if len(sys.argv)!=3: raise SystemExit("usage: patch-live-dj-registry.py input-devices.php staged-output.php")
    source=Path(sys.argv[1]).read_text(encoding='utf-8')
    next=patch(source)
    Path(sys.argv[2]).write_text(next,encoding='utf-8')
    print("PASS: only DJ-specific authenticated role added; Main/Car/Windows legacy checks intact")
