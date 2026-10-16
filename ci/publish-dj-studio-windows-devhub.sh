#!/usr/bin/env bash
# Add two verified Windows download cards inside existing authenticated Dev Hub.
# No rebuild, no changes to .htaccess, no replacement of app downloads.
set -euo pipefail
for variable in DEPLOY_HOST DEPLOY_PORT DEPLOY_USER DEPLOY_SSH_KEY DEPLOY_SSH_PASSPHRASE DEPLOY_KNOWN_HOSTS; do
  test -n "$(printenv "$variable")" || { echo "::error::Missing deployment setting"; exit 1; }
done
auth_status() {
  local code
  code="$(curl -sS -L --max-time 15 -o /dev/null -w '%{http_code}' "$1")"
  test "$code" = "401" || { echo "::error::Dev Hub password gate unavailable"; exit 1; }
}
auth_status "https://dev.rubenvanaggelen.com/builds.php"
auth_status "https://dev.rubenvanaggelen.com/windows-dj-studio-20261016.php"
local_file=devhub/windows-dj-studio-20261016.php
php -l "$local_file"
tmp="$(mktemp -d)"
trap 'ssh-agent -k >/dev/null 2>&1 || true; rm -rf "$tmp"' EXIT
printf '%s\n' "$DEPLOY_SSH_KEY" | tr -d '\r' > "$tmp/key"
printf '%s\n' "$DEPLOY_KNOWN_HOSTS" | tr -d '\r' > "$tmp/hosts"
printf '#!/bin/sh\nprintf "%%s\\n" "$DEPLOY_SSH_PASSPHRASE"\n' > "$tmp/askpass"
chmod 600 "$tmp/key" "$tmp/hosts"
chmod 700 "$tmp/askpass"
eval "$(ssh-agent -s)" >/dev/null
SSH_ASKPASS="$tmp/askpass" SSH_ASKPASS_REQUIRE=force DISPLAY=:0 ssh-add "$tmp/key" </dev/null
unset DEPLOY_SSH_KEY DEPLOY_SSH_PASSPHRASE
options=(-i "$tmp/key" -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$tmp/hosts" -o ConnectTimeout=20)
server="$DEPLOY_USER@$DEPLOY_HOST"
staging="$(ssh -p "$DEPLOY_PORT" "${options[@]}" "$server" 'mktemp -d "$HOME/.theone-windows-devhub.XXXXXXXX"')"
scp -O -P "$DEPLOY_PORT" "${options[@]}" "$local_file" "$server:$staging/windows-dj-studio-20261016.php"
ssh -p "$DEPLOY_PORT" "${options[@]}" "$server" "bash -s -- '$staging'" <<'REMOTE'
set -euo pipefail
staging="$1"
root="$HOME/dev.vanaggelen.com"
page="$root/builds.php"
include="$root/windows-dj-studio-20261016.php"
test -s "$page"
test -s "$root/.htaccess"
test -s "$staging/windows-dj-studio-20261016.php"
test ! -L "$page" && test ! -L "$include"
php -l "$page" >/dev/null
php -l "$staging/windows-dj-studio-20261016.php" >/dev/null
python3 - "$page" <<'PY'
import pathlib,sys
path=pathlib.Path(sys.argv[1])
raw=path.read_text()
if raw.count("</body>")!=1:raise SystemExit("Dev Hub page layout changed; refuse overwrite")
if "windows-dj-studio-20261016.php" in raw and raw.count("windows-dj-studio-20261016.php")!=1:
 raise SystemExit("Multiple Windows panels already installed")
PY
backup="$page.backup-windows-$(date +%Y%m%d-%H%M%S)-$$"
cp -p "$page" "$backup"
cmp -s "$page" "$backup"
cp "$staging/windows-dj-studio-20261016.php" "$include.tmp.$$"
chmod 640 "$include.tmp.$$"
mv -f "$include.tmp.$$" "$include"
python3 - "$page" <<'PY'
import pathlib,sys
page=pathlib.Path(sys.argv[1])
content=page.read_text()
label="windows-dj-studio-20261016.php"
if label not in content:
 content=content.replace("</body>","<?php require __DIR__ . '/windows-dj-studio-20261016.php'; ?>\n</body>",1)
 tmp=page.with_name(page.name+".win-panel-tmp")
 tmp.write_text(content)
 tmp.chmod(0o640)
 tmp.replace(page)
PY
if ! php -l "$page" >/dev/null; then
 cp -p "$backup" "$page"
 echo "Dev Hub syntax check failed; restored original"
 exit 1
fi
grep -q 'windows-dj-studio-20261016.php' "$page"
grep -q 'dj-windows-v240' "$include"
grep -q 'studio-v1.1.0' "$include"
rm -rf -- "$staging"
echo "DEVHUB_DJ_STUDIO_WINDOWS_CARDS_LIVE"
REMOTE
auth_status "https://dev.rubenvanaggelen.com/builds.php"
echo "DEVHUB_WINDOWS_AUTH_CONFIRMED"
