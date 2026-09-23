# Rutu BBQ online order API

Production endpoint: `https://rubenvanaggelen.com/rutu-api/index.php`.

The business credential is intentionally not stored in Git. The server reads
`~/rutu-data/business.key`; the Windows business app reads a local copy from
`~/Documents/Rutu BBQ/rutu-business.json`.

Customer status access uses a random per-order tracking token. Prices are
recalculated server-side from the fixed Rutu menu catalog.

## Automatic Tikkie integration

Rutu creates a separate payment request for every new delivery order paid by Tikkie,
including delivery fees. The customer can open the link in the Android app, and
both business apps can copy it and see the status. A payment is only marked as
paid after the server checks the provider's reported paid amount. Cash is unchanged.

Activation requires a Tikkie Business contract and production API access, plus
a production App Token with payment-request permission. The owner should configure
these credentials in a private JSON configuration file on the hosting server:
`$HOME/rutu-data/tikkie.json`, outside the web root, with mode, API key and
App Token. Set the file permissions to owner-read/write only. Never commit
credentials into Git or put them into Android or Windows builds.

For sandbox tests, the configuration mode can be sandbox with matching sandbox
credentials. A non-secret readiness flag appears in the API health response:
`?action=health` -> `tikkie_configured`.

The automatic payment link is shown in the customer app. The phone number
recorded with the order is visible only to the business; automatic SMS or
WhatsApp sending requires an additional messaging-provider integration.

The API is intentionally disabled until credentials are present. Network failures
are flagged for review rather than blindly generating another payment request,
which could charge the customer twice.

Provider documentation: https://developer.abnamro.com/api-products/tikkie/overview
