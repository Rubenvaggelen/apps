#!/usr/bin/env bash
# The One TV Android test APK. Never change the live Family update manifest.
set -euo pipefail
for name in DEPLOY_HOST DEPLOY_PORT DEPLOY_USER DEPLOY_SSH_KEY DEPLOY_SSH_PASSPHRASE DEPLOY_KNOWN_HOSTS BUILD_APK_PATH BUILD_APK_NAME; do
  test -n "${!name}" || { echo "MISSING_SETTING=$name"; exit 1; }
done
test -s "$BUILD_APK_PATH"
[[ "$BUILD_APK_NAME" =~ ^The-One-TV-Android-[0-9.]+\.apk$ ]] ||
  { echo "INVALID_CANDIDATE_NAME"; exit 2; }
# Fail closed if the Dev Hub site is not protected by Basic authentication.
code=$(curl -L -sS --connect-timeout 10 --max-time 25 -o /dev/null -w '%{http_code}' \
  'https://dev.rubenvanaggelen.com/builds.php')
if [ "$code" != 401 ]; then
  echo "PRIVATE_DEV_HUB_NOT_PROVEN=HTTP_$code"
  echo "Candidate remains encrypted in GitHub Actions."
  exit 0
fi
# Confirm authorization applies to a new file under the exact candidate path.
# A PHP-only 401 on builds.php is insufficient to protect raw APK files.
probe=$(curl -L -sS --connect-timeout 10 --max-time 25 -o /dev/null -w '%{http_code}' \
  "https://dev.rubenvanaggelen.com/the-one-tv-android-tests/this-file-must-require-login")
if [ "$probe" != 401 ]; then
  echo "DIRECT_CANDIDATE_PATH_NOT_PROTECTED=HTTP_$probe"
  echo "No confidential APK uploaded; encrypted artifact remains available."
  exit 0
fi
sha=$(sha256sum "$BUILD_APK_PATH" | awk '{print $1}')
umask 077
d=$(mktemp -d)
trap 'ssh-agent -k >/dev/null 2>&1 || true; rm -rf "$d"' EXIT
printf '%s\n' "$DEPLOY_SSH_KEY" | tr -d '\r' > "$d/key"
printf '%s\n' "$DEPLOY_KNOWN_HOSTS" | tr -d '\r' > "$d/known_hosts"
printf '#!/bin/sh\nprintf "%%s\\n" "$DEPLOY_SSH_PASSPHRASE"\n' > "$d/askpass"
chmod 600 "$d/key" "$d/known_hosts"
chmod 700 "$d/askpass"
eval "$(ssh-agent -s)" >/dev/null
SSH_ASKPASS="$d/askpass" SSH_ASKPASS_REQUIRE=force DISPLAY=:0 ssh-add "$d/key" </dev/null
unset DEPLOY_SSH_KEY DEPLOY_SSH_PASSPHRASE
opts=(-i "$d/key" -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes \
      -o UserKnownHostsFile="$d/known_hosts" -o ConnectTimeout=20)
remote="$DEPLOY_USER@$DEPLOY_HOST"
stage=$(ssh -p "$DEPLOY_PORT" "${opts[@]}" "$remote" \
  'umask 077; test -f "$HOME/dev.vanaggelen.com/builds.php"; mktemp -d "$HOME/.tv-android-candidate.XXXXXXXX"')
scp -O -P "$DEPLOY_PORT" "${opts[@]}" "$BUILD_APK_PATH" "$remote:$stage/$BUILD_APK_NAME"
ssh -p "$DEPLOY_PORT" "${opts[@]}" "$remote" \
  "bash -s -- '$stage' '$BUILD_APK_NAME' '$sha'" <<'REMOTE'
set -euo pipefail
stage="$1"; name="$2"; expected="$3"
root="$HOME/dev.vanaggelen.com"
target="$root/the-one-tv-android-tests"
test -f "$root/builds.php"
test ! -L "$root" && test ! -L "$target"
test -s "$stage/$name"
test "$(sha256sum "$stage/$name" | awk '{print $1}')" = "$expected"
mkdir -p "$target"
chmod 0755 "$target"
if [ -f "$target/$name" ]; then
  test "$(sha256sum "$target/$name" | awk '{print $1}')" = "$expected" ||
    { echo "REFUSE_OVERWRITE_TEST_CANDIDATE"; exit 3; }
else
  cp "$stage/$name" "$target/.$name.stage"
  chmod 0644 "$target/.$name.stage"
  mv -n "$target/.$name.stage" "$target/$name"
  test "$(sha256sum "$target/$name" | awk '{print $1}')" = "$expected"
fi
rm -f "$stage/$name"
rmdir "$stage"
echo "TV_ANDROID_CANDIDATE=uploaded-to-private-devhub"
echo "PRODUCTION_FAMILY_UPDATE_MANIFEST=unchanged"
REMOTE
code=$(curl -L -sS --connect-timeout 10 --max-time 25 -o /dev/null -w '%{http_code}' \
  "https://dev.rubenvanaggelen.com/the-one-tv-android-tests/$BUILD_APK_NAME")
test "$code" = 401 || { echo "UNSAFE_CANDIDATE_HTTP=$code"; exit 4; }
echo "TV_ANDROID_CANDIDATE_UNAUTHENTICATED_ACCESS=denied"
