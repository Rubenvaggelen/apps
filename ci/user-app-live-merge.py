#!/usr/bin/env python3
"""Fail-closed, additive patch to the actual live Family license API.

Preserves existing functions and live data. The deploy job creates a backup
and performs its own syntax and isolated signed-license tests before installing.
"""
from pathlib import Path
import re
import sys

if len(sys.argv) != 4:
    raise SystemExit("usage: user-app-live-merge.py LIVE DESIRED OUTPUT")
live_path, desired_path, output_path = map(Path, sys.argv[1:])
live = live_path.read_text(encoding="utf-8")
desired = desired_path.read_text(encoding="utf-8")
merged = live

def patch(old, new, label, occurrences=1):
    global merged
    if merged.count(new) == occurrences:
        print("ALREADY_PRESENT=" + label)
        return
    if merged.count(old) != occurrences:
        raise SystemExit("UNSAFE_PATCH_ANCHOR=" + label +
                         " expected=" + str(occurrences) +
                         " got=" + str(merged.count(old)))
    if desired.count(new) != occurrences:
        raise SystemExit("DESIRED_CONTRACT_MISSING=" + label)
    merged = merged.replace(old, new)
    print("ADDED=" + label)

old_scopes="'media_player', 'mixes', 'shared', 'favorites', 'dj', 'run', 'downloads', 'download_files', 'cinema_android', 'series_android', 'tv_android'"
patch(old_scopes, "'the_one_user', " + old_scopes, "user-scope-validation-and-pending-list", 2)
patch("    return [\n        'media_player' => (bool)($stored['media_player'] ?? false),",
      "    return [\n        'the_one_user' => (bool)($stored['the_one_user'] ?? false),\n        'media_player' => (bool)($stored['media_player'] ?? false),",
      "user-rights-projection")
patch("        'com.theone.tv' => 'tv_android'",
      "        'com.theone.tv' => 'tv_android',\n        'com.gmailorg.theoneuser' => 'the_one_user'",
      "signed-user-package-map")
patch("['cinema_android','series_android','tv_android'], true) && !devices_admin",
      "['the_one_user','cinema_android','series_android','tv_android'], true) && !devices_admin",
      "owner-pin-required")
patch("['cinema_android', 'series_android', 'tv_android'], true))",
      "['the_one_user', 'cinema_android', 'series_android', 'tv_android'], true))",
      "denied-user-registration-not-recreated")

def symbols(source):
    functions = set(re.findall(r"function\s+([A-Za-z_]\w*)\s*\(", source))
    actions = set(re.findall(r"\$action\s*===\s*['\"]([A-Za-z_]\w*)['\"]", source))
    return functions | {"action:" + x for x in actions}
lost = symbols(live) - symbols(merged)
if lost:
    raise SystemExit("LIVE_ACTIONS_REMOVED=" + ",".join(sorted(lost)))
if "<<<<<<<" in merged or ">>>>>>>" in merged:
    raise SystemExit("UNRESOLVED_CONFLICT")
output_path.write_text(merged, encoding="utf-8")
print("USER_APP_MERGE=validated-and-staged")
