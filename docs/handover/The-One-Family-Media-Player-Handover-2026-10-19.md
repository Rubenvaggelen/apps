# THE ONE FAMILY — VOLLEDIGE TECHNISCHE HANDOVER
## Google TV / Sony Bravia • The One Media Player • TV • Series

**Datum:** 19 oktober 2026  
**Doel:** veilige overdracht aan de volgende ontwikkelaar/ChatGPT-sessie, inclusief de werkwijze, actuele releasebasis, storingsgeschiedenis, GitHub-bewijs, APK's, openstaande werkpunten en herstelplan.  
**Afgesproken vaste basis:** **The One Media Player Google TV 1.0.36 STABLE**, `versionCode 1036`.  
**Status:** de definitieve APK is gebouwd en door de eigenaar-releasestraat ondertekend en geverifieerd. Installatie op alle drie fysieke Google TV-apparaten en de gewenste snelheidsverbetering zijn **nog niet bevestigd**. Deze onzekerheid mag niet worden weggepoetst.

---

## 1. Samenvatting voor de volgende ontwikkelaar

De gebruiker bouwt en onderhoudt een familie van eigen Android/Google TV-apps. Hij verwacht dat de ontwikkelaar zelfstandig onderzoekt, kleine gerichte wijzigingen maakt, GitHub Actions controleert, **daadwerkelijk ondertekende APK-bestanden in de chat beschikbaar stelt** en tijdens het werk korte voortgangsupdates geeft. De gebruiker wil géén lange reeks handmatige GitHub-stappen uitvoeren, behalve testen/installeren op een echt apparaat als dat nodig is.

**Huidig besluit van de gebruiker:** Media Player is functioneel naar tevredenheid. **Leg de definitieve 1.0.36-release vast als het startpunt voor toekomstige wijzigingen.** Vernieuw niets automatisch en voer geen extra experimenten uit op die vaste releasebranch. Voor elk volgend punt: nieuwe ontwikkelbranch vanuit exact het stabiele commit, tests, APK, dan eerst één apparaat.

### Bevestigd door de gebruiker

- **The One Series Google TV:** de historische reparatie-APK **1.2.3** op basis van **15 oktober** bracht het geluid terug op Sony/Google TV. De eerdere PCM-aanpassing in 1.2.2 werkte niet. **Niet ongevraagd vervangen door 1.2.2.**
- **The One Media Player Google TV 1.0.34:** het afspeelmenu en de knoppen **Geluid**, **Audio-info** en **Audio-uitvoer** verdwijnen na circa **5 seconden inactiviteit** en verschijnen weer bij bediening. Gebruiker bevestigde: **“Yes werkt perfect”**.
- Media Player speelde volgens de gebruiker verder goed; de catalogus was traag. Een volledige seriebibliotheek duurde ongeveer **2,5 minuten**, Nederland in Live TV ongeveer **1 minuut**. Op één Google TV lukte de seriebibliotheek; op een tweede gaf deze na **ongeveer 45 seconden** een foutmelding. De gebruiker was bezig dezelfde **1.0.34** op **drie** Google TV-apparaten te installeren, maar bevestigde niet de voltooiing van die installatie.

### Nog niet bevestigd op echte hardware

- Of de progressieve seriecatalogus in 1.0.35/1.0.36 op het langzame apparaat de fout na circa 45 seconden wegneemt.
- Werkelijke tijd tot *eerste* seriezoekresultaat, tot *volledig* geladen seriecatalogus en opnieuw openen uit cache op alle drie apparaten.
- Verbeterde laadtijd van Nederland Live TV. **De aparte Nederland-optimalisatie is nog niet geïmplementeerd.**
- Succesvolle installatie of automatische OTA van 1.0.36 op alle apparaten. **GitHub Actions groen ≠ fysiek geteste installatie.**

---

## 2. De autoritatieve Media Player-baseline

