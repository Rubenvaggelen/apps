#!/usr/bin/env bash
# Customer-only Rutu BBQ UI update; preserve business, backend, QR and existing orders.
set -Eeuo pipefail
stage="$1"
new_sha="$2"
site="$HOME/public_html/rutu-updates"
customer="$site/Rutu-BBQ-Android.apk"
meta="$site/android.json"
business="$site/Rutu-BBQ-Bedrijf-Android.apk"
old_sha='c05d1a4991012d5505a9beb07389af4445441cedbb2881eb425168e47fc3a6e4'
sha(){ sha256sum "$1" | awk '{print $1}'; }
umask 077
case "$stage" in "$HOME"/.rutu-customer-delivery-940.*) ;; *) echo REFUSE_INVALID_STAGE; exit 2;; esac
for f in "$customer" "$meta" "$business" "$stage/customer.apk" "$stage/android.json";do
  test -f "$f" && test ! -L "$f" || { echo REFUSE_MISSING_OR_LINKED_FILE; exit 3; }
done
test "$(sha "$customer")" = "$old_sha" || { echo CUSTOMER_V939_CHANGED; exit 4; }
business_sha="$(sha "$business")"
test "$(sha "$stage/customer.apk")" = "$new_sha" || { echo APK_STAGING_HASH_FAIL; exit 5; }
python3 - "$meta" "$stage/android.json" "$new_sha" <<'PY'
import json,sys
old=json.load(open(sys.argv[1]))
new=json.load(open(sys.argv[2]))
assert old['versionCode']==939 and old['sha256']=='c05d1a4991012d5505a9beb07389af4445441cedbb2881eb425168e47fc3a6e4'
assert new['versionCode']==940 and new['sha256']==sys.argv[3]
assert new['url']=='https://rubenvanaggelen.com/rutu-updates/Rutu-BBQ-Android.apk'
PY
backup="$HOME/rutu-data/backups/customer-delivery-v940-$(date -u +%Y%m%dT%H%M%SZ)"
install -d -m 0700 "$backup"
cp -p -- "$customer" "$backup/customer.apk"
cp -p -- "$meta" "$backup/android.json"
rollback(){
  code=$?
  trap - ERR
  cp -p -- "$backup/customer.apk" "$customer" || true
  cp -p -- "$backup/android.json" "$meta" || true
  echo "CUSTOMER_ROLLBACK=$code"
  exit "$code"
}
trap rollback ERR
test "$(sha "$business")" = "$business_sha"
test "$(sha "$customer")" = "$old_sha"
install -m 0644 "$stage/customer.apk" "$customer.rutu940.tmp"
install -m 0644 "$stage/android.json" "$meta.rutu940.tmp"
mv -f -- "$customer.rutu940.tmp" "$customer"
mv -f -- "$meta.rutu940.tmp" "$meta"
test "$(sha "$customer")" = "$new_sha"
test "$(sha "$business")" = "$business_sha"
curl -fsS --max-time 20 'https://rubenvanaggelen.com/rutu-api/index.php?action=menu_availability' | python3 -c 'import json,sys;x=json.load(sys.stdin);assert x["ok"] and isinstance(x["sold_out"],list)'
trap - ERR
rm -f -- "$stage/customer.apk" "$stage/android.json"
rmdir -- "$stage" || true
echo RUTU_CUSTOMER_V940_DELIVERY_NOTICE_RELEASE_OK
