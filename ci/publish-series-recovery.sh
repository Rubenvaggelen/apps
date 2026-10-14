#!/usr/bin/env bash
set -euo pipefail
for name in DEPLOY_HOST DEPLOY_PORT DEPLOY_USER DEPLOY_SSH_KEY DEPLOY_SSH_PASSPHRASE DEPLOY_KNOWN_HOSTS; do test -n "${!name}" || { echo "Missing deployment setting: $name";exit 1; };done
icon_key_dir=$(mktemp -d)
trap 'ssh-agent -k >/dev/null 2>&1 || true; rm -rf "$icon_key_dir"' EXIT
printf '%s\n' "$DEPLOY_SSH_KEY" | tr -d '\r' > "$icon_key_dir/key"
printf '%s\n' "$DEPLOY_KNOWN_HOSTS" | tr -d '\r' > "$icon_key_dir/known_hosts"
printf '#!/bin/sh\nprintf "%%s\\n" "$DEPLOY_SSH_PASSPHRASE"\n' > "$icon_key_dir/askpass"
chmod 600 "$icon_key_dir/key" "$icon_key_dir/known_hosts";chmod 700 "$icon_key_dir/askpass"
eval "$(ssh-agent -s)" >/dev/null
SSH_ASKPASS="$icon_key_dir/askpass" SSH_ASKPASS_REQUIRE=force DISPLAY=:0 ssh-add "$icon_key_dir/key" </dev/null
unset DEPLOY_SSH_KEY DEPLOY_SSH_PASSPHRASE
icon_opts=(-i "$icon_key_dir/key" -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$icon_key_dir/known_hosts" -o ConnectTimeout=20)
icon_remote="$DEPLOY_USER@$DEPLOY_HOST"
test -s "$BUILD_APK_PATH"
BUILD_PACKAGE_BADGING=$("$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$BUILD_APK_PATH" | sed -n '1p')
export BUILD_PACKAGE_BADGING
python3 - <<'PY'
import os,pathlib,json,hashlib,datetime
p=pathlib.Path(os.environ['BUILD_APK_PATH'])
value={'id':os.environ['BUILD_APP_ID'],'apk':p.name,'version_name':os.environ['BUILD_VERSION_NAME'],'size_bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'commit':os.environ['GITHUB_SHA'],'package_badging':os.environ['BUILD_PACKAGE_BADGING'],'created_date':datetime.datetime.now(datetime.timezone.utc).date().isoformat()}
pathlib.Path('tv-build-publish.json').write_text(json.dumps(value))
PY
icon_remote_stage=$(ssh -p "$DEPLOY_PORT" "${icon_opts[@]}" "$icon_remote" 'umask 077; test -d /home/vanawiwj/the-one-private-builds/2026-10-13/apks; mktemp -d /home/vanawiwj/the-one-private-builds/tv-icons.XXXXXXXX')
scp -O -P "$DEPLOY_PORT" "${icon_opts[@]}" "$BUILD_APK_PATH" "$icon_remote:$icon_remote_stage/build.apk"
scp -O -P "$DEPLOY_PORT" "${icon_opts[@]}" tv-build-publish.json ci/devhub-series-recovery.php "$icon_remote:$icon_remote_stage/"
ssh -p "$DEPLOY_PORT" "${icon_opts[@]}" "$icon_remote" "bash -s -- '$icon_remote_stage'" <<'REMOTE'
set -euo pipefail
icon_stage="$1"
php -l "$icon_stage/devhub-series-recovery.php" >/dev/null
php -r '$m=json_decode(file_get_contents($argv[1]),true);$source=$argv[2];if(hash_file("sha256",$source)!==$m["sha256"])exit(1);if(!preg_match("/^[A-Za-z0-9._-]+\\.apk$/",$m["apk"]))exit(2);$dest="/home/vanawiwj/the-one-private-builds/2026-10-13/apks/".$m["apk"];copy($source,$dest.".new");chmod($dest.".new",0600);rename($dest.".new",$dest);' "$icon_stage/tv-build-publish.json" "$icon_stage/build.apk"
php "$icon_stage/devhub-series-recovery.php" "$icon_stage/tv-build-publish.json"
rm -f "$icon_stage/build.apk" "$icon_stage/tv-build-publish.json" "$icon_stage/devhub-series-recovery.php"
rmdir "$icon_stage"
REMOTE