| Eigenschap | Vastgestelde waarde |
|---|---|
| App | **The One Media Player – Google TV / Sony Bravia** |
| Definitieve release | **1.0.36-stable** |
| Android `versionCode` | **1036** |
| Android `applicationId` | `com.theone.mediaplayer.tv` |
| Eigenaar-repo | `Rubenvaggelen/apps` |
| **Canonieke definitieve releasecommit** | **`7fa3f4af3df825b41bbfe22584b3a576d25aa45c`** |
| Releasebranch | `release/media-player-google-tv-v1036-stable-20261019` |
| Oorspronkelijke, bewaarde releasebasis | `baseline/media-player-google-tv-v1.0.36-final` op commit `bd8d2c984fdab063ea3d5321356fa721fba19a6f` |
| Featurecode waarop 1.0.36 voortbouwt | `ccb5631d5cab675f1eb25376ead1341af93a84fb` |
| Releaseworkflow | `.github/workflows/media-player-tv-pornhub-transport-release.yml` |
| **GROENE, canonieke GitHub Actions-run** | **`39254814996`** |
| Groene aanvullende tests | `39254814991` (TV-smoke) en `39254814927` (app-tests) |
| Getekend eigenaar-artifact | `12186932565`, `The-One-Media-Player-Google-TV-v1.0.36-STABLE-owner` |
| Bestandsnaam in handover-pakket | `The-One-Media-Player-Google-TV-v1.0.36-STABLE.apk` |
| Bestandsgrootte | **10.893.649 bytes** |
| **SHA-256 van canonieke STABLE-APK** | **`24931b0106ccb075a0e58e0f0d2b8e1b671c54c2b44b0ca438f08e0967a384fa`** |

Links:
- Canonieke run: https://github.com/Rubenvaggelen/apps/actions/runs/39254814996
- Canonieke releasecommit: https://github.com/Rubenvaggelen/apps/commit/7fa3f4af3df825b41bbfe22584b3a576d25aa45c
- Repository: https://github.com/Rubenvaggelen/apps
- Stabiele release-notitie in repo: `docs/releases/The-One-Media-Player-Google-TV-v1.0.36-STABLE-2026-10-19.md`

**Nauwkeurig onderscheid tussen twee 1.0.36-builds:** Er is ook een iets eerdere, succesvolle releasecommit `bd8d2c9...` met `versionName=1.0.36`, Actions-run `39254747662`, APK-hash `460b985c...`. De releasebranch kreeg daarna een documentatie-/workflowsynchronisatie en `versionName=1.0.36-stable` op `7fa3f4a...`, met een **nieuwe succesvolle gesigneerde build** en de hierboven genoemde **andere hash**. Voor een overdracht is **7fa3f4a... + hash 24931b...** de canonieke *STABLE* referentie. De branch `baseline/...` blijft bewust op de eerdere `bd8d2c9...` staan; gebruik voor nieuwe features expliciet de volledige **canonieke commit-SHA**, niet blind een branchnaam.

### Belangrijk voor versienummers

Op GitHub zijn later ook aparte **niet als stabiel uitgerolde testbranches** ontstaan voor zoeken/automatisch volgende aflevering:
- `fix/media-player-google-tv-queens-men-search-v1037-20261019`: testkandidaat **1.0.37**, geslaagde build/run `39285616964`.
- `feature/media-player-google-tv-auto-next-20261019`: testkandidaat **1.0.38**, geslaagde build/run `39303906734`.
- Voor The One Series is er daarnaast een aparte kandidaat 1.2.4 voor automatisch volgende aflevering (run `39303117102`).

Deze **testkandidaten zijn niet de afgesproken definitieve Media Player-baseline**. Ze kunnen als losse experimenten beschikbaar zijn, maar mogen niet zonder apart gebruikersakkoord als vervanging van 1.0.36 worden gepresenteerd. Bepaal bij elke nieuwe TV-build het **hoogste reeds gebruikte `versionCode`** en kies een hoger nummer; er zijn al kandidaatnummers **1037 en 1038** gebruikt. Gebruik dus bijvoorbeeld 1039 of hoger, na controleren van de actuele repo. Forceer geen Android-downgrade.

