# The One Family — gecontroleerde licentie-uitrol

**Status:** ONTWIKKELING. Dit document is geen bevestiging dat de beveiliging live is.

## Regel voor bestaande apparaten en herinstallaties

Een gewone Main-update verandert de autorisatie niet. Een volledige verwijdering en herinstallatie, óók op hetzelfde toestel, wordt opnieuw aangemeld en heeft een **nieuw toegewezen licentie** nodig.

Technisch worden op Android twee signalen gecombineerd:

1. `PackageInfo.firstInstallTime`, dat een nieuwe datum krijgt na een volledige herinstallatie, moet bij de eerste overgang eerder liggen dan de servermigratie.
2. Een UUID in `noBackupFilesDir`, die behouden blijft bij een app-update maar niet vanuit Android Auto Backup wordt hersteld. Activatietokens worden aan deze installatie-UUID gekoppeld.

Alle bestaande Main-apparaten worden alleen grandfathered wanneer hun registratie **voor het migratiemoment** bestaat en hun lokale installatie aantoonbaar van daarvoor is. Bij een volledige herinstallatie is de nieuwe installatiedatum na dat moment en wordt geen gratis grandfather-licentie uitgegeven.

**Beperking:** `firstInstallTime` is een clientsignaal en geen cryptografische attestatie. Een gemanipuleerde app kan clientsignalen vervalsen. Voor aantoonbaar sterke nieuwe-installatiecontroles kan later servervalidatie via Play Integrity of hardware-backed attestation worden toegevoegd.

## Standaard veilig (fail-closed voor licentiegebruik)

- `main_enforced=false` en `studio_enforced=false` bij installatie van de licentieserver.
- Er worden geen bestaande Main-installaties gewijzigd of verwijderd.
- Music Studio 1.4.0 op Ruben blijft onveranderd. De licentiebeveiliging moet nog in een **nieuwe** Windows-versie ingebouwd en getest worden.
- De beheerpagina `studio-licenses.php` accepteert alleen HTTPS mét cPanel Directory Privacy / `REMOTE_USER`. Als Directory Privacy niet werkt, blijft de pagina gesloten (403).
- Echte codes worden eenmalig getoond en op de server uitsluitend gehasht opgeslagen; willekeurige activatiecodes zijn cryptografisch onvoorspelbaar.
- Serverdatamap `$HOME/the-one-private-licenses` moet buiten documentroots staan, mode 0700 (bestanden 0600). Geen Git/OneDrive-backups in plaintext.

## Plan voor veilige publicatie

1. Back-up de bestaande serverregistraties uit `$HOME/the-one-remote-data/main-devices.json` en eigenaargegevens. Verifieer dat ook eerder geregistreerde **offline** Main-apparaten in het bestand staan. Bij twijfel GEEN migratie inschakelen.
2. Controleer GitHub Actions-testen: `php tests/main-license-lifecycle.php`, PHP lint en Android Main-compile. Geen release op basis van falende tests.
3. Deploy `license-core.php` en `licenses.php` onder `$HOME/public_html/the-one-remote-api/` met serverrechten en test `policy` (moet Main: false en Studio: false zijn).
4. Installeer `studio-licenses.php` in het beveiligde Dev Hub-documentroot onder het bestaande **HTTPS + cPanel Directory Privacy** toegangsbeleid en test of niet-ingelogde gebruikers altijd 401/403 ontvangen.
5. Controleer dat `Main → Laptop → Music Studio toegangscodes` de afgeschermde pagina opent en dat uitsluitend de eigenaar deze knop kan openen. De server mag een apparaat-ID alléén nooit vertrouwen als beheerderstoestemming.
6. Maak een testlicentie, activeer een testapparaat en controleer weigeren, goedkeuren en intrekken.
7. Neem pas daarna de oude Main-apparaten over via de expliciete migratiehandeling `BEHOUD BESTAANDE APPARATEN`. Dit is een **onomkeerbare cutover**; de UI vraagt bewuste bevestiging.
8. Controleer op een bestaand toestel een normale update zonder nieuwe activatie. Verwijder Main daarna alleen op een **toegewezen testtoestel**, installeer opnieuw en controleer dat een **nieuwe licentie vereist is**.
9. Pas **na** die test de nieuwe Main-APK aan alle gebruikers aanbieden. Een privépublicatiekanaal voor binaries is nodig; de huidige openbare GitHub-releases zijn daarvoor niet voldoende.
10. Beveilig ook Music Studio Setup/Portable **in het programma en op downloadniveau** voordat `studio_enforced` wordt ingeschakeld. Plaats geen onbeveiligde .exe in een publiek repo of leesbare URL.

## Herstel

Bij problemen niet zomaar de bestaande Main-registratie wissen. Laat de oude Main-versie en Ruben Studio 1.4.0 beschikbaar voor herstel. Bewaar een herstelkopie van de registry en van de serverlicentiedatamap met juiste bestandsrechten, buiten publieke opslag. 

**Status van de ontwikkeltak:** de PHP-licentieserver, Main-activatiescherm, eigenaarstoegang en testworkflow zijn voorbereid. Nog niet uitgerold naar productie. De nieuwe beveiligde Windows-installer, publieke/privé downloads en server-authenticatie van beheerders moeten volledig getest worden voordat live activatie verplicht wordt.


## Blijvende Main-aanmeldingen (eigenaar)

De aangemelde eigenaar ziet **Main → Meldingen** met een teller voor openstaande verzoeken. Een Main-licentieaanvraag heeft `persistent=true` en `actionType=license_request` en wordt in de Main-notificatiestore bewaard over app-herstarts heen. Wegvegen, wissen-alles of een tijdelijke netwerkstoring mag dit verzoek niet verwijderen.

De licentieserver houdt een nieuwe aanvraag op status **pending**. De goedgekeurde eigenaar kan deze zien en beslissen via **Main → Laptop → Apparaten beheren**, niet door de melding te openen of te wissen. De eigenaar wordt eenmalig gekoppeld met een 10 minuten geldige code, gemaakt vanuit de **cPanel-authenticated** licentiebeheerpagina. De server valideert daarna een geheim eigenaartoken, gekoppeld aan de installatie, bij ieder overzicht en iedere goedkeuring; een spoofbaar device-ID is **niet voldoende**.

Pas na een server-bevestigde `owner_approve` wordt de aanmelding uit de eigenaar-notificaties verwijderd. De aanvrager kan vervolgens met het bij de aanvraag gegenereerde private `request_secret` eenmalig een installatiespecifieke toegangstoken ophalen, zonder dat de beheerder handmatig een code naar die persoon hoeft te sturen.

Bij niet-bereikbare server blijven de bestaande Main-meldingen bewaard en worden ze bij volgende synchronisatie opnieuw gecontroleerd. De cPanel-pagina biedt voor Main geen actie 'Afwijzen' die het verzoek ongemerkt uit de lijst zou halen.

**Uitrolstatus:** alleen ontwikkeltak. Zowel `license-core.php`, `licenses.php`, de afgeschermde `studio-licenses.php` als de nieuwe Main APK moeten gezamenlijk gecontroleerd en uitgebracht worden. Niet simpelweg de GitHub-serverversie deployen: de huidige live `devices.php` bevat Car- en Main-allowlists uit eerdere handmatige cleanupacties die niet onbedoeld overschreven mogen worden.

