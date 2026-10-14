#!/usr/bin/env python3
"""Check customer, POS and backend menus before deploying Rutu BBQ."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
client = (root / "jadeorders/src/main/java/com/vanaggelen/jadeorders/MainActivity.kt").read_text()
pos_android = (root / "rutubusinessandroid/src/main/assets/rutu-pos.js").read_text()
pos_windows = (root / "rutu-windows/rutu-pos.js").read_text()
backend = (root / "rutu-server/index.php").read_text()
windows_client = (root / "rutu-windows/customer.html").read_text()
ios_client = (root / "rutu-ios/Sources/Models.swift").read_text()
assert pos_windows == pos_android, "Cash register menus differ across platforms"
products = [
    ("BBQ Regular", "10.00", "10"),
    ("BBQ Extra", "15.00", "15"),
    ("BBQ Family", "25.00", "25"),
    ("Extra bout", "6.00", "6"),
    ("Extra salade", "2.50", "2.5"),
    ("Portie saté", "7.00", "7"),
]
for name, price, short_price in products:
    assert f'Product("{name}", {price}' in client, f'Android customer: {name}'
    assert f"name:'{name}',price:{price}" in pos_android, f'Business POS: {name}'
    assert f"'{name}' => {price}" in backend, f'Server catalog: {name}'
    assert f"['{name}',{short_price}," in windows_client, f'Windows customer: {name}'
    assert f'name: "{name}", price: {price}' in ios_client, f'iOS catalog: {name}'
assert '2 bouten • 2 stokjes saté • salade' in client
assert "2 bouten · 2 stokjes saté · salade" in pos_android
assert '2 bouten • 2 stokjes saté • salade' in windows_client
assert '2 bouten • 2 stokjes saté • salade' in ios_client
assert 'BBQ Extra' in pos_android and "photo:'menu_bbq_extra.webp'" in pos_android
assets = [
    root / "jadeorders/src/main/res/drawable-nodpi/menu_bbq_extra.webp",
    root / "rutubusinessandroid/src/main/assets/menu/menu_bbq_extra.webp",
    root / "rutu-windows/assets/menu/menu_bbq_extra.webp",
]
image = assets[0].read_bytes()
assert len(image) >= 3000 and image[:4] == b'RIFF' and image[8:12] == b'WEBP', "Invalid BBQ Extra photo"
assert all(path.read_bytes() == image for path in assets), "Different BBQ Extra photos between apps"
assert all('scheduleDayFrom' not in (root / p).read_text() for p in [
    'rutubusinessandroid/src/main/assets/business.html',
    'rutu-windows/business.html',
]), "Old opening-time control returned"
print("PASS: identical prices across apps and server, latest photo, correct satay quantities and days-only schedules")