---

## 3. Inhoud van de bevroren Media Player-release

**Afspeel- en TV-functionaliteit:** Live TV, films, series, geselecteerde afleveringen, zoeken, Nederland/andere landen, afstandsbediening, vijfseconden-timer voor controls, audiotracks en audio-info, wisselbare audio-uitvoer, Sony-/Google TV-decoderfallback, toegangs- en favorietenfunctionaliteit.

**Bronnen en TV-indeling:** twee afzonderlijke private Xtream-bronnen; identiteit van elk kanaal/aflevering en bron behouden; tweede bron kan voorrang bij afspelen krijgen, maar eerste bron blijft beschikbaar als alternatief. Nederland uitsluitend kanalen met een ruwe naam die met `NL` begint, met de eerder overeengekomen Ziggo-/ESPN-volgorde. Geen stille verkleining van kanaallijsten bij tijdelijke bronproblemen. Adult-/XXX-toegang blijft afgeschermd door de bestaande toegangscontrole.

**Bibliotheek:** 1.0.33 introduceerde caching voor seriecatalogi per bron/categorie en parallelle categorieaanvragen. 1.0.35 introduceerde progressieve verwerking van de *volledige seriezoekbibliotheek* in blokken, met brongebonden snapshots. 1.0.36 STABLE **neemt deze code onveranderd over**. De eerste resultaten kunnen dus eerder worden getoond bij een provider die gegevens geleidelijk terugstuurt. Dit garandeert niet dat elke Xtream-provider zelf server-side gepagineerde data aanbiedt, noch een vaste maximale laadtijd op elk toestel.

**Belangrijke bronbestanden:**

- `media-player/app/src/main/java/com/theone/mediaplayer/MainActivity.java`: ExoPlayer, audio-uitvoer, afspelen, vijfseconden-timeout van speler- en audio-overlays, Google TV-afstandsbediening.
- `media-player/app/src/main/java/com/theone/mediaplayer/XtreamCatalogActivity.java`: lijst, categorieën, favorieten, NL-kanaalselectie, afleveringcatalogus, zoeken in blokken (`searchSeriesInBatches`), progressieve resultaten en kaartopbouw.
- `media-player/app/src/main/java/com/theone/mediaplayer/XtreamConfig.java`: twee Xtream-bronnen, account- en streamroutering, provider-ID-prefix, diskcaches, herstel op basis van bewaarde catalogus, `streamSeriesCatalog`, streaming-/batchverwerking.
- `media-player/app/src/test/java/com/theone/mediaplayer/XtreamConfigTest.java`: tests voor bronidentiteit, verval-/herstelgedrag van snapshots en seriebatches.
- `media-player/app/src/main/java/com/theone/mediaplayer/ChannelGuide.java`: landen/kanaalvolgorde; niet wijzigen zonder expliciete regressietest.
- `media-player/tvapp/build.gradle.kts`: Google TV-package, buildversie; Android-telefoonversie staat apart in `media-player/app/build.gradle.kts`.
- `.github/workflows/media-player-tv-pornhub-transport-release.yml`: herstel van vertrouwelijke bron-assets tijdens CI, unit-tests, emulatorstart, eigenaarsondertekening, APK-verificatie en eigenaar-artifact.

---

## 4. Chronologie van de storing en oplossing

