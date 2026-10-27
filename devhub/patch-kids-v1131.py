#!/usr/bin/env python3
"""Guarded, atomic Dev Hub registration of the already user-approved Kids v1131 APK.
Never exposes the private APK in a public repository or unauthenticated directory.
"""
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys

APP = "The-One-Kids-v1.0.1131-CoComelon-Nederlands.apk"
SHA = "0181d8a56a55d579be2c92630b73e5351673cb264858316055d70a0a8a7b2ad6"
ROOT = Path("/home/vanawiwj")
DOC = ROOT / "dev.vanaggelen.com"
PRIVATE = ROOT / "the-one-private-builds" / "kids"
STAGE = Path(sys.argv[1]).resolve()
SOURCE = STAGE / "kids-v1131.apk"
DOWNLOAD = STAGE / "kids-download.php"
FILES = [DOC / "builds.php", DOC / "index.php", DOC / "kids-download.php"]
assert SOURCE.is_file() and DOWNLOAD.is_file()
assert all(x.is_file() for x in FILES[:2])
assert re.search(r"AuthType\s+Basic", (DOC / ".htaccess").read_text(), re.I), "Dev Hub access must require Basic Auth"
assert hashlib.sha256(SOURCE.read_bytes()).hexdigest() == SHA, "Approved APK hash mismatch"

now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
current = PRIVATE / "latest.json"
if current.exists():
    existing = json.loads(current.read_text())
    assert int(existing.get("version_code", 0)) <= 1131, "Refusing to replace a newer Kids release"
    if int(existing.get("version_code", 0)) == 1131:
        assert existing.get("sha256") == SHA, "Conflicting Kids release with same version"

builds = FILES[0].read_text(encoding="utf-8")
index = FILES[1].read_text(encoding="utf-8")
download_code = DOWNLOAD.read_text(encoding="utf-8")

if 'id="kidsLatestBuildCard"' not in builds:
    mark = '<div class="grid">'
    assert builds.count(mark) == 1, "Ambiguous Dev Hub build grid"
    card = '''
    <div class="card" id="kidsLatestBuildCard">
      <h2>The One Kids — laatst goedgekeurde build</h2>
      <div class="big ok"><?php
        $kidInfo = json_decode((string)@file_get_contents('/home/vanawiwj/the-one-private-builds/kids/latest.json'), true) ?: [];
        echo htmlspecialchars('v'.(string)($kidInfo['version_name'] ?? '1.0.1131'), ENT_QUOTES, 'UTF-8');
      ?></div>
      <p class="muted">CoComelon Nederlands • bevestigd werkend • automatische updates actief</p>
      <div class="download-row"><a class="btn ok" href="kids-download.php">⬇ The One Kids APK (laatste build)</a></div>
    </div>
'''
    builds = builds.replace(mark, mark + '\n' + card, 1)

if 'id="kidsDownload"' not in index:
    match = re.search(r'^.*id="runDownload".*$', index, re.M)
    assert match, "Dev Hub downloads section changed: cannot find Run download link"
    index = index[:match.end()] + '\n    <a class="download-link" id="kidsDownload" href="kids-download.php">↓ The One Kids APK • laatste build</a>' + index[match.end():]

assert 'kidInfo' in builds and 'id="kidsDownload"' in index
assert 'REMOTE_USER' in download_code and 'PHP_AUTH_USER' in download_code
assert "the-one-private-builds/kids" in download_code and "latest.json" in download_code
assert "SHA256" not in download_code

PRIVATE.mkdir(parents=True, exist_ok=True)
os.chmod(PRIVATE, 0o700)
apks = PRIVATE / "apks"
apks.mkdir(exist_ok=True)
os.chmod(apks, 0o700)
backup = PRIVATE / "backups" / now
backup.mkdir(parents=True)
os.chmod(backup, 0o700)
for i, file in enumerate(FILES):
    if file.is_file():
        shutil.copy2(file, backup / file.name)
if current.is_file():
    shutil.copy2(current, backup / "latest.json")

def atomic_bytes(dest: Path, data: bytes, mode: int):
    temp = dest.with_name(dest.name + ".staging-" + now)
    try:
        with open(temp, "wb") as out:
            out.write(data)
            out.flush()
            os.fsync(out.fileno())
        os.chmod(temp, mode)
        temp.replace(dest)
    finally:
        if temp.exists():
            temp.unlink()

target = apks / APP
meta = {
    "id": "kids", "name": "The One Kids", "version_code": 1131,
    "version_name": "1.0.1131", "apk": APP, "sha256": SHA,
    "size_bytes": SOURCE.stat().st_size, "status": "approved-working",
    "notes": "CoComelon Nederlands; owner approved 2026-10-27",
    "source": "kids-owner-updates/channel/v1131", "release_date": "2026-10-27"
}
assert SOURCE.stat().st_size == 5375887, "Unexpected APK byte count"
changed = []
try:
    atomic_bytes(target, SOURCE.read_bytes(), 0o600)
    changed.append(target)
    atomic_bytes(current, (json.dumps(meta, indent=2, ensure_ascii=False) + '\n').encode(), 0o600)
    changed.append(current)
    atomic_bytes(FILES[2], download_code.encode(), 0o640)
    changed.append(FILES[2])
    atomic_bytes(FILES[0], builds.encode(), 0o640)
    changed.append(FILES[0])
    atomic_bytes(FILES[1], index.encode(), 0o640)
    changed.append(FILES[1])
except Exception:
    for f in reversed(changed):
        saved = backup / f.name
        if saved.is_file():
            shutil.copy2(saved, f)
        elif f.is_file():
            f.unlink()
    raise

assert hashlib.sha256(target.read_bytes()).hexdigest() == SHA
assert json.loads(current.read_text())["version_code"] == 1131
print("PUBLISHED Kids v1.0.1131 on private Dev Hub; build list and main download link updated")
print("VERIFIED SHA256=" + SHA)
print("BACKUP=" + str(backup))
