#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd()
workflow_dir = ROOT / '.github' / 'workflows'

if not workflow_dir.exists():
    print(f'ERROR: workflow folder not found: {workflow_dir}', file=sys.stderr)
    sys.exit(2)

pattern = re.compile(r'^(\s*)uses:\s*android-actions/setup-android@([^\s#]+)(\s*(?:#.*)?)$')
changed_files = []
found = 0

for path in sorted(list(workflow_dir.glob('*.yml')) + list(workflow_dir.glob('*.yaml'))):
    original = path.read_text(encoding='utf-8')
    lines = original.splitlines(keepends=True)
    out = []
    i = 0
    changed = False

    while i < len(lines):
        line = lines[i]
        m = pattern.match(line.rstrip('\r\n'))
        if not m:
            out.append(line)
            i += 1
            continue

        found += 1
        indent = m.group(1)
        newline = '\r\n' if line.endswith('\r\n') else '\n'
        out.append(line)
        i += 1

        # Preserve blank/comment lines immediately after `uses:`.
        pending = []
        while i < len(lines) and (not lines[i].strip() or lines[i].lstrip().startswith('#')):
            pending.append(lines[i])
            i += 1

        # Existing `with:` block on this action.
        if i < len(lines) and re.match(r'^' + re.escape(indent) + r'with:\s*(?:#.*)?(?:\r?\n)?$', lines[i]):
            out.extend(pending)
            out.append(lines[i])
            i += 1
            prop_indent = indent + '  '
            block = []
            packages_done = False

            while i < len(lines):
                current = lines[i]
                stripped = current.strip()
                if not stripped or current.lstrip().startswith('#'):
                    block.append(current)
                    i += 1
                    continue
                leading = len(current) - len(current.lstrip(' '))
                if leading <= len(indent):
                    break
                if re.match(r'^' + re.escape(prop_indent) + r'packages\s*:', current):
                    block.append(f"{prop_indent}packages: ''{newline}")
                    packages_done = True
                    changed = True
                else:
                    block.append(current)
                i += 1

            if not packages_done:
                block.insert(0, f"{prop_indent}packages: ''{newline}")
                changed = True
            out.extend(block)
        else:
            # No with block: add one, then keep whatever followed untouched.
            out.append(f"{indent}with:{newline}")
            out.append(f"{indent}  packages: ''{newline}")
            out.extend(pending)
            changed = True

    updated = ''.join(out)
    if changed and updated != original:
        path.write_text(updated, encoding='utf-8')
        changed_files.append(path)

if found == 0:
    print('ERROR: no android-actions/setup-android action found in .github/workflows', file=sys.stderr)
    sys.exit(3)

print(f'Found {found} setup-android step(s).')
if changed_files:
    for p in changed_files:
        print(f'Patched: {p.relative_to(ROOT)}')
else:
    print("No changes needed: every setup-android step already has packages: ''.")
