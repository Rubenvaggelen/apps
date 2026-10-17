#!/usr/bin/env bash
# Deliver only signed Android Media Player to the authenticated Dev Hub.
set -euo pipefail
APK=media-player/app/build/outputs/apk/release/app-release.apk
test -s "$APK"
for variable in DEPLOY_HOST DEPLOY_PORT DEPLOY_USER DEPLOY_SSH_KEY DEPLOY_SSH_PASSPHRASE DEPLOY_KNOWN_HOSTS; do
  test -n "$(printenv "$variable")" || { echo "::error::Missing $variable"; exit 1; }
done
guard_auth() {
  local result
  result=$(curl -sS -L --max-time 20 -o /dev/null -w '%{http_code}' "$1")
  test "$result" = "401" || { echo "::error::Dev Hub auth gate not confirmed: $result"; exit 1; }
}
guard_auth https://dev.rubenvanaggelen.com/builds.php
guard_auth 'https://dev.rubenvanaggelen.com/family-download-20261013.php?app=__probe__'
keydir=$(mktemp -d)
trap 'ssh-agent -k >/dev/null 2>&1 || true; rm -rf "$keydir"' EXIT
printf '%s\n' "$DEPLOY_SSH_KEY" | tr -d '\r' > "$keydir/key"
printf '%s\n' "$DEPLOY_KNOWN_HOSTS" | tr -d '\r' > "$keydir/known_hosts"
printf '#!/bin/sh\nprintf "%%s\\n" "$DEPLOY_SSH_PASSPHRASE"\n' > "$keydir/askpass"
chmod 600 "$keydir/key" "$keydir/known_hosts"
chmod 700 "$keydir/askpass"
eval "$(ssh-agent -s)" >/dev/null
SSH_ASKPASS="$keydir/askpass" SSH_ASKPASS_REQUIRE=force DISPLAY=:0 ssh-add "$keydir/key" </dev/null
unset DEPLOY_SSH_KEY DEPLOY_SSH_PASSPHRASE
opts=(-i "$keydir/key" -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$keydir/known_hosts" -o ConnectTimeout=20)
remote="$DEPLOY_USER@$DEPLOY_HOST"
sha=$(sha256sum "$APK" | awk '{print $1}')
NAME=The-One-Media-Player-Android-v2415-${sha:0:12}.apk
stage=$(ssh -p "$DEPLOY_PORT" "${opts[@]}" "$remote" 'mktemp -d "$HOME/.the-one-player-stage.XXXXXXXX"')
scp -O -P "$DEPLOY_PORT" "${opts[@]}" "$APK" "$remote:$stage/$NAME"
ssh -p "$DEPLOY_PORT" "${opts[@]}" "$remote" "bash -s -- '$stage' '$NAME' '$sha'" <<'REMOTE'
set -euo pipefail
stage="$1"
filename="$2"
sha="$3"
root="$HOME/the-one-private-builds/2026-10-13"
manifest="$root/manifest.json"
target="$root/apks/$filename"
webroot="$HOME/dev.vanaggelen.com"
for f in "$manifest" "$webroot/family-download-20261013.php" "$webroot/family-new-builds-20261013.php" "$webroot/.htaccess"; do
  test -s "$f" || { echo "Required protected Dev Hub file missing"; exit 1; }
done
test -d "$root/apks"
test -s "$stage/$filename"
test "$(sha256sum "$stage/$filename" | cut -d' ' -f1)" = "$sha"
php -l "$webroot/family-download-20261013.php" >/dev/null
php -l "$webroot/family-new-builds-20261013.php" >/dev/null
python3 - "$manifest" <<'PY'
import json,sys
with open(sys.argv[1],encoding="utf-8") as f: rows=json.load(f)
if not isinstance(rows,list): raise SystemExit("Invalid Dev Hub manifest format")
matches=[x for x in rows if isinstance(x,dict) and "media player" in str(x.get("app_name","")).lower() and "android" in str(x.get("platform","")).lower() and "tv" not in str(x.get("platform","")).lower()]
if len(matches)!=1:
 print("Card metadata:",[(x.get("id"),x.get("app_name"),x.get("platform")) for x in rows if isinstance(x,dict)])
 raise SystemExit("Expected exactly one Android Media Player card; nothing changed")
print("Existing Android Media Player card:",matches[0]["id"])
PY
backup="$manifest.backup-$(date +%Y%m%d%H%M%S)-$$"
cp -p "$manifest" "$backup"
cmp -s "$manifest" "$backup"
if [ -f "$target" ]; then
 test "$(sha256sum "$target" | cut -d' ' -f1)" = "$sha" || { echo "Conflicting APK; abort"; exit 1; }
else
 cp "$stage/$filename" "$target.tmp.$$"
 chmod 644 "$target.tmp.$$"
 mv -n "$target.tmp.$$" "$target"
fi
test "$(sha256sum "$target" | cut -d' ' -f1)" = "$sha"
python3 - "$manifest" "$filename" "$sha" "$target" <<'PY'
import json,os,sys,tempfile
manifest,name,sha,apk=sys.argv[1:]
with open(manifest,encoding="utf-8") as f: rows=json.load(f)
matches=[x for x in rows if isinstance(x,dict) and "media player" in str(x.get("app_name","")).lower() and "android" in str(x.get("platform","")).lower() and "tv" not in str(x.get("platform","")).lower()]
if len(matches)!=1: raise SystemExit("Concurrent card change detected")
item=matches[0]
item.update(apk=name,version_name="2.4.15-android-pornhub-tv-parity",version_code=2415,size_bytes=os.path.getsize(apk),sha256=sha)
fd,tmp=tempfile.mkstemp(prefix=".media-player-manifest-",dir=os.path.dirname(manifest))
try:
 with os.fdopen(fd,"w",encoding="utf-8") as f:
  json.dump(rows,f,ensure_ascii=False,indent=2)
  f.write("\n")
 os.chmod(tmp,0o640)
 os.replace(tmp,manifest)
finally:
 if os.path.exists(tmp):os.unlink(tmp)
with open(manifest,encoding="utf-8") as f: reloaded=json.load(f)
if not any(x.get("apk")==name and x.get("sha256")==sha for x in reloaded):raise SystemExit("Manifest verification failed")
print("MEDIA_PLAYER_ANDROID_2415_PUBLISHED_TO_PRIVATE_DEVHUB")
PY
rm -rf -- "$stage"
REMOTE
guard_auth https://dev.rubenvanaggelen.com/builds.php
guard_auth 'https://dev.rubenvanaggelen.com/family-download-20261013.php?app=__probe__'
echo "DEVHUB_PRIVATE_APK_READY"
