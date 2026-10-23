# RECOVERY — The One Family — 23 oktober 2026

## Bekend werkende toestand
- The One Main: **v1041** — APK: https://github.com/Rubenvaggelen/apps/releases/download/main-v1041/app-debug.apk
  - Bestandsgrootte: 13.335.364 bytes
  - SHA-256: `7175cb641c868c0508a6d3d08f704e4e887e2e4c4eb0a722e483566cf67327a6`
  - Android signing certificate SHA-256: `dbf6ba5bfab9b67a7518ce43235b36a4bb4ad4f19f2bed838191e2267b25ea76`
  - Main code snapshot: commit `5d7b93d4bc535e63c24bde2e31b7bd6eb9647057`.
  - Schermen Aan/Uit voor Ruben, Surface en THEONE-HUB hersteld.
  - Music Studio owner-approval in Main hersteld, bestaande persoon-dropdownrechten behouden.
- The One Music Studio Portable (Windows): **v1.4.1** op Surface.
  - Bestaand lokaal exemplaar op Surface: `Downloads/The-One-Music-Studio-1.4.1-Portable.exe`.
  - Bestandsgrootte: 100.175.661 bytes.
  - SHA-256: `082f1367e72ed1b5895a97a64bff08af4a37a91164a3ce3dbab257ff5d0a14ad`.
  - Op 23 oktober 2026 op Surface geverifieerd: licentietoken aanwezig, geen openstaande lokale aanvraag.
- The One Kids: gebruiker heeft APK v1.0.1130 bevestigd als werkend; versleuteld updatekanaal versie 1130 live. Niet onderdeel van deze Main/Studio snapshot.

## Herstelregels
1. Herstel alleen de APK's/bronbestanden die nodig zijn; doe geen automatische terugdraaiing van de actuele licentiedatabase.
2. Bewaar de huidige `licenses.json`, owner pairing, bestaande activaties en recente aanvragen. De server is niet opgenomen in deze broncodesnapshot.
3. Oude verzoeken zijn volgens de gebruiker al opgeruimd. Herintroduceer die niet door `licenses.json` te overschrijven.
4. De afwijkende serverpatch voor pending-aanvragen (weigeren en idempotentie) staat apart onder `fix/main-reject-old-license-requests-20261023`, pull request #38. Deze **niet** als reeds live of reeds getest op productie beschouwen.
5. Voor elke latere versie: controleer exact dezelfde Android application ID en signing-certificaat voordat over Main wordt geïnstalleerd.
6. Bij serverdeploy: backup live PHP *en* de private licentiedatastore buiten de webroot, PHP syntax check, geïsoleerde test, bevestigde handmatige productietoestemming en rollbackcontrole.

## Status en afbakening
Dit is een herstelpunt voor de versie van Main in GitHub en de geverifieerde werkende Studio-installatie. Het is **geen** byte-backup van de server-side private licentiedatastore en geen volledige herstel-zip van alle The One-apps.

*Vastgelegd vanuit bevestigde installatie- en buildcontroles op 23 oktober 2026.*
