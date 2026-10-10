# The One Family — DJ and Music install licensing (2026-10-10)

## Verified live outcomes

- Protected licensing API deployed successfully: https://github.com/Rubenvaggelen/apps/actions/runs/38086270370
- Independent installation approval is supported for app keys `dj` and `music`; only paired Main owner approves.
- Server default for either app is **DENIED** without active installation-bound token.
- Main v995 published: https://github.com/Rubenvaggelen/apps/releases/tag/main-v995
- DJ v1050 published: https://github.com/Rubenvaggelen/apps/releases/tag/dj-v1050
- DJ download in protected Dev Hub upgraded from 1049 to 1050 with private backup and no changes to unrelated pages: https://github.com/Rubenvaggelen/apps/actions/runs/38086633096
- Main v995: owner sees app-specific approval notifications and individual pending requests.
- DJ v1050: new installation generates private installation ID; request/secret/token live in `noBackupFilesDir`, no backup; player and audio not created until approval. Normal upgrade retains granted token. Uninstall/reinstall creates new ID, requiring approval.
- Legacy DJ installations whose first install predates 2026-10-10T20:00:00Z continue operating after an ordinary update. This *legacy client exception* means they are not yet subject to license revocation enforced by new DJ client; old APK releases may still be run without the new client-side gate. Review a future migration if stricter control is required.
- Car and Run permissions remain governed by existing Main rights; nothing about this update enables new Car/Run licensing.
- Main/Studio existing server policy flags were checked unchanged.

## Music caveat / next step

The server's separate `music` request type exists, but **no Music client has yet been updated**. Do not claim Music licensing is live in an app. The local active Windows source on Ruben appears to be `C:\Users\ruben\Documents\The One AI Music Studio`, version **1.4.0**, with Setup/Portable builds in The One Studio Windows. It is newer and materially different from GitHub's older `windows/the-one-music-maker` owner preview 1.1.0. Never overwrite the working Windows 1.4.0 with that old GitHub source.

Before modifying the Music app, confirm whether the owner means The One AI Music Studio (Windows) or another Music APK. Then work from a separate backup/copy of the current 1.4.0 source, provide installation-bound owner approval with handling for Windows uninstall/reinstall and portable distribution, and ship as a standalone update without changing Main, DJ, Car, or Run.

## Existing owner settings

Do not toggle `main_enforced` or `studio_enforced` without migration verification; existing registrations and owner pairing must remain intact.
