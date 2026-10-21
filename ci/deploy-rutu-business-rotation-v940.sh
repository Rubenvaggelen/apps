#!/usr/bin/env bash
# Only Rutu Business APK + update metadata. No API, customer APK, orders or payments touched.
set -Eeuo pipefail
stage="$1"
new_sha="$2"
base="$HOME/public_html/rutu-updates"
business="$base/Rutu-BBQ-Bedrijf-Android.apk"
customer="$base/Rutu-BBQ-Android.apk"
meta="$base/business-android.json"
devcopy="$HOME/dev.vanaggelen.com/rutu-downloads/Rutu-BBQ-Bedrijf-Android.apk"
old_sha='ea4e7efe647a666b5b3b5f0137b660d2ae4b2e8d7f15c28266760b3272a12eb0'
customer_sha='60d606b73aa44e3610902aad37df0b5e5f077f79b699589fbbd88c3699da534e'
sha(){ sha256sum "$1" | awk '{print $1}'; }
umask 077
case "$stage" in "$HOME"/.rutu-rotation-940.*) ;; *) echo REFUSE_STAGE_PATH; exit 2;; esac
for file in "$business" "$customer" "$meta" "$devcopy" "$stage/business.apk" "$stage/business.json";do
  test -f "$file" && test ! -L "$file" || { echo REFUSE_MISSING_OR_LINKED_FILE; exit 3; }
done
test "$(sha "$business")" = "$old_sha" || { echo LIVE_BUSINESS_HASH_MISMATCH; exit 4; }
test "$(sha "$devcopy")" = "$old_sha" || { echo LIVE_DEVHUB_HASH_MISMATCH; exit 5; }
test "$(sha "$customer")" = "$customer_sha" || { echo LIVE_CUSTOMER_HASH_MISMATCH; exit 6; }
test "$(sha "$stage/business.apk")" = "$new_sha" || { echo STAGED_APK_HASH_MISMATCH; exit 7; }
python3 - "$meta" "$stage/business.json" "$new_sha" <<'PY'
import json,sys
prior=json.load(open(sys.argv[1]))
next_=json.load(open(sys.argv[2]))
assert prior['versionCode']==939, 'Unexpected current production version'
assert prior['sha256']=='ea4e7efe647a666b5b3b5f0137b660d2ae4b2e8d7f15c28266760b3272a12eb0'
assert next_['versionCode']==940 and next_['sha256']==sys.argv[3]
assert next_['url']=='https://rubenvanaggelen.com/rutu-updates/Rutu-BBQ-Bedrijf-Android.apk'
PY
backup="$HOME/rutu-data/backups/rotation-only-v940-$(date -u +%Y%m%dT%H%M%SZ)"
install -d -m 0700 "$backup"
cp -p -- "$business" "$backup/business.apk"
cp -p -- "$devcopy" "$backup/devhub.apk"
cp -p -- "$meta" "$backup/business.json"
rollback() {
 code=$?
 trap - ERR
 cp -p -- "$backup/business.apk" "$business" || true
 cp -p -- "$backup/devhub.apk" "$devcopy" || true
 cp -p -- "$backup/business.json" "$meta" || true
 echo "ROLLBACK_COMPLETED_AFTER_ERROR=$code"
 exit "$code"
}
trap rollback ERR
test "$(sha "$business")" = "$old_sha"
test "$(sha "$devcopy")" = "$old_sha"
test "$(sha "$customer")" = "$customer_sha"
install -m 0644 "$stage/business.apk" "$business.rotation940.tmp"
install -m 0644 "$stage/business.apk" "$devcopy.rotation940.tmp"
install -m 0644 "$stage/business.json" "$meta.rotation940.tmp"
mv -f -- "$business.rotation940.tmp" "$business"
mv -f -- "$devcopy.rotation940.tmp" "$devcopy"
mv -f -- "$meta.rotation940.tmp" "$meta"
test "$(sha "$business")" = "$new_sha"
test "$(sha "$devcopy")" = "$new_sha"
test "$(sha "$customer")" = "$customer_sha"
curl -fsS --max-time 20 'https://rubenvanaggelen.com/rutu-api/index.php?action=health' |
 python3 -c 'import json,sys;assert json.load(sys.stdin)["ok"]'
trap - ERR
rm -f -- "$stage/business.apk" "$stage/business.json"
rmdir -- "$stage" || true
echo "RUTU_BUSINESS_V940_ONLY_DEPLOYED_BACKUP_KEPT"
