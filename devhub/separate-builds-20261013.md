# Separate media builds — 2026-10-13
Adds eight standalone authenticated APK downloads to the existing Dev Hub:
Cinema / Series / TV for Android phone and Android / Google TV; Media Player v2401; Remote v2068.
APK files and manifest live outside the web root at /home/vanawiwj/the-one-private-builds/2026-10-13.
Existing cPanel Directory Privacy protects these PHP routes. Do not remove .htaccess or publish the personal APKs on public releases.
Integrate into the current live builds.php after its Downloads heading:
<?php require __DIR__.'/family-new-builds-20261013.php'; ?>
Preserve existing live customizations. Back up before insertion. Do not replace builds.php with the older main branch file.
Licensing is not enabled; app-specific licensing is the next task.