| Datum/versie | Gedaan of gevonden | Bewezen uitkomst |
|---|---|---|
| 15 oktober | The One Series 1.2.0 oorspronkelijke ExoPlayer/bronvolgorde; The One TV Sony 1.0.9 referentie | Historisch ijkpunt |
| 18 oktober / Series 1.2.2 | Bron 2 voorrang, Sony PCM-/audio-compatibiliteitswijzigingen | Op Sony nog steeds **geen** seriesgeluid |
| 19 oktober / Series 1.2.3 | Historische code van 15 oktober opnieuw gebouwd, hogere versionCode | **Geluid werkt weer, bevestigd door gebruiker** |
| Media Player 1.0.31/1.0.32 | Audio-/decoderopties en fallback; overige herstelwijzigingen | CI/testkandidaten; afzonderlijke fysieke audiotest niet sluitend |
| Media Player 1.0.33 | Seriecataloguscache en gelijktijdige bronnen, minder blanco foutschermen | Bouw geslaagd; gebruiker meldt dat de app verder werkt, maar bibliotheek bleef traag |
| Media Player **1.0.34** | Vijfseconden-menuherstel buiten Media3-focus-timeout | **Werkend op TV bevestigd door gebruiker** |
| Media Player **1.0.35** | Full-series zoekbibliotheek in kleinere blokken, asynchrone resultaten, broncache | CI geslaagd; echte 45s-hardwarestoring nog niet bewezen opgelost |
| Media Player **1.0.36 STABLE** | Bevroren definitieve basis vanuit 1.0.35 + bewezen 1.0.34-functionaliteit, nieuwe versie/ondertekening | **GitHub/CI geslaagd**, fysieke metingen op drie apparaten nog open |

### Waarom de ene TV nog een bibliotheekfout kan geven

- Verschillen in verwerkingssnelheid, geheugen, tijdelijke caches en netwerkomstandigheden van de twee Google TV-modellen.
- Grote Xtream-resultaten; bron 1 of bron 2 kan traag reageren of tijdelijk onbereikbaar zijn.
- Een complete catalogus is mogelijk omvangrijker dan een categorie, waardoor parsing en renderen zwaarder zijn.
- Bij herhaald laden kan de nieuwe cache helpen; een koude start zonder geldige snapshot vereist nog netwerkwerk.

**Dit zijn hypotheses, geen uitgevoerde apparaatdiagnoses.** Onderzoek met tijdmetingen per provider, geheugen- en foutlogs voordat je één oorzaak als bewezen aanmerkt. De oorspronkelijke circa-45-secondenfout is niet automatisch opgelost enkel omdat de emulator slaagt.

---

## 5. Testprotocol voor drie Google TV-apparaten

1. **Inventariseren, niet gokken:** apparaatnaam/model (Sony/Google), OS-versie, huidige Media Player-versie op het apparaat, of de gebruikersinstallatie van 1.0.34 op alle drie is voltooid, verbindingstype wifi/ethernet, beschikbare opslagruimte.
2. **Eén gecontroleerde testinstallatie** van canonieke 1.0.36 STABLE op het apparaat waarop de bibliotheek na ~45 seconden faalde. **Niet vooraf alle drie bijwerken.** De APK heeft dezelfde `applicationId` en dezelfde eigenaar-signature; installeer over de bestaande app, geen uninstall en geen data wissen.
3. Meet apart: eerste pagina met categorieën, klik op “Volledige seriebibliotheek”, **tijd tot eerste zichtbare series**, aantal resultaten na 15/30/45/60 seconden, tijd tot voltooiing of fout, tijd tot opnieuw openen uit cache.
4. Test dezelfde zoekopdracht en dezelfde serie op het sneller werkende toestel. Test zo mogelijk mét en zonder eerder gevulde cache, zonder persoonsgegevens te wissen.
5. TV Nederland: tijd tot eerste kanaal, tijd tot complete lijst; check expliciet NL-prefix, de eerste Ziggo-kanalen, ESPN FHD-prioriteit en afspeelalternatieven. Laat je niet misleiden door een **snelle maar onvolledige** lijst.
6. Verifieer regressies: Live TV en XXX-geautoriseerde kanalen spelen af met geluid, films en series spelen af, terug-knop en D-pad/focus werken, audio-/ondertitelkeuzes, vijfseconden-timeout, favorieten, licenties/toegang en automatische vervolgaflevering alleen indien opgenomen in een latere expliciet goedgekeurde versie.
7. Noteer alle exacte foutmeldingen, tijden en het betrokken model. Verzamel indien mogelijk `adb logcat` met filtering op `TheOneMediaPlayer`, Java-excepties, Media3 en HTTP-statussen; **redigeer wachtwoorden/stream-URL's** voor delen.
8. Pas na een schone eerste test volgen de andere twee apparaten. Leg per toestel de versie en het resultaat vast.

