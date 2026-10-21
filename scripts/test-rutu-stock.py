#!/usr/bin/env python3
"""Test persistent stock toggles, POS/customer rejections and cart additions safely with a disposable PHP server."""
import http.client,json,pathlib,shutil,socket,subprocess,tempfile,time,datetime
ROOT=pathlib.Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="rutu-stock-") as td:
    root=pathlib.Path(td)
    (root/"www"/"rutu-api").mkdir(parents=True)
    (root/"rutu-data").mkdir()
    shutil.copyfile(ROOT/"rutu-server"/"index.php",root/"www"/"rutu-api"/"index.php")
    (root/"rutu-data"/"business.key").write_text("stock-test-business-key\n")
    state={"next_id":1046,"orders":[],"ordering_schedule":{"days":{str(i):{"open":True} for i in range(1,8)},"timezone":"Europe/Amsterdam"}}
    (root/"rutu-data"/"orders.json").write_text(json.dumps(state))
    with socket.socket() as sock:
        sock.bind(("127.0.0.1",0))
        port=sock.getsockname()[1]
    log=open(root/"php.log","w")
    server=subprocess.Popen(["php","-S",f"127.0.0.1:{port}","-t",str(root/"www")],stdout=log,stderr=log)
    try:
        def call(action,body=None,authorized=False):
            conn=http.client.HTTPConnection("127.0.0.1",port,timeout=12)
            headers={"Content-Type":"application/json"}
            if authorized:headers["X-Rutu-Business-Key"]="stock-test-business-key"
            conn.request("POST" if body is not None else "GET","/rutu-api/index.php?action="+action,json.dumps(body).encode() if body is not None else None,headers)
            r=conn.getresponse();data=r.read();code=r.status;conn.close()
            try:return code,json.loads(data)
            except ValueError:raise AssertionError(("NONJSON",code,data[:300]))
        for attempt in range(35):
            try:call("health");break
            except ConnectionError:time.sleep(.1)
        else:raise AssertionError("isolated server not running")
        assert call("menu_availability")== (200,{"ok":True,"sold_out":[]})
        assert call("business_menu_stock")[0]==401
        assert call("business_menu_stock",{"product":"BBQ Extra","available":False})[0]==401
        assert call("business_menu_stock",{"product":"Bogus","available":False},True)[0]==400
        assert call("business_menu_stock",{"product":"BBQ Extra","available":"false"},True)[0]==400
        pos={"request_id":"cashier-test-stock-00001","payment":"cash","customer":"Balie","items":{"BBQ Extra":1}}
        code,r=call("business_create_order",pos,True)
        assert code==201,(code,r)
        saved_id=r["order"]["id"]
        code,r=call("business_menu_stock",{"product":"BBQ Extra","available":False},True)
        assert code==200 and r["sold_out"]==["BBQ Extra"],(code,r)
        assert call("menu_availability")[1]["sold_out"]==["BBQ Extra"]
        assert call("announcement")[1]["announcement"]["sold_out"]==["BBQ Extra"]
        code,r=call("business_create_order",{"request_id":"cashier-test-stock-00002","payment":"free","free_name":"Gast","items":{"BBQ Extra":1}},True)
        assert code==409 and r.get("sold_out") is True and r.get("product")=="BBQ Extra",(code,r)
        # An already saved cashier order must still be retrievable when its response is replayed.
        code,r=call("business_create_order",pos,True)
        assert code==200 and r["existing"] and r["order"]["id"]==saved_id,(code,r)
        code,r=call("create",{"customer":"Nieuwe Klant","items":{"BBQ Extra":1},"delivery":False})
        assert code==409 and r.get("sold_out") is True,(code,r)
        code,r=call("create",{"customer":"Nieuwe Klant","items":{"BBQ Regular":1},"delivery":False})
        assert code==201 and r["order"]["total"]==10,(code,r)
        tracking=r["order"]["tracking"];oid=r["order"]["id"]
        code,r=call("add_items",{"id":oid,"tracking":tracking,"items":{"BBQ Extra":1}})
        assert code==409 and r.get("sold_out") is True,(code,r)
        code,r=call("add_items",{"id":oid,"tracking":tracking,"items":{"Extra salade":1}})
        assert code==200 and r["order"]["total"]==12.5,(code,r)
        # Legacy name uses same stock status as Family.
        code,r=call("business_menu_stock",{"product":"BBQ Family","available":False},True)
        assert code==200 and "BBQ Family" in r["sold_out"]
        code,r=call("create",{"customer":"Nieuwe Klant","items":{"BBQ Gezin":1},"delivery":False})
        assert code==409 and r.get("product")=="BBQ Family",(code,r)
        code,r=call("business_menu_stock",{"product":"BBQ Extra","available":True},True)
        assert code==200 and "BBQ Extra" not in r["sold_out"],(code,r)
        code,r=call("create",{"customer":"Nieuwe Klant","items":{"BBQ Extra":1},"delivery":False})
        assert code==201,(code,r)
        code,orders=call("business_orders",authorized=True)
        assert code==200 and len(orders["orders"])==4,(code,orders)
        persisted=json.loads((root/"rutu-data"/"orders.json").read_text())
        assert persisted["sold_out"]==["BBQ Family"]
        assert len(persisted["orders"])==4
        assert persisted["orders"][0]["total"]==15
        print("RUTU_STOCK_END_TO_END_OK: security, six product options, atomic order blocking, checkout, cart additions, legacy alias, replay, restoring available")
    finally:
        server.terminate()
        try:server.wait(timeout=4)
        except subprocess.TimeoutExpired:server.kill();server.wait()
        log.close()
