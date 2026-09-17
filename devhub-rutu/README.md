# Rutu Dev Hub publication

Repository: Rubenvaggelen/apps. Release branch: feature/jade-orders.
Changes to devhub-rutu/** or .github/workflows/publish-rutu.yml automatically publish.

For an app update, first build and test the app. Update manifest.json with successful
Android and Windows run IDs, their common source commit, expected artifact filenames,
download filenames and SHA-256 hashes. Update public/rutu.html with matching version,
build links and download targets. Commit this release selection only once checks pass.
The publisher rejects failures, pull-request builds, foreign repositories, mismatched
source revisions and incorrect file checksums. Expired artifacts must be rebuilt;
never substitute an arbitrary latest run. Page-only updates reuse the selected builds.

Required repository secrets: DEPLOY_HOST, DEPLOY_PORT, DEPLOY_USER, DEPLOY_SSH_KEY,
DEPLOY_SSH_PASSPHRASE, DEPLOY_KNOWN_HOSTS. The host key is pinned from the trusted
cPanel terminal. Never log secrets or disable StrictHostKeyChecking.

Destination is $HOME/dev.vanaggelen.com on the configured hosting account.
Only rutu.html, rutu-assets, rutu-downloads and rutu-deployment.json are managed.
The existing index.php, API, site authentication and other projects are untouched.
The index.php sidebar already links to rutu.html, added through cPanel.
Backups live outside the web root at $HOME/.rutu-backups/TIMESTAMP-PID/previous.tar.gz.
A failed replacement restores previous files. GitHub concurrency serializes publishers.
Confirm the green Publish Rutu to Dev Hub run and the live file checksum verification.
The site has HTTP authentication; do not describe anonymous HTTP 401 as a failed deploy.

Android and Windows are test builds. iOS is simulator-only until Apple signing and
device distribution are set up; this workflow does not publish an installable iOS app.

