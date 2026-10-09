<?php
declare(strict_types=1);
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');
require_once __DIR__ . '/license-core.php';

function license_response(int $status, array $data): never {
    http_response_code($status);
    echo json_encode($data, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_THROW_ON_ERROR);
    exit;
}
if ($_SERVER['REQUEST_METHOD'] !== 'POST') license_response(405, ['ok' => false, 'error' => 'POST required']);
if ((int)($_SERVER['CONTENT_LENGTH'] ?? 0) > 16384) license_response(413, ['ok' => false, 'error' => 'Request too large']);
$body = json_decode((string)(file_get_contents('php://input') ?: ''), true);
if (!is_array($body)) license_response(400, ['ok' => false, 'error' => 'Invalid JSON']);
$action = (string)($_GET['action'] ?? '');

try {
    $result = one_license_locked(static function(array &$state, callable $dirty) use ($action, $body): array {
        if ($action === 'policy') {
            return [
                'main' => ['required' => (bool)$state['main_enforced']],
                'studio' => ['required' => (bool)$state['studio_enforced']]
            ];
        }
        $app = strtolower(trim((string)($body['app'] ?? '')));
        if (!in_array($app, ['main', 'studio'], true)) throw new InvalidArgumentException('Unknown app');
        $device = one_license_device((string)($body['device_id'] ?? ''));
        if ($action === 'status') {
            $token = (string)($body['token'] ?? '');
            if ($app === 'main') {
                $value = one_license_main_status($state, $device, $token);
                if (isset($value['credential'])) $dirty();
                return $value;
            }
            if (!$state['studio_enforced']) return ['enabled' => false, 'allowed' => false, 'mode' => 'not_configured'];
            $grant = ($token !== '') ? one_license_find_grant($state, $app, $device, $token) : null;
            return ['enabled' => true, 'allowed' => $grant !== null, 'mode' => $grant !== null ? 'active' : 'activation_required'];
        }
        if ($action === 'redeem') {
            if (($app === 'main' && !$state['main_enforced']) || ($app === 'studio' && !$state['studio_enforced'])) {
                return ['allowed' => false, 'error' => 'licensing_not_ready'];
            }
            $person = one_license_person((string)($body['person'] ?? ''));
            $code = (string)($body['code'] ?? '');
            $value = one_license_redeem($state, $app, $device, $person, $code);
            if (($value['allowed'] ?? false)) $dirty();
            return $value;
        }
        if ($action === 'request') {
            $person = one_license_person((string)($body['person'] ?? ''));
            $result = one_license_request($state, $app, $device, $person);
            $dirty();
            return $result;
        }
        throw new InvalidArgumentException('Unknown action');
    });
    license_response(200, ['ok' => true] + $result);
} catch (InvalidArgumentException $error) {
    license_response(400, ['ok' => false, 'error' => $error->getMessage()]);
} catch (Throwable $error) {
    error_log('The One License API failure: ' . $error->getMessage());
    license_response(503, ['ok' => false, 'error' => 'Licensing service temporarily unavailable']);
}
