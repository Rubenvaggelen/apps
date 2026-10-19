# Media Player Google TV — E-AC3 audio additive recovery investigation (2026-10-20)

Status: DIAGNOSIS / NOT RELEASED. No existing media-player files, release branches, configs, stream source assets, or APKs are changed.

## Hardware evidence (Philips + same external Google TV)
- Working series episode: `audio/mp4a-latm` (AAC), 2 channels, supported, selected, volume 25/25, player 100%, ready.
- Silent series episode: `audio/eac3` (Dolby Digital Plus), 6 channels, NOT supported, no supported audio track, volume 25/25, player 100%, ready.
- The evidence establishes an audio format/support mismatch, not a zero-volume setting. It does not prove precisely whether hardware E-AC3 decoder, downstream HDMI capabilities, or both are involved.
- These snapshots do not expose streams, usernames, passwords, or customer secrets.

## Preserve working behavior
- Canonical stable base: commit `7fa3f4af3df825b41bbfe22584b3a576d25aa45c`, Google TV v1.0.36-stable, versionCode 1036.
- Do NOT edit the stable branch, source account configuration, audio settings, user data, existing video playback logic, OTA, or currently published APK.
- The goal is additive E-AC3 capability only when the native renderer cannot handle the selected format, keeping native AAC and other supported tracks unchanged.
- Current PCM toggle changes output policy but cannot synthesize an absent E-AC3 decoder.

## Engineering options
1. Preferred safe route for comprehensive E-AC3 coverage: build the official Media3 FFmpeg audio decoder extension with the `eac3` decoder enabled as a separate new module/artifact. Its native dependencies are NOT published as a normal, ready-to-use Google Maven dependency. Validate Android ABIs, licenses, binary provenance, correct Media3 version, and PCM output before any integration.
2. Add an isolated decoder renderer only as fallback for an unsupported format, leaving current MediaCodec preferred for already supported AAC/video.
3. If alternatives exist for the same episode, a compatible audio alternative can be offered; do not silently change a working preferred stream or drop results.
4. Add tests for AAC-stereo unaffected, unsupported E-AC3 diagnosed, 5.1 E-AC3 audio decodes or gracefully reports absence, pause/seek/back and 5-second controls retained.

## Safeguards
- Any future integration changes must be additive, minimal, reviewed by diff, and confined to this development branch; do not remove or replace existing working code paths.
- No automatic rollout, no OTA promotion. Build and test on one Philips-connected Google TV before other devices.
- An A/B test on real hardware is required; CI success is not proof that audio output is fixed.
