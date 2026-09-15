# The One - Recepten direct recipe fix

Datum: 2026-09-15

## Probleem
De Recepten-fallback kon een gesprekachtig antwoord tonen zoals:
"Als je wilt, kan ik je wel een klassiek lasagne-recept geven..."
in plaats van het recept zelf.

## Oplossing
- Web-search antwoorden worden alleen geaccepteerd als ze echt INGREDIENTEN en BEREIDING bevatten.
- De gewone Groq-fallback heeft nu een eigen strikte receptprompt.
- De fallback heeft een ruimere outputlimiet (1400 tokens) zodat een volledig recept past.
- Antwoorden die alleen toestemming vragen of geen volledig recept bevatten worden automatisch afgewezen.
- Bij een onvolledig antwoord probeert The One automatisch een volgende AI-route/model.
- Vraag het blijft ongewijzigd.
