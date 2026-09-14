# The One Car — vaste contacten + menu-knop update

Deze build is gebaseerd op `the-one-car-unread-badge-drag-tiles-build.zip`.

## Nieuw

- Op ieder intern The One Car-scherm staat `← Terug naar menu` en die brengt direct terug naar het hoofdscherm.
- WhatsApp-contactbeheer werkt nu met een gesloten lijst vaste contacten.
- Bestaande vaste contacten blijven behouden.
- Devon is toegevoegd als vast contact met nummer `+31627552130`.
- Envy is toegevoegd als vast contact met nummer `+31643289810`.
- Ieder vast contact heeft een eigen aan/uit-schakelaar.
- Het vaste-contactenscherm is met een pincode beveiligd. De pincode wordt niet als leesbare tekst in de broncode opgeslagen; er wordt lokaal een SHA-256 vergelijking gebruikt.
- Bij iedere nieuwe verbinding worden losse/niet-vaste contacten uit de The One Car-contactlijsten verwijderd.
- Aan/uit-keuzes van de vaste contacten blijven bewaard bij opnieuw verbinden.
- Niet-vaste WhatsApp-contacten worden niet meer automatisch aan het vaste-contactenscherm toegevoegd.

## Behouden uit de vorige build

- Ongelezen WhatsApp-badge op het dashboard.
- Dashboardtegels lang indrukken, verslepen en de gekozen volgorde bewaren.
- Achtergrondmuziek/audio-ducking en de eerdere hotspot/verbinding-functionaliteit.
