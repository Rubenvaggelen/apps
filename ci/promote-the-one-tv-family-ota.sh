#!/usr/bin/env bash
# Owner-initiated, one-off promotion: signed Android v1.0.9 + Google TV v1.0.12.
# Source APKs: already signed and emulator-tested, private candidates only.
set -euo pipefail
umask 077
for name in DEPLOY_HOST DEPLOY_PORT DEPLOY_USER DEPLOY_SSH_KEY DEPLOY_SSH_PASSPHRASE DEPLOY_KNOWN_HOSTS SIGNING_KEY; do
  test -n "${!name}" || { echo "Missing $name"; exit 1; }
done
dir=$(mktemp -d)
remote_stage=""
trap 'ssh-agent -k >/dev/null 2>&1 || true; rm -rf "$dir"' EXIT
printf '%s\n' "$DEPLOY_SSH_KEY" | tr -d '\r' > "$dir/key"
printf '%s\n' "$DEPLOY_KNOWN_HOSTS" | tr -d '\r' > "$dir/known_hosts"
printf '#!/bin/sh\nprintf "%%s\\n" "$DEPLOY_SSH_PASSPHRASE"\n' > "$dir/askpass"
chmod 600 "$dir/key" "$dir/known_hosts";chmod 700 "$dir/askpass"
eval "$(ssh-agent -s)" >/dev/null
SSH_ASKPASS="$dir/askpass" SSH_ASKPASS_REQUIRE=force DISPLAY=:0 ssh-add "$dir/key" </dev/null
printf '%s' "$SIGNING_KEY" | base64 -d > "$dir/signing.keystore"
unset SIGNING_KEY DEPLOY_SSH_KEY DEPLOY_SSH_PASSPHRASE
opts=(-i "$dir/key" -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$dir/known_hosts" -o ConnectTimeout=20)
remote="$DEPLOY_USER@$DEPLOY_HOST"
# Confirm that both existing candidates require authentication, not just PHP pages.
for url in \
  'https://dev.rubenvanaggelen.com/the-one-tv-android-tests/The-One-TV-Android-1.0.9.apk' \
  'https://dev.rubenvanaggelen.com/sony-bravia-audio-tests/The-One-TV-Google-TV-Sony-1.0.12.apk'; do
  code=$(curl -L -sS --connect-timeout 10 --max-time 25 -o /dev/null -w '%{http_code}' "$url")
  test "$code" = 401 || { echo "ABORT_SOURCE_NOT_AUTHENTICATED_HTTP_$code"; exit 2; }
done
# Copy the exact released, tested candidate bytes to this ephemeral runner.
scp -O -P "$DEPLOY_PORT" "${opts[@]}" \
  "$remote:/home/vanawiwj/dev.vanaggelen.com/the-one-tv-android-tests/The-One-TV-Android-1.0.9.apk" \
  "$dir/The-One-TV-Android-v1.0.9.apk"
scp -O -P "$DEPLOY_PORT" "${opts[@]}" \
  "$remote:/home/vanawiwj/dev.vanaggelen.com/sony-bravia-audio-tests/The-One-TV-Google-TV-Sony-1.0.12.apk" \
  "$dir/The-One-TV-Google-TV-v1.0.12.apk"
bt=$(find "$ANDROID_HOME/build-tools" -maxdepth 1 -type d | sort -V | tail -1)
keytool -exportcert -keystore "$dir/signing.keystore" -storepass android \
  -alias androiddebugkey -file "$dir/owner.der" >/dev/null 2>&1
owner_sha=$(sha256sum "$dir/owner.der" | cut -d' ' -f1)
for name in The-One-TV-Android-v1.0.9.apk The-One-TV-Google-TV-v1.0.12.apk; do
  "$bt/apksigner" verify --verbose --print-certs "$dir/$name" > "$dir/$name-signature.txt"
  grep -qi "certificate SHA-256 digest: $owner_sha" "$dir/$name-signature.txt" || \
    { echo "REJECT_UNRECOGNIZED_SIGNING_CERTIFICATE=$name"; exit 3; }
  "$bt/aapt" dump badging "$dir/$name" | sed -n '1p' > "$dir/$name-badging.txt"
done
python3 - "$dir" <<'PY'
import sys, pathlib, hashlib, datetime, json, re
folder=pathlib.Path(sys.argv[1])
specs=[
  ("tv-phone","com.theone.tv.phone",109,"1.0.9","The-One-TV-Android-v1.0.9.apk","e3015d82946b358501a089eb37ec43c762a82f11"),
  ("tv-google-tv","com.theone.tv",112,"1.0.12","The-One-TV-Google-TV-v1.0.12.apk","c7771e8e07dd26fb805db531e54a6881548fee54")
]
builds=[]
for build_id,package,code,version,name,commit in specs:
    badging=(folder/(name+"-badging.txt")).read_text().strip()
    regex=r"^package: name='"+re.escape(package)+r"' versionCode='"+str(code)+r"' versionName='"+re.escape(version)+r"'(?:\s|$)"
    if not re.search(regex,badging):
        raise SystemExit("REJECT_INCORRECT_APPLICATION_OR_VERSION="+name)
    path=folder/name
    if not path.is_file() or path.stat().st_size<100_000:
        raise SystemExit("REJECT_MISSING_APK="+name)
    builds.append({
      "id":build_id,"package":package,"version_code":code,"version_name":version,
      "apk":name,"sha256":hashlib.sha256(path.read_bytes()).hexdigest(),
      "size_bytes":path.stat().st_size,"package_badging":badging,
      "commit":commit,
      "created_date":datetime.datetime.now(datetime.timezone.utc).date().isoformat()
    })
(folder/"metadata.json").write_text(json.dumps({"builds":builds}))
print("SIGNED_APKS_VERIFIED=2")
print("APP_PACKAGE_AND_VERSION_MATCH=tv-phone:109,tv-google-tv:112")
PY
remote_stage=$(ssh -p "$DEPLOY_PORT" "${opts[@]}" "$remote" \
  'umask 077; test -d /home/vanawiwj/the-one-private-builds/2026-10-13/apks; mktemp -d /home/vanawiwj/the-one-private-builds/.tv-family-ota.XXXXXXXX')
trap 'ssh-agent -k >/dev/null 2>&1 || true; rm -rf "$dir"; if [ -n "$remote_stage" ]; then ssh -p "$DEPLOY_PORT" "${opts[@]}" "$remote" "rm -rf -- \"$remote_stage\"" >/dev/null 2>&1 || true; fi' EXIT
scp -O -P "$DEPLOY_PORT" "${opts[@]}" \
  "$dir/The-One-TV-Android-v1.0.9.apk" "$dir/The-One-TV-Google-TV-v1.0.12.apk" \
  "$dir/metadata.json" ci/promote-the-one-tv-family-ota.php \
  "$remote:$remote_stage/"
ssh -p "$DEPLOY_PORT" "${opts[@]}" "$remote" \
  "bash -s -- '$remote_stage'" <<'REMOTE'
set -euo pipefail
stage="$1"
php -l "$stage/promote-the-one-tv-family-ota.php" >/dev/null
api="/home/vanawiwj/public_html/the-one-remote-api/family-updates.php"
# Never publish if the authentication service points at different app scopes.
grep -Fq "'com.theone.tv.phone' => ['tv_android', 'tv-phone']" "$api"
grep -Fq "'com.theone.tv' => ['tv_android', 'tv-google-tv']" "$api"
php "$stage/promote-the-one-tv-family-ota.php" "$stage" "$stage/metadata.json"
REMOTE
# Authenticated APK bytes remain private. The only public endpoint is a health check.
curl -fLsS --max-time 15 \
  'https://rubenvanaggelen.com/the-one-remote-api/family-updates.php?action=health' | \
  python3 -c 'import json,sys; d=json.load(sys.stdin); assert d.get("ok") is True'
echo "THE_ONE_TV_FAMILY_OTA_PROMOTION_VERIFIED=YES"
