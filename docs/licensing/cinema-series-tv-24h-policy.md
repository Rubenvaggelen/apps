# Cinema, Series, TV: afzonderlijke autorisatie en 24 uur offline

Besluit eigenaar, 14 oktober 2026: exact 24 uur offline voor eerder online goedgekeurde installaties.

## App-identiteit
- Cinema Android telefoon: `com.theone.cinema` / `cinema_android`
- Series Android telefoon: `com.theone.series` / `series_android`
- TV Android telefoon: `com.theone.tv.phone` / `tv_android`
- De Google TV-pakketten zijn afzonderlijke apps; deze wijziging geldt voorlopig voor de Main-telefoontegels.
- Remote en Media Player horen niet bij deze uitbreiding.

## Verplichte beveiliging voor productie
1. De **APK zelf** moet toegang controleren vóór content, streams, downloads en afspelen; een Main-tegel alleen is onvoldoende.
2. Nieuwe installaties mogen niet offline activeren, ook niet met een gekopieerde voorkeurenmap.
3. De server controleert een uniek installatiebewijs dat cryptografisch aan het apparaat is gebonden (Android Keystore; geen los te kopiëren device_id als credential).
4. Een licentiebewijs wordt door de server gesigneerd, bevat exact applicationId, installatie-identiteit, eigenaarstoestemming, uitgiftetijd en vervaltijd, en mag maximaal 24 uur geldig zijn. De APK controleert de handtekening met een gepinde publieke sleutel.
5. Een goedgekeurde installatie mag maximaal 24 uur sinds de laatst bevestigde online validatie offline werken. Geen automatische verlenging zonder server.
6. Systeemtijd terugzetten of gegevens kopiëren mag de 24-uursperiode niet verlengen. Gebruik monotone tijd binnen sessies en fail-closed bij aantoonbare tijdmanipulatie, rollback of ontbrekende onbetrouwbare klokgegevens.
7. Een ingetrokken licentie wordt direct bij de eerstvolgende geslaagde online controle geblokkeerd; offline kan intrekking pas uiterlijk na de resterende 24 uur effect hebben.
8. Een normale update met dezelfde package name en signing identity behoudt registratie en tijdelijke toestemming; herinstallatie/verwijderen van appdata vraagt nieuwe autorisatie.
9. Aanvragen blijven in Main > Meldingen zichtbaar tot expliciete eigenaarstoestemming of weigering; bestaande Main, Car, Windows, DJ en Run rechten blijven intact.
10. Productiepas na backup, test van eigenaar/nieuw toestel/afwijzing/verloop/klokmanipulatie/offline/update en controle van daadwerkelijke providerweergave.

## Voortgang van PR #29
- Main-tegels, originele geïnstalleerde launchericonen, afzonderlijke scopes, aanvragen en beheer-UI zijn voorbereid op ontwikkelbranch.
- 24-uurs offline bewijs en in-app enforcement ontbreken nog: **niet production ready**.
- Bestaande live licentieserver en individuele APK's zijn niet bijgewerkt of uitgerold.

## Standaard zichtbaarheid in Main
- Eigenaars-Main: Cinema, Series en TV staan altijd zichtbaar op het beginscherm met hun huidige iconen.
- Alle andere Main-apparaten: tegels standaard verborgen onder Instellingen > Verborgen tegels.
- Terugzetten maakt een tegel zichtbaar maar geeft geen toegangsrecht; openen blijft apart goedkeuren via de licentieserver.
- Remote en Media Player worden niet aan de vaste tegels toegevoegd.

## Updates voor telefoon en Google TV
- Iedere app en platformvariant heeft een eigen pakket-ID, signing identity, versienummer, build-artifact en Dev Hub-vermelding.
- Succesvolle CI-tests bouwen de juiste APK en publiceren uitsluitend die variant naar Dev Hub; oudere varianten en licentievelden moeten ongewijzigd blijven.
- De geïnstalleerde app controleert bij openen en periodiek of er een nieuwere versie voor **exact haar pakket** beschikbaar is, en toont proactief een updateaanbod. Android/Google TV installatie vereist de reguliere systeemtoestemming; geen ongeautoriseerde silent install beloven.
- Download via geauthenticeerde, gecontroleerde endpoint; controleer SHA-256, package name, hogere versionCode en dezelfde signing certificate voordat de installatie begint.
- Bij mislukt netwerk, download of ondertekening blijft de huidige app werken; bewaar de huidige instellingen en rechten.
- TV-installatie UX: een duidelijke afstandsbediening-vriendelijke updateprompt.
- Rechten en offline licentiecontrole blijven verplicht vóór media-afspelen, ook na een nieuwe update.
- Nog niet gerealiseerd: in-app updaters voor de zes afzonderlijke APK's; niet als live-gereed aanmerken voor end-to-end tests.
