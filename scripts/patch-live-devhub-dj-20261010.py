#!/usr/bin/env python3
"""Add an independent The One DJ download to the ACTUAL Dev Hub pages.

Only two small insertions are allowed. This patch never replaces the live
Dev Hub dashboard with the older GitHub repository template.
"""
import re
from pathlib import Path
import sys

MARKER = "THE_ONE_DJ_DEVHUB_LINK_20261010"
FALLBACK = "https://github.com/Rubenvaggelen/apps/releases/download/dj-v1049/thedj-debug.apk"

def patch(source: str, page: str) -> str:
    if MARKER in source:
        raise ValueError("DJ link is already deployed; refuse double patch")
    if page == "builds":
        heading = list(re.finditer(r"<h2\b[^>]*>\s*Downloads\s*</h2>",source,re.I))
        if len(heading) != 1:
            raise ValueError("Expected one live Downloads section; refuse to overwrite")
        addition = '''
      <!-- THE_ONE_DJ_DEVHUB_LINK_20261010 -->
      <div class="the-one-dj-download" style="margin:12px 0;padding:12px;border:1px solid #216b92;border-radius:12px">
        <strong>The One DJ — zelfstandige APK</strong>
        <p style="margin:6px 0">Eigen versie en updates, zonder Main te wijzigen.</p>
        <a data-the-one-dj-download href="''' + FALLBACK + '''"
           style="display:inline-block;padding:9px 15px;border-radius:9px;background:#135c89;color:#fff;text-decoration:none;font-weight:bold">
          ⬇ Download The One DJ APK • v1049
        </a>
      </div>
'''
        match=heading[0]
        return source[:match.end()]+addition+source[match.end():]
    if page == "index":
        anchors=list(re.finditer(r"<a\b[^>]*\bid=[\"']carDownload[\"'][^>]*>[\s\S]*?</a>",source,re.I))
        if len(anchors)!=1:
            raise ValueError("Expected exactly one existing Car download in Dev Hub sidebar")
        addition='''\n    <!-- THE_ONE_DJ_DEVHUB_LINK_20261010 -->
    <a class="download-link" data-the-one-dj-download href="''' + FALLBACK + '''">↓ The One DJ APK • v1049</a>'''
        match=anchors[0]
        return source[:match.end()]+addition+source[match.end():]
    raise ValueError("Unknown page")

def assert_small_patch(old: str, new: str, page: str) -> None:
    if old == new or new.count(MARKER)!=1 or new.count("data-the-one-dj-download")!=1:
        raise ValueError("DJ patch verification failed")
    if old not in new:
        # Require new file to consist ONLY of additions around existing content.
        import difflib
        removed=[line for line in difflib.ndiff(old.splitlines(True),new.splitlines(True)) if line.startswith("- ")]
        if removed:
            raise ValueError("Existing live source lines changed; refuse unsafe deployment")

if __name__=="__main__":
    if len(sys.argv)!=4:
        raise SystemExit("usage: patch-live-devhub-dj.py builds|index input output")
    old=Path(sys.argv[2]).read_text()
    new=patch(old,sys.argv[1])
    assert_small_patch(old,new,sys.argv[1])
    Path(sys.argv[3]).write_text(new)
    print("SAFE DJ ADDITIVE PATCH",sys.argv[1],"only additions, original functionality intact")
