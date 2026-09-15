# The One — Route brandstofprijzen (2026-09-15)

- Route-tegel opent nu een eigen Route-hub met Routeplanner en Brandstofprijzen.
- Brandstofprijzen gebruikt de huidige GPS-locatie en zoekt exact binnen 5 km.
- Standaard: Euro 95 / E10; gebruiker kan ook Super 98 / E5 en Diesel kiezen.
- Stations worden op prijs (en daarna afstand) gesorteerd; goedkoopste staat bovenaan.
- Maximaal 10 resultaten zichtbaar.
- Tik op een station om direct met Waze naar de locatie te navigeren; browser fallback als Waze niet geïnstalleerd is.
- Prijsdata: ANWB `routing/points-of-interest/v3/all` fuel-station feed, zonder ingebouwde API-key.
- Waze wordt niet als prijsbron gescrapet omdat Waze geen officiële publieke brandstofprijs-API voor derde apps aanbiedt.
