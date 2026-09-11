from pathlib import Path
import re

root = Path.cwd()
if not (root / 'app/src/main').exists():
    raise SystemExit('❌ Run dit script vanuit de root van je project (bijv. /workspaces/apps).')

res = root / 'app/src/main/res'
drawable = res / 'drawable'
java_file = root / 'app/src/main/java/com/gmailorg/hub/HomeActivity.kt'

drawable.mkdir(parents=True, exist_ok=True)

notifications_xml = r'''<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- luxe gouden buitenring -->
    <item>
        <shape android:shape="oval">
            <gradient
                android:angle="315"
                android:startColor="#F7D98B"
                android:centerColor="#D89A3A"
                android:endColor="#8E5B14" />
        </shape>
    </item>
    <!-- donkergroene kern -->
    <item android:left="3dp" android:top="3dp" android:right="3dp" android:bottom="3dp">
        <shape android:shape="oval">
            <gradient
                android:angle="315"
                android:startColor="#2D5A43"
                android:endColor="#10251C" />
            <stroke android:width="1dp" android:color="#F4D08A" />
        </shape>
    </item>
    <!-- gouden bel -->
    <item android:width="31dp" android:height="31dp" android:gravity="center">
        <vector
            android:width="31dp"
            android:height="31dp"
            android:viewportWidth="24"
            android:viewportHeight="24">
            <path
                android:fillColor="#FFF1C7"
                android:strokeColor="#E0A458"
                android:strokeWidth="0.65"
                android:pathData="M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.9,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5S10.5,3.17 10.5,4v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z" />
        </vector>
    </item>
    <!-- klein luxe notificatiepunt -->
    <item android:width="10dp" android:height="10dp" android:gravity="top|right" android:top="4dp" android:right="4dp">
        <shape android:shape="oval">
            <solid android:color="#D94B3D" />
            <stroke android:width="1dp" android:color="#FFF1C7" />
        </shape>
    </item>
</layer-list>
'''

route_xml = r'''<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- luxe gouden buitenring -->
    <item>
        <shape android:shape="oval">
            <gradient
                android:angle="315"
                android:startColor="#F7D98B"
                android:centerColor="#D89A3A"
                android:endColor="#8E5B14" />
        </shape>
    </item>
    <!-- donkergroene kern -->
    <item android:left="3dp" android:top="3dp" android:right="3dp" android:bottom="3dp">
        <shape android:shape="oval">
            <gradient
                android:angle="315"
                android:startColor="#2D5A43"
                android:endColor="#10251C" />
            <stroke android:width="1dp" android:color="#F4D08A" />
        </shape>
    </item>
    <!-- route / navigatiepijl -->
    <item android:width="32dp" android:height="32dp" android:gravity="center">
        <vector
            android:width="32dp"
            android:height="32dp"
            android:viewportWidth="24"
            android:viewportHeight="24">
            <path
                android:fillColor="#FFF1C7"
                android:strokeColor="#E0A458"
                android:strokeWidth="0.55"
                android:pathData="M21.71,11.29l-9,-9a1,1 0,0 0,-1.42 0l-9,9a1,1 0,0 0,0 1.42l9,9a1,1 0,0 0,1.42 0l9,-9a1,1 0,0 0,0 -1.42zM14,14.5V12h-4v3H8v-4a1,1 0,0 1,1 -1h5V7.5l3.5,3.5z" />
        </vector>
    </item>
</layer-list>
'''

add_xml = r'''<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item>
        <shape android:shape="oval">
            <gradient
                android:angle="315"
                android:startColor="#F7D98B"
                android:centerColor="#D89A3A"
                android:endColor="#8E5B14" />
        </shape>
    </item>
    <item android:left="3dp" android:top="3dp" android:right="3dp" android:bottom="3dp">
        <shape android:shape="oval">
            <gradient
                android:angle="315"
                android:startColor="#2D5A43"
                android:endColor="#10251C" />
            <stroke android:width="1dp" android:color="#F4D08A" />
        </shape>
    </item>
    <item android:width="31dp" android:height="31dp" android:gravity="center">
        <vector
            android:width="31dp"
            android:height="31dp"
            android:viewportWidth="24"
            android:viewportHeight="24">
            <path
                android:fillColor="#FFF1C7"
                android:pathData="M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" />
        </vector>
    </item>
</layer-list>
'''

