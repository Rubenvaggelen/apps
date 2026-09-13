# The One / The One Car — Groq + koers + parkeren

## AI en spraak
- OpenAI/Gemini zijn uit Vraag het, Recepten en de primaire WhatsApp-spraaktranscriptie gehaald.
- The One gebruikt Groq `llama-3.3-70b-versatile` voor tekst en `whisper-large-v3-turbo` voor volledige spraaktranscriptie.
- De Groq API-key wordt in de telefoon-app ingevoerd via Instellingen en lokaal versleuteld met Android Keystore opgeslagen.
- GitHub Actions heeft geen OpenAI- of Groq-secret nodig.
- The One Car stopt een opname automatisch na 5 seconden gedetecteerde stilte en stuurt de volledige WAV daarna één keer naar de telefoon/Groq.

## Koers
- EUR, SRD en USD zijn beschikbaar.
- Kies zelf Van en Naar en wissel ze met één knop om.

## Parkeren
- Een The One-parkeermelding vraagt bij openen of je de officiële Amsterdam App van Gemeente Amsterdam wilt openen voor Aanmelden parkeren.
- Als de Amsterdam App niet geïnstalleerd is, wordt de Play Store geopend.

## Bestaande The One Car-functies
- Muziek-tegel biedt Radio of USB.
- USB is geen losse hoofdtegel en ondersteunt USB 1/USB 2 met echte mappenstructuur.
- Bestaande WhatsApp-, huishouden-, nieuws-, route-, parkeren-, mail/kalender-, notificatie- en tegelbeheerfuncties blijven behouden.
