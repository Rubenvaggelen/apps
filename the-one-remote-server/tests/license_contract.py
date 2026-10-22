#!/usr/bin/env python3
"""Offline contract test: no production URLs, credentials, approvals or data are used."""
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
PACKAGES = {
    "com.theone.cinema": "cinema_android",
    "com.theone.cinema.tv": "cinema_android",
    "com.theone.series": "series_android",
    "com.theone.series.tv": "series_android",
    "com.theone.tv.phone": "tv_android",
    "com.theone.tv": "tv_android",
    "com.gmailorg.theoneuser": "the_one_user",
    "com.gmailorg.kids": "kids_android",
}
EXPECTED_SCOPES = set(PACKAGES.values()) | {"media_player_xxx"}


def call(url, action, body):
    req = urllib.request.Request(
        url + "?action=" + action,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())


def expect(url, action, body, status, error=None):
    actual, response = call(url, action, body)
    assert actual == status, (action, actual, status, response)
    if error is not None:
        assert response.get("error") == error, (action, response)
    return response


def signing_pair(temp, number):
    private_key = temp / ("private-%d.pem" % number)
    public_key = temp / ("public-%d.der" % number)
    subprocess.run(
        ["openssl", "ecparam", "-name", "prime256v1", "-genkey",
         "-noout", "-out", str(private_key)],
        check=True, stdout=subprocess.DEVNULL,
    )
    subprocess.run(
        ["openssl", "pkey", "-in", str(private_key), "-pubout",
         "-outform", "DER", "-out", str(public_key)],
        check=True, stdout=subprocess.DEVNULL,
    )
    der = public_key.read_bytes()
    return private_key, der, "fam-" + hashlib.sha256(der).hexdigest()[:48]


def proof(temp, key, device, scope, action):
    nonce = base64.b64encode(os.urandom(32)).decode("ascii")
    message = (action + "|" + device + "|" + scope + "|" + nonce).encode("utf-8")
    challenge = temp / "challenge.bin"
    signed = temp / "signature.bin"
    challenge.write_bytes(message)
    subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", str(key),
         "-out", str(signed), str(challenge)],
        check=True, stdout=subprocess.DEVNULL,
    )
    return {
        "device_id": device,
        "nonce": nonce,
        "signature": base64.b64encode(signed.read_bytes()).decode("ascii"),
    }