(drawable / 'ic_home_notifications_fancy.xml').write_text(notifications_xml, encoding='utf-8')
(drawable / 'ic_home_route_fancy.xml').write_text(route_xml, encoding='utf-8')
(drawable / 'ic_home_add_fancy.xml').write_text(add_xml, encoding='utf-8')

text = java_file.read_text(encoding='utf-8')

# Add imports needed for a consistent fancy frame around externally added app icons.
imports = [
    'import android.content.Context',
    'import android.graphics.Color',
    'import android.graphics.drawable.Drawable',
    'import android.graphics.drawable.GradientDrawable',
    'import android.graphics.drawable.InsetDrawable',
    'import android.graphics.drawable.LayerDrawable',
]
for imp in imports:
    if imp not in text:
        text = text.replace('import android.content.Intent\n', 'import android.content.Intent\n' + imp + '\n', 1)

text = text.replace(
    'TileType.NOTIFICATIONS -> ContextCompat.getDrawable(context, R.drawable.ic_tile_notifications)',
    'TileType.NOTIFICATIONS -> ContextCompat.getDrawable(context, R.drawable.ic_home_notifications_fancy)'
)
text = text.replace(
    'TileType.ROUTE -> ContextCompat.getDrawable(context, R.drawable.ic_tile_route)',
    'TileType.ROUTE -> ContextCompat.getDrawable(context, R.drawable.ic_home_route_fancy)'
)

old_app_block = '''            TileType.APP -> try {
                context.packageManager.getApplicationIcon(tile.packageName!!)
            } catch (e: Exception) {
                ContextCompat.getDrawable(context, R.drawable.ic_home_add_fancy)
            }'''
new_app_block = '''            TileType.APP -> try {
                makeFancyExternalAppIcon(context, context.packageManager.getApplicationIcon(tile.packageName!!))
            } catch (e: Exception) {
                ContextCompat.getDrawable(context, R.drawable.ic_home_add_fancy)
            }'''
if old_app_block in text:
    text = text.replace(old_app_block, new_app_block)
elif 'makeFancyExternalAppIcon(context,' not in text:
    raise SystemExit('❌ Kon het APP-icoonblok in HomeActivity.kt niet veilig vinden.')

helper = r'''
    /**
     * Geeft zelf toegevoegde apps dezelfde The One-uitstraling zonder hun
     * herkenbare eigen logo kwijt te raken: donkergroen, goud en een nette inset.
     */
    private fun makeFancyExternalAppIcon(context: Context, appIcon: Drawable): Drawable {
        fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

        val outer = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#F7D98B"),
                Color.parseColor("#D89A3A"),
                Color.parseColor("#8E5B14")
            )
        ).apply {
            shape = GradientDrawable.OVAL
        }

        val inner = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#2D5A43"),
                Color.parseColor("#10251C")
            )
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), Color.parseColor("#F4D08A"))
        }

        val innerInset = InsetDrawable(inner, dp(3))
        val logoInset = InsetDrawable(appIcon.mutate(), dp(11))
        return LayerDrawable(arrayOf(outer, innerInset, logoInset))
    }
'''

if 'private fun makeFancyExternalAppIcon' not in text:
    marker = '\n    override fun onBindViewHolder(holder: ViewHolder, position: Int) {'
    if marker not in text:
        raise SystemExit('❌ Kon HomeAdapter niet veilig aanpassen.')
    text = text.replace(marker, helper + marker, 1)

java_file.write_text(text, encoding='utf-8')

print('✅ Alle home-icoontjes gelijkgetrokken naar de fancy The One-stijl.')
print('✅ Meldingen: luxe gouden bel op donkergroen')
print('✅ Route: luxe gouden navigatie-icoon op donkergroen')
print('✅ App toevoegen: luxe goud/groen plus-icoon')
print('✅ Zelf toegevoegde apps: automatisch goud/groen frame rondom hun eigen logo')
print('✅ Bestaande fancy iconen (Mail, Huishouden, Films, Parkeren, enz.) blijven behouden')
