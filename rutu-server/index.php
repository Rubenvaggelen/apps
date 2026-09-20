<?php
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Headers: Content-Type, X-Rutu-Business-Key');
header('Access-Control-Allow-Methods: GET, POST, OPTIONS');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(204);
    exit;
}

$home = dirname((string)($_SERVER['DOCUMENT_ROOT'] ?? __DIR__));
$dataDir = $home . '/rutu-data';
$stateFile = $dataDir . '/orders.json';
$keyFile = $dataDir . '/business.key';

if (!is_dir($dataDir)) {
    @mkdir($dataDir, 0700, true);
}
if (!file_exists($stateFile)) {
    @file_put_contents($stateFile, json_encode(['next_id' => 1046, 'orders' => []], JSON_PRETTY_PRINT), LOCK_EX);
    @chmod($stateFile, 0600);
}

function respond(int $status, array $body): never {
    http_response_code($status);
    echo json_encode($body, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    exit;
}

function body_json(): array {
    $raw = file_get_contents('php://input') ?: '';
    if ($raw === '') return [];
    $data = json_decode($raw, true);
    if (!is_array($data)) respond(400, ['ok' => false, 'error' => 'Ongeldige aanvraag.']);
    return $data;
}

function with_state(string $file, bool $write, callable $callback) {
    $fh = fopen($file, 'c+');
    if (!$fh) respond(500, ['ok' => false, 'error' => 'Orderopslag niet beschikbaar.']);
    try {
        if (!flock($fh, $write ? LOCK_EX : LOCK_SH)) {
            respond(500, ['ok' => false, 'error' => 'Orderopslag is tijdelijk bezet.']);
        }
        rewind($fh);
        $raw = stream_get_contents($fh) ?: '';
        $state = json_decode($raw, true);
        if (!is_array($state)) $state = ['next_id' => 1046, 'orders' => []];
        if (!isset($state['next_id'])) $state['next_id'] = 1046;
        if (!isset($state['orders']) || !is_array($state['orders'])) $state['orders'] = [];
        if (!isset($state['announcement']) || !is_array($state['announcement'])) $state['announcement'] = ['title'=>'','message'=>'','from'=>'','until'=>'','active'=>false,'updated'=>''];

        $result = $callback($state);

        if ($write) {
            rewind($fh);
            ftruncate($fh, 0);
            fwrite($fh, json_encode($state, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE));
            fflush($fh);
        }
        flock($fh, LOCK_UN);
        fclose($fh);
        return $result;
    } catch (Throwable $e) {
        @flock($fh, LOCK_UN);
        @fclose($fh);
        throw $e;
    }
}

function business_authorized(string $keyFile): bool {
    if (!is_file($keyFile)) return false;
    $expected = trim((string)file_get_contents($keyFile));
    $provided = trim((string)($_SERVER['HTTP_X_RUTU_BUSINESS_KEY'] ?? ''));
    return $expected !== '' && $provided !== '' && hash_equals($expected, $provided);
}

function clean_order_for_customer(array $order, bool $includeTracking = false): array {
    $out = [
        'id' => (int)$order['id'],
        'items' => $order['items'],
        'total' => (float)$order['total'],
        'status' => (string)$order['status'],
        'customer' => (string)$order['customer'],
        'created' => (string)$order['created'],
        'created_display' => (string)$order['created_display']
    ];
    if ($includeTracking) $out['tracking'] = (string)$order['tracking'];
    return $out;
}

$catalog = [
    'Teriyaki Chicken' => 12.50,
    'The Emperor Burger' => 14.95,
    'Nasi Special' => 11.50,
    'Roti Kip' => 13.50,
    'Friet groot' => 4.25,
    'Ube Cheesecake' => 6.95,
    'Cola' => 2.75,
    'Iced Tea' => 2.75
];

$action = (string)($_GET['action'] ?? 'health');

if ($action === 'health') {
    respond(200, ['ok' => true, 'service' => 'Rutu BBQ Online Orders', 'version' => 1]);
}

if ($action === 'create') {
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') respond(405, ['ok' => false, 'error' => 'POST vereist.']);
    $body = body_json();
    $incoming = $body['items'] ?? null;
    if (!is_array($incoming) || count($incoming) < 1 || count($incoming) > 40) {
        respond(400, ['ok' => false, 'error' => 'Je winkelmand is leeg of te groot.']);
    }

    $items = [];
    $total = 0.0;
    foreach ($incoming as $name => $qty) {
        $name = trim((string)$name);
        $qty = (int)$qty;
        if (!array_key_exists($name, $catalog) || $qty < 1 || $qty > 25) {
            respond(400, ['ok' => false, 'error' => 'Een product of aantal is ongeldig.']);
        }
        $items[$name] = $qty;
        $total += $catalog[$name] * $qty;
    }
    $total = round($total, 2);
    $customer = trim((string)($body['customer'] ?? 'Online klant'));
    if ($customer === '') $customer = 'Online klant';
    $customer = mb_substr($customer, 0, 80);

    $order = with_state($stateFile, true, function (&$state) use ($items, $total, $customer) {
        $id = max(1046, (int)$state['next_id']);
        $state['next_id'] = $id + 1;
        $createdTs = time();
        $order = [
            'id' => $id,
            'items' => $items,
            'total' => $total,
            'status' => 'Nieuw',
            'customer' => $customer,
            'created' => gmdate('c', $createdTs),
            'created_display' => date('H:i', $createdTs),
            'tracking' => bin2hex(random_bytes(18))
        ];
        array_unshift($state['orders'], $order);
        // Bewaar de volledige bestelgeschiedenis voor de bedrijfsomgeving.
        return $order;
    });

    respond(201, ['ok' => true, 'order' => clean_order_for_customer($order, true)]);
}

if ($action === 'status') {
    $id = (int)($_GET['id'] ?? 0);
    $tracking = trim((string)($_GET['tracking'] ?? ''));
    if ($id <= 0 || $tracking === '') respond(400, ['ok' => false, 'error' => 'Ordergegevens ontbreken.']);

    $order = with_state($stateFile, false, function ($state) use ($id, $tracking) {
        foreach ($state['orders'] as $order) {
            if ((int)$order['id'] === $id && hash_equals((string)$order['tracking'], $tracking)) return $order;
        }
        return null;
    });
    if (!$order) respond(404, ['ok' => false, 'error' => 'Bestelling niet gevonden.']);
    respond(200, ['ok' => true, 'order' => clean_order_for_customer($order, false)]);
}

if ($action === 'announcement') {
    $announcement = with_state($stateFile, false, function ($state) {
        $a = $state['announcement'] ?? ['title'=>'','message'=>'','from'=>'','until'=>'','active'=>false,'updated'=>''];
        if (!is_array($a)) $a = ['title'=>'','message'=>'','from'=>'','until'=>'','active'=>false,'updated'=>''];

        $active = (bool)($a['active'] ?? false);
        $today = date('Y-m-d');
        $from = trim((string)($a['from'] ?? ''));
        $until = trim((string)($a['until'] ?? ''));
        if ($from !== '' && $today < $from) $active = false;
        if ($until !== '' && $today > $until) $active = false;

        return [
            'title' => trim((string)($a['title'] ?? '')),
            'message' => trim((string)($a['message'] ?? '')),
            'from' => $from,
            'until' => $until,
            'active' => $active,
            'updated' => (string)($a['updated'] ?? '')
        ];
    });
    respond(200, ['ok' => true, 'announcement' => $announcement]);
}

if ($action === 'business_announcement') {
    if (!business_authorized($keyFile)) respond(401, ['ok' => false, 'error' => 'Niet geautoriseerd.']);

    if ($_SERVER['REQUEST_METHOD'] === 'GET') {
        $announcement = with_state($stateFile, false, fn($state) => $state['announcement'] ?? []);
        respond(200, ['ok' => true, 'announcement' => $announcement]);
    }

    if ($_SERVER['REQUEST_METHOD'] !== 'POST') respond(405, ['ok' => false, 'error' => 'GET of POST vereist.']);
    $body = body_json();
    $title = trim((string)($body['title'] ?? ''));
    $message = trim((string)($body['message'] ?? ''));
    $from = trim((string)($body['from'] ?? ''));
    $until = trim((string)($body['until'] ?? ''));
    $active = (bool)($body['active'] ?? false);

    if (mb_strlen($title) > 80 || mb_strlen($message) > 600) {
        respond(400, ['ok' => false, 'error' => 'Mededeling is te lang.']);
    }
    if ($from !== '' && !preg_match('/^\d{4}-\d{2}-\d{2}$/', $from)) {
        respond(400, ['ok' => false, 'error' => 'Ongeldige vanaf-datum.']);
    }
    if ($until !== '' && !preg_match('/^\d{4}-\d{2}-\d{2}$/', $until)) {
        respond(400, ['ok' => false, 'error' => 'Ongeldige tot-datum.']);
    }
    if ($from !== '' && $until !== '' && $until < $from) {
        respond(400, ['ok' => false, 'error' => 'Tot-datum ligt vóór vanaf-datum.']);
    }

    $announcement = with_state($stateFile, true, function (&$state) use ($title, $message, $from, $until, $active) {
        $state['announcement'] = [
            'title' => $title,
            'message' => $message,
            'from' => $from,
            'until' => $until,
            'active' => $active && ($title !== '' || $message !== ''),
            'updated' => gmdate('c')
        ];
        return $state['announcement'];
    });

    respond(200, ['ok' => true, 'announcement' => $announcement]);
}

if ($action === 'business_orders') {
    if (!business_authorized($keyFile)) respond(401, ['ok' => false, 'error' => 'Niet geautoriseerd.']);
    $orders = with_state($stateFile, false, fn($state) => array_map(
        fn($order) => clean_order_for_customer($order, false),
        $state['orders']
    ));
    respond(200, ['ok' => true, 'orders' => $orders]);
}

if ($action === 'business_status') {
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') respond(405, ['ok' => false, 'error' => 'POST vereist.']);
    if (!business_authorized($keyFile)) respond(401, ['ok' => false, 'error' => 'Niet geautoriseerd.']);
    $body = body_json();
    $id = (int)($body['id'] ?? 0);
    $status = trim((string)($body['status'] ?? ''));
    $allowed = ['Nieuw', 'In bereiding', 'Klaar', 'Afgerond', 'Geweigerd'];
    if ($id <= 0 || !in_array($status, $allowed, true)) {
        respond(400, ['ok' => false, 'error' => 'Ongeldige statuswijziging.']);
    }

    $updated = with_state($stateFile, true, function (&$state) use ($id, $status) {
        foreach ($state['orders'] as &$order) {
            if ((int)$order['id'] === $id) {
                $order['status'] = $status;
                return $order;
            }
        }
        return null;
    });
    if (!$updated) respond(404, ['ok' => false, 'error' => 'Bestelling niet gevonden.']);
    respond(200, ['ok' => true, 'order' => clean_order_for_customer($updated, false)]);
}

respond(404, ['ok' => false, 'error' => 'Onbekende actie.']);
