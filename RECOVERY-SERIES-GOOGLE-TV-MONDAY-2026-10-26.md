# The One Series Google TV — DEFINITIEVE RECOVERY (26 oktober 2026)

## Besluit eigenaar
Maandag 19 oktober 2026 is de door de eigenaar gekozen werkende versie van **The One Series voor Google TV**. De latere audio-experimenten (v1.2.5 en v1.2.6) zijn niet de definitieve basis.

## Definitieve APK
- Product: The One Series Google TV
- Android package: `com.theone.series.tv`
- App version: **1.2.3**
- Android versionCode: **129** (voor in-place updates boven v128)
- Audiocode: exact gelijk aan de herstelde maandag-v1.2.3-code
- Broncommit maandag: `2dc3b123e992010aadd79aef1b8d2e310a6f69ea`
- Geverifieerde MainActivity blob: `e4a67200da76ae1948e575d3c0608fa7b0e91f07` op beide branches
- Definitieve branch: `release/series-google-tv-monday-v129-20261026`
- Ondertekening: eigenaarssleutel, cert SHA-256 `dbf6ba5bfab9b67a7518ce43235b36a4bb4ad4f19f2bed838191e2267b25ea76`
- APK SHA-256: `1dcb2c41f01f17dbda6c6ff2ff80108457eb412529b33d2537351bf8cd559609`
- GitHub build: https://github.com/Rubenvaggelen/apps/actions/runs/40245984642
- Artifact: `The-One-Series-Google-TV-v1.2.3-Definitive-v129-owner`

## Publicatie
Op 26 oktober gepubliceerd via de bestaande **private Dev Hub Family-updateketen**, niet als publieke GitHub-release. GitHub Actions bevestigt:
`Private Family build published: series-google-tv 1.2.3`.

De private updater beschermt downloads met bestaande licenties/handtekeningen en biedt updates aan als de geïnstalleerde versionCode lager dan 129 is. De publicatie heeft alleen de record `series-google-tv` gewijzigd. Andere apps en licentiegegevens niet wijzigen.

De oorspronkelijke publicatiepoging faalde doordat `ci/devhub-family-build-update.php` ten onrechte precies 8 apprecords verlangde. In de definitieve release is die limiet vervangen door schemacontrole met unieke IDs zodat bestaande records behouden blijven. De tweede publicatie-run was groen.

## Herstelinstructies
Herbouw vanaf `release/series-google-tv-monday-v129-20261026`; gebruik de bestaande owner-keystore en veilige privébronnen, nooit APK's of private mediaconfiguraties openbaar maken. Bewaar versie 129 als goedgekeurd uitgangspunt. Voeg geen PCM/bronpriority-experimenten toe zonder apart getest kandidaattraject.

## Grenzen bewijs
Build, unit-test, package/signature en private publicatie zijn geverifieerd. De geluidstest van alle afzonderlijke afleveringen op het fysieke Google TV-toestel is niet door deze CI bewezen.
