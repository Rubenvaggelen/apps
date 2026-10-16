#!/usr/bin/env python3
"""Private diagnostics: test availability of a named live channel without leaking URLs or login data."""
import json
import socket
import sys
from pathlib import Path
from urllib.parse import urlencode, quote
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

socket.setdefaulttimeout(14)
ROOT=Path("media-player/app/src/main/assets")
TARGET="czech streets"


def fetch(api, max_bytes=20*1024*1024):
    with urlopen(Request(api,headers={"User-Agent":"TheOneMediaPlayer/2.4.1","Accept":"application/json"}),timeout=14) as response:
        blob=response.read(max_bytes+1)
        if len(blob)>max_bytes: raise RuntimeError("CATALOG_TOO_LARGE")
        return json.loads(blob.decode("utf-8-sig"))


def probe(server, username, password, stream_id, extension):
    # Never output URL, login, response body, or stream ID to logs.
    url=server.rstrip("/")+"/live/"+quote(username,safe="")+"/"+quote(password,safe="")+"/"+str(stream_id)+"."+extension
    request=Request(url,headers={"Range":"bytes=0-1023","User-Agent":"TheOneMediaPlayer/2.4.1"})
    try:
        with urlopen(request,timeout=14) as response:
            b=response.read(768)
            code=response.status
            content_type=response.headers.get("Content-Type","").split(";")[0].lower()
            if extension=="m3u8":
                valid=b.lstrip().startswith(b"#EXTM3U")
            else:
                valid=bool(b) and not b.lstrip()[:2].lower() in (b"<h",b"<?")
            return str(code)+("/CONTENT_OK" if valid else "/FORMAT_UNEXPECTED"),content_type
    except HTTPError as e:
        return "HTTP_"+str(e.code),""
    except (URLError,TimeoutError,OSError) as e:
        return "CONNECT_FAILED",""


def main():
    for i,filename in enumerate(["private_xtream.json","private_xtream_secondary.json"],1):
        file=ROOT/filename
        if not file.is_file():
            print(f"SOURCE_{i}: NOT_AVAILABLE")
            continue
        c=json.loads(file.read_text())
        server=c["server"]; username=c["username"];password=c["password"]
        api=server.rstrip("/")+"/player_api.php?"+urlencode({"username":username,"password":password,"action":"get_live_streams"})
        try:
            rows=fetch(api)
            if not isinstance(rows,list):raise RuntimeError("NOT_STREAM_LIST")
        except Exception as e:
            print(f"SOURCE_{i}: CATALOG_UNAVAILABLE reason={type(e).__name__}")
            continue
        matched=[r for r in rows if isinstance(r,dict) and TARGET in str(r.get("name","")).lower()]
        print(f"SOURCE_{i}: LIVE_CATALOG_TOTAL={len(rows)} CZECH_STREETS_MATCHES={len(matched)}")
        for n,item in enumerate(matched[:4],1):
            stream_id=item.get("stream_id")
            ext=str(item.get("container_extension") or "ts").lower().lstrip(".")
            if not str(stream_id).isdigit():
                print(f"SOURCE_{i}: MATCH_{n}=INVALID_STREAM_ID")
                continue
            tested=set()
            for extension in [ext,"ts","m3u8"]:
                if extension not in ("ts","m3u8") or extension in tested: continue
                tested.add(extension)
                status,kind=probe(server,username,password,stream_id,extension)
                print(f"SOURCE_{i}: MATCH_{n} {extension}={status} MIME={kind or 'unknown'}")
    print("HEALTHCHECK_DONE (no URLs or account data logged)")


if __name__=="__main__":
    main()
