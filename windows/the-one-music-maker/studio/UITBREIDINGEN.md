# The One Studio 1.1 — voortbouwen op jouw ZIP

De oorspronkelijke Studio-interface, synths, drums, MIDI, mixer, effecten, arrangement, microfoon en ZIP-export blijven behouden. De oorspronkelijke bronbestanden staan ook in `original/`.

Toegevoegd vanuit The One Music Maker:

- Standalone Windows-app zonder losse browser of Node-installatie.
- Redo naast undo: Ctrl+Shift+Z of Ctrl+Y.
- Compleet spoor kopiëren via de mixer; de kopie komt na de oorspronkelijke clips.
- Numerieke startpositie en brontrims in seconden; zonder loop stopt afspelen aan het projecteinde.
- Fade in en fade uit in seconden per audioclip, ook tijdens hervatten en export.
- Directe stereo-WAV-export (44,1 kHz, 16 bit) naast de bestaande WAV-in-ZIP-export. Pieken worden zo nodig verlaagd tegen clipping.
- Project opslaan inclusief audio, zodat het bestand op een ander apparaat werkt.
- Bestaande `.onemusic`-projecten importeren met startpositie, brontrims, volume, pan, mute/solo en fades.
- Waarschuwing bij afsluiten met wijzigingen of een actieve opname.

## Gebruik

Start de Windows-EXE of open `index.html` met de bijbehorende bestanden in Chrome/Edge. Selecteer een audioclip en open de editor om fades te zetten. Project → Opslaan maakt een JSON-bestand mét audio. Project → Openen accepteert oude Studio-JSON en Music Maker `.onemusic`. Met **WAV opslaan** maak je direct een mixbestand; **Mix exporteren** behoudt de oude ZIP-export.

Bewaar oude projecten en bronbestanden. Oudere Studio-projecten zonder ingebedde audio moeten eerst worden geopend in de browser waarin hun audio staat, en daar opnieuw worden opgeslagen met deze versie. Een ontbrekende audiobron geeft een melding; het bestaande project wordt dan niet vervangen.

Dit blijft een lokale eigenaar-preview. Family-registratie/toestemming, updateservice, Shared Media, plug-ins, stemseparatie, sampler, automatisering en audio time stretching zijn nog vervolgwerk. Ze worden hier niet als werkend voorgesteld. MIDI en microfoon moeten op echte hardware nog worden gecontroleerd. Het toevoegen van bestaande functies is geen volledige Cubase-implementatie.
