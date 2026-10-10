# Tablet registration cleanup — 2026-10-10

Owner authorized removing only the old Tablet registration after a backup.

## Confirmed identities from read-only live registry audit

- Old Tablet: `d5b8657d-b38d-464b-b96e-68399b23bc3f`, registered 2026-10-10 18:56:57 UTC, with five previously assigned rights (`mixes`, `shared`, `favorites`, `downloads`, `download_files`).
- New Tablet: `cf80ed1b-c837-42a5-b26e-24e2bab31c20`, registered 2026-10-10 19:12:07 UTC, with no assigned rights.
- Before cleanup, the owner confirmed both registrations visible separately and the new installation did not inherit old permissions.

## Cleanup result

- GitHub Actions audit run: https://github.com/Rubenvaggelen/apps/actions/runs/38079109533 — success.
- Owner-approved one-time cleanup run: https://github.com/Rubenvaggelen/apps/actions/runs/38079226044 — **success**.
- The cleanup required both exact device IDs, their registration timestamps, expected rights, matching Tablet names, correct ordering, non-owner and non-allowlisted status.
- A private, integrity-checked backup of `main-devices.json` was created before changes.
- **Only** the old registry record was removed; its stored rights were removed with it.
- The new Tablet entry and all other registry records were preserved byte-for-byte at the data-value level.
- Post-write verification and public registry health both passed.

No App APK release, other device permissions, live allowlists, or private license grant data were changed by this cleanup. The old license grant store was not modified in this operation.

## Future improvements

Uninstalling an APK does not reliably notify the server. Any automated stale-device retirement needs a separate safe lifecycle with owner visibility, minimum inactivity age, and explicit protection of offline devices.
