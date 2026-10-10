import importlib.util
from pathlib import Path
module_path = Path(__file__).resolve().parents[1] / "scripts/prepare-main-license-live-bridge.py"
spec = importlib.util.spec_from_file_location("main_bridge_patch", module_path)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
main_ids = "\n".join("    '00000000-0000-4000-8000-%012d' => true," % i for i in range(22))
fixture = f"""<?php
$theOneApprovedLegacyMainIds = [
{main_ids}
];
// {mod.MARKER}: temporary legacy installation allowlist.
$theOneAllowedCarIds = [
    '498714cd-2077-41c6-9a2a-4f352fc61e22' => true,
    'b843ad19-7779-4d3c-ae64-ae8ccd5c2362' => true,
];
{mod.OLD}
"""
updated = mod.patch(fixture)
assert mod.OLD not in updated
assert updated.count("one_license_main_heartbeat_granted") == 1
assert updated.count("'498714cd-2077-41c6-9a2a-4f352fc61e22' => true") == 1
assert updated.count("'b843ad19-7779-4d3c-ae64-ae8ccd5c2362' => true") == 1
for text in [fixture.replace(mod.MARKER, "WRONG"), fixture.replace(mod.OLD, ""), updated, fixture.replace("b843ad19-7779-4d3c-ae64-ae8ccd5c2362", "removed")]:
    try:
        mod.patch(text)
        raise AssertionError("Unsafe or duplicate patch was accepted")
    except ValueError:
        pass
print("Safe Main live-bridge patch fixture tests passed")
