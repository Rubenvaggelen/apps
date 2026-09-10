from pathlib import Path
import re

layout = Path('app/src/main/res/layout/activity_movies.xml')
kotlin = Path('app/src/main/java/com/gmailorg/hub/MoviesActivity.kt')

if not layout.exists() or not kotlin.exists():
    raise SystemExit('❌ Voer dit script uit vanuit de root van je GitHub-project (waar de map app/ staat).')

xml = layout.read_text(encoding='utf-8')
kt = kotlin.read_text(encoding='utf-8')

# 1) Teksten van hoofdzoekbalk universeel maken.
xml = xml.replace(
    'android:text="Zoek een film of serie en bekijk waar die beschikbaar is."',
    'android:text="Zoek films, series of muziek met één zoekbalk."'
)
xml = xml.replace(
    'android:hint="Bijv. Fight Club of Breaking Bad"',
    'android:hint="Film, serie, nummer of artiest..."'
)

# 2) Verwijder de aparte muziek-heading + uitleg + muziekzoekbalk.
# We verwijderen alles vanaf de muziek-heading tot vlak vóór musicResultContainer.
music_block = re.compile(
    r'''\n\s*<TextView\n\s*android:layout_width="match_parent"\n\s*android:layout_height="wrap_content"\n\s*android:paddingHorizontal="16dp"\n\s*android:paddingBottom="8dp"\n\s*android:text="Muziek zoeken".*?\n\s*</LinearLayout>\n\n\s*(?=<LinearLayout\n\s*android:id="@\+id/musicResultContainer")''',
    re.S
)
xml2, n = music_block.subn('\n\n            ', xml, count=1)
if n == 0:
    # Fallback voor als layout intussen iets gewijzigd is: verwijder elementen op basis van IDs.
    # Verwijder specifieke musicTitleInput parent LinearLayout plus de twee teksten ervoor.
    parent_pat = re.compile(
        r'\n\s*<LinearLayout(?=[^>]*>).*?<EditText\s+android:id="@\+id/musicTitleInput".*?</LinearLayout>',
        re.S
    )
    xml2, pn = parent_pat.subn('', xml, count=1)
    if pn:
        # Heading/uitleg optioneel opruimen.
        xml2 = re.sub(r'\n\s*<TextView(?=[^>]*android:text="Muziek zoeken")[\s\S]*?/>', '', xml2, count=1)
        xml2 = re.sub(r'\n\s*<TextView(?=[^>]*android:text="Typ een nummer of artiest en het wordt op YouTube opgezocht\.")[\s\S]*?/>', '', xml2, count=1)
    else:
        raise SystemExit('❌ Kon de aparte muziekzoekbalk niet vinden. Stuur activity_movies.xml als je layout inmiddels anders is.')
else:
    xml = xml2

# Zorg dat muziekresultaten dichter bij de filmresultaten staan en niet onnodig extra onderaan.
xml = xml.replace('android:paddingBottom="24dp"\n                android:visibility="gone"',
                  'android:paddingBottom="16dp"\n                android:visibility="gone"')
layout.write_text(xml, encoding='utf-8')

# 3) onCreate: hoofdzoekknop gebruikt searchAll; aparte muziek-input/button verwijderen.
kt = kt.replace('searchButton.setOnClickListener { searchMovie() }',
                'searchButton.setOnClickListener { searchAll() }')
kt = kt.replace('                searchMovie()\n                true',
                '                searchAll()\n                true')

old_music_setup = re.compile(
    r'''\n\s*musicResultContainer = findViewById\(R\.id\.musicResultContainer\)\n\s*val musicInput = findViewById<EditText>\(R\.id\.musicTitleInput\)\n\s*findViewById<View>\(R\.id\.musicSearchButton\)\.setOnClickListener \{ searchMusic\(musicInput\.text\.toString\(\)\) \}\n\s*musicInput\.setOnEditorActionListener \{ _, actionId, _ ->\n\s*if \(actionId == EditorInfo\.IME_ACTION_SEARCH\) \{\n\s*searchMusic\(musicInput\.text\.toString\(\)\)\n\s*true\n\s*\} else \{\n\s*false\n\s*\}\n\s*\}''',
    re.S
)
replacement = '\n\n        musicResultContainer = findViewById(R.id.musicResultContainer)'
kt2, n = old_music_setup.subn(replacement, kt, count=1)
if n == 0:
    # Als er al deels gewijzigd is, verwijder alleen bekende regels/blok.
    kt2 = kt
    kt2 = re.sub(r'\n\s*val musicInput = findViewById<EditText>\(R\.id\.musicTitleInput\)[\s\S]*?\n\s*\}\n\s*\}', '', kt2, count=1)
    if 'musicResultContainer = findViewById(R.id.musicResultContainer)' not in kt2:
        anchor = 'upcomingContainer = findViewById(R.id.upcomingContainer)'
        kt2 = kt2.replace(anchor, anchor + '\n        musicResultContainer = findViewById(R.id.musicResultContainer)')
kt = kt2

# 4) Voeg searchAll toe vóór searchMusic als die nog niet bestaat.
if 'private fun searchAll()' not in kt:
    marker = '    private fun searchMusic(query: String) {'
    if marker not in kt:
        raise SystemExit('❌ searchMusic() niet gevonden in MoviesActivity.kt')
    search_all = '''    private fun searchAll() {
        val query = titleInput.text.toString().trim()
        if (query.isBlank()) return

        // Eén zoekopdracht voor films/series én muziek.
        resultContainer.removeAllViews()
        musicResultContainer.removeAllViews()
        resultContainer.visibility = View.VISIBLE
        musicResultContainer.visibility = View.VISIBLE

        addResultLine("Films & series", bold = true)
        addResultLine("Zoeken naar “$query”…", dim = true)
        addMusicLine("Muziek", dim = false)
        addMusicLine("Zoeken naar “$query”…", dim = true)

        MovieLookup.search(query) { outcome ->
            resultContainer.removeAllViews()
            addResultLine("Films & series", bold = true)
            when (outcome) {
                is MovieLookup.LookupOutcome.Success -> showMovieResult(outcome.result)
                is MovieLookup.LookupOutcome.NotFound ->
                    addResultLine("Geen film of serie gevonden voor “${outcome.query}”.", dim = true)
                is MovieLookup.LookupOutcome.Error ->
                    addResultLine(outcome.message, dim = true)
            }
        }

        MusicLookup.search(query) { outcome ->
            musicResultContainer.removeAllViews()
            addMusicLine("Muziek")
            when (outcome) {
                is MusicLookup.LookupOutcome.Success -> showMusicResults(outcome.results)
                is MusicLookup.LookupOutcome.NotFound ->
                    addMusicLine("Geen muziek gevonden voor “${outcome.query}”.", dim = true)
                is MusicLookup.LookupOutcome.Error ->
                    addMusicLine(outcome.message, dim = true)
            }
        }
    }

'''
    kt = kt.replace(marker, search_all + marker, 1)

kotlin.write_text(kt, encoding='utf-8')
print('✅ Eén universele zoekbalk toegepast.')
print('✅ De aparte muziekzoekbalk is verwijderd.')
print('✅ De hoofdzoekbalk zoekt nu tegelijk naar films, series en muziek.')
