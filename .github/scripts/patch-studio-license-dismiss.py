#!/usr/bin/env python3
"""Surgical, idempotent patch for existing live The One Family license PHP.
Never reads or modifies licenses.json. Refuses unknown PHP layouts.
"""
import pathlib
import sys

BASE = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(".")
DRY = "--dry-run" in sys.argv

def replace_once(source, before, after, name):
    if after in source:
        return source
    if source.count(before) != 1:
        raise RuntimeError(f"{name}: expected one compatible anchor, found {source.count(before)}")
    return source.replace(before, after, 1)

api = BASE / "licenses.php"
core = BASE / "license-core.php"
old_api = api.read_text()
old_core = core.read_text()
api_action = "['owner_pair', 'owner_pending', 'owner_approve']"
api_updated = "['owner_pair', 'owner_pending', 'owner_approve', 'owner_reject', 'owner_delete']"
old_block = """            if ($action === 'owner_approve') {
                $id = (string)($body['request_id'] ?? '');
                if (!preg_match('/^[a-f0-9]{24}$/D', $id)) throw new InvalidArgumentException('Invalid request');
                $approved = one_license_owner_approve($state, $id);
                if ($approved) $dirty();
                return ['approved' => $approved];
            }
"""
insert_api = """            if ($action === 'owner_reject' || $action === 'owner_delete') {
                $ids = $body['request_ids'] ?? null;
                if (!is_array($ids)) throw new InvalidArgumentException('Invalid request list');
                // Authenticate owner FIRST, then atomically resolve only pending exact request IDs.
                $count = one_license_owner_resolve_pending(
                    $state, $ids, $action === 'owner_reject' ? 'rejected' : 'deleted'
                );
                if ($count > 0) $dirty();
                return ['changed_count' => $count];
            }
"""
api_new = replace_once(old_api, api_action, api_updated, "licenses.php allowed owner actions")
api_new = replace_once(api_new, old_block, old_block + insert_api, "licenses.php approval handler")

anchor = "function one_license_owner_approve(array &$state, string $requestId): bool {"
new_func = """/**
 * Owner-only server operation: decline or remove pending enrollment requests.
 * Existing grants, license tokens, registered devices and approved requests
 * are never touched. Batch is all-or-nothing, validated before mutation.
 */
function one_license_owner_resolve_pending(array &$state, array $ids, string $decision): int {
    if (!in_array($decision, ['rejected', 'deleted'], true)) {
        throw new InvalidArgumentException('Invalid decision');
    }
    if (count($ids) < 1 || count($ids) > 25) {
        throw new InvalidArgumentException('Invalid request list size');
    }
    $clean = [];
    foreach ($ids as $id) {
        if (!is_string($id) || !preg_match('/^[a-f0-9]{24}$/D', $id)) {
            throw new InvalidArgumentException('Invalid request');
        }
        $clean[$id] = true;
    }
    foreach (array_keys($clean) as $id) {
        if (!isset($state['requests'][$id]) ||
            ($state['requests'][$id]['status'] ?? '') !== 'pending') {
            throw new InvalidArgumentException('Request no longer pending');
        }
    }
    foreach (array_keys($clean) as $id) {
        if ($decision === 'deleted') {
            unset($state['requests'][$id]);
        } else {
            $state['requests'][$id]['status'] = 'rejected';
            $state['requests'][$id]['rejected_at'] = gmdate('c');
        }
    }
    return count($clean);
}

"""
core_new = replace_once(old_core, anchor, new_func + anchor, "license-core.php owner resolver")
for path, current, updated in ((api, old_api, api_new), (core, old_core, core_new)):
    if not updated.startswith("<?php"):
        raise RuntimeError(f"Refusing unexpected content in {path.name}")
    if not DRY and current != updated:
        path.write_text(updated)
    print(f"{path.name}: {'ready' if DRY else 'patched'}; unchanged={current == updated}")
