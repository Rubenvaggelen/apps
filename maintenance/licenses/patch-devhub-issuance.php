<?php
declare(strict_types=1);
/**
 * Optional guarded patch for the installed Dev Hub Studio license page.
 * Fixes a misleading *label*, not authorization or existing licenses.
 * Current The One Music Studio Portable 1.4.1 uses app=music and approval in Main.
 * Old Studio code issuance (app=studio) is supported for legacy clients only.
 *
 * Usage: php patch-devhub-issuance.php <staged-live-studio-licenses.php>
 */
if (PHP_SAPI !== 'cli' || $argc !== 2) {
    fwrite(STDERR, "Usage: php patch-devhub-issuance.php <staged-live-devhub-page>\n");
    exit(2);
}
$file = $argv[1];
$content = file_get_contents($file);
if (!is_string($content) || !str_contains($content, 'one_license_create_code(')) {
    throw new RuntimeException('Expected authenticated Dev Hub licensing page was not found');
}
$before = '<option value="studio">Music Studio (Windows)</option>';
$after = '<option value="studio">Studio Legacy (oude Windows-versie)</option>';
if (substr_count($content, $before) !== 1) {
    if (str_contains($content, $after)) {
        echo "PASS: Dev Hub label was already corrected\n";
        exit(0);
    }
    throw new RuntimeException('Live Dev Hub source differs; refusing blind update');
}
$content = str_replace($before, $after, $content);
$anchor = '<section><h2>Nieuwe code toewijzen</h2>';
if (substr_count($content, $anchor) !== 1) {
    throw new RuntimeException('Missing Dev Hub code issuance section');
}
$note = '<section><h2>Nieuwe code toewijzen</h2><p>De huidige The One Music Studio Portable (1.4.1) gebruikt de appidentiteit <strong>music</strong> en wordt goedgekeurd vanuit The One Main. De code hieronder is uitsluitend bedoeld voor oudere <strong>studio</strong>-installaties en de afzonderlijke Main-app.</p>';
$content = str_replace($anchor, $note, $content);
if (file_put_contents($file, $content) === false) throw new RuntimeException('Unable to update staged file');
echo "PASS: Dev Hub legacy code guidance corrected; existing live licenses unchanged\n";
