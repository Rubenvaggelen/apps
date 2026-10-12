<?php
declare(strict_types=1);
/**
 * SHA-256 hashes of independent 256-bit device credentials.
 * Raw credentials remain on each Windows user profile, never in GitHub or webroot.
 */
function one_screen_agent_hashes(): array {
    return [
        'ruben' => '445f8defa7ef2f2a8ad843524587aefa310a5a626f2e90d4573fffd5cbaf9de8',
        'surface' => '3b7a1f541109f580e39604759fe373e0bb5727bb9b033c707986eb3c7f988b98',
        'hub' => '3f50d843c03f10dbe5d3d9f6869c44f5f81c5bbffb280eb0adc25a7f107953e8'
    ];
}
