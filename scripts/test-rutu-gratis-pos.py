#!/usr/bin/env python3
"""Run RUTU POS API regression tests against an isolated disposable PHP server."""
import contextlib
import http.client
import json
import pathlib
import shutil
import socket
import subprocess
import tempfile
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="rutu-gratis-pos-") as temp:
    base = pathlib.Path(temp)
    api = base / "www" / "rutu-api"
    api.mkdir(parents=True)
    (base / "rutu-data").mkdir()
    shutil.copyfile(ROOT / "rutu-server" / "index.php", api / "index.php")
    (base / "rutu-data" / "business.key").write_text("fixture-business-key\n")
    (base / "rutu-data" / "orders.json").write_text(json.dumps({"next_id": 1046, "orders": []}))
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    with open(base / "php.log", "w") as log:
        server = subprocess.Popen(["php", "-S", f"127.0.0.1:{port}", "-t", str(base / "www")], stdout=log, stderr=log)
        try:
            def call(action, payload=None, auth=True, method=None):
                conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
                body = json.dumps(payload).encode() if payload is not None else None
                headers = {"Content-Type": "application/json"}
                if auth:
                    headers["X-Rutu-Business-Key"] = "fixture-business-key"
                conn.request(method or ("POST" if payload is not None else "GET"), f"/rutu-api/index.php?action={action}", body, headers)
                resp = conn.getresponse()
                data = resp.read()
                code = resp.status
                conn.close()
                return code, json.loads(data)

            for attempt in range(25):
                try:
                    status, _ = call("business_orders", auth=False)
                    assert status == 401
                    break
                except (ConnectionError, TimeoutError):
                    time.sleep(.1)
            else:
                raise AssertionError("PHP test server did not become reachable")
            form = {"request_id": "cashier-test-free-001", "items": {"BBQ Regular": 2, "Extra salade": 1}, "payment": "free", "customer": "Kassa"}
            assert call("business_create_order", dict(form, free_name="Gast A"), auth=False)[0] == 401
            assert call("business_create_order", form)[0] == 400
            assert call("business_create_order", dict(form, free_name="   "))[0] == 400
            assert call("business_create_order", dict(form, free_name="X" * 81))[0] == 400
            assert call("business_create_order", dict(form, free_name="Gasten\nPrivé"))[0] == 400
            status, result = call("business_create_order", dict(form, free_name="Gast A"))
            assert status == 201, (status, result)
            order = result["order"]
            assert order["payment_method"] == "Gratis" and order["payment_status"] == "Gratis verstrekt"
            assert order["total"] == 0 and order["original_total"] == 22.5 and order["free_name"] == "Gast A"
            assert order["customer"] == "Kassa"
            status, replay = call("business_create_order", dict(form, free_name="Gast A"))
            assert status == 200 and replay["existing"] and replay["order"]["id"] == order["id"]
            for payment, label, payment_status in [
                ("cash", "Cash", "Ontvangen aan kassa"),
                ("pin", "Pin", "Ontvangen aan kassa"),
                ("later", "Later", "Openstaand"),
            ]:
                code, result = call("business_create_order", {
                    "request_id": f"cashier-test-{payment}-002", "payment": payment,
                    "items": {"BBQ Extra": 1}, "customer": "Balie"
                })
                assert code == 201, (payment, code, result)
                o = result["order"]
                assert o["total"] == 15.0 and o["original_total"] == 15.0
                assert o["payment_method"] == label and o["payment_status"] == payment_status
                assert o["free_name"] == ""
            status, listing = call("business_orders")
            assert status == 200 and len(listing["orders"]) == 4
            assert not any("payment_request_token" in o for o in listing["orders"])
            print("PASS: Gratis, required name, escaped inputs, authorization, replay and cash/PIN/later")
        finally:
            server.terminate()
            try:
                server.wait(timeout=4)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait(timeout=4)