**Acceptatiecriteria:** geen crash of permanente blanco bibliotheek; eerste zichtbare serie aanzienlijk eerder dan de oude 2,5 minuut indien provider progressief levert; heropenen sneller uit cache; complete catalogus zodra alle providers slagen; bij timeout duidelijke gedeeltelijke status en veilige retry; nul verlies van bestaande NL-zenders, voorkeursvolgorde, favorieten of geluid.

---

## 6. Openstaande ontwikkeling in prioriteitsvolgorde

**P0 — eerst resultaten van 1.0.36 op het trage Google TV-apparaat.** Is de 45s-fout weg? Worden blokken werkelijk zichtbaar? Is de tweede Xtream-bron traag? Indien niet: analyseer `streamSeriesCatalog`, `streamSeriesProvider`, het JSON-batchpad en de hoofdthread-rendering. Zorg dat een gedeeltelijk antwoord nooit als definitief *volledig* wordt gemarkeerd.

**P1 — Nederland Live TV versnellen (nog niet gebouwd):** haal de landenlijst uit bewaarde veilige catalogus, toon beschikbare NL-kanalen zodra hun data binnenkomt, verwerk aanvullende providerantwoorden in blokken en toon voortgang/teller. Vereis dat een oude volledige snapshot bewaard blijft wanneer één provider ontbreekt. Geen extra netwerkrequests die de providers overbelasten. Meet koud/warm en 2 bronnen apart. Test de NL-prefix/Ziggo-/ESPN-regels integraal.

**P1 — bibliotheekoptimalisatie:** verlaag onnodige herparsing en volledig hertekenen van veel posters; houd het aantal gelijktijdige posterdownloads begrensd, respecteer geheugenlimieten op oude Google TV's. Maak retry- en voortgangsmeldingen informatief en verlies nooit favoriete gegevens.

**P2 — update-installatie Google TV:** eerdere automatische update-installaties werkten niet betrouwbaar. Maak eerst onderscheid tussen beschikbaarheid, downloaden, Android PackageInstaller-toestemming en definitieve succesvolle installatie. *Silent install* mag niet worden aangenomen; Android kan om gebruikersgoedkeuring vragen. Publiceer geen productie-OTA totdat de gebruiker dat vraagt en het één-apparaat-testprotocol groen is.

**Ander parallel project:** The One Series Google TV v1.2.3 met werkend geluid, The One TV Google TV laatst bekende 1.0.22. Niet aan hun bronvolgorde of audiopad sleutelen vanuit deze Media Player-taak. Apart verwerken met eigen regressies en APK's.

---

## 7. Werkwijze van de ontwikkelaar — verplicht vanaf deze handover

### A. Begin van iedere sessie

1. Lees **deze handover** en de vastgelegde GitHub-release-notitie. Controleer de **actuele GitHub-branch-heads** en de GitHub Actions-runs; geen aanname dat een oude samenvatting de nieuwste versie is.
2. Lees het laatste concrete gebruikerssignaal: welke TV, welke appversie, welke knop/foutmelding, wat werkt wél, wat is al getest? Vraag alleen om nieuwe informatie als die technisch noodzakelijk is.
3. Maak een **kleine diagnose/risico-afbakening**: welke code is waarschijnlijk betrokken, welke functies mogen niet wijzigen? Controleer de bekende werkende baseline en recente diff.
4. Rapporteer kort: vastgesteld, waarschijnlijk, onbekend, volgende gecontroleerde stap. **Geen onbewezen oorzaken of fictieve voortgang claimen.**

### B. Ontwikkelen

