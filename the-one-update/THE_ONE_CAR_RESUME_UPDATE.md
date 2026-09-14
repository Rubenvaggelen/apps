# The One Car – muziek hervatten na herstart

- USB-muziek blijft via de foreground playback service op de achtergrond spelen.
- De huidige wachtrij, het nummer, de afspeelpositie en play/pauze-status worden lokaal opgeslagen.
- De status wordt tijdens het afspelen iedere 2,5 seconde bijgewerkt.
- Na een nieuwe start van The One Car wordt de laatste sessie automatisch hersteld.
- Was het nummer vóór het uitzetten aan het spelen, dan speelt het automatisch verder vanaf de laatst opgeslagen positie.
- Was het nummer gepauzeerd, dan blijft het na herstel gepauzeerd op dezelfde positie.
- Als de USB-stick tijdens vroege boot nog niet beschikbaar is, blijft de opgeslagen sessie bewaard en wordt bij openen van USB opnieuw geprobeerd te herstellen.
