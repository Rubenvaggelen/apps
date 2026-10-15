#!/usr/bin/env python3
"""Fail-closed, surgical Family people merge onto the actual production PHP file.
Never replace production code wholesale; preserve all unrelated live actions.
"""
from pathlib import Path
import re, sys

if len(sys.argv) != 4:
    raise SystemExit("usage: family-people-live-merge.py LIVE DESIRED OUT")
live, desired, output = map(Path, sys.argv[1:])
source = desired.read_text(encoding="utf-8")
text = live.read_text(encoding="utf-8")

def section(document, start, end):
    a = document.find(start)
    b = document.find(end, a + len(start))
    if a < 0 or b < 0 or b <= a:
        raise SystemExit("MISSING_EXPECTED_SECTION=" + start[:50])
    return document[a:b]

def insert_before(marker, addition, label):
    global text
    if addition.strip() in text:
        print("ALREADY_PRESENT=" + label)
        return
    if text.count(marker) != 1:
        raise SystemExit("INVALID_ANCHOR=" + label)
    text = text.replace(marker, addition + marker, 1)
    print("APPLIED=" + label)

def replace_unique(old, new, label):
    global text
    if new in text:
        print("ALREADY_PRESENT=" + label)
        return
    if text.count(old) != 1:
        raise SystemExit("INVALID_ANCHOR=" + label)
    text = text.replace(old, new, 1)
    print("APPLIED=" + label)

helpers = section(source,
    "/** User-facing people are names;",
    "/** The One Family: app-install identities")
insert_before("/** The One Family: app-install identities", helpers, "person-helper")

family = section(text, "if ($action === 'family_register') {",
    "if ($action === 'family_check') {")
desired_family = section(source, "if ($action === 'family_register') {",
    "if ($action === 'family_check') {")
# Ensure the live file uses the same strict, required-name validation as new apps.
# A live-only one-character legacy check must not silently weaken this contract.
if "mb_strlen($personName) < 2" not in family:
    name_start = family.find("    $personName = ")
    state_start = family.find("    $state = devices_load($devicesFile);", name_start)
    desired_start = desired_family.find("    $personName = ")
    desired_end = desired_family.find("    $state = devices_load($devicesFile);", desired_start)
    if min(name_start, state_start, desired_start, desired_end) < 0:
        raise SystemExit("INVALID_NAME_VALIDATION_ANCHOR")
    old_family = family
    family = family[:name_start] + desired_family[desired_start:desired_end] + family[state_start:]
    text = text.replace(old_family, family, 1)
    print("APPLIED=strict-applicant-name-validation")
# Older live versions retained pending requests but never refreshed their typed name.
# Only still-pending, not-approved installations are allowed to update this field.
if "Refresh a still-pending request with the name actually typed" not in family:
    pending_start = "    // Refresh a still-pending request with the name actually typed"
    # The repository may use another comment spelling; the guard is the logic.
    if "if (isset($requests[$scope]) && !(bool)($rights[$scope] ?? false))" not in family:
        repo_start = desired_family.find("    // Refresh a still-pending request")
        repo_end = desired_family.find("    if (!(bool)($rights[$scope] ?? false)", repo_start)
        target = family.find("    if (!(bool)($rights[$scope] ?? false)")
        if min(repo_start, repo_end, target) < 0:
            raise SystemExit("INVALID_PENDING_NAME_REFRESH_ANCHOR")
        old_family = family
        family = family[:target] + desired_family[repo_start:repo_end] + family[target:]
        text = text.replace(old_family, family, 1)
        print("APPLIED=pending-name-refresh")
