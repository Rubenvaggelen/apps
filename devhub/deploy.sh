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
php -l "$(cd "$(dirname "$0")" && pwd)/builds.php"

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
if [ -f "$DOCROOT/builds.php" ]; then
  cp "$DOCROOT/builds.php" "$DOCROOT/builds.php.backup-$STAMP"
fi

cp "$SOURCE/index.php" "$DOCROOT/index.php"
cp "$SOURCE/api.php" "$DOCROOT/api.php"
cp "$(cd "$(dirname "$0")" && pwd)/builds.php" "$DOCROOT/builds.php"
chmod 640 "$DOCROOT/index.php" "$DOCROOT/api.php" "$DOCROOT/builds.php"

echo "The One Dev Hub is deployed to $DOCROOT"
echo "Build center is available at https://dev.rubenvanaggelen.com/builds.php"
echo "Legacy index.html moved to a timestamped backup, so index.php is now served."
echo "Directory Privacy/.htaccess was not changed."
