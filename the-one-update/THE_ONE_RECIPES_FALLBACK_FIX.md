# The One – Recepten fallback fix

Datum: 15 september 2026

## Opgelost
- `Vraag het` werkt via de gewone Groq model-fallbacks.
- `Recepten` probeerde eerst Groq Compound web-search, maar gooide op de laatste web-searchfout direct een exception.
- Daardoor was de bestaande gewone receptfallback onbereikbaar wanneer beide Compound-routes faalden.

## Nieuw gedrag
1. Recepten probeert eerst live web-search op de ingestelde voorkeursbronnen.
2. Als de eerste Compound-route faalt, wordt de tweede geprobeerd.
3. Als beide web-searchroutes falen, gebruikt Recepten automatisch dezelfde gewone Groq modelketen als `Vraag het`.
4. Tijdelijke time-outs/netwerk/toolfouten vallen eveneens terug naar de gewone receptroute.
5. Een 401 (ongeldige API-key) wordt bewust direct getoond, omdat een modelwissel die fout niet kan oplossen.
