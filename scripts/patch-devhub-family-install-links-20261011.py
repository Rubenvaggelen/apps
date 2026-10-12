#!/usr/bin/env python3
"""Surgically add an independent Family installer catalog on existing live Dev Hub.

Accepts existing live index.php and builds.php bytes. Does not replace editor,
login, license data, or unrelated live link changes.
"""
import re
import sys
from pathlib import Path

if len(sys.argv)!=4: raise SystemExit('usage: patch-devhub-family.py index.php builds.php outdir')
index=Path(sys.argv[1]).read_text()
builds=Path(sys.argv[2]).read_text()
dest=Path(sys.argv[3]);dest.mkdir(parents=True,exist_ok=True)
media='https://github.com/Rubenvaggelen/apps/releases/download/media-player-v2400/The-One-Media-Player-v2400.apk'
pat=r'(<a\b[^>]*\bid="mediaDownload"[^>]*\bhref=")[^"]*(")'
index,n=re.subn(pat,lambda m:m.group(1)+media+m.group(2),index)
if n!=1:raise SystemExit('REFUSE: live index mediaDownload anchor missing or duplicated')
if 'family-installaties.php' not in index:
    # Live page may have customized download heading; anchor to the existing
    # verified mediaDownload link without rebuilding the live dashboard.
    anchors=list(re.finditer(r'<a\b[^>]*\bid="mediaDownload"',index))
    if len(anchors)!=1:raise SystemExit('REFUSE: live index media download link changed')
    at=anchors[0].start()
    index=index[:at]+'<a class="download-link" href="family-installaties.php">▦ Alle The One Family-installaties ↗</a>\n     '+index[at:]
index=index.replace('Media Player v2316 en Android TV','Media Player v2400 en Android TV')
pat2=r"(\$mediaDownload\s*=\s*')[^']*(';)"
builds,n=re.subn(pat2,lambda m:m.group(1)+media+m.group(2),builds)
if n!=1:raise SystemExit('REFUSE: live builds $mediaDownload assignment missing or duplicated')
if 'family-installaties.php' not in builds:
    anchors=list(re.finditer(r'<a\b[^>]*\bid="appDownload"',builds))
    if len(anchors)!=1:raise SystemExit('REFUSE: live builds app download link changed')
    at=anchors[0].start()
    builds=builds[:at]+'<a class="btn" href="family-installaties.php">▦ Alle apps en Windows-installaties ↗</a>\n '+builds[at:]
# Upgrade known stale side links only, never rewrite newer or independently managed URLs.
builds=builds.replace('dj-windows-v234/The-One-DJ-Windows.zip','dj-windows-v240/The-One-DJ-Windows.zip')
builds=builds.replace('run-v1008/runcoach-debug.apk','run-v1012/runcoach-debug.apk')
builds=builds.replace('<div class="big">Build 1008</div>','<div class="big">Build 1012</div>')
assert index.count('family-installaties.php')>=1
assert builds.count('family-installaties.php')>=1
assert index.count(media)==1
assert builds.count(media)==1
(dest/'index.php').write_text(index)
(dest/'builds.php').write_text(builds)
print('PASS: surgically patched live Dev Hub pages without changing other app IDs')
