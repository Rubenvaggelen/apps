# The One Studio 1.1.0 — owner preview

The supplied thestudio-v1.zip is the Studio base. Its original interface and engine remain unchanged, with additive scripts for the missing Music Maker features. Original Music Maker source and tests remain in this folder; the Windows entry point now opens studio/index.html.

## Retained Studio features
Arrangement, audio recording/import, clip edits, synth/piano roll, MIDI, drums, EQ/reverb/delay, metronome, loop, mixer and browser autosave. The unchanged uploaded source is retained in studio/original/.

## Added Music Maker features
Standalone Windows shell, redo, project naming, complete track copying, numeric start/source trims, clip fade envelopes, end-of-project stop without loop, portable projects with embedded audio, legacy .onemusic import, direct 44.1 kHz/16-bit stereo WAV export with peak protection, and unsaved-change confirmation.

## Run and test
Run The-One-Studio-1.1.0-Windows.exe. Browser source: studio/index.html with its companion files. npm ci; npm test; npm run test:ui; npm run dist:win.

This remains a local owner preview. Family enrollment and owner approval, Shared Media, an update service, plugins, sample instruments and time stretching remain follow-up work. Microphone and MIDI require real hardware checks; browser tests prove actual offline audio rendering, not physical device routing. The Windows executable is not publisher-signed.

Read studio/UITBREIDINGEN.md for use and project migration. Old JSON projects without embedded audio require the original browser's IndexedDB. Missing audio aborts opening without discarding the active project.
