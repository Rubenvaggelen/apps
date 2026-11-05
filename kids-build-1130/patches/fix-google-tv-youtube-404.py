#!/usr/bin/env python3
"""Restore approved YouTube fallback clips when official channel RSS returns 404.

Applied after the v1002 Google TV menus patch. This overlays only the TV
variant and keeps its separate package identity and parental protection.
"""
from pathlib import Path

root = Path("kids-build-1130/buildroot/kids")
src = root / "src/main/java/com/gmailorg/kids"
tests = root / "src/test/java/com/gmailorg/kids"

source = src / "Sources.kt"
contents = source.read_text(encoding="utf-8")
old = """        val other = runCatching(fallback).getOrNull()
        return other ?: youtube"""
new = """        val other = runCatching(fallback).getOrNull()
        // Parent-approved provider results take precedence over curated clips.
        if (!other.isNullOrEmpty()) return other
        // HTTP 404 from an official YouTube RSS feed must not result in an empty
        // series. Use a small reviewed set from that publisher instead.
        val official = OfficialVideoFallback.forShow(show)
        if (official.isNotEmpty()) return official
        // No fabricated episodes for Oswald: it has no official YouTube source.
        return other ?: youtube"""
assert contents.count(old) == 1, "Missing expected YouTubeSource.preferredShow in Kids 1130"
source.write_text(contents.replace(old,new,1),encoding="utf-8")
(src / "OfficialVideoFallback.kt").write_text("package com.gmailorg.kids\n\n/**\n * Small, reviewed list of official NL-language videos for when YouTube's\n * public channel/playlist RSS endpoint returns HTTP 404 or HTTP 5xx.\n *\n * Only videos from verified official publisher accounts are listed.\n * These are pointers to YouTube's official embedded player; no copyrighted\n * video or stream is bundled or proxied.\n *\n * Reviewed on 2026-11-06 via YouTube oEmbed (publisher identity) and\n * thumbnail endpoint (HTTP 200):\n *  - Nick Jr. Nederlands: @NickJr.Nederlands\n *  - Peppa Pig Nederlands - Officieel Kanaal: @PeppaPigNederlandsOfficial\n *  - PAW Patrol Nederlands - Officiële Kanaal: @PAWPatrolNederlands\n *\n * No unknown channel search, no music category, and no unapproved Xtream data.\n */\nobject OfficialVideoFallback {\n    private data class OfficialVideo(val id: String, val title: String)\n\n    private val verified = mapOf(\n        \"dora\" to listOf(\n            OfficialVideo(\"C3JeQdSG1BY\", \"Dora: Dappere reddingsacties met Boots\"),\n            OfficialVideo(\"kCEEXbV8NAM\", \"Dora: Avonturen met Boots en vrienden\")\n        ),\n        \"peppa\" to listOf(\n            OfficialVideo(\"OEwSoxGKtJA\", \"Peppa Pig: Schaduwpoppen en Potato City\"),\n            OfficialVideo(\"iSV-Rgw99vs\", \"Peppa Pig: Het speelhuis\")\n        ),\n        \"paw\" to listOf(\n            OfficialVideo(\"uHr1ad_OeDU\", \"PAW Patrol: Pups en de piratenschat\"),\n            OfficialVideo(\"xLcpDV-CC-U\", \"PAW Patrol: Tracker redt de gouden kip\")\n        )\n    )\n\n    fun forShow(show: Show): List<KidItem> =\n        verified[show.key].orEmpty().map { video ->\n            KidItem(\n                kind = \"yt\",\n                id = video.id,\n                title = video.title,\n                thumb = YouTubeSource.thumb(video.id),\n                group = show.title\n            )\n        }\n}\n", encoding="utf-8")

tests.mkdir(parents=True, exist_ok=True)
(tests / "OfficialVideoFallbackTest.kt").write_text("""package com.gmailorg.kids

import org.junit.Assert.*
import org.junit.Test

class OfficialVideoFallbackTest {
    private val expected = mapOf(
        "dora" to setOf("C3JeQdSG1BY", "kCEEXbV8NAM"),
        "peppa" to setOf("OEwSoxGKtJA", "iSV-Rgw99vs"),
        "paw" to setOf("uHr1ad_OeDU", "xLcpDV-CC-U")
    )

    @Test fun verifiedPublisherVideosAppearAfter404() {
        for ((key, ids) in expected) {
            val show = Catalog.byKey(key)!!
            val found = YouTubeSource.preferredShow(
                show,
                fetch = { _, _ -> error("HTTP 404 from YouTube RSS") },
                fallback = { emptyList() }
            )!!
            assertEquals(ids, found.map { it.id }.toSet())
            assertTrue(found.all { it.kind == "yt" && it.group == show.title })
            assertTrue(found.all { it.thumb.contains(it.id) })
        }
    }

    @Test fun workingOfficialFeedTakesPrecedenceOverCuratedClips() {
        val show = Catalog.byKey("peppa")!!
        val fromFeed = KidItem("yt", "W3J0w8bc1Cg", "Nieuwe aflevering", "")
        val found = YouTubeSource.preferredShow(
            show,
            fetch = { _, _ -> listOf(fromFeed) },
            fallback = { error("Provider should not be queried") }
        )!!
        assertEquals(listOf(fromFeed), found)
    }

    @Test fun parentApprovedSeriesProviderTakesPrecedence() {
        val show = Catalog.byKey("dora")!!
        val fromParent = KidItem("ep", "20002", "Ouder-goedgekeurde Dora", "", source = "family1")
        val found = YouTubeSource.preferredShow(
            show,
            fetch = { _, _ -> error("HTTP 404") },
            fallback = { listOf(fromParent) }
        )!!
        assertEquals(listOf(fromParent), found)
    }

    @Test fun doNotInventOswaldOrMusicSources() {
        val oswald = Catalog.byKey("oswald")!!
        assertTrue(OfficialVideoFallback.forShow(oswald).isEmpty())
        assertTrue(YouTubeSource.preferredShow(oswald, fallback = { emptyList() }).isNullOrEmpty())
        assertFalse("songs" in KidsSections.all)
    }
}
""", encoding="utf-8")
print("Official NL publisher fallback enabled for Dora, Peppa and PAW Patrol; no music or unapproved sources.")
