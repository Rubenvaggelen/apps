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

function default_ordering_schedule(): array {
    $days = [];
    for ($day = 1; $day <= 7; $day++) {
        $days[(string)$day] = ['open' => $day === 3, 'from' => '10:00', 'until' => '18:00'];
    }
    return ['timezone' => 'Europe/Amsterdam', 'days' => $days, 'updated' => ''];
}

function normalize_ordering_schedule($value): array {
    $default = default_ordering_schedule();
    if (!is_array($value)) return $default;
    $rawDays = is_array($value['days'] ?? null) ? $value['days'] : [];
    foreach ($default['days'] as $key => $fallback) {
        $day = is_array($rawDays[$key] ?? null) ? $rawDays[$key] : [];
        $open = (bool)($day['open'] ?? $fallback['open']);
        $from = trim((string)($day['from'] ?? $fallback['from']));
        $until = trim((string)($day['until'] ?? $fallback['until']));
        if (!preg_match('/^(?:[01]\d|2[0-3]):[0-5]\d$/', $from)) $from = $fallback['from'];
        if (!preg_match('/^(?:[01]\d|2[0-3]):[0-5]\d$/', $until)) $until = $fallback['until'];
        if ($until <= $from) { $from = $fallback['from']; $until = $fallback['until']; }
        $default['days'][$key] = ['open' => $open, 'from' => $from, 'until' => $until];
    }
    $default['updated'] = (string)($value['updated'] ?? '');
    return $default;
}

function is_test_customer(string $customer): bool {
    $name = mb_strtolower(trim($customer), 'UTF-8');
    return preg_match('/^(ruben|leon)(?:\s|$)/u', $name) === 1;
}

