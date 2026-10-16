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
        shutil.copyfile(ROOT / "the-one-remote-server" / "devices.php", api / "devices.php")
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
                print("PASS: all 7 signed Family apps, pending/grant/revoke, signature rejection, separate XXX denial")
            finally:
                process.terminate()
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


if __name__ == "__main__":
    main()
