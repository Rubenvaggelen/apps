# The One Car K2401 - latest combined update

This project includes the previous K2401/WhatsApp fixes plus the latest requested changes:
- Mail & Kalender opens the same Gmail Org web app as the phone app.
- USB remains directly under Muziek and now has folder navigation plus playback controls.
- Voice replies stop after 5 seconds of silence, allow long recordings, preserve the full spoken audio, send the full recording to OpenAI Speech-to-Text; Android recognition is only a failure fallback.
- Notifications can be cleared separately.
- Local WhatsApp chat history is cleared at the start of a new radio connection/service session and at full headunit boot.
- Chats can be cleared manually from the WhatsApp screen.
- Settings has an Alles wissen action for chats, car notifications, temporary voice files and diagnostic history without deleting preferences/contacts/tiles.

## OpenAI / voice update
- "Vraag het" and Recepten now use the OpenAI Responses API (`gpt-5-mini`) instead of Gemini.
- Car voice replies now send the complete WAV once to OpenAI Speech-to-Text (`gpt-4o-mini-transcribe`); local Android speech recognition is only a failure fallback.
- Radio recording no longer waits for or advertises a fixed 90-second duration. Five seconds of detected silence ends the recording and starts processing immediately; the stop button remains available for manual stopping.
- The GitHub workflow now expects an `OPENAI_API_KEY` repository secret and injects it only during the build.
