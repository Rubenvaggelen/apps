# The One Cinema — Google TV

Independent native Android/Google TV app. Application ID `com.theone.cinema.tv`, version 1.0.0 (100).

Features: remote-controlled film catalog, search, duplicate-quality grouping, favorites, persistent watch progress, fullscreen Media3 player, subtitles/audio controls, seek and media remote keys, automatic source fallback and bounded retry. The TV stream uses HTTPS to the authenticated owner-hosted relay and bypasses the Cinema website. Provider credentials are never placed in the TV APK.

Build derives owner relay access from the existing Media Player configuration from the verified v2400 APK. A scoped relay token is derived privately during the build and injected into an ignored asset; no credentials are committed to source. This is a personal sideload build, not a Google Play Store publication. A real Google TV playback test remains required before calling the app fully validated on the owner's TV. Arbitrary provider titles and codecs cannot all be guaranteed.

Build: Gradle 8.7, Java 17, Android SDK 35. `gradle :app:testDebugUnitTest :app:assembleRelease`.

The release is signed with the owner's existing stable signing identity (restored by the build workflow) for future APK updates. Do not replace the app's signing key or ID. Do not publish private source configuration as logs, source files or a standalone download.
