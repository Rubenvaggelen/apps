#!/usr/bin/env python3
"""Isolated integration tests for Rutu BBQ POS. No connection to the live service."""
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "rutu-server" / "index.php"

def request(base, action, body=None, key=None):
    headers = {"Accept": "application/json"}
    if key:
        headers["X-Rutu-Business-Key"] = key
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = Request(base + "?action=" + action, data=data, headers=headers)
    try:
        with urlopen(req, timeout=5) as result:
            return result.status, json.load(result)
    except HTTPError as err:
        return err.code, json.loads(err.read().decode())

def main():
    assert shutil.which("php"), "PHP CLI missing"
    with tempfile.TemporaryDirectory(prefix="rutu-pos-") as folder:
        parent = Path(folder)
        pub = parent / "public_html"
        api = pub / "rutu-api"
        api.mkdir(parents=True)
        shutil.copy2(SOURCE, api / "index.php")
        data = parent / "rutu-data"
        data.mkdir()
        (data / "business.key").write_text("rutu-private-test-key")
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        server = subprocess.Popen(
            ["php", "-S", f"127.0.0.1:{port}", "-t", str(pub)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
        )
        try:
            base = f"http://127.0.0.1:{port}/rutu-api/index.php"
            for _ in range(40):
                try:
                    status, health = request(base, "health")
                    if status == 200:
                        break
                except (URLError, OSError):
                    time.sleep(.15)
            else:
                raise AssertionError("PHP server did not start")
            items = {"Extra bout": 2, "Extra salade": 1, "Portie saté": 1}
            sale = {
                "request_id": "pos-unique-test-request-123456",
                "customer": "Testbalie",
                "items": items,
                "payment": "cash",
                "total": 0.01,
            }
            status, _ = request(base, "business_create_order", sale)
            assert status == 401, "Unauthenticated cashier request must be denied"
            key = "rutu-private-test-key"
            status, result = request(base, "business_create_order", sale, key)
            assert status == 201 and result["ok"] and not result["existing"], (status, result)
            order = result["order"]
            assert order["source"] == "Kassa"
            assert order["items"] == items
            assert order["total"] == 21.5, "Prices must be calculated server-side"
            assert order["payment_status"] == "Ontvangen aan kassa"
            first_id = order["id"]
            status, second = request(base, "business_create_order", sale, key)
            assert status == 200 and second["existing"]
            assert second["order"]["id"] == first_id, "Retry duplicated order"
            invalid = dict(sale, request_id="pos-another-test-request-456789", payment="tikkie")
            status, _ = request(base, "business_create_order", invalid, key)
            assert status == 400, "Cashier should not auto-trigger Tikkie"
            invalid = dict(sale, request_id="pos-another-test-request-456789", items={"Bad item":1})
            status, _ = request(base, "business_create_order", invalid, key)
            assert status == 400, "Unknown catalog item accepted"
            later = dict(sale, request_id="pos-another-test-request-456789",
                         items={"BBQ Extra":1, "Portie saté":1}, payment="later")
            status, response = request(base, "business_create_order", later, key)
            assert status == 201
            assert response["order"]["total"] == 22.0
            assert response["order"]["payment_status"] == "Openstaand"
            status, response = request(base, "business_orders", key=key)
            assert status == 200 and len(response["orders"]) == 2
            print("PASS: authenticated cashier, server prices, retry idempotency, and payment records")
        finally:
            server.terminate()
            try:
                server.wait(timeout=4)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait(timeout=4)

if __name__ == "__main__":
    main()