function ordering_schedule_status(array $schedule): array {
    $schedule = normalize_ordering_schedule($schedule);
    $tz = new DateTimeZone('Europe/Amsterdam');
    $now = new DateTimeImmutable('now', $tz);
    $dayKey = $now->format('N');
    $time = $now->format('H:i');
    $day = $schedule['days'][$dayKey] ?? ['open'=>false,'from'=>'10:00','until'=>'18:00'];
    $allowed = (bool)$day['open'] && $time >= $day['from'] && $time < $day['until'];

    $names = ['1'=>'maandag','2'=>'dinsdag','3'=>'woensdag','4'=>'donderdag','5'=>'vrijdag','6'=>'zaterdag','7'=>'zondag'];
    $windows = [];
    foreach ($schedule['days'] as $key => $entry) {
        if (!empty($entry['open'])) $windows[] = $names[$key] . ' ' . $entry['from'] . '-' . $entry['until'] . ' uur';
    }
    $message = count($windows) === 1 && !empty($schedule['days']['3']['open'])
        && $schedule['days']['3']['from'] === '10:00' && $schedule['days']['3']['until'] === '18:00'
        ? 'U kunt iedere woensdag van 10:00 tot 18:00 uur bestellen. Wij helpen u graag.'
        : (count($windows) ? 'Bestellen kan op: ' . implode(', ', $windows) . '.' : 'Bestellen is momenteel gesloten.');

    return ['allowed'=>$allowed, 'message'=>$message, 'day'=>(int)$dayKey, 'time'=>$time, 'schedule'=>$schedule];
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
        $state['ordering_schedule'] = normalize_ordering_schedule($state['ordering_schedule'] ?? null);

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


// Tikkie credentials are loaded only from a private file OUTSIDE public_html.
// The Tikkie API is disabled until the Rutu owner adds production credentials.
function tikkie_config(string $dataDir): ?array {
    $file = $dataDir . '/tikkie.json';
    if (!is_file($file)) return null;
    $config = json_decode((string)file_get_contents($file), true);
    if (!is_array($config)) return null;
    $mode = (string)($config['mode'] ?? '');
    if (!in_array($mode, ['production', 'sandbox'], true)) return null;
    if (empty($config['api_key']) || empty($config['app_token'])) return null;
    return $config;
}

function tikkie_request(array $config, string $method, string $path, ?array $payload = null): ?array {
    if (!function_exists('curl_init')) return null;
    $base = $config['mode'] === 'sandbox' ? 'https://api-sandbox.abnamro.com/v2/tikkie/' : 'https://api.abnamro.com/v2/tikkie/';
    $url = $base . $path;
    $curl = curl_init($url);
    if ($curl === false) return null;
    $headers = [
        'Accept: application/json',
        'Content-Type: application/json',
        'API-Key: ' . $config['api_key'],
        'X-App-Token: ' . $config['app_token']
    ];
    $options = [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_HTTPHEADER => $headers,
        CURLOPT_TIMEOUT => 12,
        CURLOPT_CONNECTTIMEOUT => 4,
        CURLOPT_SSL_VERIFYPEER => true,
        CURLOPT_SSL_VERIFYHOST => 2,
        CURLOPT_FOLLOWLOCATION => false,
        CURLOPT_USERAGENT => 'RutuBBQ/1.0'
    ];
    if ($method === 'POST') {
        $options[CURLOPT_POST] = true;
        $options[CURLOPT_POSTFIELDS] = json_encode($payload, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    }
    curl_setopt_array($curl, $options);
    $raw = curl_exec($curl);
    $code = (int)curl_getinfo($curl, CURLINFO_HTTP_CODE);
    curl_close($curl);
    if (!is_string($raw) || $code < 200 || $code >= 300) return null;
    $decoded = json_decode($raw, true);
    return is_array($decoded) ? $decoded : null;
}

function valid_tikkie_link(string $link): bool {
    $url = parse_url($link);
    return is_array($url) && ($url['scheme'] ?? '') === 'https'
        && in_array(strtolower((string)($url['host'] ?? '')), ['tikkie.me', 'www.tikkie.me'], true);
}

// Never mark a payment paid based on a customer action, redirect or caller-supplied status.
// A server-side read from ABN AMRO is the only source for "Betaald".
function tikkie_refresh_one(string $stateFile, ?array $config, ?int $specificId = null): void {
    if ($config === null) return;
    $now = time();
    // Read-only preflight avoids rewriting orders.json on every business poll.
    $due = with_state($stateFile, false, function ($state) use ($specificId, $now) {
        foreach ($state['orders'] as $order) {
            if ($specificId !== null && (int)$order['id'] !== $specificId) continue;
            if (($order['payment_method'] ?? '') !== 'Tikkie' || empty($order['payment_request_token'])) continue;
            if (in_array((string)($order['payment_status'] ?? ''), ['Betaald','Geannuleerd'], true)) continue;
            if (in_array((string)($order['status'] ?? ''), ['Uitverkocht','Geweigerd','Geannuleerd'], true)) continue;
            if ($now - (int)($order['payment_checked_at'] ?? 0) < 60) continue;
            return true;
        }
        return false;
    });
    if (!$due) return;
    $claimed = with_state($stateFile, true, function (&$state) use ($specificId, $now) {
        foreach ($state['orders'] as &$order) {
            if ($specificId !== null && (int)$order['id'] !== $specificId) continue;
            if (($order['payment_method'] ?? '') !== 'Tikkie') continue;
            if (empty($order['payment_request_token'])) continue;
            if (in_array((string)($order['payment_status'] ?? ''), ['Betaald', 'Geannuleerd'], true)) continue;
            if (in_array((string)($order['status'] ?? ''), ['Uitverkocht', 'Geweigerd', 'Geannuleerd'], true)) continue;
            if ($now - (int)($order['payment_checked_at'] ?? 0) < 60) continue;
            $order['payment_checked_at'] = $now; // claim before network IO; avoid stampedes
            return [
                'id' => (int)$order['id'],
                'token' => (string)$order['payment_request_token'],
                'cents' => (int)($order['payment_amount_cents'] ?? 0)
            ];
        }
        return null;
    });
    if (!is_array($claimed)) return;
    $details = tikkie_request($config, 'GET', 'paymentrequests/' . rawurlencode($claimed['token']));
    if ($details === null || ($details['paymentRequestToken'] ?? '') !== $claimed['token']) return;
    $paidCents = max(0, (int)($details['totalAmountPaidInCents'] ?? 0));
    $count = max(0, (int)($details['numberOfPayments'] ?? 0));
    $paid = $claimed['cents'] > 0 && $paidCents >= $claimed['cents'] && $count >= 1;
    $expired = in_array((string)($details['status'] ?? ''), ['EXPIRED','CLOSED'], true) && !$paid;
    with_state($stateFile, true, function (&$state) use ($claimed, $paid, $expired) {
        foreach ($state['orders'] as &$order) {
            if ((int)$order['id'] !== $claimed['id'] || ($order['payment_request_token'] ?? '') !== $claimed['token']) continue;
            if ($paid) $order['payment_status'] = 'Betaald';
            elseif ($expired) $order['payment_status'] = 'Verlopen';
            $order['payment_verified_at'] = gmdate('c');
            break;
        }
        return null;
    });
}

function tikkie_create_one(string $stateFile, ?array $config, array $order): void {
    if ($config === null || ($order['payment_method'] ?? '') !== 'Tikkie') return;
    $cents = (int)round((float)$order['total'] * 100);
    $id = (int)$order['id'];
    $reference = 'RUTU-' . $id;
    $created = tikkie_request($config, 'POST', 'paymentrequests', [
        'description' => 'Rutu BBQ bestelling #' . $id,
        'amountInCents' => $cents,
        'referenceId' => $reference
    ]);
    $valid = $created !== null
        && is_string($created['paymentRequestToken'] ?? null)
        && preg_match('/^[a-zA-Z0-9_-]{8,128}$/', $created['paymentRequestToken'])
        && is_string($created['url'] ?? null) && valid_tikkie_link($created['url'])
        && (int)($created['amountInCents'] ?? -1) === $cents
        && (string)($created['referenceId'] ?? '') === $reference;
    // A timed-out payment request may still have been created at the provider.
    // Never silently retry it: that could generate duplicate payment requests.
    with_state($stateFile, true, function (&$state) use ($id, $valid, $created, $cents) {
        foreach ($state['orders'] as &$current) {
            if ((int)$current['id'] !== $id) continue;
            if ($valid) {
                $current['payment_request_token'] = $created['paymentRequestToken'];
                $current['payment_url'] = $created['url'];
                $current['payment_amount_cents'] = $cents;
                $current['payment_status'] = 'Openstaand';
                $current['payment_checked_at'] = time();
            } else {
                $current['payment_status'] = 'Controle nodig';
            }
            break;
        }
        return null;
    });
}

function ordering_blocked_today(array $announcement): bool {
    if (!(bool)($announcement['active'] ?? false)) return false;
    $from = trim((string)($announcement['from'] ?? ''));
    $until = trim((string)($announcement['until'] ?? ''));
    if ($from === '' && $until === '') return false;

    $today = (new DateTimeImmutable('now', new DateTimeZone('Europe/Amsterdam')))->format('Y-m-d');
    if ($from !== '' && $until !== '') return $today >= $from && $today <= $until;
    if ($from !== '') return $today === $from;
    return $today === $until;
}

function clean_order_for_customer(array $order, bool $includeTracking = false): array {
    $out = [
        'id' => (int)$order['id'],
        'items' => $order['items'],
        'total' => (float)$order['total'],
        'status' => (string)$order['status'],
        'customer' => (string)$order['customer'],
        'created' => (string)$order['created'],
        'created_display' => (string)$order['created_display'],
        'delivery' => (bool)($order['delivery'] ?? false),
        'address' => (string)($order['address'] ?? ''),
        'postcode' => (string)($order['postcode'] ?? ''),
        'delivery_fee' => (float)($order['delivery_fee'] ?? 0),
        'payment_method' => (string)($order['payment_method'] ?? ''),
        'payment_status' => (string)($order['payment_status'] ?? ''),
        'payment_url' => (string)($order['payment_url'] ?? '')
    ];
    if ($includeTracking) $out['tracking'] = (string)$order['tracking'];
    return $out;
}

function clean_order_for_business(array $order): array {
    $out = clean_order_for_customer($order, false);
    $out['payment_phone'] = (string)($order['payment_phone'] ?? '');
    $out['payment_reference'] = (string)($order['payment_request_token'] ?? '');
    $out['history_hidden'] = (bool)($order['history_hidden'] ?? false);
    $out['history_cleared'] = (bool)($order['history_cleared'] ?? false);
    return $out;
}

$catalog = [
    'BBQ Regular' => 10.00,
    'BBQ Extra' => 15.00,
    'BBQ Gezin' => 25.00,
    'Extra bout' => 3.00,
    'Extra salade' => 2.50,
    'Portie saté' => 5.00
];

$action = (string)($_GET['action'] ?? 'health');

if ($action === 'health') {
    respond(200, ['ok' => true, 'service' => 'Rutu BBQ Online Orders', 'version' => 2, 'tikkie_configured' => tikkie_config($dataDir) !== null]);
}

if ($action === 'create') {
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') respond(405, ['ok' => false, 'error' => 'POST vereist.']);

    $body = body_json();
    $customer = trim((string)($body['customer'] ?? 'Online klant'));
    if ($customer === '') $customer = 'Online klant';
    $customer = mb_substr($customer, 0, 80);
    $testCustomer = is_test_customer($customer);
    $testRequested = filter_var($body['test_order'] ?? false, FILTER_VALIDATE_BOOLEAN);
    $testBypass = $testCustomer && $testRequested;

    $availability = with_state($stateFile, false, function ($state) {
        $announcement = is_array($state['announcement'] ?? null) ? $state['announcement'] : [];
        $scheduleStatus = ordering_schedule_status($state['ordering_schedule'] ?? []);
        return [
            'manual_blocked' => ordering_blocked_today($announcement),
            'schedule_allowed' => (bool)$scheduleStatus['allowed'],
            'schedule_message' => (string)$scheduleStatus['message']
        ];
    });
    if (!$testBypass && ((bool)$availability['manual_blocked'] || !(bool)$availability['schedule_allowed'])) {
        $message = (bool)$availability['manual_blocked']
            ? 'Vandaag is Rutu BBQ gesloten voor bestellingen.'
            : (string)$availability['schedule_message'];
        respond(409, ['ok' => false, 'error' => $message, 'ordering_closed' => true]);
    }
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
    $subtotal = round($total, 2);
    $delivery = filter_var($body['delivery'] ?? false, FILTER_VALIDATE_BOOLEAN);
    $address = '';
    $postcode = '';
    $deliveryFee = 0.0;
    $paymentMethod = '';
    $paymentPhone = '';
    if ($delivery) {
        $address = preg_replace('/\\s+/', ' ', trim((string)($body['address'] ?? '')));
        $postcode = strtoupper(preg_replace('/\\s+/', '', trim((string)($body['postcode'] ?? ''))));
        if (mb_strlen($address) < 3) respond(400, ['ok' => false, 'error' => 'Vul je straat en huisnummer in.']);
        if (!preg_match('/^\\d{4}[A-Z]{2}$/', $postcode)) respond(400, ['ok' => false, 'error' => 'Vul een volledige postcode in, bijvoorbeeld 1106 AB.']);
        $address = mb_substr($address, 0, 120);
        $deliveryFee = substr($postcode, 0, 4) === '1106' ? 2.50 : 5.00;
        $method = strtolower(trim((string)($body['payment_method'] ?? 'Cash')));
        if (!in_array($method, ['cash', 'tikkie'], true)) respond(400, ['ok' => false, 'error' => 'Kies Cash of Tikkie.']);
        $paymentMethod = $method === 'tikkie' ? 'Tikkie' : 'Cash';
        if ($paymentMethod === 'Tikkie') {
            $paymentPhone = preg_replace('/[\\s()\\-]/', '', trim((string)($body['payment_phone'] ?? '')));
            if (!preg_match('/^(?:06[0-9]{8}|\\+316[0-9]{8}|00316[0-9]{8})$/', $paymentPhone)) {
                respond(400, ['ok' => false, 'error' => 'Vul een geldig Nederlands mobiel nummer in voor Tikkie.']);
            }
        }
    }
    $total = round($subtotal + $deliveryFee, 2);

    $tikkie = tikkie_config($dataDir);
    $order = with_state($stateFile, true, function (&$state) use ($items, $total, $customer, $delivery, $address, $postcode, $deliveryFee, $paymentMethod, $paymentPhone, $tikkie) {
        $id = max(1046, (int)$state['next_id']);
        $state['next_id'] = $id + 1;
        $createdTs = time();
        $order = [
            'id' => $id,
            'items' => $items,
            'total' => $total,
            'status' => 'Nieuw',
            'customer' => $customer,
            'delivery' => $delivery,
            'address' => $address,
            'postcode' => $postcode,
            'delivery_fee' => $deliveryFee,
            'payment_method' => $paymentMethod,
            'payment_phone' => $paymentPhone,
            'payment_status' => $paymentMethod === 'Tikkie' ? ($tikkie === null ? 'Niet gekoppeld' : 'Aanvragen') : '',
            'payment_url' => '',
            'payment_request_token' => '',
            'created' => gmdate('c', $createdTs),
            'created_display' => date('H:i', $createdTs),
            'tracking' => bin2hex(random_bytes(18)),
            'history_hidden' => false,
            'history_cleared' => false
        ];
        array_unshift($state['orders'], $order);
        // Bewaar de volledige bestelgeschiedenis voor de bedrijfsomgeving.
        return $order;
    });

    if ($paymentMethod === 'Tikkie' && $tikkie !== null) {
        tikkie_create_one($stateFile, $tikkie, $order);
        $order = with_state($stateFile, false, function ($state) use ($order) {
            foreach ($state['orders'] as $updated) if ((int)$updated['id'] === (int)$order['id']) return $updated;
            return $order;
        });
    }
    respond(201, ['ok' => true, 'order' => clean_order_for_customer($order, true)]);
}

if ($action === 'add_items') {
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') respond(405, ['ok' => false, 'error' => 'POST vereist.']);
    $body = body_json();
    $id = (int)($body['id'] ?? 0);
    $tracking = trim((string)($body['tracking'] ?? ''));
    $incoming = $body['items'] ?? null;
    if ($id <= 0 || $tracking === '') respond(400, ['ok' => false, 'error' => 'Bestelgegevens ontbreken.']);
    if (!is_array($incoming) || count($incoming) < 1 || count($incoming) > 40) respond(400, ['ok' => false, 'error' => 'Je winkelmand is leeg of te groot.']);

    $items = [];
    $extraTotal = 0.0;
    foreach ($incoming as $name => $qty) {
        $name = trim((string)$name);
        $qty = (int)$qty;
        if (!array_key_exists($name, $catalog) || $qty < 1 || $qty > 25) respond(400, ['ok' => false, 'error' => 'Een product of aantal is ongeldig.']);
        $items[$name] = $qty;
        $extraTotal += $catalog[$name] * $qty;
    }
    $extraTotal = round($extraTotal, 2);

    $updated = with_state($stateFile, true, function (&$state) use ($id, $tracking, $items, $extraTotal) {
        foreach ($state['orders'] as &$order) {
            if ((int)$order['id'] !== $id) continue;
            if (!hash_equals((string)($order['tracking'] ?? ''), $tracking)) return false;
            if (in_array((string)($order['status'] ?? ''), ['Afgerond', 'Geannuleerd', 'Uitverkocht', 'Geweigerd'], true)) return 'closed';
            if (($order['payment_method'] ?? '') === 'Tikkie' && !empty($order['payment_request_token'])) return 'tikkie_locked';
            foreach ($items as $name => $qty) {
                $newQty = (int)($order['items'][$name] ?? 0) + $qty;
                if ($newQty > 25) return 'too_many';
                $order['items'][$name] = $newQty;
            }
            $order['total'] = round((float)($order['total'] ?? 0) + $extraTotal, 2);
            $order['updated'] = gmdate('c');
            return $order;
        }
        return null;
    });
    if ($updated === false) respond(403, ['ok' => false, 'error' => 'Bestelling kan niet worden geverifieerd.']);
    if ($updated === 'closed') respond(409, ['ok' => false, 'error' => 'Deze bestelling staat niet meer open.']);
    if ($updated === 'too_many') respond(409, ['ok' => false, 'error' => 'Het totale aantal van een product is te hoog.']);
    if ($updated === 'tikkie_locked') respond(409, ['ok' => false, 'error' => 'Voor deze bestelling is al een Tikkie aangemaakt. Plaats voor extra producten een nieuwe bestelling.']);
    if (!$updated) respond(404, ['ok' => false, 'error' => 'Bestelling niet gevonden.']);
    respond(200, ['ok' => true, 'order' => clean_order_for_customer($updated, false)]);
}

if ($action === 'status') {
    $id = (int)($_GET['id'] ?? 0);
    $tracking = trim((string)($_GET['tracking'] ?? ''));
    if ($id <= 0 || $tracking === '') respond(400, ['ok' => false, 'error' => 'Ordergegevens ontbreken.']);

    // Validate the private tracking token before any provider request.
    $accessGranted = with_state($stateFile, false, function ($state) use ($id, $tracking) {
        foreach ($state['orders'] as $order) {
            if ((int)$order['id'] === $id && hash_equals((string)$order['tracking'], $tracking)) return true;
        }
        return false;
    });
    if (!$accessGranted) respond(404, ['ok' => false, 'error' => 'Bestelling niet gevonden.']);
    tikkie_refresh_one($stateFile, tikkie_config($dataDir), $id);
    $order = with_state($stateFile, false, function ($state) use ($id, $tracking) {
        foreach ($state['orders'] as $order) {
            if ((int)$order['id'] === $id && hash_equals((string)$order['tracking'], $tracking)) return $order;
        }
        return null;
    });
    if (!$order) respond(404, ['ok' => false, 'error' => 'Bestelling niet gevonden.']);
    respond(200, ['ok' => true, 'order' => clean_order_for_customer($order, false)]);
}

if ($action === 'cancel') {
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') respond(405, ['ok' => false, 'error' => 'POST vereist.']);
    $body = body_json();
    $id = (int)($body['id'] ?? 0);
    $tracking = trim((string)($body['tracking'] ?? ''));
    if ($id <= 0 || $tracking === '') respond(400, ['ok' => false, 'error' => 'Bestelgegevens ontbreken.']);

    $updated = with_state($stateFile, true, function (&$state) use ($id, $tracking) {
        foreach ($state['orders'] as &$order) {
            if ((int)$order['id'] !== $id) continue;
            if (!hash_equals((string)($order['tracking'] ?? ''), $tracking)) return false;
            if ((string)($order['status'] ?? '') === 'Afgerond') return 'closed';
            if (in_array((string)($order['status'] ?? ''), ['Geannuleerd', 'Uitverkocht'], true)) return $order;
            $order['status'] = 'Geannuleerd';
            return $order;
        }
        return null;
    });

    if ($updated === false) respond(403, ['ok' => false, 'error' => 'Bestelling kan niet worden geverifieerd.']);
    if ($updated === 'closed') respond(409, ['ok' => false, 'error' => 'Deze bestelling is al afgerond.']);
    if (!$updated) respond(404, ['ok' => false, 'error' => 'Bestelling niet gevonden.']);
    respond(200, ['ok' => true, 'order' => clean_order_for_customer($updated, false)]);
}

if ($action === 'announcement') {
    $customerForAvailability = trim((string)($_GET['customer'] ?? ''));
    $announcement = with_state($stateFile, false, function ($state) use ($customerForAvailability) {
        $a = $state['announcement'] ?? ['title'=>'','message'=>'','from'=>'','until'=>'','active'=>false,'updated'=>''];
        if (!is_array($a)) $a = ['title'=>'','message'=>'','from'=>'','until'=>'','active'=>false,'updated'=>''];

        $active = (bool)($a['active'] ?? false);
        $today = date('Y-m-d');
        $from = trim((string)($a['from'] ?? ''));
        $until = trim((string)($a['until'] ?? ''));
        // Een geplande mededeling wordt direct als banner getoond, ook als
        // de gekozen vanaf-datum in de toekomst ligt. Na de einddatum verdwijnt hij.
        if ($until !== '' && $today > $until) $active = false;

        $scheduleStatus = ordering_schedule_status($state['ordering_schedule'] ?? []);
        $tester = is_test_customer($customerForAvailability);
        $manualBlocked = ordering_blocked_today($a);
        $orderingAllowed = !$manualBlocked && (bool)$scheduleStatus['allowed'];
        return [
            'title' => trim((string)($a['title'] ?? '')),
            'message' => trim((string)($a['message'] ?? '')),
            'from' => $from,
            'until' => $until,
            'active' => $active,
            'updated' => (string)($a['updated'] ?? ''),
            'ordering_blocked' => !$orderingAllowed,
            'ordering_allowed' => $orderingAllowed,
            'ordering_message' => $manualBlocked ? 'Vandaag is Rutu BBQ gesloten voor bestellingen.' : (string)$scheduleStatus['message'],
            'test_order_allowed' => $tester
        ];
    });
    // Payment-provider onboarding is business-only; never show that notice in customer apps.
    $noticeText = mb_strtolower(($announcement['title'] ?? '') . ' ' . ($announcement['message'] ?? ''), 'UTF-8');
    if (str_contains($noticeText, 'tikkie') && (str_contains($noticeText, 'zakelijk') || str_contains($noticeText, 'business'))) {
        $announcement['active'] = false;
    }
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

if ($action === 'business_ordering_schedule') {
    if (!business_authorized($keyFile)) respond(401, ['ok' => false, 'error' => 'Niet geautoriseerd.']);

    if ($_SERVER['REQUEST_METHOD'] === 'GET') {
        $schedule = with_state($stateFile, false, fn($state) => normalize_ordering_schedule($state['ordering_schedule'] ?? null));
        respond(200, ['ok' => true, 'schedule' => $schedule, 'current' => ordering_schedule_status($schedule)]);
    }

    if ($_SERVER['REQUEST_METHOD'] !== 'POST') respond(405, ['ok' => false, 'error' => 'GET of POST vereist.']);
    $body = body_json();
    $incoming = is_array($body['schedule'] ?? null) ? $body['schedule'] : null;
    if ($incoming === null) respond(400, ['ok' => false, 'error' => 'Openingstijden ontbreken.']);
    $schedule = normalize_ordering_schedule($incoming);
    $schedule['updated'] = gmdate('c');
    with_state($stateFile, true, function (&$state) use ($schedule) {
        $state['ordering_schedule'] = $schedule;
        return true;
    });
    respond(200, ['ok' => true, 'schedule' => $schedule, 'current' => ordering_schedule_status($schedule)]);
}

if ($action === 'business_orders') {
    if (!business_authorized($keyFile)) respond(401, ['ok' => false, 'error' => 'Niet geautoriseerd.']);
    tikkie_refresh_one($stateFile, tikkie_config($dataDir));
    $orders = with_state($stateFile, false, fn($state) => array_map(
        fn($order) => clean_order_for_business($order),
        $state['orders']
    ));
    respond(200, ['ok' => true, 'orders' => $orders]);
}

if ($action === 'business_clear_history') {
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') respond(405, ['ok' => false, 'error' => 'POST vereist.']);
    if (!business_authorized($keyFile)) respond(401, ['ok' => false, 'error' => 'Niet geautoriseerd.']);

    $count = with_state($stateFile, true, function (&$state) {
        $count = 0;
        foreach ($state['orders'] as &$order) {
            if (!(bool)($order['history_cleared'] ?? false)) {
                $order['history_cleared'] = true;
                $count++;
            }
        }
        return $count;
    });

    respond(200, ['ok' => true, 'cleared' => $count]);
}

if ($action === 'business_hide_history') {
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') respond(405, ['ok' => false, 'error' => 'POST vereist.']);
    if (!business_authorized($keyFile)) respond(401, ['ok' => false, 'error' => 'Niet geautoriseerd.']);
    $body = body_json();
    $id = (int)($body['id'] ?? 0);
    if ($id <= 0) respond(400, ['ok' => false, 'error' => 'Bestelnummer ontbreekt.']);

    $updated = with_state($stateFile, true, function (&$state) use ($id) {
        foreach ($state['orders'] as &$order) {
            if ((int)$order['id'] === $id) {
                if (!in_array((string)($order['status'] ?? ''), ['Afgerond', 'Uitverkocht', 'Geweigerd', 'Geannuleerd'], true)) return false;
                $order['history_hidden'] = true;
                return $order;
            }
        }
        return null;
    });

    if ($updated === false) respond(409, ['ok' => false, 'error' => 'Alleen afgeronde of geweigerde bestellingen kunnen uit de geschiedenis worden verborgen.']);
    if (!$updated) respond(404, ['ok' => false, 'error' => 'Bestelling niet gevonden.']);
    respond(200, ['ok' => true, 'order' => clean_order_for_business($updated)]);
}

if ($action === 'business_status') {
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') respond(405, ['ok' => false, 'error' => 'POST vereist.']);
    if (!business_authorized($keyFile)) respond(401, ['ok' => false, 'error' => 'Niet geautoriseerd.']);
    $body = body_json();
    $id = (int)($body['id'] ?? 0);
    $status = trim((string)($body['status'] ?? ''));
    $allowed = ['Nieuw', 'In bereiding', 'Klaar', 'Bestelling is onderweg', 'Afgerond', 'Geweigerd', 'Geannuleerd', 'Uitverkocht'];
    if ($id <= 0 || !in_array($status, $allowed, true)) {
        respond(400, ['ok' => false, 'error' => 'Ongeldige statuswijziging.']);
    }

    $updated = with_state($stateFile, true, function (&$state) use ($id, $status) {
        foreach ($state['orders'] as &$order) {
            if ((int)$order['id'] === $id) {
                if ($status === 'Bestelling is onderweg' && empty($order['delivery'])) return 'delivery_required';
                $order['status'] = $status;
                return $order;
            }
        }
        return null;
    });
    if ($updated === 'delivery_required') respond(409, ['ok' => false, 'error' => 'Deze status is alleen beschikbaar voor bezorgbestellingen.']);
    if (!$updated) respond(404, ['ok' => false, 'error' => 'Bestelling niet gevonden.']);
    respond(200, ['ok' => true, 'order' => clean_order_for_customer($updated, false)]);
}

respond(404, ['ok' => false, 'error' => 'Onbekende actie.']);
