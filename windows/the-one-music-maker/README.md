# The One Music Maker — 0.1.0 owner preview

Standalone Windows music studio. No Main, DJ, server connection or installation of Node.js is needed to run the packaged Windows executable. Its own version and build workflow are independent of the other apps.

## Current features

- Import multiple locally supported audio files as separate tracks.
- Shared sample-clock playback, pause, resume, stop and timeline seek.
- Editable track names, start positions and source start/end trims.
- Per-track volume, stereo pan, mute/solo and fade envelopes.
- Duplicate tracks, remove tracks, undo/redo of track edits.
- Microphone recording into a new track (explicit microphone permission).
- BPM visual grid; changing BPM does not stretch existing audio.
- Master gain and peak meter.
- Save/open `.onemusic` projects with embedded audio, no missing source-file paths.
- Offline stereo PCM WAV export (44.1 kHz/16-bit), peak normalization if necessary to prevent clipping.
- Two generated demo tracks; no external samples or assets.
- Unsaved changes confirmation on close.

## Preview limits

This is a first local owner prototype, not a Cubase-equivalent finished DAW. Export currently allows up to 30 minutes. Project audio is stored as 16-bit PCM WAV and the entire project is held in memory: use short sessions initially. Codec support follows the bundled Electron engine; unsupported audio produces an explicit error. No automatic project recovery yet. Recording does not yet perform latency-calibrated overdubbing. No effects rack, MIDI, time stretching, stem separation, plugin hosting, Shared Media connection or Family enrollment yet. Do not distribute it to other users until name registration and owner approval are implemented. No Main tile is added by this change.

## Windows

Download the workflow's `The-One-Music-Maker-Windows` artifact, extract the ZIP, and run `The-One-Music-Maker-0.1.0-Windows.exe`. This is a portable app; no separate browser is needed. The preview executable is not signed with a publisher certificate.

## Development and checks

`npm ci`, `npm test`, `npm start`; build Windows using `npm run dist:win`.

`tests/model.test.cjs` covers scheduling, resume offsets, mute/solo, input bounds, fade envelopes and WAV encoding. `tests/browser.cjs` verifies actual audio rendering, project round trip and UI transport via Playwright (requires Playwright and Chromium for development only).
