#!/usr/bin/env python3
"""Owner deletion integration test with private local PHP fixture and zero production writes."""
import hashlib
import json
import os
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="theone-device-removal-") as work:
    home = Path(work)
    www = home / "public_html"
    api = www / "the-one-remote-api"
    registry = home / "the-one-remote-data"
    licenses = home / "the-one-private-licenses"
    for dir in [api, registry, licenses]:
        dir.mkdir(parents=True)
    for name in ["license-core.php", "owner-remove-device.php"]:
        (api / name).write_bytes((root / "the-one-remote-server" / name).read_bytes())
    owner = "11111111-1111-4111-8111-111111111111"
    old = "22222222-2222-4222-8222-222222222222"
    other = "33333333-3333-4333-8333-333333333333"
    owner_token = "f" * 64
    install_id = "44444444-4444-4444-8444-444444444444"
    time_old = "2026-10-10T13:00:00+00:00"
    time_other = "2026-10-10T14:00:00+00:00"
    (registry / "main-device-owner.json").write_text(json.dumps({"device_id":owner}))
    initial_registry = {"devices": {
        old: {"device_id":old, "name":"Tablet", "person_name":"Tablet","device_role":"main","registered":time_old,
              "access_rights":{"shared":True,"favorites":True},"music_rights":True},
        other:{"device_id":other,"name":"Other","person_name":"Other","device_role":"main","registered":time_other,
               "access_rights":{"shared":True}}
    }}
    (registry / "main-devices.json").write_text(json.dumps(initial_registry))
    state = {"schema":1, "main_enforced":False,"studio_enforced":False,"migration_at":None,
             "legacy_main":{old:{"name":"Tablet"}},"codes":{},
             "owner_tokens":{"owner-1":{"device":owner,"installation_id":install_id,"hash":hashlib.sha256(owner_token.encode()).hexdigest(),"revoked":False}},
             "grants":{"old-grant":{"app":"main","device":old,"status":"active","token_hash":"1"*64,
                                    "installation_id":install_id}},
             "requests":{"old-request":{"app":"main","device":old,"status":"pending"},
                         "other-request":{"app":"main","device":other,"status":"pending"}}}
    (licenses / "licenses.json").write_text(json.dumps(state))
    with socket.socket() as sock:
        sock.bind(("127.0.0.1",0))
        port=sock.getsockname()[1]
    env = dict(os.environ, THE_ONE_LICENSE_DATA_DIR=str(licenses))
    proc = subprocess.Popen(["php","-S",f"127.0.0.1:{port}","-t",str(www)],
                            env=env,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
    try:
        for _ in range(60):
            try:
                with socket.create_connection(("127.0.0.1",port),timeout=.25):
                    break
            except OSError:
                time.sleep(.1)
        else:
            raise RuntimeError("php local server failed")
        base=f"http://127.0.0.1:{port}/the-one-remote-api/owner-remove-device.php"
        payload={"device_id":owner,"installation_id":install_id,"owner_token":owner_token,
                 "target_device_id":old,"expected_registered":time_old}
        def post(values):
            req=urllib.request.Request(base,json.dumps(values).encode(),{"Content-Type":"application/json"},method="POST")
            try:
                with urllib.request.urlopen(req,timeout=4) as response:
                    return response.status,json.load(response)
            except urllib.error.HTTPError as e:
                return e.code,json.loads(e.read())
        code,_=post(dict(payload,owner_token="0"*64))
        assert code==403,("Bad owner token was not refused",code)
        code,_=post(dict(payload,target_device_id=owner))
        assert code==409,("Owner self-removal was not refused",code)
        code,_=post(dict(payload,expected_registered="2026-10-10T15:00:00+00:00"))
        assert code==409,("Stale registration was not refused",code)
        assert json.loads((licenses/"licenses.json").read_text())==state
        assert json.loads((registry/"main-devices.json").read_text())==initial_registry
        code,data=post(payload)
        assert code==200 and data.get("removed_device_id")==old,(code,data)
        after=json.loads((registry/"main-devices.json").read_text())
        assert old not in after["devices"] and after["devices"][other]==initial_registry["devices"][other]
        newstate=json.loads((licenses/"licenses.json").read_text())
        assert newstate["grants"]["old-grant"]["status"]=="revoked"
        assert newstate["requests"]["old-request"]["status"]=="revoked"
        assert newstate["requests"]["other-request"]["status"]=="pending"
        assert old not in newstate["legacy_main"]
        assert old in newstate["retired_devices"]
        assert list((home/".the-one-remote-backups").glob("device-remove-*.json"))
        code,_=post(payload)
        assert code==404,("Second deletion should not touch other devices",code)
        print("PASS: authenticated remove, wrong token/owner/timestamp denied, grants revoked, rights removed, backup retained, other devices untouched")
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            proc.kill()
