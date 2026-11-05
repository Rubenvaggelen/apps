#!/usr/bin/env python3
"""Create distinct The One Kids Google TV sources, without any songs feature."""
from pathlib import Path
import re

root=Path("kids-build-1130/buildroot/kids")
java=root/"src/main/java/com/gmailorg/kids"
tests=root/"src/test/java/com/gmailorg/kids"

def edit(rel,old,new):
 p=rel if isinstance(rel,Path) else java/rel
 s=p.read_text(encoding="utf-8")
 assert s.count(old)==1,(str(p),old[:90],s.count(old))
 p.write_text(s.replace(old,new,1),encoding="utf-8")

def cut_line(rel,pattern):
 p=java/rel
 s=p.read_text(encoding="utf-8")
 t,n=re.subn(pattern,"",s,count=1,flags=re.M)
 assert n==1,(str(p),pattern)
 p.write_text(t,encoding="utf-8")

# Distinct package and proper TV launcher. Parent approval MUST still be enforced.
edit(root/"build.gradle.kts",'applicationId = "com.gmailorg.kids"',
    'applicationId = "com.gmailorg.kids.tv"')
edit(root/"build.gradle.kts",'buildFeatures { buildConfig = true }',
    'buildFeatures { buildConfig = true }\n    defaultConfig { buildConfigField("boolean", "GOOGLE_TV", "true") }')
edit("KidsInstallAccess.kt",'private const val PACKAGE = "com.gmailorg.kids"',
    'private const val PACKAGE = "com.gmailorg.kids.tv"')
manifest=root/"src/main/AndroidManifest.xml"
m=manifest.read_text(encoding="utf-8")
assert 'android.intent.category.LAUNCHER' in m
m=m.replace('<uses-feature android:name="android.hardware.touchscreen" android:required="false" />',
    '<uses-feature android:name="android.hardware.touchscreen" android:required="false" />\n'
    '    <uses-feature android:name="android.software.leanback" android:required="true" />',1)
m=m.replace('android:label="The One Kids"',
            'android:label="The One Kids TV"\n        android:banner="@drawable/the_one_logo"',1)
m=m.replace('android.intent.category.LAUNCHER','android.intent.category.LEANBACK_LAUNCHER',1)
m=m.replace('android:launchMode="singleTask"','android:launchMode="singleTask"\n            android:screenOrientation="landscape"',1)
for activity in ('.learn.LearnActivity','.learn.GamesActivity'):
 m=m.replace('android:name="'+activity+'" android:exported="false"',
             'android:name="'+activity+'" android:screenOrientation="landscape" android:exported="false"',1)
manifest.write_text(m,encoding="utf-8")

# Remove all songs navigation, retrieval, and bundled songs catalog.
p=java/"KidsSections.kt";s=p.read_text(encoding="utf-8")
s,n=re.subn(r'(?m)^(\s*"vod" to "[^"\n]+"),\r?\n\s*"songs" to "[^"\n]+"\r?\n',r'\1\n',s,count=1)
assert n==1
p.write_text(s,encoding="utf-8")
p=java/"MainActivity.kt";s=p.read_text(encoding="utf-8")
patterns=[
 (r', "songs" to "[^"\n]*Kinderliedjes"',""),
 (r', "songs" to R\.color\.sun',""),
 (r'(?m)^\s*// De vaste Nederlandstalige liedjes[^\n]*\n',""),
 (r'(?m)^\s*lists\["songs"\] = KidsSongs[^\n]*\n',""),
 (r'(?m)^\s*load\("songs"\) \{ KidsSongs\.fetch\(\) \}\n',""),
 (r'(?m)^\s*"songs" -> true[^\n]*\n',""),
 (r'(?m)^\s*private lateinit var songSearch: EditText\n',""),
 (r'(?m)^\s*songSearch\.visibility = View\.GONE\n',""),
 (r'(?m)^\s*songSearch\.visibility = if \(tab == "songs"\) View\.VISIBLE else View\.GONE\n',""),
]
for pat,value in patterns:
 s,n=re.subn(pat,value,s,count=1)
 assert n==1,("MainActivity",pat)
s=s.replace('lists[key] = if (key == "songs") KidsSongs.merge(KidsSongs.featured(), items.orEmpty()) else items.orEmpty()',
            'lists[key] = items.orEmpty()',1)
s=s.replace('val query = if (tab == "songs") songSearch.text.toString().trim() else ""','val query = ""',1)
start=s.index('        songSearch = input(')
end=s.index('        col.addView(songSearch,',start)
end=s.index('\n',end)+1
s=s[:start]+s[end:]
assert 'songSearch' not in s and 'KidsSongs' not in s and '"songs"' not in s
# Make focus visible on TV and land first on learning tile.
s=s.replace('selector = rounded(Color.TRANSPARENT, 0f)',
            'selector = rounded(Color.argb(110,86,191,255),dp(12).toFloat(),dp(3),Color.WHITE)',1)
