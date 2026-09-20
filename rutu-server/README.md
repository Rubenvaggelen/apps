# Rutu BBQ online order API

Production endpoint: `https://rubenvanaggelen.com/rutu-api/index.php`.

The business credential is intentionally not stored in Git. The server reads
`~/rutu-data/business.key`; the Windows business app reads a local copy from
`~/Documents/Rutu BBQ/rutu-business.json`.

Customer status access uses a random per-order tracking token. Prices are
recalculated server-side from the fixed Rutu menu catalog.
