# Media Player v2401: second Xtream source

Based on the verified v2400 commit `f668c02ee95a6cf762add94ca5616a7e26dd023c` with the same application ID and permanent signing key. Install as an update; do not uninstall the current app.

The existing saved source remains primary. The owner-specific secondary source is restored from RSA-OAEP encrypted build configuration into a private APK asset. No plaintext provider credentials are committed. The new source replaces the abandoned earlier candidate, which was never deployed.

Live TV, movies and series catalogs combine both sources. Secondary categories display `Bron 2`; category, stream, series and episode IDs use a `source2:` namespace to prevent collisions. Requests for a category or series go only to its corresponding provider. An unavailable source does not suppress a successful response from the other source. Existing provider IDs and settings remain unchanged. Cached categories use a new key so old single-source caches do not mask the added provider.

Four JVM tests use local HTTP fixture servers to verify colliding IDs, category routing, series episode routing and provider failure isolation. The build verifies package/version and both asset configurations, then encrypts its personal APK artifact for the owner. Provider account availability and actual streaming playback must be verified on the user's device; no live provider test is claimed.

Cinema is a separate application and is not changed by this update.
