# Gmail Org Hub — één overzicht voor meldingen van al je apps

Een native Android-app (Kotlin) die meldingen van **alle apps** op je
telefoon opvangt — WhatsApp, Instagram, Facebook Messenger, Telegram,
X, Gmail, sms, en meer — en in één lijst toont. Waar de melding een
"snel antwoorden"-actie ondersteunt (zoals bij WhatsApp/sms/Telegram
meestal het geval is), kun je direct vanuit deze app reageren, zonder
de bron-app te openen.

## Wat de app doet (bijgewerkt)

Bij het openen zie je nu een **startscherm met icoontjes**, geen tabbladen:

- **Meldingen** — de live meldingen-hub (WhatsApp, Instagram, enz., zoals
  hierboven beschreven).
- **Mail & Kalender** — opent je Gmail Org web-app in een ingebouwde
  Chrome-tab (met adresbalk zichtbaar). Dit moest via een "echte"
  browser-tab in plaats van een ingebouwd browserscherm, omdat Google
  het inloggen anders blokkeert vanuit veiligheidsoverwegingen.
- **App toevoegen (+)** — kies zelf een ge&iuml;nstalleerde app (bijv.
  Google Home, LSC Smart Connect, of wat dan ook) om als icoontje toe te
  voegen. Lang indrukken op een toegevoegd icoontje verwijdert 'm weer
  van het startscherm (de app zelf blijft gewoon ge&iuml;nstalleerd op
  je telefoon).

## Belangrijk: wat dit wél en niet is

- **Dit werkt via meldingen, niet via een API.** WhatsApp, Instagram
  en de meeste social-media-apps bieden geen publieke API waarmee een
  app jouw volledige chatgeschiedenis of feed kan binnenhalen (dat is
  precies waarom er geen "WhatsApp Web-achtige" apps in de Play Store
  bestaan van derde partijen). Wat wél kan: elke melding die het
  besturingssysteem toont, opvangen — dat is precies wat deze app doet.
- Je ziet dus alleen berichten die **binnenkomen terwijl de app
  draait** — geen oude geschiedenis.
- Beantwoorden werkt alleen als de originele melding een "Antwoorden"-
  knop had (bij de meeste chat-apps is dat zo). Bij apps zonder zo'n
  actie (bijv. gewone Instagram-likes) zie je de melding wel, maar kun
  je niet vanuit deze app reageren.
- Dit is een aparte native app, los van de Gmail Org PWA die je eerder
  kreeg — Gmail via de officiële API blijft daar het beste werken.

## Wat je nodig hebt

- **Android Studio** (gratis, van developer.android.com/studio).
- Een Android-telefoon met **USB-debugging** aan (Instellingen >
  Over telefoon > 7x tikken op buildnummer om ontwikkelaarsopties te
  activeren, dan USB-foutopsporing aanzetten).

## Bouwen zonder computer (via GitHub, alles vanaf je telefoon)

Geen computer? Geen probleem — GitHub kan de app in de cloud bouwen.
Dit project bevat al het benodigde bestand (`.github/workflows/build-apk.yml`).

1. Maak op github.com (kan gewoon vanuit de mobiele browser) een nieuwe
   **repository** aan, bijvoorbeeld genaamd `gmail-org-hub`.
2. Upload **alle bestanden en mappen** uit dit project naar die
   repository — inclusief de verborgen map `.github/workflows/`. Let op:
   sommige upload-schermen tonen verborgen mappen niet automatisch; zorg
   dat `build-apk.yml` er echt bij staat (zie je 'm niet, gebruik dan de
   GitHub-app en maak het bestand handmatig aan met exact dezelfde inhoud).
3. Ga naar het tabblad **Actions** bovenin je repository. Er start
   automatisch een build ("Build APK") zodra de bestanden er staan.
4. Wacht tot het groene vinkje verschijnt (duurt meestal 2-4 minuten).
5. Klik op de afgeronde build → onderaan bij "Artifacts" staat
   **gmail-org-hub-apk** → tik erop om te downloaden (dit is een zip met
   de APK erin).
6. Pak de zip uit op je telefoon (bijv. met de ingebouwde
   bestanden-app), tik op het `.apk`-bestand om te installeren.
7. Android vraagt mogelijk om "installeren van onbekende bron" toe te
   staan voor je browser/bestanden-app — zet dat aan en installeer.
8. Open de app en volg de stappen hieronder bij "Eerste keer instellen".

## Bouwen met een computer (alternatief)

Heb je toch een keer toegang tot een computer, dan kan het ook lokaal:

1. Open Android Studio → "Open" → kies deze hele projectmap.
2. Laat Android Studio de Gradle-sync afronden (dit kan een paar
   minuten duren bij de eerste keer, en vraagt mogelijk om de juiste
   Gradle-versie te downloaden — accepteer dat gewoon).
3. Sluit je telefoon aan via USB, kies je toestel bovenin de werkbalk,
   en klik op de groene "Run"-knop (▶).
4. De app installeert en start automatisch op je telefoon.

## Eerste keer instellen op je telefoon

1. Open de app — bovenin zie je een banner "Meldingtoegang nodig".
   Tik op "Meldingtoegang geven".
2. Je komt in de systeeminstellingen terecht bij "Meldingtoegang" (of
   "Speciale toegang" > "Meldingtoegang"). Zoek "Gmail Org Hub" in de
   lijst en zet 'm aan. Bevestig de waarschuwing van Android.
3. Ga terug naar de app. Zodra er een nieuw bericht binnenkomt in
   WhatsApp, Instagram, Messenger, enz., verschijnt die hier.

## Gebruik

- **Chips bovenin**: filter op app ("Alles", "WhatsApp", "Instagram", ...).
- **Tik op een bericht** met een antwoord-knop: er verschijnt een
  invoerveld, typ je antwoord en tik "Stuur".
- **Veeg een bericht weg** om het uit de lijst te verwijderen (dit
  verwijdert 'm alleen hier, niet uit de bron-app).
- **"Wissen"** bovenin: leegt de hele lijst.

## Bekende beperkingen

- Geen geschiedenis vóór het moment dat je de app installeert en
  toegang geeft.
- Reageren werkt alleen bij meldingen met een ingebouwde antwoord-actie.
- Als je de telefoon herstart, kan Android de service soms pas na het
  eerste ontgrendelen weer opstarten — dat is normaal gedrag van het
  besturingssysteem, geen bug in de app.
- Sommige fabrikanten (Xiaomi, Huawei, Samsung in agressieve
  batterijbesparingsmodus) doden achtergrondservices actief — zet
  batterijoptimalisatie voor deze app uit als meldingen wegblijven.
