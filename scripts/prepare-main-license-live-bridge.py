#!/usr/bin/env python3
"""Prepare an additive license guard patch for the *live* devices.php.

Dry-run by default. Never replace the live file with the repository copy.
Requires an exact match of the owner-confirmed legacy allowlist guard.
"""
import argparse
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import os
from datetime import datetime, timezone

MARKER = "THE_ONE_MAIN_ALLOWLIST_CLEANUP_20261010"
OLD = """    if (!$theOneCarRequest && !$theOneWindowsRequest
        && !isset($theOneApprovedLegacyMainIds[$theOneHeartbeatId])) {
        respond_devices(403, [
            'ok' => false,
            'error' => 'The One Main activation needs owner approval',
            'activation_required' => true
        ]);
    }"""
NEW = """    if (!$theOneCarRequest && !$theOneWindowsRequest
        && !isset($theOneApprovedLegacyMainIds[$theOneHeartbeatId])) {
        // A new Main installation must present a valid grant, not just an approved ID.
        require_once __DIR__ . '/main-heartbeat-license-bridge.php';
        $theOneLicenseAllowed = one_license_main_heartbeat_granted(
            $theOneHeartbeatId,
            (string)($body['installation_id'] ?? ''),
            (string)($body['license_token'] ?? '')
        );
        if (!$theOneLicenseAllowed) {
            respond_devices(403, [
                'ok' => false,
                'error' => 'The One Main activation needs owner approval',
                'activation_required' => true
            ]);
        }
    }"""

def patch(source: str) -> str:
    if source.count(MARKER) != 1 or source.count(OLD) != 1:
        raise ValueError("Live allowlist differs from verified snapshot: refuse to patch")
    if "one_license_main_heartbeat_granted" in source:
        raise ValueError("License hook already exists: refuse double patch")
    if "'498714cd-2077-41c6-9a2a-4f352fc61e22' => true" not in source or "'b843ad19-7779-4d3c-ae64-ae8ccd5c2362' => true" not in source:
        raise ValueError("Car allowlist differs: refuse to patch")
    # Verify exact legacy cohort size and Car allowlist before any future deployment.
    def ids_in_array(variable: str):
        match = re.search(r"\$" + re.escape(variable) + r"\s*=\s*\[(.*?)\];", source, re.S)
        if not match:
            raise ValueError(f"Missing {variable} allowlist")
        return re.findall(r"'([0-9a-f-]{36})'\s*=>\s*true", match.group(1))
    main_ids = ids_in_array("theOneApprovedLegacyMainIds")
    car_ids = ids_in_array("theOneAllowedCarIds")
    if len(main_ids) != 22 or len(set(main_ids)) != 22:
        raise ValueError("Expected exactly 22 distinct legacy Main IDs")
    expected_cars = {
        "498714cd-2077-41c6-9a2a-4f352fc61e22",
        "b843ad19-7779-4d3c-ae64-ae8ccd5c2362",
    }
    if len(car_ids) != 2 or set(car_ids) != expected_cars:
        raise ValueError("Car allowlist no longer matches owner-approved devices")
    return source.replace(OLD, NEW, 1)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("live_devices_php", type=pathlib.Path)
    parser.add_argument("--apply", action="store_true", help="Apply only after backup and explicit deployment approval")
    args = parser.parse_args()
    target = args.live_devices_php.resolve()
    original = target.read_text()
    updated = patch(original)
    bridge = target.parent / "main-heartbeat-license-bridge.php"
    core = target.parent / "license-core.php"
    if not args.apply:
        print("DRY RUN OK: exact allowlists preserved; proposed Main grant guard matches; no files changed.")
        print("Bridge present:", bridge.is_file(), "| License core present:", core.is_file())
        return
    if not bridge.is_file() or not core.is_file():
        raise SystemExit("STOP: licensing bridge/core missing alongside live devices.php")
    backup = target.with_name(target.name + ".before-main-license-bridge-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + ".bak")
    shutil.copy2(target, backup)
    fd, staging = tempfile.mkstemp(prefix=".devices-license-", dir=target.parent)
    try:
        os.fchmod(fd, target.stat().st_mode & 0o777)
        with os.fdopen(fd, "w") as stream:
            stream.write(updated)
            stream.flush()
            os.fsync(stream.fileno())
        lint = subprocess.run(["php", "-l", staging], stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
        if lint.returncode != 0:
            raise RuntimeError("PHP syntax check failed: original live file untouched; " + lint.stderr.strip())
        os.replace(staging, target)
    finally:
        if os.path.exists(staging):
            os.unlink(staging)
    print("PATCHED", target, "BACKUP", backup)

if __name__ == "__main__":
    main()
