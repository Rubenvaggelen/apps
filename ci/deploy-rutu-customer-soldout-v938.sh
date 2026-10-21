#!/usr/bin/env bash
# Separate customer UI-only update: never alter RUTU Business, API, orders or QR assets.
set -Eeuo pipefail
stage="$1"
new_sha="$2"
site="$HOME/public_html/rutu-updates"
customer="$site/Rutu-BBQ-Android.apk"
meta="$site/android.json"
business="$site/Rutu-BBQ-Bedrijf-Android.apk"
old_sha='60d606b73aa44e3610902aad37df0b5e5f077f79b699589fbbd88c3699da534e'
business_sha='0dcf87d440724ecd694c64eb2e1da82b1130c8f61caa39f1655969e97452455a'
sha(){ sha256sum "$1" | awk '{print $1}'; }
umask 077
case "$stage" in "$HOME"/.rutu-customer-stock-938.*) ;; *) echo REFUSE_INVALID_STAGE; exit 2;; esac
for f in "$customer" "$meta" "$business" "$stage/customer.apk" "$stage/android.json";do
 test -f "$f" && test ! -L "$f" || { echo REFUSE_MISSING_OR_LINKED_FILE;exit 3; }
done
test "$(sha "$customer")" = "$old_sha" || { echo CUSTOMER_V937_CHANGED;exit 4; }
test "$(sha "$business")" = "$business_sha" || { echo BUSINESS_V941_CHANGED;exit 5; }
test "$(sha "$stage/customer.apk")" = "$new_sha" || { echo APK_STAGING_HASH_FAIL;exit 6; }
python3 - "$meta" "$stage/android.json" "$new_sha" <<'PY'
import json,sys
before=json.load(open(sys.argv[1]))
after=json.load(open(sys.argv[2]))
assert before['versionCode']==937 and before['sha256']=='60d606b73aa44e3610902aad37df0b5e5f077f79b699589fbbd88c3699da534e'
assert after['versionCode']==938 and after['sha256']==sys.argv[3]
assert after['url']=='https://rubenvanaggelen.com/rutu-updates/Rutu-BBQ-Android.apk'
PY
backup="$HOME/rutu-data/backups/customer-soldout-v938-$(date -u +%Y%m%dT%H%M%SZ)"
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
install -m 0644 "$stage/customer.apk" "$customer.rutu938.tmp"
install -m 0644 "$stage/android.json" "$meta.rutu938.tmp"
mv -f -- "$customer.rutu938.tmp" "$customer"
mv -f -- "$meta.rutu938.tmp" "$meta"
test "$(sha "$customer")" = "$new_sha"
test "$(sha "$business")" = "$business_sha"
curl -fsS --max-time 20 'https://rubenvanaggelen.com/rutu-api/index.php?action=menu_availability' | python3 -c 'import json,sys;x=json.load(sys.stdin);assert x["ok"] and isinstance(x["sold_out"],list)'
trap - ERR
rm -f -- "$stage/customer.apk" "$stage/android.json"
rmdir -- "$stage" || true
echo RUTU_CUSTOMER_V938_SOLDOUT_UI_RELEASE_OK
