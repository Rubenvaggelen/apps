#!/usr/bin/env bash
set -euo pipefail
# Only add an attachment header to the existing RUTU customer APK URL.
# Never modify the printed QR, app binaries, or update metadata.
for v in DEPLOY_HOST DEPLOY_PORT DEPLOY_USER DEPLOY_SSH_KEY DEPLOY_SSH_PASSPHRASE DEPLOY_KNOWN_HOSTS; do
  test -n "$(printenv "$v")" || { echo "::error::Missing deployment setting"; exit 1; }
done
url="https://rubenvanaggelen.com/rutu-updates/Rutu-BBQ-Android.apk"
test "$(curl -sSLI --max-time 20 -o /dev/null -w '%{http_code}' "$url")" = 200
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
opts=(-i "$tmp/key" -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$tmp/hosts" -o ConnectTimeout=20)
ssh -p "$DEPLOY_PORT" "${opts[@]}" "$DEPLOY_USER@$DEPLOY_HOST" 'bash -s' <<'REMOTE'
set -euo pipefail
umask 077
root="$HOME/public_html/rutu-updates"
apk="$root/Rutu-BBQ-Android.apk"
rules="$root/.htaccess"
url="https://rubenvanaggelen.com/rutu-updates/Rutu-BBQ-Android.apk"
test -d "$root" && test ! -L "$root"
test -s "$apk" && test ! -L "$apk"
test ! -L "$rules"
test ! -e "$rules" || test -f "$rules"
original="$(sha256sum "$apk" | awk '{print $1}')"
pre="$(curl -sSI --max-time 25 "$url")"
printf '%s' "$pre" | grep -qiE '^content-type: (application/vnd\.android\.package-archive|application/octet-stream)' || exit 10
printf '%s' "$pre" | grep -qE '^HTTP/.* 200' || exit 11
begin='# BEGIN RUTU-QR-ATTACHMENT-20261016'
end='# END RUTU-QR-ATTACHMENT-20261016'
if test -f "$rules" && grep -Fq "$begin" "$rules"; then
  test "$(grep -Fc "$begin" "$rules")" -eq 1
  test "$(grep -Fc "$end" "$rules")" -eq 1
else
  backup="$(mktemp -d "$HOME/.rutu-qr-backup.XXXXXXXX")"
  existed=false
  if test -f "$rules"; then cp -p "$rules" "$backup/htaccess"; existed=true; fi
  stage="$(mktemp "$root/.qr-header-stage.XXXXXXXX")"
  changed=false
  rollback() {
    status=$?
    trap - EXIT
    if test "$status" -ne 0 && test "$changed" = true; then
      if test "$existed" = true; then cp -p "$backup/htaccess" "$rules.restore.$$"; mv -f "$rules.restore.$$" "$rules"
      else rm -f "$rules"; fi
      echo 'RUTU_QR_HEADER_ROLLBACK_DONE'
    fi
    rm -f "$stage"
    exit "$status"
  }
  trap rollback EXIT
  if test "$existed" = true; then cat "$rules" > "$stage"; fi
  cat >> "$stage" <<'APACHE'

# BEGIN RUTU-QR-ATTACHMENT-20261016
# Request a direct file download. QR target and APK remain unchanged.
<IfModule mod_headers.c>
<FilesMatch "^Rutu-BBQ-Android\.apk$">
Header always set Content-Disposition "attachment; filename=Rutu-BBQ-Android.apk"
Header always set X-Content-Type-Options "nosniff"
Header always set Cache-Control "no-store, max-age=0"
</FilesMatch>
</IfModule>
# END RUTU-QR-ATTACHMENT-20261016
APACHE
  chmod 644 "$stage"
  if test "$existed" = true; then chmod --reference="$rules" "$stage"; fi
  mv "$stage" "$rules"
  changed=true
  after="$(curl -sSI --max-time 25 "$url")"
  printf '%s' "$after" | grep -qi '^content-disposition: attachment; filename=Rutu-BBQ-Android.apk' ||
    { echo 'RUTU_QR_ATTACHMENT_HEADER_FAILED'; exit 12; }
  printf '%s' "$after" | grep -qE '^HTTP/.* 200' || exit 13
  changed=false
  trap - EXIT
fi
test "$(sha256sum "$apk" | awk '{print $1}')" = "$original" || exit 14
echo "RUTU_QR_ORIGINAL_APK_AND_LINK_UNCHANGED"
echo "RUTU_QR_ATTACHMENT_HEADER_LIVE"
REMOTE
response="$(curl -sSI --max-time 20 "$url")"
printf '%s' "$response" | grep -qi '^content-disposition: attachment; filename=Rutu-BBQ-Android.apk'
printf '%s' "$response" | grep -qE '^HTTP/.* 200'
echo "RUTU_QR_SAME_LINK_DIRECT_DOWNLOAD_VERIFIED"