needle='        col.addView(learningButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })'
assert needle in s
s=s.replace(needle,needle+'\n        root.post { if (learningButton.visibility == View.VISIBLE) learningButton.requestFocus() else grid.requestFocus() }',1)
p.write_text(s,encoding="utf-8")
(java/"KidsSongs.kt").unlink()

# TV has its own (not the phone's) release lane. Prevent wrong-package OTA.
edit("KidsUpdater.kt",'    fun onResume() {\n',
    '    fun onResume() {\n        if (BuildConfig.GOOGLE_TV) return\n')
edit("KidsUpdater.kt",'    fun check(manual: Boolean) {\n',
    '    fun check(manual: Boolean) {\n        if (BuildConfig.GOOGLE_TV) {\n            if (manual) toast("Google TV updates komen afzonderlijk via de Dev Hub.")\n            return\n        }\n')

# Remote-friendly alternatives for touch-only drawing and clock tasks.
game="learn/GamesActivity.kt"
needle='private fun trace(letterIndex:Int=0){screen("Letters en cijfers tekenen","trace");'
tv='''private fun trace(letterIndex:Int=0){
        if(packageManager.hasSystemFeature("android.software.leanback")){
            val letters="ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val i=(letterIndex+letters.length)%letters.length
            val answer=letters[i].toString()
            screen("Letters en cijfers herkennen","trace")
            add(txt("Welke letter of welk cijfer zie je?",24f))
            add(txt(answer,96f,0xFFFFD56A.toInt()))
            val feedback=txt("Kies met de pijltjes en OK.",18f,soft)
            listOf(answer,letters[(i+4)%letters.length].toString(),letters[(i+9)%letters.length].toString())
                .shuffled().forEach{choice->add(btn(choice,card){
                    if(choice==answer)celebrate("trace"){trace(i+1)}
                    else feedback.text="Probeer het nog eens."
                }}
            add(feedback)
            add(btn("Volgende letter",card){trace(i+1)})
            return
        }
        screen("Letters en cijfers tekenen","trace");'''
edit(game,needle,tv)
needle='val board=ClockBoard(this);col.addView(board,LinearLayout.LayoutParams(-1,d(300)))'
tv='''val board=ClockBoard(this);col.addView(board,LinearLayout.LayoutParams(-1,d(300)))
        if(packageManager.hasSystemFeature("android.software.leanback")){
            add(btn("Uur -",card){board.hour=if(board.hour<=1)12 else board.hour-1;board.touched=true;board.invalidate()})
            add(btn("Uur +",card){board.hour=if(board.hour>=12)1 else board.hour+1;board.touched=true;board.invalidate()})
            add(btn("Minuten -5",card){board.minute=(board.minute+55)%60;board.touched=true;board.invalidate()})
            add(btn("Minuten +5",card){board.minute=(board.minute+5)%60;board.touched=true;board.invalidate()})
        }'''
edit(game,needle,tv)
edit(game,'setBackgroundColor(card)};board.setOnClickListener',
     'background=com.gmailorg.kids.OneTheme.tile(this@GamesActivity);isFocusable=true};board.setOnClickListener')
edit(game,'val tile=ImageView(this).apply{setBackgroundColor(card);contentDescription=',
     'val tile=ImageView(this).apply{setBackgroundColor(card);isFocusable=true;setOnFocusChangeListener{v,focused->v.alpha=if(focused)0.7f else 1f};contentDescription=')
edit(game,'add(txt("Tik op een vorm en daarna op het passende vak. Je mag de vorm ook slepen.",20f))',
     'add(txt("Kies een vorm en daarna het passende vak met OK.",20f))')
edit(game,'add(txt("Maak het voorbeeld na. Tik op een stukje naast het lege vak.",19f))',
     'add(txt("Maak het voorbeeld na. Kies een stukje naast het lege vak met OK.",19f))')

tests.mkdir(parents=True,exist_ok=True)
(tests/"GoogleTvNoSongsTest.kt").write_text('''package com.gmailorg.kids
import org.junit.Test
import org.junit.Assert.*
class GoogleTvNoSongsTest {
 @Test fun keptLearningGamesAndApprovedMediaWithoutSongs(){
  assertTrue("learning" in KidsSections.all)
  assertTrue("series" in KidsSections.all)
  assertTrue("live" in KidsSections.all)
  assertTrue("vod" in KidsSections.all)
  assertFalse("songs" in KidsSections.all)
  assertEquals(4,KidsSections.all.size)
 }
}
''',encoding="utf-8")
assert not (java/"KidsSongs.kt").exists()
print("Kids TV overlay applied: separate app, leanback remote, learning/games, no songs")