5. Maak **een aparte branch** vanaf de exacte stabiele commit `7fa3f4af3df825b41bbfe22584b3a576d25aa45c`; óók als een andere huidige branche inmiddels hoger staat. Gebruik een beschrijvende naam (bijv. `feature/media-player-tv-nl-progressive-YYYYMMDD`). Houd de baseline/releasebranch intact.
6. Verander alleen de code die nodig is. Scheid TV van telefoon waar mogelijk; gedeelde Java-code kan onbedoeld ook de telefoon raken. Laat package IDs, ondertekening, Xtream-accounts, licenties, adult-beveiliging en kanaalregels ongemoeid.
7. Voeg regressietests toe voor het gemelde probleem en voor eerder bevestigde goede functies. Controleer fallback bij bronuitval, onvolledige catalogus, trage/lege respons en beperkte TV-hardware. Voorkom oneindig laden op de UI-thread.
8. Versiebeheer: controleer *alle* al gebruikte tv-`versionCode`s, inclusief concurrerende testbranches. Versiecode moet geschikt zijn voor installatie over de reeds aanwezige APK; uit deze overdracht bestaan ten minste 1036, 1037 en 1038.
9. Schrijf een changelog met exact wat wel/niet is gewijzigd. Lees of herleid diff alvorens te committen/pushen, met actuele HEAD-concurrentiecontrole; overschrijf geen werk van andere agents.

### C. CI, beveiliging en release

10. Bouw via de bestaande GitHub Actions-workflow; unit-tests en TV-emulatorstart moeten groen. Controleer packaging, Android launcher/remote, beide privébron-assets, `applicationId`, `versionCode`, signatuurdigest en de benodigde update-permissies.
11. Gebruik **de bestaande permanente eigenaar-signing key** uit de beveiligde CI-configuratie. Genereer geen nieuw certificaat; dat zou updates over een bestaande installatie verhinderen. Geheimen mogen niet in documentatie, logging, Markdown, chat, screenshots of publiek GitHub worden opgenomen.
12. Een groene Gradle-taak is onvoldoende: de **hele officiële releaseworkflow** moet geslaagd zijn. Lees ook de releasestappen en controleer het daadwerkelijke *signed owner artifact*. Let op: een losse smoke-workflow kan door een tijdelijke Android emulator-downloadfout falen. Classificeer de fout op basis van logs; negeer echte appcrashes niet.
13. Download het juiste getekende artifact, pak de `.apk` uit, controleer dat het een geldig Android APK-archief is met `AndroidManifest.xml`, noteer bestandsgrootte/SHA-256 en verifieer de digitale APK-signing via CI/`apksigner`. **Link nooit naar een niet-bestaand bestand.**
14. Geef de gebruiker de **werkelijke downloadlink in de chat**, duidelijk met **Google TV** of **Android-telefoon**, versienaam en wijziging. Alleen zeggen “staat in Dev Hub” voldoet niet als een directe APK is gevraagd.
15. **Release/promotie/OTA** gebeurt pas na akkoord en fysieke één-apparaat-validatie. GitHub-artifact is niet hetzelfde als een live update-manifest of geïnstalleerde app. Maak het verschil expliciet.

### D. Testen, opleveren en herstellen

16. Laat de gebruiker **over de bestaande app heen installeren**; geen uninstall, geen wissen van cache/favorieten tenzij een afzonderlijke herstelactie na toestemming noodzakelijk is. Voldoende vrije ruimte controleren.
17. Vraag om **gerichte uitkomst** op het concrete toestel: werkt/niet, stopwatchtijd, fouttekst/foto. Bevestig geen hardwarefix voordat de gebruiker die daadwerkelijk test.
18. Indien regressie: stop rollout, leg eerste slechte versie vast, gebruik de canonieke baselinebron om een nieuwe hogere versie te bouwen; **forceer geen downgrade naar lagere `versionCode`**. Houd APK en instellingen intact.
19. Na iedere goede wijziging actualiseer deze handover: broncommit, versie, Actions-run/artifact, APK-SHA, bevestigde tests, openstaand risico, exacte volgende stap. Maak indien nuttig ook een recovery-ZIP met APK/README/checksums.
20. Houd de gebruiker tijdens langer werk actief op de hoogte met korte technische updates: wat is veranderd, welke test draait, wat blokkeert, wanneer is een echte APK beschikbaar. **Niet alleen “ik ben bezig” melden.**

