# The One Media Player — Google TV 1.0.36 STABLE baseline
Release date: 2026-10-19

## Purpose
This release freezes the previously signed v1.0.35 progressive-library candidate as the new Google TV engineering baseline, with the verified v1.0.34 playback and 5-second automatic audio-control hiding retained. No feature or source selection modifications are introduced during this release bump.

## Android identity
- Package: `com.theone.mediaplayer.tv`
- versionCode: 1036
- versionName: `1.0.36-stable`
- Signing: existing permanent owner TV certificate, checked in GitHub Actions
- Build branch: `release/media-player-google-tv-v1036-stable-20261019`
- Source base: `feature/media-player-tv-progressive-library-20261019` at commit `ccb5631d5cab675f1eb25376ead1341af93a84fb`

## Frozen features
- TV playback and source switching from 1.0.34
- TV remote menu and audio overlay disappear after 5 seconds inactivity
- Both configured Xtream providers and provider-aware IDs
- Series category caching, provider snapshots, and progressive series search in batches
- Favorites, license gating, TV channel ordering, Dutch NL-only channel selection, ESPN ordering and adult-section gating left unchanged

## Verification gates
- Gradle phone-app unit tests and Google TV APK build
- Both configured Xtream source assets verified in APK
- Google TV emulator launch smoke
- The APK is signed with the existing owner TV keystore and signature is verified
- APK package/versionCode/signature digest verified
- Real-device playback on all three different Google TV devices is pending. In particular, the second device previously timed out loading the series library after ~45 seconds. No claim of fixing it until confirmed.

## Change policy
1. Keep this branch and release APK as the immutable comparison point for subsequent work.
2. Create separate feature branches based on this stable branch; never make experiments directly here.
3. Do not replace the APK or update OTA production manifests until one-device validation and explicit release decision.
4. Preserve package/signature and versionCode monotonicity; no uninstall required.
5. If a change breaks playback, sound, the series library, country channel layout, navigation or adult access, return to the baseline source and determine the exact regression.

## Device acceptance tests
1. On Google TV A (known working), Series -> full catalog search -> visible first results -> scroll -> episodes -> playback.
2. On Google TV B (45-second timeout), repeat the same test, then exit and reopen to confirm cached result.
3. On Google TV C, confirm the same core flows.
4. All devices: NL channel order, live TV playback, sound, favorites and 5-second controls hide.
5. Record elapsed time to first results, time to complete series, first NL channel list, and whether provider timeout appears.

This is a signed baseline deliverable, not confirmation that performance is identical on every hardware model.
