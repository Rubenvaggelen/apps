#!/usr/bin/env bash
# Guarded RUTU BUSINESS ONLY update, preserving customer APK and historical server data.
set -Eeuo pipefail
stage="$1"
expected_old_php="$2"
expected_new_php="$3"
expected_new_apk="$4"
api="$HOME/public_html/rutu-api/index.php"
updates="$HOME/public_html/rutu-updates"
business="$updates/Rutu-BBQ-Bedrijf-Android.apk"
customer="$updates/Rutu-BBQ-Android.apk"
meta="$updates/business-android.json"
devcopy="$HOME/dev.vanaggelen.com/rutu-downloads/Rutu-BBQ-Bedrijf-Android.apk"
old_business_sha='26d5da7cbb3a72781676e851a2f8f02d1e963b0a22f7e8b1278cb2510e229c97'
old_customer_sha='60d606b73aa44e3610902aad37df0b5e5f077f79b699589fbbd88c3699da534e'
sha(){ sha256sum "$1" | awk '{print $1}'; }
umask 077
case "$stage" in "$HOME"/.rutu-klantenbeheer-stage.*) ;; *) echo INVALID_STAGE; exit 2;; esac
for f in "$api" "$business" "$customer" "$meta" "$devcopy" "$stage/api.php" "$stage/business.apk" "$stage/business.json";do
 test -f "$f" && test ! -L "$f" || { echo MISSING_OR_SYMLINK; exit 3; }
done
test "$(sha "$api")" = "$expected_old_php" || { echo LIVE_PHP_CHANGED_REFUSE; exit 10; }
test "$(sha "$business")" = "$old_business_sha" || { echo LIVE_BUSINESS_CHANGED_REFUSE; exit 11; }
test "$(sha "$devcopy")" = "$old_business_sha" || { echo LIVE_DEVHUB_CHANGED_REFUSE; exit 12; }
test "$(sha "$customer")" = "$old_customer_sha" || { echo LIVE_CUSTOMER_CHANGED_REFUSE; exit 13; }
test "$(sha "$stage/api.php")" = "$expected_new_php" || { echo STAGE_PHP_BAD; exit 14; }
test "$(sha "$stage/business.apk")" = "$expected_new_apk" || { echo STAGE_APK_BAD; exit 15; }
php -l "$stage/api.php" >/dev/null
python3 -m json.tool "$stage/business.json" >/dev/null
python3 - "$meta" "$stage/business.json" "$expected_new_apk" <<'PY'
import json,sys
a=json.load(open(sys.argv[1]))
b=json.load(open(sys.argv[2]))
assert a['versionCode']==940 and b['versionCode']==941
assert b['sha256']==sys.argv[3]
assert b['url']=='https://rubenvanaggelen.com/rutu-updates/Rutu-BBQ-Bedrijf-Android.apk'
PY
backup="$HOME/rutu-data/backups/voorraad-uitverkocht-v941-$(date -u +%Y%m%dT%H%M%SZ)"
install -d -m 0700 "$backup"
cp -p "$api" "$backup/api.php"
cp -p "$business" "$backup/business.apk"
cp -p "$devcopy" "$backup/devcopy.apk"
cp -p "$meta" "$backup/business.json"
rollback(){
  rc=$?
  trap - ERR
  cp -p "$backup/api.php" "$api" || true
  cp -p "$backup/business.apk" "$business" || true
  cp -p "$backup/devcopy.apk" "$devcopy" || true
  cp -p "$backup/business.json" "$meta" || true
  echo ROLLBACK_PERFORMED
  exit "$rc"
}
trap rollback ERR
test "$(sha "$api")" = "$expected_old_php"
test "$(sha "$business")" = "$old_business_sha"
test "$(sha "$customer")" = "$old_customer_sha"
install -m 0644 "$stage/api.php" "$api.rutu941.tmp"
install -m 0644 "$stage/business.apk" "$business.rutu941.tmp"
install -m 0644 "$stage/business.apk" "$devcopy.rutu941.tmp"
install -m 0644 "$stage/business.json" "$meta.rutu941.tmp"
mv -f "$api.rutu941.tmp" "$api"
mv -f "$business.rutu941.tmp" "$business"
mv -f "$devcopy.rutu941.tmp" "$devcopy"
mv -f "$meta.rutu941.tmp" "$meta"
test "$(sha "$api")" = "$expected_new_php"
test "$(sha "$business")" = "$expected_new_apk"
test "$(sha "$devcopy")" = "$expected_new_apk"
test "$(sha "$customer")" = "$old_customer_sha"
code="$(curl -sS --max-time 25 -o /dev/null -w '%{http_code}' 'https://rubenvanaggelen.com/rutu-api/index.php?action=business_customer_registrations')"
test "$code" = 401 || { echo LIVE_API_SECURE_SMOKE_FAILED; false; }
curl -fsS --max-time 25 'https://rubenvanaggelen.com/rutu-api/index.php?action=health' | python3 -c 'import json,sys;assert json.load(sys.stdin)["ok"]'
trap - ERR
rm -f "$stage/api.php" "$stage/business.apk" "$stage/business.json"
rmdir "$stage" || true
echo RUTU_BUSINESS_V941_STOCK_DEPLOYED_WITH_ROLLBACK_BACKUP
