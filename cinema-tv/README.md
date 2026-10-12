# The One Cinema — Google TV

Independent native Android/Google TV app. Application ID `com.theone.cinema.tv`, version 1.0.0 (100).

Features: remote-controlled film and series catalogs, search, duplicate-quality grouping, favorites, episode browsing, persistent watch progress, fullscreen Media3 player, subtitles/audio controls, seek and media remote keys, automatic source fallback and bounded retry. The TV stream goes directly to the existing Media Player provider; it does not pass through the Cinema website or PHP relay.

Build uses the existing owner's Media Player configuration from the verified v2400 APK. Credentials are injected into an ignored asset during the build; no credentials are committed to source. This is a personal sideload build, not a Google Play Store publication. A real Google TV playback test remains required before calling the app fully validated on the owner's TV. Arbitrary provider titles and codecs cannot all be guaranteed.

Build: Gradle 8.7, Java 17, Android SDK 35. `gradle :app:testDebugUnitTest :app:assembleRelease`.

The release is signed with the owner's existing stable signing identity (restored by the build workflow) for future APK updates. Do not replace the app's signing key or ID. Do not publish private source configuration as logs, source files or a standalone download.