def main():
    if shutil.which("php") is None or shutil.which("openssl") is None:
        raise RuntimeError("PHP and OpenSSL are required for license contract tests")
    with tempfile.TemporaryDirectory(prefix="theone-family-license-ci-") as work:
        temp = Path(work)
        docroot = temp / "public_html"
        api = docroot / "the-one-remote-api"
        api.mkdir(parents=True)
        data_dir = temp / "the-one-remote-data"
        data_dir.mkdir()
        test_pin = "ci-owner-pin-only-for-isolated-test"
        php_source = (ROOT / "the-one-remote-server" / "devices.php").read_text()
        marker = "const MAIN_ADMIN_SHA256 = '"
        start = php_source.index(marker) + len(marker)
        end = php_source.index("';", start)
        php_source = php_source[:start] + hashlib.sha256(test_pin.encode()).hexdigest() + php_source[end:]
        (api / "devices.php").write_text(php_source)
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        url = "http://127.0.0.1:%d/the-one-remote-api/devices.php" % port
        with (temp / "php-server.log").open("wb") as logs:
            process = subprocess.Popen(
                ["php", "-S", "127.0.0.1:%d" % port, "-t", str(docroot)],
                stdout=logs, stderr=subprocess.STDOUT,
            )
            try:
                for _ in range(50):
                    try:
                        with urllib.request.urlopen(url + "?action=health", timeout=1) as response:
                            if response.status == 200:
                                break
                    except (OSError, urllib.error.HTTPError):
                        time.sleep(0.1)
                else:
                    raise RuntimeError("PHP test server did not start")
                # Correct routing: unknown or missing registered identity MUST NOT become generic 403.
                expect(url, "family_register", {}, 400, "Unknown app")
                expect(url, "family_check", {}, 400, "Invalid device")
                family_devices = {}
                for i, (package, scope) in enumerate(PACKAGES.items()):
                    key, public_der, device = signing_pair(temp, i)
                    registration = proof(temp, key, device, scope, "register")
                    registration.update({
                        "package": package,
                        "public_key": base64.b64encode(public_der).decode("ascii"),
                        "person_name": "CI License Tester",
                        "platform": "Android",
                        "device_name": "Ephemeral Test Device",
                    })
                    response = expect(url, "family_register", registration, 200)
                    family_devices[scope] = device
                    assert response.get("pending") is True
                    assert response.get("allowed") is False

                    check_body = proof(temp, key, device, scope, "check")
                    check_body["package"] = package
                    result = expect(url, "family_check", check_body, 200)
                    assert result.get("allowed") is False
                    assert result.get("pending") is True

                    state_file = data_dir / "main-devices.json"
                    state = json.loads(state_file.read_text())
                    record = state["devices"][device]
                    assert record.get("family_package") == package
                    if not isinstance(record["access_rights"], dict):
                        record["access_rights"] = {}
                    if not isinstance(record["access_requests"], dict):
                        record["access_requests"] = {}
                    assert record["access_rights"].get(scope) is not True
                    # Approvals are granted ONLY in the local test fixture, not on a real server.
                    record["access_rights"][scope] = True
                    record["access_requests"].pop(scope, None)
                    state_file.write_text(json.dumps(state))
                    approved = expect(url, "family_check", proof(temp, key, device, scope, "check") | {"package": package}, 200)
                    assert approved.get("allowed") is True
                    forged = proof(temp, key, device, scope, "check")
                    forged["package"] = package
                    forged["nonce"] = base64.b64encode(os.urandom(32)).decode("ascii")
                    expect(url, "family_check", forged, 403, "Device signature rejected")
                    state = json.loads(state_file.read_text())
                    state["devices"][device]["access_rights"][scope] = False
                    state_file.write_text(json.dumps(state))
                    revoked = expect(url, "family_check", proof(temp, key, device, scope, "check") | {"package": package}, 200)
                    assert revoked.get("allowed") is False

                # Media Player XXX consent is distinct and defaults to DENY.
                fake = "ci-access-only"
                state = json.loads((data_dir / "main-devices.json").read_text())
                state["devices"][fake] = {
                    "device_id": fake,
                    "person_name": "CI Tester",
                    "access_rights": {"media_player": True},
                    "access_requests": {},
                    "blocked": False,
                }
                (data_dir / "main-devices.json").write_text(json.dumps(state))
                mp = expect(url, "access_status", {"device_id": fake, "scope": "media_player"}, 200)
                xxx = expect(url, "access_status", {"device_id": fake, "scope": "media_player_xxx"}, 200)
                assert mp.get("allowed") is True and xxx.get("allowed") is False
                for scope in EXPECTED_SCOPES:
                    expect(url, "access_status", {"device_id": fake, "scope": scope}, 200)
                # THEONE requires the current owner PIN for every fresh app install.
                god_devices = {}
                full_scopes = {
                    "the_one_user", "media_player", "media_player_xxx", "mixes", "shared",
                    "favorites", "dj", "run", "downloads", "download_files",
                    "cinema_android", "series_android", "tv_android", "kids_android",
                }
                for i, (package, scope) in enumerate(PACKAGES.items(), start=20):
                    key, public_der, device = signing_pair(temp, i)
                    registration = proof(temp, key, device, scope, "register")
                    registration.update({
                        "package": package,
                        "public_key": base64.b64encode(public_der).decode("ascii"),
                        "person_name": "THEONE",
                        "platform": "Android",
                        "device_name": "Ephemeral GOD Mode Device",
                    })
                    if i == 20:
                        expect(url, "family_register", registration, 403, "Owner PIN required for THEONE")
                        expect(url, "family_register", registration | {"pin": "wrong"}, 403, "Owner PIN required for THEONE")
                        god = expect(url, "family_register", registration | {"pin": test_pin}, 200)
                        generic = expect(url, "heartbeat", {
                            "device_id": "ci-god-heartbeat",
                            "name": "GOD test generic app",
                            "person_name": "theone",
                            "platform": "Android",
                        }, 200)
                        assert generic.get("access_rights") == {scope: True for scope in full_scopes}
                    else:
                        # One owner-PIN activation applies to all later app installations for THEONE.
                        god = expect(url, "family_register", registration, 200)
                    god_devices[scope + "-" + str(i)] = device
                    assert god.get("allowed") is True and god.get("pending") is False
                    assert god.get("god_mode") is True

                god_state = json.loads((data_dir / "main-devices.json").read_text())
                for device in god_devices.values():
                    record = god_state["devices"][device]
                    assert record.get("god_mode") is True
                    assert record.get("access_rights") == {scope: True for scope in full_scopes}
                    assert not record.get("access_requests")

                # Owner-only management actions use an ephemeral CI credential and isolated state.
                owner_id = "ci-owner"
                owner_person_id = "person-ci-owner"
                (data_dir / "main-device-owner.json").write_text(json.dumps({"device_id": owner_id}))
                state = json.loads((data_dir / "main-devices.json").read_text())
                state["devices"][owner_id] = {
                    "device_id": owner_id,
                    "person_id": owner_person_id,
                    "person_name": "CI Owner",
                    "access_rights": {},
                    "access_requests": {},
                }
                legacy_id = "ci-legacy-device"
                legacy_person_id = "person-ci-legacy"
                state["devices"][legacy_id] = {
                    "device_id": legacy_id,
                    "person_id": legacy_person_id,
                    "person_name": "CI Legacy",
                    "access_rights": {"media_player": True},
                    "access_requests": {"media_player": {"status": "pending", "requested_at": "now"}},
                    "blocked": False,
                }
                (data_dir / "main-devices.json").write_text(json.dumps(state))

                auth = {"request_device_id": owner_id}
                listing = expect(url, "list", auth, 200).get("devices", [])
                kids_row = next(row for row in listing if row.get("device_id") == family_devices["kids_android"])
                assert kids_row.get("family_scope") == "kids_android"

                unauthorized = auth | {
                    "pin": "wrong",
                    "person_id": legacy_person_id,
                    "device_ids": [legacy_id],
                    "scope": "media_player",
                }
                before = json.loads((data_dir / "main-devices.json").read_text())
                expect(url, "remove_person_app", unauthorized, 403, "Owner PIN required")
                assert json.loads((data_dir / "main-devices.json").read_text()) == before

                remove_legacy = auth | {
                    "pin": test_pin,
                    "person_id": legacy_person_id,
                    "device_ids": [legacy_id],
                    "scope": "media_player",
                }
                expect(url, "remove_person_app", remove_legacy, 200)
                state = json.loads((data_dir / "main-devices.json").read_text())
                assert state["devices"][legacy_id]["access_rights"]["media_player"] is False
                assert "media_player" not in state["devices"][legacy_id]["access_requests"]

                family_person_id = state["devices"][family_devices["kids_android"]]["person_id"]
                remove_kids = auth | {
                    "pin": test_pin,
                    "person_id": family_person_id,
                    "device_ids": [],
                    "scope": "kids_android",
                }
                expect(url, "remove_person_app", remove_kids, 200)
                state = json.loads((data_dir / "main-devices.json").read_text())
                assert family_devices["kids_android"] in state["devices"]
                kids_state = state["devices"][family_devices["kids_android"]]
                assert kids_state["access_rights"]["kids_android"] is False
                assert "kids_android" not in kids_state["access_requests"]
                assert "kids_android" in kids_state["family_denied_scopes"]
                assert family_devices["series_android"] in state["devices"]

                remove_person = auth | {
                    "pin": test_pin,
                    "person_id": family_person_id,
                    "device_ids": [],
                }
                expect(url, "remove_person", remove_person, 200)
                state = json.loads((data_dir / "main-devices.json").read_text())
                assert all(row.get("person_id") != family_person_id for row in state["devices"].values())
                remove_owner = auth | {
                    "pin": test_pin,
                    "person_id": owner_person_id,
                    "device_ids": [],
                }
                expect(url, "remove_person", remove_owner, 409, "Owner person cannot be removed")
                assert owner_id in json.loads((data_dir / "main-devices.json").read_text())["devices"]

                # Removing the THEONE person removes the PIN-verified global activation.
                remove_god_person = auth | {
                    "pin": test_pin,
                    "person_id": god_person_id,
                    "device_ids": [],
                }
                expect(url, "remove_person", remove_god_person, 200)
                state = json.loads((data_dir / "main-devices.json").read_text())
                assert all(row.get("person_id") != god_person_id for row in state["devices"].values())
                key, public_der, fresh_device = signing_pair(temp, 99)
                fresh = proof(temp, key, fresh_device, "series_android", "register")
                fresh.update({
                    "package": "com.theone.series",
                    "public_key": base64.b64encode(public_der).decode("ascii"),
                    "person_name": "THEONE",
                    "platform": "Android",
                    "device_name": "Fresh GOD Mode Device",
                })
                expect(url, "family_register", fresh, 403, "Owner PIN required for THEONE")

                print("PASS: signed Family licenses, global owner-PIN THEONE mode, app/person removal, owner PIN guards, separate XXX denial")
            finally:
                process.terminate()
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


if __name__ == "__main__":
    main()
