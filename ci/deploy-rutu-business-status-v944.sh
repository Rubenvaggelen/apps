#!/usr/bin/env bash
# Business-only UI repair. Never overwrite the customer APK, server, orders or history.
set -Eeuo pipefail
stage="$1"
new_sha="$2"
updates="$HOME/public_html/rutu-updates"
business="$updates/Rutu-BBQ-Bedrijf-Android.apk"
meta="$updates/business-android.json"
customer="$updates/Rutu-BBQ-Android.apk"
devcopy="$HOME/dev.vanaggelen.com/rutu-downloads/Rutu-BBQ-Bedrijf-Android.apk"
old_business_sha='5f7d40c0ec366e5c1f4b846bd2b36e1e319e9e8126cf0dda10b8c4f0b4de51fc'
customer_sha='3986f6bb6573c4ce0ab347513fa5ffd221c29575d9a76805c23427d481cc98d0'
sha(){ sha256sum "$1" | awk '{print $1}'; }
umask 077
case "$stage" in "$HOME"/.rutu-business-status-944.*) ;; *) echo REFUSE_INVALID_STAGE; exit 2;; esac
for f in "$business" "$meta" "$customer" "$devcopy" "$stage/business.apk" "$stage/business.json";do
  test -f "$f" && test ! -L "$f" || { echo REFUSE_MISSING_OR_SYMLINK; exit 3; }
done
test "$(sha "$business")" = "$old_business_sha" || { echo REFUSE_BUSINESS_CHANGED; exit 4; }
test "$(sha "$devcopy")" = "$old_business_sha" || { echo REFUSE_DEVHUB_CHANGED; exit 5; }
test "$(sha "$customer")" = "$customer_sha" || { echo REFUSE_CUSTOMER_CHANGED; exit 6; }
test "$(sha "$stage/business.apk")" = "$new_sha" || { echo STAGE_HASH_INVALID; exit 7; }
python3 - "$meta" "$stage/business.json" "$new_sha" <<'PY'
import json,sys
old=json.load(open(sys.argv[1]))
new=json.load(open(sys.argv[2]))
assert old['versionCode']==943
assert old['sha256']=='5f7d40c0ec366e5c1f4b846bd2b36e1e319e9e8126cf0dda10b8c4f0b4de51fc'
assert new['versionCode']==944 and new['sha256']==sys.argv[3]
assert new['url']=='https://rubenvanaggelen.com/rutu-updates/Rutu-BBQ-Bedrijf-Android.apk'
PY
backup="$HOME/rutu-data/backups/business-status-944-$(date -u +%Y%m%dT%H%M%SZ)"
install -d -m 0700 "$backup"
cp -p "$business" "$backup/business.apk"
cp -p "$devcopy" "$backup/devcopy.apk"
cp -p "$meta" "$backup/business.json"
rollback(){
  code=$?
  trap - ERR
  cp -p "$backup/business.apk" "$business" || true
  cp -p "$backup/devcopy.apk" "$devcopy" || true
  cp -p "$backup/business.json" "$meta" || true
  echo RUTU_BUSINESS_V944_ROLLBACK
  exit "$code"
}
trap rollback ERR
test "$(sha "$business")" = "$old_business_sha"
test "$(sha "$customer")" = "$customer_sha"
install -m 0644 "$stage/business.apk" "$business.rutu944.tmp"
install -m 0644 "$stage/business.apk" "$devcopy.rutu944.tmp"
install -m 0644 "$stage/business.json" "$meta.rutu944.tmp"
mv -f "$business.rutu944.tmp" "$business"
mv -f "$devcopy.rutu944.tmp" "$devcopy"
mv -f "$meta.rutu944.tmp" "$meta"
test "$(sha "$business")" = "$new_sha"
test "$(sha "$devcopy")" = "$new_sha"
test "$(sha "$customer")" = "$customer_sha"
curl -fsS --max-time 20 'https://rubenvanaggelen.com/rutu-api/index.php?action=health' | python3 -c 'import json,sys;assert json.load(sys.stdin)["ok"]'
trap - ERR
rm -f "$stage/business.apk" "$stage/business.json"
rmdir "$stage" || true
echo RUTU_BUSINESS_V944_STATUS_ACTIONS_DEPLOYED
