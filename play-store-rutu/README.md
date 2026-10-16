# RUTU BBQ Klant — Google Play publicatievoorbereiding

Status: **technische voorbereiding, nog niet gepubliceerd**. Dit is een aparte
featurebranch. De live RUTU Klant-APK, bestellingen, API, QR-code en Dev Hub
zijn niet gewijzigd.

## Google Play bedrijfsaccount
- Registreer via https://play.google.com/console/ als **organisatie** als RUTU een verifieerbaar bedrijf is.
- Google rekent eenmalig USD 25 registratiekosten.
- Google vraagt een D-U-N-S-nummer, officiële bedrijfsgegevens en verificatie.
  Een D-U-N-S-aanvraag bij Dun & Bradstreet is gratis, maar kan enige tijd duren.
- Naam in de Play Store: **RUTU BBQ** (onder voorbehoud van verificatie bij Google).
- App: **RUTU BBQ – Bestellen** (werknaam).
- Categorie: Eten en drinken.
- App-kosten: gratis.
- In-app transacties: fysieke BBQ-maaltijden; afrekening buiten Google Play is mogelijk.

## Isolatie en technische keuzes
- Branch `feature/rutu-google-play-20261016` heeft de Play-bewerking. **Nooit**
  samenvoegen in `feature/jade-orders` voordat de bestaande live APK-route
  afzonderlijk is getest en goedgekeurd.
- Het Play-artifact is een Android App Bundle (`.aab`) op API 36.
- Bestaande package name: `com.vanaggelen.jadeorders`.
- Uitrolversie: 938, hoger dan het op 16 oktober 2026 bekende live build 937.
- Geen `REQUEST_INSTALL_PACKAGES`; geen Chrome-APK-updates.
- Google Play verzorgt installatie en updates.
- Huidige gedrukte QR blijft exact dezelfde HTTPS-URL:
  `https://rubenvanaggelen.com/rutu-updates/Rutu-BBQ-Android.apk`
- Android App Links kunnen die URL direct naar de app sturen **na**
  publicatie van een bijbehorende `/.well-known/assetlinks.json`, met het
  **Play App Signing** SHA-256 certificaat van Google. Dat certificaat is
  nog niet beschikbaar. Tot verificatie blijft browsergedrag afhankelijk
  van de telefoon.
- Voor nieuwe klanten verandert de bestaande URL **nog niet**. Beslis pas
  over een server-redirect naar de Play Store als de live Store-pagina is
  goedgekeurd en is getest. Geen nieuwe QR-code nodig.

## Ondertekening en huidige APK-gebruikers
De oude APK wordt ondertekend met een vaste debug-keystore. De nieuwe
Google Play AAB moet een afzonderlijke, veilig bewaarde upload-sleutel
krijgen. **Let op:** als Google de Play APK's met een ander app-signing
certificaat ondertekent, kan de Play-versie niet over een bestaande
sideload-installatie met een andere handtekening heen geïnstalleerd
worden. Beloof geen in-place update tot dit op echte apparaten getest
is; maak een migratie/backupplan voor opgeslagen klantinstellingen.

De CI maakt zonder secrets alleen een **niet-ondertekende test-AAB**.
Voor een publiceerbare AAB moeten in de repo GitHub Actions Secrets
de volgende waarden veilig worden ingesteld:
`RUTU_PLAY_UPLOAD_KEYSTORE_BASE64`,
`RUTU_PLAY_UPLOAD_KEYSTORE_PASSWORD`,
`RUTU_PLAY_UPLOAD_KEY_ALIAS`,
`RUTU_PLAY_UPLOAD_KEY_PASSWORD`.
Publiceer de privésleutel nooit in GitHub of deze chat.

## Verplichte Store-informatie
- Privacyverklaring als **publiek bereikbare HTML-pagina**, óók vindbaar in de app.
- Bevestigde verwerkingsdoelen, bewaartermijnen, verwijderprocedure,
  ontvangers, hosting en eventuele betaalproviders voor de Data Safety-sectie.
- Contactgegevens uitgever, ondersteuningsmail en eventueel zakelijke website.
- Schermafbeeldingen van de uiteindelijke app, icoon, beschrijving,
  leeftijdsclassificatie, beleidsverklaringen, test/preview.
- App niet voor kinderen categoriseren zonder toetsing.

## Voorbeeld Play Store-tekst
**Titel:** RUTU BBQ – Bestellen

**Korte beschrijving:** Bekijk het BBQ-menu, plaats je bestelling en volg de status via RUTU BBQ.

**Uitgebreide beschrijving:**

RUTU BBQ brengt onze BBQ-gerechten rechtstreeks naar je telefoon. Bekijk het
actuele menu, kies je favorieten en plaats eenvoudig een bestelling.

Met RUTU BBQ kun je het beschikbare assortiment bekijken, een bestelling
samenstellen, beschikbare bestelopties kiezen en de bestelstatus volgen.
Je ontvangt updates over je bestelling in de app.

Beschikbaarheid, prijzen, levering en betaalmogelijkheden worden bij het
bestellen getoond. Zo weet je waar je aan toe bent.

RUTU BBQ — lekker eten, samen genieten.

## Volgende go/no-go
1. Zakelijke Play Console openen en verificatie afronden.
2. Upload-sleutel aanmaken en de gesigneerde AAB klaarzetten.
3. Privacyverklaring en Data Safety op echte data-handling controleren.
4. Interne Play-test op schone telefoon én reeds met APK geïnstalleerde telefoon.
5. Na goedkeuring: Play-appsigning SHA-256 publiceren in domain-verificatie;
   gedrukte QR en HTTPS-downloadpad onaangeroerd laten tot test afgerond.
