"""Create a validated, attributed NL supermarket snapshot for offline Main operation."""
import datetime
import json
import math
import pathlib
import urllib.parse
import urllib.request

TARGET = pathlib.Path("app/src/main/assets/supermarkets-nl.json")
QUERY = '[out:json][timeout:60];area["ISO3166-1"="NL"]["admin_level"="2"]->.nl;nwr["shop"="supermarket"](area.nl);out center tags;'
ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
]

def validate(data):
    if data.get("remark"):
        raise ValueError("Partial Overpass response")
    shops = []
    seen = set()
    for element in data["elements"]:
        if element.get("tags", {}).get("shop") != "supermarket":
            continue
        point = element.get("center", element)
        lat, lon = point.get("lat"), point.get("lon")
        if not isinstance(lat, (float, int)) or not isinstance(lon, (float, int)):
            raise ValueError("Missing shop coordinates")
        if not math.isfinite(lat) or not math.isfinite(lon):
            raise ValueError("Invalid shop coordinates")
        if not (50.7 <= lat <= 53.7 and 3.2 <= lon <= 7.3):
            continue  # Only European Netherlands, not Caribbean territories.
        key = (element["type"], element["id"])
        if key not in seen:
            seen.add(key)
            shops.append({"type": element["type"], "id": element["id"],
                          "lat": lat, "lon": lon, "tags": element["tags"]})
    if not 1000 <= len(shops) <= 20000:
        raise ValueError("Incomplete national supermarket snapshot")
    if not any(52.375 <= s["lat"] <= 52.415 and 5.235 <= s["lon"] <= 5.305 for s in shops):
        raise ValueError("No Almere Buiten supermarket coverage")
    return shops

def fetch():
    previous = None
    try:
        request = urllib.request.Request("https://api.github.com/repos/Rubenvaggelen/apps/releases?per_page=100",
                                        headers={"User-Agent": "TheOneMain-offline-catalog/1.0"})
        with urllib.request.urlopen(request, timeout=20) as response:
            releases = json.load(response)
        for release in releases:
            if not release.get("tag_name", "").startswith("main-v") or release.get("draft"):
                continue
            asset = next((a for a in release.get("assets", [])
                          if a["name"] == "supermarkets-nl.json"), None)
            if asset is None:
                continue
            url = asset["browser_download_url"]
            if not url.startswith("https://github.com/Rubenvaggelen/apps/releases/download/"):
                raise ValueError("Unexpected snapshot download origin")
            with urllib.request.urlopen(url, timeout=30) as response:
                previous = json.loads(response.read(8 * 1024 * 1024))
            validate(previous)
            age = datetime.datetime.now(datetime.timezone.utc) - datetime.datetime.fromisoformat(previous["generated_at"])
            if datetime.timedelta(0) <= age <= datetime.timedelta(days=7):
                TARGET.parent.mkdir(parents=True, exist_ok=True)
                TARGET.write_text(json.dumps(previous, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
                print("Reused verified snapshot from", previous["generated_at"])
                return
            break
    except Exception as error:
        previous = None
        print("Previous snapshot unavailable:", type(error).__name__)
    for endpoint in ENDPOINTS:
        try:
            request = urllib.request.Request(endpoint,
                data=urllib.parse.urlencode({"data": QUERY}).encode(),
                headers={"User-Agent": "TheOneMain-offline-catalog/1.0",
                         "Content-Type": "application/x-www-form-urlencoded",
                         "Accept": "application/json"})
            with urllib.request.urlopen(request, timeout=100) as response:
                raw = response.read(8 * 1024 * 1024 + 1)
            if len(raw) > 8 * 1024 * 1024:
                raise ValueError("Oversized response")
            data = json.loads(raw)
            shops = validate(data)
            snapshot = {"elements": shops,
                "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                "osm_base": data.get("osm3s", {}).get("timestamp_osm_base"),
                "source": endpoint, "attribution": "© OpenStreetMap contributors",
                "license": "ODbL 1.0",
                "license_url": "https://www.openstreetmap.org/copyright"}
            TARGET.parent.mkdir(parents=True, exist_ok=True)
            TARGET.write_text(json.dumps(snapshot, ensure_ascii=False, separators=(",", ":")),
                              encoding="utf-8")
            print("Validated national snapshot:", len(shops), "shops; Almere Buiten covered.")
            return
        except Exception as error:
            print("Snapshot source failed:", endpoint, type(error).__name__, str(error)[:200])
    if previous is not None:
        TARGET.parent.mkdir(parents=True, exist_ok=True)
        TARGET.write_text(json.dumps(previous, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        print("Live snapshot sources unavailable; retained verified snapshot from", previous["generated_at"])
        return
    raise SystemExit("No validated supermarket snapshot available; release blocked.")

if __name__ == "__main__":
    fetch()
