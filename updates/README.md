# The One centralized APK update feed

Source: `updates/feed.php`. Deploy this file to the public website docroot at `/updates/feed.php`, **not** below the password-protected `/apps` Dev Hub. Read-only API example: `https://theoneforlive.com/updates/feed.php?app=main`.

The API selects the highest published **non-draft, non-prerelease** GitHub release per whitelisted application, checks the exact expected release asset filename and URL, and returns `app`, `channel`, `versionCode`, `tag`, `downloadUrl`, `publishedAt`. It never publishes or uploads a binary. GitHub Releases is the initial source of truth; old in-app GitHub fallbacks remain operational.

Supported mappings: main=main-vN/app-debug.apk; car=car-vN/carradio-debug.apk; dj=dj-vN/thedj-debug.apk; run=run-vN/runcoach-debug.apk. Do not reuse those mappings for Cinema, Series, Kids, TV, Studio or Rutu until their app IDs, signed APK build pipelines, release naming and updater implementations are audited.

## Deployment checklist

1. Copy `updates/feed.php` onto the **theoneforlive.com** web root as `updates/feed.php`. Do not deploy private credentials or loosen protection on the Dev Hub.
2. Verify each endpoint returns JSON without login and that `versionCode` matches the **installed APK's actual Android versionCode**, not merely its tag.
3. Confirm the published APK has the **same signing certificate and package name** as installed. Android PackageInstaller must request user confirmation on unmanaged devices.
4. Test Main and Car on a clean test device and on an existing installation before distributing new builds. An endpoint failure must fall back to existing GitHub release discovery.
5. Set CI tests for PHP syntax, allowed app validation, fallback, checksum/signature validation, and staged release rollout before considering universal auto-updates.
6. Build & integrate equivalent clients for DJ, Run, Media Player, Series, Cinema, TV, Kids, Studio and Rutu separately. Never claim those clients have been migrated based on this feed alone.

Security: never accept a user-supplied download URL, do not store GitHub tokens in the public webroot, publish only signed approved releases, and preserve current production stable versions until end-to-end installation succeeds.


## Hosting blocker confirmed 2026-11-03

An unauthenticated GET to `https://theoneforlive.com/updates/feed.php?app=main` returned **HTTP 401 Unauthorized**. The public update endpoint is therefore NOT live. Do not merge/release APKs assuming that updatefeed is accessible.

Remediation in Namecheap/cPanel (site owner authorization required):
- Identify the actual document root and Directory Privacy scope affecting `theoneforlive.com`.
- Keep `dev.theoneforlive.com/apps` and the Dev Hub login password-protected.
- Publish `feed.php` in a dedicated public directory or separate public host that does not inherit Basic Authentication; choose the location deliberately and update Android URLs if the host differs.
- Check that no credentials, admin route, editor, secrets or PHP source files are exposed publicly.
- Verify from an anonymous device with `curl -i 'https://theoneforlive.com/updates/feed.php?app=main'`: HTTP 200, `application/json`, matching schema; check invalid app => HTTP 400; missing releases => 404; upstream outage => 503.
- Only then test signed Main/Car APKs. The app fallback to GitHub is meant to avoid breaking installs during the transition.
