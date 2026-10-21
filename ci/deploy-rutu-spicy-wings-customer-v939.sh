#!/usr/bin/env bash
# Separate customer UI-only update: never alter RUTU Business, API, orders or QR assets.
set -Eeuo pipefail
stage="$1"
new_sha="$2"
site="$HOME/public_html/rutu-updates"
customer="$site/Rutu-BBQ-Android.apk"
meta="$site/android.json"
business="$site/Rutu-BBQ-Bedrijf-Android.apk"
old_sha='668651f13408998ab0a70a080c011f464faba3b3ae49019357d06d5a524ed53c'
business_sha='96f7fc767d956cc02ad4459692b049c06f41f87464f7ec01d675ec0887f5a433'
sha(){ sha256sum "$1" | awk '{print $1}'; }
umask 077
case "$stage" in "$HOME"/.rutu-customer-spicy-939.*) ;; *) echo REFUSE_INVALID_STAGE; exit 2;; esac
for f in "$customer" "$meta" "$business" "$stage/customer.apk" "$stage/android.json";do
 test -f "$f" && test ! -L "$f" || { echo REFUSE_MISSING_OR_LINKED_FILE;exit 3; }
done
test "$(sha "$customer")" = "$old_sha" || { echo CUSTOMER_V938_CHANGED;exit 4; }
test "$(sha "$business")" = "$business_sha" || { echo BUSINESS_V942_CHANGED;exit 5; }
test "$(sha "$stage/customer.apk")" = "$new_sha" || { echo APK_STAGING_HASH_FAIL;exit 6; }
python3 - "$meta" "$stage/android.json" "$new_sha" <<'PY'
import json,sys
before=json.load(open(sys.argv[1]))
after=json.load(open(sys.argv[2]))
assert before['versionCode']==938 and before['sha256']=='668651f13408998ab0a70a080c011f464faba3b3ae49019357d06d5a524ed53c'
assert after['versionCode']==939 and after['sha256']==sys.argv[3]
assert after['url']=='https://rubenvanaggelen.com/rutu-updates/Rutu-BBQ-Android.apk'
PY
backup="$HOME/rutu-data/backups/customer-spicy-v939-$(date -u +%Y%m%dT%H%M%SZ)"
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
install -m 0644 "$stage/customer.apk" "$customer.rutu939.tmp"
install -m 0644 "$stage/android.json" "$meta.rutu939.tmp"
mv -f -- "$customer.rutu939.tmp" "$customer"
mv -f -- "$meta.rutu939.tmp" "$meta"
test "$(sha "$customer")" = "$new_sha"
test "$(sha "$business")" = "$business_sha"
curl -fsS --max-time 20 'https://rubenvanaggelen.com/rutu-api/index.php?action=menu_availability' | python3 -c 'import json,sys;x=json.load(sys.stdin);assert x["ok"] and isinstance(x["sold_out"],list)'
trap - ERR
rm -f -- "$stage/customer.apk" "$stage/android.json"
rmdir -- "$stage" || true
echo RUTU_CUSTOMER_V939_SPICY_WINGS_RELEASE_OK
