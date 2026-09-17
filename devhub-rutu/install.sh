#!/usr/bin/env bash
set -euo pipefail
stage=$1
case "$stage" in "$HOME"/.rutu-stage.*) ;; *) echo 'Invalid staging path'; exit 1;; esac
test -d "$stage"
site="$HOME/dev.vanaggelen.com"
test -f "$site/index.php"
test ! -L "$site"
cd "$stage"
tar -xzf package.tar.gz
sha256sum --check SHA256SUMS
mapfile -t files < <(sed 's/^[a-f0-9]*  //' SHA256SUMS)
for file in "${files[@]}"; do
  case "$file" in rutu.html|rutu-deployment.json|rutu-assets/*.png|rutu-downloads/*.apk|rutu-downloads/*.exe) ;; *) echo 'Unexpected package path'; exit 1;; esac
  [[ "$file" != *..* ]]
  test -f "$file"
  test ! -L "$site/$file"
done
for dir in rutu-assets rutu-downloads; do
  test ! -L "$site/$dir"
  mkdir -p "$site/$dir"
done
backup="$HOME/.rutu-backups/$(date -u +%Y%m%dT%H%M%SZ)-$$"
mkdir -p "$backup"
chmod 700 "$HOME/.rutu-backups" "$backup"
existing=(); new=()
for file in "${files[@]}"; do
  if test -f "$site/$file"; then existing+=("$file"); else new+=("$file"); fi
done
tar -czf "$backup/previous.tar.gz" -C "$site" -- "${existing[@]}"
rollback() {
  result=$?
  if [ "$result" -ne 0 ]; then
    tar -xzf "$backup/previous.tar.gz" -C "$site"
    for file in "${new[@]}"; do rm -f -- "$site/$file"; done
    echo 'Publication failed; previous files restored'
  fi
  for file in "${files[@]}"; do rm -f -- "$site/$file.pending"; done
  exit "$result"
}
trap rollback EXIT
for file in "${files[@]}"; do
  cp -- "$file" "$site/$file.pending"
  chmod 644 "$site/$file.pending"
done
for file in "${files[@]}"; do mv -f -- "$site/$file.pending" "$site/$file"; done
(cd "$site" && sha256sum --check "$stage/SHA256SUMS")
echo 'PUBLICATION VERIFIED: all live files match the package; backup saved outside document root'
trap - EXIT
rm -rf -- "$stage"