---

## 8. Reproductie / GitHub-toegang voor volgende ontwikkelaar

**Repository:** `https://github.com/Rubenvaggelen/apps`.

```bash
# Controleer eerst dat de repository en de laatste origin beschikbaar zijn.
git fetch origin --prune
# Gebruikersafspraak: codebasis exact vanaf de canonieke STABLE-release.
git switch -c feature/media-player-tv-next-change \
  7fa3f4af3df825b41bbfe22584b3a576d25aa45c
# Bekijk diff en controleer nieuwe versiecode vóór een build.
git diff --stat 7fa3f4af3df825b41bbfe22584b3a576d25aa45c...HEAD
```

**Canonieke release-tests:**

- Release: https://github.com/Rubenvaggelen/apps/actions/runs/39254814996 — `success`.
- Google TV-smoke: https://github.com/Rubenvaggelen/apps/actions/runs/39254814991 — `success`.
- Media Player unit/app-smoke: https://github.com/Rubenvaggelen/apps/actions/runs/39254814927 — `success`.

**Let op:** deze voorbeelden geven een werkwijze, geen opdracht om nu opnieuw te bouwen of productie te publiceren. CI-secretbeheer en privébron-restore verlopen uitsluitend via de bestaande workflows en geautoriseerde connectors. GitHub Actions-artifacts hebben mogelijk beperkte bewaartijd: daarom zit de ondertekende APK in het recovery-pakket.

---

## 9. Recovery-inhoud en checksums

Bij deze handover hoort een ZIP met:

- `The-One-Media-Player-Google-TV-v1.0.36-STABLE.apk` — de **canonieke definitieve release-APK**, SHA-256 `24931b0106ccb075a0e58e0f0d2b8e1b671c54c2b44b0ca438f08e0967a384fa`.
- `The-One-Media-Player-Google-TV-v1.0.34.apk` — eerder door gebruiker werkend verklaarde menu-/afspeelbasis voor vergelijkingen; niet zonder beleid als Android-downgrade installeren.
- Dit overdrachtsbestand.
- `SHA256SUMS.txt` met SHA-256-controle van de meegeleverde bestanden.

Vertrouw niet alleen op de bestandsnaam: controleer signature, package, `versionCode`, SHA en het bijbehorende GitHub-commit. Het recovery-ZIP is bedoeld voor de eigenaar/ontwikkelaar en kan APK's bevatten met privé configuratie-assets; **niet publiek delen**.

---

## 10. Beslispunt en instructie voor de eerstvolgende sessie

**Niet opnieuw vanaf 15 oktober herbouwen als algemene remedie.** Die aanpak was specifiek nodig voor de Series-audiostoring en is daar bewezen effectief. Voor Media Player geldt vanaf nu **1.0.36 STABLE / commit `7fa3f4a...`** als referentiepunt. De nieuwe full-series blokverwerking moet nog op de fysieke apparaten worden beoordeeld.

**Eerste vraag in het vervolg:** welk(e) Google TV-apparaat/apparaten heeft/hebben 1.0.36-stable geïnstalleerd en hoe snel verschijnt de eerste serie in de *volledige bibliotheek*, met name op het apparaat met de ~45s-fout? Zodra dat bevestigd is, pak de Nederland Live TV-laadoptimalisatie op als **nieuwe, afzonderlijke featurebranch**, met harde tests tegen onvolledige lijsten en gewijzigde Ziggo-/ESPN-volgorde.

**Eindstatus van deze handover:** bronbasis en signing zijn vastgelegd, canonieke APK beschikbaar en geverifieerd, tests geslaagd; fysieke prestatievalidatie en Nederland-blokladen staan open.
