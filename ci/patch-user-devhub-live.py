#!/usr/bin/env python3
"""Add the standalone The One User release to existing *live* Dev Hub pages.

Surgical, idempotent insertion at the verified existing DJ download marker.
Does not touch user accounts, other APKs, auth, server config, or Main.
Invoked against snapshots of the current live files; originals remain unchanged
until the guarded deployment validates and backs them up.
"""
from pathlib import Path
import sys

if len(sys.argv) != 7:
    raise SystemExit("usage: patch-user-devhub-live.py BUILDS_IN INDEX_IN FAMILY_IN BUILDS_OUT INDEX_OUT FAMILY_OUT")

ASSET = "https://github.com/Rubenvaggelen/apps/releases/download/user-v1003/The-One-Android-User.apk"
MARKER = "<!-- THE_ONE_USER_DEVHUB_DOWNLOAD_V1 -->"
LIVE_ANCHOR = "<!-- THE_ONE_DJ_DEVHUB_LINK_20261010 -->"
SCRIPT_MARKER = "the-one-user-release-auto"

BUILD_CARD = f'''{MARKER}
<div class="the-one-user-download" style="margin:12px 0;padding:12px;border:1px solid #216b92;border-radius:12px">
  <strong>The One Gebruiker — zelfstandige Android APK</strong>
  <p style="margin:6px 0">Voor gebruikers; eigen licentie en toegangsaanvragen in Main. Een update hiervan vervangt Main niet.</p>
  <div class="download-row">
    <a class="btn ok" id="theOneUserDownload" href="{ASSET}">⬇ The One Gebruiker APK • v1003</a>
  </div>
</div>
'''
SIDEBAR_LINK = f'''{MARKER}
<a class="download-link" id="theOneUserDownload" href="{ASSET}">↓ The One Gebruiker APK • v1003</a>
'''

JS = """
<script id="the-one-user-release-auto">
(function () {
  var target = document.getElementById('theOneUserDownload');
  if (!target) return;
  fetch('https://api.github.com/repos/Rubenvaggelen/apps/releases?per_page=100', {
    cache: 'no-store', headers: {Accept: 'application/vnd.github+json'}
  })
    .then(function (response) {
      if (!response.ok) throw new Error('GitHub API ' + response.status);
      return response.json();
    })
    .then(function (releases) {
      if (!Array.isArray(releases)) return;
      var eligible = releases.filter(function (release) {
        return !release.draft && !release.prerelease &&
          /^user-v[0-9]+$/.test(String(release.tag_name || ''));
      });
      eligible.sort(function (a, b) {
        return Number(b.tag_name.slice(6)) - Number(a.tag_name.slice(6));
      });
      for (var i = 0; i < eligible.length; i++) {
        var release = eligible[i];
        var asset = (release.assets || []).find(function (item) {
          return item.name === 'The-One-Android-User.apk' ||
            /^The-One-Android-User-v[0-9]+\\.apk$/.test(String(item.name || ''));
        });
        if (!asset || !asset.browser_download_url) continue;
        target.href = asset.browser_download_url;
        target.textContent = '⬇ The One Gebruiker APK • ' + release.tag_name;
        return;
      }
    })
    .catch(function () {
      // Preserve the verified v1003 link if GitHub cannot be reached.
    });
})();
</script>
""".strip()

def patch_html(content: str, kind: str) -> str:
    if (MARKER in content or SCRIPT_MARKER in content:
        if (content.count(MARKER) != 1 or content.count(SCRIPT_MARKER) != 1 or \
                content.count('id="theOneUserDownload"') != 1:
            raise SystemExit("PARTIALLY_PATCHED_OR_DUPLICATED_" + kind)
        if ASSET not in content:
            raise SystemExit("EXISTING_USER_DOWNLOAD_NOT_VERIFIED_" + kind)
        print("DEVHUB_ALREADY_PRESENT=" + kind)
        return content

    if content.count(LIVE_ANCHOR) != 1 or content.count("</body>") != 1:
        raise SystemExit("UNSAFE_UI_ANCHOR_" + kind)
    if content.count('id="theOneUserDownload"') != 0:
        raise SystemExit("DUPLICATE_USER_ID_" + kind)
    if "<div class=\"download-panel\">" not in content and kind == "index":
        raise SystemExit("UNEXPECTED_SIDEBAR_LAYOUT")
    if "<h2>Downloads</h2>" not in content and kind == "builds":
        raise SystemExit("UNEXPECTED_DOWNLOADS_LAYOUT")
    before = content
    content = content.replace(LIVE_ANCHOR,
                              (BUILD_CARD if kind == "builds" else SIDEBAR_LINK) +
                              "\n" + LIVE_ANCHOR)
    content = content.replace("</body>", JS + "\n</body>")
    if content.count('id="theOneUserDownload"') != 1 or \
       content.count(SCRIPT_MARKER) != 1 or \
       content.count(LIVE_ANCHOR) != 1:
        raise SystemExit("PATCH_POSTCONDITION_FAILED_" + kind)
    if not content.startswith(before.split(LIVE_ANCHOR,1)[0]):
        raise SystemExit("UNRELATED_CONTENT_CHANGED_" + kind)
    print("DEVHUB_ADDED=" + kind)
    return content

def patch_family_catalogue(content: str) -> str:
    if "'The One Gebruiker'" in content:
        if content.count("'The One Gebruiker'") != 1 or                 "'user-v1003','The-One-Android-User.apk'" not in content:
            raise SystemExit("UNEXPECTED_EXISTING_FAMILY_USER_ENTRY")
        print("DEVHUB_ALREADY_PRESENT=family")
        return content
    rows = content.splitlines(keepends=True)
    main_indexes = [i for i, row in enumerate(rows)
                    if "['Android','The One Main'," in row]
    if len(main_indexes) != 1 or not any("['Android','The One DJ'," in row
                                          for row in rows):
        raise SystemExit("UNSAFE_FAMILY_CATALOGUE_LAYOUT")
    main_idx = main_indexes[0]
    prefix = rows[main_idx].split("['Android'")[0]
    new_row = prefix + "['Android','The One Gebruiker','v1003','user-v'," + \
        "'The-One-Android-User.apk','user-v1003','The-One-Android-User.apk'," + \
        "'available','Zelfstandige gebruikers-APK met eigenaarstoestemming en per-app mediarechten']," + chr(10)
    # Keep the existing PHP catalogue, just add one independent user-app row.
    rows.insert(main_idx+1, new_row)
    content = "".join(rows)
    if content.count("'The One Gebruiker'") != 1:
        raise SystemExit("FAMILY_USER_ENTRY_FAILED")
    print("DEVHUB_ADDED=family")
    return content

for source, output, kind in [
    (Path(sys.argv[1]), Path(sys.argv[4]), "builds"),
    (Path(sys.argv[2]), Path(sys.argv[5]), "index"),
    (Path(sys.argv[3]), Path(sys.argv[6]), "family")
]:
    existing = source.read_text(encoding="utf-8")
    modified = patch_family_catalogue(existing) if kind == "family" else patch_html(existing,kind)
    output.write_text(modified,encoding="utf-8")
    print("DEVHUB_OUTPUT_" + kind + "_BYTES=" + str(len(modified.encode("utf-8"))))
