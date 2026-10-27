#!/usr/bin/env python3
"""Apply the scoped CoComelon NL Kids Series update to the approved 1130 baseline.
No third-party media is redistributed: existing official YouTube playback is reused.
"""
from pathlib import Path

project = Path("kids-build-1130/buildroot/kids")
java = project / "src/main/java/com/gmailorg/kids"
tests = project / "src/test/java/com/gmailorg/kids"

def replace_once(path: Path, old: str, new: str):
    data = path.read_text(encoding="utf-8")
    assert data.count(old) == 1, f"Unexpected source baseline: {path.name}: {old!r}"
    path.write_text(data.replace(old, new, 1), encoding="utf-8")

replace_once(
    java / "Catalog.kt",
    '        Show("paw", "PAW Patrol", "#FF163956",',
    '        Show("cocomelon", "CoComelon", "#FF163956",\n'
    '            mapOf("nl" to "UCT3w8xSeYPl1gFnkPwDV_dA"),\n'
    '            Regex("\\\\bcocomelon\\\\b|\\\\bco[ -]?co[ -]?melon\\\\b")),\n'
    '        Show("paw", "PAW Patrol", "#FF163956",'
)
# Historical Kids 1130 source still included a songs tab. Keep the user's
# newer explicit preference: songs are not displayed as a Kids navigation tab.
replace_once(
    java / "KidsSections.kt",
    '        "vod" to "🎬 Kids films",\n        "songs" to "🎵 Kinderliedjes"',
    '        "vod" to "🎬 Kids films"'
)
replace_once(
    java / "MainActivity.kt",
    'private val tabs = linkedMapOf("series" to "Series", "live" to "Kinder-tv", "vod" to "Kids films", "songs" to "🎵 Kinderliedjes")',
    'private val tabs = linkedMapOf("series" to "Series", "live" to "Kinder-tv", "vod" to "Kids films")'
)
replace_once(
    java / "MainActivity.kt",
    'private val tabColors = mapOf("series" to R.color.berry, "live" to R.color.sky, "vod" to R.color.leaf, "songs" to R.color.sun)',
    'private val tabColors = mapOf("series" to R.color.berry, "live" to R.color.sky, "vod" to R.color.leaf)'
)
replace_once(
    java / "MainActivity.kt",
    '        // De vaste Nederlandstalige liedjes en goedgekeurde playlists zijn altijd beschikbaar.\n'
    '        lists["songs"] = KidsSongs.merge(KidsSongs.featured(), KidItem.listFrom(store.cache("songs")))\n',
    ''
)
replace_once(java / "MainActivity.kt", '        load("songs") { KidsSongs.fetch() }\n', '')
replace_once(java / "MainActivity.kt", '            "songs" -> true  // Ook zonder films of tv-bronnen zichtbaar.\n', '')

tests.mkdir(parents=True, exist_ok=True)
(tests / "CoComelonSeriesTest.kt").write_text('''package com.gmailorg.kids

import org.junit.Assert.*
import org.junit.Test

class CoComelonSeriesTest {
    private val show = Catalog.byKey("cocomelon")!!

    @Test fun onlyDutchOfficialChannelForKidsSeries() {
        assertEquals("CoComelon", show.title)
        assertEquals("UCT3w8xSeYPl1gFnkPwDV_dA", show.channelFor("nl"))
        assertEquals(listOf("UCT3w8xSeYPl1gFnkPwDV_dA"), show.preferredSources())
    }

    @Test fun matchingAndExistingTabPreferences() {
        assertTrue(show.matches("CoComelon - De wielen van de bus"))
        assertTrue(show.matches("NL | CoComelon S01E03"))
        assertTrue(show.matches("Coco Melon Nederlandse afleveringen"))
        assertFalse(show.matches("Random cartoon"))
        assertTrue("series" in KidsSections.all)
        assertFalse("songs" in KidsSections.all)
    }

    @Test fun dutchContentDoesNotSwitchToEnglish() {
        val called = mutableListOf<String>()
        val items = YouTubeSource.preferredVideos(show) { source, group ->
            called.add(source)
            assertEquals("CoComelon", group)
            listOf(KidItem("yt", "SZFBVot9eys", "CoComelon Nederlands", ""))
        }
        assertEquals(listOf("UCT3w8xSeYPl1gFnkPwDV_dA"), called)
        assertEquals("SZFBVot9eys", items!!.single().id)
    }
}
''', encoding="utf-8")
print("Kids 1131 CoComelon NL source overlay ready")
