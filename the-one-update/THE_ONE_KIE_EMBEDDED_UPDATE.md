# The One – KIE API embedded update

- Groq removed from Vraag het and Recepten.
- Vraag het now uses KIE Gemini 3 Flash.
- Recepten first uses KIE Gemini 3 Flash with Google Search grounding and then a strict non-search fallback if needed.
- KIE API key is embedded in this personal build as explicitly requested by the owner.
- Settings now shows KIE AI as active; there is no API-key entry field to configure.
- Groq Whisper runtime dependency removed. Car WhatsApp voice reply transcription now uses the existing local Android SpeechRecognizer route.
- Existing menu, contact, radio, dashboard, recipes-format and GitHub Android SDK fixes remain in place.
