#!/usr/bin/env python3
"""Only update the verified independent DJ download anchor in existing live Dev Hub."""
from pathlib import Path
import re,sys
if len(sys.argv)!=3: raise SystemExit('Usage: bump-live-dj-link.py source target')
source=Path(sys.argv[1]).read_text(encoding='utf-8')
marker='THE_ONE_DJ_DEVHUB_LINK_20261010'
if source.count(marker)!=1: raise SystemExit('Protected DJ download marker missing/duplicated')
anchor=re.compile(r'<a\b(?=[^>]*data-the-one-dj-download\b)[^>]*>.*?</a>',re.S|re.I)
match=list(anchor.finditer(source))
if len(match)!=1: raise SystemExit('Expected exactly one DJ download anchor')
old=match[0].group(0)
url='https://github.com/Rubenvaggelen/apps/releases/download/dj-v1049/thedj-debug.apk'
newurl='https://github.com/Rubenvaggelen/apps/releases/download/dj-v1050/thedj-debug.apk'
if old.count(url)!=1 or old.count('v1049')<2: raise SystemExit('DJ link differs from reviewed v1049 source')
updated=old.replace(url,newurl).replace('v1049','v1050')
result=source[:match[0].start()]+updated+source[match[0].end():]
if result.replace(updated,old,1)!=source: raise SystemExit('Unexpected unrelated diff')
Path(sys.argv[2]).write_text(result,encoding='utf-8')
print('PASS: DJ Dev Hub link v1049 -> v1050 ONLY; everything else identical')
