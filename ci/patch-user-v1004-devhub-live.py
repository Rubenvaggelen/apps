#!/usr/bin/env python3
"""Update only versioned User download references in current live Dev Hub pages."""
from pathlib import Path
import sys
if len(sys.argv)!=7:
    raise SystemExit("usage: script BUILDS_IN INDEX_IN FAMILY_IN BUILDS_OUT INDEX_OUT FAMILY_OUT")
old_asset="https://github.com/Rubenvaggelen/apps/releases/download/user-v1003/The-One-Android-User.apk"
new_asset="https://github.com/Rubenvaggelen/apps/releases/download/user-v1004/The-One-Android-User.apk"
old_family="'The One Gebruiker','v1003','user-v'"
new_family="'The One Gebruiker','v1004','user-v'"
old_fallback="'The-One-Android-User.apk','user-v1003','The-One-Android-User.apk'"
new_fallback="'The-One-Android-User.apk','user-v1004','The-One-Android-User.apk'"

def update_link(text,scope):
    assert text.count('id="theOneUserDownload"')==1, "Missing unique User link in "+scope
    assert text.count('THE_ONE_USER_DEVHUB_DOWNLOAD_V1')==1, "Missing original user marker in "+scope
    if text.count(new_asset)==1 and old_asset not in text:
        print("DEVHUB_ALREADY_UPDATED="+scope)
        return text
    assert text.count(old_asset)==1 and new_asset not in text,"Unexpected user URL in "+scope
    anchor=text.index('id="theOneUserDownload"')
    start=text.rfind('<a',0,anchor)
    end=text.find('</a>',anchor)
    assert start>=0 and end>anchor
    link=text[start:end+4]
    assert old_asset in link and 'v1003' in link
    replacement=link.replace(old_asset,new_asset).replace('• v1003','• v1004')
    assert replacement!=link and '• v1004' in replacement
    text=text[:start]+replacement+text[end+4:]
    assert text.count(new_asset)==1 and text.count('id="theOneUserDownload"')==1
    print("DEVHUB_USER_VERSION_PATCHED="+scope)
    return text

def update_family(text):
    if new_family in text:
        assert text.count(new_family)==1 and new_fallback in text
        print("DEVHUB_ALREADY_UPDATED=family")
        return text
    assert text.count(old_family)==1 and text.count(old_fallback)==1,"Family User entry changed unexpectedly"
    for a,b in ((old_family,new_family),(old_fallback,new_fallback)):
        text=text.replace(a,b,1)
    assert text.count(new_family)==1 and text.count(new_fallback)==1
    print("DEVHUB_USER_VERSION_PATCHED=family")
    return text

pairs=[
    (sys.argv[1],sys.argv[4],"builds"),
    (sys.argv[2],sys.argv[5],"index"),
    (sys.argv[3],sys.argv[6],"family")
]
for old,new,kind in pairs:
    source=Path(old).read_text(encoding="utf-8")
    result=update_family(source) if kind=="family" else update_link(source,kind)
    Path(new).write_text(result,encoding="utf-8")
    print("DEVHUB_USER_CHECKED="+kind)
