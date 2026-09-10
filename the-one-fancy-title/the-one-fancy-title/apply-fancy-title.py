from pathlib import Path
import re

root = Path('app/src/main')
strings = root / 'res/values/strings.xml'
home = root / 'res/layout/activity_home.xml'

if not strings.exists():
    raise SystemExit(f'Niet gevonden: {strings}')
if not home.exists():
    raise SystemExit(f'Niet gevonden: {home}')

# Forceer de zichtbare appnaam terug naar The One.
s = strings.read_text(encoding='utf-8')
s2, n = re.subn(r'(<string\s+name="app_name"[^>]*>).*?(</string>)', r'\1The One\2', s, count=1, flags=re.S)
if n == 0:
    raise SystemExit('app_name kon niet worden gevonden in strings.xml')
strings.write_text(s2, encoding='utf-8')

# Style alleen de titel-TextView op het homescreen.
x = home.read_text(encoding='utf-8')
pattern = re.compile(r'<TextView\b(?=[^>]*android:text="@string/app_name")[^>]*/>', re.S)
m = pattern.search(x)
if not m:
    raise SystemExit('Homescreen titel-TextView kon niet worden gevonden.')
block = m.group(0)

attrs = {
    'android:textColor': '@color/amber',
    'android:textSize': '28sp',
    'android:textStyle': 'bold',
    'android:fontFamily': 'serif',
    'android:letterSpacing': '0.06',
    'android:shadowColor': '#80E0A458',
    'android:shadowDx': '0',
    'android:shadowDy': '1',
    'android:shadowRadius': '7',
}

for key, value in attrs.items():
    rx = re.compile(rf'\s+{re.escape(key)}="[^"]*"')
    if rx.search(block):
        block = rx.sub(f'\n                {key}="{value}"', block, count=1)
    else:
        block = block[:-2] + f'\n                {key}="{value}" />'

x = x[:m.start()] + block + x[m.end():]
home.write_text(x, encoding='utf-8')

print('Klaar: appnaam = The One')
print('Klaar: homescreen titel = luxe goud met subtiele glow')
print('Signing/buildconfig niet aangepast.')
