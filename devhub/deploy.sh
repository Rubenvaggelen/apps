#!/usr/bin/env bash
set -euo pipefail

DOCROOT="/home/vanawiwj/dev.vanaggelen.com"
CORE="/home/vanawiwj/devhub-core"
REPO="/home/vanawiwj/repos/the-one-apps"
SOURCE="$(cd "$(dirname "$0")" && pwd)/public"
STAMP="$(date +%Y%m%d-%H%M%S)"

for f in "$CORE/config.php" "$CORE/git.php" "$CORE/files.php"; do
  if [ ! -f "$f" ]; then
    echo "ERROR: ontbrekend Dev Hub core-bestand: $f"
    exit 1
  fi
done

php -l "$SOURCE/index.php"
php -l "$SOURCE/api.php"

# Preserve the legacy dashboard, but move it out of the default index position
# so Apache/cPanel serves index.php instead of index.html.
if [ -f "$DOCROOT/index.html" ]; then
  mv "$DOCROOT/index.html" "$DOCROOT/index.html.backup-$STAMP"
fi
if [ -f "$DOCROOT/index.php" ]; then
  cp "$DOCROOT/index.php" "$DOCROOT/index.php.backup-$STAMP"
fi
if [ -f "$DOCROOT/api.php" ]; then
  cp "$DOCROOT/api.php" "$DOCROOT/api.php.backup-$STAMP"
fi

cp "$SOURCE/index.php" "$DOCROOT/index.php"
cp "$SOURCE/api.php" "$DOCROOT/api.php"
chmod 640 "$DOCROOT/index.php" "$DOCROOT/api.php"

echo "The One Dev Hub is deployed to $DOCROOT"
echo "Legacy index.html moved to a timestamped backup, so index.php is now served."
echo "Directory Privacy/.htaccess was not changed."
