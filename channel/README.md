# The One Kids owner updates

Only authenticated AES-256-GCM ciphertext is published here. The decryption key stays in the private owner APK and private build storage. No plaintext APKs, account configurations or signing keys belong in this channel.

Install the private activation build once. Updates are checked at app launch and every 15 minutes while open; installation requires the parent settings and Android confirmation. Publish all chunks and the manifest together, with a higher versionCode and the same APK signing certificate.
