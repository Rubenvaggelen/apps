# RUTU BBQ — Google Play assets and privacy TODO

## Assets prepared

A downloadable local publicatiepakket was generated from the **original logo
and product photos in RUTU Klant Android build v937**, without image-generation
changes to the logo.

- Google Play app icon: 512×512 RGBA PNG.
- NL and EN feature graphics: 1024×500 RGB PNG.
- Social preview: 1200×630 JPG.

Use the user's delivered ZIP to upload these assets. The graphics are not
stored in GitHub to keep the feature branch lightweight.

## Real app screenshots required

Google Play requires at least two **actual screenshots** of the running app.
Recommend four smartphone captures with no personal data:

1. App landing / logo.
2. Menu with actual BBQ item photography and prices.
3. Shopping basket and pickup/delivery choice (use test data).
4. Order tracking status from a test order (no real tracking tokens).

No promotional illustration or simulated UI should be submitted as a
screenshot of a live app.

## Privacy and Data Safety

Privacy declaration is **draft only**, not submitted/published.

Code-level processing to validate: customer name; order items; optional
address/postcode for delivery; Tikkie phone number when selected; order
status and payment status; per-install random device ID and device model;
notifications; optional Nearby transport. Confirm external processors,
retention/deletion workflow, encryption and all SDK data collection.

Google requires public HTTPS privacy policy accessible in-app and in Play
Console. Confirm developer/privacy contact and legal entity details before
publishing. Do not publish a template containing placeholders.

## Production safety

Current Google Play AAB v938 build succeeds as unsigned technical proof.
Next: dedicated upload key, Play Console account verification, privacy policy,
closed testing, real screenshots and signing migration for users of existing
APK before changing any QR target or live distribution.
