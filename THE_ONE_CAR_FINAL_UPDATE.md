# The One Car – K2401 complete update

Deze projectversie combineert de bestaande telefoon-app met de K2401-specifieke The One Car-interface.

## Verbinding
- Wi-Fi/LAN is de voorkeursverbinding tussen radio en telefoon.
- Bluetooth UUID is voor The One-protocol uitgeschakeld.
- Vast RFCOMM-kanaal 8 blijft alleen noodfallback.
- Wanneer CH8 actief is en Wi-Fi beschikbaar wordt, schakelt The One bij de volgende reconnect naar Wi-Fi.

## WhatsApp
- Gesprekken per contact met aliassen en contactfilter.
- Standaardcontacten: Dochter, Ma, CA en Test.
- Contacten kunnen op de radio worden verwijderd.
- Alleen inkomende WhatsApp-berichten verschijnen onder Meldingen; technische logregels worden daar niet getoond.
- Spraakantwoord: maximaal 60 seconden; automatisch stoppen na 5 seconden echte stilte.
- Volledige opname wordt naar de telefoon gestuurd en als geheel getranscribeerd.
- Ontvangen WhatsApp-spraakberichten zijn afspeelbaar wanneer WhatsApp een audio-URI of afspeelactie aan Android beschikbaar stelt.

## Auto-dashboard
- The One Car kan bij boot/wake automatisch naar voren komen op de K2401.
- Tegels kunnen worden verwijderd en via Instellingen > Tegels beheren worden teruggezet.
- Geïnstalleerde apps kunnen als tegel worden toegevoegd en weer verwijderd.
- Radio-instellingen hebben op de autoradio geen The One-pincode.

## USB
- USB opent een ingebouwde The One-speler, niet de K2401-fabriekslauncher.
- De speler leest gemounte verwijderbare MediaStore-volumes en biedt lijst, play/pauze, vorige/volgende en seek.

## Huishouden
- Boodschappenlijst wordt tussen telefoon en radio gespiegeld.
- Toevoegen, afvinken, verwijderen en afgeronde items wissen werken via de telefoondata.
- De supermarkt-herinneringsschakelaar wordt gesynchroniseerd.
- Supermarkt-geofence meldingen van de telefoon worden ook op de verbonden radio getoond.

## Parkeren
- Parkeeradressen en parkeertimer worden met de telefoon gesynchroniseerd.
- Toevoegen/verwijderen van adressen en instellen/wissen van eindtijd werken vanaf de radio; de telefoon beheert geofences en alarmen.

## Route
- Van/Naar invoer, inclusief huidige locatie als vertrekpunt.
- Daarna keuze uit Google Maps, Waze of browser/andere navigatie.

## Nieuws
- De autoradio gebruikt dezelfde vaste sites als de telefoon: Starnieuws, Waterkant, NU.nl, NOS en De Ware Tijd.

## Radio
- De Radio-tegel opent de The One-zenderlijst in plaats van de fabrieks-FM-app.

