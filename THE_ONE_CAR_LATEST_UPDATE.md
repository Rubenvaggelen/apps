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

## Receptbronnen update
- Sranang Kukru toegevoegd als Surinaamse receptbron: https://sranangkukru.net/recepten/
- Leuke Recepten Italiaans toegevoegd: https://www.leukerecepten.nl/italiaanse-recepten/
- Leuke Recepten Hollands toegevoegd: https://www.leukerecepten.nl/hollandse-recepten/
- Receptenscherm toont bronknoppen die de bronpagina openen.
- Groq-receptprompt houdt rekening met deze voorkeursbronnen, zonder te claimen dat de websites live zijn uitgelezen.

## 2026-09-13 – Groq/recepten live bronzoeking
- `Vraag het` gebruikt geen uitgezet Llama-model meer; eerst `qwen/qwen3.6-27b`, met `openai/gpt-oss-20b` als fallback.
- Bij Groq 429 wordt `retry-after` gelezen en een bruikbare wachttijd getoond waar beschikbaar.
- Recepten gebruiken `groq/compound` live web search + website visit, beperkt tot Sranang Kukru en Leuke Recepten.
- Als meerdere bronnen iets vinden kiest The One automatisch de beste match en toont direct één recept; de gebruiker hoeft niets te kiezen.

## 2026-09-13 – Financiën in The One (telefoon)
- Nieuwe vaste tegel **Financiën** in de normale The One-app.
- Zelf startbudget en waarschuwing instellen.
- Google Wallet / Google Pay-transactiemeldingen met EUR-bedrag worden automatisch van het budget afgetrokken.
- Duidelijke uitgaande Tikkie-betalingen worden automatisch afgetrokken; ontvangen Tikkies en refunds worden genegeerd.
- Waarschuwing zodra het ingestelde resterende saldo wordt bereikt/onderschreden.
- Handmatig bedrag toevoegen en handmatige uitgave mogelijk.
- Transacties zijn lokaal zichtbaar en kunnen ongedaan worden gemaakt.
- Verwerking start pas vanaf het moment waarop een nieuw budget wordt ingesteld en dedupliceert bijgewerkte notificaties.