if "family_link_person(" not in family:
    anchor = "    $device['access_requests'] = $requests;\n    $device['last_seen'] = time();"
    replacement = section(source, "    $device['access_requests'] = $requests;\n    // Approved/denied",
        "    $state['devices'][$derivedId] = $device;")
    if family.count(anchor) != 1:
        raise SystemExit("INVALID_FAMILY_REGISTER_ANCHOR")
    family = family.replace(anchor, replacement.rstrip("\n"), 1)
    match = re.search(r"('pending'\s*=>\s*isset\(\$requests\[\$scope\]\))\s*\]\);", family)
    if not match:
        raise SystemExit("INVALID_FAMILY_REGISTER_RESPONSE")
    family = family[:match.start()] + match.group(1) + ",\n        'person_id' => $personId, 'person_exists' => $existingPerson]);" + family[match.end():]
    old_family = section(text, "if ($action === 'family_register') {",
        "if ($action === 'family_check') {")
    text = text.replace(old_family, family, 1)
    print("APPLIED=signed-family-person-link")

pending_start = "if ($action === 'pending_requests') {"
pending_end = "if ($action === 'connection_status') {"
pending = section(text, pending_start, pending_end)
old_line = "                'person_name' => $personName,\n                'device_name' => $deviceName,"
new_line = "                'person_name' => $personName,\n                'person_id' => (string)($d['person_id'] ?? ''),\n                'device_name' => $deviceName,"
if new_line not in pending:
    if pending.count(old_line) != 1: raise SystemExit("INVALID_PENDING_REQUESTS_ANCHOR")
    text = text.replace(pending, pending.replace(old_line, new_line, 1), 1)
    print("APPLIED=pending-person-id")

listing = section(text, "if ($action === 'list') {",
    "if ($action === 'set_access_right') {")
if "if (!$owner && $personName === '') continue;" in listing:
    listing = listing.replace("if (!$owner && $personName === '') continue;", 
        "// All legacy registrations remain visible for owner-assisted naming.", 1)
    print("APPLIED=show-unnamed-legacy")
if "$d['person_id'] = trim((string)($d['person_id'] ?? ''));" not in listing:
    old_line = "        $d['person_name'] = $personName;\n        $devices[] = $d;"
    new_line = "        $d['person_name'] = $personName;\n        $d['person_id'] = trim((string)($d['person_id'] ?? ''));\n        $devices[] = $d;"
    if listing.count(old_line) != 1: raise SystemExit("INVALID_LIST_PERSON_ANCHOR")
    listing = listing.replace(old_line, new_line, 1)
    print("APPLIED=return-person-id")
old_list = section(text, "if ($action === 'list') {",
    "if ($action === 'set_access_right') {")
text = text.replace(old_list, listing, 1)

owner_action = section(source,
    "if ($action === 'set_person_name') {",
    "if ($action === 'set_access_right') {")
insert_before("if ($action === 'set_access_right') {",
    owner_action, "owner-person-assignment")

if text.count("function family_link_person(") != 1:
    raise SystemExit("PERSON_HELPER_INVALID")
if text.count("if ($action === 'set_person_name') {") != 1:
    raise SystemExit("OWNER_PERSON_ACTION_INVALID")
for needle in ("'person_id' => $personId", "'person_exists' => $existingPerson",
               "$device['access_requests'] = $requests;"):
    if needle not in text: raise SystemExit("MISSING_CONTRACT=" + needle)
if "<<<<<<<" in text or ">>>>>>>" in text:
    raise SystemExit("UNRESOLVED_CONFLICT_MARKERS")

def symbols(doc):
    functions = set(re.findall(r"function\s+([A-Za-z_]\w*)\s*\(", doc))
    actions = set(re.findall(r"\$action\s*===\s*['\"]([A-Za-z_]\w*)['\"]", doc))
    return functions | {"action:" + x for x in actions}
lost = symbols(live.read_text(encoding="utf-8")) - symbols(text)
if lost:
    raise SystemExit("LIVE_SYMBOLS_REMOVED=" + ",".join(sorted(lost)))
output.write_text(text, encoding="utf-8")
print("SAFE_SURGICAL_MERGE=staged")
