# The One Media Player Google TV — Definitieve basisrelease 1.0.36
Datum: 19 oktober 2026

## Scope
Deze release is gebaseerd op gecontroleerde 1.0.35-catalogusoptimalisatie en de door de eigenaar op Sony/Google TV bevestigde werkende bediening uit versie 1.0.34.

### Vastgelegde functies
- Google TV/Android TV-app met hetzelfde package `com.theone.mediaplayer.tv` en dezelfde permanente ondertekening.
- Live TV, Nederland NL-zenders, Ziggo-volgorde, ESPN-volgorde, beide private Xtream-bronnen, afspeelalternatieven.
- Series en filmbibliotheken; categorie- en providercache; streaming van de volledige seriezoekbibliotheek in blokken.
- Audio-uitvoer, audiotrackkeuze, audio-informatie en Sony-compatibiliteitsopties.
- Afspeelknoppen en audio-overlay verbergen na 5 seconden inactiviteit op Google TV.
- Favorieten, ouder-/adult-toegang en overige bestaande gebruikersinstellingen blijven ongemoeid.

### Basis/terugdraaien
- Exacte bronbasis voor deze versie: `ccb5631d5cab675f1eb25376ead1341af93a84fb`.
- Versie: `1.0.36`, versionCode `1036`.
- Releasebron: `release/media-player-google-tv-v1036-stable-20261019`.
- Behandel de definitieve releasecommit als immutable; begin alle volgende wijzigingen op een nieuwe featurebranch vanaf deze release.
- Voor elke wijziging: unit-tests, Android TV emulatorstart, controleren beide Xtream-assets, package/signature, daarna testen op één toestel alvorens de andere twee.

### Bekende onzekerheden en werkafspraken
- De 5s-menufix van 1.0.34 is door de eigenaar op echte hardware bevestigd.
- Streaming en caching van 1.0.35/1.0.36 zijn automatisch getest; de werkelijke winst en stabiliteit van de volledige bibliotheek op alle drie de TV-apparaten blijven te meten.
- Een incidentele externe emulator-downloadstoring in een parallelle smoketest is niet dezelfde als de geslaagde eigenaar-releasepipeline.
- De definitieve APK staat als private GitHub Actions-artifact klaar; automatische productiepromotie/OTA blijft uit om onbedoelde installatie te voorkomen.
- Als een TV faalt: bestaande installatie niet verwijderen; behoud de 1.0.34 APK als bekende veilige fallback voor verdere diagnose. Installatie van een lagere versionCode vereist een expliciete terugrolprocedure; niet automatisch downgraden.
- Geen inloggegevens of private Xtream-urls in publieke release-/handoverteksten.

## Focus volgende iteraties
1. Performance op drie Google TV's: tijd eerste resultaten, tijd volledig, herladen uit cache, bronstatus.
2. Nederland-kanalen sneller opbouwen zonder het verlies van kanalen of kanaalvolgorde.
3. Isolatie van trage provider, voortgangsindicatie en eventuele geheugenproblemen bij lange catalogi.
