import pathlib,re,sys
root=pathlib.Path(sys.argv[1])
family=root/'family-installaties.php'
text=family.read_text()
for kind,description in [('Portable','Direct starten zonder installatie; bestaande Studio-gegevens blijven behouden'),('Setup','Windows installatieprogramma; bijwerken met behoud van projecten en instellingen')]:
    pattern=r"(?m)^ \['Windows','The One Music Studio "+kind+r"',[^\n]+\],$"
    replacement=" ['Windows','The One Music Studio "+kind+"','v1.4.1','','','https://dev.rubenvanaggelen.com/downloads/studio/1.4.1','The-One-Music-Studio-1.4.1-"+kind+".exe','available','"+description+"'],"
    text,n=re.subn(pattern,lambda m:replacement,text)
    assert n==1,('Unexpected Studio row count',kind,n)
anchor="function linkFor(array $a): string {\n"
assert text.count(anchor)==1
text=text.replace(anchor,anchor+" if ($a[5]==='https://dev.rubenvanaggelen.com/downloads/studio/1.4.1' && in_array($a[6],['The-One-Music-Studio-1.4.1-Portable.exe','The-One-Music-Studio-1.4.1-Setup.exe'],true)) return $a[5].'/'.rawurlencode($a[6]);\n")
old='Voor The One Music Studio moeten Setup en Portable v1.4.0 nog worden gepubliceerd en moet clientlicentiecontrole worden afgerond.'
assert text.count(old)==1
text=text.replace(old,'The One Music Studio 1.4.1 is beschikbaar als Setup en Portable. Nieuwe installaties vragen toegang via The One Main; bestaande Studio-gegevens blijven behouden.')
family.write_text(text)
component=root/'windows-dj-studio-20261016.php'
text=component.read_text()
pattern=r"    \['name' => 'The One Studio / Music Maker • Windows', 'version' => 'v1\.1\.0',\n     'info' => 'Windows installatieprogramma \(\.exe\)',\n     'url' => 'https://github\.com/Rubenvaggelen/apps/releases/download/studio-v1\.1\.0/The-One-Studio-1\.1\.0-Windows\.exe'\]"
replacement="""    ['name' => 'The One Music Studio • Windows Setup', 'version' => 'v1.4.1',
     'info' => 'Installatieprogramma voor Windows 64-bit • projecten en instellingen blijven behouden',
     'url' => 'https://dev.rubenvanaggelen.com/downloads/studio/1.4.1/The-One-Music-Studio-1.4.1-Setup.exe'],
    ['name' => 'The One Music Studio • Windows Portable', 'version' => 'v1.4.1',
     'info' => 'Direct starten zonder installatie • toegang via The One Main',
     'url' => 'https://dev.rubenvanaggelen.com/downloads/studio/1.4.1/The-One-Music-Studio-1.4.1-Portable.exe']"""
text,n=re.subn(pattern,lambda m:replacement,text)
assert n==1,('Unexpected Windows Studio card',n)
assert "'The One DJ • Windows'" in text
component.write_text(text)
print('PASS: exactly two Studio catalogue rows and the old Studio download card patched; DJ unchanged')
