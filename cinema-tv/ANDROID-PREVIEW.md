# The One Cinema Android preview 1.1.0

First phone-focused preview; package `com.theone.cinema`, version code 110. Google TV package `com.theone.cinema.tv` is unchanged. Media Player is a separate app.

Both VOD film catalogs are combined on the phone. The original source uses the existing authenticated HTTPS relay. The newly supplied provider is contacted directly by the app using its owner-specific APK configuration. No new provider credentials are persisted to the webserver. The abandoned earlier account is not included. Only film/VOD APIs are used; no live TV or series.

Second-source IDs are namespaced with an integer offset. Matching normalized titles group available qualities and provider versions behind one film entry. Language tokens and release years remain part of the key. Playback retries alternative available variants.

The separate XXX view uses provider adult flags, adult-category labels and explicit XXX/18+ title prefixes. Adult entries use a separate grouping key and stay outside ordinary Films, favorites and continue-watching. Classification depends on provider metadata; no exhaustive content inspection is claimed.

Phone layout uses two poster columns in portrait and adapts to width. Navigation scrolls horizontally. Playback survives orientation configuration changes. Source auth headers are resolved per URI; the relay token is never sent to the second provider.

Validation covers title grouping, adult classification/separation, native video first-frame playback, touch navigation and the separate folder on a phone emulator. Real provider playback remains to be tested on the user's Android device. Personal APK artifacts are encrypted for the owner before publication.
