#!/usr/bin/env python3
"""Recover user-approved Kids v1131 only inside an authenticated GitHub runner."""
import base64
import hashlib
import json
import os
from pathlib import Path
from urllib.request import Request, urlopen
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

BASE = "https://raw.githubusercontent.com/Rubenvaggelen/apps/kids-owner-updates/channel/"
APP_SHA = "0181d8a56a55d579be2c92630b73e5351673cb264858316055d70a0a8a7b2ad6"
CIPHER_SHA = "0576371bec62c69b09dabad83afdc4b690222e8cd341ae9521542a2a84d7ae9e"

def download(path):
    assert path == "latest.json" or __import__("re").fullmatch(r"v1131/chunk-[0-9]{3}\.b64", path)
    with urlopen(Request(BASE + path, headers={"User-Agent": "The-One-Kids-DevHub-CI"}), timeout=35) as response:
        assert response.status == 200
        return response.read()

m = json.loads(download("latest.json"))
assert m.get("versionCode") == 1131
assert m.get("encryptedBytes") == 5375915
assert m.get("sha256") == CIPHER_SHA
chunks = m.get("chunks", [])
assert len(chunks) == 21 and chunks == ["v1131/chunk-%03d.b64" % i for i in range(21)]
encrypted = b"".join(base64.b64decode(download(name).strip(), validate=True) for name in chunks)
assert len(encrypted) == m["encryptedBytes"]
assert hashlib.sha256(encrypted).hexdigest() == CIPHER_SHA

key = Path(os.environ["KIDS_UPDATE_KEY_FILE"]).read_bytes()
assert len(key) == 32
plain = AESGCM(key).decrypt(encrypted[:12], encrypted[12:], b"TheOneKids|com.gmailorg.kids|1131")
assert len(plain) == 5375887
assert hashlib.sha256(plain).hexdigest() == APP_SHA
dest = Path(os.environ["KIDS_APK_PATH"])
dest.parent.mkdir(parents=True, exist_ok=True)
dest.write_bytes(plain)
dest.chmod(0o600)
print("Verified: Kids 1131 encrypted OTA reconstructs exact owner-approved APK; private key and APK not logged")
