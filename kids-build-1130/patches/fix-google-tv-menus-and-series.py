#!/usr/bin/env python3
"""Keep parent-approved Kids Google TV menus and catalog visible during feed failure."""
from pathlib import Path
import re

root=Path("kids-build-1130/buildroot/kids")
base=root/"src/main/java/com/gmailorg/kids"
path=base/"MainActivity.kt"
s=path.read_text(encoding="utf-8")

def once(old,new):
    global s
    assert s.count(old)==1, (old[:90],s.count(old))
    s=s.replace(old,new,1)

once('private fun visibleShows() = store.activeShows.filter { showItems(it).isNotEmpty() || "show_${it.key}" in loading }',
     'private fun visibleShows() = store.activeShows')
once('''    private fun available(): List<String> = tabs.keys.filter { key -> store.sectionVisible(key) }.filter { key ->
        when (key) {
            "series" -> visibleShows().isNotEmpty()
            else -> (store.familySources.isNotEmpty() || (store.xtream.complete && store.xtCategories.any { it.type == key }))
        }
    }''',
     '    private fun available(): List<String> = tabs.keys.filter { key -> store.sectionVisible(key) }')
once('if (avail.size > 1) for (key in avail) {','for (key in avail) {')
once('''                adapter.set(emptyList(), showItems(show))''',
'''                subRow.addView(pill("Opnieuw laden", color(R.color.card), color(R.color.ink), 18f) {
                    refresh()
                }.apply { setPadding(dp(18), dp(8), dp(18), dp(8)) })
                adapter.set(emptyList(), showItems(show))''')
# No misleading empty pane: offer the correct parent-controlled recovery path.
pattern=r'(?m)^        empty\.text = if \(avail\.isEmpty\(\)\).*?\n        else .*?\n'
replacement='''        empty.text = when {
            avail.isEmpty() -> "De ouder heeft alle onderdelen verborgen. Open ouderinstellingen met de PIN."
            tab == "series" && openShow != null ->
                "Voor ${openShow?.title} zijn nu geen afleveringen gevonden.\\n" +
                "Kies Opnieuw laden of vraag een ouder om de kinderbronnen te controleren."
            tab == "series" ->
                "Er zijn geen series zichtbaar. Een ouder kan de zichtbaarheid instellen."
            tab == "live" ->
                "Nog geen goedgekeurde Kinder-tv-zenders.\\nEen ouder kan de kinderbronnen instellen."
            tab == "vod" ->
                "Nog geen goedgekeurde Kids films.\\nEen ouder kan de kinderbronnen instellen."
            else -> "Er is hier nog niets te zien. Probeer opnieuw te laden."
        }
'''
s,n=re.subn(pattern,lambda m:replacement,s,count=1)
assert n==1, "missing empty message block"
once('''                Images.load(image, showItems(show).firstOrNull()?.thumb.orEmpty())
                title.text = show.title''',
'''                val preview = showItems(show).firstOrNull()?.thumb.orEmpty()
                Images.load(image, preview)
                if (preview.isBlank()) {
                    image.scaleType = ImageView.ScaleType.FIT_CENTER
                    image.setPadding(dp(24), dp(12), dp(24), dp(12))
                    image.setImageResource(R.drawable.the_one_logo)
                } else {
                    image.scaleType = ImageView.ScaleType.CENTER_CROP
                    image.setPadding(0, 0, 0, 0)
                }
                title.text = when {
                    showItems(show).isNotEmpty() -> show.title
                    "show_${show.key}" in loading -> "${show.title} - Laden..."
                    else -> "${show.title} - Nog geen afleveringen"
                }''')
once('''                val item = items[position]
                card.background''',
'''                val item = items[position]
                image.setPadding(0, 0, 0, 0)
                card.background''')
assert '"songs"' not in s and 'KidsSongs' not in s
path.write_text(s,encoding="utf-8")

tests=root/"src/test/java/com/gmailorg/kids"
tests.mkdir(parents=True,exist_ok=True)
(tests/"GoogleTvCatalogVisibilityTest.kt").write_text('''package com.gmailorg.kids
import org.junit.Assert.*
import org.junit.Test
class GoogleTvCatalogVisibilityTest {
    @Test fun allFiveApprovedBaseSeriesStayInCatalog(){
        assertEquals(listOf("Bluey","Dora","Oswald","Peppa Pig","PAW Patrol"),
            Catalog.shows.map { it.title })
    }
    @Test fun originalMenusRemainWithoutSongs(){
        assertEquals(setOf("learning","series","live","vod"),KidsSections.all)
        assertFalse("songs" in KidsSections.all)
    }
}
''',encoding="utf-8")
print("The One Kids Google TV: menu visibility restored; parental policies preserved.")
