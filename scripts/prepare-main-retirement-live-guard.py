#!/usr/bin/env python3
"""Safely patch ONLY the known live Main legacy guard to prevent retired-device reentry.

Never replace the entire live devices.php with the repository copy.
"""
from pathlib import Path
import importlib.util
import re
import sys

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('legacy_bridge', HERE/'prepare-main-license-live-bridge.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

MARKER = 'THE_ONE_OWNER_RETIRED_GUARD_20261010'
EXTRA = """    // THE_ONE_OWNER_RETIRED_GUARD_20261010
    // Deny retired Main devices BEFORE the legacy allowlist fast path.
    if (!$theOneCarRequest && !$theOneWindowsRequest) {
        require_once __DIR__ . '/main-heartbeat-license-bridge.php';
        if (one_license_main_device_retired($theOneHeartbeatId)) {
            respond_devices(403, [
                'ok' => false,
                'error' => 'Main device was removed by owner',
                'activation_required' => true
            ]);
        }
    }

"""

def patch(source: str) -> str:
    if source.count(MARKER) != 0:
        raise ValueError('Retirement guard already installed')
    if source.count(module.NEW) != 1:
        raise ValueError('Live license bridge guard does not match reviewed version')
    if source.count(module.MARKER) != 1:
        raise ValueError('Live allowlist marker missing')
    if source.count('one_license_main_heartbeat_granted') != 1:
        raise ValueError('Live grant bridge missing')
    def ids(name: str) -> list[str]:
        found = re.search(r'\$' + re.escape(name) + r'\s*=\s*\[(.*?)\];',source,re.S)
        if not found:
            raise ValueError('Missing ' + name)
        return re.findall(r"'([0-9a-f-]{36})'\s*=>\s*true",found.group(1))
    main = ids('theOneApprovedLegacyMainIds')
    car = ids('theOneAllowedCarIds')
    if len(main)!=22 or len(set(main))!=22 or len(car)!=2 or set(car)!={'498714cd-2077-41c6-9a2a-4f352fc61e22','b843ad19-7779-4d3c-ae64-ae8ccd5c2362'}:
        raise ValueError('Legacy or Car allowlist differs from reviewed snapshot')
    return source.replace(module.NEW, EXTRA + module.NEW, 1)

if __name__ == '__main__':
    if len(sys.argv)!=2:
        raise SystemExit('usage: prepare-main-retirement-live-guard.py path/to/live/devices.php')
    original = Path(sys.argv[1]).read_text()
    proposed = patch(original)
    print('DRY-RUN: exact guard matched. Added bytes:',len(proposed)-len(original))
