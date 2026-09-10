from pathlib import Path
import shutil, sys

root = Path.cwd()
patch = Path(__file__).resolve().parent

home = root / 'app/src/main/java/com/gmailorg/hub/HomeActivity.kt'
layout = root / 'app/src/main/res/layout/item_home_tile.xml'
if not home.exists() or not layout.exists():
    print('ERROR: run dit vanuit de root van je GitHub-project (waar de map app/ staat).')
    sys.exit(1)

# Copy resources
nodpi_dst = root / 'app/src/main/res/drawable-nodpi'
drawable_dst = root / 'app/src/main/res/drawable'
nodpi_dst.mkdir(parents=True, exist_ok=True)
drawable_dst.mkdir(parents=True, exist_ok=True)
for f in (patch / 'app/src/main/res/drawable-nodpi').glob('*.png'):
    shutil.copy2(f, nodpi_dst / f.name)
shutil.copy2(patch / 'app/src/main/res/drawable/ic_home_add_fancy.xml', drawable_dst / 'ic_home_add_fancy.xml')

# Only replace home-tile icon references. Existing notification icons remain untouched.
text = home.read_text(encoding='utf-8')
repl = {
    'R.drawable.ic_tile_mail':'R.drawable.ic_home_mail_fancy',
    'R.drawable.ic_tile_household':'R.drawable.ic_home_household_fancy',
    'R.drawable.ic_tile_movies':'R.drawable.ic_home_movies_fancy',
    'R.drawable.ic_tile_parking':'R.drawable.ic_home_parking_fancy',
    'R.drawable.ic_tile_settings':'R.drawable.ic_home_settings_fancy',
    'R.drawable.ic_tile_ask':'R.drawable.ic_home_ask_fancy',
    'R.drawable.ic_tile_recipes':'R.drawable.ic_home_recipes_fancy',
    'R.drawable.ic_tile_news':'R.drawable.ic_home_news_fancy',
    'R.drawable.ic_tile_radio':'R.drawable.ic_home_radio_fancy',
    'R.drawable.ic_tile_currency':'R.drawable.ic_home_currency_fancy',
    'R.drawable.ic_tile_add':'R.drawable.ic_home_add_fancy',
}
for old,new in repl.items():
    text = text.replace(old,new)
home.write_text(text,encoding='utf-8')

# Give the detailed icons a little more room on the dashboard.
text = layout.read_text(encoding='utf-8')
text = text.replace('android:layout_width="48dp"\n        android:layout_height="48dp"',
                    'android:layout_width="56dp"\n        android:layout_height="56dp"')
if 'android:id="@+id/tileIcon"' in text and 'android:scaleType="centerInside"' not in text:
    text = text.replace('android:layout_marginBottom="10dp"',
                        'android:layout_marginBottom="8dp"\n        android:scaleType="centerInside"')
layout.write_text(text,encoding='utf-8')

print('Fancy home-iconen toegepast.')
print('Aangepast: HomeActivity.kt + item_home_tile.xml + 11 nieuwe drawable resources.')
