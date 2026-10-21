# The One Music Studio 1.4.1 — Windows
Datum: 21 oktober 2026

Gebouwd vanuit de bestaande 1.4.1-bron op Ruben:
C:\Users\ruben\Documents\The One AI Music Studio

Platform: Windows x64
Application ID: com.theone.musicstudio
Bestaand gegevensprofiel: The One AI Music Studio (ongewijzigd)
Codecertificaat: niet ondertekend, overeenkomstig de bestaande bouwconfiguratie.

Build:
npm run dist:win -- --config.compression=normal --config.directories.output=dist-release-1.4.1

Controle:
- 20 tests voor audio, projecten, licenties en updater geslaagd.
- Huidige studio-browser-integratietest geslaagd.
- Verpakte EXE opent licentiepoort zonder echte toegangsaanvraag.
- Kernbestanden in app.asar komen overeen met de bron.
- app.asar van de gecomprimeerde en ongecomprimeerde build is identiek.
- Het echte Portable EXE is gestart: uitpakken en licentiepoort geslaagd, zonder echte aanvraag.
- Oude browser.cjs-test is verouderd: verwacht Demo laden op de vroegere startpagina.
- Volledige fysieke gebruikersacceptatie van bewerken na eigenaarstoestemming is niet opnieuw uitgevoerd.

Portable: 100175661 bytes
SHA256: 082f1367e72ed1b5895a97a64bff08af4a37a91164a3ce3dbab257ff5d0a14ad
Setup: 100400711 bytes
SHA256: d017f931231ad4be7215c47390cc9308c1334875b2c4b46b10affa438c67bdf9

Gepubliceerd en gecontroleerd op 21 oktober 2026 om 21:55 (Europe/Paris).
Releasecommit: 65fe5ab4237ba51617d4d1d7b4fdd714a34205ad
Publicatierun: https://github.com/Rubenvaggelen/apps/actions/runs/39665810401 — success.
De serverbestanden hebben dezelfde SHA-256 als de lokale Windows-builds.
Family Installaties en de Windows-downloadkaarten op Builds renderen beide 1.4.1-links.
De downloads zijn met de bestaande Dev Hub-login afgeschermd (zonder login HTTP 401).
De tijdelijke overdrachtsroute is verwijderd.
Bestaande Dev Hub .htaccess is ongewijzigd; alleen de eigen Studio-downloadmap erft dezelfde loginbeveiliging.
Rollback van catalogue/component: ~/.the-one-remote-backups/studio141-20261021.jvFjCji7
Er is geen app bij de gebruiker geïnstalleerd en geen echte toegang aangevraagd.

Downloads:
- Setup: https://dev.rubenvanaggelen.com/downloads/studio/1.4.1/The-One-Music-Studio-1.4.1-Setup.exe
- Portable: https://dev.rubenvanaggelen.com/downloads/studio/1.4.1/The-One-Music-Studio-1.4.1-Portable.exe
- Catalogus: https://dev.rubenvanaggelen.com/family-installaties.php
- Update-metadata: https://dev.rubenvanaggelen.com/updates/studio/latest.json

De bron op Ruben blijft leidend; de oude GitHub-module windows/the-one-music-maker is niet bijgewerkt naar deze bron.
Bronbackup op Ruben: backups/Studio-1.4.1-Source-20261021.zip.
De eerste wachtrun verloor de beheerverbinding. De definitieve publicatie verliep zonder een lange SSH-wachtstap.

