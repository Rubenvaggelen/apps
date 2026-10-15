#!/usr/bin/env python3
"""Minimal, fail-closed live permission integrity patch. No registration edits."""
from pathlib import Path
import re
import sys
if len(sys.argv) != 4:
    raise SystemExit("usage: patch-user-music-rights-live.py LIVE SOURCE_DESIRED OUT")
live, desired_source, output = map(Path, sys.argv[1:])
original = live.read_text(encoding="utf-8")
expected = desired_source.read_text(encoding="utf-8")
old = "    $state['devices'][$deviceId]['access_rights'] = [\n        'media_player' => $ownerTarget ? true : (bool)($existingScoped['media_player'] ?? false),\n        'mixes' => $ownerTarget ? true : $enabled,\n        'shared' => $ownerTarget ? true : $enabled,\n        'favorites' => $ownerTarget ? true : $enabled,\n        'dj' => false,\n        'run' => $ownerTarget ? true : (bool)($existingScoped['run'] ?? false),\n        'downloads' => $ownerTarget ? true : false,\n        'download_files' => $ownerTarget ? true : false,\n        'cinema_android' => $ownerTarget ? true : (bool)($existingScoped['cinema_android'] ?? false),\n        'series_android' => $ownerTarget ? true : (bool)($existingScoped['series_android'] ?? false),\n        'tv_android' => $ownerTarget ? true : (bool)($existingScoped['tv_android'] ?? false)\n    ];\n    // Een wijziging van de oudere muziekrechten mag onafhankelijke app-aanvragen\n    // niet afhandelen of wissen zonder expliciete beslissing van de eigenaar.\n    $pendingRequests = device_access_requests($state['devices'][$deviceId]);\n    $state['devices'][$deviceId]['access_requests'] = array_intersect_key(\n        $pendingRequests,\n        array_flip(['cinema_android', 'series_android', 'tv_android'])\n    );\n"
new = "    // Only the legacy music bundle is changed here. Preserve all independent\n    // rights (including the_one_user, DJ, Run, downloads, Cinema, Series, TV)\n    // and all unrelated pending approval requests.\n    foreach (['mixes', 'shared', 'favorites'] as $musicScope) {\n        $existingScoped[$musicScope] = $ownerTarget ? true : $enabled;\n    }\n    $state['devices'][$deviceId]['access_rights'] = $existingScoped;\n    $pendingRequests = device_access_requests($state['devices'][$deviceId]);\n    foreach (['mixes', 'shared', 'favorites'] as $musicScope) {\n        unset($pendingRequests[$musicScope]);\n    }\n    $state['devices'][$deviceId]['access_requests'] = $pendingRequests;\n"
assert expected.count(new)==1, "Expected code missing from candidate repository"
if original.count(new)==1:
    print("RIGHTS_PATCH=already-current")
    fixed = original
elif original.count(old)==1:
    print("RIGHTS_PATCH=exact-minimal-replacement")
    fixed = original.replace(old,new,1)
else:
    raise SystemExit("UNSAFE_LIVE_MUSIC_RIGHTS_DIFFERENCE; production unchanged")
def actions(src):
    return set(re.findall(r"\$action\s*===\s*['\"]([a-z_]+)['\"]",src))
def functions(src):
    return set(re.findall(r"\bfunction\s+([A-Za-z_][A-Za-z_0-9]*)\s*\(",src))
if actions(original)!=actions(fixed) or functions(original)!=functions(fixed):
    raise SystemExit("FUNCTION_OR_ACTION_CHANGED; production unchanged")
if fixed.count("Preserve all independent")!=1:
    raise SystemExit("INTEGRITY_MARKER_MISSING")
output.write_text(fixed,encoding="utf-8")
print("RIGHTS_PATCH_VALIDATED=true")
