#!/usr/bin/env python3
"""Surgically update the existing live RUTU Dev Hub page, preserving all other content.

Input = the actual live rutu.html downloaded over SSH.
Never replace the site with an older source snapshot.
"""
from pathlib import Path
import re
import sys

if len(sys.argv) != 3:
    raise SystemExit("usage: patch-rutu-devhub-20261015.py LIVE_HTML OUTPUT_HTML")
source,out=map(Path,sys.argv[1:])
page=source.read_text(encoding="utf-8")
assert "<h1>Bedrijfsapps & klantapp</h1>" in page, "Unexpected RUTU page"
assert "Rutu-BBQ-Android.apk" in page and "Rutu-BBQ-Bedrijf-Android.apk" in page, "RUTU downloads missing"
assert "Rutu-BBQ-Bedrijf-Windows.exe" in page, "Windows link missing"
assert page.count("<h2>Bedrijf Android</h2>")==1
assert page.count("<h2>Bedrijf Windows</h2>")==1
assert page.count("<h2>Klant Android</h2>")==1

def replace_one(old,new,label):
    global page
    if old == new: return
    if new in page and old not in page:
        print("ALREADY_CURRENT="+label)
        return
    if page.count(old)!=1: raise SystemExit("UNSAFE_RUTU_LIVE_ANCHOR="+label)
    page=page.replace(old,new)
    print("UPDATED="+label)

android_match=re.search(r"BUILD (\d+) • GESLAAGD",page)
if not android_match:raise SystemExit("NO_ANDROID_BUILD")
android_old=int(android_match.group(1))
if android_old>937:raise SystemExit("LIVE_ANDROID_NEWER_THAN_CANDIDATE")
replace_one("BUILD "+str(android_old)+" • GESLAAGD","BUILD 937 • GESLAAGD","Android version label")

windows_match=re.search(r"WINDOWS (\d+\.\d+\.\d+) • GESLAAGD",page)
if not windows_match:raise SystemExit("NO_WINDOWS_BUILD")
v=windows_match.group(1)
if tuple(map(int,v.split(".")))>(0,4,39):raise SystemExit("LIVE_WINDOWS_NEWER_THAN_CANDIDATE")
replace_one("WINDOWS "+v+" • GESLAAGD","WINDOWS 0.4.39 • GESLAAGD","Windows version label")
if v!="0.4.39":
    page=page.replace("Versie "+v+" •","Versie 0.4.39 •")
    page=page.replace("Android build "+str(android_old),"Android build 937")

replace_one("<span class=\"ok\">ACTUELE KLANTAPP</span>",
            "<span class=\"ok\">KLANT ANDROID • BUILD 937 • GESLAAGD</span>",
            "Customer build label")
replace_one("De bestaande klantomgeving blijft ongewijzigd.",
            "Nieuwste klantapp build 937 van 15 oktober 2026. De QR-code blijft naar deze vaste download wijzen.",
            "Customer QR and update note")
page=re.sub(r"Bijgewerkt \d{1,2} [A-Za-z]+(?: \d{4})?",
            "Bijgewerkt 15 oktober 2026",page,count=1)
for text in ("BUILD 937 • GESLAAGD","WINDOWS 0.4.39 • GESLAAGD",
             "KLANT ANDROID • BUILD 937 • GESLAAGD"):
    if page.count(text)!=1:raise SystemExit("DUPLICATE_OR_MISSING_LABEL="+text)
if page.count("Rutu-BBQ-Android.apk")!=1 or page.count("Rutu-BBQ-Bedrijf-Windows.exe")!=1:
    raise SystemExit("DOWNLOAD_LINK_DUPLICATED")
out.write_text(page,encoding="utf-8")
print("RUTU_DEVHUB_PATCH=ready")
